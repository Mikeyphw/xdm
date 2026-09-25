package scheduler

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/subhra74/xdm/engine/domain/identity"
	"github.com/subhra74/xdm/engine/store/sqlite"
)

func did(t *testing.T, text string) identity.DownloadID {
	t.Helper()
	id, err := identity.ParseDownloadID(text)
	if err != nil {
		t.Fatal(err)
	}
	return id
}

func TestSchedulerArbitrationFairnessRetryDependencyAndCapacity(t *testing.T) {
	now := time.Date(2026, 9, 25, 12, 0, 0, 0, time.UTC)
	qa := qid(t, "queue_11111111111111111111111111111111")
	qb := qid(t, "queue_22222222222222222222222222222222")
	oldLow := did(t, "dl_11111111111111111111111111111111")
	highNew := did(t, "dl_22222222222222222222222222222222")
	blockedDep := did(t, "dl_33333333333333333333333333333333")
	retryLater := did(t, "dl_44444444444444444444444444444444")
	hostBlocked := did(t, "dl_55555555555555555555555555555555")
	depID := did(t, "dl_66666666666666666666666666666666")
	decision, err := EvaluateScheduler(SchedulerInput{
		Now:      now,
		Capacity: CapacitySnapshot{GlobalLimit: 3, ActiveGlobal: 0, PerHostLimit: map[string]int{"a.example": 1, "sat.example": 1}, ActiveByHost: map[string]int{"sat.example": 1}, ActiveByQueue: map[identity.QueueID]int{qa: 0}},
		Fairness: FairnessPolicy{AgingInterval: time.Hour, AgingBoost: 50000},
		Items: []WorkItem{
			{DownloadID: highNew, QueueID: qa, Host: "a.example", QueuePriority: 10, DownloadPriority: 1, QueuePosition: 1, EnqueuedAt: now.Add(-time.Hour), QueueEnabled: true, QueueConcurrency: 2, Runtime: QueueRuntimeEvaluation{Eligible: true}, Dependency: Eligibility{DownloadID: highNew, Eligible: true}},
			{DownloadID: oldLow, QueueID: qb, Host: "b.example", QueuePriority: 1, DownloadPriority: 0, QueuePosition: 0, EnqueuedAt: now.Add(-48 * time.Hour), QueueEnabled: true, QueueConcurrency: 2, Runtime: QueueRuntimeEvaluation{Eligible: true}, Dependency: Eligibility{DownloadID: oldLow, Eligible: true}},
			{DownloadID: blockedDep, QueueID: qb, Host: "c.example", QueuePriority: 9, QueuePosition: 0, EnqueuedAt: now.Add(-time.Hour), QueueEnabled: true, QueueConcurrency: 2, Runtime: QueueRuntimeEvaluation{Eligible: true}, Dependency: Eligibility{DownloadID: blockedDep, Eligible: false, Blocked: []BlockedReason{{DependencyDownloadID: depID, Requirement: SuccessRequired, Outcome: OutcomeFailed, Reason: "dependency_failed"}}}},
			{DownloadID: retryLater, QueueID: qb, Host: "d.example", QueuePriority: 9, QueuePosition: 1, EnqueuedAt: now.Add(-time.Hour), QueueEnabled: true, QueueConcurrency: 2, Runtime: QueueRuntimeEvaluation{Eligible: true}, Dependency: Eligibility{DownloadID: retryLater, Eligible: true}, Retry: RetryActivation{DueAt: now.Add(time.Minute)}},
			{DownloadID: hostBlocked, QueueID: qb, Host: "sat.example", QueuePriority: 9, QueuePosition: 2, EnqueuedAt: now.Add(-time.Hour), QueueEnabled: true, QueueConcurrency: 2, Runtime: QueueRuntimeEvaluation{Eligible: true}, Dependency: Eligibility{DownloadID: hostBlocked, Eligible: true}},
		},
	})
	if err != nil {
		t.Fatal(err)
	}
	if len(decision.Runnable) != 2 {
		t.Fatalf("runnable=%+v blocked=%+v", decision.Runnable, decision.Blocked)
	}
	if decision.Runnable[0].DownloadID != highNew || decision.Runnable[1].DownloadID != oldLow {
		t.Fatalf("deterministic ranking/fairness mismatch: %+v", decision.Runnable)
	}
	want := map[identity.DownloadID]SchedulerHoldReason{blockedDep: SchedulerHoldDependencyBlocked, retryLater: SchedulerHoldRetryNotDue, hostBlocked: SchedulerHoldHostCapacity}
	for _, blocked := range decision.Blocked {
		if len(blocked.Holds) == 0 || blocked.Holds[0] != want[blocked.DownloadID] {
			t.Fatalf("bad block for %s: %+v", blocked.DownloadID, blocked)
		}
	}
	replay, err := EvaluateScheduler(SchedulerInput{Now: now, Capacity: CapacitySnapshot{GlobalLimit: 3, PerHostLimit: map[string]int{"a.example": 1, "sat.example": 1}, ActiveByHost: map[string]int{"sat.example": 1}}, Fairness: FairnessPolicy{AgingInterval: time.Hour, AgingBoost: 50000}, Items: []WorkItem{{DownloadID: highNew, QueueID: qa, Host: "a.example", QueuePriority: 10, DownloadPriority: 1, QueuePosition: 1, EnqueuedAt: now.Add(-time.Hour), QueueEnabled: true, QueueConcurrency: 2, Runtime: QueueRuntimeEvaluation{Eligible: true}, Dependency: Eligibility{DownloadID: highNew, Eligible: true}}, {DownloadID: oldLow, QueueID: qb, Host: "b.example", QueuePriority: 1, DownloadPriority: 0, QueuePosition: 0, EnqueuedAt: now.Add(-48 * time.Hour), QueueEnabled: true, QueueConcurrency: 2, Runtime: QueueRuntimeEvaluation{Eligible: true}, Dependency: Eligibility{DownloadID: oldLow, Eligible: true}}}})
	if err != nil {
		t.Fatal(err)
	}
	if replay.Runnable[0].DownloadID != decision.Runnable[0].DownloadID {
		t.Fatalf("replay not deterministic: %+v vs %+v", replay, decision)
	}
}

