//go:build cgo

package repair_test

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"

	"github.com/subhra74/xdm/engine/domain/identity"
	"github.com/subhra74/xdm/engine/domain/resource"
	"github.com/subhra74/xdm/engine/store/checkpoint"
	store "github.com/subhra74/xdm/engine/store/sqlite"
	"github.com/subhra74/xdm/engine/transfer/checksum"
	httptransfer "github.com/subhra74/xdm/engine/transfer/http"
	"github.com/subhra74/xdm/engine/transfer/repair"
	"github.com/subhra74/xdm/engine/transfer/staging"
)

type fixture struct {
	db     *store.DB
	repo   *store.Repository
	dl     identity.DownloadID
	gen    identity.AttemptGeneration
	file   *staging.File
	life   *httptransfer.SQLiteLifecycle
	rep    httptransfer.Representation
	data   []byte
	server *httptest.Server
}

func newFixture(t *testing.T, data []byte, block int64) *fixture {
	t.Helper()
	ctx := context.Background()
	dir := t.TempDir()
	db, err := store.Open(filepath.Join(dir, "engine.sqlite"), store.DefaultOptions())
	if err != nil {
		t.Fatal(err)
	}
	if err = store.Migrate(ctx, db, 1); err != nil {
		t.Fatal(err)
	}
	repo, _ := store.NewRepository(db)
	req, _ := identity.ParseRequestID("req_00000000000000000000000000000081")
	dl, _ := identity.ParseDownloadID("dl_00000000000000000000000000000081")
	rev, _ := identity.NewRevision(1)
	if err = repo.CreateRequest(ctx, store.RequestRecord{ID: req, Revision: rev, ResourceIdentity: "repair", Method: "GET", SafeSpecJSON: `{"repair":true}`, CreatedAtUnixMS: 1}); err != nil {
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
	task, _ := identity.ParseBackendTaskID("bt_00000000000000000000000000000081")
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
	raw, err := os.OpenFile(filepath.Join(dir, "staging.bin"), os.O_CREATE|os.O_RDWR, 0600)
	if err != nil {
		t.Fatal(err)
	}
	file := staging.Wrap(raw)
	life := &httptransfer.SQLiteLifecycle{Repository: repo, DownloadID: dl, Generation: attempt.Generation, Revision: attempt.Revision, State: "reserved"}
	if err = life.Start(ctx); err != nil {
		t.Fatal(err)
	}
	committer := checkpoint.Committer{Repository: repo}
	for start, idx := int64(0), int64(0); start < int64(len(data)); start, idx = start+block, idx+1 {
		end := start + block
		if end > int64(len(data)) {
			end = int64(len(data))
		}
		if _, err = committer.CommitBlock(ctx, file, checkpoint.CommitRequest{DownloadID: dl, Generation: attempt.Generation, BlockIndex: idx, StartByte: start, Data: data[start:end], NowUnixMS: 10 + idx}); err != nil {
			t.Fatal(err)
		}
	}
	if err = life.Complete(ctx); err != nil {
		t.Fatal(err)
	}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var s, e int64
		if _, err := fmt.Sscanf(r.Header.Get("Range"), "bytes=%d-%d", &s, &e); err != nil || s < 0 || e < s || e >= int64(len(data)) {
			w.WriteHeader(http.StatusRequestedRangeNotSatisfiable)
			return
		}
		w.Header().Set("Content-Range", fmt.Sprintf("bytes %d-%d/%d", s, e, len(data)))
		w.Header().Set("Content-Length", strconv.FormatInt(e-s+1, 10))
		w.Header().Set("ETag", `"repair-v1"`)
		w.WriteHeader(http.StatusPartialContent)
		_, _ = w.Write(data[s : e+1])
	}))
	rid, _ := resource.DeriveIdentity("repair", "fixture")
	n := int64(len(data))
	rep, _ := httptransfer.RepresentationFromProbe(rid, httptransfer.ProbeResult{EffectiveURL: srv.URL, Length: &n, ETag: `"repair-v1"`})
	return &fixture{db: db, repo: repo, dl: dl, gen: attempt.Generation, file: file, life: life, rep: rep, data: data, server: srv}
}

func (f *fixture) close() { f.server.Close(); _ = f.file.Close(); _ = f.db.Close() }

func expectedFor(data []byte) checksum.Expected {
	sum := sha256.Sum256(data)
	v, _ := checksum.NormalizeExpected(checksum.SourceUser, "sha256", hex.EncodeToString(sum[:]))
	return v
}

func TestSelectiveRepairRefetchesOnlyDamagedRangesAndPreservesGoodBlocks(t *testing.T) {
	data := []byte(strings.Repeat("repair-block-", 4096))
	const block = int64(4096)
	fx := newFixture(t, data, block)
	defer fx.close()
	before := make([]byte, block)
	_, _ = fx.file.ReadAt(before, block)
	_, _ = fx.file.WriteAt([]byte("CORRUPT"), 0)
	_, _ = fx.file.WriteAt([]byte("BROKEN"), block*2)
	result, err := repair.Execute(context.Background(), fx.server.Client(), repair.ExecuteRequest{
		URL: fx.server.URL, Previous: fx.rep, Current: fx.rep, Policy: httptransfer.ResumePolicy{}, DownloadID: fx.dl, Generation: fx.gen,
		BlockBytes: block, BufferBytes: 4096, File: fx.file, Committer: checkpoint.Committer{Repository: fx.repo}, Repository: fx.repo,
		Lifecycle: fx.life, Expected: expectedFor(data),
	})
	if err != nil {
		t.Fatal(err)
	}
	if len(result.Plan.DamagedBlocks) != 2 || result.FetchedBytes != result.Plan.Bytes || !result.Verification.Matched {
		t.Fatalf("result=%+v", result)
	}
	after := make([]byte, block)
	_, _ = fx.file.ReadAt(after, block)
	if string(before) != string(after) {
		t.Fatal("good block changed during selective repair")
	}
	raw, _ := os.ReadFile(fx.file.Name())
	if string(raw) != string(data) {
		t.Fatal("repair did not restore canonical bytes")
	}
}

func TestSelectiveRepairAbortsBeforeInvalidationWhenRepresentationChanges(t *testing.T) {
	data := []byte(strings.Repeat("repair-change-", 1024))
	fx := newFixture(t, data, 4096)
	defer fx.close()
	_, _ = fx.file.WriteAt([]byte("BAD"), 0)
	changed := fx.rep
	changed.ETag = `"repair-v2"`
	_, err := repair.BuildPlan(context.Background(), fx.repo, fx.file, fx.dl, fx.gen, fx.rep, changed, httptransfer.ResumePolicy{})
	if !errors.Is(err, repair.ErrRepresentationChanged) {
		t.Fatalf("err=%v", err)
	}
	blocks, _ := fx.repo.ListCheckpointBlocks(context.Background(), fx.dl, fx.gen)
	if len(blocks) == 0 || blocks[0].State != "committed" {
		t.Fatalf("evidence mutated before representation decision: %+v", blocks)
	}
}
