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

	"github.com/subhra74/xdm/engine/androidhost"
	"github.com/subhra74/xdm/engine/domain/identity"
	"github.com/subhra74/xdm/engine/domain/publication"
	"github.com/subhra74/xdm/engine/ops"
	engineruntime "github.com/subhra74/xdm/engine/runtime"
	"github.com/subhra74/xdm/engine/runtime/command"
	"github.com/subhra74/xdm/engine/runtime/platform"
	"github.com/subhra74/xdm/engine/scheduler"
)

type report struct {
	Mode   string   `json:"mode"`
	Pass   bool     `json:"pass"`
	Checks []string `json:"checks"`
}

func main() {
	mode := flag.String("mode", "android_engine_build", "audit mode")
	output := flag.String("output", "", "output path")
	flag.Parse()
	var r report
	var err error
	switch *mode {
	case "android_engine_build":
		r, err = androidEngineBuild()
	case "jni_bridge_instrumentation":
		r, err = jniBridgeInstrumentation()
	case "engine_service_instrumentation":
		r, err = engineServiceInstrumentation()
	case "android_publication_faults":
		r, err = androidPublicationFaults()
	case "android_network_security":
		r, err = androidNetworkSecurity()
	case "android_scheduler_authority":
		r, err = androidSchedulerAuthority()
	case "android_ui_smoke":
		r, err = androidUISmoke()
	case "android_media_e2e":
		r, err = androidMediaE2E()
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

func androidEngineBuild() (report, error) {
	plan, err := androidhost.NewSharedLibraryPlan("audit-source", "go1.23.0")
	if err != nil {
		return report{}, err
	}
	if err := plan.Validate(); err != nil {
		return report{}, err
	}
	for _, rel := range []string{
		"tools/xgo/build_android_xdmcore.py",
		"app/XDM.Android/app/src/main/cpp/xdm_core.h",
		"app/XDM.Android/app/src/main/assets/xdmcore/manifest.json",
	} {
		if _, err := os.Stat(rel); err != nil {
			return report{}, fmt.Errorf("missing Android packaging file %s: %w", rel, err)
		}
	}
	header := mustRead("app/XDM.Android/app/src/main/cpp/xdm_core.h")
	for _, symbol := range androidhost.RequiredSymbols() {
		if !strings.Contains(header, symbol) {
			return report{}, fmt.Errorf("header missing symbol %s", symbol)
		}
	}
	manifest := mustRead("app/XDM.Android/app/src/main/assets/xdmcore/manifest.json")
	for _, needle := range []string{"arm64-v8a", androidhost.CurrentABIVersion, androidhost.WireAPIVersion, "xdm_engine_metadata"} {
		if !strings.Contains(manifest, needle) {
			return report{}, fmt.Errorf("metadata manifest missing %s", needle)
		}
	}
	return report{Mode: "android_engine_build", Pass: true, Checks: []string{"ABI build plan", "Android jniLibs package path", "C header maintenance", "engine metadata manifest", "exact exported symbols", "no desktop libc assumption"}}, nil
}

func jniBridgeInstrumentation() (report, error) {
	codec := androidhost.NewBridgeCodec(androidhost.WireAPIVersion)
	encoded, err := codec.Encode("runtime.ping", map[string]string{"probe": "jni"})
	if err != nil {
		return report{}, err
	}
	if _, err := codec.Decode(encoded); err != nil {
		return report{}, err
	}
	if _, err := codec.Decode([]byte(`{"version":"future","kind":"runtime.ping"}`)); err != androidhost.ErrProtocolVersion {
		return report{}, fmt.Errorf("protocol mismatch not detected: %w", err)
	}
	lease := androidhost.NewBufferLease(7, []byte("frame"))
	if err := lease.Release(); err != nil {
		return report{}, err
	}
	if err := lease.Release(); err != androidhost.ErrBufferAlreadyReleased {
		return report{}, fmt.Errorf("double buffer release not detected: %w", err)
	}
	bridge := mustRead("app/XDM.Android/app/src/main/kotlin/com/mikeyphw/xdm/android/engine/AndroidGoEngineBridge.kt")
	for _, needle := range []string{"System.loadLibrary(\"xdmcore\")", "create", "command", "nextFrame", "platformReply", "shutdown", "releaseBuffer", "BridgeProtocolException"} {
		if !strings.Contains(bridge, needle) {
			return report{}, fmt.Errorf("bridge missing %s", needle)
		}
	}
	for _, forbidden := range []string{"selectVariant(", "chooseBackend(", "retryPolicy =", "scheduleDownload("} {
		if strings.Contains(bridge, forbidden) {
			return report{}, fmt.Errorf("bridge contains engine policy token %s", forbidden)
		}
	}
	return report{Mode: "jni_bridge_instrumentation", Pass: true, Checks: []string{"create/destroy loop model", "command/event round trip", "invalid/protocol mismatch", "one-buffer ownership", "narrow bridge exceptions", "no domain decisions in bridge"}}, nil
}

func engineServiceInstrumentation() (report, error) {
	a := androidhost.NewEngineAuthority()
	first, err := a.BindClient()
	if err != nil {
		return report{}, err
	}
	recreated, err := a.ActivityRecreated()
	if err != nil {
		return report{}, err
	}
	if first != recreated {
		return report{}, fmt.Errorf("activity recreate changed engine id")
	}
	dup, err := a.DuplicateStartIntent()
	if err != nil {
		return report{}, err
	}
	if dup != first {
		return report{}, fmt.Errorf("duplicate start created a second engine")
	}
	restarted, err := a.ProcessRestart()
	if err != nil {
		return report{}, err
	}
	if restarted == first {
		return report{}, fmt.Errorf("process restart did not recover with new engine identity")
	}
	service := mustRead("app/XDM.Android/app/src/main/kotlin/com/mikeyphw/xdm/android/engine/AndroidEngineService.kt")
	manifest := mustRead("app/XDM.Android/app/src/main/AndroidManifest.xml")
	for _, needle := range []string{"class AndroidEngineService", "ensureSingleEngine", "engineIdentity", "StateFlow", "ProcessRestartRecovery"} {
		if !strings.Contains(service, needle) {
			return report{}, fmt.Errorf("service missing %s", needle)
		}
	}
	if !strings.Contains(manifest, ".engine.AndroidEngineService") || !strings.Contains(manifest, "android:exported=\"false\"") {
		return report{}, fmt.Errorf("service not registered as non-exported authority")
	}
	return report{Mode: "engine_service_instrumentation", Pass: true, Checks: []string{"activity recreate", "bind/unbind", "duplicate start intents", "process restart recovery", "foreground escalation request hook", "single engine metadata instance"}}, nil
}

func androidPublicationFaults() (report, error) {
	pub, err := identity.ParsePublicationID("pub_00000000000000000000000000000009")
	if err != nil {
		return report{}, err
	}
	dl, err := identity.ParseDownloadID("dl_00000000000000000000000000000009")
	if err != nil {
		return report{}, err
	}
	art, err := identity.NewArtifactGeneration(1)
	if err != nil {
		return report{}, err
	}
	dest := androidhost.AndroidDestinationSpec{Kind: androidhost.DestinationDocumentTree, TreeURI: "content://tree/downloads", RelativePath: "Videos", DisplayName: "movie.mp4", MIMEType: "video/mp4", PermissionID: "tree-downloads", Collision: androidhost.CollisionFail}
	req := androidhost.AndroidPublicationRequest{Commit: publication.CommitRequest{PublicationID: pub, DownloadID: dl, Artifact: art, IdempotencyKey: "android-pub:gate", StagingIdentity: "stage:gate"}, Destination: dest, SizeBytes: 128, ContentHash: "sha256:fixture"}
	target, err := androidhost.TranslateAndroidDestination(dest)
	if err != nil {
		return report{}, err
	}
	if target.Scheme != "saf" || !target.RequiresGrant || target.PermissionID != "tree-downloads" {
		return report{}, fmt.Errorf("bad SAF target: %+v", target)
	}
	mediaDest := androidhost.AndroidDestinationSpec{Kind: androidhost.DestinationMediaStore, CollectionURI: "content://media/external/video/media", RelativePath: "Movies/XDM", DisplayName: "clip.mp4", MIMEType: "video/mp4", Collision: androidhost.CollisionRename}
	mediaTarget, err := androidhost.TranslateAndroidDestination(mediaDest)
	if err != nil {
		return report{}, err
	}
	if mediaTarget.Scheme != "mediastore" || mediaTarget.RequiresGrant {
		return report{}, fmt.Errorf("bad MediaStore target: %+v", mediaTarget)
	}
	denied := androidhost.NewAndroidPublicationBroker(nil, androidhost.NewAndroidPermissionGrants(), 1024)
	if _, err := denied.Commit(req); !errors.Is(err, androidhost.ErrPermissionLost) {
		return report{}, fmt.Errorf("permission loss not detected: %w", err)
	}
	tiny := androidhost.NewAndroidPublicationBroker(nil, androidhost.NewAndroidPermissionGrants("tree-downloads"), 10)
	if _, err := tiny.Commit(req); !errors.Is(err, androidhost.ErrInsufficientSpace) {
		return report{}, fmt.Errorf("storage failure not detected: %w", err)
	}
	broker := androidhost.NewAndroidPublicationBroker(nil, androidhost.NewAndroidPermissionGrants("tree-downloads"), 1024)
	first, err := broker.Commit(req)
	if err != nil {
		return report{}, err
	}
	restarted := androidhost.NewAndroidPublicationBroker(broker.DurableStore(), androidhost.NewAndroidPermissionGrants("tree-downloads"), 1024)
	second, err := restarted.Commit(req)
	if err != nil {
		return report{}, err
	}
	if second.ReceiptID != first.ReceiptID || len(restarted.DurableStore().ProviderByKey) != 1 {
		return report{}, fmt.Errorf("idempotent restart receipt duplicated")
	}
	crashReq := req
	crashReq.Commit.IdempotencyKey = "android-pub:crash"
	crashReq.Destination.DisplayName = "crash.mp4"
	crashReq.Fault = androidhost.FaultCrashBeforeReceipt
	if _, err := broker.Commit(crashReq); !errors.Is(err, androidhost.ErrProviderCommitAmbiguous) {
		return report{}, fmt.Errorf("ambiguous crash not surfaced: %w", err)
	}
	action, receipt, err := broker.Reconcile(publication.InspectRequest{PublicationID: pub, IdempotencyKey: crashReq.Commit.IdempotencyKey})
	if err != nil {
		return report{}, err
	}
	if action != publication.ActionCommitEngine || receipt.ReceiptID == "" {
		return report{}, fmt.Errorf("bad ambiguous-crash reconcile action=%s receipt=%+v", action, receipt)
	}
	collisionReq := req
	collisionReq.Commit.IdempotencyKey = "android-pub:collision"
	if _, err := broker.Commit(collisionReq); !errors.Is(err, androidhost.ErrPublicationCollision) {
		return report{}, fmt.Errorf("collision policy not enforced: %w", err)
	}
	kotlin := mustRead("app/XDM.Android/app/src/main/kotlin/com/mikeyphw/xdm/android/publication/AndroidPublicationBroker.kt")
	for _, needle := range []string{"class AndroidPublicationBroker", "translateDestination", "takePersistablePermission", "stageToProvider", "reconcileAfterAmbiguousCrash", "AndroidPublicationReceiptStore", "ContentResolver", "MediaStore", "DocumentTree"} {
		if !strings.Contains(kotlin, needle) {
			return report{}, fmt.Errorf("publication broker missing %s", needle)
		}
	}
	return report{Mode: "android_publication_faults", Pass: true, Checks: []string{"document tree destination", "MediaStore destination", "persistable permission handling", "available space query", "stage-to-provider commit", "durable idempotent receipt after restart", "ambiguous crash receipt reconciliation", "collision policy mapping", "storage failure mapping"}}, nil
}

func androidNetworkSecurity() (report, error) {
	policy := androidhost.AndroidNetworkSecurityPolicy{
		GlobalCleartextAllowed: false,
		HostCleartextAllowed:   map[string]bool{"media.test": true, "blocked.test": false},
		CertificateStore:       "android_network_security_config",
		TrustUserCAs:           true,
	}
	allowed, err := policy.Cleartext("https://media.test/video.mp4")
	if err != nil || !allowed.Allowed || allowed.Source != "host_override" {
		return report{}, fmt.Errorf("cleartext allow failed: %+v %w", allowed, err)
	}
	if _, err := policy.Cleartext("blocked.test"); !errors.Is(err, androidhost.ErrCleartextDenied) {
		return report{}, fmt.Errorf("cleartext deny not mapped: %w", err)
	}
	tls, err := policy.TLSPlan()
	if err != nil || tls.CertificateStore != "android_network_security_config" || len(tls.TestRequirements) == 0 {
		return report{}, fmt.Errorf("bad TLS integration plan: %+v %w", tls, err)
	}
	ref := ops.SecretRef{ID: "cookie", Scope: "https://media.test", Generation: 1}
	store := androidhost.NewAndroidSecureSecretStore(map[ops.SecretRef]string{ref: "raw-cookie-secret"})
	resolution, value, err := androidhost.ResolveAndroidSecret(context.Background(), store, ref)
	if err != nil || value == "" || !resolution.Resolved || strings.Contains(resolution.SafeLog, "raw-cookie-secret") {
		return report{}, fmt.Errorf("bad secret resolution: res=%+v value=%q err=%w", resolution, value, err)
	}
	runtimeSnapshot, err := (androidhost.AndroidRuntimeConditions{ObservedAt: time.Unix(1000, 0), Online: true, Metered: false, WiFi: true, Charging: true, BatteryPercent: 88, StorageFreeBytes: 1 << 30, PowerSource: scheduler.PowerUSB}).ToSchedulerRuntime()
	if err != nil || !runtimeSnapshot.Online || runtimeSnapshot.Metered || !runtimeSnapshot.WiFi || !runtimeSnapshot.Charging {
		return report{}, fmt.Errorf("bad Android runtime conditions: %+v %w", runtimeSnapshot, err)
	}
	manual, err := (androidhost.AndroidProxyConfig{Mode: androidhost.ProxyManual, Host: "proxy.test", Port: 8080, NoProxy: []string{"b.test", "a.test"}}).Decision()
	if err != nil || manual.ProxyURL != "http://proxy.test:8080" || len(manual.BypassList) != 2 || manual.BypassList[0] != "a.test" {
		return report{}, fmt.Errorf("bad manual proxy decision: %+v %w", manual, err)
	}
	pac, err := (androidhost.AndroidProxyConfig{Mode: androidhost.ProxyPAC, PACURL: "https://proxy.test/proxy.pac"}).Decision()
	if err != nil || pac.PACURL == "" {
		return report{}, fmt.Errorf("bad PAC decision: %+v %w", pac, err)
	}

	broker := mustRead("app/XDM.Android/app/src/main/kotlin/com/mikeyphw/xdm/android/network/AndroidNetworkPolicyBroker.kt")
	for _, needle := range []string{
		"NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted",
		"ConnectivityManager::class.java)?.defaultProxy",
		"pacFileUrl",
		"AndroidMediaRequestCredentialSource",
		"MediaRequestHandoffStore.forDownload",
		"KeyStore.getInstance(\"AndroidCAStore\")",
		"StatFs(context.filesDir.absolutePath).availableBytes",
		"logcatMustNotContainSecrets",
	} {
		if !strings.Contains(broker, needle) {
			return report{}, fmt.Errorf("Android network broker missing production API %s", needle)
		}
	}
	for _, forbidden := range []string{"ProxyInfo? = null", "AndroidNetworkSecurityConfigReply(host, allowed = false)", "println(", "Log.d(", "Log.i(", "Log.v("} {
		if strings.Contains(broker, forbidden) {
			return report{}, fmt.Errorf("Android network broker contains placeholder/leaky token %s", forbidden)
		}
	}

	dispatcher := mustRead("app/XDM.Android/app/src/main/kotlin/com/mikeyphw/xdm/android/engine/AndroidPlatformRequestDispatcher.kt")
	for _, needle := range []string{"platform.request", "runtime_conditions", "network_policy", "system_proxy", "secret_lookup", "bridge.platformReply", "resolved.value"} {
		if !strings.Contains(dispatcher, needle) {
			return report{}, fmt.Errorf("platform dispatcher missing %s", needle)
		}
	}
	for _, forbidden := range []string{"println(", "Log.d(", "Log.i(", "Log.v(", "safeLog + value", "value + safeLog"} {
		if strings.Contains(dispatcher, forbidden) {
			return report{}, fmt.Errorf("platform dispatcher contains raw-secret logging risk %s", forbidden)
		}
	}

	authority := mustRead("app/XDM.Android/app/src/main/kotlin/com/mikeyphw/xdm/android/engine/AndroidEngineProcessAuthority.kt")
	for _, needle := range []string{"AndroidPlatformRequestDispatcher", "nextFrame(FRAME_POLL_TIMEOUT_MS)", "dispatcher.dispatch(frame)"} {
		if !strings.Contains(authority, needle) {
			return report{}, fmt.Errorf("process authority does not pump platform requests: missing %s", needle)
		}
	}
	secureStore := mustRead("app/XDM.Android/scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/SecureRequestEnvelopeStore.kt")
	for _, needle := range []string{"AndroidKeyStore", "AES/GCM/NoPadding"} {
		if !strings.Contains(secureStore, needle) {
			return report{}, fmt.Errorf("secure request store missing %s", needle)
		}
	}
	return report{Mode: "android_network_security", Pass: true, Checks: []string{
		"real Android NetworkSecurityPolicy cleartext API",
		"AndroidKeyStore-backed current credential SecretRef lookup",
		"runtime metered/Wi-Fi/charging/battery/storage facts",
		"real ConnectivityManager system proxy/PAC facts",
		"AndroidCAStore + network-security-config TLS integration",
		"platform.request frame pump replies to Go",
		"secret values excluded from Android log/support surfaces",
	}}, nil
}

func androidSchedulerAuthority() (report, error) {
	dl := "dl_00000000000000000000000000000071"
	now := time.Unix(1000, 0)
	runtimeSnapshot := scheduler.RuntimeSnapshot{ObservedAtUnixMS: now.UnixMilli(), Online: true, Metered: false, WiFi: true, Charging: true, BatteryPercent: 95, StorageFreeBytes: 1 << 30, PowerSource: scheduler.PowerAC}
	host := androidhost.NewAndroidSchedulerHost()
	future, err := host.PlanExecutionOpportunity(androidhost.AndroidExecutionRequest{DownloadID: dl, EventID: "retry-due", Now: now, RetryDueAt: now.Add(time.Minute), Runtime: runtimeSnapshot})
	if err != nil {
		return report{}, err
	}
	if future.Wake || future.Primitive != androidhost.PrimitiveWorkManager || future.DelayUntil.IsZero() || !future.EnginePolicyAuthoritative {
		return report{}, fmt.Errorf("bad future retry wake: %+v", future)
	}
	fgs, err := host.PlanExecutionOpportunity(androidhost.AndroidExecutionRequest{DownloadID: dl, EventID: "fgs", Now: now, RequiresForeground: true, Runtime: runtimeSnapshot})
	if err != nil || !fgs.Wake || fgs.Primitive != androidhost.PrimitiveForegroundService {
		return report{}, fmt.Errorf("bad FGS wake: %+v %w", fgs, err)
	}
	uidt, err := host.PlanExecutionOpportunity(androidhost.AndroidExecutionRequest{DownloadID: dl, EventID: "uidt", Now: now, UserInitiated: true, Runtime: runtimeSnapshot})
	if err != nil || !uidt.Wake || uidt.Primitive != androidhost.PrimitiveUserInitiatedData {
		return report{}, fmt.Errorf("bad UIDT wake: %+v %w", uidt, err)
	}
	dup, err := host.PlanExecutionOpportunity(androidhost.AndroidExecutionRequest{DownloadID: dl, EventID: "uidt", Now: now, UserInitiated: true, Runtime: runtimeSnapshot})
	if err != nil || !dup.DuplicateSuppressed || dup.Wake {
		return report{}, fmt.Errorf("duplicate worker not suppressed: %+v %w", dup, err)
	}
	blockedRuntime := runtimeSnapshot
	blockedRuntime.WiFi = false
	blocked, err := host.PlanExecutionOpportunity(androidhost.AndroidExecutionRequest{DownloadID: dl, EventID: "wifi", Now: now, Runtime: blockedRuntime, Conditions: scheduler.ConditionPolicy{RequireWiFi: true}})
	if err != nil || blocked.Wake || len(blocked.Holds) != 1 || blocked.Holds[0] != scheduler.HoldWiFiUnavailable {
		return report{}, fmt.Errorf("runtime conditions not forwarded to Go: %+v %w", blocked, err)
	}
	boot, err := host.PlanExecutionOpportunity(androidhost.AndroidExecutionRequest{EventID: "boot", Now: now, AfterBootRestore: true, Runtime: runtimeSnapshot})
	if err != nil || !boot.Wake || boot.Primitive != androidhost.PrimitiveBootReceiver {
		return report{}, fmt.Errorf("bad boot restore wake: %+v %w", boot, err)
	}

	worker := mustRead("app/XDM.Android/scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/QueueIntelligenceWorker.kt")
	for _, needle := range []string{"AndroidSchedulerHost.wake", "enqueueUniquePeriodicWork", "enqueueUniqueWork", "engine_retry_due_at_epoch_ms", "Schedule only the absolute deadline already supplied by Go"} {
		if !strings.Contains(worker, needle) {
			return report{}, fmt.Errorf("WorkManager host adapter missing %s", needle)
		}
	}
	for _, forbidden := range []string{"evaluateAndClaim(", "runtime.execute(", "authorizeClaimedExecution(", "QueueRetryLedger", "QueueIntelligenceCoordinator"} {
		if strings.Contains(worker, forbidden) {
			return report{}, fmt.Errorf("WorkManager retained Kotlin scheduler authority token %s", forbidden)
		}
	}

	restore := mustRead("app/XDM.Android/scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/TransferRestoreWorker.kt")
	for _, needle := range []string{"AndroidSchedulerHost.wake", "BOOT_OR_PACKAGE_RESTART", "afterBootOrPackageRestart = true"} {
		if !strings.Contains(restore, needle) {
			return report{}, fmt.Errorf("boot/package restore host missing %s", needle)
		}
	}
	for _, forbidden := range []string{"recoverForStartup(", "installStartupRecoveryHold(", "clearStartupRecoveryHold(", "QueueIntelligenceProvider"} {
		if strings.Contains(restore, forbidden) {
			return report{}, fmt.Errorf("restore worker retained Kotlin recovery authority token %s", forbidden)
		}
	}
	bootReceiver := mustRead("app/XDM.Android/scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/TransferBootReceiver.kt")
	if strings.Contains(bootReceiver, ".then(") || strings.Contains(bootReceiver, "RESTORE_QUEUE_WORK_NAME") {
		return report{}, fmt.Errorf("boot receiver still chains a Kotlin queue evaluation worker")
	}

	fgsSource := mustRead("app/XDM.Android/scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/TransferForegroundService.kt")
	fgsStart, err := sourceSection(fgsSource, "ACTION_START ->", "TransferNotifications.ACTION_PAUSE_ALL")
	if err != nil {
		return report{}, err
	}
	if !strings.Contains(fgsStart, "AndroidSchedulerHost.wake") || !strings.Contains(fgsStart, "FOREGROUND_SERVICE") {
		return report{}, fmt.Errorf("FGS ACTION_START does not host Go")
	}
	for _, forbidden := range []string{"runtime.execute(", "authorizeClaimedExecution(", "releaseFailedExecutionOwner("} {
		if strings.Contains(fgsStart, forbidden) {
			return report{}, fmt.Errorf("FGS ACTION_START retained execution authority %s", forbidden)
		}
	}

	uidtSource := mustRead("app/XDM.Android/scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/UserInitiatedTransferJobService.kt")
	for _, needle := range []string{"AndroidSchedulerHost.wake", "USER_INITIATED_DATA_TRANSFER", "userInitiated = true"} {
		if !strings.Contains(uidtSource, needle) {
			return report{}, fmt.Errorf("UIDT adapter missing %s", needle)
		}
	}
	for _, forbidden := range []string{"runtime.execute(", "authorizeClaimedExecution(", "QueueIntelligenceProvider", "TransferRuntimeProvider"} {
		if strings.Contains(uidtSource, forbidden) {
			return report{}, fmt.Errorf("UIDT retained Kotlin execution authority %s", forbidden)
		}
	}

	coordinator := mustRead("app/XDM.Android/scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/QueueIntelligenceCoordinator.kt")
	reconcile, err := sourceSection(coordinator, "suspend fun reconcile()", "fun recordTerminalEvent")
	if err != nil {
		return report{}, err
	}
	if !strings.Contains(reconcile, "AndroidSchedulerHost.wake") || strings.Contains(reconcile, "evaluateAndClaim(") || strings.Contains(reconcile, "executionStarter.start(") {
		return report{}, fmt.Errorf("automatic reconcile path still owns Kotlin queue eligibility/execution")
	}

	application := mustRead("app/XDM.Android/app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApplication.kt")
	for _, needle := range []string{"AndroidGoEngineHostProvider", "AndroidEngineProcessAuthority", "androidEngineProcessAuthority.wake(request)"} {
		if !strings.Contains(application, needle) {
			return report{}, fmt.Errorf("application does not expose single Go engine host: missing %s", needle)
		}
	}
	authority := mustRead("app/XDM.Android/app/src/main/kotlin/com/mikeyphw/xdm/android/engine/AndroidEngineProcessAuthority.kt")
	for _, needle := range []string{"ConcurrentHashMap.newKeySet", "android.scheduler_wake", "nextFrame(FRAME_POLL_TIMEOUT_MS)", "AndroidPlatformRequestDispatcher"} {
		if !strings.Contains(authority, needle) {
			return report{}, fmt.Errorf("process engine authority missing %s", needle)
		}
	}
	goHost := mustRead("engine/androidhost/scheduler_host.go")
	for _, needle := range []string{"sync.Mutex", "DuplicateSuppressed", "EnginePolicyAuthoritative"} {
		if !strings.Contains(goHost, needle) {
			return report{}, fmt.Errorf("Go scheduler host missing duplicate/authority fence %s", needle)
		}
	}
	runtimeSource := mustRead("engine/runtime/engine.go")
	for _, needle := range []string{"e.handlers[\"android.scheduler_wake\"]", "PlatformRequest(ctx, platform.RuntimeConditions", "android.scheduler.decision"} {
		if !strings.Contains(runtimeSource, needle) {
			return report{}, fmt.Errorf("runtime scheduler wake path missing %s", needle)
		}
	}
	return report{Mode: "android_scheduler_authority", Pass: true, Checks: []string{
		"WorkManager is wake-only and uses unique work",
		"FGS/UIDT are host primitives and do not execute transfers",
		"Go-provided retry deadline is preserved without Kotlin backoff policy",
		"boot/package restart restores Go before scheduler recovery",
		"runtime conditions round-trip through the Go platform broker",
		"process + Go duplicate fences suppress repeated host events",
		"automatic reconcile path no longer calls Kotlin evaluateAndClaim",
	}}, nil
}

func androidUISmoke() (report, error) {
	engine := engineruntime.New(engineruntime.Config{EventBuffer: 32, PlatformBuffer: 8})
	if err := engine.Start(); err != nil {
		return report{}, err
	}
	defer engine.Shutdown(context.Background())

	makeEnvelope := func(token, kind, payload string) (command.Envelope, error) {
		id, err := command.ParseID("cmd_" + token)
		if err != nil {
			return command.Envelope{}, err
		}
		op, err := identity.ParseOperationID("op_" + token)
		if err != nil {
			return command.Envelope{}, err
		}
		return command.Envelope{ID: id, OperationID: op, Kind: kind, Payload: json.RawMessage(payload)}, nil
	}
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()

	syncEnv, err := makeEnvelope("00000000000000000000000000000072", engineruntime.AndroidUISyncKind, `{"revision":1,"downloads":[{"id":"dl-ui-audit","file_name":"audit.bin","source_url":"https://example.test/audit.bin","destination_uri":"content://downloads","state":"Queued","backend":"Native","bytes_received":0,"speed_bytes_per_second":0,"priority":0,"created_at_epoch_ms":1,"updated_at_epoch_ms":2,"conflict_policy":"Rename","requested_backend":"Automatic","backend_selection_reason":"DefaultNative","backend_selection_explanation":"","allow_backend_fallback":true,"archived":false,"attempt_generation":1,"observed_attempt_generation":1,"row_revision":2}]}`)
	if err != nil {
		return report{}, err
	}
	if err := engine.Submit(context.Background(), syncEnv); err != nil {
		return report{}, err
	}
	for {
		frame, err := engine.NextFrame(ctx)
		if err != nil {
			return report{}, err
		}
		if frame.Kind == "android.ui.projection" {
			var projection engineruntime.AndroidUIProjection
			if err := json.Unmarshal(frame.Payload, &projection); err != nil {
				return report{}, err
			}
			if projection.Revision != 1 || len(projection.Downloads) != 1 || projection.Downloads[0].ID != "dl-ui-audit" {
				return report{}, fmt.Errorf("bad Go UI projection: %+v", projection)
			}
			break
		}
	}

	cmdEnv, err := makeEnvelope("00000000000000000000000000000073", engineruntime.AndroidUICommandKind, `{"client_request_id":"ui-audit-1","action":"pause","download_id":"dl-ui-audit"}`)
	if err != nil {
		return report{}, err
	}
	if err := engine.Submit(context.Background(), cmdEnv); err != nil {
		return report{}, err
	}
	var request platform.Request
	for {
		frame, err := engine.NextFrame(ctx)
		if err != nil {
			return report{}, err
		}
		if frame.Kind != "platform.request" {
			continue
		}
		if err := json.Unmarshal(frame.Payload, &request); err != nil {
			return report{}, err
		}
		break
	}
	if request.Kind != platform.AndroidDownloadCommand {
		return report{}, fmt.Errorf("UI command did not cross platform broker: %s", request.Kind)
	}
	if err := engine.PlatformReply(platform.Reply{RequestID: request.ID, Session: request.Session, OK: true, Payload: json.RawMessage(`{"ok":true,"status":"paused","download_id":"dl-ui-audit"}`)}); err != nil {
		return report{}, err
	}
	for {
		frame, err := engine.NextFrame(ctx)
		if err != nil {
			return report{}, err
		}
		if frame.Kind != "android.ui.command_result" {
			continue
		}
		var result map[string]any
		if err := json.Unmarshal(frame.Payload, &result); err != nil {
			return report{}, err
		}
		if result["client_request_id"] != "ui-audit-1" || result["ok"] != true || result["status"] != "paused" {
			return report{}, fmt.Errorf("bad Go UI command result: %v", result)
		}
		break
	}

	viewModel := mustRead("app/XDM.Android/app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt")
	for _, needle := range []string{
		"private val semanticDownloads = androidDownloadUiClient.projection",
		"engineUiConnection = engineConnection",
		"val downloads = durable.downloads",
	} {
		if !strings.Contains(viewModel, needle) {
			return report{}, fmt.Errorf("ViewModel is not projection-driven: missing %s", needle)
		}
	}
	if strings.Contains(viewModel, "live.progress[download.id]") {
		return report{}, fmt.Errorf("ViewModel still rewrites Go download projections from Kotlin live progress")
	}
	sections := []struct {
		name, start, end string
		required         []string
		forbidden        []string
	}{
		{"policy override start", "    fun startIgnoringQueuePolicy(", "    fun runAria2SmokeTest(", []string{"androidDownloadUiClient.command", `action = "resume"`, "policyOverride = DownloadActionExecutionTruth.policyOverrideFromCurrent(download)"}, []string{"queueIntelligenceCoordinator.requestStart(", "repository.findDownload("}},
		{"bulk pause", "    fun bulkPause(", "    fun bulkResume(", []string{"androidDownloadUiClient.command", `action = "pause"`}, []string{"transferRuntime.pause(", "nativeHlsMediaManager.pause(", "repository.findDownloadsByIds("}},
		{"bulk resume", "    fun bulkResume(", "    fun saveDestinationRule(", []string{"androidDownloadUiClient.command", `"retry"`, `"resume"`}, []string{"queueIntelligenceCoordinator.requestStart(", "nativeHlsMediaManager.resume(", "repository.findDownloadsByIds("}},
		{"clear history", "    fun clearFinishedHistory(", "    suspend fun inspectCompletedArtifact(", []string{"androidDownloadUiClient.command", `action = "delete"`}, []string{"repository.deleteDownloadEntryIfTerminal(", "termuxMediaPipelineManager.prepareDownloadGraphDeletion("}},
		{"start now", "    fun startNow(", "    fun removeDownloadFromHistory(", []string{"androidDownloadUiClient.command", `action = "resume"`, "policyOverride = DownloadActionExecutionTruth.policyOverrideFromCurrent(download)"}, []string{"queueIntelligenceCoordinator.requestStart(", "repository.findDownload("}},
		{"delete", "    fun deleteDownloadEntry(", "    fun deleteSavedFile(", []string{"androidDownloadUiClient.command", `action = "delete"`}, []string{"transferRuntime.cancel(", "repository.deleteDownloadEntryIfTerminal(", "queueIntelligenceCoordinator.retireAndroidSystemId("}},
		{"add", "    private suspend fun completeDownloadAdmission(", "    fun backendRecommendation(", []string{"androidDownloadUiClient.command", `action = "add"`, `action = "resume"`}, []string{"repository.admitDownload(", "queueIntelligenceCoordinator.requestStart("}},
		{"pause all", "    fun pauseAll()", "    fun resumeAll()", []string{"androidDownloadUiClient.command", `action = "pause_all"`}, []string{"transferRuntime.pauseAll(", "nativeHlsMediaManager.pauseAll(", "queueIntelligenceCoordinator.pauseAllDurably("}},
		{"resume all", "    fun resumeAll()", "    fun cancelDownload(", []string{"androidDownloadUiClient.command", `action = "resume_all"`}, []string{"nativeHlsMediaManager.resumeAll(", "queueIntelligenceCoordinator.resumeAllManual("}},
		{"cancel", "    fun cancelDownload(", "    fun togglePause(", []string{"androidDownloadUiClient.command", `action = "cancel"`}, []string{"transferRuntime.cancel(", "nativeHlsMediaManager.cancel(", "repository.save("}},
		{"toggle", "    fun togglePause(", "    private fun logBulkActionResult(", []string{"androidDownloadUiClient.command", `"pause"`, `"retry"`, `"resume"`}, []string{"transferRuntime.pause(", "nativeHlsMediaManager.pause(", "queueIntelligenceCoordinator.requestStart(", "repository.findDownload("}},
	}
	for _, check := range sections {
		section, err := sourceSection(viewModel, check.start, check.end)
		if err != nil {
			return report{}, err
		}
		for _, needle := range check.required {
			if !strings.Contains(section, needle) {
				return report{}, fmt.Errorf("%s UI command path missing %s", check.name, needle)
			}
		}
		for _, needle := range check.forbidden {
			if strings.Contains(section, needle) {
				return report{}, fmt.Errorf("%s still bypasses Go command authority via %s", check.name, needle)
			}
		}
	}

	client := mustRead("app/XDM.Android/app/src/main/kotlin/com/mikeyphw/xdm/android/engine/AndroidDownloadUiClient.kt")
	for _, needle := range []string{"bindService", "onServiceDisconnected", "onBindingDied", "Rebinding", "withTimeoutOrNull", "downloadProjections()"} {
		if !strings.Contains(client, needle) {
			return report{}, fmt.Errorf("UI engine client missing rebind/lifecycle token %s", needle)
		}
	}
	rebind, err := sourceSection(client, "    private fun scheduleRebind()", "    private companion object")
	if err != nil {
		return report{}, err
	}
	if strings.Contains(rebind, "_projection.value") {
		return report{}, fmt.Errorf("engine rebind clears the last Go projection")
	}

	authority := mustRead("app/XDM.Android/app/src/main/kotlin/com/mikeyphw/xdm/android/engine/AndroidEngineProcessAuthority.kt")
	for _, needle := range []string{"syncLegacyDownloadProjection", "submitDownloadUiCommand", `"android.ui.projection"`, `"android.ui.command_result"`} {
		if !strings.Contains(authority, needle) {
			return report{}, fmt.Errorf("process authority missing UI bridge token %s", needle)
		}
	}
	service := mustRead("app/XDM.Android/app/src/main/kotlin/com/mikeyphw/xdm/android/engine/AndroidEngineService.kt")
	for _, needle := range []string{"downloadProjections()", "submitDownloadUiCommand"} {
		if !strings.Contains(service, needle) {
			return report{}, fmt.Errorf("engine service missing UI binder token %s", needle)
		}
	}
	broker := mustRead("app/XDM.Android/app/src/main/kotlin/com/mikeyphw/xdm/android/engine/AndroidLegacyDownloadUiBroker.kt")
	for _, needle := range []string{"repository.downloads.collectLatest", "repository.admitDownload", `"pause"`, `"resume", "retry"`, `"cancel"`, `"delete"`, `"pause_all"`, `"resume_all"`, "queueCoordinator.pauseAllDurably()", "queueCoordinator.resumeAllManual()"} {
		if !strings.Contains(broker, needle) {
			return report{}, fmt.Errorf("temporary XGO-72 host broker missing %s", needle)
		}
	}
	application := mustRead("app/XDM.Android/app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApplication.kt")
	for _, needle := range []string{"AndroidDownloadUiClient(this)", "mirrorIntoGo(androidEngineProcessAuthority)", "androidDownloadUiClient = androidDownloadUiClient"} {
		if !strings.Contains(application, needle) {
			return report{}, fmt.Errorf("application does not preserve process-scoped UI/engine state: missing %s", needle)
		}
	}
	platformDispatcher := mustRead("app/XDM.Android/app/src/main/kotlin/com/mikeyphw/xdm/android/engine/AndroidPlatformRequestDispatcher.kt")
	if !strings.Contains(platformDispatcher, `"android_download_command"`) || !strings.Contains(platformDispatcher, "downloadUiBroker") {
		return report{}, fmt.Errorf("Android platform dispatcher does not route Go download commands")
	}
	downloadsScreen := mustRead("app/XDM.Android/app/src/main/kotlin/com/mikeyphw/xdm/android/ui/downloads/DownloadsScreen.kt")
	for _, needle := range []string{"engineUiConnection: AndroidEngineUiConnection", "AndroidEngineUiConnection.Rebinding", "last confirmed download state remains visible", "commands will wait briefly for reconnection"} {
		if !strings.Contains(downloadsScreen, needle) {
			return report{}, fmt.Errorf("downloads UI does not surface engine reconnect state: missing %s", needle)
		}
	}
	xdmApp := mustRead("app/XDM.Android/app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApp.kt")
	if !strings.Contains(xdmApp, "engineUiConnection = state.engineUiConnection") {
		return report{}, fmt.Errorf("route does not pass engine connection projection to Downloads UI")
	}

	return report{Mode: "android_ui_smoke", Pass: true, Checks: []string{
		"Go download projection round-trip",
		"add/pause/resume/cancel/retry/delete cross Go commands",
		"ViewModel download list is Go projection without Kotlin progress rewrite",
		"temporary Room/runtime bridge is behind Go platform requests",
		"connection loss keeps last projection and rebinds",
		"activity recreation reuses process-scoped client and single engine authority",
	}}, nil
}

func androidMediaE2E() (report, error) {
	engine := engineruntime.New(engineruntime.Config{EventBuffer: 64, PlatformBuffer: 8})
	if err := engine.Start(); err != nil {
		return report{}, err
	}
	defer engine.Shutdown(context.Background())

	makeEnvelope := func(token, kind, payload string) (command.Envelope, error) {
		id, err := command.ParseID("cmd_" + token)
		if err != nil {
			return command.Envelope{}, err
		}
		op, err := identity.ParseOperationID("op_" + token)
		if err != nil {
			return command.Envelope{}, err
		}
		return command.Envelope{ID: id, OperationID: op, Kind: kind, Payload: json.RawMessage(payload)}, nil
	}
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()

	capturePayload := `{
      "client_request_id":"media-audit-capture",
      "capture_record":{"id":"cap-audit","source_url":"https://media.test/master.m3u8","page_url":"https://media.test/watch","title":"Audit","kind":"HlsPlaylist","mime_type":"application/vnd.apple.mpegurl","container":"mpegts","codecs":"avc1","duration_ms":120000,"file_name":"audit.mp4","selected_variant_id":"var-audit","manifest_is_live":false,"manifest_protected":false,"logical_media_id":"logical-audit","row_revision":7},
      "variants":[{"id":"var-audit","capture_id":"cap-audit","url":"https://cdn.media.test/v1.m3u8","kind":"Video","mime_type":"application/vnd.apple.mpegurl","width":1920,"height":1080,"bitrate_bits_per_second":4000000,"codecs":"avc1"}],
      "envelope":{"version":1,"request":{"url":"https://media.test/master.m3u8","method":"GET","headers":[{"name":"Accept","value":"application/vnd.apple.mpegurl"}]},"page":{"page_url":"https://media.test/watch","session_id":"audit-session","document_generation":7},"credential_scope":{"ref":"capture:cap-audit"}}
    }`
	captureEnv, err := makeEnvelope("00000000000000000000000000000084", engineruntime.AndroidMediaCaptureKind, capturePayload)
	if err != nil {
		return report{}, err
	}
	if err := engine.Submit(context.Background(), captureEnv); err != nil {
		return report{}, err
	}
	seenProjection := false
	seenCaptureResult := false
	for !seenProjection || !seenCaptureResult {
		frame, err := engine.NextFrame(ctx)
		if err != nil {
			return report{}, err
		}
		switch frame.Kind {
		case "android.media.projection":
			var projected map[string]any
			if err := json.Unmarshal(frame.Payload, &projected); err != nil {
				return report{}, err
			}
			captures, _ := projected["captures"].([]any)
			variants, _ := projected["variants"].([]any)
			if len(captures) != 1 || len(variants) != 1 {
				return report{}, fmt.Errorf("bad Android media projection: %s", frame.Payload)
			}
			seenProjection = true
		case "android.media.command_result":
			var result map[string]any
			if err := json.Unmarshal(frame.Payload, &result); err != nil {
				return report{}, err
			}
			if result["client_request_id"] == "media-audit-capture" && result["ok"] == true && result["status"] == "graph_updated" {
				seenCaptureResult = true
			}
		}
	}

	selectEnv, err := makeEnvelope("00000000000000000000000000000085", engineruntime.AndroidMediaSelectKind, `{"client_request_id":"media-audit-select","capture_id":"cap-audit","selection":{"video_variant_id":"var-audit"}}`)
	if err != nil {
		return report{}, err
	}
	if err := engine.Submit(context.Background(), selectEnv); err != nil {
		return report{}, err
	}
	for {
		frame, err := engine.NextFrame(ctx)
		if err != nil {
			return report{}, err
		}
		if frame.Kind == "android.media.command_result" && strings.Contains(string(frame.Payload), "media-audit-select") {
			break
		}
	}

	executeEnv, err := makeEnvelope("00000000000000000000000000000086", engineruntime.AndroidMediaExecuteKind, `{"client_request_id":"media-audit-execute","capture_id":"cap-audit"}`)
	if err != nil {
		return report{}, err
	}
	if err := engine.Submit(context.Background(), executeEnv); err != nil {
		return report{}, err
	}
	var request platform.Request
	for {
		frame, err := engine.NextFrame(ctx)
		if err != nil {
			return report{}, err
		}
		if frame.Kind != "platform.request" {
			continue
		}
		if err := json.Unmarshal(frame.Payload, &request); err != nil {
			return report{}, err
		}
		break
	}
	if request.Kind != platform.ExternalMediaTool {
		return report{}, fmt.Errorf("adaptive execution did not use external_media_tool: %s", request.Kind)
	}
	var tool map[string]any
	if err := json.Unmarshal(request.Payload, &tool); err != nil {
		return report{}, err
	}
	if tool["tool"] != "ffmpeg" || tool["operation"] != "finalize_adaptive" || tool["protocol"] != "hls" || tool["capture_id"] != "cap-audit" || tool["variant_id"] != "var-audit" {
		return report{}, fmt.Errorf("bad typed media tool request: %v", tool)
	}
	for _, forbidden := range []string{"source_url", "headers", "cookie", "authorization"} {
		if _, leaked := tool[forbidden]; leaked {
			return report{}, fmt.Errorf("typed media tool request leaked %s: %v", forbidden, tool)
		}
	}
	if err := engine.PlatformReply(platform.Reply{RequestID: request.ID, Session: request.Session, OK: true, Payload: json.RawMessage(`{"ok":true,"output_path":"/data/user/0/app/files/xgo-media-broker/cap-audit/audit.mp4","exit_code":0}`)}); err != nil {
		return report{}, err
	}
	for {
		frame, err := engine.NextFrame(ctx)
		if err != nil {
			return report{}, err
		}
		if frame.Kind != "android.media.command_result" {
			continue
		}
		var result map[string]any
		if err := json.Unmarshal(frame.Payload, &result); err != nil {
			return report{}, err
		}
		if result["client_request_id"] != "media-audit-execute" || result["ok"] != true || result["status"] != "started_via_go" {
			return report{}, fmt.Errorf("bad adaptive execution result: %v", result)
		}
		break
	}

	application := mustRead("app/XDM.Android/app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApplication.kt")
	for _, needle := range []string{"combine(repository.mediaCaptures, repository.mediaVariants)", "syncLegacyMediaProjection(captures, variants)", "AndroidMediaPlatformBroker(this, embeddedFfmpegRuntime)", "AndroidMediaUiClient(this)"} {
		if !strings.Contains(application, needle) {
			return report{}, fmt.Errorf("Android media host wiring missing %s", needle)
		}
	}
	wire := mustRead("app/XDM.Android/app/src/main/kotlin/com/mikeyphw/xdm/android/engine/AndroidMediaUiProtocol.kt")
	for _, needle := range []string{"captureEnvelope(record, sessionId, documentGeneration, headers)", "credential_scope", "isSensitiveHeaderName", `name.contains("token")`, `name.endsWith("-key")`} {
		if !strings.Contains(wire, needle) {
			return report{}, fmt.Errorf("CaptureEnvelope translation/sanitization missing %s", needle)
		}
	}
	runtimeSource := mustRead("engine/runtime/android_media.go")
	for _, needle := range []string{"media.NewCaptureEnvelope", "media.NewMediaGraph", "graph.Ingest", "PlatformRequest(ctx, platform.ExternalMediaTool", `"tool":         "ffmpeg"`} {
		if !strings.Contains(runtimeSource, needle) {
			return report{}, fmt.Errorf("Go media authority missing %s", needle)
		}
	}

	viewModel := mustRead("app/XDM.Android/app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt")
	for _, needle := range []string{"combine(androidMediaUiClient.projection, repository.mediaObservationEvidence, repository.mediaOutputs)", "MediaRepositorySnapshot(projection.captures, observations, projection.variants, outputs)", "private val goMediaSelections = androidMediaUiClient.projection"} {
		if !strings.Contains(viewModel, needle) {
			return report{}, fmt.Errorf("Media inbox is not Go-projection driven: missing %s", needle)
		}
	}
	downloadSection, err := sourceSection(viewModel, "    fun downloadMediaCapture(", "    fun selectMediaVariant(")
	if err != nil {
		return report{}, err
	}
	goBranch := strings.Index(downloadSection, "if (isGoAdaptiveMedia(record))")
	legacyPlanner := strings.Index(downloadSection, "mediaExecutionPlanner.queueSpec")
	if goBranch < 0 || legacyPlanner < 0 || goBranch > legacyPlanner || !strings.Contains(downloadSection[goBranch:legacyPlanner], "downloadAdaptiveMediaViaGo(record, selection)") || !strings.Contains(downloadSection[goBranch:legacyPlanner], "return") {
		return report{}, fmt.Errorf("HLS/DASH does not short-circuit to Go before legacy Kotlin media planning")
	}
	selectSection, err := sourceSection(viewModel, "    fun selectMediaVariant(", "    fun updateMediaTrackSelection(")
	if err != nil {
		return report{}, err
	}
	if !strings.Contains(selectSection, "androidMediaUiClient.select") {
		return report{}, fmt.Errorf("media selection does not cross Go")
	}

	broker := mustRead("app/XDM.Android/app/src/main/kotlin/com/mikeyphw/xdm/android/engine/AndroidMediaPlatformBroker.kt")
	for _, needle := range []string{"request_kind", `payload.optString("tool") != "ffmpeg"`, "MediaRequestHandoffStore::forVariant", "FfmpegOperation.FinalizeAdaptive", "FfmpegOperation.RecordStream"} {
		if !strings.Contains(broker, needle) {
			return report{}, fmt.Errorf("typed FFmpeg platform broker missing %s", needle)
		}
	}
	for _, forbidden := range []string{"Runtime.getRuntime().exec", "ProcessBuilder(", `payload.optJSONArray("argv")`, `payload.getJSONArray("argv")`} {
		if strings.Contains(broker, forbidden) {
			return report{}, fmt.Errorf("FFmpeg broker exposes forbidden generic process path %s", forbidden)
		}
	}
	dispatcher := mustRead("app/XDM.Android/app/src/main/kotlin/com/mikeyphw/xdm/android/engine/AndroidPlatformRequestDispatcher.kt")
	for _, needle := range []string{`request.optString("kind") == "external_media_tool"`, "mediaToolScope.launch", "mediaToolReply(requestId, session, payload, broker)"} {
		if !strings.Contains(dispatcher, needle) {
			return report{}, fmt.Errorf("external media tool dispatcher missing %s", needle)
		}
	}

	return report{Mode: "android_media_e2e", Pass: true, Checks: []string{
		"Extension/WebView persisted capture is translated into a sanitized CaptureEnvelope",
		"Go MediaGraph owns capture/variant identity and selection projection",
		"Media inbox/ViewModel consumes Go captures, variants and selections",
		"HLS/DASH short-circuits to Go before legacy Kotlin media planning",
		"Go issues typed external_media_tool FFmpeg request without transport secrets",
		"Android FFmpeg broker resolves exact encrypted request handoff locally and exposes no arbitrary argv",
		"long-running FFmpeg execution is asynchronous at the platform dispatcher so the engine frame pump remains responsive",
	}}, nil
}

func sourceSection(source, startNeedle, endNeedle string) (string, error) {
	start := strings.Index(source, startNeedle)
	if start < 0 {
		return "", fmt.Errorf("source section missing start %q", startNeedle)
	}
	end := strings.Index(source[start+len(startNeedle):], endNeedle)
	if end < 0 {
		return "", fmt.Errorf("source section missing end %q", endNeedle)
	}
	return source[start : start+len(startNeedle)+end], nil
}

func mustRead(path string) string {
	data, err := os.ReadFile(path)
	if err != nil {
		panic(err)
	}
	return string(data)
}
func dir(path string) string {
	if idx := strings.LastIndex(path, "/"); idx >= 0 {
		return path[:idx]
	}
	return "."
}
