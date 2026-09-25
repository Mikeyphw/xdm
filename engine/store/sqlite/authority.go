package sqlite

import (
	"context"
	"errors"
	"fmt"
	"time"

	"github.com/subhra74/xdm/engine/domain/backend"
	"github.com/subhra74/xdm/engine/domain/identity"
)

var (
	ErrStaleAttempt       = errors.New("stale attempt generation")
	ErrOwnershipState     = errors.New("invalid backend ownership state")
	ErrOwnershipNotActive = errors.New("backend ownership is not active")
	ErrCheckpointOverlap  = errors.New("checkpoint block overlaps committed block")
	ErrCheckpointConflict = errors.New("checkpoint block identity conflicts with committed evidence")
)

type AttemptRecord struct {
	DownloadID      identity.DownloadID
	Generation      identity.AttemptGeneration
	BackendKind     string
	State           string
	FailureCategory string
	FailurePayload  string
	Revision        identity.Revision
	CreatedAtUnixMS int64
	UpdatedAtUnixMS int64
}

type OwnershipRecord struct {
	DownloadID      identity.DownloadID
	Generation      identity.AttemptGeneration
	BackendKind     string
	RuntimeIdentity string
	StagingIdentity string
	State           backend.OwnershipState
	Revision        identity.Revision
	CreatedAtUnixMS int64
	UpdatedAtUnixMS int64
}

type BackendTaskRecord struct {
	ID              identity.BackendTaskID
	DownloadID      identity.DownloadID
	Generation      identity.AttemptGeneration
	BackendKind     string
	ExternalTaskID  string
	RuntimeIdentity string
	State           backend.TaskState
	Revision        identity.Revision
	CreatedAtUnixMS int64
	UpdatedAtUnixMS int64
}

type CheckpointBlockRecord struct {
	DownloadID      identity.DownloadID
	Generation      identity.AttemptGeneration
	BlockIndex      int64
	StartByte       int64
	CommittedLength int64
	HashAlgorithm   string
	HashHex         string
	State           string
	Revision        identity.Revision
	CommittedAtMS   int64
}

func rollbackUnlessDone(ctx context.Context, tx *Tx, done *bool) {
	if tx != nil && !*done {
		_ = tx.Rollback(ctx)
	}
}

func (r *Repository) currentDownloadTx(ctx context.Context, tx *Tx, id identity.DownloadID) (DownloadRecord, error) {
	rows, err := tx.Query(ctx, `SELECT download_id, request_id, current_request_revision, current_attempt_generation, current_artifact_generation, state, revision, created_at_unix_ms, updated_at_unix_ms FROM downloads WHERE download_id=?`, id.String())
	if err != nil {
		return DownloadRecord{}, err
	}
	if len(rows) == 0 {
		return DownloadRecord{}, ErrNotFound
	}
	return decodeDownload(rows[0])
}

func assertCurrentGenerationTx(ctx context.Context, tx *Tx, id identity.DownloadID, generation identity.AttemptGeneration) error {
	rows, err := tx.Query(ctx, `SELECT current_attempt_generation FROM downloads WHERE download_id=?`, id.String())
	if err != nil {
		return err
	}
	if len(rows) == 0 {
		return ErrNotFound
	}
	if rows[0][0].Kind == Null || rows[0][0].I64 != generation.Int64() {
		return ErrStaleAttempt
	}
	return nil
}

