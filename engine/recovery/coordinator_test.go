//go:build cgo

package recovery

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"testing"

	"github.com/subhra74/xdm/engine/domain/identity"
	"github.com/subhra74/xdm/engine/domain/publication"
	"github.com/subhra74/xdm/engine/store/checkpoint"
	store "github.com/subhra74/xdm/engine/store/sqlite"
)

func rrev(v int64) identity.Revision { r, _ := identity.NewRevision(v); return r }

func recoveryRepo(t *testing.T, suffix string) (*store.DB, *store.Repository, identity.DownloadID) {
	t.Helper()
	dir := t.TempDir()
	db, err := store.Open(filepath.Join(dir, "engine.sqlite"), store.DefaultOptions())
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = db.Close() })
	ctx := context.Background()
	if err = store.Migrate(ctx, db, 1); err != nil {
		t.Fatal(err)
	}
	repo, _ := store.NewRepository(db)
	req, err := identity.ParseRequestID("req_" + suffix)
	if err != nil {
		t.Fatal(err)
	}
	dl, err := identity.ParseDownloadID("dl_" + suffix)
	if err != nil {
		t.Fatal(err)
	}
	if err = repo.CreateRequest(ctx, store.RequestRecord{ID: req, Revision: rrev(1), ResourceIdentity: "recovery", Method: "GET", SafeSpecJSON: `{"recovery":true}`, CreatedAtUnixMS: 1}); err != nil {
		t.Fatal(err)
	}
	if err = repo.CreateDownload(ctx, store.DownloadRecord{ID: dl, RequestID: req, CurrentRequestRevision: rrev(1), State: "open", Revision: rrev(1), CreatedAtUnixMS: 1, UpdatedAtUnixMS: 1}); err != nil {
		t.Fatal(err)
	}
	return db, repo, dl
}

func reserveRecovery(t *testing.T, repo *store.Repository, dl identity.DownloadID, expected identity.Revision, now int64) (store.AttemptRecord, store.DownloadRecord) {
	t.Helper()
	a, d, err := repo.ReserveAttemptGeneration(context.Background(), dl, expected, "native", now)
	if err != nil {
		t.Fatal(err)
	}
	return a, d
}

func recoveryTaskID(t *testing.T, suffix string) identity.BackendTaskID {
	t.Helper()
	id, err := identity.ParseBackendTaskID("bt_" + suffix)
	if err != nil {
		t.Fatal(err)
	}
	return id
}

func activateRecovery(t *testing.T, repo *store.Repository, dl identity.DownloadID, attempt store.AttemptRecord, suffix, staging string) store.BackendTaskRecord {
	t.Helper()
	ctx := context.Background()
	claim, err := repo.CreateOwnershipClaim(ctx, dl, attempt.Generation, "native", staging, "runtime-"+suffix, 3)
	if err != nil {
		t.Fatal(err)
	}
	bound, task, err := repo.BindBackendTask(ctx, dl, attempt.Generation, claim.Revision, recoveryTaskID(t, suffix), "external-"+suffix, "runtime-"+suffix, 4)
	if err != nil {
		t.Fatal(err)
	}
	ready, err := repo.MarkOwnershipReady(ctx, dl, attempt.Generation, bound.Revision, 5)
	if err != nil {
		t.Fatal(err)
	}
	_, activeTask, err := repo.ActivateOwnership(ctx, dl, attempt.Generation, ready.Revision, 6)
	if err != nil {
		t.Fatal(err)
	}
	_ = task
	return activeTask
}

