package main

import (
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"sync"
	"sync/atomic"
	"time"

	"github.com/subhra74/xdm/engine/domain/backend"
	"github.com/subhra74/xdm/engine/domain/identity"
	"github.com/subhra74/xdm/engine/domain/publication"
	"github.com/subhra74/xdm/engine/recovery"
	"github.com/subhra74/xdm/engine/store/checkpoint"
	store "github.com/subhra74/xdm/engine/store/sqlite"
)

type report struct {
	SchemaVersion int            `json:"schema_version"`
	Mode          string         `json:"mode"`
	Status        string         `json:"status"`
	Details       map[string]any `json:"details"`
}

func main() {
	mode := flag.String("mode", "platform", "platform|schema|cas|authority|ownership|checkpoint|verification|publication|recovery|durable-gate")
	output := flag.String("output", "", "optional JSON report path")
	iterations := flag.Int("iterations", 64, "stress rounds")
	flag.Parse()

	var details map[string]any
	var err error
	switch *mode {
	case "platform":
		details, err = auditPlatform()
	case "schema":
		details, err = auditSchema()
	case "cas":
		details, err = auditCAS(*iterations)
	case "authority":
		details, err = auditAuthority(*iterations)
	case "ownership":
		details, err = auditOwnership()
	case "checkpoint":
		details, err = auditCheckpoint()
	case "verification":
		details, err = auditVerification()
	case "publication":
		details, err = auditPublication()
	case "recovery":
		details, err = auditRecovery()
	case "durable-gate":
		details, err = auditDurableGate()
	default:
		err = fmt.Errorf("unknown mode %q", *mode)
	}
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	r := report{SchemaVersion: 1, Mode: *mode, Status: "pass", Details: details}
	data, _ := json.MarshalIndent(r, "", "  ")
	data = append(data, '\n')
	if *output != "" {
		if err := os.MkdirAll(filepath.Dir(*output), 0o755); err != nil {
			panic(err)
		}
		if err := os.WriteFile(*output, data, 0o644); err != nil {
			panic(err)
		}
	}
	_, _ = os.Stdout.Write(data)
}

func tempDB() (*store.DB, string, func(), error) {
	dir, err := os.MkdirTemp("", "xgo-store-audit-")
	if err != nil {
		return nil, "", nil, err
	}
	path := filepath.Join(dir, "engine.sqlite")
	db, err := store.Open(path, store.DefaultOptions())
	if err != nil {
		_ = os.RemoveAll(dir)
		return nil, "", nil, err
	}
	cleanup := func() { _ = db.Close(); _ = os.RemoveAll(dir) }
	return db, path, cleanup, nil
}

func ids(suffix string) (identity.RequestID, identity.DownloadID, error) {
	req, err := identity.ParseRequestID("req_" + suffix)
	if err != nil {
		return "", "", err
	}
	dl, err := identity.ParseDownloadID("dl_" + suffix)
	return req, dl, err
}
func rev(v int64) identity.Revision { r, _ := identity.NewRevision(v); return r }
func taskID(suffix string) identity.BackendTaskID {
	id, _ := identity.ParseBackendTaskID("bt_" + suffix)
	return id
}

func seed(db *store.DB, suffix string) (*store.Repository, identity.DownloadID, error) {
	ctx := context.Background()
	if err := store.Migrate(ctx, db, 1); err != nil {
		return nil, "", err
	}
	repo, _ := store.NewRepository(db)
	req, dl, err := ids(suffix)
	if err != nil {
		return nil, "", err
	}
	if err = repo.CreateRequest(ctx, store.RequestRecord{ID: req, Revision: rev(1), ResourceIdentity: "audit", Method: "GET", SafeSpecJSON: `{"audit":true}`, CreatedAtUnixMS: 1}); err != nil {
		return nil, "", err
	}
	if err = repo.CreateDownload(ctx, store.DownloadRecord{ID: dl, RequestID: req, CurrentRequestRevision: rev(1), State: "open", Revision: rev(1), CreatedAtUnixMS: 1, UpdatedAtUnixMS: 1}); err != nil {
		return nil, "", err
	}
	return repo, dl, nil
}

