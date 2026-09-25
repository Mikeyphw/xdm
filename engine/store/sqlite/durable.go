package sqlite

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/subhra74/xdm/engine/domain/backend"
	"github.com/subhra74/xdm/engine/domain/identity"
	"github.com/subhra74/xdm/engine/domain/publication"
)

var (
	ErrVerificationResult  = errors.New("invalid verification result")
	ErrArtifactNotVerified = errors.New("artifact is not verified")
	ErrArtifactNotCurrent  = errors.New("artifact is not current")
	ErrPublicationState    = errors.New("invalid publication state")
	ErrPublicationConflict = errors.New("publication receipt conflicts with durable state")
)

const (
	VerificationPassed = "passed"
	VerificationFailed = "failed"
)

type VerificationRecord struct {
	ID                 identity.VerificationID
	DownloadID         identity.DownloadID
	Generation         identity.AttemptGeneration
	ArtifactGeneration *identity.ArtifactGeneration
	Algorithm          string
	ExpectedValue      string
	ActualValue        string
	Result             string
	VerifierVersion    string
	CreatedAtUnixMS    int64
}

type ArtifactRecord struct {
	DownloadID        identity.DownloadID
	Generation        identity.ArtifactGeneration
	SourceAttempt     identity.AttemptGeneration
	StagingIdentity   string
	SizeBytes         int64
	VerificationState string
	PublicationState  string
	Revision          identity.Revision
	CreatedAtUnixMS   int64
	UpdatedAtUnixMS   int64
}

type VerificationCommitRequest struct {
	VerificationID           identity.VerificationID
	DownloadID               identity.DownloadID
	Generation               identity.AttemptGeneration
	ExpectedDownloadRevision identity.Revision
	StagingIdentity          string
	SizeBytes                int64
	Algorithm                string
	ExpectedValue            string
	ActualValue              string
	VerifierVersion          string
	NowUnixMS                int64
}

type VerificationStage string

const (
	AfterVerificationRecord  VerificationStage = "after_verification_record"
	AfterArtifactRecord      VerificationStage = "after_artifact_record"
	BeforeVerificationCommit VerificationStage = "before_verification_commit"
	AfterVerificationCommit  VerificationStage = "after_verification_commit"
)

type VerificationFaultHook func(VerificationStage) error

func (r *Repository) RecordVerificationFailure(ctx context.Context, rec VerificationRecord) (VerificationRecord, error) {
	if r == nil || r.db == nil {
		return VerificationRecord{}, ErrClosed
	}
	if rec.ID.IsZero() || rec.DownloadID.IsZero() || !rec.Generation.Valid() || strings.TrimSpace(rec.Algorithm) == "" || strings.TrimSpace(rec.VerifierVersion) == "" {
		return VerificationRecord{}, fmt.Errorf("invalid verification failure record")
	}
	if rec.CreatedAtUnixMS <= 0 {
		rec.CreatedAtUnixMS = time.Now().UnixMilli()
	}
	rec.Result = VerificationFailed
	rec.ArtifactGeneration = nil
	tx, err := r.db.BeginImmediate(ctx)
	if err != nil {
		return VerificationRecord{}, err
	}
	done := false
	defer rollbackUnlessDone(ctx, tx, &done)
	if err = assertCurrentGenerationTx(ctx, tx, rec.DownloadID, rec.Generation); err != nil {
		return VerificationRecord{}, err
	}
	_, err = tx.Exec(ctx, `INSERT INTO verification_records(verification_id,download_id,attempt_generation,artifact_generation,algorithm,expected_value,actual_value,result,verifier_version,created_at_unix_ms) VALUES(?,?,?,?,?,?,?,?,?,?)`, rec.ID.String(), rec.DownloadID.String(), rec.Generation.Int64(), nil, rec.Algorithm, nullableText(rec.ExpectedValue), nullableText(rec.ActualValue), rec.Result, rec.VerifierVersion, rec.CreatedAtUnixMS)
	if err != nil {
		return VerificationRecord{}, err
	}
	if err = tx.Commit(ctx); err != nil {
		return VerificationRecord{}, err
	}
	done = true
	return rec, nil
}

