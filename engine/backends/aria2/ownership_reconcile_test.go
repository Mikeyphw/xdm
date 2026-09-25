//go:build cgo

package aria2

import (
	"context"
	"errors"
	"fmt"
	"path/filepath"
	"testing"

	"github.com/subhra74/xdm/engine/backends/migration"
	"github.com/subhra74/xdm/engine/domain/backend"
	"github.com/subhra74/xdm/engine/domain/identity"
	"github.com/subhra74/xdm/engine/foundation/idgen/idgentest"
	"github.com/subhra74/xdm/engine/store/sqlite"
)

type ownershipRPCFake struct {
	tasks                                                  map[string]Task
	addCalls, pauseCalls, unpauseCalls, removeCalls, saves int
}

func newOwnershipRPCFake() *ownershipRPCFake { return &ownershipRPCFake{tasks: map[string]Task{}} }
func (f *ownershipRPCFake) AddURI(_ context.Context, _ []string, opts Options) (string, error) {
	f.addCalls++
	v, ok := opts["gid"]
	if !ok || v.scalar == nil {
		return "", errors.New("missing gid")
	}
	gid := *v.scalar
	path := ""
	if p, ok := opts["out"]; ok && p.scalar != nil {
		path = *p.scalar
	}
	f.tasks[gid] = Task{GID: gid, Status: StatusPaused, TotalLength: 1024, Files: []File{{Path: path}}}
	return gid, nil
}
func (f *ownershipRPCFake) TellStatus(_ context.Context, gid string) (Task, error) {
	t, ok := f.tasks[gid]
	if !ok {
		return Task{}, fmt.Errorf("%w: unknown gid", ErrRemote)
	}
	return t, nil
}
func (f *ownershipRPCFake) Pause(_ context.Context, gid string, _ bool) error {
	t, ok := f.tasks[gid]
	if !ok {
		return fmt.Errorf("%w", ErrRemote)
	}
	f.pauseCalls++
	t.Status = StatusPaused
	f.tasks[gid] = t
	return nil
}
func (f *ownershipRPCFake) Unpause(_ context.Context, gid string) error {
	t, ok := f.tasks[gid]
	if !ok {
		return fmt.Errorf("%w", ErrRemote)
	}
	f.unpauseCalls++
	t.Status = StatusActive
	f.tasks[gid] = t
	return nil
}
func (f *ownershipRPCFake) Remove(_ context.Context, gid string, _ bool) error {
	t, ok := f.tasks[gid]
	if !ok {
		return fmt.Errorf("%w", ErrRemote)
	}
	f.removeCalls++
	t.Status = StatusRemoved
	f.tasks[gid] = t
	return nil
}
func (f *ownershipRPCFake) SaveSession(context.Context) error { f.saves++; return nil }
func (f *ownershipRPCFake) TellActive(context.Context) ([]Task, error) {
	return f.byStatus(StatusActive), nil
}
func (f *ownershipRPCFake) TellWaiting(_ context.Context, offset, count int) ([]Task, error) {
	all := append(f.byStatus(StatusWaiting), f.byStatus(StatusPaused)...)
	return pageTasks(all, offset, count), nil
}
func (f *ownershipRPCFake) TellStopped(_ context.Context, offset, count int) ([]Task, error) {
	all := append(append(f.byStatus(StatusComplete), f.byStatus(StatusRemoved)...), f.byStatus(StatusError)...)
	return pageTasks(all, offset, count), nil
}
func (f *ownershipRPCFake) byStatus(status Status) []Task {
	out := []Task{}
	for _, t := range f.tasks {
		if t.Status == status {
			out = append(out, t)
		}
	}
	return out
}
func pageTasks(in []Task, offset, count int) []Task {
	if offset < 0 {
		offset = 0
	}
	if offset >= len(in) {
		return nil
	}
	end := offset + count
	if end > len(in) {
		end = len(in)
	}
	return append([]Task(nil), in[offset:end]...)
}

type ownershipFixture struct {
	repo  *sqlite.Repository
	db    *sqlite.DB
	dl    identity.DownloadID
	gen   identity.AttemptGeneration
	ids   *idgentest.Counter
	rpc   *ownershipRPCFake
	owner *DurableOwner
}

