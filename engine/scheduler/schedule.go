package scheduler

import (
	"context"
	"encoding/json"
	"fmt"
	"sort"
	"time"

	"github.com/subhra74/xdm/engine/domain/identity"
	"github.com/subhra74/xdm/engine/store/sqlite"
)

type TimezoneDB interface {
	LoadLocation(name string) (*time.Location, error)
}

type SystemTimezoneDB struct{}

func (SystemTimezoneDB) LoadLocation(name string) (*time.Location, error) {
	return time.LoadLocation(name)
}

type MissedRunPolicy string

const (
	MissedRunSkip               MissedRunPolicy = "skip"
	MissedRunStartWhenAvailable MissedRunPolicy = "start_when_available"
)

type ScheduleRecurrence string

const (
	RecurrenceDaily  ScheduleRecurrence = "daily"
	RecurrenceWeekly ScheduleRecurrence = "weekly"
)

type ScheduleWindow struct {
	StartMinute int            `json:"start_minute"`
	EndMinute   int            `json:"end_minute"`
	Weekdays    []time.Weekday `json:"weekdays,omitempty"`
}

type ScheduleDefinition struct {
	Timezone                 string             `json:"timezone"`
	Recurrence               ScheduleRecurrence `json:"recurrence"`
	Windows                  []ScheduleWindow   `json:"windows"`
	MissedRunPolicy          MissedRunPolicy    `json:"missed_run_policy"`
	MissedRunGraceMinutes    int                `json:"missed_run_grace_minutes,omitempty"`
	ConditionPolicy          ConditionPolicy    `json:"condition_policy,omitempty"`
	PreserveAcrossRestarts   bool               `json:"preserve_across_restarts"`
	HostSuppliesRuntimeState bool               `json:"host_supplies_runtime_state"`
}

type ScheduleRecord struct {
	ID              string
	Definition      ScheduleDefinition
	Enabled         bool
	Revision        identity.Revision
	CreatedAtUnixMS int64
	UpdatedAtUnixMS int64
}

type ScheduleInput struct {
	Now          time.Time
	LastChecked  time.Time
	Runtime      RuntimeSnapshot
	HaveRuntime  bool
	TimezoneDB   TimezoneDB
	ForceRestart bool
}

type ScheduleEvaluation struct {
	Eligible       bool         `json:"eligible"`
	Holds          []HoldReason `json:"holds,omitempty"`
	LocalTime      string       `json:"local_time"`
	WindowMatched  bool         `json:"window_matched"`
	MissedRun      bool         `json:"missed_run"`
	MissedWindowAt string       `json:"missed_window_at,omitempty"`
}

func EvaluateSchedule(def ScheduleDefinition, input ScheduleInput) (ScheduleEvaluation, error) {
	if err := validateSchedule(def); err != nil {
		return ScheduleEvaluation{}, err
	}
	db := input.TimezoneDB
	if db == nil {
		db = SystemTimezoneDB{}
	}
	loc, err := db.LoadLocation(def.Timezone)
	if err != nil {
		return ScheduleEvaluation{}, fmt.Errorf("%w: timezone %q: %v", ErrInvalidSchedule, def.Timezone, err)
	}
	now := input.Now
	if now.IsZero() {
		now = time.Now()
	}
	local := now.In(loc)
	eval := ScheduleEvaluation{Eligible: true, LocalTime: local.Format(time.RFC3339)}
	if def.HostSuppliesRuntimeState || input.HaveRuntime {
		if !input.HaveRuntime {
			return ScheduleEvaluation{}, ErrInvalidRuntimeSnapshot
		}
		cond, err := EvaluateConditions(def.ConditionPolicy, input.Runtime)
		if err != nil {
			return ScheduleEvaluation{}, err
		}
		if !cond.Eligible {
			eval.Eligible = false
			eval.Holds = append(eval.Holds, cond.Holds...)
		}
	}
	if inAnyWindow(def, local) {
		eval.WindowMatched = true
		return eval, nil
	}
	if def.MissedRunPolicy == MissedRunStartWhenAvailable && !input.LastChecked.IsZero() {
		if end, ok := missedWindowEnd(def, input.LastChecked.In(loc), local); ok {
			grace := time.Duration(def.MissedRunGraceMinutes) * time.Minute
			if grace == 0 || !local.After(end.Add(grace)) {
				eval.MissedRun = true
				eval.MissedWindowAt = end.Format(time.RFC3339)
				return eval, nil
			}
		}
	}
	eval.Eligible = false
	eval.Holds = append(eval.Holds, HoldScheduleClosed)
	return eval, nil
}

