//go:build cgo

package checkpoint

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"testing"

	"github.com/subhra74/xdm/engine/domain/identity"
	store "github.com/subhra74/xdm/engine/store/sqlite"
)

func mustID[T ~string](t *testing.T, parse func(string) (T, error), value string) T {
	t.Helper()
	v, err := parse(value)
	if err != nil {
		t.Fatal(err)
	}
	return v
}

func setupActiveOwner(t *testing.T) (*store.DB, *store.Repository, identity.DownloadID, identity.AttemptGeneration, *os.File) {
	t.Helper()
	ctx := context.Background()
	dir := t.TempDir()
	db, err := store.Open(filepath.Join(dir, "engine.sqlite"), store.DefaultOptions())
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = db.Close() })
	if err = store.Migrate(ctx, db, 1); err != nil {
		t.Fatal(err)
	}
	repo, _ := store.NewRepository(db)
	req := mustID(t, identity.ParseRequestID, "req_00000000000000000000000000000011")
	dl := mustID(t, identity.ParseDownloadID, "dl_00000000000000000000000000000011")
	rev, _ := identity.NewRevision(1)
	if err = repo.CreateRequest(ctx, store.RequestRecord{ID: req, Revision: rev, ResourceIdentity: "checkpoint", Method: "GET", SafeSpecJSON: `{"checkpoint":true}`, CreatedAtUnixMS: 1}); err != nil {
		t.Fatal(err)
	}
	if err = repo.CreateDownload(ctx, store.DownloadRecord{ID: dl, RequestID: req, CurrentRequestRevision: rev, State: "open", Revision: rev, CreatedAtUnixMS: 1, UpdatedAtUnixMS: 1}); err != nil {
		t.Fatal(err)
	}
	attempt, _, err := repo.ReserveAttemptGeneration(ctx, dl, rev, "native", 2)
	if err != nil {
		t.Fatal(err)
	}
	claim, err := repo.CreateOwnershipClaim(ctx, dl, attempt.Generation, "native", "stage", "runtime", 3)
	if err != nil {
		t.Fatal(err)
	}
	task := mustID(t, identity.ParseBackendTaskID, "bt_00000000000000000000000000000011")
	bound, _, err := repo.BindBackendTask(ctx, dl, attempt.Generation, claim.Revision, task, "native-task", "runtime", 4)
	if err != nil {
		t.Fatal(err)
	}
	ready, err := repo.MarkOwnershipReady(ctx, dl, attempt.Generation, bound.Revision, 5)
	if err != nil {
		t.Fatal(err)
	}
	if _, _, err = repo.ActivateOwnership(ctx, dl, attempt.Generation, ready.Revision, 6); err != nil {
		t.Fatal(err)
	}
	file, err := os.OpenFile(filepath.Join(dir, "staging.bin"), os.O_CREATE|os.O_RDWR, 0o600)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = file.Close() })
	return db, repo, dl, attempt.Generation, file
}

func TestCommitBlockOrdersDurabilityBeforeEvidence(t *testing.T) {
	_, repo, dl, gen, file := setupActiveOwner(t)
	ctx := context.Background()
	var stages []Stage
	c := Committer{Repository: repo, Hook: func(s Stage) error { stages = append(stages, s); return nil }}
	rec, err := c.CommitBlock(ctx, file, CommitRequest{DownloadID: dl, Generation: gen, BlockIndex: 0, StartByte: 0, Data: []byte("durable-block"), NowUnixMS: 7})
	if err != nil {
		t.Fatal(err)
	}
	want := []Stage{AfterWrite, AfterSync, AfterHash, AfterPersist}
	if len(stages) != len(want) {
		t.Fatalf("stages=%v", stages)
	}
	for i := range want {
		if stages[i] != want[i] {
			t.Fatalf("stages=%v want=%v", stages, want)
		}
	}
	if rec.State != "committed" || rec.HashAlgorithm != "sha256" || rec.HashHex == "" {
		t.Fatalf("unexpected record %+v", rec)
	}
	ins, err := Inspect(ctx, repo, file, dl, gen)
	if err != nil {
		t.Fatal(err)
	}
	if len(ins) != 1 || !ins[0].Valid {
		t.Fatalf("inspection=%+v", ins)
	}
}

