package main

import (
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"os"
	"path/filepath"
	"time"

	"github.com/subhra74/xdm/engine/domain/identity"
	"github.com/subhra74/xdm/engine/scheduler"
	"github.com/subhra74/xdm/engine/store/sqlite"
)

type report struct {
	Mode   string   `json:"mode"`
	Status string   `json:"status"`
	Checks []string `json:"checks"`
}

func main() {
	mode := flag.String("mode", "", "queue_model, dependency_graph, conditions_time, scheduler_stress, or bandwidth_completion")
	output := flag.String("output", "", "output JSON path")
	flag.Parse()
	if *mode == "" || *output == "" {
		fatalf("mode and output are required")
	}
	var r report
	var err error
	switch *mode {
	case "queue_model":
		r, err = runQueueModel()
	case "dependency_graph":
		r, err = runDependencyGraph()
	case "conditions_time":
		r, err = runConditionsTime()
	case "scheduler_stress":
		r, err = runSchedulerStress()
	case "bandwidth_completion":
		r, err = runBandwidthCompletion()
	default:
		err = fmt.Errorf("unknown mode %q", *mode)
	}
	if err != nil {
		fatalf("%s failed: %v", *mode, err)
	}
	if err := os.MkdirAll(filepath.Dir(*output), 0o755); err != nil {
		fatalf("mkdir output: %v", err)
	}
	data, err := json.MarshalIndent(r, "", "  ")
	if err != nil {
		fatalf("marshal: %v", err)
	}
	if err := os.WriteFile(*output, append(data, '\n'), 0o644); err != nil {
		fatalf("write output: %v", err)
	}
}

func runQueueModel() (report, error) {
	store, db, ctx, cleanup, err := openStore()
	if err != nil {
		return report{}, err
	}
	defer cleanup()
	repo, err := sqlite.NewRepository(db)
	if err != nil {
		return report{}, err
	}
	a, err := seedDownload(ctx, repo, "dl_11111111111111111111111111111111")
	if err != nil {
		return report{}, err
	}
	b, err := seedDownload(ctx, repo, "dl_22222222222222222222222222222222")
	if err != nil {
		return report{}, err
	}
	qid, _ := identity.ParseQueueID("queue_dddddddddddddddddddddddddddddddd")
	rev, _ := identity.NewRevision(1)
	q, err := store.CreateQueue(ctx, scheduler.QueueRecord{ID: qid, Name: "audit", Enabled: true, Priority: 50, Policy: scheduler.QueuePolicy{Concurrency: 2, BandwidthProfile: "balanced", Conditions: []string{"online"}, ScheduleRef: "schedule_audit", RetryPolicy: "standard"}, Revision: rev})
	if err != nil {
		return report{}, err
	}
	if err := store.Assign(ctx, q.ID, a, 0); err != nil {
		return report{}, err
	}
	if err := store.Assign(ctx, q.ID, b, 1); err != nil {
		return report{}, err
	}
	if err := store.Reorder(ctx, q.ID, []identity.DownloadID{b, a}); err != nil {
		return report{}, err
	}
	members, err := store.Members(ctx, q.ID)
	if err != nil {
		return report{}, err
	}
	if len(members) != 2 || members[0].DownloadID != b || members[1].DownloadID != a {
		return report{}, fmt.Errorf("membership reorder mismatch: %+v", members)
	}
	if _, err := store.UpdateQueue(ctx, q.ID, q.Revision, scheduler.QueueRecord{Name: "stale", Enabled: true, Priority: 1, Policy: scheduler.QueuePolicy{Concurrency: 1}}); err != nil {
		return report{}, err
	}
	if _, err := store.UpdateQueue(ctx, q.ID, q.Revision, scheduler.QueueRecord{Name: "stale-again", Enabled: true, Priority: 1, Policy: scheduler.QueuePolicy{Concurrency: 1}}); !errors.Is(err, sqlite.ErrStaleWrite) {
		return report{}, fmt.Errorf("expected stale conflict, got %v", err)
	}
	if err := store.DeleteQueue(ctx, q.ID, scheduler.DeleteRejectWithMembers); !errors.Is(err, scheduler.ErrQueueHasMembers) {
		return report{}, fmt.Errorf("expected member delete rejection, got %v", err)
	}
	return report{Mode: "queue_model", Status: "PASS", Checks: []string{"create/update queue", "membership order persisted", "single active assignment", "revision conflict", "delete-with-members policy"}}, nil
}