func activate(repo *store.Repository, dl identity.DownloadID, downloadRevision identity.Revision, suffix string) (identity.AttemptGeneration, error) {
	ctx := context.Background()
	attempt, _, err := repo.ReserveAttemptGeneration(ctx, dl, downloadRevision, "native", 2)
	if err != nil {
		return 0, err
	}
	claim, err := repo.CreateOwnershipClaim(ctx, dl, attempt.Generation, "native", "stage-"+suffix, "runtime-"+suffix, 3)
	if err != nil {
		return 0, err
	}
	bound, _, err := repo.BindBackendTask(ctx, dl, attempt.Generation, claim.Revision, taskID(suffix), "external-"+suffix, "runtime-"+suffix, 4)
	if err != nil {
		return 0, err
	}
	ready, err := repo.MarkOwnershipReady(ctx, dl, attempt.Generation, bound.Revision, 5)
	if err != nil {
		return 0, err
	}
	if _, _, err = repo.ActivateOwnership(ctx, dl, attempt.Generation, ready.Revision, 6); err != nil {
		return 0, err
	}
	return attempt.Generation, nil
}

func auditPlatform() (map[string]any, error) {
	if !store.Available {
		return nil, store.ErrUnavailable
	}
	db, path, cleanup, err := tempDB()
	if err != nil {
		return nil, err
	}
	defer cleanup()
	ctx := context.Background()
	if err = store.Migrate(ctx, db, time.Now().UnixMilli()); err != nil {
		return nil, err
	}
	if err = db.IntegrityCheck(ctx); err != nil {
		return nil, err
	}
	pragmas := map[string]any{}
	for _, q := range []string{"PRAGMA journal_mode", "PRAGMA foreign_keys", "PRAGMA busy_timeout", "PRAGMA synchronous"} {
		rows, e := db.Query(ctx, q)
		if e != nil {
			return nil, e
		}
		if len(rows) != 1 || len(rows[0]) != 1 {
			return nil, fmt.Errorf("unexpected %s result", q)
		}
		v := rows[0][0]
		if v.Kind == store.Text {
			pragmas[q] = v.Text
		} else {
			pragmas[q] = v.I64
		}
	}
	info, err := os.Stat(path)
	if err != nil {
		return nil, err
	}
	return map[string]any{"goos": runtime.GOOS, "goarch": runtime.GOARCH, "sqlite_version": store.LibraryVersion(), "database_bytes": info.Size(), "pragmas": pragmas}, nil
}

func auditSchema() (map[string]any, error) {
	db, _, cleanup, err := tempDB()
	if err != nil {
		return nil, err
	}
	defer cleanup()
	ctx := context.Background()
	if err = store.Migrate(ctx, db, 1234); err != nil {
		return nil, err
	}
	rows, err := db.Query(ctx, `SELECT name,type FROM sqlite_master WHERE type IN ('table','index') AND name NOT LIKE 'sqlite_%' ORDER BY name`)
	if err != nil {
		return nil, err
	}
	tables, indexes := 0, 0
	for _, r := range rows {
		if r[1].Text == "table" {
			tables++
		} else if r[1].Text == "index" {
			indexes++
		}
	}
	return map[string]any{"schema_version": store.CurrentSchemaVersion, "schema_checksum": store.SchemaChecksum(), "tables": tables, "indexes": indexes, "objects": len(rows)}, nil
}

func auditCAS(rounds int) (map[string]any, error) {
	if rounds < 1 {
		return nil, fmt.Errorf("iterations must be >= 1")
	}
	ctx := context.Background()
	var totalWins, totalStale int64
	for round := 0; round < rounds; round++ {
		db, path, cleanup, err := tempDB()
		if err != nil {
			return nil, err
		}
		repo, dl, err := seed(db, "00000000000000000000000000000000")
		if err != nil {
			cleanup()
			return nil, err
		}
		const writers = 12
		var wins, stale, unexpected atomic.Int64
		var wg sync.WaitGroup
		for i := 0; i < writers; i++ {
			wg.Add(1)
			go func(i int) {
				defer wg.Done()
				conn, e := store.Open(path, store.DefaultOptions())
				if e != nil {
					unexpected.Add(1)
					return
				}
				defer conn.Close()
				r, _ := store.NewRepository(conn)
				_, e = r.UpdateDownload(ctx, dl, rev(1), store.DownloadPatch{State: "winner", UpdatedAtUnixMS: int64(i + 2)})
				if e == nil {
					wins.Add(1)
				} else if errors.Is(e, store.ErrStaleWrite) {
					stale.Add(1)
				} else {
					unexpected.Add(1)
				}
			}(i)
		}
		wg.Wait()
		cleanup()
		_ = repo
		if wins.Load() != 1 || stale.Load() != writers-1 || unexpected.Load() != 0 {
			return nil, fmt.Errorf("round %d: wins=%d stale=%d unexpected=%d", round, wins.Load(), stale.Load(), unexpected.Load())
		}
		totalWins += wins.Load()
		totalStale += stale.Load()
	}
	return map[string]any{"rounds": rounds, "writers_per_round": 12, "winning_writes": totalWins, "stale_writes": totalStale}, nil
}

