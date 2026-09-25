package recovery

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"strings"

	"github.com/subhra74/xdm/engine/domain/backend"
	"github.com/subhra74/xdm/engine/domain/identity"
	"github.com/subhra74/xdm/engine/domain/publication"
	"github.com/subhra74/xdm/engine/store/checkpoint"
	store "github.com/subhra74/xdm/engine/store/sqlite"
)

type Category string

const (
	ActiveOwnedBackend                 Category = "active_owned_backend"
	BackendMissing                     Category = "backend_missing"
	StaleOwnership                     Category = "stale_ownership"
	CurrentCheckpointRecoverable       Category = "current_checkpoint_recoverable"
	CorruptCheckpoint                  Category = "corrupt_checkpoint"
	VerifiedArtifactUnpublished        Category = "verified_artifact_unpublished"
	PlatformCommittedEngineUncommitted Category = "platform_committed_engine_uncommitted"
	Completed                          Category = "completed"
	Unrecoverable                      Category = "unrecoverable"
)

type Action string

const (
	ActionObserveBackend     Action = "observe_backend"
	ActionReconcileBackend   Action = "reconcile_backend"
	ActionResumeCheckpoint   Action = "resume_checkpoint"
	ActionDiscardCheckpoint  Action = "discard_corrupt_checkpoint"
	ActionRequestPublication Action = "request_publication"
	ActionInspectReceipt     Action = "inspect_publication_receipt"
	ActionCommitEngine       Action = "commit_engine_publication"
	ActionCleanup            Action = "cleanup_publication"
	ActionDiagnosticOnly     Action = "diagnostic_only"
	ActionManual             Action = "manual_intervention"
	ActionNone               Action = "none"
)

type ResolvedFile struct {
	File  checkpoint.File
	Close func() error
}

type CheckpointResolver interface {
	Resolve(context.Context, string) (ResolvedFile, error)
}

type FilesystemResolver struct{}

func (FilesystemResolver) Resolve(_ context.Context, identity string) (ResolvedFile, error) {
	if strings.TrimSpace(identity) == "" {
		return ResolvedFile{}, fmt.Errorf("empty staging identity")
	}
	f, err := os.OpenFile(identity, os.O_RDWR, 0)
	if err != nil {
		return ResolvedFile{}, err
	}
	return ResolvedFile{File: f, Close: f.Close}, nil
}

type Stage string

const (
	AfterClassify     Stage = "after_classify"
	AfterEngineCommit Stage = "after_engine_commit"
	AfterCleanup      Stage = "after_cleanup"
	AfterDiagnostic   Stage = "after_diagnostic"
)

type FaultHook func(Stage) error

type Coordinator struct {
	Repository *store.Repository
	Resolver   CheckpointResolver
	Hook       FaultHook
	NowUnixMS  func() int64
}

type Decision struct {
	DownloadID        identity.DownloadID         `json:"download_id"`
	AttemptGeneration *identity.AttemptGeneration `json:"attempt_generation,omitempty"`
	Category          Category                    `json:"category"`
	Actions           []Action                    `json:"actions"`
	PublicationID     identity.PublicationID      `json:"publication_id,omitempty"`
	PublicationAction publication.ReconcileAction `json:"publication_action,omitempty"`
	Reason            string                      `json:"reason,omitempty"`
	DiagnosticEventID string                      `json:"diagnostic_event_id"`
}

func (c *Coordinator) now() int64 {
	if c.NowUnixMS != nil {
		if v := c.NowUnixMS(); v > 0 {
			return v
		}
	}
	return 1
}

func (c *Coordinator) hook(stage Stage) error {
	if c.Hook == nil {
		return nil
	}
	return c.Hook(stage)
}

func (c *Coordinator) RecoverAll(ctx context.Context) ([]Decision, error) {
	if c == nil || c.Repository == nil {
		return nil, store.ErrClosed
	}
	downloads, err := c.Repository.ListDownloads(ctx)
	if err != nil {
		return nil, err
	}
	out := make([]Decision, 0, len(downloads))
	for _, download := range downloads {
		decision, err := c.RecoverOne(ctx, download.ID)
		if err != nil {
			return nil, err
		}
		out = append(out, decision)
	}
	return out, nil
}

