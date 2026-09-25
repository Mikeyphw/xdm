package scheduler

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"sort"
	"strings"
	"time"

	"github.com/subhra74/xdm/engine/domain/identity"
	"github.com/subhra74/xdm/engine/store/sqlite"
)

var (
	ErrInvalidBandwidthPolicy  = errors.New("invalid bandwidth policy")
	ErrInvalidCompletionPolicy = errors.New("invalid completion policy")
)

type BandwidthScope string

const (
	BandwidthScopeNone     BandwidthScope = "none"
	BandwidthScopeGlobal   BandwidthScope = "global"
	BandwidthScopeSchedule BandwidthScope = "schedule"
	BandwidthScopeQueue    BandwidthScope = "queue"
	BandwidthScopeDownload BandwidthScope = "download"
)

type BandwidthLimit struct {
	BytesPerSecond int64  `json:"bytes_per_second"`
	Profile        string `json:"profile,omitempty"`
}

type BandwidthPolicy struct {
	Global   BandwidthLimit `json:"global,omitempty"`
	Schedule BandwidthLimit `json:"schedule,omitempty"`
	Queue    BandwidthLimit `json:"queue,omitempty"`
	Download BandwidthLimit `json:"download,omitempty"`
}

type BandwidthLimitDecision struct {
	Scope          BandwidthScope `json:"scope"`
	BytesPerSecond int64          `json:"bytes_per_second"`
	Profile        string         `json:"profile,omitempty"`
	Explanation    string         `json:"explanation,omitempty"`
}

func ResolveBandwidthLimit(policy BandwidthPolicy) BandwidthLimitDecision {
	// Narrower scopes override broader scopes: global -> schedule -> queue -> download.
	decision := BandwidthLimitDecision{Scope: BandwidthScopeNone, Explanation: "no limit"}
	for _, candidate := range []struct {
		scope BandwidthScope
		limit BandwidthLimit
	}{
		{BandwidthScopeGlobal, policy.Global},
		{BandwidthScopeSchedule, policy.Schedule},
		{BandwidthScopeQueue, policy.Queue},
		{BandwidthScopeDownload, policy.Download},
	} {
		if candidate.limit.BytesPerSecond > 0 || candidate.limit.Profile != "" {
			decision = BandwidthLimitDecision{Scope: candidate.scope, BytesPerSecond: candidate.limit.BytesPerSecond, Profile: candidate.limit.Profile, Explanation: fmt.Sprintf("%s scope selected", candidate.scope)}
		}
	}
	return decision
}

func ValidateBandwidthPolicy(policy BandwidthPolicy) error {
	for scope, limit := range map[BandwidthScope]BandwidthLimit{
		BandwidthScopeGlobal: policy.Global, BandwidthScopeSchedule: policy.Schedule, BandwidthScopeQueue: policy.Queue, BandwidthScopeDownload: policy.Download,
	} {
		if limit.BytesPerSecond < 0 {
			return fmt.Errorf("%w: %s negative limit", ErrInvalidBandwidthPolicy, scope)
		}
		if strings.ContainsAny(limit.Profile, "\r\n\x00") {
			return fmt.Errorf("%w: %s profile", ErrInvalidBandwidthPolicy, scope)
		}
	}
	return nil
}

type CompletionActionKind string

const (
	CompletionActionNotify       CompletionActionKind = "notify"
	CompletionActionOpenArtifact CompletionActionKind = "open_artifact"
	CompletionActionShutdownHost CompletionActionKind = "shutdown_host"
	CompletionActionDeleteSource CompletionActionKind = "delete_source"
)

type TerminalOutcome string

const (
	TerminalSucceeded TerminalOutcome = "succeeded"
	TerminalFailed    TerminalOutcome = "failed"
	TerminalCanceled  TerminalOutcome = "canceled"
)

type CompletionActionPolicy struct {
	ActionKind       CompletionActionKind `json:"action_kind"`
	When             []TerminalOutcome    `json:"when"`
	RequiresApproval bool                 `json:"requires_approval"`
	HostApproved     bool                 `json:"host_approved"`
	Destructive      bool                 `json:"destructive"`
}

