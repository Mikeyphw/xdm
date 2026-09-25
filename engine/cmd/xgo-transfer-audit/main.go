package main

import (
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"

	"github.com/subhra74/xdm/engine/domain/failure"
	"github.com/subhra74/xdm/engine/domain/identity"
	"github.com/subhra74/xdm/engine/domain/resource"
	"github.com/subhra74/xdm/engine/store/checkpoint"
	store "github.com/subhra74/xdm/engine/store/sqlite"
	httptransfer "github.com/subhra74/xdm/engine/transfer/http"
)

type report struct {
	SchemaVersion                  int `json:"schema_version"`
	Status, Mode                   string
	Checks, Capabilities, Fixtures []string
}
type lifecycle struct{ states []string }

func (l *lifecycle) Start(context.Context) error { l.states = append(l.states, "running"); return nil }
func (l *lifecycle) Complete(context.Context) error {
	l.states = append(l.states, "produced_artifact")
	return nil
}
func (l *lifecycle) Pause(context.Context) error { l.states = append(l.states, "paused"); return nil }
func (l *lifecycle) Fail(_ context.Context, f failure.Failure) error {
	l.states = append(l.states, "failed:"+string(f.Category))
	return nil
}

type committer struct{}

func (committer) CommitBlock(_ context.Context, file checkpoint.File, req checkpoint.CommitRequest) (store.CheckpointBlockRecord, error) {
	n, e := file.WriteAt(req.Data, req.StartByte)
	if e != nil {
		return store.CheckpointBlockRecord{}, e
	}
	if n != len(req.Data) {
		return store.CheckpointBlockRecord{}, io.ErrShortWrite
	}
	if e = file.Sync(); e != nil {
		return store.CheckpointBlockRecord{}, e
	}
	return store.CheckpointBlockRecord{DownloadID: req.DownloadID, Generation: req.Generation, BlockIndex: req.BlockIndex, StartByte: req.StartByte, CommittedLength: int64(len(req.Data))}, nil
}

func ids() (identity.DownloadID, identity.AttemptGeneration, resource.Identity) {
	dl, _ := identity.ParseDownloadID("dl_00000000000000000000000000000077")
	g, _ := identity.NewAttemptGeneration(1)
	r, _ := resource.DeriveIdentity("audit", "transfer")
	return dl, g, r
}
func requireFixture(root, id string) error {
	b, e := os.ReadFile(filepath.Join(root, "engine/testdata/http/"+id+".json"))
	if e != nil {
		return e
	}
	var v map[string]any
	if e = json.Unmarshal(b, &v); e != nil {
		return e
	}
	if v["fixture_id"] != id {
		return fmt.Errorf("fixture mismatch %s", id)
	}
	return nil
}
func auditProbe() error {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method == http.MethodHead {
			w.WriteHeader(http.StatusMethodNotAllowed)
			return
		}
		w.Header().Set("Content-Range", "bytes 0-0/321")
		w.Header().Set("Content-Length", "1")
		w.Header().Set("ETag", `"probe-v1"`)
		w.WriteHeader(http.StatusPartialContent)
		_, _ = w.Write([]byte("x"))
	}))
	defer srv.Close()
	p, e := httptransfer.Probe(context.Background(), srv.Client(), srv.URL)
	if e != nil {
		return e
	}
	if p.Length == nil || *p.Length != 321 || !p.UsedRangeFallback || p.ETag != `"probe-v1"` {
		return fmt.Errorf("probe invariant failed: %+v", p)
	}
	return nil
}
func auditRepresentation() error {
	_, _, r := ids()
	n := int64(100)
	a, _ := httptransfer.RepresentationFromProbe(r, httptransfer.ProbeResult{EffectiveURL: "https://a.test/file", Length: &n, ETag: `"same"`})
	b, _ := httptransfer.RepresentationFromProbe(r, httptransfer.ProbeResult{EffectiveURL: "https://mirror.test/file", Length: &n, ETag: `"same"`})
	if d := httptransfer.CanResume(a, b, httptransfer.ResumePolicy{}); d.Allowed {
		return fmt.Errorf("mirror change accepted without policy")
	}
	if d := httptransfer.CanResume(a, b, httptransfer.ResumePolicy{AllowMirrorChange: true}); !d.Allowed {
		return fmt.Errorf("strong mirror match rejected: %+v", d)
	}
	weak, _ := httptransfer.RepresentationFromProbe(r, httptransfer.ProbeResult{EffectiveURL: "https://a.test/file", Length: &n, ETag: `W/"same"`, WeakETag: true})
	if d := httptransfer.CanResume(weak, weak, httptransfer.ResumePolicy{AllowLengthOnly: true, AllowLastModifiedLength: true}); d.Allowed {
		return fmt.Errorf("weak etag reused")
	}
	return nil
}
func auditHTTP() error {
	data := []byte(strings.Repeat("transfer-audit-", 700))
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { _, _ = w.Write(data) }))
	defer srv.Close()
	dl, g, res := ids()
	n := int64(len(data))
	rep, _ := httptransfer.RepresentationFromProbe(res, httptransfer.ProbeResult{EffectiveURL: srv.URL, Length: &n, ETag: `"v1"`})
	f, e := os.CreateTemp("", "xgo-transfer-audit-*.bin")
	if e != nil {
		return e
	}
	defer os.Remove(f.Name())
	defer f.Close()
	life := &lifecycle{}
	got, e := httptransfer.Execute(context.Background(), srv.Client(), httptransfer.ExecutePlan{URL: srv.URL, Representation: rep, DownloadID: dl, Generation: g, BufferBytes: 4096, CheckpointBytes: 4096, File: f, Committer: committer{}, Lifecycle: life})
	if e != nil {
		return e
	}
	if got.Committed != n || len(life.states) < 2 || life.states[len(life.states)-1] != "produced_artifact" {
		return fmt.Errorf("http execution invariant failed: %+v %v", got, life.states)
	}
	raw, _ := os.ReadFile(f.Name())
	if string(raw) != string(data) {
		return fmt.Errorf("staging mismatch")
	}
	return nil
}

func main() {
	mode := flag.String("mode", "", "probe|representation|http")
	out := flag.String("output", "", "report path")
	flag.Parse()
	root, _ := os.Getwd()
	spec := map[string]struct {
		cap, fixture string
		fn           func() error
	}{"probe": {"XGO-CAP-HTTP-001", "xgo-cap-http-001", auditProbe}, "representation": {"XGO-CAP-HTTP-002", "xgo-cap-http-002", auditRepresentation}, "http": {"XGO-CAP-HTTP-003", "xgo-cap-http-003", auditHTTP}}
	s, ok := spec[*mode]
	if !ok {
		fmt.Fprintln(os.Stderr, "invalid mode")
		os.Exit(2)
	}
	if e := requireFixture(root, s.fixture); e != nil {
		fmt.Fprintln(os.Stderr, e)
		os.Exit(1)
	}
	if e := s.fn(); e != nil {
		fmt.Fprintln(os.Stderr, e)
		os.Exit(1)
	}
	r := report{1, "pass", *mode, []string{"fixture_contract", "runtime_invariants"}, []string{s.cap}, []string{s.fixture}}
	b, _ := json.MarshalIndent(r, "", "  ")
	if *out != "" {
		_ = os.MkdirAll(filepath.Dir(*out), 0755)
		if e := os.WriteFile(*out, append(b, '\n'), 0644); e != nil {
			panic(e)
		}
	}
	fmt.Println(string(b))
}
