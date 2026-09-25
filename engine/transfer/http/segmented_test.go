//go:build cgo

package httptransfer

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/subhra74/xdm/engine/domain/identity"
	"github.com/subhra74/xdm/engine/domain/resource"
	"github.com/subhra74/xdm/engine/store/checkpoint"
	store "github.com/subhra74/xdm/engine/store/sqlite"
)

type transferFixture struct {
	repo *store.Repository
	dl   identity.DownloadID
	gen  identity.AttemptGeneration
	file *os.File
}

func setupTransferFixture(t *testing.T) transferFixture {
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
	req, _ := identity.ParseRequestID("req_00000000000000000000000000000091")
	dl, _ := identity.ParseDownloadID("dl_00000000000000000000000000000091")
	rev, _ := identity.NewRevision(1)
	if err = repo.CreateRequest(ctx, store.RequestRecord{ID: req, Revision: rev, ResourceIdentity: "res:segmented", Method: "GET", SafeSpecJSON: `{"segmented":true}`, CreatedAtUnixMS: 1}); err != nil {
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
	task, _ := identity.ParseBackendTaskID("bt_00000000000000000000000000000091")
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
	return transferFixture{repo, dl, attempt.Generation, file}
}

func representationFor(t *testing.T, url string, n int64, etag string) Representation {
	t.Helper()
	r, _ := resource.DeriveIdentity("test", url)
	rep, err := RepresentationFromProbe(r, ProbeResult{EffectiveURL: url, Length: &n, ETag: etag})
	if err != nil {
		t.Fatal(err)
	}
	return rep
}

func rangedServer(data []byte, mutate func(http.ResponseWriter, *http.Request, int64, int64) bool) *httptest.Server {
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		raw := r.Header.Get("Range")
		if raw == "" {
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write(data)
			return
		}
		var start, end int64
		if _, err := fmt.Sscanf(raw, "bytes=%d-%d", &start, &end); err != nil {
			w.WriteHeader(http.StatusBadRequest)
			return
		}
		if mutate != nil && mutate(w, r, start, end) {
			return
		}
		if start < 0 || end < start || end >= int64(len(data)) {
			w.WriteHeader(http.StatusRequestedRangeNotSatisfiable)
			return
		}
		w.Header().Set("Content-Range", fmt.Sprintf("bytes %d-%d/%d", start, end, len(data)))
		w.Header().Set("Content-Length", strconv.FormatInt(end-start+1, 10))
		w.Header().Set("ETag", `"seg-v1"`)
		w.WriteHeader(http.StatusPartialContent)
		_, _ = w.Write(data[start : end+1])
	}))
}

func TestPlanSegmentsTwoFourEightCoverWithoutOverlap(t *testing.T) {
	for _, count := range []int{2, 4, 8} {
		segs, err := PlanSegments(128<<10, count, 4096)
		if err != nil {
			t.Fatal(err)
		}
		if len(segs) != count {
			t.Fatalf("count=%d got=%d", count, len(segs))
		}
		cursor := int64(0)
		for _, s := range segs {
			if s.Start != cursor || s.End < s.Start {
				t.Fatalf("bad segment %+v", s)
			}
			cursor = s.End + 1
		}
		if cursor != 128<<10 {
			t.Fatalf("coverage=%d", cursor)
		}
	}
}

func TestSegmentedTransferRandomAccessOutOfOrderAndByteExact(t *testing.T) {
	data := []byte(strings.Repeat("0123456789abcdef", 8192))
	srv := rangedServer(data, func(w http.ResponseWriter, r *http.Request, start, end int64) bool {
		if start == 0 && end > 0 {
			time.Sleep(25 * time.Millisecond)
		}
		return false
	})
	defer srv.Close()
	fx := setupTransferFixture(t)
	rep := representationFor(t, srv.URL, int64(len(data)), `"seg-v1"`)
	life := &fakeLifecycle{}
	got, err := ExecuteSegmented(context.Background(), srv.Client(), SegmentedPlan{URL: srv.URL, Representation: rep, DownloadID: fx.dl, Generation: fx.gen, SegmentCount: 8, BlockBytes: 4096, BufferBytes: 4096, File: fx.file, Committer: checkpoint.Committer{Repository: fx.repo}, Ledger: fx.repo, Lifecycle: life})
	if err != nil {
		t.Fatal(err)
	}
	if got.Segments != 8 || got.Bytes != int64(len(data)) {
		t.Fatalf("result=%+v", got)
	}
	raw, err := os.ReadFile(fx.file.Name())
	if err != nil {
		t.Fatal(err)
	}
	if string(raw) != string(data) {
		t.Fatal("staging differs")
	}
	rows, err := fx.repo.ListCheckpointBlocks(context.Background(), fx.dl, fx.gen)
	if err != nil {
		t.Fatal(err)
	}
	if len(rows) == 0 {
		t.Fatal("no checkpoint evidence")
	}
}

