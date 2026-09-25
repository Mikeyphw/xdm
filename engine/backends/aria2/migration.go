package aria2

import (
	"context"
	"errors"
	"fmt"
	"strings"

	"github.com/subhra74/xdm/engine/backends/migration"
	"github.com/subhra74/xdm/engine/domain/backend"
	"github.com/subhra74/xdm/engine/domain/identity"
	"github.com/subhra74/xdm/engine/store/sqlite"
)

// MigrationTarget adapts DurableOwner to the protocol-neutral migration state
// machine. Build must reproduce the canonical request for the new generation;
// EnsurePaused remains responsible for persisting GID/runtime ownership before
// aria2 can become a writer.
type MigrationTarget struct {
	Owner *DurableOwner
	Build func(context.Context, migration.EstablishRequest) (PrepareRequest, error)
}

func (t *MigrationTarget) BackendKind() string { return BackendKind }

func (t *MigrationTarget) Establish(ctx context.Context, req migration.EstablishRequest) error {
	if t == nil || t.Owner == nil || t.Build == nil {
		return fmt.Errorf("aria2 migration target is not configured")
	}
	prepared, err := t.Build(ctx, req)
	if err != nil {
		return err
	}
	prepared.DownloadID = req.DownloadID
	prepared.Generation = req.TargetGeneration
	_, err = t.Owner.EnsurePaused(ctx, prepared)
	return err
}

func (t *MigrationTarget) Activate(ctx context.Context, id identity.DownloadID, generation identity.AttemptGeneration) error {
	if t == nil || t.Owner == nil {
		return fmt.Errorf("aria2 migration target is not configured")
	}
	current, err := t.Owner.Repo.GetBackendBinding(ctx, id, generation)
	if err != nil {
		return err
	}
	_, err = t.Owner.Activate(ctx, bindingFromRecord(current))
	return err
}

// MigrationSource safely quiesces and inspects an aria2-owned source. External
// retirement is idempotent; the migration coordinator separately retires the
// historical SQLite ownership after target activation is durable.
type MigrationSource struct {
	RPC OwnershipRPC
}

func (s MigrationSource) Quiesce(ctx context.Context, b sqlite.BackendBinding) error {
	if s.RPC == nil {
		return fmt.Errorf("aria2 migration source is not configured")
	}
	if b.Task.ExternalTaskID == "" {
		return sqlite.ErrBackendTaskMismatch
	}
	task, err := s.RPC.TellStatus(ctx, b.Task.ExternalTaskID)
	if err != nil {
		if errors.Is(err, ErrRemote) {
			return nil
		}
		return err
	}
	switch task.Status {
	case StatusActive, StatusWaiting:
		return s.RPC.Pause(ctx, b.Task.ExternalTaskID, true)
	case StatusPaused, StatusComplete, StatusRemoved, StatusError:
		return nil
	default:
		return fmt.Errorf("cannot quiesce aria2 task in status %s", task.Status)
	}
}

func (s MigrationSource) Inspect(ctx context.Context, b sqlite.BackendBinding) (migration.ReuseDecision, error) {
	if s.RPC == nil {
		return migration.ReuseDecision{}, fmt.Errorf("aria2 migration source is not configured")
	}
	task, err := s.RPC.TellStatus(ctx, b.Task.ExternalTaskID)
	if err != nil {
		if errors.Is(err, ErrRemote) {
			return migration.ReuseDecision{}, fmt.Errorf("%w: aria2 source task %q missing during migration inspection", migration.ErrUnsafeReuse, b.Task.ExternalTaskID)
		}
		return migration.ReuseDecision{}, err
	}
	return migration.ReuseDecision{
		StagingIdentity: b.Ownership.StagingIdentity,
		BytesPresent:    task.CompletedLength,
		Complete:        task.Status == StatusComplete || (task.TotalLength > 0 && task.CompletedLength >= task.TotalLength),
		// aria2's control-file/checkpoint semantics are not silently portable
		// to the native backend. A target may still choose the same empty file.
		CheckpointReusable: task.CompletedLength == 0,
		Detail:             "aria2 source inspected after quiesce",
	}, nil
}

func (s MigrationSource) Retire(ctx context.Context, b sqlite.BackendBinding) error {
	if s.RPC == nil {
		return fmt.Errorf("aria2 migration source is not configured")
	}
	if strings.TrimSpace(b.Task.ExternalTaskID) == "" {
		return sqlite.ErrBackendTaskMismatch
	}
	task, err := s.RPC.TellStatus(ctx, b.Task.ExternalTaskID)
	if err == nil && task.Status != StatusRemoved && task.Status != StatusComplete {
		if err = s.RPC.Remove(ctx, b.Task.ExternalTaskID, true); err != nil && !errors.Is(err, ErrRemote) {
			return err
		}
	} else if err != nil && !errors.Is(err, ErrRemote) {
		return err
	}
	if err = s.RPC.SaveSession(ctx); err != nil {
		return err
	}
	return nil
}

func IsDurablyWritable(b sqlite.BackendBinding) bool {
	return b.Attempt.BackendKind == BackendKind && b.Ownership.State == backend.OwnershipActive && b.Task.State == backend.TaskActive && b.Ownership.RuntimeIdentity == b.Task.RuntimeIdentity
}
