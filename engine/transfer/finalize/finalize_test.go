//go:build cgo

package finalize_test

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"os"
	"path/filepath"
	"testing"

	"github.com/subhra74/xdm/engine/domain/identity"
	"github.com/subhra74/xdm/engine/domain/publication"
	store "github.com/subhra74/xdm/engine/store/sqlite"
	"github.com/subhra74/xdm/engine/transfer/checksum"
	"github.com/subhra74/xdm/engine/transfer/finalize"
	"github.com/subhra74/xdm/engine/transfer/staging"
)

type fx struct {
	db       *store.DB
	repo     *store.Repository
	dl       identity.DownloadID
	gen      identity.AttemptGeneration
	download store.DownloadRecord
	file     *staging.File
	data     []byte
}

func newFX(t *testing.T, data []byte) *fx {
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
	req, _ := identity.ParseRequestID("req_00000000000000000000000000000091")
	dl, _ := identity.ParseDownloadID("dl_00000000000000000000000000000091")
	rev, _ := identity.NewRevision(1)
	if err = repo.CreateRequest(ctx, store.RequestRecord{ID: req, Revision: rev, ResourceIdentity: "finalize", Method: "GET", SafeSpecJSON: `{"finalize":true}`, CreatedAtUnixMS: 1}); err != nil {
		t.Fatal(err)
	}
	if err = repo.CreateDownload(ctx, store.DownloadRecord{ID: dl, RequestID: req, CurrentRequestRevision: rev, State: "open", Revision: rev, CreatedAtUnixMS: 1, UpdatedAtUnixMS: 1}); err != nil {
		t.Fatal(err)
	}
	attempt, download, err := repo.ReserveAttemptGeneration(ctx, dl, rev, "native", 2)
	if err != nil {
		t.Fatal(err)
	}
	attempt, err = repo.MutateAttempt(ctx, dl, attempt.Generation, attempt.Revision, "prepared", "", "", 3)
	if err != nil {
		t.Fatal(err)
	}
	attempt, err = repo.MutateAttempt(ctx, dl, attempt.Generation, attempt.Revision, "running", "", "", 4)
	if err != nil {
		t.Fatal(err)
	}
	attempt, err = repo.MutateAttempt(ctx, dl, attempt.Generation, attempt.Revision, "transport_complete", "", "", 5)
	if err != nil {
		t.Fatal(err)
	}
	raw, err := os.OpenFile(filepath.Join(dir, "staging.bin"), os.O_CREATE|os.O_RDWR, 0600)
	if err != nil {
		t.Fatal(err)
	}
	if _, err = raw.Write(data); err != nil {
		t.Fatal(err)
	}
	if err = raw.Sync(); err != nil {
		t.Fatal(err)
	}
	_, _ = raw.Seek(0, 0)
	return &fx{db: db, repo: repo, dl: dl, gen: attempt.Generation, download: download, file: staging.Wrap(raw), data: data}
}
func (f *fx) close() { _ = f.file.Close(); _ = f.db.Close() }
func verID(t *testing.T, suffix string) identity.VerificationID {
	t.Helper()
	v, e := identity.ParseVerificationID("ver_" + suffix)
	if e != nil {
		t.Fatal(e)
	}
	return v
}
func pubID(t *testing.T, suffix string) identity.PublicationID {
	t.Helper()
	v, e := identity.ParsePublicationID("pub_" + suffix)
	if e != nil {
		t.Fatal(e)
	}
	return v
}
func expected(data []byte) checksum.Expected {
	s := sha256.Sum256(data)
	v, _ := checksum.NormalizeExpected(checksum.SourceUser, "sha256", hex.EncodeToString(s[:]))
	return v
}
func request(t *testing.T, f *fx, exp *checksum.Expected) finalize.Request {
	t.Helper()
	return finalize.Request{Repository: f.repo, DownloadID: f.dl, Generation: f.gen, ExpectedDownloadRevision: f.download.Revision,
		VerificationID: verID(t, "00000000000000000000000000000091"), PublicationID: pubID(t, "00000000000000000000000000000091"),
		PublicationKey: "finalize-91", StagingIdentity: f.file.Name(), SizeBytes: int64(len(f.data)), Reader: f.file, Freeze: f.file.Freeze, Expected: exp, NowUnixMS: 10}
}

