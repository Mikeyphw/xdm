package seal

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
	"time"

	"github.com/subhra74/xdm/engine/domain/download"
	"github.com/subhra74/xdm/engine/domain/identity"
	"github.com/subhra74/xdm/engine/media"
	"github.com/subhra74/xdm/engine/ops"
	"github.com/subhra74/xdm/engine/recovery"
	"github.com/subhra74/xdm/engine/scheduler"
	store "github.com/subhra74/xdm/engine/store/sqlite"
	httptransfer "github.com/subhra74/xdm/engine/transfer/http"
)

type performanceBudget struct {
	SchemaVersion int                            `json:"schema_version"`
	Policy        string                         `json:"policy"`
	Workloads     map[string]performanceWorkload `json:"workloads"`
}

type performanceWorkload struct {
	Operations int64  `json:"operations"`
	Unit       string `json:"unit"`
	MaxWallMS  int64  `json:"max_wall_ms"`
}

type performanceResult struct {
	Operations int64   `json:"operations"`
	Unit       string  `json:"unit"`
	WallMS     float64 `json:"wall_ms"`
	MaxWallMS  int64   `json:"max_wall_ms"`
	Status     string  `json:"status"`
}

type performanceEvidence struct {
	SchemaVersion int                          `json:"schema_version"`
	Status        string                       `json:"status"`
	Policy        string                       `json:"policy"`
	GoOS          string                       `json:"goos"`
	GoArch        string                       `json:"goarch"`
	Workloads     map[string]performanceResult `json:"workloads"`
}

type zeroReader struct{}

func (zeroReader) Read(p []byte) (int, error) {
	for i := range p {
		p[i] = 0x5a
	}
	return len(p), nil
}

func loadPerformanceBudget(t *testing.T) performanceBudget {
	t.Helper()
	_, file, _, _ := runtime.Caller(0)
	path := filepath.Join(filepath.Dir(file), "..", "testdata", "seal", "performance-budget.json")
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	var budget performanceBudget
	if err := json.Unmarshal(data, &budget); err != nil {
		t.Fatal(err)
	}
	if budget.SchemaVersion != 1 || len(budget.Workloads) != 8 {
		t.Fatalf("invalid performance budget: %+v", budget)
	}
	return budget
}

