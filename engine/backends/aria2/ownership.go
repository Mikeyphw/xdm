package aria2

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

const BackendKind = "aria2"

// OwnershipRPC is the subset of JSON-RPC required by durable task ownership.
// Client implements it; tests can inject a deterministic daemon.
type OwnershipRPC interface {
	AddURI(context.Context, []string, Options) (string, error)
	TellStatus(context.Context, string) (Task, error)
	Pause(context.Context, string, bool) error
	Unpause(context.Context, string) error
	Remove(context.Context, string, bool) error
	SaveSession(context.Context) error
}

type DurableOwner struct {
	Repo *sqlite.Repository
	RPC  OwnershipRPC
	IDs  identity.TokenSource
	Now  func() time.Time
}

type PrepareRequest struct {
	DownloadID      identity.DownloadID
	Generation      identity.AttemptGeneration
	URIs            []string
	Options         Options
	RuntimeIdentity string
	StagingIdentity string
}

type Binding struct {
	DownloadID identity.DownloadID
	Generation identity.AttemptGeneration
	GID        string
	Runtime    string
	Staging    string
	Ownership  sqlite.OwnershipRecord
	Task       sqlite.BackendTaskRecord
}

func (o *DurableOwner) nowMS() int64 {
	if o != nil && o.Now != nil {
		return o.Now().UnixMilli()
	}
	return time.Now().UnixMilli()
}

func validateRuntimeIdentity(v string) error {
	if strings.TrimSpace(v) == "" || strings.ContainsAny(v, "\r\n\x00") {
		return fmt.Errorf("invalid aria2 runtime identity")
	}
	return nil
}

func (o *DurableOwner) validate() error {
	if o == nil || o.Repo == nil || o.RPC == nil || o.IDs == nil {
		return fmt.Errorf("aria2 durable owner is not configured")
	}
	return nil
}

func cloneOptions(in Options) Options {
	out := make(Options, len(in)+2)
	for k, v := range in {
		out[k] = v
	}
	return out
}

