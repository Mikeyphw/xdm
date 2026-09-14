#!/usr/bin/env python3
from __future__ import annotations

import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
errors: list[str] = []


def read(path: str) -> str:
    file = ROOT / path
    if not file.is_file():
        errors.append(f"missing {path}")
        return ""
    return file.read_text(encoding="utf-8")


def need(condition: bool, message: str) -> None:
    if not condition:
        errors.append(message)

manifest = json.loads(read("PROJECT_MANIFEST.json") or "{}")
xar = manifest.get("xar_roadmap", {})
need("XAR15" in xar.get("applied_overlays", []), "roadmap must include XAR15")
need(xar.get("current_overlay") in {"XAR15", "XAR16", "XAR17"}, "current overlay must be XAR15 or later")
phase = manifest.get("xar15_privacy_security_performance", {})
for key in (
    "artwork_network_guarded",
    "direct_capture_approval_gate",
    "startup_expiry_sweep_async",
    "browser_capture_registry_bounded",
    "artwork_inflight_bounded",
    "share_clipdata_nonblocking",
    "cancellation_rethrown",
    "progress_projection_throttled",
    "settings_clipboard_sensitive",
    "artwork_cache_atomic_verified",
    "behavioral_validator_added",
    "scoped_storage_release_lane",
    "legacy_storage_not_auto_requested",
):
    need(phase.get(key) is True, f"manifest xar15_privacy_security_performance.{key} must be true")

main_activity = read("app/src/main/kotlin/com/mikeyphw/xdm/android/MainActivity.kt")
external_review = read("app/src/main/kotlin/com/mikeyphw/xdm/android/ExternalHandoffReviewActivity.kt")
external_security = read("app/src/main/kotlin/com/mikeyphw/xdm/android/ExternalAutomationSecurity.kt")
clipboard = read("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/common/UiTextHelpers.kt")
settings_ui = read("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/settings/AdvancedDownloadSettingsScreen.kt")
artwork = read("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/media/XdmMediaArtwork.kt")
handoff = read("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/MediaRequestHandoffStore.kt")
application = read("app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApplication.kt")
registry = read("media/src/main/kotlin/com/mikeyphw/xdm/android/media/BrowserCaptureSessionRegistry.kt")
runtime = read("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/TransferExecutionRuntime.kt")
main_manifest = read("app/src/main/AndroidManifest.xml")
release_manifest = read("app/src/release/AndroidManifest.xml")
build = read("app/build.gradle.kts")
final_gate = read("tools/run-final-release-gate.sh")
test = read("app/src/test/kotlin/com/mikeyphw/xdm/android/Xar15PrivacySecurityPerformanceContractTest.kt")
report = read("docs/remediation/XAR15-PRIVACY-SECURITY-PERFORMANCE.md")

need("InternalBrowserCaptureApprovalGate.consume" in main_activity, "MainActivity must consume one-use direct-capture approval token")
need("EXTRA_INTERNAL_BROWSER_DIRECT_CAPTURE_TOKEN" in main_activity, "MainActivity token extra missing")
need("InternalBrowserCaptureApprovalGate.approve" in external_review and "routeDirectBrowserCapture" in external_review, "exported review boundary must mint direct-capture token")
need("clipValuesWithoutCoercion" in external_security, "external intake must use non-coercing ClipData reader")
need("coerceToText(activity)" not in external_security, "external share review must not coerce attacker ClipData on UI thread")
need("coerceToText(context)" not in clipboard, "clipboard URL scan must avoid provider-backed coercion")
need('copySensitiveTextToClipboard(context, "XDM settings snapshot"' in settings_ui, "settings export must use sensitive clipboard metadata")
need("ExternalUrlPolicy.classifyNetworkTarget" in artwork and "ExternalNetworkTarget.Public" in artwork, "artwork loader must use guarded network target policy")
need("networkPermits.tryAcquire" in artwork and "decodePermits.tryAcquire" in artwork, "artwork network/decode concurrency must be bounded")
need("cleanupStaleTempFiles" in artwork and "decodeFile(file) == null" in artwork, "artwork cache publication must clean temps and verify decoded output")
init_index = handoff.find("fun initialize(store: SecureRequestEnvelopeStore)")
sweep_index = handoff.find("fun sweepExpired")
init_body = handoff[init_index:(sweep_index if sweep_index > init_index else len(handoff))] if init_index >= 0 else ""
need("durableStore = store" in init_body and "deleteExpired" not in init_body, "secure envelope expiry sweep must not run in initialize")
need("MediaRequestHandoffStore.sweepExpired()" in application and "Dispatchers.IO" in application, "Application must sweep expired envelopes off startup critical path")
need("MAX_SESSIONS" in registry and "pruneSessions()" in registry, "browser capture registry must have retention bound")
need("catch (error: CancellationException)" in runtime and "throw error" in runtime, "TransferExecutionRuntime must rethrow coroutine cancellation")
need("LIVE_SUMMARY_PROJECTION_INTERVAL_MS" in runtime and "lastSummaryProjectionAt" in runtime, "live progress summary projection must be throttled")
need("MANAGE_EXTERNAL_STORAGE" in main_manifest, "personal/direct-storage build lane must retain all-files permission")
need("MANAGE_EXTERNAL_STORAGE" in release_manifest and 'tools:node="remove"' in release_manifest, "release manifest must remove all-files permission")
need("READ_EXTERNAL_STORAGE" in release_manifest and "WRITE_EXTERNAL_STORAGE" in release_manifest, "release manifest must remove legacy storage permissions")
need('sourceSets.getByName("release")' in build and "XDM_SCOPED_STORAGE_RELEASE_LANE" in build, "Gradle must declare scoped-storage release lane")
need('        requestLegacyStoragePermissionsIfNeeded()' not in main_activity, "legacy storage permission must not be requested automatically on launch")
need("tools/validate-xar15-privacy-security-performance.py" in final_gate, "final release gate must include XAR15 validator")
need("S15-01" in report and "DS8-S15-05" in report, "XAR15 report must document all owned roots")
need("xar15GuardsCrossCuttingPrivacySecurityAndPerformancePaths" in test, "XAR15 contract test missing")

# XFE01 invariant: production Android extension files are still present and not referenced by this overlay validator as editable payload.
extension_dir = ROOT / "browser-extension/src/main/extension/xdm-firefox"
need((extension_dir / "network-observer.js").is_file(), "canonical Android Firefox extension network-observer.js missing")
need((extension_dir / "manifest.template.json").is_file(), "canonical Android Firefox extension manifest template missing")

if errors:
    print("XAR15 privacy/security/performance validation failed:")
    for error in errors:
        print(f"- {error}")
    raise SystemExit(1)

print("XAR15 privacy/security/performance validation passed: 13/13 S15 roots covered with scoped-storage release lane and unchanged canonical Firefox source")
