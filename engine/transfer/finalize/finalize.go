package finalize

import (
	"context"
	"errors"
	"fmt"
	"io"
	"time"

	"github.com/subhra74/xdm/engine/domain/identity"
	store "github.com/subhra74/xdm/engine/store/sqlite"
	"github.com/subhra74/xdm/engine/transfer/checksum"
)

var (
	ErrInvalidFinalization = errors.New("invalid native transfer finalization")
	ErrAttemptNotReady     = errors.New("attempt is not transport-complete")
)

type Stage string

const (
	AfterVerifiedArtifact Stage = "after_verified_artifact"
	BeforePublication     Stage = "before_publication"
)

type FaultHook func(Stage) error

type Request struct {
	Repository               *store.Repository
	DownloadID               identity.DownloadID
	Generation               identity.AttemptGeneration
	ExpectedDownloadRevision identity.Revision
	VerificationID           identity.VerificationID
	PublicationID            identity.PublicationID
	PublicationKey           string
	StagingIdentity          string
	SizeBytes                int64
	Reader                   io.ReadSeeker
	Freeze                   func() func()
	Expected                 *checksum.Expected
	NowUnixMS                int64
	Hook                     FaultHook
}

type Result struct {
	Verification checksum.Result          `json:"verification"`
	Record       store.VerificationRecord `json:"record"`
	Artifact     store.ArtifactRecord     `json:"artifact"`
	Publication  store.PublicationRecord  `json:"publication"`
}

func Finalize(ctx context.Context, req Request) (Result, error) {
	if req.Repository == nil || req.DownloadID.IsZero() || !req.Generation.Valid() || !req.ExpectedDownloadRevision.Valid() || req.VerificationID.IsZero() || req.PublicationID.IsZero() || req.PublicationKey == "" || req.StagingIdentity == "" || req.SizeBytes < 0 || req.Reader == nil || req.Freeze == nil {
		return Result{}, ErrInvalidFinalization
	}
	attempt, err := req.Repository.GetAttempt(ctx, req.DownloadID, req.Generation)
	if err != nil {
		return Result{}, err
	}
	if attempt.State != "transport_complete" {
		return Result{}, fmt.Errorf("%w: %s", ErrAttemptNotReady, attempt.State)
	}
	thaw := req.Freeze()
	if thaw == nil {
		return Result{}, ErrInvalidFinalization
	}
	defer thaw()
	end, err := req.Reader.Seek(0, io.SeekEnd)
	if err != nil {
		return Result{}, err
	}
	if end != req.SizeBytes {
		return Result{}, fmt.Errorf("%w: size=%d want=%d", ErrInvalidFinalization, end, req.SizeBytes)
	}
	if _, err = req.Reader.Seek(0, io.SeekStart); err != nil {
		return Result{}, err
	}
	verified, verr := checksum.Verify(ctx, req.Reader, req.Expected, 0)
	now := req.NowUnixMS
	if now <= 0 {
		now = time.Now().UnixMilli()
	}
	if verr != nil {
		if errors.Is(verr, context.Canceled) || errors.Is(verr, context.DeadlineExceeded) {
			return Result{Verification: verified}, verr
		}
		rec := store.VerificationRecord{ID: req.VerificationID, DownloadID: req.DownloadID, Generation: req.Generation, Algorithm: string(verified.Algorithm), ExpectedValue: verified.Expected, ActualValue: verified.Actual, VerifierVersion: verified.Version, CreatedAtUnixMS: now}
		stored, recErr := req.Repository.RecordVerificationFailure(context.WithoutCancel(ctx), rec)
		if recErr != nil {
			return Result{Verification: verified}, recErr
		}
		return Result{Verification: verified, Record: stored}, verr
	}
	record, artifact, download, err := req.Repository.CommitVerifiedArtifact(ctx, store.VerificationCommitRequest{
		VerificationID: req.VerificationID, DownloadID: req.DownloadID, Generation: req.Generation,
		ExpectedDownloadRevision: req.ExpectedDownloadRevision, StagingIdentity: req.StagingIdentity,
		SizeBytes: req.SizeBytes, Algorithm: string(verified.Algorithm), ExpectedValue: verified.Expected,
		ActualValue: verified.Actual, VerifierVersion: verified.Version, NowUnixMS: now,
	}, nil)
	if err != nil {
		return Result{Verification: verified}, err
	}
	partial := Result{Verification: verified, Record: record, Artifact: artifact}
	if req.Hook != nil {
		if hookErr := req.Hook(AfterVerifiedArtifact); hookErr != nil {
			return partial, hookErr
		}
		if hookErr := req.Hook(BeforePublication); hookErr != nil {
			return partial, hookErr
		}
	}
	publication, queuedArtifact, err := req.Repository.PreparePublication(ctx, store.PreparePublicationRequest{
		ID: req.PublicationID, DownloadID: req.DownloadID, ArtifactGeneration: artifact.Generation,
		ExpectedDownloadRevision: download.Revision, IdempotencyKey: req.PublicationKey, NowUnixMS: now,
	})
	if err != nil {
		return Result{Verification: verified, Record: record, Artifact: artifact}, err
	}
	return Result{Verification: verified, Record: record, Artifact: queuedArtifact, Publication: publication}, nil
}
