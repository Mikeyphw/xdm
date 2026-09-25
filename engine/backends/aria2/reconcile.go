package aria2

import (
	"context"
	"errors"
	"fmt"
	"path/filepath"
	"sort"
	"strings"
	"time"

	"github.com/subhra74/xdm/engine/domain/backend"
	"github.com/subhra74/xdm/engine/store/sqlite"
)

type ReconcileRPC interface {
	TellActive(context.Context) ([]Task, error)
	TellWaiting(context.Context, int, int) ([]Task, error)
	TellStopped(context.Context, int, int) ([]Task, error)
}

type ReconcileClass string

const (
	ReconcileHealthy         ReconcileClass = "healthy"
	ReconcilePaused          ReconcileClass = "paused"
	ReconcileActivated       ReconcileClass = "activated_after_crash"
	ReconcileOfflineComplete ReconcileClass = "offline_complete"
	ReconcileRuntimeRebound  ReconcileClass = "runtime_rebound"
	ReconcileRuntimeReplaced ReconcileClass = "runtime_replaced"
	ReconcileMissing         ReconcileClass = "missing"
	ReconcileRemoved         ReconcileClass = "removed"
	ReconcileErrored         ReconcileClass = "errored"
	ReconcileUnknown         ReconcileClass = "unknown"
)

type ReconcileResult struct {
	Binding    sqlite.BackendBinding
	Class      ReconcileClass
	Detail     string
	NewBinding *Binding
}

type Reconciler struct {
	Repo            *sqlite.Repository
	RPC             ReconcileRPC
	Owner           *DurableOwner
	RuntimeIdentity string
	Now             func() time.Time
	MatchStaging    func(string, Task) bool
}

func (r *Reconciler) nowMS() int64 {
	if r != nil && r.Now != nil {
		return r.Now().UnixMilli()
	}
	return time.Now().UnixMilli()
}

func defaultStagingMatch(staging string, task Task) bool {
	staging = filepath.Clean(strings.TrimSpace(staging))
	if staging == "." || staging == "" {
		return false
	}
	for _, f := range task.Files {
		if strings.TrimSpace(f.Path) != "" && filepath.Clean(f.Path) == staging {
			return true
		}
	}
	return false
}

func addSnapshot(dst map[string]Task, tasks []Task) error {
	for _, task := range tasks {
		if strings.TrimSpace(task.GID) == "" {
			return fmt.Errorf("daemon snapshot contains empty GID")
		}
		if old, ok := dst[task.GID]; ok && old.Status != task.Status {
			return fmt.Errorf("daemon snapshot contains conflicting GID %s", task.GID)
		}
		dst[task.GID] = task
	}
	return nil
}

func snapshotDaemon(ctx context.Context, rpc ReconcileRPC) (map[string]Task, error) {
	if rpc == nil {
		return nil, fmt.Errorf("nil aria2 reconciliation RPC")
	}
	out := map[string]Task{}
	active, err := rpc.TellActive(ctx)
	if err != nil {
		return nil, err
	}
	if err = addSnapshot(out, active); err != nil {
		return nil, err
	}
	for _, stopped := range []bool{false, true} {
		for offset := 0; ; offset += maxListCount {
			var page []Task
			if stopped {
				page, err = rpc.TellStopped(ctx, offset, maxListCount)
			} else {
				page, err = rpc.TellWaiting(ctx, offset, maxListCount)
			}
			if err != nil {
				return nil, err
			}
			if err = addSnapshot(out, page); err != nil {
				return nil, err
			}
			if len(page) < maxListCount {
				break
			}
		}
	}
	return out, nil
}

func (r *Reconciler) markRecovery(ctx context.Context, b sqlite.BackendBinding, class ReconcileClass, detail string) (ReconcileResult, error) {
	if _, _, err := r.Repo.AbandonCurrentOwnership(ctx, b.Attempt.DownloadID, b.Attempt.Generation, r.nowMS()); err != nil && !errors.Is(err, sqlite.ErrOwnershipState) {
		return ReconcileResult{}, err
	}
	attempt, err := r.Repo.GetAttempt(ctx, b.Attempt.DownloadID, b.Attempt.Generation)
	if err != nil {
		return ReconcileResult{}, err
	}
	if attempt.State != "backend_recovery_required" {
		if _, err = r.Repo.MutateAttempt(ctx, b.Attempt.DownloadID, b.Attempt.Generation, attempt.Revision, "backend_recovery_required", "", "", r.nowMS()); err != nil {
			return ReconcileResult{}, err
		}
	}
	return ReconcileResult{Binding: b, Class: class, Detail: detail}, nil
}

func (r *Reconciler) markComplete(ctx context.Context, b sqlite.BackendBinding) (ReconcileResult, error) {
	// A completed task recovered while ownership was Ready is promoted locally;
	// no RPC activation occurs because the daemon has already finished it.
	if b.Ownership.State == backend.OwnershipReady {
		own, task, err := r.Repo.ActivateOwnership(ctx, b.Attempt.DownloadID, b.Attempt.Generation, b.Ownership.Revision, r.nowMS())
		if err != nil {
			return ReconcileResult{}, err
		}
		b.Ownership, b.Task = own, task
	}
	if err := r.Repo.AssertBackendTaskWriter(ctx, b.Attempt.DownloadID, b.Attempt.Generation, b.Task.ExternalTaskID, b.Task.RuntimeIdentity); err != nil {
		return ReconcileResult{}, err
	}
	attempt, err := r.Repo.GetAttempt(ctx, b.Attempt.DownloadID, b.Attempt.Generation)
	if err != nil {
		return ReconcileResult{}, err
	}
	if attempt.State != "transport_complete" {
		attempt, err = r.Repo.MutateAttempt(ctx, b.Attempt.DownloadID, b.Attempt.Generation, attempt.Revision, "transport_complete", "", "", r.nowMS())
		if err != nil {
			return ReconcileResult{}, err
		}
		b.Attempt = attempt
	}
	return ReconcileResult{Binding: b, Class: ReconcileOfflineComplete}, nil
}

