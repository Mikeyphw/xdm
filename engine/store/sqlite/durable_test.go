//go:build cgo

package sqlite

import (
	"context"
	"errors"
	"testing"

	"github.com/subhra74/xdm/engine/domain/identity"
	"github.com/subhra74/xdm/engine/domain/publication"
)

func mustVerificationID(t *testing.T, suffix string) identity.VerificationID {
	t.Helper()
	id, err := identity.ParseVerificationID("ver_" + suffix)
	if err != nil {
		t.Fatal(err)
	}
	return id
}

func mustPublicationID(t *testing.T, suffix string) identity.PublicationID {
	t.Helper()
	id, err := identity.ParsePublicationID("pub_" + suffix)
	if err != nil {
		t.Fatal(err)
	}
	return id
}

func reserveForDurable(t *testing.T, repo *Repository, dl identity.DownloadID, expected identity.Revision, now int64) (AttemptRecord, DownloadRecord) {
	t.Helper()
	attempt, download, err := repo.ReserveAttemptGeneration(context.Background(), dl, expected, "native", now)
	if err != nil {
		t.Fatal(err)
	}
	return attempt, download
}

func transportCompleteForDurable(t *testing.T, repo *Repository, attempt AttemptRecord, now int64) AttemptRecord {
	t.Helper()
	ctx := context.Background()
	var err error
	attempt, err = repo.MutateAttempt(ctx, attempt.DownloadID, attempt.Generation, attempt.Revision, "prepared", "", "", now)
	if err != nil {
		t.Fatal(err)
	}
	attempt, err = repo.MutateAttempt(ctx, attempt.DownloadID, attempt.Generation, attempt.Revision, "running", "", "", now+1)
	if err != nil {
		t.Fatal(err)
	}
	attempt, err = repo.MutateAttempt(ctx, attempt.DownloadID, attempt.Generation, attempt.Revision, "transport_complete", "", "", now+2)
	if err != nil {
		t.Fatal(err)
	}
	return attempt
}

func verifyForDurable(t *testing.T, repo *Repository, dl identity.DownloadID, attempt AttemptRecord, download DownloadRecord, suffix string, now int64) (VerificationRecord, ArtifactRecord, DownloadRecord) {
	t.Helper()
	current, getErr := repo.GetAttempt(context.Background(), dl, attempt.Generation)
	if getErr != nil {
		t.Fatal(getErr)
	}
	if current.State != "transport_complete" {
		attempt = transportCompleteForDurable(t, repo, current, now-3)
	} else {
		attempt = current
	}
	verification, artifact, updated, err := repo.CommitVerifiedArtifact(context.Background(), VerificationCommitRequest{
		VerificationID: mustVerificationID(t, suffix), DownloadID: dl, Generation: attempt.Generation,
		ExpectedDownloadRevision: download.Revision, StagingIdentity: "stage-" + suffix, SizeBytes: 12,
		Algorithm: "sha256", ExpectedValue: "aa", ActualValue: "aa", VerifierVersion: "xgo-test-v1", NowUnixMS: now,
	}, nil)
	if err != nil {
		t.Fatal(err)
	}
	return verification, artifact, updated
}

func TestVerificationFailureIsJournaledWithoutArtifact(t *testing.T) {
	db, _ := openTestDB(t)
	repo, dl := seedRepository(t, db)
	attempt, download := reserveForDurable(t, repo, dl, mustRevision(t, 1), 2)
	ctx := context.Background()
	failure, err := repo.RecordVerificationFailure(ctx, VerificationRecord{
		ID: mustVerificationID(t, "00000000000000000000000000000001"), DownloadID: dl, Generation: attempt.Generation,
		Algorithm: "sha256", ExpectedValue: "aa", ActualValue: "bb", VerifierVersion: "v1", CreatedAtUnixMS: 3,
	})
	if err != nil {
		t.Fatal(err)
	}
	if failure.Result != VerificationFailed || failure.ArtifactGeneration != nil {
		t.Fatalf("failure=%+v", failure)
	}
	got, err := repo.GetDownload(ctx, dl)
	if err != nil {
		t.Fatal(err)
	}
	if got.CurrentArtifact != nil || got.Revision != download.Revision {
		t.Fatalf("failed verification mutated download: %+v", got)
	}
	rows, err := repo.ListVerificationRecords(ctx, dl)
	if err != nil {
		t.Fatal(err)
	}
	if len(rows) != 1 || rows[0].Result != VerificationFailed {
		t.Fatalf("records=%+v", rows)
	}
}