func (s *Store) SaveSchedule(ctx context.Context, rec ScheduleRecord) (ScheduleRecord, error) {
	if s == nil || s.db == nil {
		return ScheduleRecord{}, sqlite.ErrClosed
	}
	if rec.ID == "" {
		return ScheduleRecord{}, ErrInvalidSchedule
	}
	if err := validateSchedule(rec.Definition); err != nil {
		return ScheduleRecord{}, err
	}
	now := nowUnix(rec.CreatedAtUnixMS)
	rec.CreatedAtUnixMS = now
	if rec.UpdatedAtUnixMS <= 0 {
		rec.UpdatedAtUnixMS = now
	}
	if !rec.Revision.Valid() {
		rec.Revision, _ = identity.NewRevision(1)
	}
	definitionJSON, err := encodeScheduleDefinition(rec.Definition)
	if err != nil {
		return ScheduleRecord{}, err
	}
	_, err = s.db.Exec(ctx, `INSERT INTO schedule_definitions(schedule_id,definition_json,enabled,revision,created_at_unix_ms,updated_at_unix_ms) VALUES(?,?,?,?,?,?)`, rec.ID, definitionJSON, rec.Enabled, rec.Revision.Int64(), rec.CreatedAtUnixMS, rec.UpdatedAtUnixMS)
	if err != nil {
		return ScheduleRecord{}, err
	}
	return s.GetSchedule(ctx, rec.ID)
}

func (s *Store) GetSchedule(ctx context.Context, id string) (ScheduleRecord, error) {
	if s == nil || s.db == nil {
		return ScheduleRecord{}, sqlite.ErrClosed
	}
	rows, err := s.db.Query(ctx, `SELECT schedule_id,definition_json,enabled,revision,created_at_unix_ms,updated_at_unix_ms FROM schedule_definitions WHERE schedule_id=?`, id)
	if err != nil {
		return ScheduleRecord{}, err
	}
	if len(rows) == 0 {
		return ScheduleRecord{}, sqlite.ErrNotFound
	}
	return decodeSchedule(rows[0])
}

func (s *Store) UpdateSchedule(ctx context.Context, id string, expected identity.Revision, patch ScheduleRecord) (ScheduleRecord, error) {
	if s == nil || s.db == nil {
		return ScheduleRecord{}, sqlite.ErrClosed
	}
	if id == "" || !expected.Valid() {
		return ScheduleRecord{}, ErrInvalidSchedule
	}
	if err := validateSchedule(patch.Definition); err != nil {
		return ScheduleRecord{}, err
	}
	next, err := expected.Next()
	if err != nil {
		return ScheduleRecord{}, err
	}
	definitionJSON, err := encodeScheduleDefinition(patch.Definition)
	if err != nil {
		return ScheduleRecord{}, err
	}
	changes, err := s.db.Exec(ctx, `UPDATE schedule_definitions SET definition_json=?, enabled=?, revision=?, updated_at_unix_ms=? WHERE schedule_id=? AND revision=?`, definitionJSON, patch.Enabled, next.Int64(), nowUnix(patch.UpdatedAtUnixMS), id, expected.Int64())
	if err != nil {
		return ScheduleRecord{}, err
	}
	if changes != 1 {
		if _, lookup := s.GetSchedule(ctx, id); lookup == sqlite.ErrNotFound {
			return ScheduleRecord{}, sqlite.ErrNotFound
		}
		return ScheduleRecord{}, sqlite.ErrStaleWrite
	}
	return s.GetSchedule(ctx, id)
}

func (s *Store) DeleteSchedule(ctx context.Context, id string) error {
	if s == nil || s.db == nil {
		return sqlite.ErrClosed
	}
	if id == "" {
		return ErrInvalidSchedule
	}
	changes, err := s.db.Exec(ctx, `DELETE FROM schedule_definitions WHERE schedule_id=?`, id)
	if err != nil {
		return err
	}
	if changes != 1 {
		return sqlite.ErrNotFound
	}
	return nil
}