func (r *Repository) CommitVerifiedArtifact(ctx context.Context, req VerificationCommitRequest, hook VerificationFaultHook) (VerificationRecord, ArtifactRecord, DownloadRecord, error) {
	if r == nil || r.db == nil {
		return VerificationRecord{}, ArtifactRecord{}, DownloadRecord{}, ErrClosed
	}
	if req.VerificationID.IsZero() || req.DownloadID.IsZero() || !req.Generation.Valid() || !req.ExpectedDownloadRevision.Valid() || strings.TrimSpace(req.StagingIdentity) == "" || req.SizeBytes < 0 || strings.TrimSpace(req.Algorithm) == "" || strings.TrimSpace(req.VerifierVersion) == "" {
		return VerificationRecord{}, ArtifactRecord{}, DownloadRecord{}, fmt.Errorf("invalid verified artifact request")
	}
	if req.NowUnixMS <= 0 {
		req.NowUnixMS = time.Now().UnixMilli()
	}
	tx, err := r.db.BeginImmediate(ctx)
	if err != nil {
		return VerificationRecord{}, ArtifactRecord{}, DownloadRecord{}, err
	}
	done := false
	defer rollbackUnlessDone(ctx, tx, &done)
	if err = assertCurrentGenerationTx(ctx, tx, req.DownloadID, req.Generation); err != nil {
		return VerificationRecord{}, ArtifactRecord{}, DownloadRecord{}, err
	}
	download, err := r.currentDownloadTx(ctx, tx, req.DownloadID)
	if err != nil {
		return VerificationRecord{}, ArtifactRecord{}, DownloadRecord{}, err
	}
	if download.Revision != req.ExpectedDownloadRevision {
		return VerificationRecord{}, ArtifactRecord{}, DownloadRecord{}, ErrStaleWrite
	}
	var artifactGeneration identity.ArtifactGeneration
	if download.CurrentArtifact == nil {
		artifactGeneration, err = identity.NewArtifactGeneration(1)
	} else {
		artifactGeneration, err = download.CurrentArtifact.Next()
	}
	if err != nil {
		return VerificationRecord{}, ArtifactRecord{}, DownloadRecord{}, err
	}
	verification := VerificationRecord{
		ID: req.VerificationID, DownloadID: req.DownloadID, Generation: req.Generation,
		ArtifactGeneration: &artifactGeneration, Algorithm: req.Algorithm, ExpectedValue: req.ExpectedValue,
		ActualValue: req.ActualValue, Result: VerificationPassed, VerifierVersion: req.VerifierVersion,
		CreatedAtUnixMS: req.NowUnixMS,
	}
	if _, err = tx.Exec(ctx, `INSERT INTO verification_records(verification_id,download_id,attempt_generation,artifact_generation,algorithm,expected_value,actual_value,result,verifier_version,created_at_unix_ms) VALUES(?,?,?,?,?,?,?,?,?,?)`, verification.ID.String(), verification.DownloadID.String(), verification.Generation.Int64(), artifactGeneration.Int64(), verification.Algorithm, nullableText(verification.ExpectedValue), nullableText(verification.ActualValue), verification.Result, verification.VerifierVersion, verification.CreatedAtUnixMS); err != nil {
		return VerificationRecord{}, ArtifactRecord{}, DownloadRecord{}, err
	}
	if hook != nil {
		if err = hook(AfterVerificationRecord); err != nil {
			return VerificationRecord{}, ArtifactRecord{}, DownloadRecord{}, err
		}
	}
	artifact := ArtifactRecord{
		DownloadID: req.DownloadID, Generation: artifactGeneration, SourceAttempt: req.Generation,
		StagingIdentity: req.StagingIdentity, SizeBytes: req.SizeBytes, VerificationState: "verified",
		PublicationState: "unpublished", Revision: mustRevisionOne(), CreatedAtUnixMS: req.NowUnixMS, UpdatedAtUnixMS: req.NowUnixMS,
	}
	if _, err = tx.Exec(ctx, `INSERT INTO artifacts(download_id,artifact_generation,source_attempt_generation,staging_identity,size_bytes,verification_state,publication_state,revision,created_at_unix_ms,updated_at_unix_ms) VALUES(?,?,?,?,?,?,?,?,?,?)`, artifact.DownloadID.String(), artifact.Generation.Int64(), artifact.SourceAttempt.Int64(), artifact.StagingIdentity, artifact.SizeBytes, artifact.VerificationState, artifact.PublicationState, artifact.Revision.Int64(), artifact.CreatedAtUnixMS, artifact.UpdatedAtUnixMS); err != nil {
		return VerificationRecord{}, ArtifactRecord{}, DownloadRecord{}, err
	}
	if hook != nil {
		if err = hook(AfterArtifactRecord); err != nil {
			return VerificationRecord{}, ArtifactRecord{}, DownloadRecord{}, err
		}
	}
	nextDownloadRevision, err := download.Revision.Next()
	if err != nil {
		return VerificationRecord{}, ArtifactRecord{}, DownloadRecord{}, err
	}
	changes, err := tx.Exec(ctx, `UPDATE downloads SET current_artifact_generation=?,revision=?,updated_at_unix_ms=? WHERE download_id=? AND revision=? AND current_attempt_generation=?`, artifactGeneration.Int64(), nextDownloadRevision.Int64(), req.NowUnixMS, req.DownloadID.String(), download.Revision.Int64(), req.Generation.Int64())
	if err != nil {
		return VerificationRecord{}, ArtifactRecord{}, DownloadRecord{}, err
	}
	if changes != 1 {
		return VerificationRecord{}, ArtifactRecord{}, DownloadRecord{}, ErrStaleWrite
	}
	if hook != nil {
		if err = hook(BeforeVerificationCommit); err != nil {
			return VerificationRecord{}, ArtifactRecord{}, DownloadRecord{}, err
		}
	}
	if err = tx.Commit(ctx); err != nil {
		return VerificationRecord{}, ArtifactRecord{}, DownloadRecord{}, err
	}
	done = true
	download.CurrentArtifact = &artifactGeneration
	download.Revision = nextDownloadRevision
	download.UpdatedAtUnixMS = req.NowUnixMS
	if hook != nil {
		if err = hook(AfterVerificationCommit); err != nil {
			return verification, artifact, download, err
		}
	}
	return verification, artifact, download, nil
}

func mustRevisionOne() identity.Revision {
	r, _ := identity.NewRevision(1)
	return r
}

func (r *Repository) GetArtifact(ctx context.Context, downloadID identity.DownloadID, generation identity.ArtifactGeneration) (ArtifactRecord, error) {
	if r == nil || r.db == nil {
		return ArtifactRecord{}, ErrClosed
	}
	rows, err := r.db.Query(ctx, `SELECT download_id,artifact_generation,source_attempt_generation,staging_identity,size_bytes,verification_state,publication_state,revision,created_at_unix_ms,updated_at_unix_ms FROM artifacts WHERE download_id=? AND artifact_generation=?`, downloadID.String(), generation.Int64())
	if err != nil {
		return ArtifactRecord{}, err
	}
	if len(rows) == 0 {
		return ArtifactRecord{}, ErrNotFound
	}
	return decodeArtifact(rows[0])
}

