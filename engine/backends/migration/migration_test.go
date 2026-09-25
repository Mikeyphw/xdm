//go:build cgo

package migration

import (
	"context"
	"errors"
	"path/filepath"
	"testing"

	"github.com/subhra74/xdm/engine/domain/backend"
	"github.com/subhra74/xdm/engine/domain/identity"
	"github.com/subhra74/xdm/engine/foundation/idgen/idgentest"
	"github.com/subhra74/xdm/engine/store/sqlite"
)

var errCrash = errors.New("simulated crash")

type fakeSource struct {
	reuse           ReuseDecision
	quiesced        bool
	retired         bool
	quiesceCalls    int
	inspectCalls    int
	retireCalls     int
	failQuiesceOnce bool
	failRetireOnce  bool
}

func (s *fakeSource) Quiesce(context.Context, sqlite.BackendBinding) error {
	s.quiesceCalls++
	s.quiesced = true
	if s.failQuiesceOnce {
		s.failQuiesceOnce = false
		return errCrash
	}
	return nil
}
func (s *fakeSource) Inspect(context.Context, sqlite.BackendBinding) (ReuseDecision, error) {
	s.inspectCalls++
	if !s.quiesced {
		return ReuseDecision{}, errors.New("inspect before quiesce")
	}
	return s.reuse, nil
}
func (s *fakeSource) Retire(context.Context, sqlite.BackendBinding) error {
	s.retireCalls++
	s.retired = true
	if s.failRetireOnce {
		s.failRetireOnce = false
		return errCrash
	}
	return nil
}

type fixture struct {
	t                *testing.T
	repo             *sqlite.Repository
	db               *sqlite.DB
	ids              *idgentest.Counter
	dl               identity.DownloadID
	sourceGeneration identity.AttemptGeneration
	sourceKind       string
	targetKind       string
	source           *fakeSource
	target           *StoreTarget
	prepareCalls     int
	activateCalls    int
	prepared         bool
	activated        bool
	failPrepareOnce  bool
	failActivateOnce bool
}

func revision(t *testing.T, n int64) identity.Revision {
	t.Helper()
	v, err := identity.NewRevision(n)
	if err != nil {
		t.Fatal(err)
	}
	return v
}

func newFixture(t *testing.T, sourceKind, targetKind string) *fixture {
	t.Helper()
	path := filepath.Join(t.TempDir(), "migration.sqlite")
	db, err := sqlite.Open(path, sqlite.DefaultOptions())
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = db.Close() })
	if err = sqlite.Migrate(context.Background(), db, 1); err != nil {
		t.Fatal(err)
	}
	repo, err := sqlite.NewRepository(db)
	if err != nil {
		t.Fatal(err)
	}
	ids := idgentest.NewCounter(1)
	req, _ := identity.NewRequestID(ids)
	dl, _ := identity.NewDownloadID(ids)
	if err = repo.CreateRequest(context.Background(), sqlite.RequestRecord{ID: req, Revision: revision(t, 1), ResourceIdentity: "resource/migration", Method: "GET", SafeSpecJSON: `{"url":"https://example.test/file"}`, CreatedAtUnixMS: 1}); err != nil {
		t.Fatal(err)
	}
	if err = repo.CreateDownload(context.Background(), sqlite.DownloadRecord{ID: dl, RequestID: req, CurrentRequestRevision: revision(t, 1), State: "queued", Revision: revision(t, 1), CreatedAtUnixMS: 1, UpdatedAtUnixMS: 1}); err != nil {
		t.Fatal(err)
	}
	attempt, _, err := repo.ReserveAttemptGeneration(context.Background(), dl, revision(t, 1), sourceKind, 2)
	if err != nil {
		t.Fatal(err)
	}
	own, err := repo.CreateOwnershipClaim(context.Background(), dl, attempt.Generation, sourceKind, "source-stage", "source-runtime", 3)
	if err != nil {
		t.Fatal(err)
	}
	taskID, _ := identity.NewBackendTaskID(ids)
	own, _, err = repo.BindBackendTask(context.Background(), dl, attempt.Generation, own.Revision, taskID, sourceKind+"-task", "source-runtime", 4)
	if err != nil {
		t.Fatal(err)
	}
	own, err = repo.MarkOwnershipReady(context.Background(), dl, attempt.Generation, own.Revision, 5)
	if err != nil {
		t.Fatal(err)
	}
	if _, _, err = repo.ActivateOwnership(context.Background(), dl, attempt.Generation, own.Revision, 6); err != nil {
		t.Fatal(err)
	}
	attempt, err = repo.MutateAttempt(context.Background(), dl, attempt.Generation, attempt.Revision, "running", "", "", 7)
	if err != nil {
		t.Fatal(err)
	}

	f := &fixture{t: t, repo: repo, db: db, ids: ids, dl: dl, sourceGeneration: attempt.Generation, sourceKind: sourceKind, targetKind: targetKind}
	f.source = &fakeSource{reuse: ReuseDecision{StagingIdentity: "source-stage", BytesPresent: 0, CheckpointReusable: true, Detail: "empty source staging is reusable"}}
	f.target = &StoreTarget{Repo: repo, Kind: targetKind, IDs: ids, RuntimeIdentity: "target-runtime", Now: nil}
	f.target.StagingIdentity = func(req EstablishRequest) (string, error) {
		if req.Reuse.CheckpointReusable && req.Reuse.StagingIdentity != "" {
			return req.Reuse.StagingIdentity, nil
		}
		return "target-fresh-stage", nil
	}
	f.target.Prepare = func(_ context.Context, _ EstablishRequest, _ sqlite.BackendBinding) error {
		f.prepareCalls++
		f.prepared = true
		if f.failPrepareOnce {
			f.failPrepareOnce = false
			return errCrash
		}
		return nil
	}
	f.target.ActivatePrepared = func(_ context.Context, _ sqlite.BackendBinding) error {
		f.activateCalls++
		f.activated = true
		if f.failActivateOnce {
			f.failActivateOnce = false
			return errCrash
		}
		return nil
	}
	return f
}

