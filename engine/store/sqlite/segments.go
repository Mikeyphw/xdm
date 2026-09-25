package sqlite

import (
	"context"
	"errors"
	"fmt"
	"time"

	"github.com/subhra74/xdm/engine/domain/identity"
)

var (
	ErrSegmentState    = errors.New("invalid segment state")
	ErrSegmentCoverage = errors.New("segment is not fully backed by committed checkpoint evidence")
)

type SegmentRecord struct {
	DownloadID identity.DownloadID
	Generation identity.AttemptGeneration
	Index      int64
	StartByte  int64
	EndByte    int64
	State      string
	Revision   identity.Revision
}

func validSegmentState(state string) bool {
	switch state {
	case "planned", "running", "completed", "failed":
		return true
	default:
		return false
	}
}

// EnsureSegmentPlan persists the byte partition under the current attempt
// generation. Existing identical rows are idempotent; conflicting rows are
// rejected so a restarted executor cannot reinterpret committed bytes.
func (r *Repository) EnsureSegmentPlan(ctx context.Context, id identity.DownloadID, generation identity.AttemptGeneration, segments []SegmentRecord) ([]SegmentRecord, error) {
	if r == nil || r.db == nil {
		return nil, ErrClosed
	}
	if id.IsZero() || !generation.Valid() || len(segments) == 0 {
		return nil, fmt.Errorf("invalid segment plan")
	}
	tx, err := r.db.BeginImmediate(ctx)
	if err != nil {
		return nil, err
	}
	done := false
	defer rollbackUnlessDone(ctx, tx, &done)
	if err = assertCurrentGenerationTx(ctx, tx, id, generation); err != nil {
		return nil, err
	}
	prevEnd := int64(-1)
	out := make([]SegmentRecord, 0, len(segments))
	for i, seg := range segments {
		if seg.Index != int64(i) || seg.StartByte < 0 || seg.EndByte < seg.StartByte || seg.StartByte != prevEnd+1 {
			return nil, fmt.Errorf("invalid segment plan")
		}
		prevEnd = seg.EndByte
		rows, qerr := tx.Query(ctx, `SELECT start_byte,end_byte,state,revision FROM segments WHERE download_id=? AND attempt_generation=? AND segment_index=?`, id.String(), generation.Int64(), seg.Index)
		if qerr != nil {
			return nil, qerr
		}
		if len(rows) > 0 {
			row := rows[0]
			if row[0].I64 != seg.StartByte || row[1].I64 != seg.EndByte {
				return nil, ErrSegmentState
			}
			rev, _ := identity.NewRevision(row[3].I64)
			out = append(out, SegmentRecord{DownloadID: id, Generation: generation, Index: seg.Index, StartByte: seg.StartByte, EndByte: seg.EndByte, State: row[2].Text, Revision: rev})
			continue
		}
		rev, _ := identity.NewRevision(1)
		if _, qerr = tx.Exec(ctx, `INSERT INTO segments(download_id,attempt_generation,segment_index,start_byte,end_byte,state,revision) VALUES(?,?,?,?,?,?,?)`, id.String(), generation.Int64(), seg.Index, seg.StartByte, seg.EndByte, "planned", rev.Int64()); qerr != nil {
			return nil, qerr
		}
		seg.DownloadID, seg.Generation, seg.State, seg.Revision = id, generation, "planned", rev
		out = append(out, seg)
	}
	if err = tx.Commit(ctx); err != nil {
		return nil, err
	}
	done = true
	return out, nil
}

func (r *Repository) MutateSegment(ctx context.Context, id identity.DownloadID, generation identity.AttemptGeneration, index int64, expected identity.Revision, next string) (SegmentRecord, error) {
	if r == nil || r.db == nil {
		return SegmentRecord{}, ErrClosed
	}
	if id.IsZero() || !generation.Valid() || index < 0 || !expected.Valid() || !validSegmentState(next) {
		return SegmentRecord{}, fmt.Errorf("invalid segment mutation")
	}
	tx, err := r.db.BeginImmediate(ctx)
	if err != nil {
		return SegmentRecord{}, err
	}
	done := false
	defer rollbackUnlessDone(ctx, tx, &done)
	if err = assertCurrentGenerationTx(ctx, tx, id, generation); err != nil {
		return SegmentRecord{}, err
	}
	rows, err := tx.Query(ctx, `SELECT start_byte,end_byte,state,revision FROM segments WHERE download_id=? AND attempt_generation=? AND segment_index=?`, id.String(), generation.Int64(), index)
	if err != nil {
		return SegmentRecord{}, err
	}
	if len(rows) == 0 {
		return SegmentRecord{}, ErrNotFound
	}
	row := rows[0]
	current := row[2].Text
	allowed := (current == "planned" && next == "running") || (current == "running" && (next == "failed" || next == "completed")) || current == next
	if !allowed {
		return SegmentRecord{}, ErrSegmentState
	}
	if row[3].I64 != expected.Int64() {
		return SegmentRecord{}, ErrStaleWrite
	}
	if current == next {
		if err = tx.Commit(ctx); err != nil {
			return SegmentRecord{}, err
		}
		done = true
		return SegmentRecord{DownloadID: id, Generation: generation, Index: index, StartByte: row[0].I64, EndByte: row[1].I64, State: current, Revision: expected}, nil
	}
	nextRev, _ := expected.Next()
	changes, err := tx.Exec(ctx, `UPDATE segments SET state=?,revision=? WHERE download_id=? AND attempt_generation=? AND segment_index=? AND revision=?`, next, nextRev.Int64(), id.String(), generation.Int64(), index, expected.Int64())
	if err != nil {
		return SegmentRecord{}, err
	}
	if changes != 1 {
		return SegmentRecord{}, ErrStaleWrite
	}
	if err = tx.Commit(ctx); err != nil {
		return SegmentRecord{}, err
	}
	done = true
	return SegmentRecord{DownloadID: id, Generation: generation, Index: index, StartByte: row[0].I64, EndByte: row[1].I64, State: next, Revision: nextRev}, nil
}

