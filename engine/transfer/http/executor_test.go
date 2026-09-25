package httptransfer

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"sync"
	"testing"

	"github.com/subhra74/xdm/engine/domain/failure"
	"github.com/subhra74/xdm/engine/domain/identity"
	"github.com/subhra74/xdm/engine/domain/resource"
	"github.com/subhra74/xdm/engine/store/checkpoint"
	store "github.com/subhra74/xdm/engine/store/sqlite"
	"github.com/subhra74/xdm/engine/transfer/arbitration"
)

type fakeLifecycle struct {
	mu     sync.Mutex
	states []string
	failed failure.Category
}

func (f *fakeLifecycle) add(v string) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.states = append(f.states, v)
}
func (f *fakeLifecycle) Start(context.Context) error    { f.add("running"); return nil }
func (f *fakeLifecycle) Complete(context.Context) error { f.add("produced_artifact"); return nil }
func (f *fakeLifecycle) Pause(context.Context) error    { f.add("paused"); return nil }
func (f *fakeLifecycle) Fail(_ context.Context, x failure.Failure) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.failed = x.Category
	f.states = append(f.states, "failed")
	return nil
}

type fakeCommitter struct{}

func (fakeCommitter) CommitBlock(_ context.Context, file checkpoint.File, req checkpoint.CommitRequest) (store.CheckpointBlockRecord, error) {
	n, err := file.WriteAt(req.Data, req.StartByte)
	if err != nil {
		return store.CheckpointBlockRecord{}, err
	}
	if n != len(req.Data) {
		return store.CheckpointBlockRecord{}, io.ErrShortWrite
	}
	if err := file.Sync(); err != nil {
		return store.CheckpointBlockRecord{}, err
	}
	return store.CheckpointBlockRecord{DownloadID: req.DownloadID, Generation: req.Generation, BlockIndex: req.BlockIndex, StartByte: req.StartByte, CommittedLength: int64(len(req.Data)), HashAlgorithm: "sha256"}, nil
}

type progressSink struct{ values []Progress }

func (p *progressSink) OnProgress(v Progress) { p.values = append(p.values, v) }

type countingLimiter struct {
	calls int
	bytes int
}

func (l *countingLimiter) WaitN(_ context.Context, n int) error {
	l.calls++
	l.bytes += n
	return nil
}

type errorDoer struct{ err error }

func (d errorDoer) Do(*http.Request) (*http.Response, error) { return nil, d.err }

func ids(t *testing.T) (identity.DownloadID, identity.AttemptGeneration, resource.Identity) {
	t.Helper()
	dl, err := identity.ParseDownloadID("dl_00000000000000000000000000000000")
	if err != nil {
		t.Fatal(err)
	}
	g, _ := identity.NewAttemptGeneration(1)
	r, _ := resource.DeriveIdentity("test", "logical")
	return dl, g, r
}
func planFor(t *testing.T, url string, length int64) (ExecutePlan, *os.File, *fakeLifecycle) {
	t.Helper()
	dl, g, r := ids(t)
	f, err := os.CreateTemp(t.TempDir(), "stage-*.bin")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { f.Close() })
	rep, _ := RepresentationFromProbe(r, ProbeResult{EffectiveURL: url, Length: &length, ETag: `"v1"`})
	life := &fakeLifecycle{}
	return ExecutePlan{URL: url, Representation: rep, DownloadID: dl, Generation: g, BufferBytes: 4096, CheckpointBytes: 4096, File: f, Committer: fakeCommitter{}, Lifecycle: life}, f, life
}

func TestExecuteNormalTransferAndProgress(t *testing.T) {
	data := make([]byte, 9000)
	for i := range data {
		data[i] = byte(i % 251)
	}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Length", "9000")
		_, _ = w.Write(data)
	}))
	defer srv.Close()
	plan, f, life := planFor(t, srv.URL, int64(len(data)))
	p := &progressSink{}
	plan.Progress = p
	got, err := Execute(context.Background(), srv.Client(), plan)
	if err != nil {
		t.Fatal(err)
	}
	if got.Committed != 9000 || got.Blocks != 3 || len(p.values) != 3 {
		t.Fatalf("result=%+v progress=%d", got, len(p.values))
	}
	raw, _ := os.ReadFile(f.Name())
	if string(raw) != string(data) {
		t.Fatal("staging mismatch")
	}
	if life.states[len(life.states)-1] != "produced_artifact" {
		t.Fatalf("states=%v", life.states)
	}
}

func TestExecuteRejectsEarlyEOFAndOversize(t *testing.T) {
	t.Run("early", func(t *testing.T) {
		srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			w.Header().Set("Content-Length", "5")
			_, _ = w.Write([]byte("12345"))
		}))
		defer srv.Close()
		plan, _, life := planFor(t, srv.URL, 10)
		_, err := Execute(context.Background(), srv.Client(), plan)
		if err == nil {
			t.Fatal("expected error")
		}
		if life.failed != failure.RangeContradiction {
			t.Fatalf("failed=%s err=%v", life.failed, err)
		}
	})
	t.Run("oversize", func(t *testing.T) {
		srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { _, _ = w.Write([]byte("12345678901")) }))
		defer srv.Close()
		plan, _, life := planFor(t, srv.URL, 10)
		_, err := Execute(context.Background(), srv.Client(), plan)
		if err == nil {
			t.Fatal("expected error")
		}
		if life.failed != failure.RangeContradiction {
			t.Fatalf("failed=%s err=%v", life.failed, err)
		}
	})
}