func decodeArtifact(row Row) (ArtifactRecord, error) {
	if len(row) != 10 {
		return ArtifactRecord{}, fmt.Errorf("unexpected artifact column count %d", len(row))
	}
	dl, err := identity.ParseDownloadID(row[0].Text)
	if err != nil {
		return ArtifactRecord{}, err
	}
	gen, err := identity.NewArtifactGeneration(row[1].I64)
	if err != nil {
		return ArtifactRecord{}, err
	}
	attempt, err := identity.NewAttemptGeneration(row[2].I64)
	if err != nil {
		return ArtifactRecord{}, err
	}
	rev, err := identity.NewRevision(row[7].I64)
	if err != nil {
		return ArtifactRecord{}, err
	}
	return ArtifactRecord{DownloadID: dl, Generation: gen, SourceAttempt: attempt, StagingIdentity: row[3].Text, SizeBytes: row[4].I64, VerificationState: row[5].Text, PublicationState: row[6].Text, Revision: rev, CreatedAtUnixMS: row[8].I64, UpdatedAtUnixMS: row[9].I64}, nil
}

func (r *Repository) ListVerificationRecords(ctx context.Context, downloadID identity.DownloadID) ([]VerificationRecord, error) {
	if r == nil || r.db == nil {
		return nil, ErrClosed
	}
	rows, err := r.db.Query(ctx, `SELECT verification_id,download_id,attempt_generation,artifact_generation,algorithm,expected_value,actual_value,result,verifier_version,created_at_unix_ms FROM verification_records WHERE download_id=? ORDER BY created_at_unix_ms,verification_id`, downloadID.String())
	if err != nil {
		return nil, err
	}
	out := make([]VerificationRecord, 0, len(rows))
	for _, row := range rows {
		rec, err := decodeVerification(row)
		if err != nil {
			return nil, err
		}
		out = append(out, rec)
	}
	return out, nil
}

func decodeVerification(row Row) (VerificationRecord, error) {
	if len(row) != 10 {
		return VerificationRecord{}, fmt.Errorf("unexpected verification column count %d", len(row))
	}
	id, err := identity.ParseVerificationID(row[0].Text)
	if err != nil {
		return VerificationRecord{}, err
	}
	dl, err := identity.ParseDownloadID(row[1].Text)
	if err != nil {
		return VerificationRecord{}, err
	}
	attempt, err := identity.NewAttemptGeneration(row[2].I64)
	if err != nil {
		return VerificationRecord{}, err
	}
	var artifact *identity.ArtifactGeneration
	if row[3].Kind != Null {
		v, e := identity.NewArtifactGeneration(row[3].I64)
		if e != nil {
			return VerificationRecord{}, e
		}
		artifact = &v
	}
	return VerificationRecord{ID: id, DownloadID: dl, Generation: attempt, ArtifactGeneration: artifact, Algorithm: row[4].Text, ExpectedValue: textOrEmpty(row[5]), ActualValue: textOrEmpty(row[6]), Result: row[7].Text, VerifierVersion: row[8].Text, CreatedAtUnixMS: row[9].I64}, nil
}

func textOrEmpty(v Value) string {
	if v.Kind == Text {
		return v.Text
	}
	return ""
}

type PublicationRecord struct {
	ID                 identity.PublicationID
	DownloadID         identity.DownloadID
	ArtifactGeneration identity.ArtifactGeneration
	State              publication.State
	IdempotencyKey     string
	PlatformReceipt    string
	PublishedLocation  string
	Revision           identity.Revision
	CreatedAtUnixMS    int64
	UpdatedAtUnixMS    int64
}

type PreparePublicationRequest struct {
	ID                       identity.PublicationID
	DownloadID               identity.DownloadID
	ArtifactGeneration       identity.ArtifactGeneration
	ExpectedDownloadRevision identity.Revision
	IdempotencyKey           string
	NowUnixMS                int64
}

