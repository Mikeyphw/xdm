package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"strconv"
	"strings"
	"sync"
	"time"

	aria2 "github.com/subhra74/xdm/engine/backends/aria2"
)

type aria2LabRequest struct {
	JSONRPC string            `json:"jsonrpc"`
	ID      string            `json:"id"`
	Method  string            `json:"method"`
	Params  []json.RawMessage `json:"params"`
}

type aria2LabServer struct {
	mu      sync.Mutex
	seenIDs []string
	handle  func(aria2LabRequest) (status int, body string, delay time.Duration)
}

func (s *aria2LabServer) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	var request aria2LabRequest
	if r.Method != http.MethodPost {
		w.WriteHeader(http.StatusMethodNotAllowed)
		return
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, 1<<20)).Decode(&request); err != nil {
		w.WriteHeader(http.StatusBadRequest)
		return
	}
	s.mu.Lock()
	s.seenIDs = append(s.seenIDs, request.ID)
	s.mu.Unlock()
	status, body, delay := s.handle(request)
	if delay > 0 {
		time.Sleep(delay)
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_, _ = w.Write([]byte(body))
}

func aria2LabResponse(request aria2LabRequest, result string) string {
	return fmt.Sprintf(`{"jsonrpc":"2.0","id":%q,"result":%s}`, request.ID, result)
}

func withAria2LabClient(secret string, timeout time.Duration, handler func(aria2LabRequest) (int, string, time.Duration), fn func(*aria2.Client, *aria2LabServer) error) error {
	mock := &aria2LabServer{handle: handler}
	server := httptest.NewServer(mock)
	defer server.Close()
	client, err := aria2.NewClient(server.URL, secret, server.Client(), timeout)
	if err != nil {
		return err
	}
	return fn(client, mock)
}

func aria2MockServerCase() error {
	return withAria2LabClient("rpc-secret", time.Second, func(request aria2LabRequest) (int, string, time.Duration) {
		if request.JSONRPC != "2.0" || request.Method != "aria2.getVersion" || len(request.Params) != 1 {
			return 500, `{}`, 0
		}
		var token string
		_ = json.Unmarshal(request.Params[0], &token)
		if token != "token:rpc-secret" {
			return 401, `{}`, 0
		}
		return http.StatusOK, aria2LabResponse(request, `{"version":"1.37.0","enabledFeatures":["HTTPS","GZip"]}`), 0
	}, func(client *aria2.Client, _ *aria2LabServer) error {
		version, err := client.GetVersion(context.Background())
		if err != nil {
			return err
		}
		if version.Version != "1.37.0" || len(version.EnabledFeatures) != 2 {
			return fmt.Errorf("version=%+v", version)
		}
		return nil
	})
}

func aria2CorrelationCase() error {
	return withAria2LabClient("", time.Second, func(request aria2LabRequest) (int, string, time.Duration) {
		return http.StatusOK, `{"jsonrpc":"2.0","id":"stale-id","result":{"version":"1"}}`, 0
	}, func(client *aria2.Client, _ *aria2LabServer) error {
		_, err := client.GetVersion(context.Background())
		if !errors.Is(err, aria2.ErrCorrelation) {
			return fmt.Errorf("correlation err=%v", err)
		}
		return nil
	})
}

func aria2MalformedCase() error {
	return withAria2LabClient("", time.Second, func(request aria2LabRequest) (int, string, time.Duration) {
		return http.StatusOK, `{"jsonrpc":"2.0","id":`, 0
	}, func(client *aria2.Client, _ *aria2LabServer) error {
		_, err := client.GetVersion(context.Background())
		if !errors.Is(err, aria2.ErrMalformedResponse) {
			return fmt.Errorf("malformed err=%v", err)
		}
		return nil
	})
}

func aria2TimeoutCase() error {
	return withAria2LabClient("", 20*time.Millisecond, func(request aria2LabRequest) (int, string, time.Duration) {
		return http.StatusOK, aria2LabResponse(request, `{"version":"1"}`), 100 * time.Millisecond
	}, func(client *aria2.Client, _ *aria2LabServer) error {
		_, err := client.GetVersion(context.Background())
		if !errors.Is(err, aria2.ErrTimeout) {
			return fmt.Errorf("timeout err=%v", err)
		}
		return nil
	})
}

