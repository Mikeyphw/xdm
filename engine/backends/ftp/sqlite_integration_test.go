//go:build cgo

package ftpbackend

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"os"
	"path/filepath"
	"testing"

	"github.com/subhra74/xdm/engine/domain/identity"
	domainrequest "github.com/subhra74/xdm/engine/domain/request"
	"github.com/subhra74/xdm/engine/store/checkpoint"
	store "github.com/subhra74/xdm/engine/store/sqlite"
	"github.com/subhra74/xdm/engine/transfer/checksum"
	"github.com/subhra74/xdm/engine/transfer/finalize"
	transferlifecycle "github.com/subhra74/xdm/engine/transfer/lifecycle"
	"github.com/subhra74/xdm/engine/transfer/staging"
)

func mustRevision37(t *testing.T, n int64) identity.Revision {
	t.Helper()
	v, e := identity.NewRevision(n)
	if e != nil {
		t.Fatal(e)
	}
	return v
}

func TestFTPEndToEndUsesCanonicalOwnershipCheckpointVerificationPublication(t *testing.T) {
	ctx := context.Background()
	dir := t.TempDir()
	db, err := store.Open(filepath.Join(dir, "engine.sqlite"), store.DefaultOptions())
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	if err = store.Migrate(ctx, db, 1); err != nil {
		t.Fatal(err)
	}
	repo, _ := store.NewRepository(db)
	req, _ := identity.ParseRequestID("req_00000000000000000000000000000037")
	dl, _ := identity.ParseDownloadID("dl_00000000000000000000000000000037")
	rev := mustRevision37(t, 1)
	if err = repo.CreateRequest(ctx, store.RequestRecord{ID: req, Revision: rev, ResourceIdentity: "ftp-e2e", Method: "GET", SafeSpecJSON: `{"ftp":true}`, CreatedAtUnixMS: 1}); err != nil {
		t.Fatal(err)
	}
	if err = repo.CreateDownload(ctx, store.DownloadRecord{ID: dl, RequestID: req, CurrentRequestRevision: rev, State: "open", Revision: rev, CreatedAtUnixMS: 1, UpdatedAtUnixMS: 1}); err != nil {
		t.Fatal(err)
	}
	attempt, download, err := repo.ReserveAttemptGeneration(ctx, dl, rev, "native", 2)
	if err != nil {
		t.Fatal(err)
	}
	claim, err := repo.CreateOwnershipClaim(ctx, dl, attempt.Generation, "native", "ftp-stage", "runtime-37", 3)
	if err != nil {
		t.Fatal(err)
	}
	task, _ := identity.ParseBackendTaskID("bt_00000000000000000000000000000037")
	bound, _, err := repo.BindBackendTask(ctx, dl, attempt.Generation, claim.Revision, task, "ftp-native-37", "runtime-37", 4)
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

	raw, err := os.OpenFile(filepath.Join(dir, "staging.bin"), os.O_CREATE|os.O_RDWR, 0o600)
	if err != nil {
		t.Fatal(err)
	}
	stage := staging.Wrap(raw)
	defer stage.Close()
	payload := []byte("ftp canonical lifecycle payload")
	n := int64(len(payload))
	intent := testIntent(t, "ftp://example.test/file.bin", &n)
	factory, err := NewFactory(intent, nil)
	if err != nil {
		t.Fatal(err)
	}
	sess := &fakeSession{data: payload, size: n}
	lifecycle := &transferlifecycle.SQLiteLifecycle{Repository: repo, DownloadID: dl, Generation: attempt.Generation, Revision: attempt.Revision, State: attempt.State}
	result, err := Execute(ctx, ExecutePlan{Factory: factory, Dialer: &fakeDialer{session: sess}, DownloadID: dl, Generation: attempt.Generation, BufferBytes: 4 << 10, CheckpointBytes: 4 << 10, File: stage, Committer: checkpoint.Committer{Repository: repo}, Lifecycle: lifecycle})
	if err != nil {
		t.Fatal(err)
	}
	if result.Committed != n || lifecycle.State != "transport_complete" {
		t.Fatalf("result=%+v lifecycle=%+v", result, lifecycle)
	}
	blocks, err := repo.ListCheckpointBlocks(ctx, dl, attempt.Generation)
	if err != nil {
		t.Fatal(err)
	}
	if len(blocks) == 0 || blocks[0].State != "committed" {
		t.Fatalf("blocks=%+v", blocks)
	}

	sum := sha256.Sum256(payload)
	expected, err := checksum.NormalizeExpected(checksum.SourceUser, "sha256", hex.EncodeToString(sum[:]))
	if err != nil {
		t.Fatal(err)
	}
	ver, _ := identity.ParseVerificationID("ver_00000000000000000000000000000037")
	pub, _ := identity.ParsePublicationID("pub_00000000000000000000000000000037")
	finalized, err := finalize.Finalize(ctx, finalize.Request{Repository: repo, DownloadID: dl, Generation: attempt.Generation, ExpectedDownloadRevision: download.Revision, VerificationID: ver, PublicationID: pub, PublicationKey: "ftp-37", StagingIdentity: stage.Name(), SizeBytes: n, Reader: stage, Freeze: stage.Freeze, Expected: &expected, NowUnixMS: 10})
	if err != nil {
		t.Fatal(err)
	}
	if !finalized.Verification.Matched || finalized.Artifact.VerificationState != "verified" || finalized.Publication.State == "" {
		t.Fatalf("finalized=%+v", finalized)
	}
	stored, err := repo.GetAttempt(ctx, dl, attempt.Generation)
	if err != nil {
		t.Fatal(err)
	}
	if stored.State != "produced_artifact" {
		t.Fatalf("attempt=%+v", stored)
	}

	_ = domainrequest.CredentialFTPPassword // compile-time assertion that FTP auth lives in canonical intent
}
