//go:build cgo

package sqlite

import (
	"context"
	"errors"
	"sync"
	"sync/atomic"
	"testing"

	"github.com/subhra74/xdm/engine/domain/backend"
	"github.com/subhra74/xdm/engine/domain/identity"
)

func mustBackendTaskID(t *testing.T, value string) identity.BackendTaskID {
	t.Helper()
	id, err := identity.ParseBackendTaskID(value)
	if err != nil {
		t.Fatal(err)
	}
	return id
}

func TestReserveAttemptGenerationAtomicallyAdvancesAuthority(t *testing.T) {
	db, _ := openTestDB(t)
	repo, id := seedRepository(t, db)
	ctx := context.Background()
	first, download, err := repo.ReserveAttemptGeneration(ctx, id, mustRevision(t, 1), "native", 2)
	if err != nil {
		t.Fatal(err)
	}
	if first.Generation.Int64() != 1 || download.Revision.Int64() != 2 || download.CurrentAttempt == nil || download.CurrentAttempt.Int64() != 1 {
		t.Fatalf("unexpected first reservation: attempt=%+v download=%+v", first, download)
	}
	running, err := repo.MutateAttempt(ctx, id, first.Generation, first.Revision, "running", "", "", 3)
	if err != nil {
		t.Fatal(err)
	}
	second, download, err := repo.ReserveAttemptGeneration(ctx, id, download.Revision, "native", 4)
	if err != nil {
		t.Fatal(err)
	}
	if second.Generation.Int64() != 2 || download.CurrentAttempt == nil || download.CurrentAttempt.Int64() != 2 {
		t.Fatalf("unexpected second reservation: attempt=%+v download=%+v", second, download)
	}
	if _, err := repo.MutateAttempt(ctx, id, first.Generation, running.Revision, "completed", "", "", 5); !errors.Is(err, ErrStaleAttempt) {
		t.Fatalf("stale attempt mutated authoritative state: %v", err)
	}
	rows, err := db.Query(ctx, `SELECT state,revision FROM download_attempts WHERE download_id=? AND attempt_generation=1`, id.String())
	if err != nil {
		t.Fatal(err)
	}
	if rows[0][0].Text != "running" || rows[0][1].I64 != running.Revision.Int64() {
		t.Fatalf("stale write changed historical attempt: %v", rows)
	}
}

func TestConcurrentGenerationReservationExactlyOneWins(t *testing.T) {
	db, path := openTestDB(t)
	_, id := seedRepository(t, db)
	ctx := context.Background()
	const writers = 12
	var wins, stale, unexpected atomic.Int64
	var wg sync.WaitGroup
	for i := 0; i < writers; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			conn, err := Open(path, DefaultOptions())
			if err != nil {
				unexpected.Add(1)
				return
			}
			defer conn.Close()
			repo, _ := NewRepository(conn)
			_, _, err = repo.ReserveAttemptGeneration(ctx, id, mustRevision(t, 1), "native", 2)
			if err == nil {
				wins.Add(1)
			} else if errors.Is(err, ErrStaleWrite) {
				stale.Add(1)
			} else {
				unexpected.Add(1)
			}
		}()
	}
	wg.Wait()
	if wins.Load() != 1 || stale.Load() != writers-1 || unexpected.Load() != 0 {
		t.Fatalf("wins=%d stale=%d unexpected=%d", wins.Load(), stale.Load(), unexpected.Load())
	}
}

func TestOwnershipProtocolFencesActivationAndByteAuthority(t *testing.T) {
	db, _ := openTestDB(t)
	repo, id := seedRepository(t, db)
	ctx := context.Background()
	attempt, download, err := repo.ReserveAttemptGeneration(ctx, id, mustRevision(t, 1), "native", 2)
	if err != nil {
		t.Fatal(err)
	}
	claim, err := repo.CreateOwnershipClaim(ctx, id, attempt.Generation, "native", "stage-1", "runtime-1", 3)
	if err != nil {
		t.Fatal(err)
	}
	if err := repo.AssertAuthoritativeWriter(ctx, id, attempt.Generation); !errors.Is(err, ErrOwnershipNotActive) {
		t.Fatalf("writer active before task: %v", err)
	}
	if _, _, err := repo.ActivateOwnership(ctx, id, attempt.Generation, claim.Revision, 4); !errors.Is(err, ErrOwnershipState) {
		t.Fatalf("activation skipped task/ready: %v", err)
	}
	taskID := mustBackendTaskID(t, "bt_00000000000000000000000000000001")
	bound, task, err := repo.BindBackendTask(ctx, id, attempt.Generation, claim.Revision, taskID, "native-1", "runtime-1", 5)
	if err != nil {
		t.Fatal(err)
	}
	if bound.State != backend.OwnershipTaskBound || task.State != backend.TaskPrepared {
		t.Fatalf("unexpected binding: %+v %+v", bound, task)
	}
	if _, _, err := repo.ActivateOwnership(ctx, id, attempt.Generation, bound.Revision, 6); !errors.Is(err, ErrOwnershipState) {
		t.Fatalf("activation skipped ready: %v", err)
	}
	ready, err := repo.MarkOwnershipReady(ctx, id, attempt.Generation, bound.Revision, 7)
	if err != nil {
		t.Fatal(err)
	}
	active, activeTask, err := repo.ActivateOwnership(ctx, id, attempt.Generation, ready.Revision, 8)
	if err != nil {
		t.Fatal(err)
	}
	if active.State != backend.OwnershipActive || activeTask.State != backend.TaskActive {
		t.Fatalf("unexpected active records: %+v %+v", active, activeTask)
	}
	if err := repo.AssertAuthoritativeWriter(ctx, id, attempt.Generation); err != nil {
		t.Fatalf("active owner denied: %v", err)
	}
	_, _, err = repo.ReserveAttemptGeneration(ctx, id, download.Revision, "native", 9)
	if err != nil {
		t.Fatal(err)
	}
	if err := repo.AssertAuthoritativeWriter(ctx, id, attempt.Generation); !errors.Is(err, ErrStaleAttempt) {
		t.Fatalf("superseded owner retained authority: %v", err)
	}
}

func TestOwnershipCASRejectsStaleRevision(t *testing.T) {
	db, _ := openTestDB(t)
	repo, id := seedRepository(t, db)
	ctx := context.Background()
	attempt, _, err := repo.ReserveAttemptGeneration(ctx, id, mustRevision(t, 1), "native", 2)
	if err != nil {
		t.Fatal(err)
	}
	claim, err := repo.CreateOwnershipClaim(ctx, id, attempt.Generation, "native", "stage", "", 3)
	if err != nil {
		t.Fatal(err)
	}
	taskID := mustBackendTaskID(t, "bt_00000000000000000000000000000002")
	bound, _, err := repo.BindBackendTask(ctx, id, attempt.Generation, claim.Revision, taskID, "", "", 4)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := repo.MarkOwnershipReady(ctx, id, attempt.Generation, claim.Revision, 5); !errors.Is(err, ErrStaleWrite) {
		t.Fatalf("want stale ownership revision, got %v", err)
	}
	if bound.State != backend.OwnershipTaskBound {
		t.Fatalf("unexpected bound state %s", bound.State)
	}
}