func (r *Repository) PreparePublication(ctx context.Context, req PreparePublicationRequest) (PublicationRecord, ArtifactRecord, error) {
	if r == nil || r.db == nil {
		return PublicationRecord{}, ArtifactRecord{}, ErrClosed
	}
	if req.ID.IsZero() || req.DownloadID.IsZero() || !req.ArtifactGeneration.Valid() || !req.ExpectedDownloadRevision.Valid() || strings.TrimSpace(req.IdempotencyKey) == "" {
		return PublicationRecord{}, ArtifactRecord{}, fmt.Errorf("invalid publication preparation")
	}
	if req.NowUnixMS <= 0 {
		req.NowUnixMS = time.Now().UnixMilli()
	}
	tx, err := r.db.BeginImmediate(ctx)
	if err != nil {
		return PublicationRecord{}, ArtifactRecord{}, err
	}
	done := false
	defer rollbackUnlessDone(ctx, tx, &done)
	download, err := r.currentDownloadTx(ctx, tx, req.DownloadID)
	if err != nil {
		return PublicationRecord{}, ArtifactRecord{}, err
	}
	if download.Revision != req.ExpectedDownloadRevision {
		return PublicationRecord{}, ArtifactRecord{}, ErrStaleWrite
	}
	if download.CurrentArtifact == nil || *download.CurrentArtifact != req.ArtifactGeneration {
		return PublicationRecord{}, ArtifactRecord{}, ErrArtifactNotCurrent
	}
	artifact, err := loadArtifactTx(ctx, tx, req.DownloadID, req.ArtifactGeneration)
	if err != nil {
		return PublicationRecord{}, ArtifactRecord{}, err
	}
	if artifact.VerificationState != "verified" {
		return PublicationRecord{}, ArtifactRecord{}, ErrArtifactNotVerified
	}
	if download.CurrentAttempt == nil || artifact.SourceAttempt != *download.CurrentAttempt {
		return PublicationRecord{}, ArtifactRecord{}, ErrStaleAttempt
	}
	if artifact.PublicationState != "unpublished" {
		return PublicationRecord{}, ArtifactRecord{}, ErrPublicationState
	}
	nextArtifactRevision, err := artifact.Revision.Next()
	if err != nil {
		return PublicationRecord{}, ArtifactRecord{}, err
	}
	changes, err := tx.Exec(ctx, `UPDATE artifacts SET publication_state='publishing',revision=?,updated_at_unix_ms=? WHERE download_id=? AND artifact_generation=? AND revision=? AND publication_state='unpublished'`, nextArtifactRevision.Int64(), req.NowUnixMS, req.DownloadID.String(), req.ArtifactGeneration.Int64(), artifact.Revision.Int64())
	if err != nil {
		return PublicationRecord{}, ArtifactRecord{}, err
	}
	if changes != 1 {
		return PublicationRecord{}, ArtifactRecord{}, ErrStaleWrite
	}
	record := PublicationRecord{ID: req.ID, DownloadID: req.DownloadID, ArtifactGeneration: req.ArtifactGeneration, State: publication.Prepared, IdempotencyKey: req.IdempotencyKey, Revision: mustRevisionOne(), CreatedAtUnixMS: req.NowUnixMS, UpdatedAtUnixMS: req.NowUnixMS}
	_, err = tx.Exec(ctx, `INSERT INTO publication_transactions(publication_id,download_id,artifact_generation,state,idempotency_key,platform_receipt,published_location,revision,created_at_unix_ms,updated_at_unix_ms) VALUES(?,?,?,?,?,?,?,?,?,?)`, record.ID.String(), record.DownloadID.String(), record.ArtifactGeneration.Int64(), string(record.State), record.IdempotencyKey, nil, nil, record.Revision.Int64(), record.CreatedAtUnixMS, record.UpdatedAtUnixMS)
	if err != nil {
		return PublicationRecord{}, ArtifactRecord{}, err
	}
	if err = tx.Commit(ctx); err != nil {
		return PublicationRecord{}, ArtifactRecord{}, err
	}
	done = true
	artifact.PublicationState = "publishing"
	artifact.Revision = nextArtifactRevision
	artifact.UpdatedAtUnixMS = req.NowUnixMS
	return record, artifact, nil
}

func loadArtifactTx(ctx context.Context, tx *Tx, downloadID identity.DownloadID, generation identity.ArtifactGeneration) (ArtifactRecord, error) {
	rows, err := tx.Query(ctx, `SELECT download_id,artifact_generation,source_attempt_generation,staging_identity,size_bytes,verification_state,publication_state,revision,created_at_unix_ms,updated_at_unix_ms FROM artifacts WHERE download_id=? AND artifact_generation=?`, downloadID.String(), generation.Int64())
	if err != nil {
		return ArtifactRecord{}, err
	}
	if len(rows) == 0 {
		return ArtifactRecord{}, ErrNotFound
	}
	return decodeArtifact(rows[0])
}

func (r *Repository) GetPublication(ctx context.Context, id identity.PublicationID) (PublicationRecord, error) {
	if r == nil || r.db == nil {
		return PublicationRecord{}, ErrClosed
	}
	rows, err := r.db.Query(ctx, `SELECT publication_id,download_id,artifact_generation,state,idempotency_key,platform_receipt,published_location,revision,created_at_unix_ms,updated_at_unix_ms FROM publication_transactions WHERE publication_id=?`, id.String())
	if err != nil {
		return PublicationRecord{}, err
	}
	if len(rows) == 0 {
		return PublicationRecord{}, ErrNotFound
	}
	return decodePublication(rows[0])
}

func (r *Repository) GetPublicationForArtifact(ctx context.Context, downloadID identity.DownloadID, generation identity.ArtifactGeneration) (PublicationRecord, error) {
	if r == nil || r.db == nil {
		return PublicationRecord{}, ErrClosed
	}
	rows, err := r.db.Query(ctx, `SELECT publication_id,download_id,artifact_generation,state,idempotency_key,platform_receipt,published_location,revision,created_at_unix_ms,updated_at_unix_ms FROM publication_transactions WHERE download_id=? AND artifact_generation=? ORDER BY created_at_unix_ms DESC LIMIT 1`, downloadID.String(), generation.Int64())
	if err != nil {
		return PublicationRecord{}, err
	}
	if len(rows) == 0 {
		return PublicationRecord{}, ErrNotFound
	}
	return decodePublication(rows[0])
}

func decodePublication(row Row) (PublicationRecord, error) {
	if len(row) != 10 {
		return PublicationRecord{}, fmt.Errorf("unexpected publication column count %d", len(row))
	}
	id, err := identity.ParsePublicationID(row[0].Text)
	if err != nil {
		return PublicationRecord{}, err
	}
	dl, err := identity.ParseDownloadID(row[1].Text)
	if err != nil {
		return PublicationRecord{}, err
	}
	art, err := identity.NewArtifactGeneration(row[2].I64)
	if err != nil {
		return PublicationRecord{}, err
	}
	state := publication.State(row[3].Text)
	if !state.Valid() {
		return PublicationRecord{}, ErrPublicationState
	}
	rev, err := identity.NewRevision(row[7].I64)
	if err != nil {
		return PublicationRecord{}, err
	}
	return PublicationRecord{ID: id, DownloadID: dl, ArtifactGeneration: art, State: state, IdempotencyKey: row[4].Text, PlatformReceipt: textOrEmpty(row[5]), PublishedLocation: textOrEmpty(row[6]), Revision: rev, CreatedAtUnixMS: row[8].I64, UpdatedAtUnixMS: row[9].I64}, nil
}

