package scheduler

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/subhra74/xdm/engine/domain/identity"
	"github.com/subhra74/xdm/engine/store/sqlite"
)

type fakeTZDB map[string]*time.Location

func (db fakeTZDB) LoadLocation(name string) (*time.Location, error) {
	loc, ok := db[name]
	if !ok {
		return nil, errors.New("missing location")
	}
	return loc, nil
}

func TestRuntimeConditionsEveryHoldReason(t *testing.T) {
	eval, err := EvaluateConditions(ConditionPolicy{
		RequireOnline:       true,
		RequireUnmetered:    true,
		RequireWiFi:         true,
		RequireCharging:     true,
		MinBatteryPercent:   75,
		MinStorageFreeBytes: 10 << 30,
		AllowedPowerSources: []PowerSource{PowerAC},
	}, RuntimeSnapshot{
		Online:           false,
		Metered:          true,
		WiFi:             false,
		Charging:         false,
		BatteryPercent:   23,
		StorageFreeBytes: 512,
		PowerSource:      PowerBattery,
	})
	if err != nil {
		t.Fatal(err)
	}
	want := []HoldReason{HoldOffline, HoldMetered, HoldWiFiUnavailable, HoldNotCharging, HoldBatteryLow, HoldStorageLow, HoldPowerSourceDisallowed}
	if eval.Eligible || len(eval.Holds) != len(want) {
		t.Fatalf("bad holds: %+v", eval)
	}
	for i, reason := range want {
		if eval.Holds[i] != reason {
			t.Fatalf("hold %d = %s, want %s in %+v", i, eval.Holds[i], reason, eval.Holds)
		}
	}
}

func TestScheduleOvernightWindowAndRestartPersistence(t *testing.T) {
	def := ScheduleDefinition{
		Timezone:                 "UTC",
		Recurrence:               RecurrenceDaily,
		Windows:                  []ScheduleWindow{{StartMinute: 22 * 60, EndMinute: 2 * 60}},
		MissedRunPolicy:          MissedRunSkip,
		PreserveAcrossRestarts:   true,
		HostSuppliesRuntimeState: false,
	}
	for _, now := range []time.Time{
		time.Date(2026, 1, 2, 23, 15, 0, 0, time.UTC),
		time.Date(2026, 1, 3, 1, 30, 0, 0, time.UTC),
	} {
		eval, err := EvaluateSchedule(def, ScheduleInput{Now: now})
		if err != nil {
			t.Fatal(err)
		}
		if !eval.Eligible || !eval.WindowMatched {
			t.Fatalf("%s should be in overnight window: %+v", now, eval)
		}
	}
	closed, err := EvaluateSchedule(def, ScheduleInput{Now: time.Date(2026, 1, 3, 3, 0, 0, 0, time.UTC), ForceRestart: true})
	if err != nil {
		t.Fatal(err)
	}
	if closed.Eligible || closed.Holds[0] != HoldScheduleClosed {
		t.Fatalf("outside window should hold after restart: %+v", closed)
	}
}

func TestScheduleDSTForwardAndBack(t *testing.T) {
	loc, err := time.LoadLocation("America/New_York")
	if err != nil {
		t.Fatal(err)
	}
	def := ScheduleDefinition{
		Timezone:        "America/New_York",
		Recurrence:      RecurrenceDaily,
		Windows:         []ScheduleWindow{{StartMinute: 60, EndMinute: 4 * 60}},
		MissedRunPolicy: MissedRunSkip,
	}
	// Spring-forward day: 02:30 never exists, but 03:30 local remains inside the declared 01:00-04:00 window.
	forward, err := EvaluateSchedule(def, ScheduleInput{Now: time.Date(2026, 3, 8, 3, 30, 0, 0, loc)})
	if err != nil {
		t.Fatal(err)
	}
	if !forward.Eligible || !forward.WindowMatched {
		t.Fatalf("spring-forward local time should be eligible: %+v", forward)
	}
	// Fall-back day: both 01:30 instants must evaluate through their local clock time, not UTC arithmetic.
	first := time.Date(2026, 11, 1, 1, 30, 0, 0, loc)
	second := first.Add(time.Hour)
	for _, now := range []time.Time{first, second} {
		eval, err := EvaluateSchedule(def, ScheduleInput{Now: now})
		if err != nil {
			t.Fatal(err)
		}
		if !eval.Eligible || !eval.WindowMatched {
			t.Fatalf("fall-back local time %s should be eligible: %+v", now, eval)
		}
	}
}