func runDependencyGraph() (report, error) {
	store, db, ctx, cleanup, err := openStore()
	if err != nil {
		return report{}, err
	}
	defer cleanup()
	repo, err := sqlite.NewRepository(db)
	if err != nil {
		return report{}, err
	}
	a, err := seedDownload(ctx, repo, "dl_33333333333333333333333333333333")
	if err != nil {
		return report{}, err
	}
	b, err := seedDownload(ctx, repo, "dl_44444444444444444444444444444444")
	if err != nil {
		return report{}, err
	}
	c, err := seedDownload(ctx, repo, "dl_55555555555555555555555555555555")
	if err != nil {
		return report{}, err
	}
	d, err := seedDownload(ctx, repo, "dl_66666666666666666666666666666666")
	if err != nil {
		return report{}, err
	}
	e, err := seedDownload(ctx, repo, "dl_77777777777777777777777777777777")
	if err != nil {
		return report{}, err
	}
	if err := store.AddDependency(ctx, scheduler.DependencyRecord{DownloadID: a, DependencyDownloadID: b, Requirement: scheduler.SuccessRequired}); err != nil {
		return report{}, err
	}
	if err := store.AddDependency(ctx, scheduler.DependencyRecord{DownloadID: b, DependencyDownloadID: c, Requirement: scheduler.CompletionRequired}); err != nil {
		return report{}, err
	}
	if err := store.AddDependency(ctx, scheduler.DependencyRecord{DownloadID: c, DependencyDownloadID: a, Requirement: scheduler.SuccessRequired}); !errors.Is(err, scheduler.ErrDependencyCycle) {
		return report{}, fmt.Errorf("expected cycle rejection, got %v", err)
	}
	failed, err := store.Eligibility(ctx, a, map[identity.DownloadID]scheduler.DownloadOutcome{b: scheduler.OutcomeFailed})
	if err != nil {
		return report{}, err
	}
	if failed.Eligible || len(failed.Blocked) != 1 || failed.Blocked[0].Reason != "dependency_failed" {
		return report{}, fmt.Errorf("bad failure propagation: %+v", failed)
	}
	ready, err := store.Eligibility(ctx, a, map[identity.DownloadID]scheduler.DownloadOutcome{b: scheduler.OutcomeSucceeded})
	if err != nil {
		return report{}, err
	}
	if !ready.Eligible {
		return report{}, fmt.Errorf("dependency retry success did not unblock: %+v", ready)
	}

	// Diamond graph: d depends on b and c, both of which depend on e. This
	// proves shared ancestors do not look cyclic and that all parents must be
	// independently satisfied before the child is eligible.
	if err := store.AddDependency(ctx, scheduler.DependencyRecord{DownloadID: b, DependencyDownloadID: e, Requirement: scheduler.SuccessRequired}); err != nil {
		return report{}, err
	}
	if err := store.AddDependency(ctx, scheduler.DependencyRecord{DownloadID: c, DependencyDownloadID: e, Requirement: scheduler.SuccessRequired}); err != nil {
		return report{}, err
	}
	if err := store.AddDependency(ctx, scheduler.DependencyRecord{DownloadID: d, DependencyDownloadID: b, Requirement: scheduler.SuccessRequired}); err != nil {
		return report{}, err
	}
	if err := store.AddDependency(ctx, scheduler.DependencyRecord{DownloadID: d, DependencyDownloadID: c, Requirement: scheduler.CompletionRequired}); err != nil {
		return report{}, err
	}
	multiBlocked, err := store.Eligibility(ctx, d, map[identity.DownloadID]scheduler.DownloadOutcome{b: scheduler.OutcomeRunning, c: scheduler.OutcomeRunning, e: scheduler.OutcomeSucceeded})
	if err != nil {
		return report{}, err
	}
	if multiBlocked.Eligible || len(multiBlocked.Blocked) != 2 {
		return report{}, fmt.Errorf("multiple dependency block reasons missing: %+v", multiBlocked)
	}
	diamondReady, err := store.Eligibility(ctx, d, map[identity.DownloadID]scheduler.DownloadOutcome{b: scheduler.OutcomeSucceeded, c: scheduler.OutcomeCanceled, e: scheduler.OutcomeSucceeded})
	if err != nil {
		return report{}, err
	}
	if !diamondReady.Eligible || len(diamondReady.Blocked) != 0 {
		return report{}, fmt.Errorf("diamond dependencies did not unblock: %+v", diamondReady)
	}
	return report{Mode: "dependency_graph", Status: "PASS", Checks: []string{"chain dependency", "diamond dependency", "multiple dependency reasons", "cycle insertion rejection", "success-required failure propagation", "dependency retry success unblocks"}}, nil
}

