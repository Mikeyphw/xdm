package ops

import (
	"strings"
	"testing"
	"time"
)

func TestDiagnosticsRetentionHealthAndSnapshot(t *testing.T) {
	settings := DefaultEngineSettings()
	settings.Retention.EventsMaxCount = 3
	settings.Retention.EventsMaxBytes = 4096
	settings.Retention.EventsMaxAgeDays = 1
	settings.Retention.ExternalLogMaxBytes = 20
	now := time.Unix(100000, 0)
	store, err := NewDiagnosticStore(settings.Retention, func() time.Time { return now })
	if err != nil {
		t.Fatal(err)
	}
	for i := 0; i < 6; i++ {
		err := store.Add(DiagnosticEvent{Timestamp: now.Add(time.Duration(i) * time.Second), Subsystem: "media", OperationID: "op", DownloadID: "dl", AttemptID: "att", Stage: "fetch", Severity: SeverityInfo, SafeContext: map[string]any{"url": "https://x.test/a?signature=abc", "token": "raw-token"}})
		if err != nil {
			t.Fatal(err)
		}
	}
	if len(store.Events()) != 3 {
		t.Fatalf("retention count not enforced: %d", len(store.Events()))
	}
	health, err := NewHealthReport(now, map[string]HealthState{"db": HealthOK, "media": HealthDegraded, "scheduler": HealthOK})
	if err != nil {
		t.Fatal(err)
	}
	snap, err := BuildSupportSnapshot(now, settings, health, store.Events(), map[string]string{"ffmpeg": "progress password=abc signature=abc " + strings.Repeat("x", 100)})
	if err != nil {
		t.Fatal(err)
	}
	if err := snap.SecretScan(); err != nil {
		t.Fatal(err)
	}
	if len(snap.ExternalLogs["ffmpeg"]) > settings.Retention.ExternalLogMaxBytes+len("<truncated>") {
		t.Fatalf("log not bounded")
	}
}