func (r *Reconciler) ReconcileAll(ctx context.Context) ([]ReconcileResult, error) {
	if r == nil || r.Repo == nil || r.RPC == nil {
		return nil, fmt.Errorf("aria2 reconciler is not configured")
	}
	if err := validateRuntimeIdentity(r.RuntimeIdentity); err != nil {
		return nil, err
	}
	snapshot, err := snapshotDaemon(ctx, r.RPC)
	if err != nil {
		return nil, err
	}
	bindings, err := r.Repo.ListCurrentBackendBindings(ctx, BackendKind)
	if err != nil {
		return nil, err
	}
	matcher := r.MatchStaging
	if matcher == nil {
		matcher = defaultStagingMatch
	}
	results := make([]ReconcileResult, 0, len(bindings))
	for _, b := range bindings {
		daemon, exists := snapshot[b.Task.ExternalTaskID]
		if b.Ownership.RuntimeIdentity != r.RuntimeIdentity || b.Task.RuntimeIdentity != r.RuntimeIdentity {
			rebindable := exists && (daemon.Status == StatusActive || daemon.Status == StatusPaused || daemon.Status == StatusWaiting || daemon.Status == StatusComplete)
			if rebindable && matcher(b.Ownership.StagingIdentity, daemon) && r.Owner != nil {
				rebound, rebErr := r.Owner.RebindRecoveredTask(ctx, b, r.RuntimeIdentity, daemon)
				if rebErr != nil {
					return nil, rebErr
				}
				nb, getErr := r.Repo.GetBackendBinding(ctx, rebound.DownloadID, rebound.Generation)
				if getErr != nil {
					return nil, getErr
				}
				result := ReconcileResult{Binding: b, Class: ReconcileRuntimeRebound, NewBinding: &rebound}
				if daemon.Status == StatusComplete {
					completed, compErr := r.markComplete(ctx, nb)
					if compErr != nil {
						return nil, compErr
					}
					result.Detail = string(completed.Class)
				}
				results = append(results, result)
				continue
			}
			result, recErr := r.markRecovery(ctx, b, ReconcileRuntimeReplaced, "stored runtime identity is stale")
			if recErr != nil {
				return nil, recErr
			}
			results = append(results, result)
			continue
		}
		if !exists {
			result, recErr := r.markRecovery(ctx, b, ReconcileMissing, "GID absent from daemon snapshot")
			if recErr != nil {
				return nil, recErr
			}
			results = append(results, result)
			continue
		}
		switch daemon.Status {
		case StatusActive:
			if b.Ownership.State == backend.OwnershipReady {
				own, task, actErr := r.Repo.ActivateOwnership(ctx, b.Attempt.DownloadID, b.Attempt.Generation, b.Ownership.Revision, r.nowMS())
				if actErr != nil {
					return nil, actErr
				}
				b.Ownership, b.Task = own, task
				results = append(results, ReconcileResult{Binding: b, Class: ReconcileActivated})
			} else if b.Ownership.State == backend.OwnershipActive {
				results = append(results, ReconcileResult{Binding: b, Class: ReconcileHealthy})
			} else {
				result, recErr := r.markRecovery(ctx, b, ReconcileUnknown, "active daemon task lacks ready/active ownership")
				if recErr != nil {
					return nil, recErr
				}
				results = append(results, result)
			}
		case StatusPaused, StatusWaiting:
			if b.Ownership.State == backend.OwnershipReady || b.Ownership.State == backend.OwnershipActive {
				results = append(results, ReconcileResult{Binding: b, Class: ReconcilePaused})
			} else {
				result, recErr := r.markRecovery(ctx, b, ReconcileUnknown, "paused daemon task lacks durable owner")
				if recErr != nil {
					return nil, recErr
				}
				results = append(results, result)
			}
		case StatusComplete:
			completed, compErr := r.markComplete(ctx, b)
			if compErr != nil {
				return nil, compErr
			}
			results = append(results, completed)
		case StatusRemoved:
			result, recErr := r.markRecovery(ctx, b, ReconcileRemoved, "daemon task removed")
			if recErr != nil {
				return nil, recErr
			}
			results = append(results, result)
		case StatusError:
			result, recErr := r.markRecovery(ctx, b, ReconcileErrored, "daemon task failed")
			if recErr != nil {
				return nil, recErr
			}
			results = append(results, result)
		default:
			result, recErr := r.markRecovery(ctx, b, ReconcileUnknown, "daemon task status unknown")
			if recErr != nil {
				return nil, recErr
			}
			results = append(results, result)
		}
	}
	sort.Slice(results, func(i, j int) bool {
		return results[i].Binding.Attempt.DownloadID.String() < results[j].Binding.Attempt.DownloadID.String()
	})
	return results, nil
}