func TestFinalizeCreatesVerifiedArtifactPromotesAttemptAndQueuesPublication(t *testing.T) {
	f := newFX(t, []byte("verified-finalization-payload"))
	defer f.close()
	exp := expected(f.data)
	got, err := finalize.Finalize(context.Background(), request(t, f, &exp))
	if err != nil {
		t.Fatal(err)
	}
	if !got.Verification.Matched || got.Record.Result != store.VerificationPassed || got.Artifact.VerificationState != "verified" || got.Artifact.PublicationState != "publishing" || got.Publication.State != publication.Prepared {
		t.Fatalf("got=%+v", got)
	}
	attempt, err := f.repo.GetAttempt(context.Background(), f.dl, f.gen)
	if err != nil {
		t.Fatal(err)
	}
	if attempt.State != "produced_artifact" {
		t.Fatalf("attempt=%+v", attempt)
	}
}

func TestChecksumMismatchJournalsFailureWithoutArtifactOrPublication(t *testing.T) {
	f := newFX(t, []byte("wrong-checksum-payload"))
	defer f.close()
	exp := expected([]byte("different"))
	got, err := finalize.Finalize(context.Background(), request(t, f, &exp))
	if !errors.Is(err, checksum.ErrMismatch) {
		t.Fatalf("err=%v", err)
	}
	if got.Record.Result != store.VerificationFailed || got.Record.ArtifactGeneration != nil {
		t.Fatalf("got=%+v", got)
	}
	download, _ := f.repo.GetDownload(context.Background(), f.dl)
	if download.CurrentArtifact != nil {
		t.Fatalf("unverified artifact escaped: %+v", download)
	}
	attempt, _ := f.repo.GetAttempt(context.Background(), f.dl, f.gen)
	if attempt.State != "transport_complete" {
		t.Fatalf("attempt=%+v", attempt)
	}
}

func TestCrashAfterArtifactCreationLeavesRecoverableUnpublishedArtifact(t *testing.T) {
	f := newFX(t, []byte("crash-window"))
	defer f.close()
	exp := expected(f.data)
	req := request(t, f, &exp)
	boom := errors.New("crash after artifact")
	req.Hook = func(stage finalize.Stage) error {
		if stage == finalize.AfterVerifiedArtifact {
			return boom
		}
		return nil
	}
	got, err := finalize.Finalize(context.Background(), req)
	if !errors.Is(err, boom) || got.Artifact.Generation.Int64() != 1 {
		t.Fatalf("got=%+v err=%v", got, err)
	}
	stored, err := f.repo.GetArtifact(context.Background(), f.dl, got.Artifact.Generation)
	if err != nil {
		t.Fatal(err)
	}
	if stored.PublicationState != "unpublished" {
		t.Fatalf("stored=%+v", stored)
	}
	attempt, _ := f.repo.GetAttempt(context.Background(), f.dl, f.gen)
	if attempt.State != "produced_artifact" {
		t.Fatalf("attempt=%+v", attempt)
	}
}

func TestCancelledVerificationCreatesNoArtifact(t *testing.T) {
	f := newFX(t, make([]byte, 1<<20))
	defer f.close()
	exp := expected(f.data)
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	_, err := finalize.Finalize(ctx, request(t, f, &exp))
	if !errors.Is(err, context.Canceled) {
		t.Fatalf("err=%v", err)
	}
	download, _ := f.repo.GetDownload(context.Background(), f.dl)
	if download.CurrentArtifact != nil {
		t.Fatalf("artifact=%+v", download.CurrentArtifact)
	}
	rows, _ := f.repo.ListVerificationRecords(context.Background(), f.dl)
	if len(rows) != 0 {
		t.Fatalf("records=%+v", rows)
	}
}

func TestStaleAttemptCannotFinalize(t *testing.T) {
	f := newFX(t, []byte("stale"))
	defer f.close()
	current, _ := f.repo.GetDownload(context.Background(), f.dl)
	if _, _, err := f.repo.ReserveAttemptGeneration(context.Background(), f.dl, current.Revision, "native", 20); err != nil {
		t.Fatal(err)
	}
	exp := expected(f.data)
	_, err := finalize.Finalize(context.Background(), request(t, f, &exp))
	if !errors.Is(err, store.ErrStaleAttempt) {
		t.Fatalf("err=%v", err)
	}
}