func (c *Coordinator) RecoverOne(ctx context.Context, downloadID identity.DownloadID) (Decision, error) {
	if c == nil || c.Repository == nil {
		return Decision{}, store.ErrClosed
	}
	snap, err := c.Repository.RecoverySnapshot(ctx, downloadID)
	if err != nil {
		return Decision{}, err
	}
	decision, err := c.classify(ctx, snap)
	if err != nil {
		return Decision{}, err
	}
	if err = c.hook(AfterClassify); err != nil {
		return Decision{}, err
	}

	// Only publication recovery mutates durable state in v1. Backend/checkpoint
	// decisions are explicit handoff instructions for later execution layers.
	if snap.Publication != nil {
		switch snap.Publication.State {
		case publication.PlatformCommitted:
			pub, _, _, commitErr := c.Repository.EngineCommitPublication(ctx, snap.Publication.ID, snap.Publication.Revision, snap.Download.Revision, c.now())
			if commitErr != nil {
				return Decision{}, commitErr
			}
			decision.Actions = appendUnique(decision.Actions, ActionCommitEngine)
			if err = c.hook(AfterEngineCommit); err != nil {
				return decision, err
			}
			cleaned, cleanErr := c.Repository.CleanPublication(ctx, pub.ID, pub.Revision, c.now())
			if cleanErr != nil {
				return Decision{}, cleanErr
			}
			_ = cleaned
			decision.Actions = appendUnique(decision.Actions, ActionCleanup)
			if err = c.hook(AfterCleanup); err != nil {
				return decision, err
			}
		case publication.EngineCommitted:
			if _, cleanErr := c.Repository.CleanPublication(ctx, snap.Publication.ID, snap.Publication.Revision, c.now()); cleanErr != nil {
				return Decision{}, cleanErr
			}
			decision.Actions = appendUnique(decision.Actions, ActionCleanup)
			if err = c.hook(AfterCleanup); err != nil {
				return decision, err
			}
		}
	}

	payload, _ := json.Marshal(map[string]any{"category": decision.Category, "actions": decision.Actions, "reason": decision.Reason})
	if err = c.Repository.RecordRecoveryDiagnostic(ctx, decision.DiagnosticEventID, decision.DownloadID, decision.AttemptGeneration, "recovery."+string(decision.Category), string(payload), c.now()); err != nil {
		return Decision{}, err
	}
	if err = c.hook(AfterDiagnostic); err != nil {
		return decision, err
	}
	return decision, nil
}

func (c *Coordinator) classify(ctx context.Context, snap store.RecoverySnapshot) (Decision, error) {
	decision := Decision{DownloadID: snap.Download.ID, Actions: []Action{ActionNone}}
	if snap.Download.CurrentAttempt != nil {
		v := *snap.Download.CurrentAttempt
		decision.AttemptGeneration = &v
	}
	if snap.Publication != nil {
		decision.PublicationID = snap.Publication.ID
		decision.PublicationAction = publication.ActionFor(snap.Publication.State)
	}

	if snap.Publication != nil {
		switch snap.Publication.State {
		case publication.PlatformCommitted:
			decision.Category = PlatformCommittedEngineUncommitted
			decision.Actions = []Action{ActionCommitEngine, ActionCleanup}
			decision.Reason = "durable platform receipt exists but engine commit is incomplete"
			decision.DiagnosticEventID = diagnosticID(snap, decision.Category)
			return decision, nil
		case publication.EngineCommitted:
			decision.Category = Completed
			decision.Actions = []Action{ActionCleanup}
			decision.Reason = "engine completion is durable; cleanup remains"
			decision.DiagnosticEventID = diagnosticID(snap, decision.Category)
			return decision, nil
		case publication.Cleaned:
			decision.Category = Completed
			decision.Actions = []Action{ActionNone}
			decision.Reason = "publication saga is fully cleaned"
			decision.DiagnosticEventID = diagnosticID(snap, decision.Category)
			return decision, nil
		case publication.PlatformCommitRequested:
			decision.Category = VerifiedArtifactUnpublished
			decision.Actions = []Action{ActionInspectReceipt}
			decision.Reason = "platform commit result is ambiguous; inspect receipt before retry"
			decision.DiagnosticEventID = diagnosticID(snap, decision.Category)
			return decision, nil
		case publication.Prepared:
			decision.Category = VerifiedArtifactUnpublished
			decision.Actions = []Action{ActionRequestPublication}
			decision.Reason = "verified artifact has a prepared publication intent"
			decision.DiagnosticEventID = diagnosticID(snap, decision.Category)
			return decision, nil
		}
	}

	if snap.Artifact != nil && snap.Artifact.VerificationState == "verified" {
		decision.Category = VerifiedArtifactUnpublished
		decision.Actions = []Action{ActionRequestPublication}
		decision.Reason = "verified current artifact has not been published"
		decision.DiagnosticEventID = diagnosticID(snap, decision.Category)
		return decision, nil
	}

	if snap.Download.State == "completed" {
		decision.Category = Completed
		decision.Actions = []Action{ActionNone}
		decision.Reason = "download is durably completed"
		decision.DiagnosticEventID = diagnosticID(snap, decision.Category)
		return decision, nil
	}

	if snap.Ownership != nil && snap.BackendTask != nil && snap.Ownership.State == backend.OwnershipActive && snap.BackendTask.State == backend.TaskActive {
		decision.Category = ActiveOwnedBackend
		decision.Actions = []Action{ActionObserveBackend}
		decision.Reason = "current generation still has active durable backend ownership"
		decision.DiagnosticEventID = diagnosticID(snap, decision.Category)
		return decision, nil
	}

	if snap.CheckpointCount > 0 {
		if snap.Ownership == nil || strings.TrimSpace(snap.Ownership.StagingIdentity) == "" || c.Resolver == nil {
			decision.Category = Unrecoverable
			decision.Actions = []Action{ActionManual}
			decision.Reason = "checkpoint evidence exists but staging bytes cannot be resolved"
			decision.DiagnosticEventID = diagnosticID(snap, decision.Category)
			return decision, nil
		}
		resolved, err := c.Resolver.Resolve(ctx, snap.Ownership.StagingIdentity)
		if err != nil {
			decision.Category = Unrecoverable
			decision.Actions = []Action{ActionManual}
			decision.Reason = "checkpoint staging identity could not be opened"
			decision.DiagnosticEventID = diagnosticID(snap, decision.Category)
			return decision, nil
		}
		if resolved.Close != nil {
			defer resolved.Close()
		}
		inspections, err := checkpoint.Inspect(ctx, c.Repository, resolved.File, snap.Download.ID, *snap.Download.CurrentAttempt)
		if err != nil {
			return Decision{}, err
		}
		for _, inspection := range inspections {
			if !inspection.Valid {
				decision.Category = CorruptCheckpoint
				decision.Actions = []Action{ActionDiscardCheckpoint}
				decision.Reason = inspection.Reason
				decision.DiagnosticEventID = diagnosticID(snap, decision.Category)
				return decision, nil
			}
		}
		decision.Category = CurrentCheckpointRecoverable
		decision.Actions = []Action{ActionResumeCheckpoint}
		decision.Reason = "all durable checkpoint blocks match staging bytes"
		decision.DiagnosticEventID = diagnosticID(snap, decision.Category)
		return decision, nil
	}

	if snap.HasStaleOwnership {
		decision.Category = StaleOwnership
		decision.Actions = []Action{ActionDiagnosticOnly}
		decision.Reason = "nonterminal ownership exists for a superseded generation"
		decision.DiagnosticEventID = diagnosticID(snap, decision.Category)
		return decision, nil
	}

	if snap.Ownership != nil && (snap.BackendTask == nil || snap.BackendTask.State != backend.TaskActive) {
		decision.Category = BackendMissing
		decision.Actions = []Action{ActionReconcileBackend}
		decision.Reason = "current ownership has no active backend task"
		decision.DiagnosticEventID = diagnosticID(snap, decision.Category)
		return decision, nil
	}

	decision.Category = Unrecoverable
	decision.Actions = []Action{ActionManual}
	decision.Reason = "no authoritative backend, verified artifact, or valid checkpoint evidence"
	decision.DiagnosticEventID = diagnosticID(snap, decision.Category)
	return decision, nil
}

