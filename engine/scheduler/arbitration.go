package scheduler

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"sort"
	"time"

	"github.com/subhra74/xdm/engine/domain/identity"
	"github.com/subhra74/xdm/engine/store/sqlite"
)

var (
	ErrInvalidSchedulerInput = errors.New("invalid scheduler input")
	ErrInvalidGlobalPause    = errors.New("invalid global pause")
)

type SchedulerHoldReason string

const (
	SchedulerHoldGlobalPause       SchedulerHoldReason = "global_pause"
	SchedulerHoldQueueDisabled     SchedulerHoldReason = "queue_disabled"
	SchedulerHoldQueueCapacity     SchedulerHoldReason = "queue_capacity"
	SchedulerHoldGlobalCapacity    SchedulerHoldReason = "global_capacity"
	SchedulerHoldHostCapacity      SchedulerHoldReason = "host_capacity"
	SchedulerHoldRetryNotDue       SchedulerHoldReason = "retry_not_due"
	SchedulerHoldDependencyBlocked SchedulerHoldReason = "dependency_blocked"
	SchedulerHoldRuntimeCondition  SchedulerHoldReason = "runtime_condition"
)

type RetryActivation struct {
	DueAt     time.Time `json:"due_at,omitempty"`
	Attempt   int       `json:"attempt,omitempty"`
	LastError string    `json:"last_error,omitempty"`
}

type QueueRuntimeEvaluation struct {
	Eligible bool         `json:"eligible"`
	Holds    []HoldReason `json:"holds,omitempty"`
}

type WorkItem struct {
	DownloadID       identity.DownloadID    `json:"download_id"`
	QueueID          identity.QueueID       `json:"queue_id"`
	Host             string                 `json:"host,omitempty"`
	QueuePriority    int                    `json:"queue_priority"`
	DownloadPriority int                    `json:"download_priority"`
	QueuePosition    int                    `json:"queue_position"`
	EnqueuedAt       time.Time              `json:"enqueued_at"`
	RemainingBytes   int64                  `json:"remaining_bytes,omitempty"`
	QueueEnabled     bool                   `json:"queue_enabled"`
	QueueConcurrency int                    `json:"queue_concurrency"`
	Dependency       Eligibility            `json:"dependency"`
	Retry            RetryActivation        `json:"retry,omitempty"`
	Runtime          QueueRuntimeEvaluation `json:"runtime,omitempty"`
	Bandwidth        BandwidthPolicy        `json:"bandwidth,omitempty"`
}

type CapacitySnapshot struct {
	GlobalLimit   int                      `json:"global_limit"`
	ActiveGlobal  int                      `json:"active_global"`
	PerHostLimit  map[string]int           `json:"per_host_limit,omitempty"`
	ActiveByHost  map[string]int           `json:"active_by_host,omitempty"`
	ActiveByQueue map[identity.QueueID]int `json:"active_by_queue,omitempty"`
}

type GlobalPauseRecord struct {
	Enabled         bool              `json:"enabled"`
	Reason          string            `json:"reason,omitempty"`
	Revision        identity.Revision `json:"revision"`
	UpdatedAtUnixMS int64             `json:"updated_at_unix_ms"`
}

type SchedulerInput struct {
	Now         time.Time         `json:"now"`
	Items       []WorkItem        `json:"items"`
	Capacity    CapacitySnapshot  `json:"capacity"`
	GlobalPause GlobalPauseRecord `json:"global_pause"`
	Fairness    FairnessPolicy    `json:"fairness"`
}

type FairnessPolicy struct {
	AgingInterval time.Duration `json:"aging_interval"`
	AgingBoost    int           `json:"aging_boost"`
}

type SchedulerCandidate struct {
	DownloadID      identity.DownloadID    `json:"download_id"`
	QueueID         identity.QueueID       `json:"queue_id"`
	Eligible        bool                   `json:"eligible"`
	Holds           []SchedulerHoldReason  `json:"holds,omitempty"`
	DependencyHolds []BlockedReason        `json:"dependency_holds,omitempty"`
	RuntimeHolds    []HoldReason           `json:"runtime_holds,omitempty"`
	Score           int64                  `json:"score"`
	Explanation     string                 `json:"explanation"`
	EffectiveLimit  BandwidthLimitDecision `json:"effective_limit,omitempty"`
}

type SchedulerDecision struct {
	Runnable    []SchedulerCandidate `json:"runnable"`
	Blocked     []SchedulerCandidate `json:"blocked"`
	AtUnixMS    int64                `json:"at_unix_ms"`
	Explanation string               `json:"explanation"`
}

