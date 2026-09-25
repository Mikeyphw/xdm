package sqlite

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/subhra74/xdm/engine/domain/identity"
)

var (
	ErrAttemptDiagnosticState    = errors.New("attempt diagnostic state mismatch")
	ErrAttemptDiagnosticConflict = errors.New("attempt diagnostic conflicts with persisted evidence")
)

type AttemptDiagnosticRecord struct {
	EventID         string
	Subsystem       string
	DownloadID      identity.DownloadID
	Generation      identity.AttemptGeneration
	ExpectedState   string
	EventType       string
	Severity        string
	SafePayload     string
	CreatedAtUnixMS int64
}

// RecordAttemptDiagnostic persists append-only safe evidence tied to the current
// attempt generation. ExpectedState is checked in the same transaction as the
// insert so callers cannot attach a pre-start decision after execution begins.
func (r *Repository) RecordAttemptDiagnostic(ctx context.Context, rec AttemptDiagnosticRecord) error {
	if r == nil || r.db == nil {
		return ErrClosed
	}
	if strings.TrimSpace(rec.EventID) == "" || strings.TrimSpace(rec.Subsystem) == "" || rec.DownloadID.IsZero() || !rec.Generation.Valid() || strings.TrimSpace(rec.ExpectedState) == "" || strings.TrimSpace(rec.EventType) == "" || strings.TrimSpace(rec.SafePayload) == "" {
		return fmt.Errorf("invalid attempt diagnostic")
	}
	if rec.Severity == "" {
		rec.Severity = "info"
	}
	if rec.CreatedAtUnixMS <= 0 {
		rec.CreatedAtUnixMS = time.Now().UnixMilli()
	}
	tx, err := r.db.BeginImmediate(ctx)
	if err != nil {
		return err
	}
	done := false
	defer rollbackUnlessDone(ctx, tx, &done)
	if err = assertCurrentGenerationTx(ctx, tx, rec.DownloadID, rec.Generation); err != nil {
		return err
	}
	rows, err := tx.Query(ctx, `SELECT state FROM download_attempts WHERE download_id=? AND attempt_generation=?`, rec.DownloadID.String(), rec.Generation.Int64())
	if err != nil {
		return err
	}
	if len(rows) == 0 {
		return ErrNotFound
	}
	if rows[0][0].Text != rec.ExpectedState {
		return fmt.Errorf("%w: have=%s want=%s", ErrAttemptDiagnosticState, rows[0][0].Text, rec.ExpectedState)
	}
	existing, err := tx.Query(ctx, `SELECT subsystem,download_id,attempt_generation,severity,event_type,safe_payload_json FROM diagnostic_events WHERE event_id=?`, rec.EventID)
	if err != nil {
		return err
	}
	if len(existing) > 0 {
		row := existing[0]
		if len(row) == 6 && row[0].Text == rec.Subsystem && row[1].Text == rec.DownloadID.String() && row[2].I64 == rec.Generation.Int64() && row[3].Text == rec.Severity && row[4].Text == rec.EventType && row[5].Text == rec.SafePayload {
			if err = tx.Commit(ctx); err != nil {
				return err
			}
			done = true
			return nil
		}
		return ErrAttemptDiagnosticConflict
	}
	if _, err = tx.Exec(ctx, `INSERT INTO diagnostic_events(event_id,subsystem,operation_id,download_id,attempt_generation,severity,event_type,safe_payload_json,created_at_unix_ms) VALUES(?,?,?,?,?,?,?,?,?)`, rec.EventID, rec.Subsystem, nil, rec.DownloadID.String(), rec.Generation.Int64(), rec.Severity, rec.EventType, rec.SafePayload, rec.CreatedAtUnixMS); err != nil {
		return err
	}
	if err = tx.Commit(ctx); err != nil {
		return err
	}
	done = true
	return nil
}

type AttemptDiagnostic struct {
	EventID         string
	Subsystem       string
	EventType       string
	Severity        string
	SafePayload     string
	CreatedAtUnixMS int64
}

func (r *Repository) ListAttemptDiagnostics(ctx context.Context, id identity.DownloadID, generation identity.AttemptGeneration, subsystem string) ([]AttemptDiagnostic, error) {
	if r == nil || r.db == nil {
		return nil, ErrClosed
	}
	if id.IsZero() || !generation.Valid() {
		return nil, fmt.Errorf("invalid attempt diagnostic query")
	}
	query := `SELECT event_id,subsystem,event_type,severity,safe_payload_json,created_at_unix_ms FROM diagnostic_events WHERE download_id=? AND attempt_generation=?`
	args := []any{id.String(), generation.Int64()}
	if strings.TrimSpace(subsystem) != "" {
		query += ` AND subsystem=?`
		args = append(args, subsystem)
	}
	query += ` ORDER BY created_at_unix_ms,event_id`
	rows, err := r.db.Query(ctx, query, args...)
	if err != nil {
		return nil, err
	}
	out := make([]AttemptDiagnostic, 0, len(rows))
	for _, row := range rows {
		if len(row) != 6 {
			return nil, fmt.Errorf("unexpected diagnostic column count %d", len(row))
		}
		out = append(out, AttemptDiagnostic{EventID: row[0].Text, Subsystem: row[1].Text, EventType: row[2].Text, Severity: row[3].Text, SafePayload: row[4].Text, CreatedAtUnixMS: row[5].I64})
	}
	return out, nil
}
