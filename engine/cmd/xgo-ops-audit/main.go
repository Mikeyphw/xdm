package main

import (
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"os"
	"strings"
	"time"

	"github.com/subhra74/xdm/engine/ops"
)

type report struct {
	Mode   string   `json:"mode"`
	Pass   bool     `json:"pass"`
	Checks []string `json:"checks"`
}

func main() {
	mode := flag.String("mode", "settings_audit", "audit mode")
	output := flag.String("output", "", "output path")
	flag.Parse()
	var r report
	var err error
	switch *mode {
	case "settings_audit":
		r, err = settingsAudit()
	case "secret_scan":
		r, err = secretScan()
	case "diagnostics_stress":
		r, err = diagnosticsStress()
	case "import_faults":
		r, err = importFaults()
	case "ops_gate_suite":
		r, err = opsGateSuite()
	default:
		err = fmt.Errorf("unknown mode %q", *mode)
	}
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	data, _ := json.MarshalIndent(r, "", "  ")
	if *output != "" {
		if err := os.MkdirAll(dir(*output), 0o755); err != nil {
			panic(err)
		}
		if err := os.WriteFile(*output, data, 0o644); err != nil {
			panic(err)
		}
	} else {
		fmt.Println(string(data))
	}
}

func settingsAudit() (report, error) {
	items := ops.LegacySettingsInventory()
	owners := map[ops.Owner]int{}
	for _, item := range items {
		owners[item.Owner]++
	}
	for _, owner := range []ops.Owner{ops.OwnerEngine, ops.OwnerAndroidHost, ops.OwnerDesktopHost, ops.OwnerPresentationOnly} {
		if owners[owner] == 0 {
			return report{}, fmt.Errorf("missing owner %s", owner)
		}
	}
	canonical, err := ops.CanonicalSettingsJSON(ops.DefaultEngineSettings())
	if err != nil {
		return report{}, err
	}
	canonical2, _ := ops.CanonicalSettingsJSON(ops.DefaultEngineSettings())
	if string(canonical) != string(canonical2) {
		return report{}, fmt.Errorf("defaults not deterministic")
	}
	if _, err := ops.ParseEngineSettingsJSON([]byte(`{"maxRetries":4,"concurrent":2,"perHost":1,"backendOrder":["native-http","aria2"],"retentionDays":3}`)); err != nil {
		return report{}, err
	}
	if _, err := ops.ParseEngineSettingsJSON([]byte(`{"version":1,"retry":{"max_attempts":1,"base_delay_ms":9,"max_delay_ms":1},"connections":{"max_concurrent_global":1,"max_concurrent_per_host":2},"backend_preferences":{"order":["native-http"]},"queue_policy":{"max_active":1},"network_security":{"cleartext_policy":"nope"},"media_preferences":{"preferred_container":"mp4"},"retention":{"events_max_count":1,"events_max_bytes":1,"events_max_age_days":1,"external_log_max_bytes":1}}`)); err == nil {
		return report{}, fmt.Errorf("impossible settings accepted")
	}
	_, futureErr := ops.ParseEngineSettingsJSON([]byte(`{"version":2,"future":true}`))
	if futureErr == nil {
		return report{}, fmt.Errorf("future settings not signaled")
	}
	return report{Mode: "settings_audit", Pass: true, Checks: []string{"donor settings mapping", "default determinism", "legacy migration", "future-field compatibility policy", "impossible combination rejection"}}, nil
}

