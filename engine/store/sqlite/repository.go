package sqlite

import (
	"context"
	"errors"
	"fmt"
	"time"

	"github.com/subhra74/xdm/engine/domain/identity"
)

type Repository struct{ db *DB }

func NewRepository(db *DB) (*Repository, error) {
	if db == nil {
		return nil, ErrClosed
	}
	return &Repository{db: db}, nil
}

type RequestRecord struct {
	ID               identity.RequestID
	Revision         identity.Revision
	ResourceIdentity string
	Method           string
	SafeSpecJSON     string
	CreatedAtUnixMS  int64
}

type DownloadRecord struct {
	ID                     identity.DownloadID
	RequestID              identity.RequestID
	CurrentRequestRevision identity.Revision
	CurrentAttempt         *identity.AttemptGeneration
	CurrentArtifact        *identity.ArtifactGeneration
	State                  string
	Revision               identity.Revision
	CreatedAtUnixMS        int64
	UpdatedAtUnixMS        int64
}

type DownloadPatch struct {
	State           string
	CurrentAttempt  *identity.AttemptGeneration
	CurrentArtifact *identity.ArtifactGeneration
	UpdatedAtUnixMS int64
}

func (r *Repository) CreateRequest(ctx context.Context, rec RequestRecord) error {
	if r == nil || r.db == nil {
		return ErrClosed
	}
	if rec.ID.IsZero() || !rec.Revision.Valid() || rec.ResourceIdentity == "" || rec.Method == "" || rec.SafeSpecJSON == "" {
		return fmt.Errorf("invalid request record")
	}
	if rec.CreatedAtUnixMS <= 0 {
		rec.CreatedAtUnixMS = time.Now().UnixMilli()
	}
	_, err := r.db.Exec(ctx, `INSERT INTO download_requests(request_id, request_revision, resource_identity, method, spec_json, created_at_unix_ms) VALUES(?,?,?,?,?,?)`, rec.ID.String(), rec.Revision.Int64(), rec.ResourceIdentity, rec.Method, rec.SafeSpecJSON, rec.CreatedAtUnixMS)
	return err
}

func (r *Repository) CreateDownload(ctx context.Context, rec DownloadRecord) error {
	if r == nil || r.db == nil {
		return ErrClosed
	}
	if rec.ID.IsZero() || rec.RequestID.IsZero() || !rec.CurrentRequestRevision.Valid() || !rec.Revision.Valid() || rec.State == "" {
		return fmt.Errorf("invalid download record")
	}
	if rec.CreatedAtUnixMS <= 0 {
		rec.CreatedAtUnixMS = time.Now().UnixMilli()
	}
	if rec.UpdatedAtUnixMS <= 0 {
		rec.UpdatedAtUnixMS = rec.CreatedAtUnixMS
	}
	_, err := r.db.Exec(ctx, `INSERT INTO downloads(download_id, request_id, current_request_revision, current_attempt_generation, current_artifact_generation, state, revision, created_at_unix_ms, updated_at_unix_ms) VALUES(?,?,?,?,?,?,?,?,?)`, rec.ID.String(), rec.RequestID.String(), rec.CurrentRequestRevision.Int64(), nullableAttempt(rec.CurrentAttempt), nullableArtifact(rec.CurrentArtifact), rec.State, rec.Revision.Int64(), rec.CreatedAtUnixMS, rec.UpdatedAtUnixMS)
	return err
}

func (r *Repository) GetDownload(ctx context.Context, id identity.DownloadID) (DownloadRecord, error) {
	if r == nil || r.db == nil {
		return DownloadRecord{}, ErrClosed
	}
	rows, err := r.db.Query(ctx, `SELECT download_id, request_id, current_request_revision, current_attempt_generation, current_artifact_generation, state, revision, created_at_unix_ms, updated_at_unix_ms FROM downloads WHERE download_id=?`, id.String())
	if err != nil {
		return DownloadRecord{}, err
	}
	if len(rows) == 0 {
		return DownloadRecord{}, ErrNotFound
	}
	return decodeDownload(rows[0])
}

// UpdateDownload is a compare-and-swap mutation. Callers must provide the exact
// revision they read. A stale revision never triggers an implicit read/retry.
func (r *Repository) UpdateDownload(ctx context.Context, id identity.DownloadID, expected identity.Revision, patch DownloadPatch) (DownloadRecord, error) {
	if r == nil || r.db == nil {
		return DownloadRecord{}, ErrClosed
	}
	if id.IsZero() || !expected.Valid() || patch.State == "" {
		return DownloadRecord{}, fmt.Errorf("invalid CAS download mutation")
	}
	next, err := expected.Next()
	if err != nil {
		return DownloadRecord{}, err
	}
	if patch.UpdatedAtUnixMS <= 0 {
		patch.UpdatedAtUnixMS = time.Now().UnixMilli()
	}
	changes, err := r.db.Exec(ctx, `UPDATE downloads SET current_attempt_generation=?, current_artifact_generation=?, state=?, revision=?, updated_at_unix_ms=? WHERE download_id=? AND revision=?`, nullableAttempt(patch.CurrentAttempt), nullableArtifact(patch.CurrentArtifact), patch.State, next.Int64(), patch.UpdatedAtUnixMS, id.String(), expected.Int64())
	if err != nil {
		return DownloadRecord{}, err
	}
	if changes != 1 {
		if _, lookupErr := r.GetDownload(ctx, id); errors.Is(lookupErr, ErrNotFound) {
			return DownloadRecord{}, ErrNotFound
		}
		return DownloadRecord{}, ErrStaleWrite
	}
	return r.GetDownload(ctx, id)
}

func nullableAttempt(value *identity.AttemptGeneration) any {
	if value == nil {
		return nil
	}
	return value.Int64()
}
func nullableArtifact(value *identity.ArtifactGeneration) any {
	if value == nil {
		return nil
	}
	return value.Int64()
}

func decodeDownload(row Row) (DownloadRecord, error) {
	if len(row) != 9 {
		return DownloadRecord{}, fmt.Errorf("unexpected download column count %d", len(row))
	}
	id, err := identity.ParseDownloadID(row[0].Text)
	if err != nil {
		return DownloadRecord{}, err
	}
	reqID, err := identity.ParseRequestID(row[1].Text)
	if err != nil {
		return DownloadRecord{}, err
	}
	reqRev, err := identity.NewRevision(row[2].I64)
	if err != nil {
		return DownloadRecord{}, err
	}
	var attempt *identity.AttemptGeneration
	if row[3].Kind != Null {
		v, err := identity.NewAttemptGeneration(row[3].I64)
		if err != nil {
			return DownloadRecord{}, err
		}
		attempt = &v
	}
	var artifact *identity.ArtifactGeneration
	if row[4].Kind != Null {
		v, err := identity.NewArtifactGeneration(row[4].I64)
		if err != nil {
			return DownloadRecord{}, err
		}
		artifact = &v
	}
	rev, err := identity.NewRevision(row[6].I64)
	if err != nil {
		return DownloadRecord{}, err
	}
	return DownloadRecord{ID: id, RequestID: reqID, CurrentRequestRevision: reqRev, CurrentAttempt: attempt, CurrentArtifact: artifact, State: row[5].Text, Revision: rev, CreatedAtUnixMS: row[7].I64, UpdatedAtUnixMS: row[8].I64}, nil
}