func TestFairnessAgingProvidesBoundedStarvationEscape(t *testing.T) {
	now := time.Date(2026, 9, 25, 12, 0, 0, 0, time.UTC)
	q := qid(t, "queue_99999999999999999999999999999999")
	old := did(t, "dl_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
	newer := did(t, "dl_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
	decision, err := EvaluateScheduler(SchedulerInput{
		Now: now,
		Items: []WorkItem{
			{DownloadID: newer, QueueID: q, QueueEnabled: true, QueueConcurrency: 2, QueuePriority: 10, QueuePosition: 0, EnqueuedAt: now.Add(-time.Hour), Dependency: Eligibility{DownloadID: newer, Eligible: true}, Runtime: QueueRuntimeEvaluation{Eligible: true}},
			{DownloadID: old, QueueID: q, QueueEnabled: true, QueueConcurrency: 2, QueuePriority: 1, QueuePosition: 1, EnqueuedAt: now.Add(-220 * time.Hour), Dependency: Eligibility{DownloadID: old, Eligible: true}, Runtime: QueueRuntimeEvaluation{Eligible: true}},
		},
		Fairness: FairnessPolicy{AgingInterval: time.Hour, AgingBoost: 50000},
	})
	if err != nil {
		t.Fatal(err)
	}
	if len(decision.Runnable) != 2 || decision.Runnable[0].DownloadID != old {
		t.Fatalf("fairness aging should eventually outrank fresh high-priority work: %+v", decision.Runnable)
	}
}

func TestGlobalPausePersistsAndBlocksScheduler(t *testing.T) {
	store, db, ctx := openSchedulerTestStore(t)
	defer db.Close()
	one := rev1(t)
	paused, err := store.SetGlobalPause(ctx, one, GlobalPauseRecord{Enabled: true, Reason: "maintenance"})
	if err != nil {
		t.Fatal(err)
	}
	loaded, err := store.GetGlobalPause(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if !loaded.Enabled || loaded.Revision != paused.Revision || loaded.Reason != "maintenance" {
		t.Fatalf("bad persisted pause: %+v", loaded)
	}
	if _, err := store.SetGlobalPause(ctx, one, GlobalPauseRecord{Enabled: false}); !errors.Is(err, sqlite.ErrStaleWrite) {
		t.Fatalf("expected stale pause write, got %v", err)
	}
	d := did(t, "dl_77777777777777777777777777777777")
	q := qid(t, "queue_77777777777777777777777777777777")
	decision, err := EvaluateScheduler(SchedulerInput{Now: time.Now(), GlobalPause: loaded, Items: []WorkItem{{DownloadID: d, QueueID: q, QueueEnabled: true, QueueConcurrency: 1, Dependency: Eligibility{DownloadID: d, Eligible: true}, Runtime: QueueRuntimeEvaluation{Eligible: true}}}})
	if err != nil {
		t.Fatal(err)
	}
	if len(decision.Blocked) != 1 || decision.Blocked[0].Holds[0] != SchedulerHoldGlobalPause {
		t.Fatalf("global pause did not block: %+v", decision)
	}
}

func TestBandwidthPrecedenceAndCompletionActionIdempotency(t *testing.T) {
	policy := BandwidthPolicy{Global: BandwidthLimit{BytesPerSecond: 100, Profile: "global"}, Schedule: BandwidthLimit{BytesPerSecond: 200, Profile: "night"}, Queue: BandwidthLimit{BytesPerSecond: 300, Profile: "queue"}, Download: BandwidthLimit{BytesPerSecond: 400, Profile: "download"}}
	if err := ValidateBandwidthPolicy(policy); err != nil {
		t.Fatal(err)
	}
	limit := ResolveBandwidthLimit(policy)
	if limit.Scope != BandwidthScopeDownload || limit.BytesPerSecond != 400 {
		t.Fatalf("bad precedence: %+v", limit)
	}
	d := did(t, "dl_88888888888888888888888888888888")
	event := CompletionEvent{DownloadID: d, ArtifactGeneration: 2, Outcome: TerminalSucceeded, TerminalAtUnixMS: time.Now().UnixMilli()}
	actionPolicy := CompletionActionPolicy{ActionKind: CompletionActionNotify, When: []TerminalOutcome{TerminalSucceeded}}
	decision, err := DecideCompletionAction(actionPolicy, event, nil)
	if err != nil {
		t.Fatal(err)
	}
	if !decision.ShouldFire || decision.IdempotencyKey == "" {
		t.Fatalf("expected action fire: %+v", decision)
	}
	store, db, ctx := openSchedulerTestStore(t)
	defer db.Close()
	rec, err := store.RecordCompletionAction(ctx, CompletionActionRecord{IdempotencyKey: decision.IdempotencyKey, DownloadID: d, ActionKind: CompletionActionNotify, Status: CompletionActionPending})
	if err != nil {
		t.Fatal(err)
	}
	again, err := store.RecordCompletionAction(ctx, CompletionActionRecord{IdempotencyKey: decision.IdempotencyKey, DownloadID: d, ActionKind: CompletionActionNotify, Status: CompletionActionPending})
	if err != nil {
		t.Fatal(err)
	}
	if again.Revision != rec.Revision {
		t.Fatalf("duplicate idempotency mutated record: first=%+v second=%+v", rec, again)
	}
	accepted, err := store.UpdateCompletionActionStatus(ctx, decision.IdempotencyKey, CompletionActionAccepted, "host-ok")
	if err != nil {
		t.Fatal(err)
	}
	if accepted.Status != CompletionActionAccepted || accepted.HostReceipt != "host-ok" {
		t.Fatalf("bad host reply: %+v", accepted)
	}
	skipped, err := DecideCompletionAction(actionPolicy, event, map[string]CompletionActionRecord{decision.IdempotencyKey: accepted})
	if err != nil {
		t.Fatal(err)
	}
	if skipped.ShouldFire || skipped.Status != CompletionActionAccepted {
		t.Fatalf("action should be once-only: %+v", skipped)
	}
	if destructive, err := DecideCompletionAction(CompletionActionPolicy{ActionKind: CompletionActionDeleteSource, When: []TerminalOutcome{TerminalSucceeded}, RequiresApproval: true, Destructive: true}, event, nil); err != nil || destructive.ShouldFire || destructive.Explanation == "" {
		t.Fatalf("destructive without approval should skip: decision=%+v err=%v", destructive, err)
	}
}