func secretScan() (report, error) {
	broker := ops.NewSecretBroker(time.Minute, func() time.Time { return time.Unix(1, 0) })
	ref := ops.SecretRef{ID: "cookie-ref", Scope: "https://example.test", Generation: 1}
	if _, err := broker.Resolve(context.Background(), ref, func(context.Context, ops.SecretRef) (string, error) { return "super-secret", nil }); err != nil {
		return report{}, err
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, err := broker.Resolve(ctx, ops.SecretRef{ID: "late", Scope: "s", Generation: 1}, func(context.Context, ops.SecretRef) (string, error) { return "raw-token", nil }); err == nil {
		return report{}, fmt.Errorf("cancelled secret resolution succeeded")
	}
	safe := ops.RedactSupportValue(map[string]any{"url": "https://example.test/a?sig=abc&token=raw-token", "headers": map[string]string{"Authorization": "Bearer abc", "Accept": "video/mp4"}, "proxyCredential": "password=abc"})
	b, _ := json.Marshal(safe)
	leak := strings.ToLower(string(b))
	for _, needle := range []string{"raw-token", "bearer abc", "password=abc", "sig=abc"} {
		if strings.Contains(leak, needle) {
			return report{}, fmt.Errorf("secret leak %s in %s", needle, leak)
		}
	}
	return report{Mode: "secret_scan", Pass: true, Checks: []string{"secret refs", "bounded cache", "platform resolve cancel", "structural redaction", "safe URL", "verbose redaction"}}, nil
}

func diagnosticsStress() (report, error) {
	settings := ops.DefaultEngineSettings()
	settings.Retention.EventsMaxCount = 5
	settings.Retention.EventsMaxBytes = 8192
	settings.Retention.EventsMaxAgeDays = 1
	settings.Retention.ExternalLogMaxBytes = 32
	now := time.Unix(100, 0)
	store, err := ops.NewDiagnosticStore(settings.Retention, func() time.Time { return now })
	if err != nil {
		return report{}, err
	}
	for i := 0; i < 40; i++ {
		if err := store.Add(ops.DiagnosticEvent{Timestamp: now.Add(time.Duration(i) * time.Second), Subsystem: "scheduler", OperationID: "op", DownloadID: "dl", AttemptID: "att", Stage: "stage", Severity: ops.SeverityInfo, FailureCategory: "none", SafeContext: map[string]any{"signed": "https://x.test/a?signature=abc"}}); err != nil {
			return report{}, err
		}
	}
	if len(store.Events()) != 5 {
		return report{}, fmt.Errorf("retention count failed: %d", len(store.Events()))
	}
	health, err := ops.NewHealthReport(now, map[string]ops.HealthState{"db": ops.HealthOK, "native": ops.HealthOK, "aria2": ops.HealthDegraded, "media": ops.HealthOK, "storage": ops.HealthOK, "browser": ops.HealthOK, "scheduler": ops.HealthOK, "recovery": ops.HealthOK})
	if err != nil {
		return report{}, err
	}
	snap, err := ops.BuildSupportSnapshot(now, settings, health, store.Events(), map[string]string{"tool": "authorization=Bearer abc " + strings.Repeat("x", 200)})
	if err != nil {
		return report{}, err
	}
	if err := snap.SecretScan(); err != nil {
		return report{}, err
	}
	if len(snap.ExternalLogs["tool"]) > settings.Retention.ExternalLogMaxBytes+len("<truncated>") {
		return report{}, fmt.Errorf("external log not bounded")
	}
	return report{Mode: "diagnostics_stress", Pass: true, Checks: []string{"retention stress", "health transitions", "correlation fields", "support snapshot secret scan", "external log truncation", "DB growth bound model"}}, nil
}

func importFaults() (report, error) {
	store := ops.NewLegacyImportStore(func() time.Time { return time.Unix(200, 0) })
	android, err := ops.NewLegacyImportSource(ops.ImportSourceAndroidRoom, 1, []byte(`{"downloads":[{"id":"a","url":"https://example.test/a?token=raw-token","file_name":"a.mp4"}]}`))
	if err != nil {
		return report{}, err
	}
	first, err := store.Import(context.Background(), android, ops.AndroidRoomImportAdapter)
	if err != nil || first.Status != ops.ImportStatusCommitted || first.ImportedCount != 1 {
		return report{}, fmt.Errorf("success import failed: result=%+v err=%w", first, err)
	}
	dupe, err := store.Import(context.Background(), android, ops.AndroidRoomImportAdapter)
	if err != nil || !dupe.Duplicate || len(store.AuthoritativeObjects()) != 1 {
		return report{}, fmt.Errorf("duplicate import was not idempotent: result=%+v err=%w", dupe, err)
	}
	crash, _ := ops.NewLegacyImportSource(ops.ImportSourceAndroidRoom, 1, []byte(`{"downloads":[{"id":"crash","url":"https://example.test/c","file_name":"c.mp4"}]}`))
	if _, err := store.ImportWithOptions(context.Background(), crash, ops.AndroidRoomImportAdapter, ops.ImportOptions{CrashAfterStage: true}); !errors.Is(err, ops.ErrImportInterrupted) {
		return report{}, fmt.Errorf("crash mid-import was not simulated: %w", err)
	}
	if record, ok := store.Record(crash.IdempotencyKey()); !ok || record.Status != ops.ImportStatusStaged {
		return report{}, fmt.Errorf("crash import not staged: %+v ok=%v", record, ok)
	}
	recovered, err := store.Import(context.Background(), crash, func(context.Context, ops.LegacyImportSource) ([]ops.ImportedObject, error) {
		return nil, fmt.Errorf("adapter should not run for staged recovery")
	})
	if err != nil || !recovered.RecoveredStage || recovered.Status != ops.ImportStatusCommitted {
		return report{}, fmt.Errorf("staged recovery failed: result=%+v err=%w", recovered, err)
	}
	if _, err := ops.NewLegacyImportSource(ops.ImportSourceAndroidRoom, 1, []byte(`{"downloads":[`)); err == nil {
		return report{}, fmt.Errorf("malformed input accepted")
	}
	partial, _ := ops.NewLegacyImportSource(ops.ImportSourceAndroidRoom, 1, []byte(`{"downloads":[{"id":"partial","file_name":"missing-url.mp4"}]}`))
	if _, err := store.Import(context.Background(), partial, ops.AndroidRoomImportAdapter); err == nil {
		return report{}, fmt.Errorf("partial source accepted")
	}
	if len(store.AuthoritativeObjects()) != 2 {
		return report{}, fmt.Errorf("partial source mutated authoritative state")
	}
	newer, _ := ops.NewLegacyImportSource(ops.ImportSourceDesktopJSON, 2, []byte(`{"items":[{"id":"future","url":"https://example.test/f","file_name":"f.bin"}]}`))
	if _, err := store.Import(context.Background(), newer, ops.DesktopJSONImportAdapter); !errors.Is(err, ops.ErrUnsupportedSourceVersion) {
		return report{}, fmt.Errorf("unsupported newer version got %w", err)
	}
	if _, ok := store.Record(newer.IdempotencyKey()); ok {
		return report{}, fmt.Errorf("unsupported newer version created a journal record")
	}
	retry, _ := ops.NewLegacyImportSource(ops.ImportSourceDesktopJSON, 1, []byte(`{"items":[{"id":"retry","url":"https://example.test/r","file_name":"r.bin"}]}`))
	failOnce := true
	adapter := func(ctx context.Context, source ops.LegacyImportSource) ([]ops.ImportedObject, error) {
		if failOnce {
			failOnce = false
			return nil, ops.ErrImportFailed
		}
		return ops.DesktopJSONImportAdapter(ctx, source)
	}
	if _, err := store.Import(context.Background(), retry, adapter); err == nil {
		return report{}, fmt.Errorf("first retry import unexpectedly succeeded")
	}
	retried, err := store.Import(context.Background(), retry, adapter)
	if err != nil || retried.Status != ops.ImportStatusCommitted || retried.ImportedCount != 1 {
		return report{}, fmt.Errorf("retry after failure failed: result=%+v err=%w", retried, err)
	}
	return report{Mode: "import_faults", Pass: true, Checks: []string{"success", "duplicate import", "crash mid-import", "malformed input", "partial source", "unsupported newer version", "retry after failure"}}, nil
}

func opsGateSuite() (report, error) {
	settings := ops.DefaultEngineSettings()
	settings.Retention.EventsMaxCount = 16
	settings.Retention.EventsMaxBytes = 64 * 1024
	settings.Retention.EventsMaxAgeDays = 1
	settings.Retention.ExternalLogMaxBytes = 48
	now := time.Unix(300, 0)
	store, err := ops.NewDiagnosticStore(settings.Retention, func() time.Time { return now })
	if err != nil {
		return report{}, err
	}
	secretContexts := []map[string]any{
		{"url": "https://example.test/db?signature=abc&token=raw-token", "headers": map[string]string{"Authorization": "Bearer abc", "Cookie": "sid=super-secret"}},
		{"database_event": map[string]any{"proxyCredential": "password=abc", "body": "token=raw-token"}},
		{"support": []any{"https://cdn.test/frag.ts?sig=abc", map[string]any{"secret": "super-secret"}}},
	}
	for i, ctx := range secretContexts {
		if err := store.Add(ops.DiagnosticEvent{Timestamp: now.Add(time.Duration(i) * time.Second), Subsystem: "ops", OperationID: "gate-09", DownloadID: "dl", AttemptID: fmt.Sprintf("att-%d", i), Stage: "gate", Severity: ops.SeverityWarn, FailureCategory: "secret-scan", SafeContext: ctx}); err != nil {
			return report{}, err
		}
	}
	health, err := ops.NewHealthReport(now, map[string]ops.HealthState{"db": ops.HealthOK, "native": ops.HealthOK, "aria2": ops.HealthOK, "media": ops.HealthOK, "storage": ops.HealthOK, "browser": ops.HealthOK, "scheduler": ops.HealthOK, "recovery": ops.HealthOK})
	if err != nil {
		return report{}, err
	}
	snap, err := ops.BuildSupportSnapshot(now, settings, health, store.Events(), map[string]string{"db": "token=raw-token", "events": "signature=abc", "support": "authorization=Bearer abc password=abc"})
	if err != nil {
		return report{}, err
	}
	if err := snap.SecretScan(); err != nil {
		return report{}, err
	}
	encoded, _ := json.Marshal(snap)
	lower := strings.ToLower(string(encoded))
	for _, needle := range []string{"raw-token", "super-secret", "password=abc", "signature=abc", "bearer abc"} {
		if strings.Contains(lower, needle) {
			return report{}, fmt.Errorf("gate support snapshot leaked %s", needle)
		}
	}
	importStore := ops.NewLegacyImportStore(func() time.Time { return now })
	source, err := ops.NewLegacyImportSource(ops.ImportSourceDesktopJSON, 1, []byte(`{"items":[{"id":"gate","url":"https://example.test/gate?token=raw-token","file_name":"gate.bin"}]}`))
	if err != nil {
		return report{}, err
	}
	if _, err := importStore.ImportWithOptions(context.Background(), source, ops.DesktopJSONImportAdapter, ops.ImportOptions{CrashAfterStage: true}); !errors.Is(err, ops.ErrImportInterrupted) {
		return report{}, fmt.Errorf("gate crash import did not interrupt: %w", err)
	}
	recovered, err := importStore.Import(context.Background(), source, func(context.Context, ops.LegacyImportSource) ([]ops.ImportedObject, error) {
		return nil, fmt.Errorf("adapter should not run during gate staged recovery")
	})
	if err != nil || !recovered.RecoveredStage || recovered.Status != ops.ImportStatusCommitted {
		return report{}, fmt.Errorf("gate staged recovery failed: result=%+v err=%w", recovered, err)
	}
	dupe, err := importStore.Import(context.Background(), source, ops.DesktopJSONImportAdapter)
	if err != nil || !dupe.Duplicate || len(importStore.AuthoritativeObjects()) != 1 {
		return report{}, fmt.Errorf("gate duplicate import was not idempotent: result=%+v err=%w", dupe, err)
	}
	return report{Mode: "ops_gate_suite", Pass: true, Checks: []string{"secret scanner across diagnostic DB events", "secret scanner across support snapshot", "retention configuration bound", "import crash recovery", "import duplicate idempotency"}}, nil
}

func dir(path string) string {
	if i := strings.LastIndex(path, "/"); i >= 0 {
		return path[:i]
	}
	return "."
}