func diagnosticID(snap store.RecoverySnapshot, category Category) string {
	parts := []string{snap.Download.ID.String(), snap.Download.Revision.String(), string(category)}
	if snap.Download.CurrentAttempt != nil {
		parts = append(parts, snap.Download.CurrentAttempt.String())
	}
	if snap.Download.CurrentArtifact != nil {
		parts = append(parts, snap.Download.CurrentArtifact.String())
	}
	if snap.Publication != nil {
		parts = append(parts, snap.Publication.ID.String(), snap.Publication.Revision.String(), string(snap.Publication.State))
	}
	sum := sha256.Sum256([]byte(strings.Join(parts, "|")))
	return "recovery_" + hex.EncodeToString(sum[:16])
}

func appendUnique(actions []Action, value Action) []Action {
	for _, existing := range actions {
		if existing == value {
			return actions
		}
	}
	return append(actions, value)
}

func (c *Coordinator) ObserveBackendCompletion(ctx context.Context, downloadID identity.DownloadID, generation identity.AttemptGeneration) (bool, error) {
	if c == nil || c.Repository == nil {
		return false, store.ErrClosed
	}
	download, err := c.Repository.GetDownload(ctx, downloadID)
	if err != nil {
		return false, err
	}
	if download.CurrentAttempt != nil && *download.CurrentAttempt == generation {
		return true, nil
	}
	payload, _ := json.Marshal(map[string]any{"late_attempt_generation": generation.Int64(), "authoritative_state_changed": false})
	eventSeed := sha256.Sum256([]byte(downloadID.String() + "|" + generation.String() + "|stale_backend_completion"))
	eventID := "recovery_" + hex.EncodeToString(eventSeed[:16])
	if err := c.Repository.RecordRecoveryDiagnostic(ctx, eventID, downloadID, &generation, "recovery.stale_backend_completion", string(payload), c.now()); err != nil {
		return false, err
	}
	return false, nil
}

var _ io.Closer = (*os.File)(nil)