func auditAuthority(rounds int) (map[string]any, error) {
	if rounds < 1 {
		return nil, fmt.Errorf("iterations must be >= 1")
	}
	ctx := context.Background()
	staleRejected := 0
	for i := 0; i < rounds; i++ {
		db, _, cleanup, err := tempDB()
		if err != nil {
			return nil, err
		}
		repo, dl, err := seed(db, "00000000000000000000000000000001")
		if err != nil {
			cleanup()
			return nil, err
		}
		first, d, err := repo.ReserveAttemptGeneration(ctx, dl, rev(1), "native", 2)
		if err != nil {
			cleanup()
			return nil, err
		}
		running, err := repo.MutateAttempt(ctx, dl, first.Generation, first.Revision, "running", "", "", 3)
		if err != nil {
			cleanup()
			return nil, err
		}
		_, _, err = repo.ReserveAttemptGeneration(ctx, dl, d.Revision, "native", 4)
		if err != nil {
			cleanup()
			return nil, err
		}
		_, err = repo.MutateAttempt(ctx, dl, first.Generation, running.Revision, "completed", "", "", 5)
		if !errors.Is(err, store.ErrStaleAttempt) {
			cleanup()
			return nil, fmt.Errorf("round %d stale mutation: %v", i, err)
		}
		staleRejected++
		cleanup()
	}
	return map[string]any{"rounds": rounds, "stale_attempt_mutations_rejected": staleRejected}, nil
}

func auditOwnership() (map[string]any, error) {
	ctx := context.Background()
	db, _, cleanup, err := tempDB()
	if err != nil {
		return nil, err
	}
	defer cleanup()
	repo, dl, err := seed(db, "00000000000000000000000000000002")
	if err != nil {
		return nil, err
	}
	attempt, d, err := repo.ReserveAttemptGeneration(ctx, dl, rev(1), "native", 2)
	if err != nil {
		return nil, err
	}
	states := []string{"reserved"}
	if err = repo.AssertAuthoritativeWriter(ctx, dl, attempt.Generation); !errors.Is(err, store.ErrNotFound) && !errors.Is(err, store.ErrOwnershipNotActive) {
		return nil, fmt.Errorf("reservation unexpectedly writable: %v", err)
	}
	claim, err := repo.CreateOwnershipClaim(ctx, dl, attempt.Generation, "native", "stage", "runtime", 3)
	if err != nil {
		return nil, err
	}
	states = append(states, string(claim.State))
	if err = repo.AssertAuthoritativeWriter(ctx, dl, attempt.Generation); !errors.Is(err, store.ErrOwnershipNotActive) {
		return nil, fmt.Errorf("claim unexpectedly writable: %v", err)
	}
	bound, task, err := repo.BindBackendTask(ctx, dl, attempt.Generation, claim.Revision, taskID("00000000000000000000000000000002"), "external", "runtime", 4)
	if err != nil {
		return nil, err
	}
	states = append(states, string(bound.State)+":"+string(task.State))
	if err = repo.AssertAuthoritativeWriter(ctx, dl, attempt.Generation); !errors.Is(err, store.ErrOwnershipNotActive) {
		return nil, fmt.Errorf("bound unexpectedly writable: %v", err)
	}
	ready, err := repo.MarkOwnershipReady(ctx, dl, attempt.Generation, bound.Revision, 5)
	if err != nil {
		return nil, err
	}
	states = append(states, string(ready.State))
	if err = repo.AssertAuthoritativeWriter(ctx, dl, attempt.Generation); !errors.Is(err, store.ErrOwnershipNotActive) {
		return nil, fmt.Errorf("ready unexpectedly writable: %v", err)
	}
	active, activeTask, err := repo.ActivateOwnership(ctx, dl, attempt.Generation, ready.Revision, 6)
	if err != nil {
		return nil, err
	}
	if active.State != backend.OwnershipActive || activeTask.State != backend.TaskActive {
		return nil, fmt.Errorf("activation state mismatch")
	}
	if err = repo.AssertAuthoritativeWriter(ctx, dl, attempt.Generation); err != nil {
		return nil, err
	}
	states = append(states, string(active.State)+":"+string(activeTask.State))
	if _, _, err = repo.ReserveAttemptGeneration(ctx, dl, d.Revision, "native", 7); err != nil {
		return nil, err
	}
	if err = repo.AssertAuthoritativeWriter(ctx, dl, attempt.Generation); !errors.Is(err, store.ErrStaleAttempt) {
		return nil, fmt.Errorf("superseded owner retained authority: %v", err)
	}
	return map[string]any{"sequence": states, "writable_only_after_activation": true, "superseded_owner_fenced": true}, nil
}