func EvaluateQueuePolicy(policy QueuePolicy, schedules map[string]ScheduleRecord, input ScheduleInput) (ScheduleEvaluation, error) {
	condPolicy, err := conditionPolicyFromNames(policy.Conditions)
	if err != nil {
		return ScheduleEvaluation{}, err
	}
	if policy.ScheduleRef == "" {
		if !input.HaveRuntime && len(policy.Conditions) == 0 {
			return ScheduleEvaluation{Eligible: true}, nil
		}
		cond, err := EvaluateConditions(condPolicy, input.Runtime)
		if err != nil {
			return ScheduleEvaluation{}, err
		}
		return ScheduleEvaluation{Eligible: cond.Eligible, Holds: cond.Holds}, nil
	}
	rec, ok := schedules[policy.ScheduleRef]
	if !ok {
		return ScheduleEvaluation{}, sqlite.ErrNotFound
	}
	if !rec.Enabled {
		return ScheduleEvaluation{Eligible: false, Holds: []HoldReason{HoldScheduleDisabled}}, nil
	}
	def := rec.Definition
	if merged, err := mergeConditionPolicies(def.ConditionPolicy, condPolicy); err != nil {
		return ScheduleEvaluation{}, err
	} else {
		def.ConditionPolicy = merged
		def.HostSuppliesRuntimeState = def.HostSuppliesRuntimeState || len(policy.Conditions) > 0
	}
	return EvaluateSchedule(def, input)
}

func conditionPolicyFromNames(names []string) (ConditionPolicy, error) {
	policy := ConditionPolicy{}
	for _, name := range names {
		switch name {
		case "", "any":
		case "online":
			policy.RequireOnline = true
		case "unmetered":
			policy.RequireUnmetered = true
		case "wifi", "wi-fi":
			policy.RequireWiFi = true
		case "charging":
			policy.RequireCharging = true
		default:
			return ConditionPolicy{}, fmt.Errorf("%w: unknown condition %q", ErrInvalidConditionPolicy, name)
		}
	}
	return policy, nil
}

func mergeConditionPolicies(a, b ConditionPolicy) (ConditionPolicy, error) {
	if err := validateConditionPolicy(a); err != nil {
		return ConditionPolicy{}, err
	}
	if err := validateConditionPolicy(b); err != nil {
		return ConditionPolicy{}, err
	}
	out := ConditionPolicy{
		RequireOnline:       a.RequireOnline || b.RequireOnline,
		AllowMetered:        a.AllowMetered || b.AllowMetered,
		RequireUnmetered:    a.RequireUnmetered || b.RequireUnmetered,
		RequireWiFi:         a.RequireWiFi || b.RequireWiFi,
		RequireCharging:     a.RequireCharging || b.RequireCharging,
		MinBatteryPercent:   maxInt(a.MinBatteryPercent, b.MinBatteryPercent),
		MinStorageFreeBytes: maxInt64(a.MinStorageFreeBytes, b.MinStorageFreeBytes),
	}
	out.AllowedPowerSources = intersectPowerSources(a.AllowedPowerSources, b.AllowedPowerSources)
	return canonicalConditionPolicy(out)
}

func intersectPowerSources(a, b []PowerSource) []PowerSource {
	if len(a) == 0 {
		return append([]PowerSource(nil), b...)
	}
	if len(b) == 0 {
		return append([]PowerSource(nil), a...)
	}
	set := map[PowerSource]bool{}
	for _, src := range a {
		set[src] = true
	}
	var out []PowerSource
	for _, src := range b {
		if set[src] {
			out = append(out, src)
		}
	}
	return out
}

func validateSchedule(def ScheduleDefinition) error {
	if def.Timezone == "" || len(def.Windows) == 0 {
		return ErrInvalidSchedule
	}
	switch def.Recurrence {
	case "", RecurrenceDaily, RecurrenceWeekly:
	default:
		return fmt.Errorf("%w: recurrence %q", ErrInvalidSchedule, def.Recurrence)
	}
	switch def.MissedRunPolicy {
	case "", MissedRunSkip, MissedRunStartWhenAvailable:
	default:
		return fmt.Errorf("%w: missed policy %q", ErrInvalidSchedule, def.MissedRunPolicy)
	}
	if def.MissedRunGraceMinutes < 0 {
		return ErrInvalidSchedule
	}
	if err := validateConditionPolicy(def.ConditionPolicy); err != nil {
		return err
	}
	for _, w := range def.Windows {
		if w.StartMinute < 0 || w.StartMinute >= 24*60 || w.EndMinute < 0 || w.EndMinute >= 24*60 || w.StartMinute == w.EndMinute {
			return ErrInvalidSchedule
		}
		seenDays := map[time.Weekday]bool{}
		for _, d := range w.Weekdays {
			if d < time.Sunday || d > time.Saturday || seenDays[d] {
				return ErrInvalidSchedule
			}
			seenDays[d] = true
		}
	}
	return nil
}

