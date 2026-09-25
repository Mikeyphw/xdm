package migration

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/subhra74/xdm/engine/domain/backend"
	"github.com/subhra74/xdm/engine/domain/identity"
	"github.com/subhra74/xdm/engine/store/sqlite"
)

const (
	StateReserved          = "migration_reserved"
	StateSourceQuiesced    = "migration_source_quiesced"
	StateTargetEstablished = "migration_target_established"
	StateTargetActive      = "migration_target_active"
	StateRecoveryRequired  = "backend_recovery_required"
	StateComplete          = "active"
)

type Boundary string

const (
	BoundaryReserved          Boundary = "reserved"
	BoundarySourceQuiesced    Boundary = "source_quiesced"
	BoundaryTargetEstablished Boundary = "target_established"
	BoundaryTargetActive      Boundary = "target_active"
	BoundarySourceRetired     Boundary = "source_retired"
)

var (
	ErrNotMigration       = errors.New("attempt is not a backend migration")
	ErrBackendMismatch    = errors.New("migration backend mismatch")
	ErrSourceComplete     = errors.New("source transfer is already complete")
	ErrRecoveryRequired   = errors.New("backend migration requires recovery")
	ErrUnsafeReuse        = errors.New("source checkpoint is not safe to reuse")
	ErrMigrationInvariant = errors.New("backend migration invariant violated")
)

// ReuseDecision is the source backend's post-quiesce statement about physical
// bytes. A target must not infer checkpoint compatibility from path equality.
type ReuseDecision struct {
	StagingIdentity    string
	BytesPresent       int64
	Complete           bool
	CheckpointReusable bool
	Detail             string
}

type Source interface {
	Quiesce(context.Context, sqlite.BackendBinding) error
	Inspect(context.Context, sqlite.BackendBinding) (ReuseDecision, error)
	Retire(context.Context, sqlite.BackendBinding) error
}

type EstablishRequest struct {
	DownloadID       identity.DownloadID
	SourceGeneration identity.AttemptGeneration
	TargetGeneration identity.AttemptGeneration
	Source           sqlite.BackendBinding
	Reuse            ReuseDecision
}

type Target interface {
	BackendKind() string
	Establish(context.Context, EstablishRequest) error
	Activate(context.Context, identity.DownloadID, identity.AttemptGeneration) error
}

type Migration struct {
	DownloadID       identity.DownloadID
	SourceGeneration identity.AttemptGeneration
	TargetGeneration identity.AttemptGeneration
	SourceBackend    string
	TargetBackend    string
}

type Result struct {
	Migration Migration
	State     string
	Reuse     ReuseDecision
}

type Coordinator struct {
	Repo          *sqlite.Repository
	Sources       map[string]Source
	Targets       map[string]Target
	Now           func() time.Time
	AfterBoundary func(Boundary) error
}

func (c *Coordinator) nowMS() int64 {
	if c != nil && c.Now != nil {
		return c.Now().UnixMilli()
	}
	return time.Now().UnixMilli()
}

func (c *Coordinator) validate() error {
	if c == nil || c.Repo == nil {
		return fmt.Errorf("migration coordinator is not configured")
	}
	return nil
}

func (c *Coordinator) source(kind string) (Source, error) {
	s := c.Sources[strings.TrimSpace(kind)]
	if s == nil {
		return nil, fmt.Errorf("%w: source=%q", ErrBackendMismatch, kind)
	}
	return s, nil
}

func (c *Coordinator) target(kind string) (Target, error) {
	t := c.Targets[strings.TrimSpace(kind)]
	if t == nil || strings.TrimSpace(t.BackendKind()) != strings.TrimSpace(kind) {
		return nil, fmt.Errorf("%w: target=%q", ErrBackendMismatch, kind)
	}
	return t, nil
}

func (c *Coordinator) boundary(b Boundary) error {
	if c.AfterBoundary != nil {
		return c.AfterBoundary(b)
	}
	return nil
}