func (f *fixture) coordinator() *Coordinator {
	return &Coordinator{Repo: f.repo, Sources: map[string]Source{f.sourceKind: f.source}, Targets: map[string]Target{f.targetKind: f.target}}
}

func (f *fixture) assertComplete(t *testing.T, r Result) {
	t.Helper()
	if r.State != StateComplete {
		t.Fatalf("state=%s", r.State)
	}
	if !f.source.quiesced || !f.source.retired || !f.prepared || !f.activated {
		t.Fatalf("external stages missing q=%v r=%v p=%v a=%v", f.source.quiesced, f.source.retired, f.prepared, f.activated)
	}
	current, err := f.repo.GetDownload(context.Background(), f.dl)
	if err != nil {
		t.Fatal(err)
	}
	if current.CurrentAttempt == nil || *current.CurrentAttempt != r.Migration.TargetGeneration {
		t.Fatalf("current=%+v migration=%+v", current.CurrentAttempt, r.Migration)
	}
	targetBinding, err := f.repo.GetBackendBinding(context.Background(), f.dl, r.Migration.TargetGeneration)
	if err != nil {
		t.Fatal(err)
	}
	if targetBinding.Ownership.State != backend.OwnershipActive || targetBinding.Task.State != backend.TaskActive {
		t.Fatalf("target not active: %+v", targetBinding)
	}
	sourceBinding, err := f.repo.GetBackendBinding(context.Background(), f.dl, r.Migration.SourceGeneration)
	if err != nil {
		t.Fatal(err)
	}
	if sourceBinding.Ownership.State != backend.OwnershipRetired || sourceBinding.Task.State != backend.TaskRetired {
		t.Fatalf("source not retired: %+v", sourceBinding)
	}
	if err = f.repo.AssertAuthoritativeWriter(context.Background(), f.dl, r.Migration.SourceGeneration); !errors.Is(err, sqlite.ErrStaleAttempt) {
		t.Fatalf("source writer not fenced: %v", err)
	}
}

func TestMigrationCrashRecoveryAtEveryDurableBoundary(t *testing.T) {
	boundaries := []Boundary{BoundaryReserved, BoundarySourceQuiesced, BoundaryTargetEstablished, BoundaryTargetActive, BoundarySourceRetired}
	for _, boundary := range boundaries {
		t.Run(string(boundary), func(t *testing.T) {
			f := newFixture(t, "native", "aria2")
			c := f.coordinator()
			c.AfterBoundary = func(got Boundary) error {
				if got == boundary {
					return errCrash
				}
				return nil
			}
			first, err := c.Start(context.Background(), f.dl, "aria2")
			if !errors.Is(err, errCrash) {
				t.Fatalf("expected crash at %s, result=%+v err=%v", boundary, first, err)
			}
			recovered, err := f.coordinator().Recover(context.Background(), f.dl)
			if err != nil {
				t.Fatal(err)
			}
			f.assertComplete(t, recovered)
		})
	}
}