func (r *Repository) markPublicationState(ctx context.Context, id identity.PublicationID, expected identity.Revision, from, to publication.State, nowMS int64) (PublicationRecord, error) {
	if r == nil || r.db == nil {
		return PublicationRecord{}, ErrClosed
	}
	if id.IsZero() || !expected.Valid() || !from.Valid() || !to.Valid() || publication.ValidateTransition(from, to) != nil {
		return PublicationRecord{}, ErrPublicationState
	}
	if nowMS <= 0 {
		nowMS = time.Now().UnixMilli()
	}
	next, err := expected.Next()
	if err != nil {
		return PublicationRecord{}, err
	}
	changes, err := r.db.Exec(ctx, `UPDATE publication_transactions SET state=?,revision=?,updated_at_unix_ms=? WHERE publication_id=? AND revision=? AND state=?`, string(to), next.Int64(), nowMS, id.String(), expected.Int64(), string(from))
	if err != nil {
		return PublicationRecord{}, err
	}
	if changes != 1 {
		current, lookupErr := r.GetPublication(ctx, id)
		if lookupErr != nil {
			return PublicationRecord{}, lookupErr
		}
		if current.Revision != expected {
			return PublicationRecord{}, ErrStaleWrite
		}
		return PublicationRecord{}, ErrPublicationState
	}
	return r.GetPublication(ctx, id)
}

func (r *Repository) MarkPublicationRequested(ctx context.Context, id identity.PublicationID, expected identity.Revision, nowMS int64) (PublicationRecord, error) {
	return r.markPublicationState(ctx, id, expected, publication.Prepared, publication.PlatformCommitRequested, nowMS)
}

func (r *Repository) RecordPlatformCommit(ctx context.Context, id identity.PublicationID, expected identity.Revision, receipt, location string, nowMS int64) (PublicationRecord, error) {
	if r == nil || r.db == nil {
		return PublicationRecord{}, ErrClosed
	}
	if id.IsZero() || !expected.Valid() || strings.TrimSpace(receipt) == "" || strings.TrimSpace(location) == "" {
		return PublicationRecord{}, fmt.Errorf("invalid platform commit")
	}
	if nowMS <= 0 {
		nowMS = time.Now().UnixMilli()
	}
	tx, err := r.db.BeginImmediate(ctx)
	if err != nil {
		return PublicationRecord{}, err
	}
	done := false
	defer rollbackUnlessDone(ctx, tx, &done)
	current, err := loadPublicationTx(ctx, tx, id)
	if err != nil {
		return PublicationRecord{}, err
	}
	if current.State == publication.PlatformCommitted || current.State == publication.EngineCommitted || current.State == publication.Cleaned {
		if current.PlatformReceipt == receipt && current.PublishedLocation == location {
			if err = tx.Commit(ctx); err != nil {
				return PublicationRecord{}, err
			}
			done = true
			return current, nil
		}
		return PublicationRecord{}, ErrPublicationConflict
	}
	if current.Revision != expected {
		return PublicationRecord{}, ErrStaleWrite
	}
	if current.State != publication.PlatformCommitRequested {
		return PublicationRecord{}, ErrPublicationState
	}
	next, err := current.Revision.Next()
	if err != nil {
		return PublicationRecord{}, err
	}
	changes, err := tx.Exec(ctx, `UPDATE publication_transactions SET state=?,platform_receipt=?,published_location=?,revision=?,updated_at_unix_ms=? WHERE publication_id=? AND revision=? AND state=?`, string(publication.PlatformCommitted), receipt, location, next.Int64(), nowMS, id.String(), current.Revision.Int64(), string(publication.PlatformCommitRequested))
	if err != nil {
		return PublicationRecord{}, err
	}
	if changes != 1 {
		return PublicationRecord{}, ErrStaleWrite
	}
	if err = tx.Commit(ctx); err != nil {
		return PublicationRecord{}, err
	}
	done = true
	current.State = publication.PlatformCommitted
	current.PlatformReceipt = receipt
	current.PublishedLocation = location
	current.Revision = next
	current.UpdatedAtUnixMS = nowMS
	return current, nil
}

func loadPublicationTx(ctx context.Context, tx *Tx, id identity.PublicationID) (PublicationRecord, error) {
	rows, err := tx.Query(ctx, `SELECT publication_id,download_id,artifact_generation,state,idempotency_key,platform_receipt,published_location,revision,created_at_unix_ms,updated_at_unix_ms FROM publication_transactions WHERE publication_id=?`, id.String())
	if err != nil {
		return PublicationRecord{}, err
	}
	if len(rows) == 0 {
		return PublicationRecord{}, ErrNotFound
	}
	return decodePublication(rows[0])
}