func ownRev(t *testing.T, n int64) identity.Revision {
	t.Helper()
	v, e := identity.NewRevision(n)
	if e != nil {
		t.Fatal(e)
	}
	return v
}
func newOwnershipFixture(t *testing.T) *ownershipFixture {
	t.Helper()
	db, e := sqlite.Open(filepath.Join(t.TempDir(), "aria2.sqlite"), sqlite.DefaultOptions())
	if e != nil {
		t.Fatal(e)
	}
	t.Cleanup(func() { _ = db.Close() })
	if e = sqlite.Migrate(context.Background(), db, 1); e != nil {
		t.Fatal(e)
	}
	repo, e := sqlite.NewRepository(db)
	if e != nil {
		t.Fatal(e)
	}
	ids := idgentest.NewCounter(100)
	req, _ := identity.NewRequestID(ids)
	dl, _ := identity.NewDownloadID(ids)
	if e = repo.CreateRequest(context.Background(), sqlite.RequestRecord{ID: req, Revision: ownRev(t, 1), ResourceIdentity: "aria2-resource", Method: "GET", SafeSpecJSON: `{"url":"https://example.test/a"}`, CreatedAtUnixMS: 1}); e != nil {
		t.Fatal(e)
	}
	if e = repo.CreateDownload(context.Background(), sqlite.DownloadRecord{ID: dl, RequestID: req, CurrentRequestRevision: ownRev(t, 1), State: "queued", Revision: ownRev(t, 1), CreatedAtUnixMS: 1, UpdatedAtUnixMS: 1}); e != nil {
		t.Fatal(e)
	}
	a, _, e := repo.ReserveAttemptGeneration(context.Background(), dl, ownRev(t, 1), BackendKind, 2)
	if e != nil {
		t.Fatal(e)
	}
	rpc := newOwnershipRPCFake()
	owner := &DurableOwner{Repo: repo, RPC: rpc, IDs: ids}
	return &ownershipFixture{repo: repo, db: db, dl: dl, gen: a.Generation, ids: ids, rpc: rpc, owner: owner}
}
func (f *ownershipFixture) request(runtime string) PrepareRequest {
	return PrepareRequest{DownloadID: f.dl, Generation: f.gen, URIs: []string{"https://example.test/a"}, Options: Options{"out": StringOption("stage.part")}, RuntimeIdentity: runtime, StagingIdentity: "stage.part"}
}
func (f *ownershipFixture) ready(t *testing.T, runtime string) Binding {
	t.Helper()
	b, e := f.owner.EnsurePaused(context.Background(), f.request(runtime))
	if e != nil {
		t.Fatal(e)
	}
	return b
}
func (f *ownershipFixture) active(t *testing.T, runtime string) Binding {
	t.Helper()
	b := f.ready(t, runtime)
	b, e := f.owner.Activate(context.Background(), b)
	if e != nil {
		t.Fatal(e)
	}
	return b
}

func TestOwnershipCrashBeforeBindingAndAfterBindingBeforeActivation(t *testing.T) {
	f := newOwnershipFixture(t)
	claim, e := f.repo.CreateOwnershipClaim(context.Background(), f.dl, f.gen, BackendKind, "stage.part", "runtime-A", 3)
	if e != nil {
		t.Fatal(e)
	}
	if claim.State != backend.OwnershipClaimed {
		t.Fatalf("claim=%+v", claim)
	}
	b, e := f.owner.EnsurePaused(context.Background(), f.request("runtime-A"))
	if e != nil {
		t.Fatal(e)
	}
	if b.Ownership.State != backend.OwnershipReady || b.Task.State != backend.TaskPrepared {
		t.Fatalf("binding=%+v", b)
	}
	if f.rpc.tasks[b.GID].Status != StatusPaused {
		t.Fatalf("daemon escaped paused boundary: %+v", f.rpc.tasks[b.GID])
	}
	b, e = f.owner.Activate(context.Background(), b)
	if e != nil {
		t.Fatal(e)
	}
	if b.Ownership.State != backend.OwnershipActive || b.Task.State != backend.TaskActive || f.rpc.tasks[b.GID].Status != StatusActive {
		t.Fatalf("activation=%+v daemon=%+v", b, f.rpc.tasks[b.GID])
	}
}

func TestActivationWithoutDurableOwnershipForbidden(t *testing.T) {
	f := newOwnershipFixture(t)
	fake := Binding{DownloadID: f.dl, Generation: f.gen, GID: "0123456789abcdef", Runtime: "runtime-A", Staging: "stage.part"}
	if _, e := f.owner.Activate(context.Background(), fake); !errors.Is(e, sqlite.ErrNotFound) {
		t.Fatalf("activation without owner: %v", e)
	}
}

func TestStaleGIDAndRuntimeUpdatesRejected(t *testing.T) {
	f := newOwnershipFixture(t)
	b := f.active(t, "runtime-A")
	wrongGID := b
	wrongGID.GID = "fedcba9876543210"
	if e := f.owner.AcceptUpdate(context.Background(), wrongGID); !errors.Is(e, sqlite.ErrBackendTaskMismatch) {
		t.Fatalf("wrong GID accepted: %v", e)
	}
	wrongRuntime := b
	wrongRuntime.Runtime = "runtime-B"
	if e := f.owner.AcceptUpdate(context.Background(), wrongRuntime); !errors.Is(e, sqlite.ErrRuntimeIdentityMismatch) {
		t.Fatalf("wrong runtime accepted: %v", e)
	}
}