func TestSegmentedRejectsWrongContentRangeBeforeWritingSegment(t *testing.T) {
	data := []byte(strings.Repeat("x", 64<<10))
	srv := rangedServer(data, func(w http.ResponseWriter, r *http.Request, start, end int64) bool {
		if !(start == 0 && end == 0) {
			w.Header().Set("Content-Range", fmt.Sprintf("bytes %d-%d/%d", start+1, end, len(data)))
			w.WriteHeader(http.StatusPartialContent)
			return true
		}
		return false
	})
	defer srv.Close()
	fx := setupTransferFixture(t)
	rep := representationFor(t, srv.URL, int64(len(data)), `"seg-v1"`)
	_, err := ExecuteSegmented(context.Background(), srv.Client(), SegmentedPlan{URL: srv.URL, Representation: rep, DownloadID: fx.dl, Generation: fx.gen, SegmentCount: 4, BlockBytes: 4096, BufferBytes: 4096, File: fx.file, Committer: checkpoint.Committer{Repository: fx.repo}, Ledger: fx.repo, Lifecycle: &fakeLifecycle{}})
	if err == nil {
		t.Fatal("wrong range accepted")
	}
	blocks, _ := fx.repo.ListCheckpointBlocks(context.Background(), fx.dl, fx.gen)
	if len(blocks) != 0 {
		t.Fatalf("corrupt response became evidence: %+v", blocks)
	}
}

func TestAdaptiveFallsBackBeforeSegmentEvidenceWhenRangeIgnored(t *testing.T) {
	data := []byte(strings.Repeat("fallback", 4096))
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(http.StatusOK); _, _ = w.Write(data) }))
	defer srv.Close()
	fx := setupTransferFixture(t)
	rep := representationFor(t, srv.URL, int64(len(data)), `"v1"`)
	life := &fakeLifecycle{}
	seg := SegmentedPlan{URL: srv.URL, Representation: rep, DownloadID: fx.dl, Generation: fx.gen, SegmentCount: 4, BlockBytes: 4096, BufferBytes: 4096, File: fx.file, Committer: checkpoint.Committer{Repository: fx.repo}, Ledger: fx.repo, Lifecycle: life}
	single := ExecutePlan{URL: srv.URL, Representation: rep, DownloadID: fx.dl, Generation: fx.gen, BufferBytes: 4096, CheckpointBytes: 4096, File: fx.file, Committer: checkpoint.Committer{Repository: fx.repo}, Lifecycle: life}
	got, err := ExecuteAdaptive(context.Background(), srv.Client(), seg, single)
	if err != nil {
		t.Fatal(err)
	}
	if !got.Fallback {
		t.Fatal("did not fall back")
	}
	raw, _ := os.ReadFile(fx.file.Name())
	if string(raw) != string(data) {
		t.Fatal("fallback bytes differ")
	}
}

type failingPreallocate struct{ *os.File }

func (f failingPreallocate) Truncate(int64) error { return errors.New("no space") }
func TestPreallocationFailureProducesNoCheckpoint(t *testing.T) {
	data := []byte(strings.Repeat("z", 32<<10))
	srv := rangedServer(data, nil)
	defer srv.Close()
	fx := setupTransferFixture(t)
	rep := representationFor(t, srv.URL, int64(len(data)), `"seg-v1"`)
	_, err := ExecuteSegmented(context.Background(), srv.Client(), SegmentedPlan{URL: srv.URL, Representation: rep, DownloadID: fx.dl, Generation: fx.gen, SegmentCount: 2, BlockBytes: 4096, BufferBytes: 4096, File: failingPreallocate{fx.file}, Committer: checkpoint.Committer{Repository: fx.repo}, Ledger: fx.repo, Lifecycle: &fakeLifecycle{}})
	if err == nil {
		t.Fatal("preallocation failure ignored")
	}
	blocks, _ := fx.repo.ListCheckpointBlocks(context.Background(), fx.dl, fx.gen)
	if len(blocks) != 0 {
		t.Fatalf("blocks=%v", blocks)
	}
}

func TestSegmentedRejectsShortSegmentBody(t *testing.T) {
	data := []byte(strings.Repeat("short", 16<<10))
	srv := rangedServer(data, func(w http.ResponseWriter, r *http.Request, start, end int64) bool {
		if start == 0 && end == 0 {
			return false
		}
		w.Header().Set("Content-Range", fmt.Sprintf("bytes %d-%d/%d", start, end, len(data)))
		w.Header().Set("Content-Length", strconv.FormatInt(end-start+1, 10))
		w.WriteHeader(http.StatusPartialContent)
		_, _ = w.Write(data[start : start+(end-start+1)/2])
		return true
	})
	defer srv.Close()
	fx := setupTransferFixture(t)
	rep := representationFor(t, srv.URL, int64(len(data)), `"seg-v1"`)
	_, err := ExecuteSegmented(context.Background(), srv.Client(), SegmentedPlan{URL: srv.URL, Representation: rep, DownloadID: fx.dl, Generation: fx.gen, SegmentCount: 4, BlockBytes: 4096, BufferBytes: 4096, File: fx.file, Committer: checkpoint.Committer{Repository: fx.repo}, Ledger: fx.repo, Lifecycle: &fakeLifecycle{}})
	if err == nil {
		t.Fatal("short segment accepted")
	}
}