func auditCheckpoint() (map[string]any, error) {
	ctx := context.Background()
	stages := []checkpoint.Stage{checkpoint.AfterWrite, checkpoint.AfterSync, checkpoint.AfterHash}
	discarded := 0
	for i, stage := range stages {
		db, _, cleanup, err := tempDB()
		if err != nil {
			return nil, err
		}
		repo, dl, err := seed(db, "00000000000000000000000000000003")
		if err != nil {
			cleanup()
			return nil, err
		}
		gen, err := activate(repo, dl, rev(1), fmt.Sprintf("%032x", i+3))
		if err != nil {
			cleanup()
			return nil, err
		}
		stageDir, err := os.MkdirTemp("", "xgo-checkpoint-prepersist-")
		if err != nil {
			cleanup()
			return nil, err
		}
		f, err := os.OpenFile(filepath.Join(stageDir, fmt.Sprintf("staging-%d.bin", i)), os.O_CREATE|os.O_RDWR, 0o600)
		if err != nil {
			_ = os.RemoveAll(stageDir)
			cleanup()
			return nil, err
		}
		boom := errors.New("fault")
		c := checkpoint.Committer{Repository: repo, Hook: func(s checkpoint.Stage) error {
			if s == stage {
				return boom
			}
			return nil
		}}
		_, err = c.CommitBlock(ctx, f, checkpoint.CommitRequest{DownloadID: dl, Generation: gen, BlockIndex: 0, StartByte: 0, Data: []byte("durable-block"), NowUnixMS: 7})
		_ = f.Close()
		_ = os.RemoveAll(stageDir)
		if !errors.Is(err, boom) {
			cleanup()
			return nil, fmt.Errorf("stage %s: %v", stage, err)
		}
		blocks, e := repo.ListCheckpointBlocks(ctx, dl, gen)
		if e != nil {
			cleanup()
			return nil, e
		}
		if len(blocks) != 0 {
			cleanup()
			return nil, fmt.Errorf("stage %s persisted resumable evidence", stage)
		}
		discarded++
		cleanup()
	}
	// Separate post-persist case proves committed evidence survives a crash signal.
	db, _, cleanup, err := tempDB()
	if err != nil {
		return nil, err
	}
	defer cleanup()
	repo, dl, err := seed(db, "00000000000000000000000000000004")
	if err != nil {
		return nil, err
	}
	gen, err := activate(repo, dl, rev(1), "00000000000000000000000000000004")
	if err != nil {
		return nil, err
	}
	dir, err := os.MkdirTemp("", "xgo-checkpoint-audit-")
	if err != nil {
		return nil, err
	}
	defer os.RemoveAll(dir)
	f, err := os.OpenFile(filepath.Join(dir, "staging.bin"), os.O_CREATE|os.O_RDWR, 0o600)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	boom := errors.New("after persist")
	c := checkpoint.Committer{Repository: repo, Hook: func(s checkpoint.Stage) error {
		if s == checkpoint.AfterPersist {
			return boom
		}
		return nil
	}}
	rec, err := c.CommitBlock(ctx, f, checkpoint.CommitRequest{DownloadID: dl, Generation: gen, BlockIndex: 0, StartByte: 0, Data: []byte("durable-block"), NowUnixMS: 8})
	if !errors.Is(err, boom) || rec.State != "committed" {
		return nil, fmt.Errorf("post-persist evidence not durable: rec=%+v err=%v", rec, err)
	}
	ins, err := checkpoint.Inspect(ctx, repo, f, dl, gen)
	if err != nil {
		return nil, err
	}
	if len(ins) != 1 || !ins[0].Valid {
		return nil, fmt.Errorf("post-persist inspection=%+v", ins)
	}
	return map[string]any{"pre_persist_fault_points": len(stages), "pre_persist_rows_discarded": discarded, "post_persist_recoverable": true, "hash_algorithm": "sha256"}, nil
}

func verificationID(suffix string) identity.VerificationID {
	id, _ := identity.ParseVerificationID("ver_" + suffix)
	return id
}

