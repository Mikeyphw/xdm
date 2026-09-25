//go:build cgo

package httptransfer

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/subhra74/xdm/engine/store/checkpoint"
	store "github.com/subhra74/xdm/engine/store/sqlite"
)

func commitFixtureBlock(t *testing.T, fx transferFixture, index, start int64, data []byte) {
	t.Helper()
	if _, err := (checkpoint.Committer{Repository: fx.repo}).CommitBlock(context.Background(), fx.file, checkpoint.CommitRequest{DownloadID: fx.dl, Generation: fx.gen, BlockIndex: index, StartByte: start, Data: data, NowUnixMS: 10 + index}); err != nil {
		t.Fatal(err)
	}
}

func TestBuildResumePlanUsesSparseVerifiedBlocks(t *testing.T) {
	fx := setupTransferFixture(t)
	total := int64(3 * 4096)
	if err := fx.file.Truncate(total); err != nil {
		t.Fatal(err)
	}
	a := make([]byte, 4096)
	b := make([]byte, 4096)
	for i := range a {
		a[i] = 'A'
		b[i] = 'C'
	}
	commitFixtureBlock(t, fx, 0, 0, a)
	commitFixtureBlock(t, fx, 2, 8192, b)
	rep := representationFor(t, "https://example.test/file", total, `"same"`)
	plan, err := BuildResumePlan(context.Background(), fx.repo, fx.file, fx.dl, fx.gen, rep, rep, ResumePolicy{})
	if err != nil {
		t.Fatal(err)
	}
	if len(plan.Reusable) != 2 || len(plan.Missing) != 1 || plan.Missing[0] != (ByteRange{4096, 8191}) {
		t.Fatalf("plan=%+v", plan)
	}
}

func TestBuildResumePlanInvalidatesCorruptBlockButKeepsLaterValidBlock(t *testing.T) {
	fx := setupTransferFixture(t)
	total := int64(3 * 4096)
	_ = fx.file.Truncate(total)
	a := make([]byte, 4096)
	c := make([]byte, 4096)
	for i := range a {
		a[i] = 'A'
		c[i] = 'C'
	}
	commitFixtureBlock(t, fx, 0, 0, a)
	commitFixtureBlock(t, fx, 2, 8192, c)
	if _, err := fx.file.WriteAt([]byte("Z"), 0); err != nil {
		t.Fatal(err)
	}
	_ = fx.file.Sync()
	rep := representationFor(t, "https://example.test/file", total, `"same"`)
	plan, err := BuildResumePlan(context.Background(), fx.repo, fx.file, fx.dl, fx.gen, rep, rep, ResumePolicy{})
	if err != nil {
		t.Fatal(err)
	}
	if len(plan.Invalidated) != 1 || plan.Invalidated[0] != 0 {
		t.Fatalf("plan=%+v", plan)
	}
	if len(plan.Reusable) != 1 || plan.Reusable[0] != (ByteRange{8192, 12287}) {
		t.Fatalf("reusable=%+v", plan.Reusable)
	}
	blocks, _ := fx.repo.ListCheckpointBlocks(context.Background(), fx.dl, fx.gen)
	if blocks[0].State != "invalid" || blocks[1].State != "committed" {
		t.Fatalf("blocks=%+v", blocks)
	}
}

func TestChangedRepresentationInvalidatesAllCommittedBlocks(t *testing.T) {
	fx := setupTransferFixture(t)
	total := int64(4096)
	_ = fx.file.Truncate(total)
	data := make([]byte, total)
	commitFixtureBlock(t, fx, 0, 0, data)
	old := representationFor(t, "https://example.test/file", total, `"old"`)
	current := representationFor(t, "https://example.test/file", total, `"new"`)
	plan, err := BuildResumePlan(context.Background(), fx.repo, fx.file, fx.dl, fx.gen, old, current, ResumePolicy{})
	if err != nil {
		t.Fatal(err)
	}
	if !plan.RestartRequired || len(plan.Invalidated) != 1 || len(plan.Missing) != 1 || plan.Missing[0].Start != 0 {
		t.Fatalf("plan=%+v", plan)
	}
}

func TestResumeAtEOFIsComplete(t *testing.T) {
	fx := setupTransferFixture(t)
	total := int64(4096)
	_ = fx.file.Truncate(total)
	commitFixtureBlock(t, fx, 0, 0, make([]byte, total))
	rep := representationFor(t, "https://example.test/file", total, `"same"`)
	plan, err := BuildResumePlan(context.Background(), fx.repo, fx.file, fx.dl, fx.gen, rep, rep, ResumePolicy{})
	if err != nil {
		t.Fatal(err)
	}
	if !plan.Complete || len(plan.Missing) != 0 {
		t.Fatalf("plan=%+v", plan)
	}
}