// Start atomically reserves the next attempt generation before touching target
// execution. ReserveAttemptGeneration is the canonical stale-writer fence: any
// source progress/completion arriving after this call is non-authoritative.
func (c *Coordinator) Start(ctx context.Context, downloadID identity.DownloadID, targetBackend string) (Result, error) {
	if err := c.validate(); err != nil {
		return Result{}, err
	}
	targetBackend = strings.TrimSpace(targetBackend)
	if downloadID.IsZero() || targetBackend == "" {
		return Result{}, fmt.Errorf("invalid migration request")
	}
	download, err := c.Repo.GetDownload(ctx, downloadID)
	if err != nil {
		return Result{}, err
	}
	if download.CurrentAttempt == nil {
		return Result{}, fmt.Errorf("%w: no source attempt", ErrNotMigration)
	}
	sourceGeneration := *download.CurrentAttempt
	sourceAttempt, err := c.Repo.GetAttempt(ctx, downloadID, sourceGeneration)
	if err != nil {
		return Result{}, err
	}
	if sourceAttempt.BackendKind == targetBackend {
		return Result{}, fmt.Errorf("%w: source and target are %q", ErrBackendMismatch, targetBackend)
	}
	if _, err = c.source(sourceAttempt.BackendKind); err != nil {
		return Result{}, err
	}
	if _, err = c.target(targetBackend); err != nil {
		return Result{}, err
	}

	targetAttempt, _, err := c.Repo.ReserveAttemptGeneration(ctx, downloadID, download.Revision, targetBackend, c.nowMS())
	if err != nil {
		return Result{}, err
	}
	targetAttempt, err = c.Repo.MutateAttempt(ctx, downloadID, targetAttempt.Generation, targetAttempt.Revision, StateReserved, "", "", c.nowMS())
	if err != nil {
		return Result{}, err
	}
	m := Migration{
		DownloadID: downloadID, SourceGeneration: sourceGeneration, TargetGeneration: targetAttempt.Generation,
		SourceBackend: sourceAttempt.BackendKind, TargetBackend: targetBackend,
	}
	if err = c.boundary(BoundaryReserved); err != nil {
		return Result{Migration: m, State: targetAttempt.State}, err
	}
	return c.Resume(ctx, m)
}

// Recover reconstructs a migration after process death from the current target
// generation and the immediately preceding historical source generation. No
// separate journal table is required; attempt state is the durable journal.
func (c *Coordinator) Recover(ctx context.Context, downloadID identity.DownloadID) (Result, error) {
	if err := c.validate(); err != nil {
		return Result{}, err
	}
	download, err := c.Repo.GetDownload(ctx, downloadID)
	if err != nil {
		return Result{}, err
	}
	if download.CurrentAttempt == nil || download.CurrentAttempt.Int64() <= 1 {
		return Result{}, ErrNotMigration
	}
	targetGeneration := *download.CurrentAttempt
	targetAttempt, err := c.Repo.GetAttempt(ctx, downloadID, targetGeneration)
	if err != nil {
		return Result{}, err
	}
	if targetAttempt.State == StateRecoveryRequired {
		return Result{}, fmt.Errorf("%w: state=%s", ErrRecoveryRequired, targetAttempt.State)
	}
	if targetAttempt.State != "reserved" && targetAttempt.State != StateReserved && targetAttempt.State != StateSourceQuiesced && targetAttempt.State != StateTargetEstablished && targetAttempt.State != StateTargetActive && targetAttempt.State != StateComplete {
		return Result{}, fmt.Errorf("%w: state=%s", ErrNotMigration, targetAttempt.State)
	}
	sourceGeneration, err := identity.NewAttemptGeneration(targetGeneration.Int64() - 1)
	if err != nil {
		return Result{}, err
	}
	sourceAttempt, err := c.Repo.GetAttempt(ctx, downloadID, sourceGeneration)
	if err != nil {
		return Result{}, err
	}
	if sourceAttempt.BackendKind == targetAttempt.BackendKind {
		return Result{}, fmt.Errorf("%w: adjacent attempts use backend %q", ErrNotMigration, targetAttempt.BackendKind)
	}
	m := Migration{DownloadID: downloadID, SourceGeneration: sourceGeneration, TargetGeneration: targetGeneration, SourceBackend: sourceAttempt.BackendKind, TargetBackend: targetAttempt.BackendKind}
	return c.Resume(ctx, m)
}