func (r *Repository) EngineCommitPublication(ctx context.Context, id identity.PublicationID, expectedPublicationRevision, expectedDownloadRevision identity.Revision, nowMS int64) (PublicationRecord, ArtifactRecord, DownloadRecord, error) {
	if r == nil || r.db == nil {
		return PublicationRecord{}, ArtifactRecord{}, DownloadRecord{}, ErrClosed
	}
	if id.IsZero() || !expectedPublicationRevision.Valid() || !expectedDownloadRevision.Valid() {
		return PublicationRecord{}, ArtifactRecord{}, DownloadRecord{}, fmt.Errorf("invalid engine publication commit")
	}
	if nowMS <= 0 {
		nowMS = time.Now().UnixMilli()
	}
	tx, err := r.db.BeginImmediate(ctx)
	if err != nil {
		return PublicationRecord{}, ArtifactRecord{}, DownloadRecord{}, err
	}
	done := false
	defer rollbackUnlessDone(ctx, tx, &done)
	pub, err := loadPublicationTx(ctx, tx, id)
	if err != nil {
		return PublicationRecord{}, ArtifactRecord{}, DownloadRecord{}, err
	}
	if pub.State == publication.EngineCommitted || pub.State == publication.Cleaned {
		artifact, aerr := loadArtifactTx(ctx, tx, pub.DownloadID, pub.ArtifactGeneration)
		if aerr != nil {
			return PublicationRecord{}, ArtifactRecord{}, DownloadRecord{}, aerr
		}
		download, derr := r.currentDownloadTx(ctx, tx, pub.DownloadID)
		if derr != nil {
			return PublicationRecord{}, ArtifactRecord{}, DownloadRecord{}, derr
		}
		if err = tx.Commit(ctx); err != nil {
			return PublicationRecord{}, ArtifactRecord{}, DownloadRecord{}, err
		}
		done = true
		return pub, artifact, download, nil
	}
	if pub.Revision != expectedPublicationRevision {
		return PublicationRecord{}, ArtifactRecord{}, DownloadRecord{}, ErrStaleWrite
	}
	if pub.State != publication.PlatformCommitted || pub.PlatformReceipt == "" || pub.PublishedLocation == "" {
		return PublicationRecord{}, ArtifactRecord{}, DownloadRecord{}, ErrPublicationState
	}
	download, err := r.currentDownloadTx(ctx, tx, pub.DownloadID)
	if err != nil {
		return PublicationRecord{}, ArtifactRecord{}, DownloadRecord{}, err
	}
	if download.Revision != expectedDownloadRevision {
		return PublicationRecord{}, ArtifactRecord{}, DownloadRecord{}, ErrStaleWrite
	}
	if download.CurrentArtifact == nil || *download.CurrentArtifact != pub.ArtifactGeneration {
		return PublicationRecord{}, ArtifactRecord{}, DownloadRecord{}, ErrArtifactNotCurrent
	}
	artifact, err := loadArtifactTx(ctx, tx, pub.DownloadID, pub.ArtifactGeneration)
	if err != nil {
		return PublicationRecord{}, ArtifactRecord{}, DownloadRecord{}, err
	}
	if artifact.VerificationState != "verified" {
		return PublicationRecord{}, ArtifactRecord{}, DownloadRecord{}, ErrArtifactNotVerified
	}
	if download.CurrentAttempt == nil || artifact.SourceAttempt != *download.CurrentAttempt {
		return PublicationRecord{}, ArtifactRecord{}, DownloadRecord{}, ErrStaleAttempt
	}
	nextPubRev, err := pub.Revision.Next()
	if err != nil {
		return PublicationRecord{}, ArtifactRecord{}, DownloadRecord{}, err
	}
	nextArtifactRev, err := artifact.Revision.Next()
	if err != nil {
		return PublicationRecord{}, ArtifactRecord{}, DownloadRecord{}, err
	}
	nextDownloadRev, err := download.Revision.Next()
	if err != nil {
		return PublicationRecord{}, ArtifactRecord{}, DownloadRecord{}, err
	}
	changes, err := tx.Exec(ctx, `UPDATE publication_transactions SET state=?,revision=?,updated_at_unix_ms=? WHERE publication_id=? AND revision=? AND state=?`, string(publication.EngineCommitted), nextPubRev.Int64(), nowMS, pub.ID.String(), pub.Revision.Int64(), string(publication.PlatformCommitted))
	if err != nil {
		return PublicationRecord{}, ArtifactRecord{}, DownloadRecord{}, err
	}
	if changes != 1 {
		return PublicationRecord{}, ArtifactRecord{}, DownloadRecord{}, ErrStaleWrite
	}
	changes, err = tx.Exec(ctx, `UPDATE artifacts SET publication_state='published',revision=?,updated_at_unix_ms=? WHERE download_id=? AND artifact_generation=? AND revision=? AND verification_state='verified'`, nextArtifactRev.Int64(), nowMS, artifact.DownloadID.String(), artifact.Generation.Int64(), artifact.Revision.Int64())
	if err != nil {
		return PublicationRecord{}, ArtifactRecord{}, DownloadRecord{}, err
	}
	if changes != 1 {
		return PublicationRecord{}, ArtifactRecord{}, DownloadRecord{}, ErrStaleWrite
	}
	changes, err = tx.Exec(ctx, `UPDATE downloads SET state='completed',revision=?,updated_at_unix_ms=? WHERE download_id=? AND revision=? AND current_artifact_generation=? AND current_attempt_generation=?`, nextDownloadRev.Int64(), nowMS, download.ID.String(), download.Revision.Int64(), artifact.Generation.Int64(), artifact.SourceAttempt.Int64())
	if err != nil {
		return PublicationRecord{}, ArtifactRecord{}, DownloadRecord{}, err
	}
	if changes != 1 {
		return PublicationRecord{}, ArtifactRecord{}, DownloadRecord{}, ErrStaleWrite
	}
	if err = tx.Commit(ctx); err != nil {
		return PublicationRecord{}, ArtifactRecord{}, DownloadRecord{}, err
	}
	done = true
	pub.State = publication.EngineCommitted
	pub.Revision = nextPubRev
	pub.UpdatedAtUnixMS = nowMS
	artifact.PublicationState = "published"
	artifact.Revision = nextArtifactRev
	artifact.UpdatedAtUnixMS = nowMS
	download.State = "completed"
	download.Revision = nextDownloadRev
	download.UpdatedAtUnixMS = nowMS
	return pub, artifact, download, nil
}

func (r *Repository) CleanPublication(ctx context.Context, id identity.PublicationID, expected identity.Revision, nowMS int64) (PublicationRecord, error) {
	if r == nil || r.db == nil {
		return PublicationRecord{}, ErrClosed
	}
	current, err := r.GetPublication(ctx, id)
	if err != nil {
		return PublicationRecord{}, err
	}
	if current.State == publication.Cleaned {
		return current, nil
	}
	return r.markPublicationState(ctx, id, expected, publication.EngineCommitted, publication.Cleaned, nowMS)
}