func TestMigrationRecoversExternalEffectBeforeDurableStageCommit(t *testing.T) {
	cases := []struct {
		name         string
		configure    func(*fixture)
		wantMinCalls func(*fixture) bool
	}{
		{"quiesce", func(f *fixture) { f.source.failQuiesceOnce = true }, func(f *fixture) bool { return f.source.quiesceCalls >= 2 }},
		{"target_prepare", func(f *fixture) { f.failPrepareOnce = true }, func(f *fixture) bool { return f.prepareCalls >= 2 }},
		{"target_activate", func(f *fixture) { f.failActivateOnce = true }, func(f *fixture) bool { return f.activateCalls >= 2 }},
		{"source_retire", func(f *fixture) { f.source.failRetireOnce = true }, func(f *fixture) bool { return f.source.retireCalls >= 2 }},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			f := newFixture(t, "native", "aria2")
			tc.configure(f)
			_, err := f.coordinator().Start(context.Background(), f.dl, "aria2")
			if !errors.Is(err, errCrash) {
				t.Fatalf("expected injected crash: %v", err)
			}
			recovered, err := f.coordinator().Recover(context.Background(), f.dl)
			if err != nil {
				t.Fatal(err)
			}
			f.assertComplete(t, recovered)
			if !tc.wantMinCalls(f) {
				t.Fatalf("operation was not retried idempotently")
			}
		})
	}
}

func TestMigrationBothDirectionsAndLateSourceEventIsDiagnosticOnly(t *testing.T) {
	for _, pair := range [][2]string{{"native", "aria2"}, {"aria2", "native"}} {
		t.Run(pair[0]+"_to_"+pair[1], func(t *testing.T) {
			f := newFixture(t, pair[0], pair[1])
			result, err := f.coordinator().Start(context.Background(), f.dl, pair[1])
			if err != nil {
				t.Fatal(err)
			}
			f.assertComplete(t, result)
			if err = f.coordinator().RecordLateSourceEvent(context.Background(), result.Migration, "completed"); err != nil {
				t.Fatal(err)
			}
			diags, err := f.repo.ListAttemptDiagnostics(context.Background(), f.dl, result.Migration.TargetGeneration, "backend_migration")
			if err != nil {
				t.Fatal(err)
			}
			if len(diags) != 1 || diags[0].EventType != "stale_source_event" {
				t.Fatalf("diagnostics=%+v", diags)
			}
			old, err := f.repo.GetAttempt(context.Background(), f.dl, result.Migration.SourceGeneration)
			if err != nil {
				t.Fatal(err)
			}
			if old.State != "running" {
				t.Fatalf("late completion mutated source state: %s", old.State)
			}
		})
	}
}

func TestMigrationMakesExplicitCheckpointReuseDecision(t *testing.T) {
	f := newFixture(t, "native", "aria2")
	f.source.reuse = ReuseDecision{StagingIdentity: "source-stage", BytesPresent: 512, CheckpointReusable: false, Detail: "backend checkpoint formats differ"}
	result, err := f.coordinator().Start(context.Background(), f.dl, "aria2")
	if err != nil {
		t.Fatal(err)
	}
	target, err := f.repo.GetBackendBinding(context.Background(), f.dl, result.Migration.TargetGeneration)
	if err != nil {
		t.Fatal(err)
	}
	if target.Ownership.StagingIdentity != "target-fresh-stage" {
		t.Fatalf("unsafe checkpoint was reused: %+v", target.Ownership)
	}
}

func TestMigrationRejectsCompletedSource(t *testing.T) {
	f := newFixture(t, "native", "aria2")
	f.source.reuse = ReuseDecision{StagingIdentity: "source-stage", BytesPresent: 1024, Complete: true, CheckpointReusable: false}
	result, err := f.coordinator().Start(context.Background(), f.dl, "aria2")
	if !errors.Is(err, ErrSourceComplete) {
		t.Fatalf("result=%+v err=%v", result, err)
	}
	if result.State != StateRecoveryRequired {
		t.Fatalf("completed source did not enter explicit recovery state: %+v", result)
	}
	if f.prepared || f.activated {
		t.Fatal("target started for already-complete source")
	}
	if _, err = f.coordinator().Recover(context.Background(), f.dl); !errors.Is(err, ErrRecoveryRequired) {
		t.Fatalf("recovery state was not fail-closed: %v", err)
	}
}

func TestMigrationRecoverRejectsOrdinaryAdjacentAttempt(t *testing.T) {
	f := newFixture(t, "native", "aria2")
	download, err := f.repo.GetDownload(context.Background(), f.dl)
	if err != nil {
		t.Fatal(err)
	}
	ordinary, _, err := f.repo.ReserveAttemptGeneration(context.Background(), f.dl, download.Revision, "native", 20)
	if err != nil {
		t.Fatal(err)
	}
	if _, err = f.repo.MutateAttempt(context.Background(), f.dl, ordinary.Generation, ordinary.Revision, StateComplete, "", "", 21); err != nil {
		t.Fatal(err)
	}
	if _, err = f.coordinator().Recover(context.Background(), f.dl); !errors.Is(err, ErrNotMigration) {
		t.Fatalf("ordinary retry attempt was misclassified as migration: %v", err)
	}
}
