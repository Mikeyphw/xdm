package publication

import (
	"errors"
	"fmt"
	"strings"

	"github.com/subhra74/xdm/engine/domain/identity"
)

var (
	ErrInvalidState      = errors.New("invalid publication state")
	ErrInvalidTransition = errors.New("invalid publication transition")
	ErrInvalidRequest    = errors.New("invalid publication platform request")
	ErrInvalidReceipt    = errors.New("invalid publication platform receipt")
)

type State string

const (
	Prepared                State = "PREPARED"
	PlatformCommitRequested State = "PLATFORM_COMMIT_REQUESTED"
	PlatformCommitted       State = "PLATFORM_COMMITTED"
	EngineCommitted         State = "ENGINE_COMMITTED"
	Cleaned                 State = "CLEANED"
)

func (s State) Valid() bool {
	switch s {
	case Prepared, PlatformCommitRequested, PlatformCommitted, EngineCommitted, Cleaned:
		return true
	default:
		return false
	}
}

func (s State) Terminal() bool { return s == Cleaned }

func CanTransition(from, to State) bool {
	switch from {
	case Prepared:
		return to == PlatformCommitRequested
	case PlatformCommitRequested:
		return to == PlatformCommitted
	case PlatformCommitted:
		return to == EngineCommitted
	case EngineCommitted:
		return to == Cleaned
	default:
		return false
	}
}

func ValidateTransition(from, to State) error {
	if !from.Valid() || !to.Valid() || !CanTransition(from, to) {
		return fmt.Errorf("%w: %s -> %s", ErrInvalidTransition, from, to)
	}
	return nil
}

type CommitRequest struct {
	PublicationID   identity.PublicationID      `json:"publication_id"`
	DownloadID      identity.DownloadID         `json:"download_id"`
	Artifact        identity.ArtifactGeneration `json:"artifact_generation"`
	IdempotencyKey  string                      `json:"idempotency_key"`
	StagingIdentity string                      `json:"staging_identity"`
}

func (r CommitRequest) Validate() error {
	if r.PublicationID.IsZero() || r.DownloadID.IsZero() || !r.Artifact.Valid() || strings.TrimSpace(r.IdempotencyKey) == "" || strings.TrimSpace(r.StagingIdentity) == "" {
		return ErrInvalidRequest
	}
	return nil
}

type InspectRequest struct {
	PublicationID  identity.PublicationID `json:"publication_id"`
	IdempotencyKey string                 `json:"idempotency_key"`
	ReceiptHint    string                 `json:"receipt_hint,omitempty"`
}

func (r InspectRequest) Validate() error {
	if r.PublicationID.IsZero() || strings.TrimSpace(r.IdempotencyKey) == "" {
		return ErrInvalidRequest
	}
	return nil
}

type Receipt struct {
	PublicationID identity.PublicationID `json:"publication_id"`
	ReceiptID     string                 `json:"receipt_id"`
	Location      string                 `json:"location"`
}

func (r Receipt) Validate() error {
	if r.PublicationID.IsZero() || strings.TrimSpace(r.ReceiptID) == "" || strings.TrimSpace(r.Location) == "" {
		return ErrInvalidReceipt
	}
	return nil
}

type ReconcileAction string

const (
	ActionRequestCommit  ReconcileAction = "request_commit"
	ActionInspectReceipt ReconcileAction = "inspect_receipt"
	ActionCommitEngine   ReconcileAction = "commit_engine"
	ActionCleanup        ReconcileAction = "cleanup"
	ActionNone           ReconcileAction = "none"
)

func ActionFor(state State) ReconcileAction {
	switch state {
	case Prepared:
		return ActionRequestCommit
	case PlatformCommitRequested:
		return ActionInspectReceipt
	case PlatformCommitted:
		return ActionCommitEngine
	case EngineCommitted:
		return ActionCleanup
	case Cleaned:
		return ActionNone
	default:
		return ActionNone
	}
}
