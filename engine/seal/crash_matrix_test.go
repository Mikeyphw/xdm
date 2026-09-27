package seal

import (
	"context"
	"errors"
	"path/filepath"
	"testing"

	"github.com/subhra74/xdm/engine/domain/backend"
	"github.com/subhra74/xdm/engine/domain/identity"
	store "github.com/subhra74/xdm/engine/store/sqlite"
)

func TestCrashMatrixOwnershipDurabilityReopenBoundaries(t *testing.T) {
	if !store.Available {
		t.Skip("sqlite unavailable")
	}
	ctx := context.Background()
	path := filepath.Join(t.TempDir(), "engine.sqlite")

	open := func(t *testing.T) (*store.DB, *store.Repository) {
		t.Helper()
		db, err := store.Open(path, store.DefaultOptions())
		if err != nil {
			t.Fatal(err)
		}
		if err := store.Migrate(ctx, db, 1); err != nil {
			db.Close()
			t.Fatal(err)
		}
		repo, err := store.NewRepository(db)
		if err != nil {
			db.Close()
			t.Fatal(err)
		}
		return db, repo
	}

	db, repo := open(t)
	req, _ := identity.ParseRequestID("req_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
	dl, _ := identity.ParseDownloadID("dl_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
	r1, _ := identity.NewRevision(1)
	if err := repo.CreateRequest(ctx, store.RequestRecord{ID: req, Revision: r1, ResourceIdentity: "seal", Method: "GET", SafeSpecJSON: `{"seal":true}`, CreatedAtUnixMS: 1}); err != nil {
		t.Fatal(err)
	}
	if err := repo.CreateDownload(ctx, store.DownloadRecord{ID: dl, RequestID: req, CurrentRequestRevision: r1, State: "open", Revision: r1, CreatedAtUnixMS: 1, UpdatedAtUnixMS: 1}); err != nil {
		t.Fatal(err)
	}

	attempt, downloadRow, err := repo.ReserveAttemptGeneration(ctx, dl, r1, "native", 2)
	if err != nil {
		t.Fatal(err)
	}
	if err := db.Close(); err != nil {
		t.Fatal(err)
	}

	// after generation reserve
	db, repo = open(t)
	persistedAttempt, err := repo.GetAttempt(ctx, dl, attempt.Generation)
	if err != nil || persistedAttempt.Generation != attempt.Generation {
		t.Fatalf("generation reserve not durable: %+v %v", persistedAttempt, err)
	}
	if current, err := repo.GetDownload(ctx, dl); err != nil || current.CurrentAttempt == nil || *current.CurrentAttempt != attempt.Generation {
		t.Fatalf("current generation not durable: %+v %v", current, err)
	}

	claim, err := repo.CreateOwnershipClaim(ctx, dl, attempt.Generation, "native", "stage-crashmatrix", "runtime-a", 3)
	if err != nil {
		t.Fatal(err)
	}
	if err := db.Close(); err != nil {
		t.Fatal(err)
	}

	// after ownership claim
	db, repo = open(t)
	ownership, err := repo.GetOwnership(ctx, dl, attempt.Generation)
	if err != nil {
		t.Fatal(err)
	}
	if ownership.State != backend.OwnershipClaimed {
		t.Fatalf("claim state=%s", ownership.State)
	}
	if err := repo.AssertAuthoritativeWriter(ctx, dl, attempt.Generation); !errors.Is(err, store.ErrOwnershipNotActive) {
		t.Fatalf("claimed writer became authoritative: %v", err)
	}

	taskID, _ := identity.ParseBackendTaskID("bt_cccccccccccccccccccccccccccccccc")
	bound, task, err := repo.BindBackendTask(ctx, dl, attempt.Generation, claim.Revision, taskID, "external-a", "runtime-a", 4)
	if err != nil {
		t.Fatal(err)
	}
	if task.State != backend.TaskPrepared {
		t.Fatalf("task state=%s", task.State)
	}
	if err := db.Close(); err != nil {
		t.Fatal(err)
	}

	// after backend task create
	db, repo = open(t)
	binding, err := repo.GetBackendBinding(ctx, dl, attempt.Generation)
	if err != nil {
		t.Fatal(err)
	}
	if binding.Ownership.State != backend.OwnershipTaskBound || binding.Task.State != backend.TaskPrepared {
		t.Fatalf("bound state=%+v", binding)
	}
	if err := repo.AssertAuthoritativeWriter(ctx, dl, attempt.Generation); !errors.Is(err, store.ErrOwnershipNotActive) {
		t.Fatalf("task-bound writer became authoritative: %v", err)
	}

	ready, err := repo.MarkOwnershipReady(ctx, dl, attempt.Generation, bound.Revision, 5)
	if err != nil {
		t.Fatal(err)
	}
	if err := db.Close(); err != nil {
		t.Fatal(err)
	}

	// before activation
	db, repo = open(t)
	binding, err = repo.GetBackendBinding(ctx, dl, attempt.Generation)
	if err != nil {
		t.Fatal(err)
	}
	if binding.Ownership.State != backend.OwnershipReady || binding.Task.State != backend.TaskPrepared {
		t.Fatalf("ready state=%+v", binding)
	}
	if err := repo.AssertAuthoritativeWriter(ctx, dl, attempt.Generation); !errors.Is(err, store.ErrOwnershipNotActive) {
		t.Fatalf("ready writer became authoritative: %v", err)
	}

	active, activeTask, err := repo.ActivateOwnership(ctx, dl, attempt.Generation, ready.Revision, 6)
	if err != nil {
		t.Fatal(err)
	}
	if active.State != backend.OwnershipActive || activeTask.State != backend.TaskActive {
		t.Fatalf("activation=%+v task=%+v", active, activeTask)
	}
	if err := db.Close(); err != nil {
		t.Fatal(err)
	}

	// after activation
	db, repo = open(t)
	defer db.Close()
	binding, err = repo.GetBackendBinding(ctx, dl, attempt.Generation)
	if err != nil {
		t.Fatal(err)
	}
	if binding.Ownership.State != backend.OwnershipActive || binding.Task.State != backend.TaskActive {
		t.Fatalf("active state=%+v", binding)
	}
	if err := repo.AssertAuthoritativeWriter(ctx, dl, attempt.Generation); err != nil {
		t.Fatalf("active writer rejected: %v", err)
	}

	// A stale generation cannot regain authority after a later generation reserve.
	_ = downloadRow // reserve returned the post-mutation row; reload below to avoid stale-revision assumptions.
	current, err := repo.GetDownload(ctx, dl)
	if err != nil {
		t.Fatal(err)
	}
	_, _, err = repo.ReserveAttemptGeneration(ctx, dl, current.Revision, "aria2", 7)
	if err != nil {
		t.Fatal(err)
	}
	if err := repo.AssertAuthoritativeWriter(ctx, dl, attempt.Generation); !errors.Is(err, store.ErrStaleAttempt) {
		t.Fatalf("stale writer not fenced: %v", err)
	}
}