type CompletionEvent struct {
	DownloadID         identity.DownloadID `json:"download_id"`
	ArtifactGeneration int64               `json:"artifact_generation"`
	Outcome            TerminalOutcome     `json:"outcome"`
	TerminalAtUnixMS   int64               `json:"terminal_at_unix_ms"`
}

type CompletionActionStatus string

const (
	CompletionActionPending  CompletionActionStatus = "pending"
	CompletionActionAccepted CompletionActionStatus = "accepted"
	CompletionActionDeclined CompletionActionStatus = "declined"
	CompletionActionSkipped  CompletionActionStatus = "skipped"
)

type CompletionActionDecision struct {
	ShouldFire     bool                   `json:"should_fire"`
	IdempotencyKey string                 `json:"idempotency_key"`
	ActionKind     CompletionActionKind   `json:"action_kind"`
	Status         CompletionActionStatus `json:"status"`
	Explanation    string                 `json:"explanation"`
}

type CompletionActionRecord struct {
	IdempotencyKey  string                 `json:"idempotency_key"`
	DownloadID      identity.DownloadID    `json:"download_id"`
	ActionKind      CompletionActionKind   `json:"action_kind"`
	Status          CompletionActionStatus `json:"status"`
	HostReceipt     string                 `json:"host_receipt,omitempty"`
	Revision        identity.Revision      `json:"revision"`
	UpdatedAtUnixMS int64                  `json:"updated_at_unix_ms"`
}

func DecideCompletionAction(policy CompletionActionPolicy, event CompletionEvent, existing map[string]CompletionActionRecord) (CompletionActionDecision, error) {
	if err := validateCompletionPolicy(policy); err != nil {
		return CompletionActionDecision{}, err
	}
	if event.DownloadID.IsZero() || event.ArtifactGeneration <= 0 {
		return CompletionActionDecision{}, ErrInvalidCompletionPolicy
	}
	key := CompletionActionIdempotencyKey(event, policy.ActionKind)
	decision := CompletionActionDecision{IdempotencyKey: key, ActionKind: policy.ActionKind, Status: CompletionActionSkipped, Explanation: "terminal outcome not selected"}
	if existing != nil {
		if prior, ok := existing[key]; ok {
			decision.Status = prior.Status
			decision.Explanation = "already recorded"
			return decision, nil
		}
	}
	matched := false
	for _, outcome := range policy.When {
		if outcome == event.Outcome {
			matched = true
			break
		}
	}
	if !matched {
		return decision, nil
	}
	if policy.Destructive && (!policy.RequiresApproval || !policy.HostApproved) {
		decision.Explanation = "destructive action requires host approval"
		return decision, nil
	}
	if policy.RequiresApproval && !policy.HostApproved {
		decision.Explanation = "host approval required"
		return decision, nil
	}
	decision.ShouldFire = true
	decision.Status = CompletionActionPending
	decision.Explanation = "emit platform completion request once"
	return decision, nil
}

func CompletionActionIdempotencyKey(event CompletionEvent, kind CompletionActionKind) string {
	raw := fmt.Sprintf("completion:%s:%d:%s:%s", event.DownloadID.String(), event.ArtifactGeneration, event.Outcome, kind)
	sum := sha256.Sum256([]byte(raw))
	return "completion_" + hex.EncodeToString(sum[:16])
}

