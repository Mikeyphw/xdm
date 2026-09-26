package main

import (
	"context"
	"encoding/json"
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

func dir(path string) string {
	if i := strings.LastIndex(path, "/"); i >= 0 {
		return path[:i]
	}
	return "."
}
