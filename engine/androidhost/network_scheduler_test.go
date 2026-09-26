package androidhost

import (
	"context"
	"errors"
	"strings"
	"testing"
	"time"

	"github.com/subhra74/xdm/engine/ops"
	"github.com/subhra74/xdm/engine/scheduler"
)

func TestAndroidNetworkPolicySecretProxyAndRuntime(t *testing.T) {
	policy := AndroidNetworkSecurityPolicy{GlobalCleartextAllowed: false, HostCleartextAllowed: map[string]bool{"media.test": true, "denied.test": false}, CertificateStore: "android_network_security_config", TrustUserCAs: true}
	allowed, err := policy.Cleartext("https://media.test/file")
	if err != nil || !allowed.Allowed || allowed.Source != "host_override" {
		t.Fatalf("cleartext allow failed: %+v %v", allowed, err)
	}
	if _, err := policy.Cleartext("denied.test"); !errors.Is(err, ErrCleartextDenied) {
		t.Fatalf("cleartext deny not mapped: %v", err)
	}
	plan, err := policy.TLSPlan()
	if err != nil || plan.CertificateStore != "android_network_security_config" || len(plan.TestRequirements) == 0 {
		t.Fatalf("bad TLS plan: %+v %v", plan, err)
	}
	ref := ops.SecretRef{ID: "cookie", Scope: "https://media.test", Generation: 1}
	store := NewAndroidSecureSecretStore(map[ops.SecretRef]string{ref: "Cookie: raw-secret"})
	res, value, err := ResolveAndroidSecret(context.Background(), store, ref)
	if err != nil || value == "" || !res.Resolved || strings.Contains(res.SafeLog, "raw-secret") {
		t.Fatalf("bad secret resolution: res=%+v value=%q err=%v", res, value, err)
	}
	if _, _, err := ResolveAndroidSecret(context.Background(), store, ops.SecretRef{ID: "missing", Scope: "s", Generation: 1}); !errors.Is(err, ErrSecretNotFound) {
		t.Fatalf("missing secret not mapped: %v", err)
	}
	runtime, err := (AndroidRuntimeConditions{ObservedAt: time.Unix(100, 0), Online: true, Metered: false, WiFi: true, Charging: true, BatteryPercent: 80, StorageFreeBytes: 4096, PowerSource: scheduler.PowerUSB}).ToSchedulerRuntime()
	if err != nil || !runtime.Online || runtime.Metered || !runtime.WiFi || runtime.PowerSource != scheduler.PowerUSB {
		t.Fatalf("bad runtime snapshot: %+v %v", runtime, err)
	}
	manual, err := (AndroidProxyConfig{Mode: ProxyManual, Host: "proxy.test", Port: 8080, NoProxy: []string{"b.test", "a.test"}}).Decision()
	if err != nil || manual.ProxyURL != "http://proxy.test:8080" || manual.BypassList[0] != "a.test" {
		t.Fatalf("bad manual proxy: %+v %v", manual, err)
	}
	pac, err := (AndroidProxyConfig{Mode: ProxyPAC, PACURL: "https://proxy.test/proxy.pac"}).Decision()
	if err != nil || pac.PACURL == "" {
		t.Fatalf("bad PAC proxy: %+v %v", pac, err)
	}
}

func TestAndroidSchedulerHostDelegatesPolicyToGo(t *testing.T) {
	dl := "dl_00000000000000000000000000000070"
	now := time.Unix(1000, 0)
	runtime := scheduler.RuntimeSnapshot{ObservedAtUnixMS: now.UnixMilli(), Online: true, Metered: false, WiFi: true, Charging: true, BatteryPercent: 90, StorageFreeBytes: 1 << 30, PowerSource: scheduler.PowerAC}
	host := NewAndroidSchedulerHost()
	future, err := host.PlanExecutionOpportunity(AndroidExecutionRequest{DownloadID: dl, EventID: "due-later", Now: now, RetryDueAt: now.Add(time.Minute), Runtime: runtime})
	if err != nil {
		t.Fatal(err)
	}
	if future.Wake || future.Primitive != PrimitiveWorkManager || future.DelayUntil.IsZero() || !future.EnginePolicyAuthoritative {
		t.Fatalf("future retry should only schedule a wake: %+v", future)
	}
	uidt, err := host.PlanExecutionOpportunity(AndroidExecutionRequest{DownloadID: dl, EventID: "user", Now: now, UserInitiated: true, Runtime: runtime})
	if err != nil {
		t.Fatal(err)
	}
	if !uidt.Wake || uidt.Primitive != PrimitiveUserInitiatedData || !uidt.EnginePolicyAuthoritative {
		t.Fatalf("bad user initiated decision: %+v", uidt)
	}
	dup, err := host.PlanExecutionOpportunity(AndroidExecutionRequest{DownloadID: dl, EventID: "user", Now: now, UserInitiated: true, Runtime: runtime})
	if err != nil {
		t.Fatal(err)
	}
	if !dup.DuplicateSuppressed || dup.Wake {
		t.Fatalf("duplicate worker not suppressed: %+v", dup)
	}
	blockedRuntime := runtime
	blockedRuntime.WiFi = false
	blocked, err := host.PlanExecutionOpportunity(AndroidExecutionRequest{DownloadID: dl, EventID: "wifi", Now: now, Runtime: blockedRuntime, Conditions: scheduler.ConditionPolicy{RequireWiFi: true}})
	if err != nil {
		t.Fatal(err)
	}
	if blocked.Wake || len(blocked.Holds) != 1 || blocked.Holds[0] != scheduler.HoldWiFiUnavailable {
		t.Fatalf("conditions not forwarded to Go eligibility: %+v", blocked)
	}
	boot, err := host.PlanExecutionOpportunity(AndroidExecutionRequest{DownloadID: dl, EventID: "boot", Now: now, AfterBootRestore: true, Runtime: runtime})
	if err != nil {
		t.Fatal(err)
	}
	if !boot.Wake || boot.Primitive != PrimitiveBootReceiver {
		t.Fatalf("bad boot restore: %+v", boot)
	}
}