func (r *Repository) PublicationCommitRequest(ctx context.Context, id identity.PublicationID) (publication.CommitRequest, error) {
	pub, err := r.GetPublication(ctx, id)
	if err != nil {
		return publication.CommitRequest{}, err
	}
	if pub.State != publication.Prepared && pub.State != publication.PlatformCommitRequested {
		return publication.CommitRequest{}, ErrPublicationState
	}
	artifact, err := r.GetArtifact(ctx, pub.DownloadID, pub.ArtifactGeneration)
	if err != nil {
		return publication.CommitRequest{}, err
	}
	req := publication.CommitRequest{PublicationID: pub.ID, DownloadID: pub.DownloadID, Artifact: pub.ArtifactGeneration, IdempotencyKey: pub.IdempotencyKey, StagingIdentity: artifact.StagingIdentity}
	if err := req.Validate(); err != nil {
		return publication.CommitRequest{}, err
	}
	return req, nil
}

func (r *Repository) PublicationInspectRequest(ctx context.Context, id identity.PublicationID) (publication.InspectRequest, error) {
	pub, err := r.GetPublication(ctx, id)
	if err != nil {
		return publication.InspectRequest{}, err
	}
	if pub.State != publication.PlatformCommitRequested && pub.State != publication.PlatformCommitted {
		return publication.InspectRequest{}, ErrPublicationState
	}
	req := publication.InspectRequest{PublicationID: pub.ID, IdempotencyKey: pub.IdempotencyKey, ReceiptHint: pub.PlatformReceipt}
	if err := req.Validate(); err != nil {
		return publication.InspectRequest{}, err
	}
	return req, nil
}

func (r *Repository) PublicationAction(ctx context.Context, id identity.PublicationID) (publication.ReconcileAction, PublicationRecord, error) {
	rec, err := r.GetPublication(ctx, id)
	if err != nil {
		return publication.ActionNone, PublicationRecord{}, err
	}
	return publication.ActionFor(rec.State), rec, nil
}

type RecoverySnapshot struct {
	Download          DownloadRecord
	Attempt           *AttemptRecord
	Ownership         *OwnershipRecord
	BackendTask       *BackendTaskRecord
	CheckpointCount   int
	Artifact          *ArtifactRecord
	Publication       *PublicationRecord
	HasStaleOwnership bool
}

func (r *Repository) ListDownloads(ctx context.Context) ([]DownloadRecord, error) {
	if r == nil || r.db == nil {
		return nil, ErrClosed
	}
	rows, err := r.db.Query(ctx, `SELECT download_id,request_id,current_request_revision,current_attempt_generation,current_artifact_generation,state,revision,created_at_unix_ms,updated_at_unix_ms FROM downloads ORDER BY download_id`)
	if err != nil {
		return nil, err
	}
	out := make([]DownloadRecord, 0, len(rows))
	for _, row := range rows {
		rec, e := decodeDownload(row)
		if e != nil {
			return nil, e
		}
		out = append(out, rec)
	}
	return out, nil
}

func (r *Repository) RecoverySnapshot(ctx context.Context, id identity.DownloadID) (RecoverySnapshot, error) {
	download, err := r.GetDownload(ctx, id)
	if err != nil {
		return RecoverySnapshot{}, err
	}
	snap := RecoverySnapshot{Download: download}
	if download.CurrentAttempt != nil {
		if attempt, e := r.getAttempt(ctx, id, *download.CurrentAttempt); e == nil {
			snap.Attempt = &attempt
		} else if !errors.Is(e, ErrNotFound) {
			return RecoverySnapshot{}, e
		}
		if own, e := r.getOwnership(ctx, id, *download.CurrentAttempt); e == nil {
			snap.Ownership = &own
		} else if !errors.Is(e, ErrNotFound) {
			return RecoverySnapshot{}, e
		}
		if task, e := r.getBackendTaskForAttempt(ctx, id, *download.CurrentAttempt); e == nil {
			snap.BackendTask = &task
		} else if !errors.Is(e, ErrNotFound) {
			return RecoverySnapshot{}, e
		}
		blocks, e := r.ListCheckpointBlocks(ctx, id, *download.CurrentAttempt)
		if e != nil {
			return RecoverySnapshot{}, e
		}
		snap.CheckpointCount = len(blocks)
	}
	if download.CurrentArtifact != nil {
		if art, e := r.GetArtifact(ctx, id, *download.CurrentArtifact); e == nil {
			snap.Artifact = &art
		} else if !errors.Is(e, ErrNotFound) {
			return RecoverySnapshot{}, e
		}
		if pub, e := r.GetPublicationForArtifact(ctx, id, *download.CurrentArtifact); e == nil {
			snap.Publication = &pub
		} else if !errors.Is(e, ErrNotFound) {
			return RecoverySnapshot{}, e
		}
	}
	if download.CurrentAttempt != nil {
		rows, e := r.db.Query(ctx, `SELECT count(*) FROM backend_ownership WHERE download_id=? AND attempt_generation<>? AND ownership_state NOT IN ('retired','abandoned')`, id.String(), download.CurrentAttempt.Int64())
		if e != nil {
			return RecoverySnapshot{}, e
		}
		snap.HasStaleOwnership = len(rows) == 1 && rows[0][0].I64 > 0
	}
	return snap, nil
}

