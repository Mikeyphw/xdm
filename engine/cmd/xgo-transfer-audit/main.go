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
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/subhra74/xdm/engine/domain/failure"
	"github.com/subhra74/xdm/engine/domain/identity"
	"github.com/subhra74/xdm/engine/domain/resource"
	"github.com/subhra74/xdm/engine/store/checkpoint"
	store "github.com/subhra74/xdm/engine/store/sqlite"
	"github.com/subhra74/xdm/engine/transfer/arbitration"
	httptransfer "github.com/subhra74/xdm/engine/transfer/http"
	retrypolicy "github.com/subhra74/xdm/engine/transfer/retry"
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

type fixedClock struct{ t time.Time }

func (f fixedClock) Now() time.Time { return f.t }

type zeroJitter struct{}

func (zeroJitter) Apply(d time.Duration, _ int) time.Duration { return d }

func ids() (identity.DownloadID, identity.AttemptGeneration, resource.Identity) {
	dl, _ := identity.ParseDownloadID("dl_00000000000000000000000000000077")
	g, _ := identity.NewAttemptGeneration(1)
	r, _ := resource.DeriveIdentity("audit", "transfer")
	return dl, g, r
}
func requireFixture(root, id string) error {
	b, e := os.ReadFile(filepath.Join(root, "engine/testdata", fixturePath(id)))
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
func fixturePath(id string) string {
	if strings.HasPrefix(id, "xgo-cap-retry-") {
		return "retry/" + id + ".json"
	}
	if strings.HasPrefix(id, "xgo-cap-bandwidth-") {
		return "bandwidth/" + id + ".json"
	}
	return "http/" + id + ".json"
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

func setupStore() (*store.DB, *store.Repository, identity.DownloadID, identity.AttemptGeneration, *os.File, error) {
	ctx := context.Background()
	dir, err := os.MkdirTemp("", "xgo-transfer-store-*")
	if err != nil {
		return nil, nil, "", 0, nil, err
	}
	db, err := store.Open(filepath.Join(dir, "engine.sqlite"), store.DefaultOptions())
	if err != nil {
		return nil, nil, "", 0, nil, err
	}
	if err = store.Migrate(ctx, db, 1); err != nil {
		return nil, nil, "", 0, nil, err
	}
	repo, _ := store.NewRepository(db)
	req, _ := identity.ParseRequestID("req_00000000000000000000000000000077")
	dl, _ := identity.ParseDownloadID("dl_00000000000000000000000000000077")
	rev, _ := identity.NewRevision(1)
	if err = repo.CreateRequest(ctx, store.RequestRecord{ID: req, Revision: rev, ResourceIdentity: "audit", Method: "GET", SafeSpecJSON: `{"audit":true}`, CreatedAtUnixMS: 1}); err != nil {
		return nil, nil, "", 0, nil, err
	}
	if err = repo.CreateDownload(ctx, store.DownloadRecord{ID: dl, RequestID: req, CurrentRequestRevision: rev, State: "open", Revision: rev, CreatedAtUnixMS: 1, UpdatedAtUnixMS: 1}); err != nil {
		return nil, nil, "", 0, nil, err
	}
	attempt, _, err := repo.ReserveAttemptGeneration(ctx, dl, rev, "native", 2)
	if err != nil {
		return nil, nil, "", 0, nil, err
	}
	claim, err := repo.CreateOwnershipClaim(ctx, dl, attempt.Generation, "native", "stage", "runtime", 3)
	if err != nil {
		return nil, nil, "", 0, nil, err
	}
	task, _ := identity.ParseBackendTaskID("bt_00000000000000000000000000000077")
	bound, _, err := repo.BindBackendTask(ctx, dl, attempt.Generation, claim.Revision, task, "native-task", "runtime", 4)
	if err != nil {
		return nil, nil, "", 0, nil, err
	}
	ready, err := repo.MarkOwnershipReady(ctx, dl, attempt.Generation, bound.Revision, 5)
	if err != nil {
		return nil, nil, "", 0, nil, err
	}
	if _, _, err = repo.ActivateOwnership(ctx, dl, attempt.Generation, ready.Revision, 6); err != nil {
		return nil, nil, "", 0, nil, err
	}
	f, err := os.OpenFile(filepath.Join(dir, "staging.bin"), os.O_CREATE|os.O_RDWR, 0o600)
	return db, repo, dl, attempt.Generation, f, err
}
func rangedServer(data []byte) *httptest.Server {
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		raw := r.Header.Get("Range")
		if raw == "" {
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write(data)
			return
		}
		var s, e int64
		if _, err := fmt.Sscanf(raw, "bytes=%d-%d", &s, &e); err != nil || s < 0 || e < s || e >= int64(len(data)) {
			w.WriteHeader(http.StatusRequestedRangeNotSatisfiable)
			return
		}
		w.Header().Set("Content-Range", fmt.Sprintf("bytes %d-%d/%d", s, e, len(data)))
		w.Header().Set("Content-Length", strconv.FormatInt(e-s+1, 10))
		w.Header().Set("ETag", `"audit-v1"`)
		w.WriteHeader(http.StatusPartialContent)
		_, _ = w.Write(data[s : e+1])
	}))
}
func auditSegmented() error {
	data := []byte(strings.Repeat("segmented-audit-", 8192))
	srv := rangedServer(data)
	defer srv.Close()
	db, repo, dl, g, f, err := setupStore()
	if err != nil {
		return err
	}
	defer db.Close()
	defer f.Close()
	_, _, res := ids()
	n := int64(len(data))
	rep, _ := httptransfer.RepresentationFromProbe(res, httptransfer.ProbeResult{EffectiveURL: srv.URL, Length: &n, ETag: `"audit-v1"`})
	got, err := httptransfer.ExecuteSegmented(context.Background(), srv.Client(), httptransfer.SegmentedPlan{URL: srv.URL, Representation: rep, DownloadID: dl, Generation: g, SegmentCount: 4, BlockBytes: 4096, BufferBytes: 4096, File: f, Committer: checkpoint.Committer{Repository: repo}, Ledger: repo, Lifecycle: &lifecycle{}})
	if err != nil {
		return err
	}
	if got.Segments != 4 || got.Bytes != n {
		return fmt.Errorf("segmented result %+v", got)
	}
	raw, _ := os.ReadFile(f.Name())
	if string(raw) != string(data) {
		return fmt.Errorf("segmented staging mismatch")
	}
	return nil
}
func auditResume() error {
	data := []byte(strings.Repeat("resume-audit-", 2048))
	srv := rangedServer(data)
	defer srv.Close()
	db, repo, dl, g, f, err := setupStore()
	if err != nil {
		return err
	}
	defer db.Close()
	defer f.Close()
	n := int64(len(data))
	if err = f.Truncate(n); err != nil {
		return err
	}
	block := int64(4096)
	if _, err = (checkpoint.Committer{Repository: repo}).CommitBlock(context.Background(), f, checkpoint.CommitRequest{DownloadID: dl, Generation: g, BlockIndex: 0, StartByte: 0, Data: data[:block], NowUnixMS: 10}); err != nil {
		return err
	}
	_, _, res := ids()
	rep, _ := httptransfer.RepresentationFromProbe(res, httptransfer.ProbeResult{EffectiveURL: srv.URL, Length: &n, ETag: `"audit-v1"`})
	plan, err := httptransfer.BuildResumePlan(context.Background(), repo, f, dl, g, rep, rep, httptransfer.ResumePolicy{})
	if err != nil {
		return err
	}
	if len(plan.Missing) == 0 {
		return fmt.Errorf("resume audit expected missing range")
	}
	got, err := httptransfer.ExecuteResume(context.Background(), srv.Client(), httptransfer.ResumeExecutePlan{URL: srv.URL, Representation: rep, DownloadID: dl, Generation: g, Ranges: plan.Missing, BlockBytes: block, BufferBytes: 4096, File: f, Committer: checkpoint.Committer{Repository: repo}, Repository: repo, Lifecycle: &lifecycle{}})
	if err != nil {
		return err
	}
	if got.Committed != n {
		return fmt.Errorf("resume result %+v", got)
	}
	return nil
}
func auditRetry() error {
	now := time.Unix(1000, 0).UTC()
	f, _ := failure.NewDefault(failure.RateLimited, nil)
	eng := retrypolicy.Engine{Clock: fixedClock{now}, Jitter: zeroJitter{}}
	d, err := eng.Decide(retrypolicy.Input{Failure: f, AttemptCount: 3, RetryAfter: "120", Queue: retrypolicy.QueuePolicy{Enabled: true, MaxAttempts: 5, BaseDelay: time.Second, MaxDelay: time.Minute}, NetworkAvailable: true, Replayable: true})
	if err != nil {
		return err
	}
	if d.Kind != retrypolicy.RetryAt || d.RetryAtUnixMS != now.Add(120*time.Second).UnixMilli() {
		return fmt.Errorf("retry decision %+v", d)
	}
	db, repo, dl, g, _, err := setupStore()
	if err != nil {
		return err
	}
	defer db.Close()
	attempt, err := repo.GetAttempt(context.Background(), dl, g)
	if err != nil {
		return err
	}
	if _, _, err = retrypolicy.Persist(context.Background(), repo, dl, g, attempt.Revision, f, 3, d, now); err != nil {
		return err
	}
	loaded, _, err := retrypolicy.Load(context.Background(), repo, dl, g)
	if err != nil {
		return err
	}
	if loaded.Decision.RetryAtUnixMS != d.RetryAtUnixMS {
		return fmt.Errorf("retry deadline not durable")
	}
	return nil
}