const globalPauseMetadataKey = "scheduler.global_pause"

func (s *Store) SetGlobalPause(ctx context.Context, expected identity.Revision, rec GlobalPauseRecord) (GlobalPauseRecord, error) {
	if s == nil || s.db == nil {
		return GlobalPauseRecord{}, sqlite.ErrClosed
	}
	if !expected.Valid() {
		return GlobalPauseRecord{}, ErrInvalidGlobalPause
	}
	current, err := s.GetGlobalPause(ctx)
	if err != nil && !errors.Is(err, sqlite.ErrNotFound) {
		return GlobalPauseRecord{}, err
	}
	if errors.Is(err, sqlite.ErrNotFound) {
		one, _ := identity.NewRevision(1)
		current = GlobalPauseRecord{Revision: one}
	}
	if current.Revision != expected {
		return GlobalPauseRecord{}, sqlite.ErrStaleWrite
	}
	next, err := expected.Next()
	if err != nil {
		return GlobalPauseRecord{}, err
	}
	rec.Revision = next
	if rec.UpdatedAtUnixMS <= 0 {
		rec.UpdatedAtUnixMS = time.Now().UnixMilli()
	}
	data, err := json.Marshal(rec)
	if err != nil {
		return GlobalPauseRecord{}, err
	}
	_, err = s.db.Exec(ctx, `INSERT INTO engine_metadata(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value`, globalPauseMetadataKey, string(data))
	if err != nil {
		return GlobalPauseRecord{}, err
	}
	return rec, nil
}

func (s *Store) GetGlobalPause(ctx context.Context) (GlobalPauseRecord, error) {
	if s == nil || s.db == nil {
		return GlobalPauseRecord{}, sqlite.ErrClosed
	}
	rows, err := s.db.Query(ctx, `SELECT value FROM engine_metadata WHERE key=?`, globalPauseMetadataKey)
	if err != nil {
		return GlobalPauseRecord{}, err
	}
	if len(rows) == 0 {
		return GlobalPauseRecord{}, sqlite.ErrNotFound
	}
	if len(rows) != 1 || len(rows[0]) != 1 || rows[0][0].Kind != sqlite.Text {
		return GlobalPauseRecord{}, fmt.Errorf("bad global pause metadata")
	}
	var rec GlobalPauseRecord
	if err := json.Unmarshal([]byte(rows[0][0].Text), &rec); err != nil {
		return GlobalPauseRecord{}, err
	}
	if !rec.Revision.Valid() {
		return GlobalPauseRecord{}, ErrInvalidGlobalPause
	}
	return rec, nil
}

func EvaluateScheduler(input SchedulerInput) (SchedulerDecision, error) {
	now := input.Now
	if now.IsZero() {
		now = time.Now().UTC()
	}
	if input.Capacity.GlobalLimit < 0 || input.Capacity.ActiveGlobal < 0 {
		return SchedulerDecision{}, ErrInvalidSchedulerInput
	}
	if input.Fairness.AgingInterval < 0 || input.Fairness.AgingBoost < 0 {
		return SchedulerDecision{}, ErrInvalidSchedulerInput
	}
	if input.Fairness.AgingInterval == 0 {
		input.Fairness.AgingInterval = time.Hour
	}
	if input.Fairness.AgingBoost == 0 {
		input.Fairness.AgingBoost = 1
	}
	decision := SchedulerDecision{AtUnixMS: now.UnixMilli()}
	queueRunnable := map[identity.QueueID]int{}
	hostRunnable := map[string]int{}
	runnableCount := 0
	items := append([]WorkItem(nil), input.Items...)
	sort.Slice(items, func(i, j int) bool { return stableWorkOrder(items[i], items[j]) })
	for _, item := range items {
		candidate := evaluateCandidate(item, input, now, queueRunnable, hostRunnable, runnableCount)
		if candidate.Eligible {
			decision.Runnable = append(decision.Runnable, candidate)
			queueRunnable[item.QueueID]++
			if item.Host != "" {
				hostRunnable[item.Host]++
			}
			runnableCount++
		} else {
			decision.Blocked = append(decision.Blocked, candidate)
		}
	}
	sort.Slice(decision.Runnable, func(i, j int) bool {
		a, b := decision.Runnable[i], decision.Runnable[j]
		if a.Score != b.Score {
			return a.Score > b.Score
		}
		if a.QueueID != b.QueueID {
			return a.QueueID.String() < b.QueueID.String()
		}
		return a.DownloadID.String() < b.DownloadID.String()
	})
	sort.Slice(decision.Blocked, func(i, j int) bool {
		a, b := decision.Blocked[i], decision.Blocked[j]
		if a.QueueID != b.QueueID {
			return a.QueueID.String() < b.QueueID.String()
		}
		return a.DownloadID.String() < b.DownloadID.String()
	})
	decision.Explanation = fmt.Sprintf("%d runnable, %d blocked", len(decision.Runnable), len(decision.Blocked))
	return decision, nil
}

