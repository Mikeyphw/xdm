package sqlite

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/subhra74/xdm/engine/domain/backend"
	"github.com/subhra74/xdm/engine/domain/identity"
)

var (
	ErrRuntimeIdentityMismatch = errors.New("backend runtime identity mismatch")
	ErrBackendTaskMismatch     = errors.New("backend task identity mismatch")
)

// BackendBinding is the durable authority tuple used when reconciling an
// external backend. The attempt generation is part of the identity: a GID is
// never authoritative by itself.
type BackendBinding struct {
	Attempt   AttemptRecord
	Ownership OwnershipRecord
	Task      BackendTaskRecord
}

func (r *Repository) GetOwnership(ctx context.Context, id identity.DownloadID, generation identity.AttemptGeneration) (OwnershipRecord, error) {
	if r == nil || r.db == nil {
		return OwnershipRecord{}, ErrClosed
	}
	tx, err := r.db.BeginImmediate(ctx)
	if err != nil {
		return OwnershipRecord{}, err
	}
	done := false
	defer rollbackUnlessDone(ctx, tx, &done)
	rec, err := loadOwnershipTx(ctx, tx, id, generation)
	if err != nil {
		return OwnershipRecord{}, err
	}
	if err = tx.Commit(ctx); err != nil {
		return OwnershipRecord{}, err
	}
	done = true
	return rec, nil
}

func (r *Repository) GetAttemptTask(ctx context.Context, id identity.DownloadID, generation identity.AttemptGeneration) (BackendTaskRecord, error) {
	if r == nil || r.db == nil {
		return BackendTaskRecord{}, ErrClosed
	}
	tx, err := r.db.BeginImmediate(ctx)
	if err != nil {
		return BackendTaskRecord{}, err
	}
	done := false
	defer rollbackUnlessDone(ctx, tx, &done)
	rec, err := loadAttemptTaskTx(ctx, tx, id, generation)
	if err != nil {
		return BackendTaskRecord{}, err
	}
	if err = tx.Commit(ctx); err != nil {
		return BackendTaskRecord{}, err
	}
	done = true
	return rec, nil
}

func (r *Repository) GetBackendBinding(ctx context.Context, id identity.DownloadID, generation identity.AttemptGeneration) (BackendBinding, error) {
	attempt, err := r.GetAttempt(ctx, id, generation)
	if err != nil {
		return BackendBinding{}, err
	}
	own, err := r.GetOwnership(ctx, id, generation)
	if err != nil {
		return BackendBinding{}, err
	}
	task, err := r.GetAttemptTask(ctx, id, generation)
	if err != nil {
		return BackendBinding{}, err
	}
	return BackendBinding{Attempt: attempt, Ownership: own, Task: task}, nil
}