func (r *Repository) getAttempt(ctx context.Context, id identity.DownloadID, generation identity.AttemptGeneration) (AttemptRecord, error) {
	rows, err := r.db.Query(ctx, `SELECT download_id,attempt_generation,backend_kind,state,failure_category,failure_payload_json,revision,created_at_unix_ms,updated_at_unix_ms FROM download_attempts WHERE download_id=? AND attempt_generation=?`, id.String(), generation.Int64())
	if err != nil {
		return AttemptRecord{}, err
	}
	if len(rows) == 0 {
		return AttemptRecord{}, ErrNotFound
	}
	rev, err := identity.NewRevision(rows[0][6].I64)
	if err != nil {
		return AttemptRecord{}, err
	}
	return AttemptRecord{DownloadID: id, Generation: generation, BackendKind: rows[0][2].Text, State: rows[0][3].Text, FailureCategory: textOrEmpty(rows[0][4]), FailurePayload: textOrEmpty(rows[0][5]), Revision: rev, CreatedAtUnixMS: rows[0][7].I64, UpdatedAtUnixMS: rows[0][8].I64}, nil
}

func (r *Repository) getOwnership(ctx context.Context, id identity.DownloadID, generation identity.AttemptGeneration) (OwnershipRecord, error) {
	rows, err := r.db.Query(ctx, `SELECT download_id,attempt_generation,backend_kind,runtime_identity,staging_identity,ownership_state,revision,created_at_unix_ms,updated_at_unix_ms FROM backend_ownership WHERE download_id=? AND attempt_generation=?`, id.String(), generation.Int64())
	if err != nil {
		return OwnershipRecord{}, err
	}
	if len(rows) == 0 {
		return OwnershipRecord{}, ErrNotFound
	}
	return decodeOwnershipRow(rows[0])
}

func (r *Repository) getBackendTaskForAttempt(ctx context.Context, id identity.DownloadID, generation identity.AttemptGeneration) (BackendTaskRecord, error) {
	rows, err := r.db.Query(ctx, `SELECT backend_task_id,download_id,attempt_generation,backend_kind,external_task_id,runtime_identity,task_state,revision,created_at_unix_ms,updated_at_unix_ms FROM backend_tasks WHERE download_id=? AND attempt_generation=? ORDER BY created_at_unix_ms DESC LIMIT 1`, id.String(), generation.Int64())
	if err != nil {
		return BackendTaskRecord{}, err
	}
	if len(rows) == 0 {
		return BackendTaskRecord{}, ErrNotFound
	}
	return decodeBackendTaskRow(rows[0])
}

func decodeOwnershipRow(row Row) (OwnershipRecord, error) {
	if len(row) != 9 {
		return OwnershipRecord{}, fmt.Errorf("unexpected ownership columns %d", len(row))
	}
	dl, err := identity.ParseDownloadID(row[0].Text)
	if err != nil {
		return OwnershipRecord{}, err
	}
	gen, err := identity.NewAttemptGeneration(row[1].I64)
	if err != nil {
		return OwnershipRecord{}, err
	}
	rev, err := identity.NewRevision(row[6].I64)
	if err != nil {
		return OwnershipRecord{}, err
	}
	return OwnershipRecord{DownloadID: dl, Generation: gen, BackendKind: row[2].Text, RuntimeIdentity: textOrEmpty(row[3]), StagingIdentity: row[4].Text, State: backend.OwnershipState(row[5].Text), Revision: rev, CreatedAtUnixMS: row[7].I64, UpdatedAtUnixMS: row[8].I64}, nil
}

func decodeBackendTaskRow(row Row) (BackendTaskRecord, error) {
	if len(row) != 10 {
		return BackendTaskRecord{}, fmt.Errorf("unexpected backend task columns %d", len(row))
	}
	id, err := identity.ParseBackendTaskID(row[0].Text)
	if err != nil {
		return BackendTaskRecord{}, err
	}
	dl, err := identity.ParseDownloadID(row[1].Text)
	if err != nil {
		return BackendTaskRecord{}, err
	}
	gen, err := identity.NewAttemptGeneration(row[2].I64)
	if err != nil {
		return BackendTaskRecord{}, err
	}
	rev, err := identity.NewRevision(row[7].I64)
	if err != nil {
		return BackendTaskRecord{}, err
	}
	return BackendTaskRecord{ID: id, DownloadID: dl, Generation: gen, BackendKind: row[3].Text, ExternalTaskID: textOrEmpty(row[4]), RuntimeIdentity: textOrEmpty(row[5]), State: backend.TaskState(row[6].Text), Revision: rev, CreatedAtUnixMS: row[8].I64, UpdatedAtUnixMS: row[9].I64}, nil
}

func (r *Repository) RecordRecoveryDiagnostic(ctx context.Context, eventID string, downloadID identity.DownloadID, generation *identity.AttemptGeneration, eventType, safePayload string, nowMS int64) error {
	if r == nil || r.db == nil {
		return ErrClosed
	}
	if strings.TrimSpace(eventID) == "" || downloadID.IsZero() || strings.TrimSpace(eventType) == "" || strings.TrimSpace(safePayload) == "" {
		return fmt.Errorf("invalid recovery diagnostic")
	}
	if nowMS <= 0 {
		nowMS = time.Now().UnixMilli()
	}
	var gen any
	if generation != nil {
		gen = generation.Int64()
	}
	_, err := r.db.Exec(ctx, `INSERT OR IGNORE INTO diagnostic_events(event_id,subsystem,operation_id,download_id,attempt_generation,severity,event_type,safe_payload_json,created_at_unix_ms) VALUES(?,?,?,?,?,?,?,?,?)`, eventID, "recovery", nil, downloadID.String(), gen, "info", eventType, safePayload, nowMS)
	return err
}

func (r *Repository) DiagnosticEventCount(ctx context.Context, eventType string) (int64, error) {
	rows, err := r.db.Query(ctx, `SELECT count(*) FROM diagnostic_events WHERE subsystem='recovery' AND event_type=?`, eventType)
	if err != nil {
		return 0, err
	}
	if len(rows) != 1 {
		return 0, fmt.Errorf("unexpected diagnostic count result")
	}
	return rows[0][0].I64, nil
}