func publicationID(suffix string) identity.PublicationID {
	id, _ := identity.ParsePublicationID("pub_" + suffix)
	return id
}

func auditVerification() (map[string]any, error) {
	ctx := context.Background()
	db, _, cleanup, err := tempDB()
	if err != nil {
		return nil, err
	}
	defer cleanup()
	repo, dl, err := seed(db, "00000000000000000000000000000021")
	if err != nil {
		return nil, err
	}
	attempt1, d1, err := repo.ReserveAttemptGeneration(ctx, dl, rev(1), "native", 2)
	if err != nil {
		return nil, err
	}
	if d1.CurrentArtifact != nil {
		return nil, fmt.Errorf("transport reservation unexpectedly created artifact")
	}
	failed, err := repo.RecordVerificationFailure(ctx, store.VerificationRecord{ID: verificationID("00000000000000000000000000000021"), DownloadID: dl, Generation: attempt1.Generation, Algorithm: "sha256", ExpectedValue: "aa", ActualValue: "bb", VerifierVersion: "audit-v1", CreatedAtUnixMS: 3})
	if err != nil {
		return nil, err
	}
	if failed.Result != store.VerificationFailed || failed.ArtifactGeneration != nil {
		return nil, fmt.Errorf("failed verification created artifact identity")
	}
	boom := errors.New("verification crash")
	_, _, _, err = repo.CommitVerifiedArtifact(ctx, store.VerificationCommitRequest{VerificationID: verificationID("00000000000000000000000000000022"), DownloadID: dl, Generation: attempt1.Generation, ExpectedDownloadRevision: d1.Revision, StagingIdentity: "audit-stage-21", SizeBytes: 8, Algorithm: "sha256", ExpectedValue: "aa", ActualValue: "aa", VerifierVersion: "audit-v1", NowUnixMS: 4}, func(stage store.VerificationStage) error {
		if stage == store.AfterVerificationRecord {
			return boom
		}
		return nil
	})
	if !errors.Is(err, boom) {
		return nil, fmt.Errorf("verification fault not surfaced: %v", err)
	}
	records, err := repo.ListVerificationRecords(ctx, dl)
	if err != nil {
		return nil, err
	}
	if len(records) != 1 {
		return nil, fmt.Errorf("partial verification transaction escaped rollback: %d rows", len(records))
	}
	_, artifact1, d2, err := repo.CommitVerifiedArtifact(ctx, store.VerificationCommitRequest{VerificationID: verificationID("00000000000000000000000000000023"), DownloadID: dl, Generation: attempt1.Generation, ExpectedDownloadRevision: d1.Revision, StagingIdentity: "audit-stage-21", SizeBytes: 8, Algorithm: "sha256", ExpectedValue: "aa", ActualValue: "aa", VerifierVersion: "audit-v1", NowUnixMS: 5}, nil)
	if err != nil {
		return nil, err
	}
	if artifact1.Generation.Int64() != 1 {
		return nil, fmt.Errorf("first accepted artifact generation=%d", artifact1.Generation.Int64())
	}
	attempt2, d3, err := repo.ReserveAttemptGeneration(ctx, dl, d2.Revision, "native", 6)
	if err != nil {
		return nil, err
	}
	_, err = repo.RecordVerificationFailure(ctx, store.VerificationRecord{ID: verificationID("00000000000000000000000000000024"), DownloadID: dl, Generation: attempt1.Generation, Algorithm: "sha256", VerifierVersion: "audit-v1", CreatedAtUnixMS: 7})
	if !errors.Is(err, store.ErrStaleAttempt) {
		return nil, fmt.Errorf("stale verification accepted: %v", err)
	}
	_, artifact2, _, err := repo.CommitVerifiedArtifact(ctx, store.VerificationCommitRequest{VerificationID: verificationID("00000000000000000000000000000025"), DownloadID: dl, Generation: attempt2.Generation, ExpectedDownloadRevision: d3.Revision, StagingIdentity: "audit-stage-22", SizeBytes: 9, Algorithm: "sha256", ExpectedValue: "cc", ActualValue: "cc", VerifierVersion: "audit-v1", NowUnixMS: 8}, nil)
	if err != nil {
		return nil, err
	}
	if artifact2.Generation.Int64() != 2 {
		return nil, fmt.Errorf("retry artifact generation=%d", artifact2.Generation.Int64())
	}
	if err = db.IntegrityCheck(ctx); err != nil {
		return nil, err
	}
	return map[string]any{"failure_journaled_without_artifact": true, "interrupted_success_atomic": true, "stale_attempt_rejected": true, "accepted_artifact_generations": []int64{artifact1.Generation.Int64(), artifact2.Generation.Int64()}, "integrity_check": "ok"}, nil
}