func evaluateCandidate(item WorkItem, input SchedulerInput, now time.Time, queueRunnable map[identity.QueueID]int, hostRunnable map[string]int, plannedGlobal int) SchedulerCandidate {
	c := SchedulerCandidate{DownloadID: item.DownloadID, QueueID: item.QueueID, Eligible: true, EffectiveLimit: ResolveBandwidthLimit(item.Bandwidth)}
	if item.DownloadID.IsZero() || item.QueueID.IsZero() {
		c.Eligible = false
		c.Holds = append(c.Holds, SchedulerHoldDependencyBlocked)
		c.Explanation = "invalid identity"
		return c
	}
	if input.GlobalPause.Enabled {
		c.Holds = append(c.Holds, SchedulerHoldGlobalPause)
	}
	if !item.QueueEnabled {
		c.Holds = append(c.Holds, SchedulerHoldQueueDisabled)
	}
	if item.QueueConcurrency > 0 && input.Capacity.ActiveByQueue[item.QueueID]+queueRunnable[item.QueueID] >= item.QueueConcurrency {
		c.Holds = append(c.Holds, SchedulerHoldQueueCapacity)
	}
	if input.Capacity.GlobalLimit > 0 && input.Capacity.ActiveGlobal+plannedGlobal >= input.Capacity.GlobalLimit {
		c.Holds = append(c.Holds, SchedulerHoldGlobalCapacity)
	}
	if item.Host != "" && input.Capacity.PerHostLimit != nil {
		if limit := input.Capacity.PerHostLimit[item.Host]; limit > 0 && input.Capacity.ActiveByHost[item.Host]+hostRunnable[item.Host] >= limit {
			c.Holds = append(c.Holds, SchedulerHoldHostCapacity)
		}
	}
	if !item.Retry.DueAt.IsZero() && item.Retry.DueAt.After(now) {
		c.Holds = append(c.Holds, SchedulerHoldRetryNotDue)
	}
	dep := item.Dependency
	if dep.DownloadID != "" && !dep.Eligible {
		c.Holds = append(c.Holds, SchedulerHoldDependencyBlocked)
		c.DependencyHolds = append([]BlockedReason(nil), dep.Blocked...)
	}
	if !item.Runtime.Eligible && len(item.Runtime.Holds) > 0 {
		c.Holds = append(c.Holds, SchedulerHoldRuntimeCondition)
		c.RuntimeHolds = append([]HoldReason(nil), item.Runtime.Holds...)
	}
	c.Eligible = len(c.Holds) == 0
	c.Score = schedulerScore(item, input.Fairness, now)
	if c.Eligible {
		c.Explanation = fmt.Sprintf("runnable score=%d", c.Score)
	} else {
		c.Explanation = fmt.Sprintf("blocked by %v", c.Holds)
	}
	return c
}

func schedulerScore(item WorkItem, fairness FairnessPolicy, now time.Time) int64 {
	ageBoost := int64(0)
	if !item.EnqueuedAt.IsZero() && fairness.AgingInterval > 0 {
		ageBoost = int64(now.Sub(item.EnqueuedAt)/fairness.AgingInterval) * int64(fairness.AgingBoost)
		if ageBoost < 0 {
			ageBoost = 0
		}
	}
	nearCompleteBoost := int64(0)
	if item.RemainingBytes > 0 && item.RemainingBytes <= 1<<20 {
		nearCompleteBoost = 5
	}
	return int64(item.QueuePriority*1_000_000+item.DownloadPriority*10_000-item.QueuePosition) + ageBoost + nearCompleteBoost
}

func stableWorkOrder(a, b WorkItem) bool {
	if a.QueuePriority != b.QueuePriority {
		return a.QueuePriority > b.QueuePriority
	}
	if a.QueueID != b.QueueID {
		return a.QueueID.String() < b.QueueID.String()
	}
	if a.QueuePosition != b.QueuePosition {
		return a.QueuePosition < b.QueuePosition
	}
	return a.DownloadID.String() < b.DownloadID.String()
}