func verifyRecovery(t *testing.T, repo *store.Repository, dl identity.DownloadID, attempt store.AttemptRecord, download store.DownloadRecord, suffix string, now int64) (store.ArtifactRecord, store.DownloadRecord) {
	t.Helper()
	ctx := context.Background()
	current, err := repo.GetAttempt(ctx, dl, attempt.Generation)
	if err != nil {
		t.Fatal(err)
	}
	if current.State != "transport_complete" {
		current, err = repo.MutateAttempt(ctx, dl, current.Generation, current.Revision, "prepared", "", "", now)
		if err != nil {
			t.Fatal(err)
		}
		current, err = repo.MutateAttempt(ctx, dl, current.Generation, current.Revision, "running", "", "", now+1)
		if err != nil {
			t.Fatal(err)
		}
		current, err = repo.MutateAttempt(ctx, dl, current.Generation, current.Revision, "transport_complete", "", "", now+2)
		if err != nil {
			t.Fatal(err)
		}
	}
	attempt = current
	vid, err := identity.ParseVerificationID("ver_" + suffix)
	if err != nil {
		t.Fatal(err)
	}
	_, artifact, updated, err := repo.CommitVerifiedArtifact(context.Background(), store.VerificationCommitRequest{VerificationID: vid, DownloadID: dl, Generation: attempt.Generation, ExpectedDownloadRevision: download.Revision, StagingIdentity: "stage-" + suffix, SizeBytes: 10, Algorithm: "sha256", ExpectedValue: "aa", ActualValue: "aa", VerifierVersion: "v1", NowUnixMS: now}, nil)
	if err != nil {
		t.Fatal(err)
	}
	return artifact, updated
}

func pubID(t *testing.T, suffix string) identity.PublicationID {
	t.Helper()
	id, err := identity.ParsePublicationID("pub_" + suffix)
	if err != nil {
		t.Fatal(err)
	}
	return id
}

func makePlatformCommitted(t *testing.T, repo *store.Repository, dl identity.DownloadID, artifact store.ArtifactRecord, download store.DownloadRecord, suffix string) store.PublicationRecord {
	t.Helper()
	ctx := context.Background()
	prepared, _, err := repo.PreparePublication(ctx, store.PreparePublicationRequest{ID: pubID(t, suffix), DownloadID: dl, ArtifactGeneration: artifact.Generation, ExpectedDownloadRevision: download.Revision, IdempotencyKey: "idem-" + suffix, NowUnixMS: 20})
	if err != nil {
		t.Fatal(err)
	}
	requested, err := repo.MarkPublicationRequested(ctx, prepared.ID, prepared.Revision, 21)
	if err != nil {
		t.Fatal(err)
	}
	committed, err := repo.RecordPlatformCommit(ctx, requested.ID, requested.Revision, "receipt-"+suffix, "content://"+suffix, 22)
	if err != nil {
		t.Fatal(err)
	}
	return committed
}