func auditBandwidth() error {
	limits := arbitration.Limits{GlobalConnections: 2, DefaultHostConnections: 1, DefaultDownloadConnections: 2, GlobalBytesPerSecond: 1 << 20}
	arb, err := arbitration.New(limits)
	if err != nil {
		return err
	}
	a, _ := arb.Bind(arbitration.Key{DownloadID: "download-a", Host: "a.test"})
	b, _ := arb.Bind(arbitration.Key{DownloadID: "download-b", Host: "b.test"})
	aSame, _ := arb.Bind(arbitration.Key{DownloadID: "download-c", Host: "a.test"})

	releaseA, err := a.AcquireConnection(context.Background())
	if err != nil {
		return err
	}
	defer releaseA()
	ctx, cancel := context.WithTimeout(context.Background(), 40*time.Millisecond)
	defer cancel()
	if _, err = aSame.AcquireConnection(ctx); err == nil {
		return fmt.Errorf("per-host connection cap not enforced")
	}
	releaseB, err := b.AcquireConnection(context.Background())
	if err != nil {
		return fmt.Errorf("host isolation failed: %w", err)
	}
	releaseB()
	releaseA()

	// A live limit update must wake an already queued acquisition.
	one, _ := arb.Bind(arbitration.Key{DownloadID: "download-one", Host: "one.test"})
	two, _ := arb.Bind(arbitration.Key{DownloadID: "download-two", Host: "two.test"})
	limits.GlobalConnections = 1
	limits.DefaultHostConnections = 2
	if err = arb.UpdateLimits(limits); err != nil {
		return err
	}
	r1, err := one.AcquireConnection(context.Background())
	if err != nil {
		return err
	}
	done := make(chan error, 1)
	go func() {
		r, e := two.AcquireConnection(context.Background())
		if r != nil {
			r()
		}
		done <- e
	}()
	time.Sleep(20 * time.Millisecond)
	limits.GlobalConnections = 2
	if err = arb.UpdateLimits(limits); err != nil {
		return err
	}
	select {
	case e := <-done:
		if e != nil {
			return e
		}
	case <-time.After(time.Second):
		return fmt.Errorf("live connection limit update did not wake waiter")
	}
	r1()

	// Equal active transfers receive equal accounted bytes while the global rate
	// enforces a measurable lower throughput bound.
	limits.GlobalConnections = 4
	limits.GlobalBytesPerSecond = 1 << 20
	if err = arb.UpdateLimits(limits); err != nil {
		return err
	}
	start := time.Now()
	var wg sync.WaitGroup
	errCh := make(chan error, 2)
	for _, h := range []*arbitration.Handle{a, b} {
		wg.Add(1)
		go func(handle *arbitration.Handle) {
			defer wg.Done()
			for i := 0; i < 8; i++ {
				if e := handle.WaitN(context.Background(), 16<<10); e != nil {
					errCh <- e
					return
				}
			}
		}(h)
	}
	wg.Wait()
	close(errCh)
	for e := range errCh {
		if e != nil {
			return e
		}
	}
	elapsed := time.Since(start)
	if elapsed < 150*time.Millisecond || elapsed > 3*time.Second {
		return fmt.Errorf("bandwidth throughput outside tolerance: %s", elapsed)
	}
	m := arb.Metrics()
	if m.BytesByDownload["download-a"] != 128<<10 || m.BytesByDownload["download-b"] != 128<<10 {
		return fmt.Errorf("fair bandwidth accounting failed: %+v", m.BytesByDownload)
	}
	if m.PeakConnections > 2 || m.ActiveConnections != 0 || m.LimitsRevision < 4 {
		return fmt.Errorf("arbiter metrics invariant failed: %+v", m)
	}
	return nil
}

