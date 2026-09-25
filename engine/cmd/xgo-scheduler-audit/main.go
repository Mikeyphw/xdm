package main

import (
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"os"
	"path/filepath"
	"time"

	"github.com/subhra74/xdm/engine/domain/identity"
	"github.com/subhra74/xdm/engine/scheduler"
	"github.com/subhra74/xdm/engine/store/sqlite"
)

type report struct {
	Mode   string   `json:"mode"`
	Status string   `json:"status"`
	Checks []string `json:"checks"`
}

func main() {
	mode := flag.String("mode", "", "queue_model or dependency_graph")
	output := flag.String("output", "", "output JSON path")
	flag.Parse()
	if *mode == "" || *output == "" {
		fatalf("mode and output are required")
	}
	var r report
	var err error
	switch *mode {
	case "queue_model":
		r, err = runQueueModel()
	case "dependency_graph":
		r, err = runDependencyGraph()
	default:
		err = fmt.Errorf("unknown mode %q", *mode)
	}
	if err != nil {
		fatalf("%s failed: %v", *mode, err)
	}
	if err := os.MkdirAll(filepath.Dir(*output), 0o755); err != nil {
		fatalf("mkdir output: %v", err)
	}
	data, err := json.MarshalIndent(r, "", "  ")
	if err != nil {
		fatalf("marshal: %v", err)
	}
	if err := os.WriteFile(*output, append(data, '\n'), 0o644); err != nil {
		fatalf("write output: %v", err)
	}
}

func runQueueModel() (report, error) {
	store, db, ctx, cleanup, err := openStore()
	if err != nil {
		return report{}, err
	}
	defer cleanup()
	repo, err := sqlite.NewRepository(db)
	if err != nil {
		return report{}, err
	}
	a, err := seedDownload(ctx, repo, "dl_11111111111111111111111111111111")
	if err != nil {
		return report{}, err
	}
	b, err := seedDownload(ctx, repo, "dl_22222222222222222222222222222222")
	if err != nil {
		return report{}, err
	}
	qid, _ := identity.ParseQueueID("queue_dddddddddddddddddddddddddddddddd")
	rev, _ := identity.NewRevision(1)
	q, err := store.CreateQueue(ctx, scheduler.QueueRecord{ID: qid, Name: "audit", Enabled: true, Priority: 50, Policy: scheduler.QueuePolicy{Concurrency: 2, BandwidthProfile: "balanced", Conditions: []string{"online"}, ScheduleRef: "schedule_audit", RetryPolicy: "standard"}, Revision: rev})
	if err != nil {
		return report{}, err
	}
	if err := store.Assign(ctx, q.ID, a, 0); err != nil {
		return report{}, err
	}
	if err := store.Assign(ctx, q.ID, b, 1); err != nil {
		return report{}, err
	}
	if err := store.Reorder(ctx, q.ID, []identity.DownloadID{b, a}); err != nil {
		return report{}, err
	}
	members, err := store.Members(ctx, q.ID)
	if err != nil {
		return report{}, err
	}
	if len(members) != 2 || members[0].DownloadID != b || members[1].DownloadID != a {
		return report{}, fmt.Errorf("membership reorder mismatch: %+v", members)
	}
	if _, err := store.UpdateQueue(ctx, q.ID, q.Revision, scheduler.QueueRecord{Name: "stale", Enabled: true, Priority: 1, Policy: scheduler.QueuePolicy{Concurrency: 1}}); err != nil {
		return report{}, err
	}
	if _, err := store.UpdateQueue(ctx, q.ID, q.Revision, scheduler.QueueRecord{Name: "stale-again", Enabled: true, Priority: 1, Policy: scheduler.QueuePolicy{Concurrency: 1}}); !errors.Is(err, sqlite.ErrStaleWrite) {
		return report{}, fmt.Errorf("expected stale conflict, got %v", err)
	}
	if err := store.DeleteQueue(ctx, q.ID, scheduler.DeleteRejectWithMembers); !errors.Is(err, scheduler.ErrQueueHasMembers) {
		return report{}, fmt.Errorf("expected member delete rejection, got %v", err)
	}
	return report{Mode: "queue_model", Status: "PASS", Checks: []string{"create/update queue", "membership order persisted", "single active assignment", "revision conflict", "delete-with-members policy"}}, nil
}

