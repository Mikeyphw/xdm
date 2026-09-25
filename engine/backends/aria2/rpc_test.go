package aria2

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"
)

type recordedRequest struct {
	JSONRPC string          `json:"jsonrpc"`
	ID      string          `json:"id"`
	Method  string          `json:"method"`
	Params  json.RawMessage `json:"params"`
}

type mockRPC struct {
	t      *testing.T
	mu     sync.Mutex
	seen   []recordedRequest
	handle func(recordedRequest) (int, string, time.Duration)
}

func (m *mockRPC) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		m.t.Errorf("method=%s", r.Method)
	}
	if got := r.Header.Get("Content-Type"); !strings.HasPrefix(got, "application/json") {
		m.t.Errorf("content-type=%q", got)
	}
	var req recordedRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		m.t.Errorf("decode: %v", err)
		w.WriteHeader(http.StatusBadRequest)
		return
	}
	m.mu.Lock()
	m.seen = append(m.seen, req)
	m.mu.Unlock()
	status, body, delay := m.handle(req)
	if delay > 0 {
		time.Sleep(delay)
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_, _ = w.Write([]byte(body))
}

func clientFor(t *testing.T, handler func(recordedRequest) (int, string, time.Duration), secret string, timeout time.Duration) (*Client, *mockRPC, func()) {
	t.Helper()
	mock := &mockRPC{t: t, handle: handler}
	srv := httptest.NewServer(mock)
	client, err := NewClient(srv.URL, secret, srv.Client(), timeout)
	if err != nil {
		srv.Close()
		t.Fatal(err)
	}
	return client, mock, srv.Close
}

func response(req recordedRequest, result string) string {
	return fmt.Sprintf(`{"jsonrpc":"2.0","id":%q,"result":%s}`, req.ID, result)
}