// AssertBackendTaskWriter is the external-backend equivalent of
// AssertAuthoritativeWriter. It additionally fences daemon restarts and GID
// namespace reuse by requiring the exact persisted runtime and external task id.
func (r *Repository) AssertBackendTaskWriter(ctx context.Context, id identity.DownloadID, generation identity.AttemptGeneration, externalTaskID, runtimeIdentity string) error {
	if r == nil || r.db == nil {
		return ErrClosed
	}
	externalTaskID, runtimeIdentity = strings.TrimSpace(externalTaskID), strings.TrimSpace(runtimeIdentity)
	if externalTaskID == "" || runtimeIdentity == "" {
		return fmt.Errorf("invalid backend writer identity")
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
	task, err := loadAttemptTaskTx(ctx, tx, id, generation)
	if err != nil {
		return err
	}
	if own.State != backend.OwnershipActive || task.State != backend.TaskActive {
		return ErrOwnershipNotActive
	}
	if own.RuntimeIdentity != runtimeIdentity || task.RuntimeIdentity != runtimeIdentity {
		return ErrRuntimeIdentityMismatch
	}
	if task.ExternalTaskID != externalTaskID {
		return ErrBackendTaskMismatch
	}
	if err = tx.Commit(ctx); err != nil {
		return err
	}
	done = true
	return nil
}

// AbandonCurrentOwnership revokes a current external owner after reconciliation
// proves that the daemon/task identity is gone or no longer trustworthy.
func (r *Repository) AbandonCurrentOwnership(ctx context.Context, id identity.DownloadID, generation identity.AttemptGeneration, nowMS int64) (OwnershipRecord, BackendTaskRecord, error) {
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
	task, err := loadAttemptTaskTx(ctx, tx, id, generation)
	if err != nil {
		return OwnershipRecord{}, BackendTaskRecord{}, err
	}
	if own.State == backend.OwnershipAbandoned {
		if err = tx.Commit(ctx); err != nil {
			return OwnershipRecord{}, BackendTaskRecord{}, err
		}
		done = true
		return own, task, nil
	}
	if own.State.Terminal() {
		return OwnershipRecord{}, BackendTaskRecord{}, ErrOwnershipState
	}
	if err = backend.ValidateOwnershipTransition(own.State, backend.OwnershipAbandoned); err != nil {
		return OwnershipRecord{}, BackendTaskRecord{}, ErrOwnershipState
	}
	nextOwn, _ := own.Revision.Next()
	if _, err = tx.Exec(ctx, `UPDATE backend_ownership SET ownership_state=?,revision=?,updated_at_unix_ms=? WHERE download_id=? AND attempt_generation=? AND revision=?`, string(backend.OwnershipAbandoned), nextOwn.Int64(), nowMS, id.String(), generation.Int64(), own.Revision.Int64()); err != nil {
		return OwnershipRecord{}, BackendTaskRecord{}, err
	}
	if task.State != backend.TaskRetired {
		nextTask, _ := task.Revision.Next()
		if _, err = tx.Exec(ctx, `UPDATE backend_tasks SET task_state=?,revision=?,updated_at_unix_ms=? WHERE backend_task_id=? AND revision=?`, string(backend.TaskRetired), nextTask.Int64(), nowMS, task.ID.String(), task.Revision.Int64()); err != nil {
			return OwnershipRecord{}, BackendTaskRecord{}, err
		}
		task.State, task.Revision, task.UpdatedAtUnixMS = backend.TaskRetired, nextTask, nowMS
	}
	if err = tx.Commit(ctx); err != nil {
		return OwnershipRecord{}, BackendTaskRecord{}, err
	}
	done = true
	own.State, own.Revision, own.UpdatedAtUnixMS = backend.OwnershipAbandoned, nextOwn, nowMS
	return own, task, nil
}

// RetireSupersededOwnership records historical retirement only after a newer
// generation is already current. This preserves the generation fence even if
// the source backend reports a late completion after migration.
func (r *Repository) RetireSupersededOwnership(ctx context.Context, id identity.DownloadID, generation identity.AttemptGeneration, nowMS int64) (OwnershipRecord, BackendTaskRecord, error) {
	if nowMS <= 0 {
		nowMS = time.Now().UnixMilli()
	}
	tx, err := r.db.BeginImmediate(ctx)
	if err != nil {
		return OwnershipRecord{}, BackendTaskRecord{}, err
	}
	done := false
	defer rollbackUnlessDone(ctx, tx, &done)
	current, err := r.currentDownloadTx(ctx, tx, id)
	if err != nil {
		return OwnershipRecord{}, BackendTaskRecord{}, err
	}
	if current.CurrentAttempt == nil || current.CurrentAttempt.Int64() <= generation.Int64() {
		return OwnershipRecord{}, BackendTaskRecord{}, ErrStaleAttempt
	}
	own, err := loadOwnershipTx(ctx, tx, id, generation)
	if err != nil {
		return OwnershipRecord{}, BackendTaskRecord{}, err
	}
	task, err := loadAttemptTaskTx(ctx, tx, id, generation)
	if err != nil {
		return OwnershipRecord{}, BackendTaskRecord{}, err
	}
	if own.State.Terminal() && task.State == backend.TaskRetired {
		if err = tx.Commit(ctx); err != nil {
			return OwnershipRecord{}, BackendTaskRecord{}, err
		}
		done = true
		return own, task, nil
	}
	// A superseded active writer is retired. A superseded pre-activation owner
	// is abandoned instead: it never held canonical write authority, but its
	// external task identity still has to become terminal. This is required for
	// crash recovery after binding/ready but before activation.
	targetState := backend.OwnershipRetired
	if own.State != backend.OwnershipActive {
		switch own.State {
		case backend.OwnershipClaimed, backend.OwnershipTaskBound, backend.OwnershipReady:
			targetState = backend.OwnershipAbandoned
		default:
			return OwnershipRecord{}, BackendTaskRecord{}, ErrOwnershipState
		}
	}
	nextOwn, _ := own.Revision.Next()
	nextTask, _ := task.Revision.Next()
	if _, err = tx.Exec(ctx, `UPDATE backend_ownership SET ownership_state=?,revision=?,updated_at_unix_ms=? WHERE download_id=? AND attempt_generation=? AND revision=?`, string(targetState), nextOwn.Int64(), nowMS, id.String(), generation.Int64(), own.Revision.Int64()); err != nil {
		return OwnershipRecord{}, BackendTaskRecord{}, err
	}
	if _, err = tx.Exec(ctx, `UPDATE backend_tasks SET task_state=?,revision=?,updated_at_unix_ms=? WHERE backend_task_id=? AND revision=?`, string(backend.TaskRetired), nextTask.Int64(), nowMS, task.ID.String(), task.Revision.Int64()); err != nil {
		return OwnershipRecord{}, BackendTaskRecord{}, err
	}
	if err = tx.Commit(ctx); err != nil {
		return OwnershipRecord{}, BackendTaskRecord{}, err
	}
	done = true
	own.State, own.Revision, own.UpdatedAtUnixMS = targetState, nextOwn, nowMS
	task.State, task.Revision, task.UpdatedAtUnixMS = backend.TaskRetired, nextTask, nowMS
	return own, task, nil
}

// ListCurrentBackendBindings returns only the generation that currently owns
// each download. Historical rows are intentionally excluded.
func (r *Repository) ListCurrentBackendBindings(ctx context.Context, backendKind string) ([]BackendBinding, error) {
	if r == nil || r.db == nil {
		return nil, ErrClosed
	}
	backendKind = strings.TrimSpace(backendKind)
	if backendKind == "" {
		return nil, fmt.Errorf("empty backend kind")
	}
	rows, err := r.db.Query(ctx, `SELECT d.download_id,d.current_attempt_generation FROM downloads d JOIN download_attempts a ON a.download_id=d.download_id AND a.attempt_generation=d.current_attempt_generation WHERE a.backend_kind=? AND d.current_attempt_generation IS NOT NULL ORDER BY d.download_id`, backendKind)
	if err != nil {
		return nil, err
	}
	out := make([]BackendBinding, 0, len(rows))
	for _, row := range rows {
		id, err := identity.ParseDownloadID(row[0].Text)
		if err != nil {
			return nil, err
		}
		gen, err := identity.NewAttemptGeneration(row[1].I64)
		if err != nil {
			return nil, err
		}
		b, err := r.GetBackendBinding(ctx, id, gen)
		if err != nil {
			if errors.Is(err, ErrNotFound) {
				continue
			}
			return nil, err
		}
		out = append(out, b)
	}
	return out, nil
}

// RebindBackendTaskGeneration atomically transfers a daemon-restored external
// task to a fresh attempt generation. It is intentionally a single SQLite
// transaction: reconciliation must never leave the download pointing at a new
// generation whose runtime/GID ownership row has not yet been persisted.
func (r *Repository) RebindBackendTaskGeneration(
	ctx context.Context,
	id identity.DownloadID,
	sourceGeneration identity.AttemptGeneration,
	expectedDownloadRevision identity.Revision,
	newTaskID identity.BackendTaskID,
	externalTaskID, runtimeIdentity, stagingIdentity, attemptState string,
	activate bool,
	nowMS int64,
) (BackendBinding, BackendBinding, error) {
	if r == nil || r.db == nil {
		return BackendBinding{}, BackendBinding{}, ErrClosed
	}
	externalTaskID = strings.TrimSpace(externalTaskID)
	runtimeIdentity = strings.TrimSpace(runtimeIdentity)
	stagingIdentity = strings.TrimSpace(stagingIdentity)
	attemptState = strings.TrimSpace(attemptState)
	if id.IsZero() || !sourceGeneration.Valid() || !expectedDownloadRevision.Valid() || newTaskID.IsZero() || externalTaskID == "" || runtimeIdentity == "" || stagingIdentity == "" || attemptState == "" {
		return BackendBinding{}, BackendBinding{}, fmt.Errorf("invalid backend task generation rebind")
	}
	if nowMS <= 0 {
		nowMS = time.Now().UnixMilli()
	}
	tx, err := r.db.BeginImmediate(ctx)
	if err != nil {
		return BackendBinding{}, BackendBinding{}, err
	}
	done := false
	defer rollbackUnlessDone(ctx, tx, &done)
	download, err := r.currentDownloadTx(ctx, tx, id)
	if err != nil {
		return BackendBinding{}, BackendBinding{}, err
	}
	if download.Revision != expectedDownloadRevision || download.CurrentAttempt == nil || *download.CurrentAttempt != sourceGeneration {
		return BackendBinding{}, BackendBinding{}, ErrStaleWrite
	}
	sourceAttemptRows, err := tx.Query(ctx, `SELECT backend_kind,state,failure_category,failure_payload_json,revision,created_at_unix_ms,updated_at_unix_ms FROM download_attempts WHERE download_id=? AND attempt_generation=?`, id.String(), sourceGeneration.Int64())
	if err != nil {
		return BackendBinding{}, BackendBinding{}, err
	}
	if len(sourceAttemptRows) == 0 {
		return BackendBinding{}, BackendBinding{}, ErrNotFound
	}
	sourceAttempt, err := decodeAttemptRecord(id, sourceGeneration, sourceAttemptRows[0])
	if err != nil {
		return BackendBinding{}, BackendBinding{}, err
	}
	sourceOwn, err := loadOwnershipTx(ctx, tx, id, sourceGeneration)
	if err != nil {
		return BackendBinding{}, BackendBinding{}, err
	}
	sourceTask, err := loadAttemptTaskTx(ctx, tx, id, sourceGeneration)
	if err != nil {
		return BackendBinding{}, BackendBinding{}, err
	}
	if sourceOwn.BackendKind != sourceAttempt.BackendKind || sourceTask.BackendKind != sourceAttempt.BackendKind || sourceTask.ExternalTaskID != externalTaskID || sourceOwn.StagingIdentity != stagingIdentity {
		return BackendBinding{}, BackendBinding{}, ErrBackendTaskMismatch
	}
	if sourceOwn.State.Terminal() || sourceTask.State == backend.TaskRetired {
		return BackendBinding{}, BackendBinding{}, ErrOwnershipState
	}

	targetGeneration, err := sourceGeneration.Next()
	if err != nil {
		return BackendBinding{}, BackendBinding{}, err
	}
	one, _ := identity.NewRevision(1)
	if _, err = tx.Exec(ctx, `INSERT INTO download_attempts(download_id,attempt_generation,backend_kind,state,revision,created_at_unix_ms,updated_at_unix_ms) VALUES(?,?,?,?,?,?,?)`, id.String(), targetGeneration.Int64(), sourceAttempt.BackendKind, attemptState, one.Int64(), nowMS, nowMS); err != nil {
		return BackendBinding{}, BackendBinding{}, err
	}
	targetOwnState := backend.OwnershipReady
	targetTaskState := backend.TaskPrepared
	if activate {
		targetOwnState = backend.OwnershipActive
		targetTaskState = backend.TaskActive
	}
	if _, err = tx.Exec(ctx, `INSERT INTO backend_ownership(download_id,attempt_generation,backend_kind,runtime_identity,staging_identity,ownership_state,revision,created_at_unix_ms,updated_at_unix_ms) VALUES(?,?,?,?,?,?,?,?,?)`, id.String(), targetGeneration.Int64(), sourceAttempt.BackendKind, runtimeIdentity, stagingIdentity, string(targetOwnState), one.Int64(), nowMS, nowMS); err != nil {
		return BackendBinding{}, BackendBinding{}, err
	}
	if _, err = tx.Exec(ctx, `INSERT INTO backend_tasks(backend_task_id,download_id,attempt_generation,backend_kind,external_task_id,runtime_identity,task_state,revision,created_at_unix_ms,updated_at_unix_ms) VALUES(?,?,?,?,?,?,?,?,?,?)`, newTaskID.String(), id.String(), targetGeneration.Int64(), sourceAttempt.BackendKind, externalTaskID, runtimeIdentity, string(targetTaskState), one.Int64(), nowMS, nowMS); err != nil {
		return BackendBinding{}, BackendBinding{}, err
	}
	nextDownloadRevision, err := download.Revision.Next()
	if err != nil {
		return BackendBinding{}, BackendBinding{}, err
	}
	changes, err := tx.Exec(ctx, `UPDATE downloads SET current_attempt_generation=?,revision=?,updated_at_unix_ms=? WHERE download_id=? AND revision=? AND current_attempt_generation=?`, targetGeneration.Int64(), nextDownloadRevision.Int64(), nowMS, id.String(), download.Revision.Int64(), sourceGeneration.Int64())
	if err != nil {
		return BackendBinding{}, BackendBinding{}, err
	}
	if changes != 1 {
		return BackendBinding{}, BackendBinding{}, ErrStaleWrite
	}

	sourceTerminal := backend.OwnershipRetired
	if sourceOwn.State != backend.OwnershipActive {
		sourceTerminal = backend.OwnershipAbandoned
	}
	nextSourceOwn, _ := sourceOwn.Revision.Next()
	nextSourceTask, _ := sourceTask.Revision.Next()
	sourceOwnChanges, err := tx.Exec(ctx, `UPDATE backend_ownership SET ownership_state=?,revision=?,updated_at_unix_ms=? WHERE download_id=? AND attempt_generation=? AND revision=?`, string(sourceTerminal), nextSourceOwn.Int64(), nowMS, id.String(), sourceGeneration.Int64(), sourceOwn.Revision.Int64())
	if err != nil {
		return BackendBinding{}, BackendBinding{}, err
	}
	if sourceOwnChanges != 1 {
		return BackendBinding{}, BackendBinding{}, ErrStaleWrite
	}
	sourceTaskChanges, err := tx.Exec(ctx, `UPDATE backend_tasks SET task_state=?,revision=?,updated_at_unix_ms=? WHERE backend_task_id=? AND revision=?`, string(backend.TaskRetired), nextSourceTask.Int64(), nowMS, sourceTask.ID.String(), sourceTask.Revision.Int64())
	if err != nil {
		return BackendBinding{}, BackendBinding{}, err
	}
	if sourceTaskChanges != 1 {
		return BackendBinding{}, BackendBinding{}, ErrStaleWrite
	}
	if err = tx.Commit(ctx); err != nil {
		return BackendBinding{}, BackendBinding{}, err
	}
	done = true

	sourceOwn.State, sourceOwn.Revision, sourceOwn.UpdatedAtUnixMS = sourceTerminal, nextSourceOwn, nowMS
	sourceTask.State, sourceTask.Revision, sourceTask.UpdatedAtUnixMS = backend.TaskRetired, nextSourceTask, nowMS
	targetAttempt := AttemptRecord{DownloadID: id, Generation: targetGeneration, BackendKind: sourceAttempt.BackendKind, State: attemptState, Revision: one, CreatedAtUnixMS: nowMS, UpdatedAtUnixMS: nowMS}
	targetOwn := OwnershipRecord{DownloadID: id, Generation: targetGeneration, BackendKind: sourceAttempt.BackendKind, RuntimeIdentity: runtimeIdentity, StagingIdentity: stagingIdentity, State: targetOwnState, Revision: one, CreatedAtUnixMS: nowMS, UpdatedAtUnixMS: nowMS}
	targetTask := BackendTaskRecord{ID: newTaskID, DownloadID: id, Generation: targetGeneration, BackendKind: sourceAttempt.BackendKind, ExternalTaskID: externalTaskID, RuntimeIdentity: runtimeIdentity, State: targetTaskState, Revision: one, CreatedAtUnixMS: nowMS, UpdatedAtUnixMS: nowMS}
	return BackendBinding{Attempt: sourceAttempt, Ownership: sourceOwn, Task: sourceTask}, BackendBinding{Attempt: targetAttempt, Ownership: targetOwn, Task: targetTask}, nil
}