func aria2UnknownGIDCase() error {
	return withAria2LabClient("rpc-secret", time.Second, func(request aria2LabRequest) (int, string, time.Duration) {
		return http.StatusOK, fmt.Sprintf(`{"jsonrpc":"2.0","id":%q,"error":{"code":1,"message":"GID not found; token:rpc-secret"}}`, request.ID), 0
	}, func(client *aria2.Client, _ *aria2LabServer) error {
		_, err := client.TellStatus(context.Background(), "missing-gid")
		var rpcErr *aria2.RPCError
		if !errors.As(err, &rpcErr) || rpcErr.Code != 1 {
			return fmt.Errorf("unknown gid err=%v", err)
		}
		if strings.Contains(rpcErr.Message, "rpc-secret") {
			return fmt.Errorf("remote error leaked token: %q", rpcErr.Message)
		}
		return nil
	})
}

func aria2OptionEncodingCase() error {
	return withAria2LabClient("", time.Second, func(request aria2LabRequest) (int, string, time.Duration) {
		if request.Method != "aria2.addUri" || len(request.Params) != 2 {
			return 500, `{}`, 0
		}
		var options map[string]any
		if err := json.Unmarshal(request.Params[1], &options); err != nil {
			return 500, `{}`, 0
		}
		if options["pause"] != "true" || options["split"] != "4" {
			return 500, `{}`, 0
		}
		headers, ok := options["header"].([]any)
		if !ok || len(headers) != 1 || headers[0] != "X-Lab: value" {
			return 500, `{}`, 0
		}
		return http.StatusOK, aria2LabResponse(request, `"lab-gid"`), 0
	}, func(client *aria2.Client, _ *aria2LabServer) error {
		gid, err := client.AddURI(context.Background(), []string{"https://example.test/file", "https://mirror.test/file"}, aria2.Options{
			"pause":  aria2.StringOption("true"),
			"split":  aria2.StringOption("4"),
			"header": aria2.StringsOption("X-Lab: value"),
		})
		if err != nil || gid != "lab-gid" {
			return fmt.Errorf("gid=%q err=%v", gid, err)
		}
		return nil
	})
}

func aria2TaskListsCase() error {
	return withAria2LabClient("", time.Second, func(request aria2LabRequest) (int, string, time.Duration) {
		task := `{"gid":"g1","status":"active","totalLength":"100","completedLength":"40","downloadSpeed":"12","dir":"/tmp","files":[{"path":"/tmp/file","uris":[{"uri":"https://example.test/file","status":"used"}]}]}`
		switch request.Method {
		case "aria2.tellStatus":
			return http.StatusOK, aria2LabResponse(request, task), 0
		case "aria2.tellActive", "aria2.tellWaiting", "aria2.tellStopped":
			return http.StatusOK, aria2LabResponse(request, `[`+task+`]`), 0
		default:
			return 500, `{}`, 0
		}
	}, func(client *aria2.Client, _ *aria2LabServer) error {
		ctx := context.Background()
		task, err := client.TellStatus(ctx, "g1")
		if err != nil || task.Status != aria2.StatusActive || task.CompletedLength != 40 {
			return fmt.Errorf("task=%+v err=%v", task, err)
		}
		for _, call := range []func() ([]aria2.Task, error){
			func() ([]aria2.Task, error) { return client.TellActive(ctx) },
			func() ([]aria2.Task, error) { return client.TellWaiting(ctx, 0, 10) },
			func() ([]aria2.Task, error) { return client.TellStopped(ctx, 0, 10) },
		} {
			items, err := call()
			if err != nil || len(items) != 1 || items[0].GID != "g1" {
				return fmt.Errorf("items=%+v err=%v", items, err)
			}
		}
		return nil
	})
}

func aria2ControlsCase() error {
	return withAria2LabClient("", time.Second, func(request aria2LabRequest) (int, string, time.Duration) {
		switch request.Method {
		case "aria2.pause", "aria2.forcePause", "aria2.unpause", "aria2.remove", "aria2.forceRemove":
			return http.StatusOK, aria2LabResponse(request, `"g1"`), 0
		case "aria2.changeOption", "aria2.changeGlobalOption", "aria2.saveSession":
			return http.StatusOK, aria2LabResponse(request, `"OK"`), 0
		case "aria2.getGlobalOption":
			return http.StatusOK, aria2LabResponse(request, `{"max-concurrent-downloads":"3"}`), 0
		default:
			return 500, `{}`, 0
		}
	}, func(client *aria2.Client, _ *aria2LabServer) error {
		ctx := context.Background()
		calls := []func() error{
			func() error { return client.Pause(ctx, "g1", false) },
			func() error { return client.Pause(ctx, "g1", true) },
			func() error { return client.Unpause(ctx, "g1") },
			func() error { return client.Remove(ctx, "g1", false) },
			func() error { return client.Remove(ctx, "g1", true) },
			func() error { return client.ChangeOptions(ctx, "g1", aria2.Options{"split": aria2.StringOption("4")}) },
			func() error {
				return client.ChangeGlobalOptions(ctx, aria2.Options{"max-concurrent-downloads": aria2.StringOption("3")})
			},
			func() error { return client.SaveSession(ctx) },
		}
		for _, call := range calls {
			if err := call(); err != nil {
				return err
			}
		}
		opts, err := client.GetGlobalOptions(ctx)
		if err != nil || opts["max-concurrent-downloads"] != "3" {
			return fmt.Errorf("opts=%v err=%v", opts, err)
		}
		return nil
	})
}