func TestInvokeCorrelatesIDsAndAddsSecret(t *testing.T) {
	client, mock, closeFn := clientFor(t, func(req recordedRequest) (int, string, time.Duration) {
		var params []any
		if err := json.Unmarshal(req.Params, &params); err != nil {
			t.Fatal(err)
		}
		if len(params) != 1 || params[0] != "token:secret" {
			t.Fatalf("params=%v", params)
		}
		return http.StatusOK, response(req, `{"version":"1.37.0","enabledFeatures":["HTTPS","GZip"]}`), 0
	}, "secret", time.Second)
	defer closeFn()
	v, err := client.GetVersion(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if v.Version != "1.37.0" || len(v.EnabledFeatures) != 2 {
		t.Fatalf("version=%+v", v)
	}
	client.muTestSeen(t, mock, 1)
}

func (c *Client) muTestSeen(t *testing.T, mock *mockRPC, want int) {
	t.Helper()
	mock.mu.Lock()
	defer mock.mu.Unlock()
	if len(mock.seen) != want {
		t.Fatalf("seen=%d want=%d", len(mock.seen), want)
	}
}

func TestCorrelationMismatch(t *testing.T) {
	client, _, closeFn := clientFor(t, func(req recordedRequest) (int, string, time.Duration) {
		return http.StatusOK, `{"jsonrpc":"2.0","id":"999","result":{"version":"1"}}`, 0
	}, "", time.Second)
	defer closeFn()
	_, err := client.GetVersion(context.Background())
	if !errors.Is(err, ErrCorrelation) {
		t.Fatalf("err=%v", err)
	}
}

func TestCorrelationRequiresSameJSONIDType(t *testing.T) {
	client, _, closeFn := clientFor(t, func(req recordedRequest) (int, string, time.Duration) {
		return http.StatusOK, `{"jsonrpc":"2.0","id":1,"result":{"version":"1"}}`, 0
	}, "", time.Second)
	defer closeFn()
	_, err := client.GetVersion(context.Background())
	if !errors.Is(err, ErrMalformedResponse) {
		t.Fatalf("err=%v", err)
	}
}

func TestRejectsResultAndErrorTogether(t *testing.T) {
	client, _, closeFn := clientFor(t, func(req recordedRequest) (int, string, time.Duration) {
		return http.StatusOK, fmt.Sprintf(`{"jsonrpc":"2.0","id":%q,"result":{"version":"1"},"error":{"code":1,"message":"bad"}}`, req.ID), 0
	}, "", time.Second)
	defer closeFn()
	_, err := client.GetVersion(context.Background())
	if !errors.Is(err, ErrMalformedResponse) {
		t.Fatalf("err=%v", err)
	}
}

func TestMalformedAndHTTPFailuresDoNotEchoBody(t *testing.T) {
	for _, tc := range []struct {
		name   string
		status int
		body   string
		want   error
	}{
		{"malformed", http.StatusOK, `not-json-secret`, ErrMalformedResponse},
		{"http", http.StatusUnauthorized, `secret-body`, ErrHTTPStatus},
	} {
		t.Run(tc.name, func(t *testing.T) {
			client, _, closeFn := clientFor(t, func(req recordedRequest) (int, string, time.Duration) { return tc.status, tc.body, 0 }, "secret", time.Second)
			defer closeFn()
			_, err := client.GetVersion(context.Background())
			if !errors.Is(err, tc.want) {
				t.Fatalf("err=%v", err)
			}
			if strings.Contains(fmt.Sprint(err), "secret-body") || strings.Contains(fmt.Sprint(err), "not-json-secret") {
				t.Fatalf("error leaked body: %v", err)
			}
		})
	}
}

func TestTimeout(t *testing.T) {
	client, _, closeFn := clientFor(t, func(req recordedRequest) (int, string, time.Duration) {
		return http.StatusOK, response(req, `{"version":"1"}`), 80 * time.Millisecond
	}, "", 20*time.Millisecond)
	defer closeFn()
	_, err := client.GetVersion(context.Background())
	if !errors.Is(err, ErrTimeout) {
		t.Fatalf("err=%v", err)
	}
}

func TestRemoteErrorRedactsSecret(t *testing.T) {
	client, _, closeFn := clientFor(t, func(req recordedRequest) (int, string, time.Duration) {
		return http.StatusOK, fmt.Sprintf(`{"jsonrpc":"2.0","id":%q,"error":{"code":1,"message":"bad token:secret secret"}}`, req.ID), 0
	}, "secret", time.Second)
	defer closeFn()
	_, err := client.TellStatus(context.Background(), "missing")
	var rpcErr *RPCError
	if !errors.As(err, &rpcErr) || rpcErr.Code != 1 {
		t.Fatalf("err=%v", err)
	}
	if strings.Contains(rpcErr.Message, "secret") {
		t.Fatalf("message leaked secret: %q", rpcErr.Message)
	}
}

func TestAddURIOptionEncoding(t *testing.T) {
	client, _, closeFn := clientFor(t, func(req recordedRequest) (int, string, time.Duration) {
		if req.Method != "aria2.addUri" {
			t.Fatalf("method=%s", req.Method)
		}
		var params []json.RawMessage
		if err := json.Unmarshal(req.Params, &params); err != nil {
			t.Fatal(err)
		}
		if len(params) != 2 {
			t.Fatalf("params=%s", req.Params)
		}
		var options map[string]any
		if err := json.Unmarshal(params[1], &options); err != nil {
			t.Fatal(err)
		}
		if options["pause"] != "true" || options["split"] != "4" {
			t.Fatalf("options=%v", options)
		}
		headers, ok := options["header"].([]any)
		if !ok || len(headers) != 1 || headers[0] != "X-Test: value" {
			t.Fatalf("headers=%#v", options["header"])
		}
		return http.StatusOK, response(req, `"gid-1"`), 0
	}, "", time.Second)
	defer closeFn()
	gid, err := client.AddURI(context.Background(), []string{"https://example.test/a", "https://mirror.test/a"}, Options{
		"pause":  StringOption("true"),
		"split":  StringOption("4"),
		"header": StringsOption("X-Test: value"),
	})
	if err != nil || gid != "gid-1" {
		t.Fatalf("gid=%q err=%v", gid, err)
	}
}

func TestTaskStatusAndLists(t *testing.T) {
	client, _, closeFn := clientFor(t, func(req recordedRequest) (int, string, time.Duration) {
		task := `{"gid":"g1","status":"active","totalLength":"100","completedLength":"25","downloadSpeed":"5","dir":"/tmp","files":[{"path":"/tmp/a","uris":[{"uri":"https://e/a","status":"used"}]}]}`
		switch req.Method {
		case "aria2.tellStatus":
			return http.StatusOK, response(req, task), 0
		case "aria2.tellActive", "aria2.tellWaiting", "aria2.tellStopped":
			return http.StatusOK, response(req, `[`+task+`]`), 0
		default:
			t.Fatalf("method=%s", req.Method)
			return 500, "", 0
		}
	}, "", time.Second)
	defer closeFn()
	task, err := client.TellStatus(context.Background(), "g1")
	if err != nil || task.Status != StatusActive || task.CompletedLength != 25 || task.TotalLength != 100 {
		t.Fatalf("task=%+v err=%v", task, err)
	}
	for _, call := range []func() ([]Task, error){
		func() ([]Task, error) { return client.TellActive(context.Background()) },
		func() ([]Task, error) { return client.TellWaiting(context.Background(), 0, 2000) },
		func() ([]Task, error) { return client.TellStopped(context.Background(), 0, 10) },
	} {
		items, err := call()
		if err != nil || len(items) != 1 || items[0].GID != "g1" {
			t.Fatalf("items=%+v err=%v", items, err)
		}
	}
}

func TestControlsOptionsSessionAndShutdown(t *testing.T) {
	client, mock, closeFn := clientFor(t, func(req recordedRequest) (int, string, time.Duration) {
		switch req.Method {
		case "aria2.pause", "aria2.forcePause", "aria2.unpause", "aria2.remove", "aria2.forceRemove":
			return http.StatusOK, response(req, `"g1"`), 0
		case "aria2.changeOption", "aria2.changeGlobalOption", "aria2.saveSession", "aria2.shutdown", "aria2.forceShutdown":
			return http.StatusOK, response(req, `"OK"`), 0
		case "aria2.getGlobalOption":
			return http.StatusOK, response(req, `{"max-concurrent-downloads":"5"}`), 0
		default:
			t.Fatalf("method=%s", req.Method)
			return 500, "", 0
		}
	}, "", time.Second)
	defer closeFn()
	ctx := context.Background()
	for _, call := range []func() error{
		func() error { return client.Pause(ctx, "g1", false) },
		func() error { return client.Pause(ctx, "g1", true) },
		func() error { return client.Unpause(ctx, "g1") },
		func() error { return client.Remove(ctx, "g1", false) },
		func() error { return client.Remove(ctx, "g1", true) },
		func() error { return client.ChangeOptions(ctx, "g1", Options{"split": StringOption("4")}) },
		func() error {
			return client.ChangeGlobalOptions(ctx, Options{"max-concurrent-downloads": StringOption("5")})
		},
		func() error { return client.SaveSession(ctx) },
		func() error { return client.Shutdown(ctx, false) },
		func() error { return client.Shutdown(ctx, true) },
	} {
		if err := call(); err != nil {
			t.Fatal(err)
		}
	}
	opts, err := client.GetGlobalOptions(ctx)
	if err != nil || opts["max-concurrent-downloads"] != "5" {
		t.Fatalf("opts=%v err=%v", opts, err)
	}
	client.muTestSeen(t, mock, 11)
}

func TestHealth(t *testing.T) {
	client, _, closeFn := clientFor(t, func(req recordedRequest) (int, string, time.Duration) {
		return http.StatusOK, response(req, `{"version":"1.37.0","enabledFeatures":["HTTPS"]}`), 0
	}, "", time.Second)
	defer closeFn()
	health, err := client.Health(context.Background())
	if err != nil || !health.Healthy || health.Version != "1.37.0" || health.Latency < 0 {
		t.Fatalf("health=%+v err=%v", health, err)
	}
}

func TestEndpointAndOptionValidation(t *testing.T) {
	for _, endpoint := range []string{"", "unix:///tmp/aria2", "http://user:pass@127.0.0.1:6800/jsonrpc", "http://127.0.0.1:6800/jsonrpc?token=secret", "relative"} {
		if _, err := NewClient(endpoint, "", nil, time.Second); !errors.Is(err, ErrInvalidEndpoint) {
			t.Fatalf("endpoint=%q err=%v", endpoint, err)
		}
	}
	client, _, closeFn := clientFor(t, func(req recordedRequest) (int, string, time.Duration) {
		return http.StatusOK, response(req, `"g"`), 0
	}, "", time.Second)
	defer closeFn()
	if _, err := client.AddURI(context.Background(), []string{"https://example.test/a"}, Options{"header": StringsOption("X: ok\r\nBad: yes")}); !errors.Is(err, ErrInvalidArgument) {
		t.Fatalf("err=%v", err)
	}
}