func TestVerifiedArtifactCommitIsAtomicAndGenerationOnlyAdvancesOnAcceptance(t *testing.T) {
	db, _ := openTestDB(t)
	repo, dl := seedRepository(t, db)
	attempt, download := reserveForDurable(t, repo, dl, mustRevision(t, 1), 2)
	attempt = transportCompleteForDurable(t, repo, attempt, 3)
	ctx := context.Background()
	boom := errors.New("crash between verification and artifact")
	_, _, _, err := repo.CommitVerifiedArtifact(ctx, VerificationCommitRequest{
		VerificationID: mustVerificationID(t, "00000000000000000000000000000002"), DownloadID: dl, Generation: attempt.Generation,
		ExpectedDownloadRevision: download.Revision, StagingIdentity: "stage-a", SizeBytes: 8,
		Algorithm: "sha256", ExpectedValue: "aa", ActualValue: "aa", VerifierVersion: "v1", NowUnixMS: 3,
	}, func(stage VerificationStage) error {
		if stage == AfterVerificationRecord {
			return boom
		}
		return nil
	})
	if !errors.Is(err, boom) {
		t.Fatalf("fault=%v", err)
	}
	rows, err := repo.ListVerificationRecords(ctx, dl)
	if err != nil {
		t.Fatal(err)
	}
	if len(rows) != 0 {
		t.Fatalf("partial verification escaped rollback: %+v", rows)
	}
	got, _ := repo.GetDownload(ctx, dl)
	if got.CurrentArtifact != nil || got.Revision != download.Revision {
		t.Fatalf("partial artifact mutated download: %+v", got)
	}

	verification, artifact, updated := verifyForDurable(t, repo, dl, attempt, download, "00000000000000000000000000000003", 4)
	if verification.Result != VerificationPassed || artifact.Generation.Int64() != 1 || updated.CurrentArtifact == nil || updated.CurrentArtifact.Int64() != 1 {
		t.Fatalf("verification=%+v artifact=%+v download=%+v", verification, artifact, updated)
	}
	attempt2, download2 := reserveForDurable(t, repo, dl, updated.Revision, 5)
	_, artifact2, updated2 := verifyForDurable(t, repo, dl, attempt2, download2, "00000000000000000000000000000004", 6)
	if artifact2.Generation.Int64() != 2 || updated2.CurrentArtifact == nil || updated2.CurrentArtifact.Int64() != 2 {
		t.Fatalf("new artifact generation not monotonic: %+v %+v", artifact2, updated2)
	}
}

func TestStaleAttemptCannotRecordVerificationOrPublishOldArtifact(t *testing.T) {
	db, _ := openTestDB(t)
	repo, dl := seedRepository(t, db)
	attempt1, d1 := reserveForDurable(t, repo, dl, mustRevision(t, 1), 2)
	_, artifact, d2 := verifyForDurable(t, repo, dl, attempt1, d1, "00000000000000000000000000000005", 3)
	_, d3 := reserveForDurable(t, repo, dl, d2.Revision, 4)
	ctx := context.Background()
	_, err := repo.RecordVerificationFailure(ctx, VerificationRecord{ID: mustVerificationID(t, "00000000000000000000000000000006"), DownloadID: dl, Generation: attempt1.Generation, Algorithm: "sha256", VerifierVersion: "v1", CreatedAtUnixMS: 5})
	if !errors.Is(err, ErrStaleAttempt) {
		t.Fatalf("stale verification=%v", err)
	}
	_, _, err = repo.PreparePublication(ctx, PreparePublicationRequest{ID: mustPublicationID(t, "00000000000000000000000000000001"), DownloadID: dl, ArtifactGeneration: artifact.Generation, ExpectedDownloadRevision: d3.Revision, IdempotencyKey: "publish-old", NowUnixMS: 6})
	if !errors.Is(err, ErrStaleAttempt) {
		t.Fatalf("stale artifact publication=%v", err)
	}
}