func reopenStore(path string) (*store.DB, *store.Repository, error) {
	db, err := store.Open(path, store.DefaultOptions())
	if err != nil {
		return nil, nil, err
	}
	repo, err := store.NewRepository(db)
	if err != nil {
		_ = db.Close()
		return nil, nil, err
	}
	return db, repo, nil
}

func seedVerifiedArtifact(db *store.DB, suffix string) (*store.Repository, identity.DownloadID, store.ArtifactRecord, store.DownloadRecord, error) {
	ctx := context.Background()
	repo, dl, err := seed(db, suffix)
	if err != nil {
		return nil, "", store.ArtifactRecord{}, store.DownloadRecord{}, err
	}
	attempt, d1, err := repo.ReserveAttemptGeneration(ctx, dl, rev(1), "native", 2)
	if err != nil {
		return nil, "", store.ArtifactRecord{}, store.DownloadRecord{}, err
	}
	_, artifact, d2, err := repo.CommitVerifiedArtifact(ctx, store.VerificationCommitRequest{VerificationID: verificationID(suffix), DownloadID: dl, Generation: attempt.Generation, ExpectedDownloadRevision: d1.Revision, StagingIdentity: "stage-" + suffix, SizeBytes: 32, Algorithm: "sha256", ExpectedValue: "aa", ActualValue: "aa", VerifierVersion: "audit-v1", NowUnixMS: 3}, nil)
	return repo, dl, artifact, d2, err
}