func aria2HealthShutdownCase() error {
	return withAria2LabClient("", time.Second, func(request aria2LabRequest) (int, string, time.Duration) {
		switch request.Method {
		case "aria2.getVersion":
			return http.StatusOK, aria2LabResponse(request, `{"version":"1.37.0","enabledFeatures":["HTTPS"]}`), 0
		case "aria2.shutdown", "aria2.forceShutdown":
			return http.StatusOK, aria2LabResponse(request, `"OK"`), 0
		default:
			return 500, `{}`, 0
		}
	}, func(client *aria2.Client, _ *aria2LabServer) error {
		health, err := client.Health(context.Background())
		if err != nil || !health.Healthy || health.Version != "1.37.0" {
			return fmt.Errorf("health=%+v err=%v", health, err)
		}
		if err := client.Shutdown(context.Background(), true); err != nil {
			return err
		}
		return nil
	})
}

func aria2RealSmokeCase() error {
	binary, err := exec.LookPath("aria2c")
	if err != nil {
		return nil // explicitly optional: real smoke only where aria2c is installed.
	}
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return err
	}
	port := listener.Addr().(*net.TCPAddr).Port
	_ = listener.Close()

	tempDir, err := osMkdirTemp("xgo41-aria2-")
	if err != nil {
		return err
	}
	defer osRemoveAll(tempDir)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	cmd := exec.CommandContext(ctx, binary,
		"--enable-rpc=true",
		"--rpc-listen-all=false",
		"--rpc-listen-port="+strconv.Itoa(port),
		"--rpc-secret=xgo41-smoke",
		"--no-conf=true",
		"--disable-ipv6=true",
		"--dir="+tempDir,
		"--console-log-level=warn",
	)
	if err := cmd.Start(); err != nil {
		return fmt.Errorf("start aria2c: %w", err)
	}
	waitCh := make(chan error, 1)
	go func() { waitCh <- cmd.Wait() }()
	defer func() {
		cancel()
		select {
		case <-waitCh:
		case <-time.After(time.Second):
			if cmd.Process != nil {
				_ = cmd.Process.Kill()
			}
		}
	}()
	client, err := aria2.NewClient(fmt.Sprintf("http://127.0.0.1:%d/jsonrpc", port), "xgo41-smoke", nil, 300*time.Millisecond)
	if err != nil {
		return err
	}
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		health, healthErr := client.Health(context.Background())
		if healthErr == nil && health.Healthy && health.Version != "" {
			_ = client.Shutdown(context.Background(), true)
			return nil
		}
		select {
		case processErr := <-waitCh:
			return fmt.Errorf("aria2c exited before health probe: %v", processErr)
		case <-time.After(50 * time.Millisecond):
		}
	}
	return errors.New("aria2c did not become healthy")
}

// wrappers keep os package out of the RPC client while still allowing the
// target-local optional real-process smoke lab.
var osMkdirTemp = func(prefix string) (string, error) { return os.MkdirTemp("", prefix) }
var osRemoveAll = os.RemoveAll

func runAria2RPCLab() report {
	cases := []caseResult{
		runCase("mock_json_rpc_server", aria2MockServerCase),
		runCase("request_id_correlation", aria2CorrelationCase),
		runCase("malformed_response", aria2MalformedCase),
		runCase("timeout", aria2TimeoutCase),
		runCase("unknown_gid", aria2UnknownGIDCase),
		runCase("option_encoding", aria2OptionEncodingCase),
		runCase("task_lists", aria2TaskListsCase),
		runCase("control_and_global_options", aria2ControlsCase),
		runCase("health_and_shutdown", aria2HealthShutdownCase),
		runCase("real_aria2_smoke_if_available", aria2RealSmokeCase),
	}
	r := report{SchemaVersion: 1, Mode: "aria2-rpc", Status: "pass", Total: len(cases), Cases: cases}
	for _, c := range cases {
		if c.Passed {
			r.Passed++
		}
	}
	if r.Passed != r.Total {
		r.Status = "fail"
	}
	return r
}