func (s *Store) RecordCompletionAction(ctx context.Context, rec CompletionActionRecord) (CompletionActionRecord, error) {
	if s == nil || s.db == nil {
		return CompletionActionRecord{}, sqlite.ErrClosed
	}
	if rec.IdempotencyKey == "" || rec.DownloadID.IsZero() || rec.ActionKind == "" {
		return CompletionActionRecord{}, ErrInvalidCompletionPolicy
	}
	if rec.Status == "" {
		rec.Status = CompletionActionPending
	}
	one, _ := identity.NewRevision(1)
	if !rec.Revision.Valid() {
		rec.Revision = one
	}
	if rec.UpdatedAtUnixMS <= 0 {
		rec.UpdatedAtUnixMS = time.Now().UnixMilli()
	}
	data, err := json.Marshal(rec)
	if err != nil {
		return CompletionActionRecord{}, err
	}
	key := completionMetadataKey(rec.IdempotencyKey)
	changes, err := s.db.Exec(ctx, `INSERT INTO engine_metadata(key,value) VALUES(?,?) ON CONFLICT(key) DO NOTHING`, key, string(data))
	if err != nil {
		return CompletionActionRecord{}, err
	}
	if changes != 1 {
		return s.GetCompletionAction(ctx, rec.IdempotencyKey)
	}
	return rec, nil
}

func (s *Store) GetCompletionAction(ctx context.Context, idempotencyKey string) (CompletionActionRecord, error) {
	if s == nil || s.db == nil {
		return CompletionActionRecord{}, sqlite.ErrClosed
	}
	rows, err := s.db.Query(ctx, `SELECT value FROM engine_metadata WHERE key=?`, completionMetadataKey(idempotencyKey))
	if err != nil {
		return CompletionActionRecord{}, err
	}
	if len(rows) == 0 {
		return CompletionActionRecord{}, sqlite.ErrNotFound
	}
	var rec CompletionActionRecord
	if len(rows) != 1 || len(rows[0]) != 1 || rows[0][0].Kind != sqlite.Text {
		return CompletionActionRecord{}, fmt.Errorf("bad completion action metadata")
	}
	if err := json.Unmarshal([]byte(rows[0][0].Text), &rec); err != nil {
		return CompletionActionRecord{}, err
	}
	return rec, nil
}

func (s *Store) UpdateCompletionActionStatus(ctx context.Context, idempotencyKey string, status CompletionActionStatus, receipt string) (CompletionActionRecord, error) {
	rec, err := s.GetCompletionAction(ctx, idempotencyKey)
	if err != nil {
		return CompletionActionRecord{}, err
	}
	if status != CompletionActionAccepted && status != CompletionActionDeclined && status != CompletionActionPending {
		return CompletionActionRecord{}, ErrInvalidCompletionPolicy
	}
	next, err := rec.Revision.Next()
	if err != nil {
		return CompletionActionRecord{}, err
	}
	rec.Revision = next
	rec.Status = status
	rec.HostReceipt = receipt
	rec.UpdatedAtUnixMS = time.Now().UnixMilli()
	data, err := json.Marshal(rec)
	if err != nil {
		return CompletionActionRecord{}, err
	}
	_, err = s.db.Exec(ctx, `UPDATE engine_metadata SET value=? WHERE key=?`, string(data), completionMetadataKey(idempotencyKey))
	if err != nil {
		return CompletionActionRecord{}, err
	}
	return rec, nil
}

func completionMetadataKey(idempotencyKey string) string {
	return "scheduler.completion_action." + idempotencyKey
}

func validateCompletionPolicy(policy CompletionActionPolicy) error {
	switch policy.ActionKind {
	case CompletionActionNotify, CompletionActionOpenArtifact, CompletionActionShutdownHost, CompletionActionDeleteSource:
	default:
		return ErrInvalidCompletionPolicy
	}
	if len(policy.When) == 0 {
		return ErrInvalidCompletionPolicy
	}
	seen := map[TerminalOutcome]bool{}
	for _, outcome := range policy.When {
		switch outcome {
		case TerminalSucceeded, TerminalFailed, TerminalCanceled:
		default:
			return ErrInvalidCompletionPolicy
		}
		if seen[outcome] {
			return ErrInvalidCompletionPolicy
		}
		seen[outcome] = true
	}
	if policy.Destructive && !policy.RequiresApproval {
		return ErrInvalidCompletionPolicy
	}
	return nil
}

func CanonicalCompletionOutcomes(outcomes []TerminalOutcome) []TerminalOutcome {
	out := append([]TerminalOutcome(nil), outcomes...)
	sort.Slice(out, func(i, j int) bool { return out[i] < out[j] })
	return out
}