func auditPublication() (map[string]any, error) {
	ctx := context.Background()
	dir, err := os.MkdirTemp("", "xgo-publication-audit-")
	if err != nil {
		return nil, err
	}
	defer os.RemoveAll(dir)
	path := filepath.Join(dir, "engine.sqlite")
	db, err := store.Open(path, store.DefaultOptions())
	if err != nil {
		return nil, err
	}
	repo, dl, artifact, download, err := seedVerifiedArtifact(db, "00000000000000000000000000000031")
	if err != nil {
		_ = db.Close()
		return nil, err
	}
	pubID := publicationID("00000000000000000000000000000031")
	prepared, _, err := repo.PreparePublication(ctx, store.PreparePublicationRequest{ID: pubID, DownloadID: dl, ArtifactGeneration: artifact.Generation, ExpectedDownloadRevision: download.Revision, IdempotencyKey: "publication-audit-31", NowUnixMS: 4})
	if err != nil {
		_ = db.Close()
		return nil, err
	}
	repo2, dl2, artifact2, download2, err := seedVerifiedArtifact(db, "00000000000000000000000000000032")
	if err != nil {
		_ = db.Close()
		return nil, err
	}
	_, _, err = repo2.PreparePublication(ctx, store.PreparePublicationRequest{ID: publicationID("00000000000000000000000000000032"), DownloadID: dl2, ArtifactGeneration: artifact2.Generation, ExpectedDownloadRevision: download2.Revision, IdempotencyKey: "publication-audit-31", NowUnixMS: 4})
	var collision *store.Error
	if !errors.As(err, &collision) || !collision.Constraint() {
		_ = db.Close()
		return nil, fmt.Errorf("cross-download publication key collision accepted: %v", err)
	}
	commitReq, err := repo.PublicationCommitRequest(ctx, pubID)
	if err != nil || commitReq.IdempotencyKey != "publication-audit-31" {
		_ = db.Close()
		return nil, fmt.Errorf("commit request contract: %+v %v", commitReq, err)
	}
	states := []string{string(prepared.State)}
	if action, _, err := repo.PublicationAction(ctx, pubID); err != nil || action != publication.ActionRequestCommit {
		_ = db.Close()
		return nil, fmt.Errorf("prepared action=%s err=%v", action, err)
	}
	_ = db.Close()

	db, repo, err = reopenStore(path)
	if err != nil {
		return nil, err
	}
	requested, err := repo.MarkPublicationRequested(ctx, pubID, prepared.Revision, 5)
	if err != nil {
		_ = db.Close()
		return nil, err
	}
	states = append(states, string(requested.State))
	inspectReq, err := repo.PublicationInspectRequest(ctx, pubID)
	if err != nil || inspectReq.IdempotencyKey != "publication-audit-31" {
		_ = db.Close()
		return nil, fmt.Errorf("inspect request: %+v %v", inspectReq, err)
	}
	if action, _, err := repo.PublicationAction(ctx, pubID); err != nil || action != publication.ActionInspectReceipt {
		_ = db.Close()
		return nil, fmt.Errorf("requested action=%s err=%v", action, err)
	}
	_ = db.Close()

	db, repo, err = reopenStore(path)
	if err != nil {
		return nil, err
	}
	committed, err := repo.RecordPlatformCommit(ctx, pubID, requested.Revision, "receipt-31", "content://published/31", 6)
	if err != nil {
		_ = db.Close()
		return nil, err
	}
	states = append(states, string(committed.State))
	duplicate, err := repo.RecordPlatformCommit(ctx, pubID, requested.Revision, "receipt-31", "content://published/31", 7)
	if err != nil || duplicate.Revision != committed.Revision {
		_ = db.Close()
		return nil, fmt.Errorf("duplicate reply: %+v %v", duplicate, err)
	}
	if _, err = repo.RecordPlatformCommit(ctx, pubID, committed.Revision, "receipt-other", "content://other", 8); !errors.Is(err, store.ErrPublicationConflict) {
		_ = db.Close()
		return nil, fmt.Errorf("conflicting receipt accepted: %v", err)
	}
	_ = db.Close()

	db, repo, err = reopenStore(path)
	if err != nil {
		return nil, err
	}
	engineCommitted, _, completed, err := repo.EngineCommitPublication(ctx, pubID, committed.Revision, download.Revision, 9)
	if err != nil {
		_ = db.Close()
		return nil, err
	}
	if completed.State != "completed" {
		_ = db.Close()
		return nil, fmt.Errorf("download not completed after engine commit")
	}
	states = append(states, string(engineCommitted.State))
	_ = db.Close()

	db, repo, err = reopenStore(path)
	if err != nil {
		return nil, err
	}
	cleaned, err := repo.CleanPublication(ctx, pubID, engineCommitted.Revision, 10)
	if err != nil {
		_ = db.Close()
		return nil, err
	}
	states = append(states, string(cleaned.State))
	cleanedAgain, err := repo.CleanPublication(ctx, pubID, engineCommitted.Revision, 11)
	if err != nil || cleanedAgain.Revision != cleaned.Revision {
		_ = db.Close()
		return nil, fmt.Errorf("cleanup not idempotent: %+v %v", cleanedAgain, err)
	}
	if err = db.IntegrityCheck(ctx); err != nil {
		_ = db.Close()
		return nil, err
	}
	_ = db.Close()
	return map[string]any{"crash_reopen_states": states, "platform_request_has_idempotency_key": true, "cross_download_key_collision_rejected": true, "ambiguous_requested_action": string(publication.ActionInspectReceipt), "duplicate_platform_reply_idempotent": true, "conflicting_receipt_rejected": true, "cleanup_idempotent": true, "integrity_check": "ok"}, nil
}

