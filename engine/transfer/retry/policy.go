package retry

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/subhra74/xdm/engine/domain/failure"
	"github.com/subhra74/xdm/engine/domain/identity"
	store "github.com/subhra74/xdm/engine/store/sqlite"
)

var ErrInvalidRetryInput = errors.New("invalid retry policy input")

type DecisionKind string

const (
	RetryNow DecisionKind = "retry_now"
	RetryAt  DecisionKind = "retry_at"
	Hold     DecisionKind = "hold"
	Terminal DecisionKind = "terminal"
)

type Decision struct {
	Kind          DecisionKind `json:"kind"`
	RetryAtUnixMS int64        `json:"retry_at_unix_ms,omitempty"`
	Reason        string       `json:"reason"`
}

type QueuePolicy struct {
	Enabled     bool
	MaxAttempts int
	BaseDelay   time.Duration
	MaxDelay    time.Duration
}

type Input struct {
	Failure          failure.Failure
	AttemptCount     int
	RetryAfter       string
	Queue            QueuePolicy
	NetworkAvailable bool
	Replayable       bool
	UserOverride     bool
}

type Clock interface{ Now() time.Time }
type Jitter interface {
	Apply(time.Duration, int) time.Duration
}
type RealClock struct{}

func (RealClock) Now() time.Time { return time.Now() }

type NoJitter struct{}

func (NoJitter) Apply(d time.Duration, _ int) time.Duration { return d }

type DeterministicJitter struct{ Permille int64 }

func (j DeterministicJitter) Apply(d time.Duration, attempt int) time.Duration {
	// Deterministic signed sequence in [-permille,+permille] based on attempt.
	if j.Permille <= 0 {
		return d
	}
	span := 2*j.Permille + 1
	v := ((int64(attempt)*1103515245 + 12345) % span) - j.Permille
	delta := time.Duration((int64(d) * v) / 1000)
	out := d + delta
	if out < 0 {
		return 0
	}
	return out
}

type Engine struct {
	Clock  Clock
	Jitter Jitter
}

func ParseRetryAfter(raw string, now time.Time) (time.Time, bool) {
	raw = strings.TrimSpace(raw)
	if raw == "" {
		return time.Time{}, false
	}
	if seconds, err := strconv.ParseInt(raw, 10, 64); err == nil && seconds >= 0 {
		return now.Add(time.Duration(seconds) * time.Second), true
	}
	if t, err := http.ParseTime(raw); err == nil {
		return t, true
	}
	return time.Time{}, false
}

func backoff(q QueuePolicy, attempt int, j Jitter) time.Duration {
	base := q.BaseDelay
	if base <= 0 {
		base = time.Second
	}
	max := q.MaxDelay
	if max <= 0 {
		max = time.Minute
	}
	shift := attempt - 1
	if shift < 0 {
		shift = 0
	}
	if shift > 20 {
		shift = 20
	}
	d := base * time.Duration(int64(1)<<shift)
	if d > max {
		d = max
	}
	if j != nil {
		d = j.Apply(d, attempt)
	}
	if d > max {
		d = max
	}
	return d
}

