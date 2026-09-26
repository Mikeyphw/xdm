package androidhost

import (
	"errors"
	"strings"
	"sync"
	"time"

	"github.com/subhra74/xdm/engine/domain/identity"
	"github.com/subhra74/xdm/engine/scheduler"
)

var ErrInvalidWakeRequest = errors.New("invalid android wake request")

type AndroidExecutionPrimitive string

const (
	PrimitiveNone              AndroidExecutionPrimitive = "none"
	PrimitiveWorkManager       AndroidExecutionPrimitive = "workmanager"
	PrimitiveForegroundService AndroidExecutionPrimitive = "foreground_service"
	PrimitiveUserInitiatedData AndroidExecutionPrimitive = "user_initiated_data_transfer"
	PrimitiveBootReceiver      AndroidExecutionPrimitive = "boot_receiver"
)

// AndroidSchedulerWakeCommand is the narrow host-to-engine scheduler boundary.
// Android may describe why it woke the process and may preserve a retry deadline
// previously emitted by Go. It must not calculate queue eligibility or retry policy.
type AndroidSchedulerWakeCommand struct {
	EventID                   string                    `json:"event_id"`
	DownloadID                string                    `json:"download_id,omitempty"`
	Reason                    string                    `json:"reason"`
	EngineRetryDueAtEpochMS   int64                     `json:"engine_retry_due_at_epoch_ms,omitempty"`
	RequiresForeground        bool                      `json:"requires_foreground,omitempty"`
	UserInitiated             bool                      `json:"user_initiated,omitempty"`
	AfterBootOrPackageRestart bool                      `json:"after_boot_or_package_restart,omitempty"`
	Conditions                scheduler.ConditionPolicy `json:"conditions,omitempty"`
}

type AndroidExecutionRequest struct {
	DownloadID         string                    `json:"download_id,omitempty"`
	EventID            string                    `json:"event_id"`
	Reason             string                    `json:"reason"`
	Now                time.Time                 `json:"now"`
	RetryDueAt         time.Time                 `json:"retry_due_at,omitempty"`
	RequiresForeground bool                      `json:"requires_foreground"`
	UserInitiated      bool                      `json:"user_initiated"`
	AfterBootRestore   bool                      `json:"after_boot_restore"`
	Runtime            scheduler.RuntimeSnapshot `json:"runtime"`
	Conditions         scheduler.ConditionPolicy `json:"conditions"`
}

type AndroidWakeDecision struct {
	DownloadID                string                    `json:"download_id,omitempty"`
	Wake                      bool                      `json:"wake"`
	Primitive                 AndroidExecutionPrimitive `json:"primitive"`
	DelayUntil                time.Time                 `json:"delay_until,omitempty"`
	EngineRetryDueAt          time.Time                 `json:"engine_retry_due_at,omitempty"`
	EnginePolicyAuthoritative bool                      `json:"engine_policy_authoritative"`
	DuplicateSuppressed       bool                      `json:"duplicate_suppressed"`
	Holds                     []scheduler.HoldReason    `json:"holds,omitempty"`
	Reason                    string                    `json:"reason"`
}

type AndroidSchedulerHost struct {
	mu   sync.Mutex
	seen map[string]AndroidWakeDecision
}

func NewAndroidSchedulerHost() *AndroidSchedulerHost {
	return &AndroidSchedulerHost{seen: map[string]AndroidWakeDecision{}}
}

func (h *AndroidSchedulerHost) PlanExecutionOpportunity(req AndroidExecutionRequest) (AndroidWakeDecision, error) {
	if h == nil || strings.TrimSpace(req.EventID) == "" {
		return AndroidWakeDecision{}, ErrInvalidWakeRequest
	}
	if req.DownloadID != "" {
		if _, err := identity.ParseDownloadID(req.DownloadID); err != nil {
			return AndroidWakeDecision{}, ErrInvalidWakeRequest
		}
	}
	now := req.Now
	if now.IsZero() {
		now = time.Now().UTC()
	}
	if _, err := scheduler.EvaluateConditions(scheduler.ConditionPolicy{}, req.Runtime); err != nil {
		return AndroidWakeDecision{}, err
	}
	cond, err := scheduler.EvaluateConditions(req.Conditions, req.Runtime)
	if err != nil {
		return AndroidWakeDecision{}, err
	}

	h.mu.Lock()
	defer h.mu.Unlock()
	if prior, ok := h.seen[req.EventID]; ok {
		prior.DuplicateSuppressed = true
		prior.Wake = false
		prior.Primitive = PrimitiveNone
		prior.Reason = "duplicate Android host event suppressed; Go remains the only attempt authority"
		return prior, nil
	}

	decision := AndroidWakeDecision{
		DownloadID:                req.DownloadID,
		EnginePolicyAuthoritative: true,
		EngineRetryDueAt:          req.RetryDueAt,
		Reason:                    "Android supplied an execution opportunity; Go owns eligibility and retry policy",
	}
	if !cond.Eligible {
		decision.Wake = false
		decision.Primitive = PrimitiveNone
		decision.Holds = cond.Holds
		decision.Reason = "Android runtime conditions reached Go and the engine held this execution opportunity"
		h.seen[req.EventID] = decision
		return decision, nil
	}
	if !req.RetryDueAt.IsZero() && req.RetryDueAt.After(now) {
		decision.Wake = false
		decision.Primitive = PrimitiveWorkManager
		decision.DelayUntil = req.RetryDueAt
		decision.Reason = "Go preserved its retry deadline; Android may schedule only that deadline wake"
		h.seen[req.EventID] = decision
		return decision, nil
	}
	decision.Wake = true
	switch {
	case req.AfterBootRestore:
		decision.Primitive = PrimitiveBootReceiver
		decision.Reason = "boot/package restart restored the engine; Go now decides recovery and runnable work"
	case req.UserInitiated:
		decision.Primitive = PrimitiveUserInitiatedData
		decision.Reason = "UIDT is only an Android process-hosting primitive; Go owns transfer policy"
	case req.RequiresForeground:
		decision.Primitive = PrimitiveForegroundService
		decision.Reason = "foreground service is only an Android process-hosting primitive; Go owns transfer policy"
	default:
		decision.Primitive = PrimitiveWorkManager
		decision.Reason = "WorkManager woke the host and delegated scheduler authority to Go"
	}
	h.seen[req.EventID] = decision
	return decision, nil
}