func TestDaemonRestartAfterBindingBeforeActivationRebindsReadyOwner(t *testing.T) {
	f := newOwnershipFixture(t)
	old := f.ready(t, "runtime-A")
	task := f.rpc.tasks[old.GID]
	task.Status = StatusPaused
	task.Files = []File{{Path: "stage.part"}}
	f.rpc.tasks[old.GID] = task
	results, err := (&Reconciler{Repo: f.repo, RPC: f.rpc, Owner: f.owner, RuntimeIdentity: "runtime-B"}).ReconcileAll(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if len(results) != 1 || results[0].Class != ReconcileRuntimeRebound || results[0].NewBinding == nil {
		t.Fatalf("results=%+v", results)
	}
	fresh := *results[0].NewBinding
	if fresh.Ownership.State != backend.OwnershipReady || fresh.Task.State != backend.TaskPrepared {
		t.Fatalf("rebound ready owner=%+v", fresh)
	}
	historical, err := f.repo.GetBackendBinding(context.Background(), f.dl, old.Generation)
	if err != nil {
		t.Fatal(err)
	}
	if historical.Ownership.State != backend.OwnershipAbandoned || historical.Task.State != backend.TaskRetired {
		t.Fatalf("pre-activation historical owner not abandoned: %+v", historical)
	}
	if err = f.owner.AcceptUpdate(context.Background(), old); !errors.Is(err, sqlite.ErrStaleAttempt) {
		t.Fatalf("old ready owner accepted after runtime rebound: %v", err)
	}
}

func TestDaemonRestartSameGIDRequiresFreshGenerationAndStagingProof(t *testing.T) {
	f := newOwnershipFixture(t)
	old := f.active(t, "runtime-A")
	task := f.rpc.tasks[old.GID]
	task.Status = StatusActive
	task.Files = []File{{Path: "stage.part"}}
	f.rpc.tasks[old.GID] = task
	reconciler := &Reconciler{Repo: f.repo, RPC: f.rpc, Owner: f.owner, RuntimeIdentity: "runtime-B"}
	results, e := reconciler.ReconcileAll(context.Background())
	if e != nil {
		t.Fatal(e)
	}
	if len(results) != 1 || results[0].Class != ReconcileRuntimeRebound || results[0].NewBinding == nil {
		t.Fatalf("results=%+v", results)
	}
	fresh := *results[0].NewBinding
	if fresh.Generation.Int64() != old.Generation.Int64()+1 || fresh.Runtime != "runtime-B" || fresh.GID != old.GID {
		t.Fatalf("rebound=%+v old=%+v", fresh, old)
	}
	if e = f.owner.AcceptUpdate(context.Background(), old); !errors.Is(e, sqlite.ErrStaleAttempt) {
		t.Fatalf("stale runtime writer accepted after rebound: %v", e)
	}
	historical, e := f.repo.GetBackendBinding(context.Background(), f.dl, old.Generation)
	if e != nil {
		t.Fatal(e)
	}
	if !historical.Ownership.State.Terminal() || historical.Task.State != backend.TaskRetired {
		t.Fatalf("historical owner not closed: %+v", historical)
	}
}

func TestDaemonRestartSameGIDDifferentStagingIsRecoveryNotRebind(t *testing.T) {
	f := newOwnershipFixture(t)
	old := f.active(t, "runtime-A")
	task := f.rpc.tasks[old.GID]
	task.Files = []File{{Path: "different.part"}}
	f.rpc.tasks[old.GID] = task
	results, e := (&Reconciler{Repo: f.repo, RPC: f.rpc, Owner: f.owner, RuntimeIdentity: "runtime-B"}).ReconcileAll(context.Background())
	if e != nil {
		t.Fatal(e)
	}
	if len(results) != 1 || results[0].Class != ReconcileRuntimeReplaced || results[0].NewBinding != nil {
		t.Fatalf("results=%+v", results)
	}
	a, e := f.repo.GetAttempt(context.Background(), f.dl, old.Generation)
	if e != nil {
		t.Fatal(e)
	}
	if a.State != "backend_recovery_required" {
		t.Fatalf("state=%s", a.State)
	}
}

func TestReconciliationMissingPausedRemovedAndOfflineComplete(t *testing.T) {
	t.Run("missing", func(t *testing.T) {
		f := newOwnershipFixture(t)
		b := f.active(t, "runtime-A")
		delete(f.rpc.tasks, b.GID)
		r, e := (&Reconciler{Repo: f.repo, RPC: f.rpc, Owner: f.owner, RuntimeIdentity: "runtime-A"}).ReconcileAll(context.Background())
		if e != nil {
			t.Fatal(e)
		}
		if len(r) != 1 || r[0].Class != ReconcileMissing {
			t.Fatalf("%+v", r)
		}
	})
	t.Run("paused", func(t *testing.T) {
		f := newOwnershipFixture(t)
		b := f.active(t, "runtime-A")
		task := f.rpc.tasks[b.GID]
		task.Status = StatusPaused
		f.rpc.tasks[b.GID] = task
		r, e := (&Reconciler{Repo: f.repo, RPC: f.rpc, Owner: f.owner, RuntimeIdentity: "runtime-A"}).ReconcileAll(context.Background())
		if e != nil {
			t.Fatal(e)
		}
		if len(r) != 1 || r[0].Class != ReconcilePaused {
			t.Fatalf("%+v", r)
		}
	})
	t.Run("removed", func(t *testing.T) {
		f := newOwnershipFixture(t)
		b := f.active(t, "runtime-A")
		task := f.rpc.tasks[b.GID]
		task.Status = StatusRemoved
		f.rpc.tasks[b.GID] = task
		r, e := (&Reconciler{Repo: f.repo, RPC: f.rpc, Owner: f.owner, RuntimeIdentity: "runtime-A"}).ReconcileAll(context.Background())
		if e != nil {
			t.Fatal(e)
		}
		if len(r) != 1 || r[0].Class != ReconcileRemoved {
			t.Fatalf("%+v", r)
		}
	})
	t.Run("offline_complete", func(t *testing.T) {
		f := newOwnershipFixture(t)
		b := f.active(t, "runtime-A")
		task := f.rpc.tasks[b.GID]
		task.Status = StatusComplete
		task.CompletedLength = task.TotalLength
		f.rpc.tasks[b.GID] = task
		r, e := (&Reconciler{Repo: f.repo, RPC: f.rpc, Owner: f.owner, RuntimeIdentity: "runtime-A"}).ReconcileAll(context.Background())
		if e != nil {
			t.Fatal(e)
		}
		if len(r) != 1 || r[0].Class != ReconcileOfflineComplete {
			t.Fatalf("%+v", r)
		}
		a, e := f.repo.GetAttempt(context.Background(), f.dl, b.Generation)
		if e != nil {
			t.Fatal(e)
		}
		if a.State != "transport_complete" {
			t.Fatalf("state=%s", a.State)
		}
	})
}

func TestDaemonRestartAtomicRebindRollsBackOnTargetInsertFailure(t *testing.T) {
	f := newOwnershipFixture(t)
	old := f.active(t, "runtime-A")
	download, err := f.repo.GetDownload(context.Background(), f.dl)
	if err != nil {
		t.Fatal(err)
	}
	_, _, err = f.repo.RebindBackendTaskGeneration(
		context.Background(), f.dl, old.Generation, download.Revision, old.Task.ID,
		old.GID, "runtime-B", old.Staging, "running", true, 100,
	)
	if err == nil {
		t.Fatal("expected duplicate backend task id to abort atomic rebind")
	}
	after, err := f.repo.GetDownload(context.Background(), f.dl)
	if err != nil {
		t.Fatal(err)
	}
	if after.CurrentAttempt == nil || *after.CurrentAttempt != old.Generation {
		t.Fatalf("failed rebind changed current generation: %+v", after.CurrentAttempt)
	}
	binding, err := f.repo.GetBackendBinding(context.Background(), f.dl, old.Generation)
	if err != nil {
		t.Fatal(err)
	}
	if binding.Ownership.State != backend.OwnershipActive || binding.Task.State != backend.TaskActive {
		t.Fatalf("failed rebind partially retired source: %+v", binding)
	}
	if _, err = f.repo.GetAttempt(context.Background(), f.dl, identity.AttemptGeneration(old.Generation.Int64()+1)); !errors.Is(err, sqlite.ErrNotFound) {
		t.Fatalf("failed rebind left target attempt behind: %v", err)
	}
}

func TestReconciliationMigrationSourceMissingFailsClosed(t *testing.T) {
	f := newOwnershipFixture(t)
	old := f.active(t, "runtime-A")
	record, err := f.repo.GetBackendBinding(context.Background(), f.dl, old.Generation)
	if err != nil {
		t.Fatal(err)
	}
	delete(f.rpc.tasks, old.GID)
	if _, err = (MigrationSource{RPC: f.rpc}).Inspect(context.Background(), record); !errors.Is(err, migration.ErrUnsafeReuse) {
		t.Fatalf("missing aria2 source was treated as safe empty staging: %v", err)
	}
}