// ReserveAttemptGeneration atomically advances current execution authority and
// creates the corresponding historical attempt row. Two callers using the same
// download revision cannot both reserve authority.
func (r *Repository) ReserveAttemptGeneration(ctx context.Context, id identity.DownloadID, expectedDownloadRevision identity.Revision, backendKind string, nowMS int64) (AttemptRecord, DownloadRecord, error) {
	if r == nil || r.db == nil {
		return AttemptRecord{}, DownloadRecord{}, ErrClosed
	}
	if id.IsZero() || !expectedDownloadRevision.Valid() || backendKind == "" {
		return AttemptRecord{}, DownloadRecord{}, fmt.Errorf("invalid attempt reservation")
	}
	if nowMS <= 0 {
		nowMS = time.Now().UnixMilli()
	}
	tx, err := r.db.BeginImmediate(ctx)
	if err != nil {
		return AttemptRecord{}, DownloadRecord{}, err
	}
	done := false
	defer rollbackUnlessDone(ctx, tx, &done)
	download, err := r.currentDownloadTx(ctx, tx, id)
	if err != nil {
		return AttemptRecord{}, DownloadRecord{}, err
	}
	if download.Revision != expectedDownloadRevision {
		return AttemptRecord{}, DownloadRecord{}, ErrStaleWrite
	}
	var generation identity.AttemptGeneration
	if download.CurrentAttempt == nil {
		generation, err = identity.NewAttemptGeneration(1)
	} else {
		generation, err = download.CurrentAttempt.Next()
	}
	if err != nil {
		return AttemptRecord{}, DownloadRecord{}, err
	}
	revision, _ := identity.NewRevision(1)
	if _, err = tx.Exec(ctx, `INSERT INTO download_attempts(download_id, attempt_generation, backend_kind, state, revision, created_at_unix_ms, updated_at_unix_ms) VALUES(?,?,?,?,?,?,?)`, id.String(), generation.Int64(), backendKind, "reserved", revision.Int64(), nowMS, nowMS); err != nil {
		return AttemptRecord{}, DownloadRecord{}, err
	}
	nextDownloadRevision, err := expectedDownloadRevision.Next()
	if err != nil {
		return AttemptRecord{}, DownloadRecord{}, err
	}
	changes, err := tx.Exec(ctx, `UPDATE downloads SET current_attempt_generation=?, revision=?, updated_at_unix_ms=? WHERE download_id=? AND revision=?`, generation.Int64(), nextDownloadRevision.Int64(), nowMS, id.String(), expectedDownloadRevision.Int64())
	if err != nil {
		return AttemptRecord{}, DownloadRecord{}, err
	}
	if changes != 1 {
		return AttemptRecord{}, DownloadRecord{}, ErrStaleWrite
	}
	if err = tx.Commit(ctx); err != nil {
		return AttemptRecord{}, DownloadRecord{}, err
	}
	done = true
	download.CurrentAttempt = &generation
	download.Revision = nextDownloadRevision
	download.UpdatedAtUnixMS = nowMS
	return AttemptRecord{DownloadID: id, Generation: generation, BackendKind: backendKind, State: "reserved", Revision: revision, CreatedAtUnixMS: nowMS, UpdatedAtUnixMS: nowMS}, download, nil
}