func auditRecovery() (map[string]any, error) {
	ctx := context.Background()
	dir, err := os.MkdirTemp("", "xgo-recovery-audit-")
	if err != nil {
		return nil, err
	}
	defer os.RemoveAll(dir)
	path := filepath.Join(dir, "engine.sqlite")
	db, err := store.Open(path, store.DefaultOptions())
	if err != nil {
		return nil, err
	}
	repo, dl, artifact, download, err := seedVerifiedArtifact(db, "00000000000000000000000000000041")
	if err != nil {
		_ = db.Close()
		return nil, err
	}
	pubID := publicationID("00000000000000000000000000000041")
	prepared, _, err := repo.PreparePublication(ctx, store.PreparePublicationRequest{ID: pubID, DownloadID: dl, ArtifactGeneration: artifact.Generation, ExpectedDownloadRevision: download.Revision, IdempotencyKey: "recovery-audit-41", NowUnixMS: 4})
	if err != nil {
		_ = db.Close()
		return nil, err
	}
	requested, err := repo.MarkPublicationRequested(ctx, pubID, prepared.Revision, 5)
	if err != nil {
		_ = db.Close()
		return nil, err
	}
	committed, err := repo.RecordPlatformCommit(ctx, pubID, requested.Revision, "receipt-41", "content://published/41", 6)
	if err != nil {
		_ = db.Close()
		return nil, err
	}
	boom := errors.New("process death after engine commit")
	coord := recovery.Coordinator{Repository: repo, Hook: func(stage recovery.Stage) error {
		if stage == recovery.AfterEngineCommit {
			return boom
		}
		return nil
	}}
	decision, err := coord.RecoverOne(ctx, dl)
	if !errors.Is(err, boom) || decision.Category != recovery.PlatformCommittedEngineUncommitted {
		_ = db.Close()
		return nil, fmt.Errorf("interrupted recovery decision=%+v err=%v", decision, err)
	}
	mid, err := repo.GetPublication(ctx, pubID)
	if err != nil {
		_ = db.Close()
		return nil, err
	}
	if mid.State != publication.EngineCommitted {
		_ = db.Close()
		return nil, fmt.Errorf("mid recovery state=%s", mid.State)
	}
	_ = committed
	_ = db.Close()

	db, repo, err = reopenStore(path)
	if err != nil {
		return nil, err
	}
	coord = recovery.Coordinator{Repository: repo}
	decision2, err := coord.RecoverOne(ctx, dl)
	if err != nil {
		_ = db.Close()
		return nil, err
	}
	final, err := repo.GetPublication(ctx, pubID)
	if err != nil {
		_ = db.Close()
		return nil, err
	}
	if final.State != publication.Cleaned {
		_ = db.Close()
		return nil, fmt.Errorf("restarted recovery state=%s", final.State)
	}
	finalRevision := final.Revision
	decision3, err := coord.RecoverOne(ctx, dl)
	if err != nil {
		_ = db.Close()
		return nil, err
	}
	after, _ := repo.GetPublication(ctx, pubID)
	if after.Revision != finalRevision || decision3.Category != recovery.Completed {
		_ = db.Close()
		return nil, fmt.Errorf("double recovery mutated terminal state: %+v %+v", after, decision3)
	}
	if err = db.IntegrityCheck(ctx); err != nil {
		_ = db.Close()
		return nil, err
	}
	_ = db.Close()

	// Separate database proves late stale backend completion is diagnostic-only.
	db2, _, cleanup2, err := tempDB()
	if err != nil {
		return nil, err
	}
	defer cleanup2()
	repo2, dl2, err := seed(db2, "00000000000000000000000000000042")
	if err != nil {
		return nil, err
	}
	attempt1, d1, err := repo2.ReserveAttemptGeneration(ctx, dl2, rev(1), "native", 2)
	if err != nil {
		return nil, err
	}
	_, d2, err := repo2.ReserveAttemptGeneration(ctx, dl2, d1.Revision, "native", 3)
	if err != nil {
		return nil, err
	}
	coord2 := recovery.Coordinator{Repository: repo2}
	accepted, err := coord2.ObserveBackendCompletion(ctx, dl2, attempt1.Generation)
	if err != nil {
		return nil, err
	}
	if accepted {
		return nil, fmt.Errorf("stale backend completion accepted")
	}
	post, err := repo2.GetDownload(ctx, dl2)
	if err != nil {
		return nil, err
	}
	if post.Revision != d2.Revision || post.CurrentAttempt == nil || post.CurrentAttempt.Int64() != 2 {
		return nil, fmt.Errorf("stale backend completion changed authority")
	}
	if err = db2.IntegrityCheck(ctx); err != nil {
		return nil, err
	}
	return map[string]any{"interrupted_category": decision.Category, "restart_category": decision2.Category, "terminal_category": decision3.Category, "recovery_restarted_idempotently": true, "stale_backend_completion_diagnostic_only": true, "integrity_check": "ok"}, nil
}

func auditDurableGate() (map[string]any, error) {
	verification, err := auditVerification()
	if err != nil {
		return nil, fmt.Errorf("verification matrix: %w", err)
	}
	publicationMatrix, err := auditPublication()
	if err != nil {
		return nil, fmt.Errorf("publication matrix: %w", err)
	}
	recoveryMatrix, err := auditRecovery()
	if err != nil {
		return nil, fmt.Errorf("recovery matrix: %w", err)
	}
	checkpointMatrix, err := auditCheckpoint()
	if err != nil {
		return nil, fmt.Errorf("checkpoint matrix: %w", err)
	}
	ownershipMatrix, err := auditOwnership()
	if err != nil {
		return nil, fmt.Errorf("ownership matrix: %w", err)
	}
	return map[string]any{"verification": verification, "publication": publicationMatrix, "recovery": recoveryMatrix, "checkpoint": checkpointMatrix, "ownership": ownershipMatrix, "sqlite_integrity_after_scenarios": true, "stale_writer_invariant": true}, nil
}
