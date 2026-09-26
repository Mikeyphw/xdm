package ops

import (
	"encoding/json"
	"errors"
	"fmt"
	"sort"
	"strings"
	"time"
)

var ErrInvalidDiagnosticEvent = errors.New("invalid diagnostic event")

type Severity string
type HealthState string

const (
	SeverityDebug Severity = "debug"
	SeverityInfo  Severity = "info"
	SeverityWarn  Severity = "warn"
	SeverityError Severity = "error"

	HealthOK       HealthState = "ok"
	HealthDegraded HealthState = "degraded"
	HealthFailing  HealthState = "failing"
	HealthUnknown  HealthState = "unknown"
)

type DiagnosticEvent struct {
	Timestamp       time.Time      `json:"timestamp"`
	Subsystem       string         `json:"subsystem"`
	OperationID     string         `json:"operation_id,omitempty"`
	DownloadID      string         `json:"download_id,omitempty"`
	AttemptID       string         `json:"attempt_id,omitempty"`
	Stage           string         `json:"stage"`
	Severity        Severity       `json:"severity"`
	FailureCategory string         `json:"failure_category,omitempty"`
	SafeContext     map[string]any `json:"safe_context,omitempty"`
}

func NewDiagnosticEvent(event DiagnosticEvent) (DiagnosticEvent, error) {
	if event.Timestamp.IsZero() || strings.TrimSpace(event.Subsystem) == "" || strings.TrimSpace(event.Stage) == "" {
		return DiagnosticEvent{}, fmt.Errorf("%w: required", ErrInvalidDiagnosticEvent)
	}
	switch event.Severity {
	case SeverityDebug, SeverityInfo, SeverityWarn, SeverityError:
	default:
		return DiagnosticEvent{}, fmt.Errorf("%w: severity", ErrInvalidDiagnosticEvent)
	}
	if event.SafeContext != nil {
		redacted, ok := RedactSupportValue(event.SafeContext).(map[string]any)
		if !ok {
			return DiagnosticEvent{}, fmt.Errorf("%w: context", ErrInvalidDiagnosticEvent)
		}
		event.SafeContext = redacted
	}
	return event, nil
}

func (e DiagnosticEvent) EncodedSize() int64 {
	b, _ := json.Marshal(e)
	return int64(len(b))
}

type DiagnosticStore struct {
	settings RetentionSettings
	now      func() time.Time
	events   []DiagnosticEvent
	bytes    int64
}

func NewDiagnosticStore(settings RetentionSettings, now func() time.Time) (*DiagnosticStore, error) {
	base := DefaultEngineSettings()
	base.Retention = settings
	if err := base.Validate(); err != nil {
		return nil, err
	}
	if now == nil {
		now = time.Now
	}
	return &DiagnosticStore{settings: settings, now: now}, nil
}

func (s *DiagnosticStore) Add(event DiagnosticEvent) error {
	e, err := NewDiagnosticEvent(event)
	if err != nil {
		return err
	}
	s.events = append(s.events, e)
	s.bytes += e.EncodedSize()
	s.enforceRetention()
	return nil
}

func (s *DiagnosticStore) Events() []DiagnosticEvent {
	out := append([]DiagnosticEvent(nil), s.events...)
	sort.SliceStable(out, func(i, j int) bool { return out[i].Timestamp.Before(out[j].Timestamp) })
	return out
}

func (s *DiagnosticStore) Bytes() int64 { return s.bytes }

func (s *DiagnosticStore) enforceRetention() {
	cutoff := s.now().Add(-time.Duration(s.settings.EventsMaxAgeDays) * 24 * time.Hour)
	filtered := s.events[:0]
	s.bytes = 0
	for _, e := range s.events {
		if !e.Timestamp.Before(cutoff) {
			filtered = append(filtered, e)
		}
	}
	s.events = filtered
	for _, e := range s.events {
		s.bytes += e.EncodedSize()
	}
	for len(s.events) > s.settings.EventsMaxCount || s.bytes > s.settings.EventsMaxBytes {
		s.bytes -= s.events[0].EncodedSize()
		s.events = s.events[1:]
	}
}

type HealthReport struct {
	GeneratedAt time.Time              `json:"generated_at"`
	Subsystems  map[string]HealthState `json:"subsystems"`
}

func NewHealthReport(now time.Time, states map[string]HealthState) (HealthReport, error) {
	required := []string{"db", "native", "aria2", "media", "storage", "browser", "scheduler", "recovery"}
	out := map[string]HealthState{}
	for _, name := range required {
		state := states[name]
		if state == "" {
			state = HealthUnknown
		}
		switch state {
		case HealthOK, HealthDegraded, HealthFailing, HealthUnknown:
			out[name] = state
		default:
			return HealthReport{}, fmt.Errorf("invalid health state for %s", name)
		}
	}
	return HealthReport{GeneratedAt: now, Subsystems: out}, nil
}

type SupportSnapshot struct {
	GeneratedAt  time.Time         `json:"generated_at"`
	Settings     EngineSettings    `json:"settings"`
	Health       HealthReport      `json:"health"`
	Events       []DiagnosticEvent `json:"events"`
	ExternalLogs map[string]string `json:"external_logs,omitempty"`
}

func BuildSupportSnapshot(now time.Time, settings EngineSettings, health HealthReport, events []DiagnosticEvent, externalLogs map[string]string) (SupportSnapshot, error) {
	if err := settings.Validate(); err != nil {
		return SupportSnapshot{}, err
	}
	boundedLogs := map[string]string{}
	limit := settings.Retention.ExternalLogMaxBytes
	for name, value := range externalLogs {
		redacted := RedactInlineSecrets(value)
		if len(redacted) > limit {
			redacted = redacted[:limit] + "<truncated>"
		}
		boundedLogs[name] = redacted
	}
	cleanEvents := make([]DiagnosticEvent, 0, len(events))
	for _, e := range events {
		ce, err := NewDiagnosticEvent(e)
		if err != nil {
			return SupportSnapshot{}, err
		}
		cleanEvents = append(cleanEvents, ce)
	}
	return SupportSnapshot{GeneratedAt: now, Settings: settings, Health: health, Events: cleanEvents, ExternalLogs: boundedLogs}, nil
}

func (s SupportSnapshot) SecretScan() error {
	b, _ := json.Marshal(s)
	lower := strings.ToLower(string(b))
	needles := []string{"super-secret", "raw-token", "password=abc", "signature=abc", "authorization=bearer"}
	for _, n := range needles {
		if strings.Contains(lower, n) {
			return fmt.Errorf("support snapshot leaked %s", n)
		}
	}
	return nil
}