// CompleteSegment succeeds only when committed checkpoint rows cover the exact
// segment interval without gaps. Worker completion by itself is never evidence.
func (r *Repository) CompleteSegment(ctx context.Context, id identity.DownloadID, generation identity.AttemptGeneration, index int64, expected identity.Revision) (SegmentRecord, error) {
	if r == nil || r.db == nil {
		return SegmentRecord{}, ErrClosed
	}
	tx, err := r.db.BeginImmediate(ctx)
	if err != nil {
		return SegmentRecord{}, err
	}
	done := false
	defer rollbackUnlessDone(ctx, tx, &done)
	if err = assertCurrentGenerationTx(ctx, tx, id, generation); err != nil {
		return SegmentRecord{}, err
	}
	rows, err := tx.Query(ctx, `SELECT start_byte,end_byte,state,revision FROM segments WHERE download_id=? AND attempt_generation=? AND segment_index=?`, id.String(), generation.Int64(), index)
	if err != nil {
		return SegmentRecord{}, err
	}
	if len(rows) == 0 {
		return SegmentRecord{}, ErrNotFound
	}
	row := rows[0]
	if row[2].Text == "completed" {
		rev, _ := identity.NewRevision(row[3].I64)
		if err = tx.Commit(ctx); err != nil {
			return SegmentRecord{}, err
		}
		done = true
		return SegmentRecord{DownloadID: id, Generation: generation, Index: index, StartByte: row[0].I64, EndByte: row[1].I64, State: "completed", Revision: rev}, nil
	}
	if row[2].Text != "running" || row[3].I64 != expected.Int64() {
		if row[3].I64 != expected.Int64() {
			return SegmentRecord{}, ErrStaleWrite
		}
		return SegmentRecord{}, ErrSegmentState
	}
	blocks, err := tx.Query(ctx, `SELECT start_byte,committed_length FROM checkpoint_blocks WHERE download_id=? AND attempt_generation=? AND state='committed' AND start_byte <= ? AND (start_byte+committed_length) > ? ORDER BY start_byte`, id.String(), generation.Int64(), row[1].I64, row[0].I64)
	if err != nil {
		return SegmentRecord{}, err
	}
	cursor := row[0].I64
	for _, b := range blocks {
		if b[0].I64 != cursor {
			return SegmentRecord{}, ErrSegmentCoverage
		}
		cursor += b[1].I64
		if cursor > row[1].I64+1 {
			return SegmentRecord{}, ErrSegmentCoverage
		}
	}
	if cursor != row[1].I64+1 {
		return SegmentRecord{}, ErrSegmentCoverage
	}
	rev, _ := identity.NewRevision(row[3].I64)
	next, _ := rev.Next()
	changes, err := tx.Exec(ctx, `UPDATE segments SET state='completed',revision=? WHERE download_id=? AND attempt_generation=? AND segment_index=? AND revision=?`, next.Int64(), id.String(), generation.Int64(), index, rev.Int64())
	if err != nil {
		return SegmentRecord{}, err
	}
	if changes != 1 {
		return SegmentRecord{}, ErrStaleWrite
	}
	if err = tx.Commit(ctx); err != nil {
		return SegmentRecord{}, err
	}
	done = true
	return SegmentRecord{DownloadID: id, Generation: generation, Index: index, StartByte: row[0].I64, EndByte: row[1].I64, State: "completed", Revision: next}, nil
}