func TestRecoveryClassifiesEveryDurableCategory(t *testing.T) {
	ctx := context.Background()
	t.Run("active owned backend", func(t *testing.T) {
		_, repo, dl := recoveryRepo(t, "00000000000000000000000000000101")
		a, _ := reserveRecovery(t, repo, dl, rrev(1), 2)
		stage := filepath.Join(t.TempDir(), "active.bin")
		if err := os.WriteFile(stage, nil, 0o600); err != nil {
			t.Fatal(err)
		}
		activateRecovery(t, repo, dl, a, "00000000000000000000000000000101", stage)
		c := Coordinator{Repository: repo, Resolver: FilesystemResolver{}}
		d, err := c.RecoverOne(ctx, dl)
		if err != nil {
			t.Fatal(err)
		}
		if d.Category != ActiveOwnedBackend {
			t.Fatalf("decision=%+v", d)
		}
	})

	t.Run("backend missing", func(t *testing.T) {
		_, repo, dl := recoveryRepo(t, "00000000000000000000000000000102")
		a, _ := reserveRecovery(t, repo, dl, rrev(1), 2)
		if _, err := repo.CreateOwnershipClaim(ctx, dl, a.Generation, "native", "stage", "runtime", 3); err != nil {
			t.Fatal(err)
		}
		d, err := (&Coordinator{Repository: repo}).RecoverOne(ctx, dl)
		if err != nil {
			t.Fatal(err)
		}
		if d.Category != BackendMissing {
			t.Fatalf("decision=%+v", d)
		}
	})

	t.Run("stale ownership", func(t *testing.T) {
		_, repo, dl := recoveryRepo(t, "00000000000000000000000000000103")
		a1, d1 := reserveRecovery(t, repo, dl, rrev(1), 2)
		if _, err := repo.CreateOwnershipClaim(ctx, dl, a1.Generation, "native", "stage-old", "runtime-old", 3); err != nil {
			t.Fatal(err)
		}
		_, _ = reserveRecovery(t, repo, dl, d1.Revision, 4)
		d, err := (&Coordinator{Repository: repo}).RecoverOne(ctx, dl)
		if err != nil {
			t.Fatal(err)
		}
		if d.Category != StaleOwnership {
			t.Fatalf("decision=%+v", d)
		}
	})

	for _, tc := range []struct {
		name    string
		corrupt bool
		want    Category
	}{
		{"recoverable checkpoint", false, CurrentCheckpointRecoverable},
		{"corrupt checkpoint", true, CorruptCheckpoint},
	} {
		t.Run(tc.name, func(t *testing.T) {
			db, repo, dl := recoveryRepo(t, map[bool]string{false: "00000000000000000000000000000104", true: "00000000000000000000000000000105"}[tc.corrupt])
			a, _ := reserveRecovery(t, repo, dl, rrev(1), 2)
			stage := filepath.Join(t.TempDir(), "checkpoint.bin")
			f, err := os.OpenFile(stage, os.O_CREATE|os.O_RDWR, 0o600)
			if err != nil {
				t.Fatal(err)
			}
			activateRecovery(t, repo, dl, a, map[bool]string{false: "00000000000000000000000000000104", true: "00000000000000000000000000000105"}[tc.corrupt], stage)
			committer := checkpoint.Committer{Repository: repo}
			if _, err = committer.CommitBlock(ctx, f, checkpoint.CommitRequest{DownloadID: dl, Generation: a.Generation, BlockIndex: 0, StartByte: 0, Data: []byte("recoverable"), NowUnixMS: 7}); err != nil {
				t.Fatal(err)
			}
			if tc.corrupt {
				if _, err = f.WriteAt([]byte("X"), 0); err != nil {
					t.Fatal(err)
				}
				if err = f.Sync(); err != nil {
					t.Fatal(err)
				}
			}
			_ = f.Close()
			if _, err = db.Exec(ctx, `UPDATE backend_tasks SET task_state='retired',revision=revision+1 WHERE download_id=? AND attempt_generation=?`, dl.String(), a.Generation.Int64()); err != nil {
				t.Fatal(err)
			}
			d, err := (&Coordinator{Repository: repo, Resolver: FilesystemResolver{}}).RecoverOne(ctx, dl)
			if err != nil {
				t.Fatal(err)
			}
			if d.Category != tc.want {
				t.Fatalf("decision=%+v", d)
			}
		})
	}

	t.Run("verified artifact unpublished", func(t *testing.T) {
		_, repo, dl := recoveryRepo(t, "00000000000000000000000000000106")
		a, d1 := reserveRecovery(t, repo, dl, rrev(1), 2)
		_, _ = verifyRecovery(t, repo, dl, a, d1, "00000000000000000000000000000106", 3)
		d, err := (&Coordinator{Repository: repo}).RecoverOne(ctx, dl)
		if err != nil {
			t.Fatal(err)
		}
		if d.Category != VerifiedArtifactUnpublished {
			t.Fatalf("decision=%+v", d)
		}
	})

	t.Run("platform committed engine uncommitted", func(t *testing.T) {
		_, repo, dl := recoveryRepo(t, "00000000000000000000000000000107")
		a, d1 := reserveRecovery(t, repo, dl, rrev(1), 2)
		artifact, d2 := verifyRecovery(t, repo, dl, a, d1, "00000000000000000000000000000107", 3)
		pub := makePlatformCommitted(t, repo, dl, artifact, d2, "00000000000000000000000000000107")
		decision, err := (&Coordinator{Repository: repo}).RecoverOne(ctx, dl)
		if err != nil {
			t.Fatal(err)
		}
		if decision.Category != PlatformCommittedEngineUncommitted {
			t.Fatalf("decision=%+v", decision)
		}
		got, err := repo.GetPublication(ctx, pub.ID)
		if err != nil {
			t.Fatal(err)
		}
		if got.State != publication.Cleaned {
			t.Fatalf("publication=%+v", got)
		}
		download, _ := repo.GetDownload(ctx, dl)
		if download.State != "completed" {
			t.Fatalf("download=%+v", download)
		}
	})

	t.Run("completed", func(t *testing.T) {
		_, repo, dl := recoveryRepo(t, "00000000000000000000000000000108")
		a, d1 := reserveRecovery(t, repo, dl, rrev(1), 2)
		artifact, d2 := verifyRecovery(t, repo, dl, a, d1, "00000000000000000000000000000108", 3)
		_ = makePlatformCommitted(t, repo, dl, artifact, d2, "00000000000000000000000000000108")
		c := Coordinator{Repository: repo}
		if _, err := c.RecoverOne(ctx, dl); err != nil {
			t.Fatal(err)
		}
		decision, err := c.RecoverOne(ctx, dl)
		if err != nil {
			t.Fatal(err)
		}
		if decision.Category != Completed {
			t.Fatalf("decision=%+v", decision)
		}
	})

	t.Run("unrecoverable", func(t *testing.T) {
		_, repo, dl := recoveryRepo(t, "00000000000000000000000000000109")
		_, _ = reserveRecovery(t, repo, dl, rrev(1), 2)
		decision, err := (&Coordinator{Repository: repo}).RecoverOne(ctx, dl)
		if err != nil {
			t.Fatal(err)
		}
		if decision.Category != Unrecoverable {
			t.Fatalf("decision=%+v", decision)
		}
	})
}