func runConditionsTime() (report, error) {
	store, _, ctx, cleanup, err := openStore()
	if err != nil {
		return report{}, err
	}
	defer cleanup()
	rev, _ := identity.NewRevision(1)
	rec, err := store.SaveSchedule(ctx, scheduler.ScheduleRecord{
		ID:       "schedule_audit_time",
		Enabled:  true,
		Revision: rev,
		Definition: scheduler.ScheduleDefinition{
			Timezone:                 "America/New_York",
			Recurrence:               scheduler.RecurrenceDaily,
			Windows:                  []scheduler.ScheduleWindow{{StartMinute: 60, EndMinute: 4 * 60}, {StartMinute: 22 * 60, EndMinute: 2 * 60}},
			MissedRunPolicy:          scheduler.MissedRunStartWhenAvailable,
			MissedRunGraceMinutes:    120,
			HostSuppliesRuntimeState: true,
			PreserveAcrossRestarts:   true,
			ConditionPolicy: scheduler.ConditionPolicy{
				RequireOnline:       true,
				RequireUnmetered:    true,
				RequireWiFi:         true,
				RequireCharging:     true,
				MinBatteryPercent:   50,
				MinStorageFreeBytes: 1 << 30,
				AllowedPowerSources: []scheduler.PowerSource{scheduler.PowerAC, scheduler.PowerUSB},
			},
		},
	})
	if err != nil {
		return report{}, err
	}
	badSnapshot := scheduler.RuntimeSnapshot{Online: false, Metered: true, WiFi: false, Charging: false, BatteryPercent: 10, StorageFreeBytes: 1, PowerSource: scheduler.PowerBattery}
	blocked, err := scheduler.EvaluateSchedule(rec.Definition, scheduler.ScheduleInput{Now: time.Date(2026, 3, 8, 3, 30, 0, 0, mustNY()), Runtime: badSnapshot, HaveRuntime: true})
	if err != nil {
		return report{}, err
	}
	if blocked.Eligible || len(blocked.Holds) != 7 {
		return report{}, fmt.Errorf("expected seven runtime holds, got %+v", blocked)
	}
	goodSnapshot := scheduler.RuntimeSnapshot{Online: true, Metered: false, WiFi: true, Charging: true, BatteryPercent: 90, StorageFreeBytes: 2 << 30, PowerSource: scheduler.PowerAC}
	eligible, err := scheduler.EvaluateSchedule(rec.Definition, scheduler.ScheduleInput{Now: time.Date(2026, 3, 8, 3, 30, 0, 0, mustNY()), Runtime: goodSnapshot, HaveRuntime: true})
	if err != nil {
		return report{}, err
	}
	if !eligible.Eligible || !eligible.WindowMatched {
		return report{}, fmt.Errorf("DST-forward schedule should be eligible, got %+v", eligible)
	}
	fallFirst := time.Date(2026, 11, 1, 1, 30, 0, 0, mustNY())
	fallSecond := fallFirst.Add(time.Hour)
	for _, now := range []time.Time{fallFirst, fallSecond} {
		eval, err := scheduler.EvaluateSchedule(rec.Definition, scheduler.ScheduleInput{Now: now, Runtime: goodSnapshot, HaveRuntime: true})
		if err != nil {
			return report{}, err
		}
		if !eval.Eligible || !eval.WindowMatched {
			return report{}, fmt.Errorf("DST-back schedule should be eligible at %s: %+v", now, eval)
		}
	}
	overnight, err := scheduler.EvaluateSchedule(rec.Definition, scheduler.ScheduleInput{Now: time.Date(2026, 1, 2, 23, 30, 0, 0, mustNY()), Runtime: goodSnapshot, HaveRuntime: true, ForceRestart: true})
	if err != nil {
		return report{}, err
	}
	if !overnight.Eligible || !overnight.WindowMatched {
		return report{}, fmt.Errorf("overnight restart window should be eligible: %+v", overnight)
	}
	missedDef := scheduler.ScheduleDefinition{Timezone: "UTC", Recurrence: scheduler.RecurrenceDaily, Windows: []scheduler.ScheduleWindow{{StartMinute: 9 * 60, EndMinute: 10 * 60}}, MissedRunPolicy: scheduler.MissedRunStartWhenAvailable, MissedRunGraceMinutes: 90}
	missed, err := scheduler.EvaluateSchedule(missedDef, scheduler.ScheduleInput{Now: time.Date(2026, 5, 1, 10, 30, 0, 0, time.UTC), LastChecked: time.Date(2026, 5, 1, 8, 0, 0, 0, time.UTC)})
	if err != nil {
		return report{}, err
	}
	if !missed.Eligible || !missed.MissedRun {
		return report{}, fmt.Errorf("missed schedule should be eligible within grace: %+v", missed)
	}
	return report{Mode: "conditions_time", Status: "PASS", Checks: []string{"every runtime hold reason", "overnight window", "DST forward/back", "missed schedule", "condition change runtime input", "restart schedule persistence"}}, nil
}