// InvalidateCheckpointBlocks revokes resumability of selected evidence while
// preserving its history. A later re-download may replace an invalid row.
func (r *Repository) InvalidateCheckpointBlocks(ctx context.Context, id identity.DownloadID, generation identity.AttemptGeneration, indices []int64, nowMS int64) error {
	if r == nil || r.db == nil {
		return ErrClosed
	}
	if id.IsZero() || !generation.Valid() {
		return fmt.Errorf("invalid checkpoint invalidation")
	}
	if len(indices) == 0 {
		return nil
	}
	if nowMS <= 0 {
		nowMS = time.Now().UnixMilli()
	}
	tx, err := r.db.BeginImmediate(ctx)
	if err != nil {
		return err
	}
	done := false
	defer rollbackUnlessDone(ctx, tx, &done)
	if err = assertCurrentGenerationTx(ctx, tx, id, generation); err != nil {
		return err
	}
	for _, idx := range indices {
		if idx < 0 {
			return fmt.Errorf("invalid checkpoint index")
		}
		rows, qerr := tx.Query(ctx, `SELECT revision FROM checkpoint_blocks WHERE download_id=? AND attempt_generation=? AND block_index=?`, id.String(), generation.Int64(), idx)
		if qerr != nil {
			return qerr
		}
		if len(rows) == 0 {
			continue
		}
		rev, _ := identity.NewRevision(rows[0][0].I64)
		next, _ := rev.Next()
		if _, qerr = tx.Exec(ctx, `UPDATE checkpoint_blocks SET state='invalid',revision=?,committed_at_unix_ms=? WHERE download_id=? AND attempt_generation=? AND block_index=? AND revision=?`, next.Int64(), nowMS, id.String(), generation.Int64(), idx, rev.Int64()); qerr != nil {
			return qerr
		}
	}
	if err = tx.Commit(ctx); err != nil {
		return err
	}
	done = true
	return nil
}

// StoreAttemptRetryPayload persists canonical retry scheduling metadata on the
// failed attempt row. It uses the attempt revision CAS and current-generation
// fence so restart recovery cannot attach a deadline to stale execution.
func (r *Repository) StoreAttemptRetryPayload(ctx context.Context, id identity.DownloadID, generation identity.AttemptGeneration, expected identity.Revision, category, payload string, nowMS int64) (AttemptRecord, error) {
	if r == nil || r.db == nil {
		return AttemptRecord{}, ErrClosed
	}
	if id.IsZero() || !generation.Valid() || !expected.Valid() || category == "" || payload == "" {
		return AttemptRecord{}, fmt.Errorf("invalid retry payload")
	}
	if nowMS <= 0 {
		nowMS = time.Now().UnixMilli()
	}
	tx, err := r.db.BeginImmediate(ctx)
	if err != nil {
		return AttemptRecord{}, err
	}
	done := false
	defer rollbackUnlessDone(ctx, tx, &done)
	if err = assertCurrentGenerationTx(ctx, tx, id, generation); err != nil {
		return AttemptRecord{}, err
	}
	rows, err := tx.Query(ctx, `SELECT backend_kind,state,revision,created_at_unix_ms FROM download_attempts WHERE download_id=? AND attempt_generation=?`, id.String(), generation.Int64())
	if err != nil {
		return AttemptRecord{}, err
	}
	if len(rows) == 0 {
		return AttemptRecord{}, ErrNotFound
	}
	row := rows[0]
	if row[2].I64 != expected.Int64() {
		return AttemptRecord{}, ErrStaleWrite
	}
	rev, _ := identity.NewRevision(row[2].I64)
	next, _ := rev.Next()
	changes, err := tx.Exec(ctx, `UPDATE download_attempts SET failure_category=?,failure_payload_json=?,revision=?,updated_at_unix_ms=? WHERE download_id=? AND attempt_generation=? AND revision=?`, category, payload, next.Int64(), nowMS, id.String(), generation.Int64(), rev.Int64())
	if err != nil {
		return AttemptRecord{}, err
	}
	if changes != 1 {
		return AttemptRecord{}, ErrStaleWrite
	}
	if err = tx.Commit(ctx); err != nil {
		return AttemptRecord{}, err
	}
	done = true
	return AttemptRecord{DownloadID: id, Generation: generation, BackendKind: row[0].Text, State: row[1].Text, FailureCategory: category, FailurePayload: payload, Revision: next, CreatedAtUnixMS: row[3].I64, UpdatedAtUnixMS: nowMS}, nil
}

func (r *Repository) GetAttempt(ctx context.Context, id identity.DownloadID, generation identity.AttemptGeneration) (AttemptRecord, error) {
	rows, err := r.db.Query(ctx, `SELECT backend_kind,state,failure_category,failure_payload_json,revision,created_at_unix_ms,updated_at_unix_ms FROM download_attempts WHERE download_id=? AND attempt_generation=?`, id.String(), generation.Int64())
	if err != nil {
		return AttemptRecord{}, err
	}
	if len(rows) == 0 {
		return AttemptRecord{}, ErrNotFound
	}
	row := rows[0]
	rev, err := identity.NewRevision(row[4].I64)
	if err != nil {
		return AttemptRecord{}, err
	}
	cat, payload := "", ""
	if row[2].Kind != Null {
		cat = row[2].Text
	}
	if row[3].Kind != Null {
		payload = row[3].Text
	}
	return AttemptRecord{DownloadID: id, Generation: generation, BackendKind: row[0].Text, State: row[1].Text, FailureCategory: cat, FailurePayload: payload, Revision: rev, CreatedAtUnixMS: row[5].I64, UpdatedAtUnixMS: row[6].I64}, nil
}