func TestExecutePauseAndRestartFromCommittedCheckpoint(t *testing.T) {
	data := []byte("abcdefghij")
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Range") == "bytes=4-" {
			w.Header().Set("Content-Range", "bytes 4-9/10")
			w.WriteHeader(http.StatusPartialContent)
			_, _ = w.Write(data[4:])
			return
		}
		_, _ = w.Write(data)
	}))
	defer srv.Close()
	plan, f, life := planFor(t, srv.URL, 10)
	plan.CheckpointBytes = 4096 // one final block would not pause
	// use 4 KiB minimum with a larger payload for checkpoint-boundary pause
	big := make([]byte, 8192)
	copy(big, data)
	srv.Close()
	srv = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Range") == "bytes=4096-" {
			w.Header().Set("Content-Range", "bytes 4096-8191/8192")
			w.WriteHeader(http.StatusPartialContent)
			_, _ = w.Write(big[4096:])
			return
		}
		_, _ = w.Write(big)
	}))
	defer srv.Close()
	_, _, res := ids(t)
	rep, _ := RepresentationFromProbe(res, ProbeResult{EffectiveURL: srv.URL, Length: func() *int64 { v := int64(8192); return &v }(), ETag: `"v1"`})
	plan.URL = srv.URL
	plan.Representation = rep
	calls := 0
	plan.PauseRequested = func() bool { calls++; return calls == 1 }
	got, err := Execute(context.Background(), srv.Client(), plan)
	if !errors.Is(err, ErrPaused) {
		t.Fatalf("err=%v", err)
	}
	if got.Committed != 4096 || life.states[len(life.states)-1] != "paused" {
		t.Fatalf("got=%+v states=%v", got, life.states)
	}
	life2 := &fakeLifecycle{}
	plan2 := plan
	plan2.StartOffset = 4096
	plan2.StartBlockIndex = 1
	plan2.Lifecycle = life2
	plan2.PauseRequested = nil
	got2, err := Execute(context.Background(), srv.Client(), plan2)
	if err != nil {
		t.Fatal(err)
	}
	if got2.Committed != 8192 {
		t.Fatalf("got2=%+v", got2)
	}
	raw, _ := os.ReadFile(f.Name())
	if len(raw) != 8192 || string(raw) != string(big) {
		t.Fatal("restart staging mismatch")
	}
}

func TestExecuteInvokesLimiterForStreamedBytes(t *testing.T) {
	data := make([]byte, 7000)
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { _, _ = w.Write(data) }))
	defer srv.Close()
	plan, _, _ := planFor(t, srv.URL, int64(len(data)))
	limiter := &countingLimiter{}
	plan.Limiter = limiter
	if _, err := Execute(context.Background(), srv.Client(), plan); err != nil {
		t.Fatal(err)
	}
	if limiter.calls == 0 || limiter.bytes != len(data) {
		t.Fatalf("limiter calls=%d bytes=%d", limiter.calls, limiter.bytes)
	}
}

func TestExecuteCancellationAndNetworkFailureAreTyped(t *testing.T) {
	plan, _, life := planFor(t, "https://example.invalid/file", 1)
	_, err := Execute(context.Background(), errorDoer{errors.New("network down")}, plan)
	if err == nil || life.failed != failure.NetworkUnavailable {
		t.Fatalf("err=%v failed=%s", err, life.failed)
	}

	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { <-r.Context().Done() }))
	defer srv.Close()
	plan, _, life = planFor(t, srv.URL, 1)
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	_, err = Execute(ctx, srv.Client(), plan)
	if err == nil || life.failed != failure.Cancelled {
		t.Fatalf("err=%v failed=%s", err, life.failed)
	}
}

func TestExecuteUsesCentralResourceArbiter(t *testing.T) {
	data := []byte(strings.Repeat("resource-arbiter", 1024))
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Length", fmt.Sprintf("%d", len(data)))
		_, _ = w.Write(data)
	}))
	defer srv.Close()
	plan, _, _ := planFor(t, srv.URL, int64(len(data)))
	arb, err := arbitration.New(arbitration.Limits{GlobalConnections: 1, DefaultHostConnections: 1, DefaultDownloadConnections: 1, GlobalBytesPerSecond: 16 << 20})
	if err != nil {
		t.Fatal(err)
	}
	h, err := arb.Bind(arbitration.Key{DownloadID: plan.DownloadID.String(), Host: "127.0.0.1"})
	if err != nil {
		t.Fatal(err)
	}
	plan.Resources = h
	if _, err = Execute(context.Background(), srv.Client(), plan); err != nil {
		t.Fatal(err)
	}
	m := arb.Metrics()
	if m.ConnectionAcquisitions != 1 || m.ActiveConnections != 0 || m.BandwidthBytes != int64(len(data)) {
		t.Fatalf("resource metrics=%+v", m)
	}
}