func main() {
	mode := flag.String("mode", "", "probe|representation|http|segmented|resume|retry|bandwidth")
	out := flag.String("output", "", "report path")
	flag.Parse()
	root, _ := os.Getwd()
	spec := map[string]struct {
		caps, fixtures []string
		fn             func() error
	}{
		"probe":          {[]string{"XGO-CAP-HTTP-001"}, []string{"xgo-cap-http-001"}, auditProbe},
		"representation": {[]string{"XGO-CAP-HTTP-002"}, []string{"xgo-cap-http-002"}, auditRepresentation},
		"http":           {[]string{"XGO-CAP-HTTP-003"}, []string{"xgo-cap-http-003"}, auditHTTP},
		"segmented":      {[]string{"XGO-CAP-HTTP-004", "XGO-CAP-HTTP-005"}, []string{"xgo-cap-http-004", "xgo-cap-http-005"}, auditSegmented},
		"resume":         {[]string{"XGO-CAP-HTTP-006"}, []string{"xgo-cap-http-006"}, auditResume},
		"retry":          {[]string{"XGO-CAP-RETRY-001"}, []string{"xgo-cap-retry-001"}, auditRetry},
		"bandwidth":      {[]string{"XGO-CAP-BANDWIDTH-001"}, []string{"xgo-cap-bandwidth-001"}, auditBandwidth},
	}
	s, ok := spec[*mode]
	if !ok {
		fmt.Fprintln(os.Stderr, "invalid mode")
		os.Exit(2)
	}
	for _, fixture := range s.fixtures {
		if e := requireFixture(root, fixture); e != nil {
			fmt.Fprintln(os.Stderr, e)
			os.Exit(1)
		}
	}
	if e := s.fn(); e != nil {
		fmt.Fprintln(os.Stderr, e)
		os.Exit(1)
	}
	r := report{1, "pass", *mode, []string{"fixture_contract", "runtime_invariants"}, s.caps, s.fixtures}
	b, _ := json.MarshalIndent(r, "", "  ")
	if *out != "" {
		_ = os.MkdirAll(filepath.Dir(*out), 0755)
		if e := os.WriteFile(*out, append(b, '\n'), 0644); e != nil {
			panic(e)
		}
	}
	fmt.Println(string(b))
}