func TestPerformanceScaleCampaign(t *testing.T) {
	budget := loadPerformanceBudget(t)
	evidence := performanceEvidence{
		SchemaVersion: 1,
		Status:        "pass",
		Policy:        budget.Policy,
		GoOS:          runtime.GOOS,
		GoArch:        runtime.GOARCH,
		Workloads:     map[string]performanceResult{},
	}

	run := func(name string, fn func(int64) error) {
		t.Helper()
		spec, ok := budget.Workloads[name]
		if !ok {
			t.Fatalf("missing budget workload %q", name)
		}
		start := time.Now()
		err := fn(spec.Operations)
		elapsed := time.Since(start)
		result := performanceResult{Operations: spec.Operations, Unit: spec.Unit, WallMS: float64(elapsed.Microseconds()) / 1000.0, MaxWallMS: spec.MaxWallMS, Status: "pass"}
		if err != nil {
			result.Status = "fail"
			evidence.Status = "fail"
			t.Errorf("%s failed: %v", name, err)
		}
		if elapsed > time.Duration(spec.MaxWallMS)*time.Millisecond {
			result.Status = "budget_exceeded"
			evidence.Status = "fail"
			t.Errorf("%s wall budget exceeded: %s > %dms", name, elapsed, spec.MaxWallMS)
		}
		evidence.Workloads[name] = result
	}

	run("large_single_transfer", func(size int64) error {
		buf := make([]byte, 256<<10)
		n, err := io.CopyBuffer(io.Discard, io.LimitReader(zeroReader{}, size), buf)
		if err != nil {
			return err
		}
		if n != size {
			return fmt.Errorf("copied %d bytes, want %d", n, size)
		}
		return nil
	})

	run("segmented_transfer", func(size int64) error {
		segments, err := httptransfer.PlanSegments(size, 16, 256<<10)
		if err != nil {
			return err
		}
		var copied int64
		buf := make([]byte, 256<<10)
		for _, segment := range segments {
			length := segment.End - segment.Start + 1
			n, err := io.CopyBuffer(io.Discard, io.LimitReader(zeroReader{}, length), buf)
			if err != nil {
				return err
			}
			copied += n
		}
		if copied != size {
			return fmt.Errorf("segmented copy %d bytes, want %d", copied, size)
		}
		return nil
	})

	run("small_downloads", func(count int64) error {
		generation, _ := identity.NewAttemptGeneration(1)
		for i := int64(0); i < count; i++ {
			suffix := fmt.Sprintf("%032x", i+1)
			dlID, err := identity.ParseDownloadID("dl_" + suffix)
			if err != nil {
				return err
			}
			reqID, err := identity.ParseRequestID("req_" + suffix)
			if err != nil {
				return err
			}
			dl, err := download.New(dlID, reqID)
			if err != nil {
				return err
			}
			if _, err = dl.BeginAttempt(generation); err != nil {
				return err
			}
		}
		return nil
	})

	run("hls_10k_fragments", func(count int64) error {
		var body strings.Builder
		body.WriteString("#EXTM3U\n#EXT-X-TARGETDURATION:2\n")
		for i := int64(0); i < count; i++ {
			fmt.Fprintf(&body, "#EXTINF:2,\nsegment-%d.ts\n", i)
		}
		body.WriteString("#EXT-X-ENDLIST\n")
		playlist, err := media.ParseHLSPlaylist("https://cdn.example.test/perf/list.m3u8", body.String())
		if err != nil {
			return err
		}
		if int64(len(playlist.Segments)) != count {
			return fmt.Errorf("HLS segments=%d want=%d", len(playlist.Segments), count)
		}
		return nil
	})

	run("dash_large_timeline", func(count int64) error {
		var body strings.Builder
		body.WriteString(`<MPD type="static" mediaPresentationDuration="PT30000S"><Period><AdaptationSet contentType="video"><Representation id="v" bandwidth="1000"><SegmentTemplate media="seg-$Time$.m4s" initialization="init.m4s" timescale="1"><SegmentTimeline>`)
		for i := int64(0); i < count; i++ {
			body.WriteString(`<S d="2"/>`)
		}
		body.WriteString(`</SegmentTimeline></SegmentTemplate></Representation></AdaptationSet></Period></MPD>`)
		manifest, err := media.ParseDASHManifest("https://cdn.example.test/perf/manifest.mpd", body.String())
		if err != nil {
			return err
		}
		fragments, err := media.ExpandDASHFragments(manifest)
		if err != nil {
			return err
		}
		if int64(len(fragments)) < count {
			return fmt.Errorf("DASH fragments=%d want-at-least=%d", len(fragments), count)
		}
		return nil
	})

	run("history_100k_rows", func(count int64) error {
		var raw bytes.Buffer
		raw.WriteString(`{"history":[`)
		for i := int64(0); i < count; i++ {
			if i > 0 {
				raw.WriteByte(',')
			}
			fmt.Fprintf(&raw, `{"id":"h-%d","payload":{"state":"complete"}}`, i)
		}
		raw.WriteString(`]}`)
		source, err := ops.NewLegacyImportSource(ops.ImportSourceAndroidRoom, 1, raw.Bytes())
		if err != nil {
			return err
		}
		objects, err := ops.AndroidRoomImportAdapter(context.Background(), source)
		if err != nil {
			return err
		}
		if int64(len(objects)) != count {
			return fmt.Errorf("history objects=%d want=%d", len(objects), count)
		}
		return nil
	})

	run("queued_jobs", func(count int64) error {
		now := time.Unix(1_800_000_000, 0)
		qa, _ := identity.ParseQueueID("queue_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
		qb, _ := identity.ParseQueueID("queue_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
		items := make([]scheduler.WorkItem, 0, count)
		for i := int64(0); i < count; i++ {
			id, err := identity.ParseDownloadID("dl_" + fmt.Sprintf("%032x", i+1))
			if err != nil {
				return err
			}
			queueID, host := qa, "a.example"
			if i%2 == 1 {
				queueID, host = qb, "b.example"
			}
			items = append(items, scheduler.WorkItem{DownloadID: id, QueueID: queueID, Host: host, QueueEnabled: true, QueueConcurrency: 1000, QueuePriority: int(i % 17), DownloadPriority: int(i % 5), QueuePosition: int(i), EnqueuedAt: now.Add(-time.Duration(i%96) * time.Hour), Runtime: scheduler.QueueRuntimeEvaluation{Eligible: true}, Dependency: scheduler.Eligibility{DownloadID: id, Eligible: true}})
		}
		decision, err := scheduler.EvaluateScheduler(scheduler.SchedulerInput{Now: now, Items: items, Capacity: scheduler.CapacitySnapshot{GlobalLimit: 64, PerHostLimit: map[string]int{"a.example": 48, "b.example": 48}}, Fairness: scheduler.FairnessPolicy{AgingInterval: time.Hour, AgingBoost: 100}})
		if err != nil {
			return err
		}
		if len(decision.Runnable) != 64 {
			return fmt.Errorf("runnable=%d want=64", len(decision.Runnable))
		}
		return nil
	})

	run("mass_recovery_startup", func(count int64) error {
		if !store.Available {
			return store.ErrUnavailable
		}
		ctx := context.Background()
		path := filepath.Join(t.TempDir(), "perf-recovery.sqlite")
		db, err := store.Open(path, store.DefaultOptions())
		if err != nil {
			return err
		}
		defer db.Close()
		if err := store.Migrate(ctx, db, 1); err != nil {
			return err
		}
		repo, err := store.NewRepository(db)
		if err != nil {
			return err
		}
		rev, _ := identity.NewRevision(1)
		for i := int64(0); i < count; i++ {
			suffix := fmt.Sprintf("%032x", i+1)
			reqID, _ := identity.ParseRequestID("req_" + suffix)
			dlID, _ := identity.ParseDownloadID("dl_" + suffix)
			if err := repo.CreateRequest(ctx, store.RequestRecord{ID: reqID, Revision: rev, ResourceIdentity: "perf:" + suffix, Method: "GET", SafeSpecJSON: `{"perf":true}`, CreatedAtUnixMS: 1}); err != nil {
				return err
			}
			if err := repo.CreateDownload(ctx, store.DownloadRecord{ID: dlID, RequestID: reqID, CurrentRequestRevision: rev, State: "open", Revision: rev, CreatedAtUnixMS: 1, UpdatedAtUnixMS: 1}); err != nil {
				return err
			}
		}
		coordinator := recovery.Coordinator{Repository: repo, NowUnixMS: func() int64 { return 2 }}
		decisions, err := coordinator.RecoverAll(ctx)
		if err != nil {
			return err
		}
		if int64(len(decisions)) != count {
			return fmt.Errorf("recovery decisions=%d want=%d", len(decisions), count)
		}
		return nil
	})

	if path := os.Getenv("XGO_PERF_EVIDENCE"); path != "" {
		data, err := json.MarshalIndent(evidence, "", "  ")
		if err != nil {
			t.Fatal(err)
		}
		if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(path, append(data, '\n'), 0o644); err != nil {
			t.Fatal(err)
		}
	}
}