func TestPublicationSagaReceiptIdempotencyAndEngineCommit(t *testing.T) {
	db, _ := openTestDB(t)
	repo, dl := seedRepository(t, db)
	attempt, d1 := reserveForDurable(t, repo, dl, mustRevision(t, 1), 2)
	_, artifact, d2 := verifyForDurable(t, repo, dl, attempt, d1, "00000000000000000000000000000007", 3)
	ctx := context.Background()
	pubID := mustPublicationID(t, "00000000000000000000000000000002")
	prepared, changedArtifact, err := repo.PreparePublication(ctx, PreparePublicationRequest{ID: pubID, DownloadID: dl, ArtifactGeneration: artifact.Generation, ExpectedDownloadRevision: d2.Revision, IdempotencyKey: "publish-2", NowUnixMS: 4})
	if err != nil {
		t.Fatal(err)
	}
	if prepared.State != publication.Prepared || changedArtifact.PublicationState != "publishing" {
		t.Fatalf("prepared=%+v artifact=%+v", prepared, changedArtifact)
	}
	requested, err := repo.MarkPublicationRequested(ctx, pubID, prepared.Revision, 5)
	if err != nil {
		t.Fatal(err)
	}
	committed, err := repo.RecordPlatformCommit(ctx, pubID, requested.Revision, "receipt-2", "content://out/2", 6)
	if err != nil {
		t.Fatal(err)
	}
	duplicate, err := repo.RecordPlatformCommit(ctx, pubID, requested.Revision, "receipt-2", "content://out/2", 7)
	if err != nil || duplicate.Revision != committed.Revision {
		t.Fatalf("duplicate reply not idempotent: %+v %v", duplicate, err)
	}
	if _, err := repo.RecordPlatformCommit(ctx, pubID, committed.Revision, "other-receipt", "content://other", 8); !errors.Is(err, ErrPublicationConflict) {
		t.Fatalf("conflict=%v", err)
	}
	engineCommitted, published, completed, err := repo.EngineCommitPublication(ctx, pubID, committed.Revision, d2.Revision, 9)
	if err != nil {
		t.Fatal(err)
	}
	if engineCommitted.State != publication.EngineCommitted || published.PublicationState != "published" || completed.State != "completed" {
		t.Fatalf("pub=%+v artifact=%+v download=%+v", engineCommitted, published, completed)
	}
	again, _, againDownload, err := repo.EngineCommitPublication(ctx, pubID, committed.Revision, d2.Revision, 10)
	if err != nil || again.State != publication.EngineCommitted || againDownload.Revision != completed.Revision {
		t.Fatalf("engine commit not idempotent: %+v %+v %v", again, againDownload, err)
	}
	cleaned, err := repo.CleanPublication(ctx, pubID, engineCommitted.Revision, 11)
	if err != nil {
		t.Fatal(err)
	}
	if cleaned.State != publication.Cleaned {
		t.Fatalf("cleaned=%+v", cleaned)
	}
	cleanedAgain, err := repo.CleanPublication(ctx, pubID, engineCommitted.Revision, 12)
	if err != nil || cleanedAgain.Revision != cleaned.Revision {
		t.Fatalf("cleanup not idempotent: %+v %v", cleanedAgain, err)
	}
}