func TestStaleAttemptCannotBuildResumePlan(t *testing.T) {
	fx := setupTransferFixture(t)
	d, err := fx.repo.GetDownload(context.Background(), fx.dl)
	if err != nil {
		t.Fatal(err)
	}
	if _, _, err = fx.repo.ReserveAttemptGeneration(context.Background(), fx.dl, d.Revision, "native", 20); err != nil {
		t.Fatal(err)
	}
	rep := representationFor(t, "https://example.test/file", 4096, `"same"`)
	_, err = BuildResumePlan(context.Background(), fx.repo, fx.file, fx.dl, fx.gen, rep, rep, ResumePolicy{})
	if !errors.Is(err, store.ErrStaleAttempt) {
		t.Fatalf("want stale attempt, got %v", err)
	}
}

func TestResume200AfterIfRangeInvalidatesBeforeWriting(t *testing.T) {
	fx := setupTransferFixture(t)
	total := int64(8192)
	_ = fx.file.Truncate(total)
	first := make([]byte, 4096)
	for i := range first {
		first[i] = 'A'
	}
	commitFixtureBlock(t, fx, 0, 0, first)
	rep := representationFor(t, "https://placeholder", total, `"v1"`)
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if got := r.Header.Get("If-Range"); got != `"v1"` {
			t.Errorf("If-Range=%q", got)
		}
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write(make([]byte, total))
	}))
	defer srv.Close()
	rep.EffectiveURL = srv.URL
	_, err := ExecuteResume(context.Background(), srv.Client(), ResumeExecutePlan{URL: srv.URL, Representation: rep, DownloadID: fx.dl, Generation: fx.gen, Ranges: []ByteRange{{4096, 8191}}, BlockBytes: 4096, BufferBytes: 4096, File: fx.file, Committer: checkpoint.Committer{Repository: fx.repo}, Repository: fx.repo, Lifecycle: &fakeLifecycle{}})
	if err == nil {
		t.Fatal("200 resume accepted")
	}
	blocks, _ := fx.repo.ListCheckpointBlocks(context.Background(), fx.dl, fx.gen)
	if len(blocks) != 1 || blocks[0].State != "invalid" {
		t.Fatalf("blocks=%+v", blocks)
	}
	raw := make([]byte, 1)
	_, _ = fx.file.ReadAt(raw, 4096)
	if raw[0] != 0 {
		t.Fatalf("response byte written before restart: %q", raw[0])
	}
}

func TestExecuteResumeRefetchesInvalidSparseHole(t *testing.T) {
	block := int64(4096)
	data := make([]byte, 3*block)
	for i := range data {
		data[i] = byte(i % 251)
	}
	srv := rangedServer(data, nil)
	defer srv.Close()
	fx := setupTransferFixture(t)
	_ = fx.file.Truncate(int64(len(data)))
	commitFixtureBlock(t, fx, 0, 0, data[:block])
	commitFixtureBlock(t, fx, 2, 2*block, data[2*block:])
	rep := representationFor(t, srv.URL, int64(len(data)), `"seg-v1"`)
	plan, err := BuildResumePlan(context.Background(), fx.repo, fx.file, fx.dl, fx.gen, rep, rep, ResumePolicy{})
	if err != nil {
		t.Fatal(err)
	}
	if fmt.Sprint(plan.Missing) != fmt.Sprint([]ByteRange{{block, 2*block - 1}}) {
		t.Fatalf("missing=%v", plan.Missing)
	}
	got, err := ExecuteResume(context.Background(), srv.Client(), ResumeExecutePlan{URL: srv.URL, Representation: rep, DownloadID: fx.dl, Generation: fx.gen, Ranges: plan.Missing, BlockBytes: block, BufferBytes: 4096, File: fx.file, Committer: checkpoint.Committer{Repository: fx.repo}, Repository: fx.repo, Lifecycle: &fakeLifecycle{}})
	if err != nil {
		t.Fatal(err)
	}
	if got.Committed != int64(len(data)) {
		t.Fatalf("result=%+v", got)
	}
}

func TestTruncatedStagingInvalidatesCommittedCheckpoint(t *testing.T) {
	fx := setupTransferFixture(t)
	total := int64(8192)
	_ = fx.file.Truncate(total)
	commitFixtureBlock(t, fx, 0, 0, make([]byte, 4096))
	if err := fx.file.Truncate(1024); err != nil {
		t.Fatal(err)
	}
	rep := representationFor(t, "https://example.test/file", total, `"same"`)
	plan, err := BuildResumePlan(context.Background(), fx.repo, fx.file, fx.dl, fx.gen, rep, rep, ResumePolicy{})
	if err != nil {
		t.Fatal(err)
	}
	if len(plan.Invalidated) != 1 || plan.Invalidated[0] != 0 {
		t.Fatalf("plan=%+v", plan)
	}
}
