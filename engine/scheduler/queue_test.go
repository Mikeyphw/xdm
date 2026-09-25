package scheduler

import (
	"context"
	"errors"
	"path/filepath"
	"testing"
	"time"

	"github.com/subhra74/xdm/engine/domain/identity"
	"github.com/subhra74/xdm/engine/store/sqlite"
)

func openSchedulerTestStore(t *testing.T) (*Store, *sqlite.DB, context.Context) {
	t.Helper()
	if !sqlite.Available {
		t.Skip("sqlite cgo unavailable")
	}
	ctx := context.Background()
	db, err := sqlite.Open(filepath.Join(t.TempDir(), "scheduler.sqlite"), sqlite.DefaultOptions())
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = db.Close() })
	if err := sqlite.Migrate(ctx, db, time.Now().UnixMilli()); err != nil {
		t.Fatal(err)
	}
	store, err := NewStore(db)
	if err != nil {
		t.Fatal(err)
	}
	return store, db, ctx
}

func rev1(t *testing.T) identity.Revision {
	t.Helper()
	r, err := identity.NewRevision(1)
	if err != nil {
		t.Fatal(err)
	}
	return r
}

func seedDownload(t *testing.T, ctx context.Context, repo *sqlite.Repository, id string) identity.DownloadID {
	t.Helper()
	dl, err := identity.ParseDownloadID(id)
	if err != nil {
		t.Fatal(err)
	}
	req, err := identity.ParseRequestID("req_" + id[3:])
	if err != nil {
		t.Fatal(err)
	}
	if err := repo.CreateRequest(ctx, sqlite.RequestRecord{ID: req, Revision: rev1(t), ResourceIdentity: "res:" + id, Method: "GET", SafeSpecJSON: `{"url":"https://example.test/` + id + `"}`}); err != nil {
		t.Fatal(err)
	}
	if err := repo.CreateDownload(ctx, sqlite.DownloadRecord{ID: dl, RequestID: req, CurrentRequestRevision: rev1(t), State: "queued", Revision: rev1(t)}); err != nil {
		t.Fatal(err)
	}
	return dl
}

func qid(t *testing.T, text string) identity.QueueID {
	t.Helper()
	id, err := identity.ParseQueueID(text)
	if err != nil {
		t.Fatal(err)
	}
	return id
}