func (c *Coordinator) mutateState(ctx context.Context, attempt sqlite.AttemptRecord, state string) (sqlite.AttemptRecord, error) {
	if attempt.State == state {
		return attempt, nil
	}
	return c.Repo.MutateAttempt(ctx, attempt.DownloadID, attempt.Generation, attempt.Revision, state, "", "", c.nowMS())
}

func (c *Coordinator) Resume(ctx context.Context, m Migration) (Result, error) {
	if err := c.validate(); err != nil {
		return Result{}, err
	}
	source, err := c.source(m.SourceBackend)
	if err != nil {
		return Result{}, err
	}
	target, err := c.target(m.TargetBackend)
	if err != nil {
		return Result{}, err
	}
	if !m.TargetGeneration.Valid() || !m.SourceGeneration.Valid() || m.TargetGeneration.Int64() != m.SourceGeneration.Int64()+1 {
		return Result{}, fmt.Errorf("%w: non-adjacent generations", ErrMigrationInvariant)
	}
	download, err := c.Repo.GetDownload(ctx, m.DownloadID)
	if err != nil {
		return Result{}, err
	}
	if download.CurrentAttempt == nil || *download.CurrentAttempt != m.TargetGeneration {
		return Result{}, sqlite.ErrStaleAttempt
	}
	targetAttempt, err := c.Repo.GetAttempt(ctx, m.DownloadID, m.TargetGeneration)
	if err != nil {
		return Result{}, err
	}
	if targetAttempt.BackendKind != m.TargetBackend {
		return Result{}, ErrBackendMismatch
	}
	sourceBinding, err := c.Repo.GetBackendBinding(ctx, m.DownloadID, m.SourceGeneration)
	if err != nil {
		return Result{}, err
	}
	if sourceBinding.Attempt.BackendKind != m.SourceBackend {
		return Result{}, ErrBackendMismatch
	}
	if targetAttempt.State == "reserved" {
		targetAttempt, err = c.mutateState(ctx, targetAttempt, StateReserved)
		if err != nil {
			return Result{}, err
		}
	}

	result := Result{Migration: m, State: targetAttempt.State}
	if targetAttempt.State == StateComplete {
		return result, nil
	}

	if targetAttempt.State == StateReserved {
		if err = source.Quiesce(ctx, sourceBinding); err != nil {
			return result, err
		}
		targetAttempt, err = c.mutateState(ctx, targetAttempt, StateSourceQuiesced)
		if err != nil {
			return result, err
		}
		result.State = targetAttempt.State
		if err = c.boundary(BoundarySourceQuiesced); err != nil {
			return result, err
		}
	}

	if targetAttempt.State == StateSourceQuiesced {
		reuse, inspectErr := source.Inspect(ctx, sourceBinding)
		if inspectErr != nil {
			return result, inspectErr
		}
		if reuse.BytesPresent < 0 {
			return result, fmt.Errorf("%w: negative source byte count", ErrMigrationInvariant)
		}
		if reuse.Complete {
			targetAttempt, err = c.mutateState(ctx, targetAttempt, StateRecoveryRequired)
			if err != nil {
				return result, err
			}
			return Result{Migration: m, State: targetAttempt.State, Reuse: reuse}, ErrSourceComplete
		}
		result.Reuse = reuse
		if err = target.Establish(ctx, EstablishRequest{DownloadID: m.DownloadID, SourceGeneration: m.SourceGeneration, TargetGeneration: m.TargetGeneration, Source: sourceBinding, Reuse: reuse}); err != nil {
			return result, err
		}
		targetAttempt, err = c.mutateState(ctx, targetAttempt, StateTargetEstablished)
		if err != nil {
			return result, err
		}
		result.State = targetAttempt.State
		if err = c.boundary(BoundaryTargetEstablished); err != nil {
			return result, err
		}
	}

	if targetAttempt.State == StateTargetEstablished {
		if err = target.Activate(ctx, m.DownloadID, m.TargetGeneration); err != nil {
			return result, err
		}
		targetAttempt, err = c.mutateState(ctx, targetAttempt, StateTargetActive)
		if err != nil {
			return result, err
		}
		result.State = targetAttempt.State
		if err = c.boundary(BoundaryTargetActive); err != nil {
			return result, err
		}
	}

	if targetAttempt.State == StateTargetActive {
		if err = source.Retire(ctx, sourceBinding); err != nil {
			return result, err
		}
		if _, _, err = c.Repo.RetireSupersededOwnership(ctx, m.DownloadID, m.SourceGeneration, c.nowMS()); err != nil {
			return result, err
		}
		targetAttempt, err = c.mutateState(ctx, targetAttempt, StateComplete)
		if err != nil {
			return result, err
		}
		result.State = targetAttempt.State
		if err = c.boundary(BoundarySourceRetired); err != nil {
			return result, err
		}
	}
	return result, nil
}