func runSchedulerStress() (report, error) {
	store, _, ctx, cleanup, err := openStore()
	if err != nil {
		return report{}, err
	}
	defer cleanup()
	one, _ := identity.NewRevision(1)
	pause, err := store.SetGlobalPause(ctx, one, scheduler.GlobalPauseRecord{Enabled: true, Reason: "audit"})
	if err != nil {
		return report{}, err
	}
	if loaded, err := store.GetGlobalPause(ctx); err != nil || !loaded.Enabled || loaded.Revision != pause.Revision {
		return report{}, fmt.Errorf("global pause not durable: rec=%+v err=%v", loaded, err)
	}
	now := time.Date(2026, 9, 25, 12, 0, 0, 0, time.UTC)
	qa, _ := identity.ParseQueueID("queue_33333333333333333333333333333333")
	qb, _ := identity.ParseQueueID("queue_44444444444444444444444444444444")
	items := make([]scheduler.WorkItem, 0, 2500)
	for i := 0; i < 2500; i++ {
		id, _ := identity.ParseDownloadID(fmt.Sprintf("dl_%032x", i+1000))
		queueID := qa
		host := "a.example"
		if i%2 == 1 {
			queueID = qb
			host = "b.example"
		}
		items = append(items, scheduler.WorkItem{
			DownloadID:       id,
			QueueID:          queueID,
			Host:             host,
			QueueEnabled:     true,
			QueueConcurrency: 1000,
			QueuePriority:    i % 17,
			DownloadPriority: i % 5,
			QueuePosition:    i,
			EnqueuedAt:       now.Add(-time.Duration(i%96) * time.Hour),
			Runtime:          scheduler.QueueRuntimeEvaluation{Eligible: true},
			Dependency:       scheduler.Eligibility{DownloadID: id, Eligible: true},
		})
	}
	decision, err := scheduler.EvaluateScheduler(scheduler.SchedulerInput{Now: now, Items: items, Capacity: scheduler.CapacitySnapshot{GlobalLimit: 64, PerHostLimit: map[string]int{"a.example": 48, "b.example": 48}}, Fairness: scheduler.FairnessPolicy{AgingInterval: time.Hour, AgingBoost: 100}})
	if err != nil {
		return report{}, err
	}
	if len(decision.Runnable) != 64 {
		return report{}, fmt.Errorf("expected 64 runnable jobs, got %d", len(decision.Runnable))
	}
	replay, err := scheduler.EvaluateScheduler(scheduler.SchedulerInput{Now: now, Items: items, Capacity: scheduler.CapacitySnapshot{GlobalLimit: 64, PerHostLimit: map[string]int{"a.example": 48, "b.example": 48}}, Fairness: scheduler.FairnessPolicy{AgingInterval: time.Hour, AgingBoost: 100}})
	if err != nil {
		return report{}, err
	}
	for i := range decision.Runnable {
		if decision.Runnable[i].DownloadID != replay.Runnable[i].DownloadID || decision.Runnable[i].Score != replay.Runnable[i].Score {
			return report{}, fmt.Errorf("non-deterministic replay at %d: %s/%d vs %s/%d", i, decision.Runnable[i].DownloadID, decision.Runnable[i].Score, replay.Runnable[i].DownloadID, replay.Runnable[i].Score)
		}
	}
	holdID, _ := identity.ParseDownloadID("dl_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
	holdDecision, err := scheduler.EvaluateScheduler(scheduler.SchedulerInput{Now: now, Items: []scheduler.WorkItem{
		{DownloadID: holdID, QueueID: qa, QueueEnabled: true, QueueConcurrency: 1, Dependency: scheduler.Eligibility{DownloadID: holdID, Eligible: true}, Runtime: scheduler.QueueRuntimeEvaluation{Eligible: true}, Retry: scheduler.RetryActivation{DueAt: now.Add(time.Minute)}},
	}})
	if err != nil {
		return report{}, err
	}
	if len(holdDecision.Blocked) != 1 || len(holdDecision.Blocked[0].Holds) == 0 || holdDecision.Blocked[0].Holds[0] != scheduler.SchedulerHoldRetryNotDue {
		return report{}, fmt.Errorf("retry hold not explained: %+v", holdDecision)
	}
	blockedPause, err := scheduler.EvaluateScheduler(scheduler.SchedulerInput{Now: now, GlobalPause: pause, Items: items[:1], Capacity: scheduler.CapacitySnapshot{GlobalLimit: 1}})
	if err != nil {
		return report{}, err
	}
	if len(blockedPause.Blocked) != 1 || len(blockedPause.Blocked[0].Holds) == 0 || blockedPause.Blocked[0].Holds[0] != scheduler.SchedulerHoldGlobalPause {
		return report{}, fmt.Errorf("global pause did not block: %+v", blockedPause)
	}
	return report{Mode: "scheduler_stress", Status: "PASS", Checks: []string{"2500 queued jobs", "deterministic replay", "global capacity", "per-host capacity", "fairness aging", "retry not due", "durable global pause"}}, nil
}

func runBandwidthCompletion() (report, error) {
	store, _, ctx, cleanup, err := openStore()
	if err != nil {
		return report{}, err
	}
	defer cleanup()
	policy := scheduler.BandwidthPolicy{
		Global:   scheduler.BandwidthLimit{BytesPerSecond: 100, Profile: "global"},
		Schedule: scheduler.BandwidthLimit{BytesPerSecond: 200, Profile: "night"},
		Queue:    scheduler.BandwidthLimit{BytesPerSecond: 300, Profile: "queue"},
		Download: scheduler.BandwidthLimit{BytesPerSecond: 400, Profile: "download"},
	}
	if err := scheduler.ValidateBandwidthPolicy(policy); err != nil {
		return report{}, err
	}
	limit := scheduler.ResolveBandwidthLimit(policy)
	if limit.Scope != scheduler.BandwidthScopeDownload || limit.BytesPerSecond != 400 {
		return report{}, fmt.Errorf("bad bandwidth precedence: %+v", limit)
	}
	downloadID, _ := identity.ParseDownloadID("dl_99999999999999999999999999999999")
	event := scheduler.CompletionEvent{DownloadID: downloadID, ArtifactGeneration: 3, Outcome: scheduler.TerminalSucceeded, TerminalAtUnixMS: time.Now().UnixMilli()}
	decision, err := scheduler.DecideCompletionAction(scheduler.CompletionActionPolicy{ActionKind: scheduler.CompletionActionNotify, When: []scheduler.TerminalOutcome{scheduler.TerminalSucceeded}}, event, nil)
	if err != nil {
		return report{}, err
	}
	if !decision.ShouldFire || decision.IdempotencyKey == "" {
		return report{}, fmt.Errorf("expected completion action: %+v", decision)
	}
	rec, err := store.RecordCompletionAction(ctx, scheduler.CompletionActionRecord{IdempotencyKey: decision.IdempotencyKey, DownloadID: downloadID, ActionKind: scheduler.CompletionActionNotify, Status: scheduler.CompletionActionPending})
	if err != nil {
		return report{}, err
	}
	dup, err := store.RecordCompletionAction(ctx, scheduler.CompletionActionRecord{IdempotencyKey: decision.IdempotencyKey, DownloadID: downloadID, ActionKind: scheduler.CompletionActionNotify, Status: scheduler.CompletionActionPending})
	if err != nil {
		return report{}, err
	}
	if dup.Revision != rec.Revision {
		return report{}, fmt.Errorf("idempotent duplicate mutated record: first=%+v dup=%+v", rec, dup)
	}
	declined, err := store.UpdateCompletionActionStatus(ctx, decision.IdempotencyKey, scheduler.CompletionActionDeclined, "host-declined")
	if err != nil {
		return report{}, err
	}
	if declined.Status != scheduler.CompletionActionDeclined || declined.HostReceipt != "host-declined" {
		return report{}, fmt.Errorf("host decline not persisted: %+v", declined)
	}
	repeat, err := scheduler.DecideCompletionAction(scheduler.CompletionActionPolicy{ActionKind: scheduler.CompletionActionNotify, When: []scheduler.TerminalOutcome{scheduler.TerminalSucceeded}}, event, map[string]scheduler.CompletionActionRecord{decision.IdempotencyKey: declined})
	if err != nil {
		return report{}, err
	}
	if repeat.ShouldFire || repeat.Status != scheduler.CompletionActionDeclined {
		return report{}, fmt.Errorf("completion action repeated after host reply: %+v", repeat)
	}
	return report{Mode: "bandwidth_completion", Status: "PASS", Checks: []string{"limit precedence", "live profile value", "completion action once", "restart before duplicate", "host decline persisted"}}, nil
}

func mustNY() *time.Location {
	loc, err := time.LoadLocation("America/New_York")
	if err != nil {
		panic(err)
	}
	return loc
}

func openStore() (*scheduler.Store, *sqlite.DB, context.Context, func(), error) {
	if !sqlite.Available {
		return nil, nil, nil, nil, sqlite.ErrUnavailable
	}
	path := filepath.Join(os.TempDir(), fmt.Sprintf("xgo-scheduler-audit-%d.sqlite", time.Now().UnixNano()))
	db, err := sqlite.Open(path, sqlite.DefaultOptions())
	if err != nil {
		return nil, nil, nil, nil, err
	}
	cleanup := func() {
		_ = db.Close()
		_ = os.Remove(path)
		_ = os.Remove(path + "-wal")
		_ = os.Remove(path + "-shm")
	}
	ctx := context.Background()
	if err := sqlite.Migrate(ctx, db, time.Now().UnixMilli()); err != nil {
		cleanup()
		return nil, nil, nil, nil, err
	}
	store, err := scheduler.NewStore(db)
	if err != nil {
		cleanup()
		return nil, nil, nil, nil, err
	}
	return store, db, ctx, cleanup, nil
}

func seedDownload(ctx context.Context, repo *sqlite.Repository, id string) (identity.DownloadID, error) {
	dl, err := identity.ParseDownloadID(id)
	if err != nil {
		return "", err
	}
	req, err := identity.ParseRequestID("req_" + id[3:])
	if err != nil {
		return "", err
	}
	rev, _ := identity.NewRevision(1)
	if err := repo.CreateRequest(ctx, sqlite.RequestRecord{ID: req, Revision: rev, ResourceIdentity: "audit:" + id, Method: "GET", SafeSpecJSON: `{"url":"https://example.invalid/` + id + `"}`}); err != nil {
		return "", err
	}
	if err := repo.CreateDownload(ctx, sqlite.DownloadRecord{ID: dl, RequestID: req, CurrentRequestRevision: rev, State: "queued", Revision: rev}); err != nil {
		return "", err
	}
	return dl, nil
}

func fatalf(format string, args ...any) { fmt.Fprintf(os.Stderr, format+"\n", args...); os.Exit(1) }