func TestFaultBeforePersistLeavesNoCheckpointEvidence(t *testing.T) {
	for _, stage := range []Stage{AfterWrite, AfterSync, AfterHash} {
		t.Run(string(stage), func(t *testing.T) {
			_, repo, dl, gen, file := setupActiveOwner(t)
			ctx := context.Background()
			boom := errors.New("simulated crash")
			c := Committer{Repository: repo, Hook: func(s Stage) error {
				if s == stage {
					return boom
				}
				return nil
			}}
			if _, err := c.CommitBlock(ctx, file, CommitRequest{DownloadID: dl, Generation: gen, BlockIndex: 0, StartByte: 0, Data: []byte("bytes"), NowUnixMS: 7}); !errors.Is(err, boom) {
				t.Fatalf("want crash, got %v", err)
			}
			blocks, err := repo.ListCheckpointBlocks(ctx, dl, gen)
			if err != nil {
				t.Fatal(err)
			}
			if len(blocks) != 0 {
				t.Fatalf("uncommitted bytes became resumable: %+v", blocks)
			}
		})
	}
}

func TestFaultAfterPersistLeavesRecoverableEvidence(t *testing.T) {
	_, repo, dl, gen, file := setupActiveOwner(t)
	ctx := context.Background()
	boom := errors.New("crash after persist")
	c := Committer{Repository: repo, Hook: func(s Stage) error {
		if s == AfterPersist {
			return boom
		}
		return nil
	}}
	rec, err := c.CommitBlock(ctx, file, CommitRequest{DownloadID: dl, Generation: gen, BlockIndex: 0, StartByte: 0, Data: []byte("bytes"), NowUnixMS: 7})
	if !errors.Is(err, boom) {
		t.Fatalf("want crash, got %v", err)
	}
	if rec.State != "committed" {
		t.Fatalf("persisted record lost: %+v", rec)
	}
	blocks, _ := repo.ListCheckpointBlocks(ctx, dl, gen)
	if len(blocks) != 1 {
		t.Fatalf("blocks=%+v", blocks)
	}
}

func TestCheckpointRejectsOverlapAndStaleGeneration(t *testing.T) {
	_, repo, dl, gen, file := setupActiveOwner(t)
	ctx := context.Background()
	c := Committer{Repository: repo}
	if _, err := c.CommitBlock(ctx, file, CommitRequest{DownloadID: dl, Generation: gen, BlockIndex: 0, StartByte: 0, Data: []byte("abcdefgh"), NowUnixMS: 7}); err != nil {
		t.Fatal(err)
	}
	if _, err := c.CommitBlock(ctx, file, CommitRequest{DownloadID: dl, Generation: gen, BlockIndex: 1, StartByte: 4, Data: []byte("ijkl"), NowUnixMS: 8}); !errors.Is(err, store.ErrCheckpointOverlap) {
		t.Fatalf("want overlap, got %v", err)
	}
	download, err := repo.GetDownload(ctx, dl)
	if err != nil {
		t.Fatal(err)
	}
	if _, _, err = repo.ReserveAttemptGeneration(ctx, dl, download.Revision, "native", 9); err != nil {
		t.Fatal(err)
	}
	if _, err := c.CommitBlock(ctx, file, CommitRequest{DownloadID: dl, Generation: gen, BlockIndex: 2, StartByte: 8, Data: []byte("stale"), NowUnixMS: 10}); !errors.Is(err, store.ErrStaleAttempt) {
		t.Fatalf("want stale attempt, got %v", err)
	}
}

func TestInspectDetectsTruncationAndHashMismatch(t *testing.T) {
	_, repo, dl, gen, file := setupActiveOwner(t)
	ctx := context.Background()
	c := Committer{Repository: repo}
	if _, err := c.CommitBlock(ctx, file, CommitRequest{DownloadID: dl, Generation: gen, BlockIndex: 0, StartByte: 0, Data: []byte("abcdefgh"), NowUnixMS: 7}); err != nil {
		t.Fatal(err)
	}
	if _, err := file.WriteAt([]byte("Z"), 0); err != nil {
		t.Fatal(err)
	}
	if err := file.Sync(); err != nil {
		t.Fatal(err)
	}
	ins, err := Inspect(ctx, repo, file, dl, gen)
	if err != nil {
		t.Fatal(err)
	}
	if len(ins) != 1 || ins[0].Valid || ins[0].Reason != "hash_mismatch" {
		t.Fatalf("inspection=%+v", ins)
	}
	if err := file.Truncate(2); err != nil {
		t.Fatal(err)
	}
	ins, err = Inspect(ctx, repo, file, dl, gen)
	if err != nil {
		t.Fatal(err)
	}
	if len(ins) != 1 || ins[0].Valid || ins[0].Reason != "staging_truncated" {
		t.Fatalf("inspection=%+v", ins)
	}
}

func TestDefaultBlockSizeIsIndependentPolicy(t *testing.T) {
	if DefaultBlockSize <= 0 {
		t.Fatal("invalid default block size")
	}
	if err := ValidateBlockSize(DefaultBlockSize); err != nil {
		t.Fatal(err)
	}
	if err := ValidateBlockSize(0); err == nil {
		t.Fatal("zero block size accepted")
	}
}