func TestPublicationIdempotencyKeyCannotBeClaimedByAnotherDownload(t *testing.T) {
	db, _ := openTestDB(t)
	repo, dl1 := seedRepository(t, db)
	ctx := context.Background()
	attempt1, d1 := reserveForDurable(t, repo, dl1, mustRevision(t, 1), 2)
	_, artifact1, d2 := verifyForDurable(t, repo, dl1, attempt1, d1, "00000000000000000000000000000009", 3)
	if _, _, err := repo.PreparePublication(ctx, PreparePublicationRequest{
		ID: mustPublicationID(t, "00000000000000000000000000000004"), DownloadID: dl1,
		ArtifactGeneration: artifact1.Generation, ExpectedDownloadRevision: d2.Revision,
		IdempotencyKey: "shared-output-key", NowUnixMS: 4,
	}); err != nil {
		t.Fatal(err)
	}

	req2 := mustRequestID(t, "req_11111111111111111111111111111111")
	if err := repo.CreateRequest(ctx, RequestRecord{ID: req2, Revision: mustRevision(t, 1), ResourceIdentity: "res:fixture-2", Method: "GET", SafeSpecJSON: `{"resource":"fixture-2"}`, CreatedAtUnixMS: 5}); err != nil {
		t.Fatal(err)
	}
	dl2 := mustDownloadID(t, "dl_11111111111111111111111111111111")
	if err := repo.CreateDownload(ctx, DownloadRecord{ID: dl2, RequestID: req2, CurrentRequestRevision: mustRevision(t, 1), State: "open", Revision: mustRevision(t, 1), CreatedAtUnixMS: 5, UpdatedAtUnixMS: 5}); err != nil {
		t.Fatal(err)
	}
	attempt2, d3 := reserveForDurable(t, repo, dl2, mustRevision(t, 1), 6)
	_, artifact2, d4 := verifyForDurable(t, repo, dl2, attempt2, d3, "11111111111111111111111111111111", 7)
	_, _, err := repo.PreparePublication(ctx, PreparePublicationRequest{
		ID: mustPublicationID(t, "11111111111111111111111111111111"), DownloadID: dl2,
		ArtifactGeneration: artifact2.Generation, ExpectedDownloadRevision: d4.Revision,
		IdempotencyKey: "shared-output-key", NowUnixMS: 8,
	})
	var sqlErr *Error
	if !errors.As(err, &sqlErr) || !sqlErr.Constraint() {
		t.Fatalf("cross-download output collision must be rejected by durable uniqueness, got %v", err)
	}
}

func TestPublicationActionNeverBlindlyRepublishesAmbiguousRequest(t *testing.T) {
	db, _ := openTestDB(t)
	repo, dl := seedRepository(t, db)
	attempt, d1 := reserveForDurable(t, repo, dl, mustRevision(t, 1), 2)
	_, artifact, d2 := verifyForDurable(t, repo, dl, attempt, d1, "00000000000000000000000000000008", 3)
	pubID := mustPublicationID(t, "00000000000000000000000000000003")
	prepared, _, err := repo.PreparePublication(context.Background(), PreparePublicationRequest{ID: pubID, DownloadID: dl, ArtifactGeneration: artifact.Generation, ExpectedDownloadRevision: d2.Revision, IdempotencyKey: "publish-3", NowUnixMS: 4})
	if err != nil {
		t.Fatal(err)
	}
	requested, err := repo.MarkPublicationRequested(context.Background(), pubID, prepared.Revision, 5)
	if err != nil {
		t.Fatal(err)
	}
	action, got, err := repo.PublicationAction(context.Background(), pubID)
	if err != nil {
		t.Fatal(err)
	}
	if action != publication.ActionInspectReceipt || got.State != requested.State {
		t.Fatalf("action=%s record=%+v", action, got)
	}
}