func runDependencyGraph() (report, error) {
	store, db, ctx, cleanup, err := openStore()
	if err != nil {
		return report{}, err
	}
	defer cleanup()
	repo, err := sqlite.NewRepository(db)
	if err != nil {
		return report{}, err
	}
	a, err := seedDownload(ctx, repo, "dl_33333333333333333333333333333333")
	if err != nil {
		return report{}, err
	}
	b, err := seedDownload(ctx, repo, "dl_44444444444444444444444444444444")
	if err != nil {
		return report{}, err
	}
	c, err := seedDownload(ctx, repo, "dl_55555555555555555555555555555555")
	if err != nil {
		return report{}, err
	}
	if err := store.AddDependency(ctx, scheduler.DependencyRecord{DownloadID: a, DependencyDownloadID: b, Requirement: scheduler.SuccessRequired}); err != nil {
		return report{}, err
	}
	if err := store.AddDependency(ctx, scheduler.DependencyRecord{DownloadID: b, DependencyDownloadID: c, Requirement: scheduler.CompletionRequired}); err != nil {
		return report{}, err
	}
	if err := store.AddDependency(ctx, scheduler.DependencyRecord{DownloadID: c, DependencyDownloadID: a, Requirement: scheduler.SuccessRequired}); !errors.Is(err, scheduler.ErrDependencyCycle) {
		return report{}, fmt.Errorf("expected cycle rejection, got %v", err)
	}
	failed, err := store.Eligibility(ctx, a, map[identity.DownloadID]scheduler.DownloadOutcome{b: scheduler.OutcomeFailed})
	if err != nil {
		return report{}, err
	}
	if failed.Eligible || len(failed.Blocked) != 1 || failed.Blocked[0].Reason != "dependency_failed" {
		return report{}, fmt.Errorf("bad failure propagation: %+v", failed)
	}
	ready, err := store.Eligibility(ctx, a, map[identity.DownloadID]scheduler.DownloadOutcome{b: scheduler.OutcomeSucceeded})
	if err != nil {
		return report{}, err
	}
	if !ready.Eligible {
		return report{}, fmt.Errorf("dependency retry success did not unblock: %+v", ready)
	}
	return report{Mode: "dependency_graph", Status: "PASS", Checks: []string{"chain dependency", "cycle insertion rejection", "success-required failure propagation", "dependency retry success unblocks"}}, nil
}

func openStore() (*scheduler.Store, *sqlite.DB, context.Context, func(), error) {
	if !sqlite.Available {
		return nil, nil, nil, nil, sqlite.ErrUnavailable
	}
	path := filepath.Join(os.TempDir(), fmt.Sprintf("xgo-scheduler-audit-%d.sqlite", time.Now().UnixNano()))
	db, err := sqlite.Open(path, sqlite.DefaultOptions())
	if err != nil {
		return nil, nil, nil, nil, err
	}
	cleanup := func() {
		_ = db.Close()
		_ = os.Remove(path)
		_ = os.Remove(path + "-wal")
		_ = os.Remove(path + "-shm")
	}
	ctx := context.Background()
	if err := sqlite.Migrate(ctx, db, time.Now().UnixMilli()); err != nil {
		cleanup()
		return nil, nil, nil, nil, err
	}
	store, err := scheduler.NewStore(db)
	if err != nil {
		cleanup()
		return nil, nil, nil, nil, err
	}
	return store, db, ctx, cleanup, nil
}

func seedDownload(ctx context.Context, repo *sqlite.Repository, id string) (identity.DownloadID, error) {
	dl, err := identity.ParseDownloadID(id)
	if err != nil {
		return "", err
	}
	req, err := identity.ParseRequestID("req_" + id[3:])
	if err != nil {
		return "", err
	}
	rev, _ := identity.NewRevision(1)
	if err := repo.CreateRequest(ctx, sqlite.RequestRecord{ID: req, Revision: rev, ResourceIdentity: "audit:" + id, Method: "GET", SafeSpecJSON: `{"url":"https://example.invalid/` + id + `"}`}); err != nil {
		return "", err
	}
	if err := repo.CreateDownload(ctx, sqlite.DownloadRecord{ID: dl, RequestID: req, CurrentRequestRevision: rev, State: "queued", Revision: rev}); err != nil {
		return "", err
	}
	return dl, nil
}

func fatalf(format string, args ...any) { fmt.Fprintf(os.Stderr, format+"\n", args...); os.Exit(1) }