func TestRecoveryInterruptedAfterEngineCommitResumesIdempotently(t *testing.T) {
	ctx := context.Background()
	_, repo, dl := recoveryRepo(t, "00000000000000000000000000000110")
	a, d1 := reserveRecovery(t, repo, dl, rrev(1), 2)
	artifact, d2 := verifyRecovery(t, repo, dl, a, d1, "00000000000000000000000000000110", 3)
	pub := makePlatformCommitted(t, repo, dl, artifact, d2, "00000000000000000000000000000110")
	boom := errors.New("simulated process death")
	c := Coordinator{Repository: repo, Hook: func(stage Stage) error {
		if stage == AfterEngineCommit {
			return boom
		}
		return nil
	}}
	if _, err := c.RecoverOne(ctx, dl); !errors.Is(err, boom) {
		t.Fatalf("want interruption, got %v", err)
	}
	mid, err := repo.GetPublication(ctx, pub.ID)
	if err != nil {
		t.Fatal(err)
	}
	if mid.State != publication.EngineCommitted {
		t.Fatalf("mid=%+v", mid)
	}
	c.Hook = nil
	if _, err = c.RecoverOne(ctx, dl); err != nil {
		t.Fatal(err)
	}
	final, err := repo.GetPublication(ctx, pub.ID)
	if err != nil {
		t.Fatal(err)
	}
	if final.State != publication.Cleaned {
		t.Fatalf("final=%+v", final)
	}
	rev := final.Revision
	if _, err = c.RecoverOne(ctx, dl); err != nil {
		t.Fatal(err)
	}
	again, _ := repo.GetPublication(ctx, pub.ID)
	if again.Revision != rev {
		t.Fatalf("double recovery mutated terminal saga: before=%s after=%s", rev, again.Revision)
	}
}

func TestStaleBackendCompletionIsDiagnosticOnly(t *testing.T) {
	ctx := context.Background()
	_, repo, dl := recoveryRepo(t, "00000000000000000000000000000111")
	a1, d1 := reserveRecovery(t, repo, dl, rrev(1), 2)
	_, d2 := reserveRecovery(t, repo, dl, d1.Revision, 3)
	c := Coordinator{Repository: repo}
	accepted, err := c.ObserveBackendCompletion(ctx, dl, a1.Generation)
	if err != nil {
		t.Fatal(err)
	}
	if accepted {
		t.Fatal("stale completion accepted")
	}
	after, err := repo.GetDownload(ctx, dl)
	if err != nil {
		t.Fatal(err)
	}
	if after.Revision != d2.Revision || after.CurrentAttempt == nil || after.CurrentAttempt.Int64() != 2 {
		t.Fatalf("stale completion mutated authority: %+v", after)
	}
	accepted, err = c.ObserveBackendCompletion(ctx, dl, a1.Generation)
	if err != nil {
		t.Fatal(err)
	}
	if accepted {
		t.Fatal("repeated stale completion accepted")
	}
	count, err := repo.DiagnosticEventCount(ctx, "recovery.stale_backend_completion")
	if err != nil {
		t.Fatal(err)
	}
	if count != 1 {
		t.Fatalf("diagnostic count=%d", count)
	}
}