func encodeScheduleDefinition(def ScheduleDefinition) (string, error) {
	if err := validateSchedule(def); err != nil {
		return "", err
	}
	def = canonicalSchedule(def)
	b, err := json.Marshal(def)
	if err != nil {
		return "", err
	}
	return string(b), nil
}

func decodeSchedule(row sqlite.Row) (ScheduleRecord, error) {
	if len(row) != 6 {
		return ScheduleRecord{}, fmt.Errorf("unexpected schedule column count %d", len(row))
	}
	rev, err := identity.NewRevision(row[3].I64)
	if err != nil {
		return ScheduleRecord{}, err
	}
	var def ScheduleDefinition
	if err := json.Unmarshal([]byte(row[1].Text), &def); err != nil {
		return ScheduleRecord{}, err
	}
	if err := validateSchedule(def); err != nil {
		return ScheduleRecord{}, err
	}
	return ScheduleRecord{ID: row[0].Text, Definition: def, Enabled: row[2].I64 == 1, Revision: rev, CreatedAtUnixMS: row[4].I64, UpdatedAtUnixMS: row[5].I64}, nil
}

func canonicalSchedule(def ScheduleDefinition) ScheduleDefinition {
	if def.Recurrence == "" {
		def.Recurrence = RecurrenceDaily
	}
	if def.MissedRunPolicy == "" {
		def.MissedRunPolicy = MissedRunSkip
	}
	def.Windows = append([]ScheduleWindow(nil), def.Windows...)
	for i := range def.Windows {
		def.Windows[i].Weekdays = append([]time.Weekday(nil), def.Windows[i].Weekdays...)
		sort.Slice(def.Windows[i].Weekdays, func(a, b int) bool { return def.Windows[i].Weekdays[a] < def.Windows[i].Weekdays[b] })
	}
	sort.Slice(def.Windows, func(i, j int) bool {
		if def.Windows[i].StartMinute != def.Windows[j].StartMinute {
			return def.Windows[i].StartMinute < def.Windows[j].StartMinute
		}
		return def.Windows[i].EndMinute < def.Windows[j].EndMinute
	})
	return def
}

func inAnyWindow(def ScheduleDefinition, local time.Time) bool {
	for _, w := range def.Windows {
		if windowContains(w, local) {
			return true
		}
	}
	return false
}

func windowContains(w ScheduleWindow, local time.Time) bool {
	minute := local.Hour()*60 + local.Minute()
	day := local.Weekday()
	if w.StartMinute < w.EndMinute {
		return dayAllowed(w, day) && minute >= w.StartMinute && minute < w.EndMinute
	}
	if minute >= w.StartMinute {
		return dayAllowed(w, day)
	}
	prev := day - 1
	if prev < time.Sunday {
		prev = time.Saturday
	}
	return dayAllowed(w, prev) && minute < w.EndMinute
}

func dayAllowed(w ScheduleWindow, day time.Weekday) bool {
	if len(w.Weekdays) == 0 {
		return true
	}
	for _, d := range w.Weekdays {
		if d == day {
			return true
		}
	}
	return false
}

func missedWindowEnd(def ScheduleDefinition, lastCheckedLocal, nowLocal time.Time) (time.Time, bool) {
	if !lastCheckedLocal.Before(nowLocal) {
		return time.Time{}, false
	}
	best := time.Time{}
	for _, w := range def.Windows {
		for d := -3; d <= 1; d++ {
			base := localMidnight(nowLocal).AddDate(0, 0, d)
			endDay := base
			if w.StartMinute > w.EndMinute {
				endDay = endDay.AddDate(0, 0, 1)
			}
			if !dayAllowed(w, base.Weekday()) {
				continue
			}
			end := endDay.Add(time.Duration(w.EndMinute) * time.Minute)
			if end.After(lastCheckedLocal) && !end.After(nowLocal) && (best.IsZero() || end.After(best)) {
				best = end
			}
		}
	}
	return best, !best.IsZero()
}

func localMidnight(t time.Time) time.Time {
	y, m, d := t.Date()
	return time.Date(y, m, d, 0, 0, 0, 0, t.Location())
}

func maxInt(a, b int) int {
	if a > b {
		return a
	}
	return b
}

func maxInt64(a, b int64) int64 {
	if a > b {
		return a
	}
	return b
}