func TestMissedSchedulePolicy(t *testing.T) {
	def := ScheduleDefinition{
		Timezone:              "UTC",
		Recurrence:            RecurrenceDaily,
		Windows:               []ScheduleWindow{{StartMinute: 9 * 60, EndMinute: 10 * 60}},
		MissedRunPolicy:       MissedRunStartWhenAvailable,
		MissedRunGraceMinutes: 90,
	}
	eval, err := EvaluateSchedule(def, ScheduleInput{Now: time.Date(2026, 5, 1, 10, 30, 0, 0, time.UTC), LastChecked: time.Date(2026, 5, 1, 8, 0, 0, 0, time.UTC)})
	if err != nil {
		t.Fatal(err)
	}
	if !eval.Eligible || !eval.MissedRun || eval.MissedWindowAt == "" {
		t.Fatalf("missed window should start within grace: %+v", eval)
	}
	late, err := EvaluateSchedule(def, ScheduleInput{Now: time.Date(2026, 5, 1, 12, 0, 0, 0, time.UTC), LastChecked: time.Date(2026, 5, 1, 8, 0, 0, 0, time.UTC)})
	if err != nil {
		t.Fatal(err)
	}
	if late.Eligible || late.Holds[0] != HoldScheduleClosed {
		t.Fatalf("missed window outside grace should hold: %+v", late)
	}
}

func TestConditionChangesWhileQueuedAndSchedulePersistence(t *testing.T) {
	store, db, ctx := openSchedulerTestStore(t)
	defer db.Close()
	rev, _ := identity.NewRevision(1)
	rec, err := store.SaveSchedule(ctx, ScheduleRecord{
		ID:       "schedule_nightly",
		Enabled:  true,
		Revision: rev,
		Definition: ScheduleDefinition{
			Timezone:                 "UTC",
			Recurrence:               RecurrenceDaily,
			Windows:                  []ScheduleWindow{{StartMinute: 0, EndMinute: 23*60 + 59}},
			MissedRunPolicy:          MissedRunSkip,
			HostSuppliesRuntimeState: true,
			ConditionPolicy:          ConditionPolicy{RequireOnline: true, RequireWiFi: true, MinBatteryPercent: 30},
			PreserveAcrossRestarts:   true,
		},
	})
	if err != nil {
		t.Fatal(err)
	}
	loaded, err := store.GetSchedule(context.Background(), rec.ID)
	if err != nil {
		t.Fatal(err)
	}
	policy := QueuePolicy{Conditions: []string{"online", "wifi"}, ScheduleRef: loaded.ID}
	holding, err := EvaluateQueuePolicy(policy, map[string]ScheduleRecord{loaded.ID: loaded}, ScheduleInput{Now: time.Date(2026, 6, 1, 12, 0, 0, 0, time.UTC), HaveRuntime: true, Runtime: RuntimeSnapshot{Online: true, WiFi: false, BatteryPercent: 95, StorageFreeBytes: 1 << 30, PowerSource: PowerAC}})
	if err != nil {
		t.Fatal(err)
	}
	if holding.Eligible || len(holding.Holds) != 1 || holding.Holds[0] != HoldWiFiUnavailable {
		t.Fatalf("wifi hold expected: %+v", holding)
	}
	ready, err := EvaluateQueuePolicy(policy, map[string]ScheduleRecord{loaded.ID: loaded}, ScheduleInput{Now: time.Date(2026, 6, 1, 12, 5, 0, 0, time.UTC), HaveRuntime: true, Runtime: RuntimeSnapshot{Online: true, WiFi: true, BatteryPercent: 95, StorageFreeBytes: 1 << 30, PowerSource: PowerAC}})
	if err != nil {
		t.Fatal(err)
	}
	if !ready.Eligible {
		t.Fatalf("condition change should unblock queued work: %+v", ready)
	}
	updated, err := store.UpdateSchedule(ctx, loaded.ID, loaded.Revision, ScheduleRecord{Enabled: true, Definition: loaded.Definition})
	if err != nil {
		t.Fatal(err)
	}
	if _, err := store.UpdateSchedule(ctx, loaded.ID, loaded.Revision, ScheduleRecord{Enabled: true, Definition: updated.Definition}); !errors.Is(err, sqlite.ErrStaleWrite) {
		t.Fatalf("expected stale schedule write, got %v", err)
	}
}