func TestQueueLifecycleMembershipAndRevisionConflicts(t *testing.T) {
	store, db, ctx := openSchedulerTestStore(t)
	repo, err := sqlite.NewRepository(db)
	if err != nil {
		t.Fatal(err)
	}
	a := seedDownload(t, ctx, repo, "dl_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
	b := seedDownload(t, ctx, repo, "dl_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
	q, err := store.CreateQueue(ctx, QueueRecord{ID: qid(t, "queue_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"), Name: "Main", Enabled: true, Priority: 10, Policy: QueuePolicy{Concurrency: 2, BandwidthProfile: "day", Conditions: []string{"online", "wifi"}, ScheduleRef: "schedule_daily", RetryPolicy: "standard"}, Revision: rev1(t)})
	if err != nil {
		t.Fatal(err)
	}
	if !q.Enabled || q.Policy.Concurrency != 2 || len(q.Policy.Conditions) != 2 {
		t.Fatalf("unexpected queue: %+v", q)
	}

	if err := store.Assign(ctx, q.ID, a, 0); err != nil {
		t.Fatal(err)
	}
	if err := store.Assign(ctx, q.ID, b, 1); err != nil {
		t.Fatal(err)
	}
	members, err := store.Members(ctx, q.ID)
	if err != nil {
		t.Fatal(err)
	}
	if got := []identity.DownloadID{members[0].DownloadID, members[1].DownloadID}; got[0] != a || got[1] != b {
		t.Fatalf("members=%v", got)
	}
	if err := store.Reorder(ctx, q.ID, []identity.DownloadID{b, a}); err != nil {
		t.Fatal(err)
	}
	members, err = store.Members(ctx, q.ID)
	if err != nil {
		t.Fatal(err)
	}
	if members[0].DownloadID != b || members[1].DownloadID != a || members[0].Position != 0 || members[1].Position != 1 {
		t.Fatalf("reorder failed: %+v", members)
	}

	updated, err := store.UpdateQueue(ctx, q.ID, q.Revision, QueueRecord{Name: "Renamed", Enabled: false, Priority: 11, Policy: QueuePolicy{Concurrency: 1}})
	if err != nil {
		t.Fatal(err)
	}
	if updated.Name != "Renamed" || updated.Enabled || updated.Revision.Int64() != q.Revision.Int64()+1 {
		t.Fatalf("bad update: %+v", updated)
	}
	if _, err := store.UpdateQueue(ctx, q.ID, q.Revision, QueueRecord{Name: "Stale", Enabled: true, Priority: 1, Policy: QueuePolicy{Concurrency: 1}}); !errors.Is(err, sqlite.ErrStaleWrite) {
		t.Fatalf("want stale write, got %v", err)
	}
	if err := store.DeleteQueue(ctx, q.ID, DeleteRejectWithMembers); !errors.Is(err, ErrQueueHasMembers) {
		t.Fatalf("want queue has members, got %v", err)
	}
	if err := store.DeleteQueue(ctx, q.ID, DeleteUnassignMembers); err != nil {
		t.Fatal(err)
	}
	if _, err := store.GetQueue(ctx, q.ID); !errors.Is(err, sqlite.ErrNotFound) {
		t.Fatalf("want not found, got %v", err)
	}
}

func TestSingleActiveQueueAssignmentMove(t *testing.T) {
	store, db, ctx := openSchedulerTestStore(t)
	repo, err := sqlite.NewRepository(db)
	if err != nil {
		t.Fatal(err)
	}
	dl := seedDownload(t, ctx, repo, "dl_cccccccccccccccccccccccccccccccc")
	qa, err := store.CreateQueue(ctx, QueueRecord{ID: qid(t, "queue_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"), Name: "A", Enabled: true, Priority: 1, Policy: QueuePolicy{Concurrency: 1}, Revision: rev1(t)})
	if err != nil {
		t.Fatal(err)
	}
	qb, err := store.CreateQueue(ctx, QueueRecord{ID: qid(t, "queue_cccccccccccccccccccccccccccccccc"), Name: "B", Enabled: true, Priority: 2, Policy: QueuePolicy{Concurrency: 1}, Revision: rev1(t)})
	if err != nil {
		t.Fatal(err)
	}
	if err := store.Assign(ctx, qa.ID, dl, 0); err != nil {
		t.Fatal(err)
	}
	if err := store.Move(ctx, qb.ID, dl, 0); err != nil {
		t.Fatal(err)
	}
	membership, err := store.QueueForDownload(ctx, dl)
	if err != nil {
		t.Fatal(err)
	}
	if membership.QueueID != qb.ID {
		t.Fatalf("download assigned to %s", membership.QueueID)
	}
	members, err := store.Members(ctx, qa.ID)
	if err != nil {
		t.Fatal(err)
	}
	if len(members) != 0 {
		t.Fatalf("old queue still has members: %+v", members)
	}
}

func TestDependencyCycleEligibilityAndRetryOutcome(t *testing.T) {
	store, db, ctx := openSchedulerTestStore(t)
	repo, err := sqlite.NewRepository(db)
	if err != nil {
		t.Fatal(err)
	}
	a := seedDownload(t, ctx, repo, "dl_dddddddddddddddddddddddddddddddd")
	b := seedDownload(t, ctx, repo, "dl_eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee")
	c := seedDownload(t, ctx, repo, "dl_ffffffffffffffffffffffffffffffff")
	if err := store.AddDependency(ctx, DependencyRecord{DownloadID: a, DependencyDownloadID: b, Requirement: SuccessRequired}); err != nil {
		t.Fatal(err)
	}
	if err := store.AddDependency(ctx, DependencyRecord{DownloadID: b, DependencyDownloadID: c, Requirement: CompletionRequired}); err != nil {
		t.Fatal(err)
	}
	if err := store.AddDependency(ctx, DependencyRecord{DownloadID: c, DependencyDownloadID: a, Requirement: CompletionRequired}); !errors.Is(err, ErrDependencyCycle) {
		t.Fatalf("want dependency cycle, got %v", err)
	}

	blocked, err := store.Eligibility(ctx, a, map[identity.DownloadID]DownloadOutcome{b: OutcomeRunning})
	if err != nil {
		t.Fatal(err)
	}
	if blocked.Eligible || len(blocked.Blocked) != 1 || blocked.Blocked[0].Reason != "dependency_not_successful" {
		t.Fatalf("bad blocked result: %+v", blocked)
	}
	failed, err := store.Eligibility(ctx, a, map[identity.DownloadID]DownloadOutcome{b: OutcomeFailed})
	if err != nil {
		t.Fatal(err)
	}
	if failed.Eligible || failed.Blocked[0].Reason != "dependency_failed" {
		t.Fatalf("bad failure propagation: %+v", failed)
	}
	ready, err := store.Eligibility(ctx, a, map[identity.DownloadID]DownloadOutcome{b: OutcomeSucceeded})
	if err != nil {
		t.Fatal(err)
	}
	if !ready.Eligible || len(ready.Blocked) != 0 {
		t.Fatalf("retry success should unblock: %+v", ready)
	}
	bReady, err := store.Eligibility(ctx, b, map[identity.DownloadID]DownloadOutcome{c: OutcomeCanceled})
	if err != nil {
		t.Fatal(err)
	}
	if !bReady.Eligible {
		t.Fatalf("completion dependency accepts terminal canceled: %+v", bReady)
	}
}