// RecordLateSourceEvent persists a safe diagnostic on the current target
// attempt. It never mutates the superseded source attempt or its checkpoint.
func (c *Coordinator) RecordLateSourceEvent(ctx context.Context, m Migration, event string) error {
	if err := c.validate(); err != nil {
		return err
	}
	event = strings.TrimSpace(event)
	if event == "" || strings.ContainsAny(event, "\r\n\x00") {
		return fmt.Errorf("invalid late source event")
	}
	download, err := c.Repo.GetDownload(ctx, m.DownloadID)
	if err != nil {
		return err
	}
	if download.CurrentAttempt == nil || *download.CurrentAttempt != m.TargetGeneration || m.SourceGeneration == m.TargetGeneration {
		return sqlite.ErrStaleAttempt
	}
	current, err := c.Repo.GetAttempt(ctx, m.DownloadID, m.TargetGeneration)
	if err != nil {
		return err
	}
	payload, _ := json.Marshal(map[string]any{"event": event, "source_generation": m.SourceGeneration.Int64(), "source_backend": m.SourceBackend})
	eventID := fmt.Sprintf("migration-late-%s-%d-%s", m.DownloadID.String(), m.SourceGeneration.Int64(), strings.ReplaceAll(event, " ", "_"))
	return c.Repo.RecordAttemptDiagnostic(ctx, sqlite.AttemptDiagnosticRecord{
		EventID: eventID, Subsystem: "backend_migration", DownloadID: m.DownloadID, Generation: m.TargetGeneration,
		ExpectedState: current.State, EventType: "stale_source_event", Severity: "warning", SafePayload: string(payload), CreatedAtUnixMS: c.nowMS(),
	})
}

// AssertSourceFenced is a testable/public statement of the central migration
// invariant: once target generation is current, source canonical writes fail.
func (c *Coordinator) AssertSourceFenced(ctx context.Context, m Migration) error {
	err := c.Repo.AssertAuthoritativeWriter(ctx, m.DownloadID, m.SourceGeneration)
	if errors.Is(err, sqlite.ErrStaleAttempt) {
		return nil
	}
	if err == nil {
		return fmt.Errorf("%w: superseded source is still authoritative", ErrMigrationInvariant)
	}
	return err
}

func OwnershipIsWritable(b sqlite.BackendBinding) bool {
	return b.Ownership.State == backend.OwnershipActive && b.Task.State == backend.TaskActive
}