func (e Engine) Decide(in Input) (Decision, error) {
	if err := in.Failure.Validate(); err != nil || in.AttemptCount < 1 {
		return Decision{}, ErrInvalidRetryInput
	}
	if e.Clock == nil {
		e.Clock = RealClock{}
	}
	if e.Jitter == nil {
		e.Jitter = NoJitter{}
	}
	now := e.Clock.Now()
	if in.UserOverride {
		if !in.Replayable && in.Failure.Retry != failure.RetryNever {
			return Decision{Kind: Terminal, Reason: "request_not_replayable"}, nil
		}
		switch in.Failure.Category {
		case failure.Cancelled, failure.InvalidRequest, failure.IntegrityFailure, failure.RepresentationChanged, failure.RangeContradiction, failure.TLSFailure:
			return Decision{Kind: Terminal, Reason: "failure_requires_user_change"}, nil
		}
		if !in.NetworkAvailable {
			return Decision{Kind: Hold, Reason: "network_unavailable"}, nil
		}
		return Decision{Kind: RetryNow, Reason: "user_override"}, nil
	}
	if !in.Queue.Enabled {
		return Decision{Kind: Hold, Reason: "queue_policy_disabled"}, nil
	}
	if in.Queue.MaxAttempts > 0 && in.AttemptCount >= in.Queue.MaxAttempts {
		return Decision{Kind: Terminal, Reason: "attempt_limit"}, nil
	}
	if !in.Replayable && in.Failure.Retry != failure.RetryNever && in.Failure.Retry != failure.RetryHold {
		return Decision{Kind: Terminal, Reason: "request_not_replayable"}, nil
	}
	if !in.NetworkAvailable {
		switch in.Failure.Retry {
		case failure.RetryNow, failure.RetryBackoff, failure.RetryAtTime:
			return Decision{Kind: Hold, Reason: "network_unavailable"}, nil
		}
	}
	switch in.Failure.Retry {
	case failure.RetryNever:
		return Decision{Kind: Terminal, Reason: "failure_terminal"}, nil
	case failure.RetryHold:
		return Decision{Kind: Hold, Reason: "failure_requires_condition"}, nil
	case failure.RetryNow:
		return Decision{Kind: RetryNow, Reason: "failure_retry_now"}, nil
	case failure.RetryAtTime:
		if at, ok := ParseRetryAfter(in.RetryAfter, now); ok {
			if at.Before(now) {
				at = now
			}
			return Decision{Kind: RetryAt, RetryAtUnixMS: at.UnixMilli(), Reason: "retry_after"}, nil
		}
		d := backoff(in.Queue, in.AttemptCount, e.Jitter)
		return Decision{Kind: RetryAt, RetryAtUnixMS: now.Add(d).UnixMilli(), Reason: "retry_after_missing_backoff"}, nil
	case failure.RetryBackoff:
		d := backoff(in.Queue, in.AttemptCount, e.Jitter)
		return Decision{Kind: RetryAt, RetryAtUnixMS: now.Add(d).UnixMilli(), Reason: "exponential_backoff"}, nil
	default:
		return Decision{}, ErrInvalidRetryInput
	}
}

type Persisted struct {
	SchemaVersion    int              `json:"schema_version"`
	FailureCategory  failure.Category `json:"failure_category"`
	AttemptCount     int              `json:"attempt_count"`
	Decision         Decision         `json:"decision"`
	RecordedAtUnixMS int64            `json:"recorded_at_unix_ms"`
}

func Persist(ctx context.Context, repo *store.Repository, id identity.DownloadID, generation identity.AttemptGeneration, expected identity.Revision, f failure.Failure, attemptCount int, decision Decision, now time.Time) (Persisted, store.AttemptRecord, error) {
	if repo == nil || id.IsZero() || !generation.Valid() || !expected.Valid() || attemptCount < 1 {
		return Persisted{}, store.AttemptRecord{}, ErrInvalidRetryInput
	}
	rec := Persisted{SchemaVersion: 1, FailureCategory: f.Category, AttemptCount: attemptCount, Decision: decision, RecordedAtUnixMS: now.UnixMilli()}
	raw, err := json.Marshal(rec)
	if err != nil {
		return Persisted{}, store.AttemptRecord{}, err
	}
	updated, err := repo.StoreAttemptRetryPayload(ctx, id, generation, expected, string(f.Category), string(raw), now.UnixMilli())
	if err != nil {
		return Persisted{}, store.AttemptRecord{}, err
	}
	return rec, updated, nil
}

func Load(ctx context.Context, repo *store.Repository, id identity.DownloadID, generation identity.AttemptGeneration) (Persisted, store.AttemptRecord, error) {
	if repo == nil {
		return Persisted{}, store.AttemptRecord{}, ErrInvalidRetryInput
	}
	attempt, err := repo.GetAttempt(ctx, id, generation)
	if err != nil {
		return Persisted{}, store.AttemptRecord{}, err
	}
	if attempt.FailurePayload == "" {
		return Persisted{}, attempt, store.ErrNotFound
	}
	var rec Persisted
	if err = json.Unmarshal([]byte(attempt.FailurePayload), &rec); err != nil {
		return Persisted{}, attempt, fmt.Errorf("decode retry payload: %w", err)
	}
	if rec.SchemaVersion != 1 {
		return Persisted{}, attempt, ErrInvalidRetryInput
	}
	return rec, attempt, nil
}

func FailureForHTTPStatus(status int) (failure.Failure, error) {
	category := failure.NetworkUnavailable
	switch status {
	case http.StatusTooManyRequests:
		category = failure.RateLimited
	case http.StatusUnauthorized, http.StatusForbidden:
		category = failure.AuthenticationRequired
	case http.StatusInternalServerError, http.StatusBadGateway, http.StatusServiceUnavailable, http.StatusGatewayTimeout:
		category = failure.NetworkUnavailable
	default:
		if status >= 400 && status < 500 {
			category = failure.InvalidRequest
		}
	}
	return failure.NewDefault(category, fmt.Errorf("http status %d", status))
}