func nextGID(source identity.TokenSource) (string, error) {
	token, err := source.Token()
	if err != nil {
		return "", err
	}
	if len(token) != 32 {
		return "", fmt.Errorf("identifier source returned non-canonical token")
	}
	gid := token[:16]
	for _, ch := range gid {
		if !((ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f')) {
			return "", fmt.Errorf("identifier source returned non-hex token")
		}
	}
	return gid, nil
}

func bindingFromRecord(rec sqlite.BackendBinding) Binding {
	return Binding{DownloadID: rec.Attempt.DownloadID, Generation: rec.Attempt.Generation, GID: rec.Task.ExternalTaskID, Runtime: rec.Task.RuntimeIdentity, Staging: rec.Ownership.StagingIdentity, Ownership: rec.Ownership, Task: rec.Task}
}

// EnsurePaused creates or recovers an aria2 task without ever allowing it to
// run before GID/runtime/generation/staging ownership is durable. The desired
// GID is allocated and persisted before aria2.addUri is sent, so a crash around
// task creation is recoverable without accepting an unbound daemon writer.
func (o *DurableOwner) EnsurePaused(ctx context.Context, req PrepareRequest) (Binding, error) {
	if err := o.validate(); err != nil {
		return Binding{}, err
	}
	if req.DownloadID.IsZero() || !req.Generation.Valid() || len(req.URIs) == 0 || strings.TrimSpace(req.StagingIdentity) == "" {
		return Binding{}, fmt.Errorf("invalid aria2 ownership request")
	}
	if err := validateRuntimeIdentity(req.RuntimeIdentity); err != nil {
		return Binding{}, err
	}

	attempt, err := o.Repo.GetAttempt(ctx, req.DownloadID, req.Generation)
	if err != nil {
		return Binding{}, err
	}
	if attempt.BackendKind != BackendKind {
		return Binding{}, fmt.Errorf("attempt backend %q is not aria2", attempt.BackendKind)
	}

	own, err := o.Repo.GetOwnership(ctx, req.DownloadID, req.Generation)
	if errors.Is(err, sqlite.ErrNotFound) {
		own, err = o.Repo.CreateOwnershipClaim(ctx, req.DownloadID, req.Generation, BackendKind, req.StagingIdentity, req.RuntimeIdentity, o.nowMS())
	}
	if err != nil {
		return Binding{}, err
	}
	if own.BackendKind != BackendKind || own.RuntimeIdentity != req.RuntimeIdentity || own.StagingIdentity != req.StagingIdentity {
		return Binding{}, fmt.Errorf("existing aria2 ownership identity differs from request")
	}
	if own.State == backend.OwnershipAbandoned || own.State == backend.OwnershipRetired {
		return Binding{}, sqlite.ErrOwnershipState
	}

	task, err := o.Repo.GetAttemptTask(ctx, req.DownloadID, req.Generation)
	if errors.Is(err, sqlite.ErrNotFound) {
		if own.State != backend.OwnershipClaimed {
			return Binding{}, sqlite.ErrOwnershipState
		}
		taskID, idErr := identity.NewBackendTaskID(o.IDs)
		if idErr != nil {
			return Binding{}, idErr
		}
		gid, gidErr := nextGID(o.IDs)
		if gidErr != nil {
			return Binding{}, gidErr
		}
		own, task, err = o.Repo.BindBackendTask(ctx, req.DownloadID, req.Generation, own.Revision, taskID, gid, req.RuntimeIdentity, o.nowMS())
	}
	if err != nil {
		return Binding{}, err
	}
	if task.RuntimeIdentity != req.RuntimeIdentity || strings.TrimSpace(task.ExternalTaskID) == "" {
		return Binding{}, sqlite.ErrRuntimeIdentityMismatch
	}
	gid := task.ExternalTaskID

	if own.State == backend.OwnershipActive {
		return Binding{DownloadID: req.DownloadID, Generation: req.Generation, GID: gid, Runtime: req.RuntimeIdentity, Staging: req.StagingIdentity, Ownership: own, Task: task}, nil
	}

	daemonTask, statusErr := o.RPC.TellStatus(ctx, gid)
	if statusErr == nil {
		// A task that escaped the paused boundary is immediately quiesced. It
		// still cannot write canonical progress because DB ownership is not active.
		if daemonTask.Status == StatusActive {
			if err = o.RPC.Pause(ctx, gid, true); err != nil {
				return Binding{}, err
			}
		}
	} else if errors.Is(statusErr, ErrRemote) {
		opts := cloneOptions(req.Options)
		opts["pause"] = StringOption("true")
		opts["gid"] = StringOption(gid)
		returned, addErr := o.RPC.AddURI(ctx, req.URIs, opts)
		if addErr != nil {
			return Binding{}, addErr
		}
		if returned != gid {
			_ = o.RPC.Remove(ctx, returned, true)
			return Binding{}, fmt.Errorf("aria2 returned GID %q for persisted GID %q", returned, gid)
		}
	} else {
		return Binding{}, statusErr
	}
	if err = o.RPC.SaveSession(ctx); err != nil {
		return Binding{}, err
	}

	if own.State == backend.OwnershipTaskBound {
		own, err = o.Repo.MarkOwnershipReady(ctx, req.DownloadID, req.Generation, own.Revision, o.nowMS())
		if err != nil {
			return Binding{}, err
		}
	}
	task, err = o.Repo.GetAttemptTask(ctx, req.DownloadID, req.Generation)
	if err != nil {
		return Binding{}, err
	}
	return Binding{DownloadID: req.DownloadID, Generation: req.Generation, GID: gid, Runtime: req.RuntimeIdentity, Staging: req.StagingIdentity, Ownership: own, Task: task}, nil
}

// Activate unpauses only a durably bound Ready owner. If aria2 already runs
// because of a crash between RPC activation and SQLite commit, the method is
// idempotent and only completes the DB transition.
func (o *DurableOwner) Activate(ctx context.Context, binding Binding) (Binding, error) {
	if err := o.validate(); err != nil {
		return Binding{}, err
	}
	current, err := o.Repo.GetBackendBinding(ctx, binding.DownloadID, binding.Generation)
	if err != nil {
		return Binding{}, err
	}
	if current.Ownership.RuntimeIdentity != binding.Runtime || current.Task.RuntimeIdentity != binding.Runtime || current.Task.ExternalTaskID != binding.GID {
		return Binding{}, sqlite.ErrRuntimeIdentityMismatch
	}
	if current.Ownership.State == backend.OwnershipActive {
		if err = o.Repo.AssertBackendTaskWriter(ctx, binding.DownloadID, binding.Generation, binding.GID, binding.Runtime); err != nil {
			return Binding{}, err
		}
		return bindingFromRecord(current), nil
	}
	if current.Ownership.State != backend.OwnershipReady || current.Task.State != backend.TaskPrepared {
		return Binding{}, sqlite.ErrOwnershipState
	}
	status, statusErr := o.RPC.TellStatus(ctx, binding.GID)
	if statusErr != nil {
		return Binding{}, statusErr
	}
	switch status.Status {
	case StatusPaused, StatusWaiting:
		if err = o.RPC.Unpause(ctx, binding.GID); err != nil {
			return Binding{}, err
		}
	case StatusActive:
		// Crash recovery: external activation happened, durable activation did not.
	default:
		return Binding{}, fmt.Errorf("aria2 task cannot activate from daemon status %s", status.Status)
	}
	own, task, err := o.Repo.ActivateOwnership(ctx, binding.DownloadID, binding.Generation, current.Ownership.Revision, o.nowMS())
	if err != nil {
		_ = o.RPC.Pause(ctx, binding.GID, true)
		return Binding{}, err
	}
	return Binding{DownloadID: binding.DownloadID, Generation: binding.Generation, GID: binding.GID, Runtime: binding.Runtime, Staging: own.StagingIdentity, Ownership: own, Task: task}, nil
}

func (o *DurableOwner) AcceptUpdate(ctx context.Context, binding Binding) error {
	if err := o.validate(); err != nil {
		return err
	}
	return o.Repo.AssertBackendTaskWriter(ctx, binding.DownloadID, binding.Generation, binding.GID, binding.Runtime)
}

// RebindRecoveredTask atomically transfers a daemon-restored task to a fresh
// generation. Staging identity must match the historical owner; GID equality
// alone is never sufficient proof after a daemon/runtime restart.
func (o *DurableOwner) RebindRecoveredTask(ctx context.Context, old sqlite.BackendBinding, runtimeIdentity string, daemonTask Task) (Binding, error) {
	if err := o.validate(); err != nil {
		return Binding{}, err
	}
	if err := validateRuntimeIdentity(runtimeIdentity); err != nil {
		return Binding{}, err
	}
	if daemonTask.GID == "" || daemonTask.GID != old.Task.ExternalTaskID {
		return Binding{}, sqlite.ErrBackendTaskMismatch
	}
	if !defaultStagingMatch(old.Ownership.StagingIdentity, daemonTask) {
		return Binding{}, fmt.Errorf("recovered aria2 task staging identity does not match durable owner")
	}
	activate := false
	attemptState := "paused"
	switch daemonTask.Status {
	case StatusActive:
		activate = true
		attemptState = "running"
	case StatusComplete:
		activate = true
		attemptState = "transport_complete"
	case StatusPaused, StatusWaiting:
		// Ready ownership remains non-writing until explicit activation.
	default:
		return Binding{}, fmt.Errorf("aria2 task cannot be rebound from daemon status %s", daemonTask.Status)
	}
	download, err := o.Repo.GetDownload(ctx, old.Attempt.DownloadID)
	if err != nil {
		return Binding{}, err
	}
	if download.CurrentAttempt == nil || *download.CurrentAttempt != old.Attempt.Generation {
		return Binding{}, sqlite.ErrStaleAttempt
	}
	taskID, err := identity.NewBackendTaskID(o.IDs)
	if err != nil {
		return Binding{}, err
	}
	_, target, err := o.Repo.RebindBackendTaskGeneration(
		ctx,
		old.Attempt.DownloadID,
		old.Attempt.Generation,
		download.Revision,
		taskID,
		daemonTask.GID,
		runtimeIdentity,
		old.Ownership.StagingIdentity,
		attemptState,
		activate,
		o.nowMS(),
	)
	if err != nil {
		return Binding{}, err
	}
	return bindingFromRecord(target), nil
}