func (r *Repository) MutateAttempt(ctx context.Context, id identity.DownloadID, generation identity.AttemptGeneration, expected identity.Revision, state, failureCategory, failurePayload string, nowMS int64) (AttemptRecord, error) {
	if r == nil || r.db == nil {
		return AttemptRecord{}, ErrClosed
	}
	if id.IsZero() || !generation.Valid() || !expected.Valid() || state == "" {
		return AttemptRecord{}, fmt.Errorf("invalid attempt mutation")
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
	next, err := expected.Next()
	if err != nil {
		return AttemptRecord{}, err
	}
	changes, err := tx.Exec(ctx, `UPDATE download_attempts SET state=?, failure_category=?, failure_payload_json=?, revision=?, updated_at_unix_ms=? WHERE download_id=? AND attempt_generation=? AND revision=?`, state, nullableText(failureCategory), nullableText(failurePayload), next.Int64(), nowMS, id.String(), generation.Int64(), expected.Int64())
	if err != nil {
		return AttemptRecord{}, err
	}
	if changes != 1 {
		rows, qerr := tx.Query(ctx, `SELECT revision FROM download_attempts WHERE download_id=? AND attempt_generation=?`, id.String(), generation.Int64())
		if qerr != nil {
			return AttemptRecord{}, qerr
		}
		if len(rows) == 0 {
			return AttemptRecord{}, ErrNotFound
		}
		return AttemptRecord{}, ErrStaleWrite
	}
	rows, err := tx.Query(ctx, `SELECT backend_kind,state,failure_category,failure_payload_json,revision,created_at_unix_ms,updated_at_unix_ms FROM download_attempts WHERE download_id=? AND attempt_generation=?`, id.String(), generation.Int64())
	if err != nil {
		return AttemptRecord{}, err
	}
	if err = tx.Commit(ctx); err != nil {
		return AttemptRecord{}, err
	}
	done = true
	return decodeAttemptRecord(id, generation, rows[0])
}

func nullableText(v string) any {
	if v == "" {
		return nil
	}
	return v
}

func decodeAttemptRecord(id identity.DownloadID, generation identity.AttemptGeneration, row Row) (AttemptRecord, error) {
	if len(row) != 7 {
		return AttemptRecord{}, fmt.Errorf("unexpected attempt column count %d", len(row))
	}
	rev, err := identity.NewRevision(row[4].I64)
	if err != nil {
		return AttemptRecord{}, err
	}
	failureCategory, failurePayload := "", ""
	if row[2].Kind != Null {
		failureCategory = row[2].Text
	}
	if row[3].Kind != Null {
		failurePayload = row[3].Text
	}
	return AttemptRecord{DownloadID: id, Generation: generation, BackendKind: row[0].Text, State: row[1].Text, FailureCategory: failureCategory, FailurePayload: failurePayload, Revision: rev, CreatedAtUnixMS: row[5].I64, UpdatedAtUnixMS: row[6].I64}, nil
}

func (r *Repository) CreateOwnershipClaim(ctx context.Context, id identity.DownloadID, generation identity.AttemptGeneration, backendKind, stagingIdentity, runtimeIdentity string, nowMS int64) (OwnershipRecord, error) {
	if r == nil || r.db == nil {
		return OwnershipRecord{}, ErrClosed
	}
	if id.IsZero() || !generation.Valid() || backendKind == "" || stagingIdentity == "" {
		return OwnershipRecord{}, fmt.Errorf("invalid ownership claim")
	}
	if nowMS <= 0 {
		nowMS = time.Now().UnixMilli()
	}
	tx, err := r.db.BeginImmediate(ctx)
	if err != nil {
		return OwnershipRecord{}, err
	}
	done := false
	defer rollbackUnlessDone(ctx, tx, &done)
	if err = assertCurrentGenerationTx(ctx, tx, id, generation); err != nil {
		return OwnershipRecord{}, err
	}
	rows, err := tx.Query(ctx, `SELECT backend_kind FROM download_attempts WHERE download_id=? AND attempt_generation=?`, id.String(), generation.Int64())
	if err != nil {
		return OwnershipRecord{}, err
	}
	if len(rows) == 0 {
		return OwnershipRecord{}, ErrNotFound
	}
	if rows[0][0].Text != backendKind {
		return OwnershipRecord{}, fmt.Errorf("ownership backend %q does not match attempt backend %q", backendKind, rows[0][0].Text)
	}
	rev, _ := identity.NewRevision(1)
	_, err = tx.Exec(ctx, `INSERT INTO backend_ownership(download_id,attempt_generation,backend_kind,runtime_identity,staging_identity,ownership_state,revision,created_at_unix_ms,updated_at_unix_ms) VALUES(?,?,?,?,?,?,?,?,?)`, id.String(), generation.Int64(), backendKind, nullableText(runtimeIdentity), stagingIdentity, string(backend.OwnershipClaimed), rev.Int64(), nowMS, nowMS)
	if err != nil {
		return OwnershipRecord{}, err
	}
	if err = tx.Commit(ctx); err != nil {
		return OwnershipRecord{}, err
	}
	done = true
	return OwnershipRecord{DownloadID: id, Generation: generation, BackendKind: backendKind, RuntimeIdentity: runtimeIdentity, StagingIdentity: stagingIdentity, State: backend.OwnershipClaimed, Revision: rev, CreatedAtUnixMS: nowMS, UpdatedAtUnixMS: nowMS}, nil
}

func (r *Repository) BindBackendTask(ctx context.Context, id identity.DownloadID, generation identity.AttemptGeneration, expected identity.Revision, taskID identity.BackendTaskID, externalTaskID, runtimeIdentity string, nowMS int64) (OwnershipRecord, BackendTaskRecord, error) {
	if r == nil || r.db == nil {
		return OwnershipRecord{}, BackendTaskRecord{}, ErrClosed
	}
	if id.IsZero() || !generation.Valid() || !expected.Valid() || taskID.IsZero() {
		return OwnershipRecord{}, BackendTaskRecord{}, fmt.Errorf("invalid backend task binding")
	}
	if nowMS <= 0 {
		nowMS = time.Now().UnixMilli()
	}
	tx, err := r.db.BeginImmediate(ctx)
	if err != nil {
		return OwnershipRecord{}, BackendTaskRecord{}, err
	}
	done := false
	defer rollbackUnlessDone(ctx, tx, &done)
	if err = assertCurrentGenerationTx(ctx, tx, id, generation); err != nil {
		return OwnershipRecord{}, BackendTaskRecord{}, err
	}
	own, err := loadOwnershipTx(ctx, tx, id, generation)
	if err != nil {
		return OwnershipRecord{}, BackendTaskRecord{}, err
	}
	if own.Revision != expected {
		return OwnershipRecord{}, BackendTaskRecord{}, ErrStaleWrite
	}
	if err = backend.ValidateOwnershipTransition(own.State, backend.OwnershipTaskBound); err != nil {
		return OwnershipRecord{}, BackendTaskRecord{}, ErrOwnershipState
	}
	taskRev, _ := identity.NewRevision(1)
	_, err = tx.Exec(ctx, `INSERT INTO backend_tasks(backend_task_id,download_id,attempt_generation,backend_kind,external_task_id,runtime_identity,task_state,revision,created_at_unix_ms,updated_at_unix_ms) VALUES(?,?,?,?,?,?,?,?,?,?)`, taskID.String(), id.String(), generation.Int64(), own.BackendKind, nullableText(externalTaskID), nullableText(runtimeIdentity), string(backend.TaskPrepared), taskRev.Int64(), nowMS, nowMS)
	if err != nil {
		return OwnershipRecord{}, BackendTaskRecord{}, err
	}
	next, _ := expected.Next()
	changes, err := tx.Exec(ctx, `UPDATE backend_ownership SET runtime_identity=?,ownership_state=?,revision=?,updated_at_unix_ms=? WHERE download_id=? AND attempt_generation=? AND revision=?`, nullableText(runtimeIdentity), string(backend.OwnershipTaskBound), next.Int64(), nowMS, id.String(), generation.Int64(), expected.Int64())
	if err != nil {
		return OwnershipRecord{}, BackendTaskRecord{}, err
	}
	if changes != 1 {
		return OwnershipRecord{}, BackendTaskRecord{}, ErrStaleWrite
	}
	if err = tx.Commit(ctx); err != nil {
		return OwnershipRecord{}, BackendTaskRecord{}, err
	}
	done = true
	own.RuntimeIdentity = runtimeIdentity
	own.State = backend.OwnershipTaskBound
	own.Revision = next
	own.UpdatedAtUnixMS = nowMS
	return own, BackendTaskRecord{ID: taskID, DownloadID: id, Generation: generation, BackendKind: own.BackendKind, ExternalTaskID: externalTaskID, RuntimeIdentity: runtimeIdentity, State: backend.TaskPrepared, Revision: taskRev, CreatedAtUnixMS: nowMS, UpdatedAtUnixMS: nowMS}, nil
}

func (r *Repository) MarkOwnershipReady(ctx context.Context, id identity.DownloadID, generation identity.AttemptGeneration, expected identity.Revision, nowMS int64) (OwnershipRecord, error) {
	return r.transitionOwnership(ctx, id, generation, expected, backend.OwnershipReady, nowMS)
}

func (r *Repository) transitionOwnership(ctx context.Context, id identity.DownloadID, generation identity.AttemptGeneration, expected identity.Revision, to backend.OwnershipState, nowMS int64) (OwnershipRecord, error) {
	if nowMS <= 0 {
		nowMS = time.Now().UnixMilli()
	}
	tx, err := r.db.BeginImmediate(ctx)
	if err != nil {
		return OwnershipRecord{}, err
	}
	done := false
	defer rollbackUnlessDone(ctx, tx, &done)
	if err = assertCurrentGenerationTx(ctx, tx, id, generation); err != nil {
		return OwnershipRecord{}, err
	}
	own, err := loadOwnershipTx(ctx, tx, id, generation)
	if err != nil {
		return OwnershipRecord{}, err
	}
	if own.Revision != expected {
		return OwnershipRecord{}, ErrStaleWrite
	}
	if err = backend.ValidateOwnershipTransition(own.State, to); err != nil {
		return OwnershipRecord{}, ErrOwnershipState
	}
	next, err := expected.Next()
	if err != nil {
		return OwnershipRecord{}, err
	}
	changes, err := tx.Exec(ctx, `UPDATE backend_ownership SET ownership_state=?,revision=?,updated_at_unix_ms=? WHERE download_id=? AND attempt_generation=? AND revision=?`, string(to), next.Int64(), nowMS, id.String(), generation.Int64(), expected.Int64())
	if err != nil {
		return OwnershipRecord{}, err
	}
	if changes != 1 {
		return OwnershipRecord{}, ErrStaleWrite
	}
	if err = tx.Commit(ctx); err != nil {
		return OwnershipRecord{}, err
	}
	done = true
	own.State = to
	own.Revision = next
	own.UpdatedAtUnixMS = nowMS
	return own, nil
}

func (r *Repository) ActivateOwnership(ctx context.Context, id identity.DownloadID, generation identity.AttemptGeneration, expectedOwnership identity.Revision, nowMS int64) (OwnershipRecord, BackendTaskRecord, error) {
	if nowMS <= 0 {
		nowMS = time.Now().UnixMilli()
	}
	tx, err := r.db.BeginImmediate(ctx)
	if err != nil {
		return OwnershipRecord{}, BackendTaskRecord{}, err
	}
	done := false
	defer rollbackUnlessDone(ctx, tx, &done)
	if err = assertCurrentGenerationTx(ctx, tx, id, generation); err != nil {
		return OwnershipRecord{}, BackendTaskRecord{}, err
	}
	own, err := loadOwnershipTx(ctx, tx, id, generation)
	if err != nil {
		return OwnershipRecord{}, BackendTaskRecord{}, err
	}
	if own.Revision != expectedOwnership {
		return OwnershipRecord{}, BackendTaskRecord{}, ErrStaleWrite
	}
	if err = backend.ValidateOwnershipTransition(own.State, backend.OwnershipActive); err != nil {
		return OwnershipRecord{}, BackendTaskRecord{}, ErrOwnershipState
	}
	task, err := loadAttemptTaskTx(ctx, tx, id, generation)
	if err != nil {
		return OwnershipRecord{}, BackendTaskRecord{}, err
	}
	if task.State != backend.TaskPrepared {
		return OwnershipRecord{}, BackendTaskRecord{}, ErrOwnershipState
	}
	nextOwn, _ := expectedOwnership.Next()
	nextTask, _ := task.Revision.Next()
	if _, err = tx.Exec(ctx, `UPDATE backend_tasks SET task_state=?,revision=?,updated_at_unix_ms=? WHERE backend_task_id=? AND revision=?`, string(backend.TaskActive), nextTask.Int64(), nowMS, task.ID.String(), task.Revision.Int64()); err != nil {
		return OwnershipRecord{}, BackendTaskRecord{}, err
	}
	if _, err = tx.Exec(ctx, `UPDATE backend_ownership SET ownership_state=?,revision=?,updated_at_unix_ms=? WHERE download_id=? AND attempt_generation=? AND revision=?`, string(backend.OwnershipActive), nextOwn.Int64(), nowMS, id.String(), generation.Int64(), expectedOwnership.Int64()); err != nil {
		return OwnershipRecord{}, BackendTaskRecord{}, err
	}
	if err = tx.Commit(ctx); err != nil {
		return OwnershipRecord{}, BackendTaskRecord{}, err
	}
	done = true
	own.State = backend.OwnershipActive
	own.Revision = nextOwn
	own.UpdatedAtUnixMS = nowMS
	task.State = backend.TaskActive
	task.Revision = nextTask
	task.UpdatedAtUnixMS = nowMS
	return own, task, nil
}

func (r *Repository) AssertAuthoritativeWriter(ctx context.Context, id identity.DownloadID, generation identity.AttemptGeneration) error {
	if r == nil || r.db == nil {
		return ErrClosed
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
	own, err := loadOwnershipTx(ctx, tx, id, generation)
	if err != nil {
		return err
	}
	if own.State != backend.OwnershipActive {
		return ErrOwnershipNotActive
	}
	task, err := loadAttemptTaskTx(ctx, tx, id, generation)
	if err != nil {
		return err
	}
	if task.State != backend.TaskActive {
		return ErrOwnershipNotActive
	}
	if err = tx.Commit(ctx); err != nil {
		return err
	}
	done = true
	return nil
}

func loadOwnershipTx(ctx context.Context, tx *Tx, id identity.DownloadID, generation identity.AttemptGeneration) (OwnershipRecord, error) {
	rows, err := tx.Query(ctx, `SELECT backend_kind,runtime_identity,staging_identity,ownership_state,revision,created_at_unix_ms,updated_at_unix_ms FROM backend_ownership WHERE download_id=? AND attempt_generation=?`, id.String(), generation.Int64())
	if err != nil {
		return OwnershipRecord{}, err
	}
	if len(rows) == 0 {
		return OwnershipRecord{}, ErrNotFound
	}
	rev, err := identity.NewRevision(rows[0][4].I64)
	if err != nil {
		return OwnershipRecord{}, err
	}
	runtimeID := ""
	if rows[0][1].Kind != Null {
		runtimeID = rows[0][1].Text
	}
	return OwnershipRecord{DownloadID: id, Generation: generation, BackendKind: rows[0][0].Text, RuntimeIdentity: runtimeID, StagingIdentity: rows[0][2].Text, State: backend.OwnershipState(rows[0][3].Text), Revision: rev, CreatedAtUnixMS: rows[0][5].I64, UpdatedAtUnixMS: rows[0][6].I64}, nil
}

func loadAttemptTaskTx(ctx context.Context, tx *Tx, id identity.DownloadID, generation identity.AttemptGeneration) (BackendTaskRecord, error) {
	rows, err := tx.Query(ctx, `SELECT backend_task_id,backend_kind,external_task_id,runtime_identity,task_state,revision,created_at_unix_ms,updated_at_unix_ms FROM backend_tasks WHERE download_id=? AND attempt_generation=? ORDER BY created_at_unix_ms DESC, backend_task_id DESC LIMIT 1`, id.String(), generation.Int64())
	if err != nil {
		return BackendTaskRecord{}, err
	}
	if len(rows) == 0 {
		return BackendTaskRecord{}, ErrNotFound
	}
	taskID, err := identity.ParseBackendTaskID(rows[0][0].Text)
	if err != nil {
		return BackendTaskRecord{}, err
	}
	rev, err := identity.NewRevision(rows[0][5].I64)
	if err != nil {
		return BackendTaskRecord{}, err
	}
	external, runtimeID := "", ""
	if rows[0][2].Kind != Null {
		external = rows[0][2].Text
	}
	if rows[0][3].Kind != Null {
		runtimeID = rows[0][3].Text
	}
	return BackendTaskRecord{ID: taskID, DownloadID: id, Generation: generation, BackendKind: rows[0][1].Text, ExternalTaskID: external, RuntimeIdentity: runtimeID, State: backend.TaskState(rows[0][4].Text), Revision: rev, CreatedAtUnixMS: rows[0][6].I64, UpdatedAtUnixMS: rows[0][7].I64}, nil
}

func (r *Repository) CommitCheckpointBlock(ctx context.Context, rec CheckpointBlockRecord) (CheckpointBlockRecord, error) {
	if r == nil || r.db == nil {
		return CheckpointBlockRecord{}, ErrClosed
	}
	if rec.DownloadID.IsZero() || !rec.Generation.Valid() || rec.BlockIndex < 0 || rec.StartByte < 0 || rec.CommittedLength <= 0 || rec.HashAlgorithm == "" || rec.HashHex == "" {
		return CheckpointBlockRecord{}, fmt.Errorf("invalid checkpoint block")
	}
	if rec.CommittedAtMS <= 0 {
		rec.CommittedAtMS = time.Now().UnixMilli()
	}
	tx, err := r.db.BeginImmediate(ctx)
	if err != nil {
		return CheckpointBlockRecord{}, err
	}
	done := false
	defer rollbackUnlessDone(ctx, tx, &done)
	if err = assertCurrentGenerationTx(ctx, tx, rec.DownloadID, rec.Generation); err != nil {
		return CheckpointBlockRecord{}, err
	}
	own, err := loadOwnershipTx(ctx, tx, rec.DownloadID, rec.Generation)
	if err != nil {
		return CheckpointBlockRecord{}, err
	}
	if own.State != backend.OwnershipActive {
		return CheckpointBlockRecord{}, ErrOwnershipNotActive
	}
	task, err := loadAttemptTaskTx(ctx, tx, rec.DownloadID, rec.Generation)
	if err != nil {
		return CheckpointBlockRecord{}, err
	}
	if task.State != backend.TaskActive {
		return CheckpointBlockRecord{}, ErrOwnershipNotActive
	}
	existing, err := tx.Query(ctx, `SELECT start_byte,committed_length,hash_algorithm,hash_hex,state,revision,committed_at_unix_ms FROM checkpoint_blocks WHERE download_id=? AND attempt_generation=? AND block_index=?`, rec.DownloadID.String(), rec.Generation.Int64(), rec.BlockIndex)
	if err != nil {
		return CheckpointBlockRecord{}, err
	}
	if len(existing) > 0 {
		row := existing[0]
		if row[0].I64 == rec.StartByte && row[1].I64 == rec.CommittedLength && row[2].Text == rec.HashAlgorithm && row[3].Text == rec.HashHex && row[4].Text == "committed" {
			rev, _ := identity.NewRevision(row[5].I64)
			rec.State = "committed"
			rec.Revision = rev
			rec.CommittedAtMS = row[6].I64
			if err = tx.Commit(ctx); err != nil {
				return CheckpointBlockRecord{}, err
			}
			done = true
			return rec, nil
		}
		return CheckpointBlockRecord{}, ErrCheckpointConflict
	}
	end := rec.StartByte + rec.CommittedLength
	overlap, err := tx.Query(ctx, `SELECT block_index FROM checkpoint_blocks WHERE download_id=? AND attempt_generation=? AND state='committed' AND start_byte < ? AND (start_byte + committed_length) > ? LIMIT 1`, rec.DownloadID.String(), rec.Generation.Int64(), end, rec.StartByte)
	if err != nil {
		return CheckpointBlockRecord{}, err
	}
	if len(overlap) > 0 {
		return CheckpointBlockRecord{}, ErrCheckpointOverlap
	}
	rev, _ := identity.NewRevision(1)
	_, err = tx.Exec(ctx, `INSERT INTO checkpoint_blocks(download_id,attempt_generation,block_index,start_byte,committed_length,hash_algorithm,hash_hex,state,revision,committed_at_unix_ms) VALUES(?,?,?,?,?,?,?,?,?,?)`, rec.DownloadID.String(), rec.Generation.Int64(), rec.BlockIndex, rec.StartByte, rec.CommittedLength, rec.HashAlgorithm, rec.HashHex, "committed", rev.Int64(), rec.CommittedAtMS)
	if err != nil {
		return CheckpointBlockRecord{}, err
	}
	if err = tx.Commit(ctx); err != nil {
		return CheckpointBlockRecord{}, err
	}
	done = true
	rec.State = "committed"
	rec.Revision = rev
	return rec, nil
}

func (r *Repository) ListCheckpointBlocks(ctx context.Context, id identity.DownloadID, generation identity.AttemptGeneration) ([]CheckpointBlockRecord, error) {
	rows, err := r.db.Query(ctx, `SELECT block_index,start_byte,committed_length,hash_algorithm,hash_hex,state,revision,committed_at_unix_ms FROM checkpoint_blocks WHERE download_id=? AND attempt_generation=? ORDER BY block_index`, id.String(), generation.Int64())
	if err != nil {
		return nil, err
	}
	out := make([]CheckpointBlockRecord, 0, len(rows))
	for _, row := range rows {
		rev, err := identity.NewRevision(row[6].I64)
		if err != nil {
			return nil, err
		}
		out = append(out, CheckpointBlockRecord{DownloadID: id, Generation: generation, BlockIndex: row[0].I64, StartByte: row[1].I64, CommittedLength: row[2].I64, HashAlgorithm: row[3].Text, HashHex: row[4].Text, State: row[5].Text, Revision: rev, CommittedAtMS: row[7].I64})
	}
	return out, nil
}
