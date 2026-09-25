package migration

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/subhra74/xdm/engine/domain/backend"
	"github.com/subhra74/xdm/engine/domain/identity"
	"github.com/subhra74/xdm/engine/store/sqlite"
)

type StoreTarget struct {
	Repo             *sqlite.Repository
	Kind             string
	IDs              identity.TokenSource
	RuntimeIdentity  string
	StagingIdentity  func(EstablishRequest) (string, error)
	ExternalTaskID   func(EstablishRequest) (string, error)
	Prepare          func(context.Context, EstablishRequest, sqlite.BackendBinding) error
	ActivatePrepared func(context.Context, sqlite.BackendBinding) error
	Now              func() time.Time
}

func (t *StoreTarget) BackendKind() string { return strings.TrimSpace(t.Kind) }

func (t *StoreTarget) nowMS() int64 {
	if t != nil && t.Now != nil {
		return t.Now().UnixMilli()
	}
	return time.Now().UnixMilli()
}

func (t *StoreTarget) validate() error {
	if t == nil || t.Repo == nil || t.IDs == nil || strings.TrimSpace(t.Kind) == "" || strings.TrimSpace(t.RuntimeIdentity) == "" || t.StagingIdentity == nil {
		return fmt.Errorf("store target is not configured")
	}
	return nil
}

func (t *StoreTarget) externalID(req EstablishRequest) (string, error) {
	if t.ExternalTaskID != nil {
		return t.ExternalTaskID(req)
	}
	token, err := t.IDs.Token()
	if err != nil {
		return "", err
	}
	if strings.TrimSpace(token) == "" {
		return "", fmt.Errorf("empty external task identity")
	}
	return t.Kind + "-" + token, nil
}

func (t *StoreTarget) Establish(ctx context.Context, req EstablishRequest) error {
	if err := t.validate(); err != nil {
		return err
	}
	attempt, err := t.Repo.GetAttempt(ctx, req.DownloadID, req.TargetGeneration)
	if err != nil {
		return err
	}
	if attempt.BackendKind != t.Kind {
		return ErrBackendMismatch
	}
	staging, err := t.StagingIdentity(req)
	if err != nil {
		return err
	}
	staging = strings.TrimSpace(staging)
	if staging == "" {
		return fmt.Errorf("empty target staging identity")
	}

	own, err := t.Repo.GetOwnership(ctx, req.DownloadID, req.TargetGeneration)
	if errors.Is(err, sqlite.ErrNotFound) {
		own, err = t.Repo.CreateOwnershipClaim(ctx, req.DownloadID, req.TargetGeneration, t.Kind, staging, t.RuntimeIdentity, t.nowMS())
	}
	if err != nil {
		return err
	}
	if own.BackendKind != t.Kind || own.RuntimeIdentity != t.RuntimeIdentity || own.StagingIdentity != staging {
		return fmt.Errorf("target ownership identity differs from persisted claim")
	}
	if own.State.Terminal() {
		return sqlite.ErrOwnershipState
	}

	task, err := t.Repo.GetAttemptTask(ctx, req.DownloadID, req.TargetGeneration)
	if errors.Is(err, sqlite.ErrNotFound) {
		if own.State != backend.OwnershipClaimed {
			return sqlite.ErrOwnershipState
		}
		taskID, idErr := identity.NewBackendTaskID(t.IDs)
		if idErr != nil {
			return idErr
		}
		external, idErr := t.externalID(req)
		if idErr != nil {
			return idErr
		}
		own, task, err = t.Repo.BindBackendTask(ctx, req.DownloadID, req.TargetGeneration, own.Revision, taskID, external, t.RuntimeIdentity, t.nowMS())
	}
	if err != nil {
		return err
	}
	if task.RuntimeIdentity != t.RuntimeIdentity || task.BackendKind != t.Kind {
		return sqlite.ErrRuntimeIdentityMismatch
	}
	binding := sqlite.BackendBinding{Attempt: attempt, Ownership: own, Task: task}
	if own.State == backend.OwnershipReady || own.State == backend.OwnershipActive {
		return nil
	}
	if own.State != backend.OwnershipTaskBound || task.State != backend.TaskPrepared {
		return sqlite.ErrOwnershipState
	}
	if t.Prepare != nil {
		if err = t.Prepare(ctx, req, binding); err != nil {
			return err
		}
	}
	_, err = t.Repo.MarkOwnershipReady(ctx, req.DownloadID, req.TargetGeneration, own.Revision, t.nowMS())
	return err
}

func (t *StoreTarget) Activate(ctx context.Context, id identity.DownloadID, generation identity.AttemptGeneration) error {
	if err := t.validate(); err != nil {
		return err
	}
	binding, err := t.Repo.GetBackendBinding(ctx, id, generation)
	if err != nil {
		return err
	}
	if binding.Attempt.BackendKind != t.Kind || binding.Ownership.BackendKind != t.Kind || binding.Task.BackendKind != t.Kind {
		return ErrBackendMismatch
	}
	if binding.Ownership.State == backend.OwnershipActive && binding.Task.State == backend.TaskActive {
		return nil
	}
	if binding.Ownership.State != backend.OwnershipReady || binding.Task.State != backend.TaskPrepared {
		return sqlite.ErrOwnershipState
	}
	if t.ActivatePrepared != nil {
		if err = t.ActivatePrepared(ctx, binding); err != nil {
			return err
		}
	}
	_, _, err = t.Repo.ActivateOwnership(ctx, id, generation, binding.Ownership.Revision, t.nowMS())
	return err
}

type StoreSource struct {
	QuiesceFunc func(context.Context, sqlite.BackendBinding) error
	InspectFunc func(context.Context, sqlite.BackendBinding) (ReuseDecision, error)
	RetireFunc  func(context.Context, sqlite.BackendBinding) error
}

func (s StoreSource) Quiesce(ctx context.Context, b sqlite.BackendBinding) error {
	if s.QuiesceFunc == nil {
		return nil
	}
	return s.QuiesceFunc(ctx, b)
}
func (s StoreSource) Inspect(ctx context.Context, b sqlite.BackendBinding) (ReuseDecision, error) {
	if s.InspectFunc == nil {
		return ReuseDecision{StagingIdentity: b.Ownership.StagingIdentity}, nil
	}
	return s.InspectFunc(ctx, b)
}
func (s StoreSource) Retire(ctx context.Context, b sqlite.BackendBinding) error {
	if s.RetireFunc == nil {
		return nil
	}
	return s.RetireFunc(ctx, b)
}
