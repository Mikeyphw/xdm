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
	mode := flag.String("mode", "platform", "platform|schema|cas|authority|ownership|checkpoint")
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
