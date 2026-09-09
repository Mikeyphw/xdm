#!/usr/bin/env python3
from pathlib import Path
import json
import sys

ROOT = Path(__file__).resolve().parents[1]
errors = []

def read(rel: str) -> str:
    path = ROOT / rel
    if not path.is_file():
        errors.append(f"missing {rel}")
        return ""
    return path.read_text()

def need(condition: bool, message: str) -> None:
    if not condition:
        errors.append(message)

backend = read("transfer-api/src/main/kotlin/com/mikeyphw/xdm/android/transfer/DownloadBackend.kt")
native = read("transfer-native/src/main/kotlin/com/mikeyphw/xdm/android/transfer/nativeengine/NativeHttpDownloadBackend.kt")
native_test = read("transfer-native/src/test/kotlin/com/mikeyphw/xdm/android/transfer/nativeengine/NativeHttpDownloadBackendTest.kt")
main = read("app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt")
handoff = read("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/MediaRequestHandoffStore.kt")
locator = read("app/src/main/kotlin/com/mikeyphw/xdm/android/MediaLocatorActivity.kt")
strings = read("app/src/main/res/values/strings.xml")
card = read("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/media/MediaCaptureCard.kt")
transfer_test = read("transfer-api/src/test/kotlin/com/mikeyphw/xdm/android/transfer/ExecutionSemanticsRepairTest.kt")
scheduler_test = read("scheduler/src/test/kotlin/com/mikeyphw/xdm/android/scheduler/SecureRequestEnvelopeStoreTest.kt")
app_contract = read("app/src/test/kotlin/com/mikeyphw/xdm/android/ExecutionMediaSemanticsRepairContractTest.kt")
ux_contract = read("app/src/test/kotlin/com/mikeyphw/xdm/android/AddMediaUxRemodelContractTest.kt")
gate = read("tools/run-final-release-gate.sh")
report = read("XDM_PROMISE_DELIVERY_AUDIT_2026_09_08.md")
manifest = json.loads(read("PROJECT_MANIFEST.json") or "{}")

need("val transferShape: MediaTransferShape = inferTransferShape(sourceUrl, mimeType)" in backend,
     "legacy isMediaRequest must not alter DownloadRequest default transfer shape")
need("if (isMediaRequest) MediaTransferShape.AdaptivePlaylist" not in backend,
     "legacy media boolean must not promote direct resources in transfer-api")
need("if (isMediaRequest) MediaTransferShape.AdaptivePlaylist" not in main,
     "preview request must not promote direct resources from legacy media boolean")
need("request.isMediaRequest" not in native,
     "legacy media boolean must not alter synthesized native HTTP request headers")
need("request.transferShape == MediaTransferShape.AdaptivePlaylist" in native and
     "request.transferShape == MediaTransferShape.DirectMedia" in native,
     "native media Accept defaults must derive from explicit transfer shape")
need("legacyMediaBooleanCannotChangeNativeWireDefaults" in native_test,
     "native wire-default regression test missing")
need("val activeJob = control.job" in native and
     "if (control.state.value.state == DownloadState.RecoveryRequired)" in native and
     "activeJob.join()" in native,
     "native recovery resume must wait for a still-unwinding transfer job instead of dropping the retry")
need("finalSaveRecoveryRetriesPublicationWithoutNetworkRedownload" in native_test and
     "backend.resume(task.taskId)" in native_test and "server.stop(0)" in native_test,
     "final-save recovery regression must prove publication retry works without network redownload")
need("refreshedTransferShape(source.transferShape" in handoff and "refreshedTransferShape(it.transferShape, exactUrl)" in handoff,
     "clone and link refresh must carry specialized shape through opaque URLs")
need("inferred == MediaTransferShape.DirectFile" in handoff and "existing !in setOf(MediaTransferShape.DirectFile, MediaTransferShape.DirectMedia)" in handoff,
     "opaque replacement rule must preserve only specialized existing semantics")
need("legacyMediaBooleanCannotPromoteDirectResourcesToPlaylistSemantics" in transfer_test,
     "legacy boolean regression test missing")
need("opaqueSpecializedShapeSurvivesCloneAndSignedUrlRefresh" in scheduler_test,
     "opaque clone/refresh regression test missing")

saved = locator.split("private fun encodeSavedCandidate", 1)[-1].split("private fun restoreLocatorState", 1)[0]
need("data class MediaLocatorRequestContext" in locator and all(token in locator for token in ("sourceUrl: String", "pageUrl: String?", "headers: Map<String, String>", "variantUrls: Map<String, String>")),
     "Live Locator process-local context must own exact source/page/variant URLs and headers")
for forbidden in ('put("url"', 'put("pageUrl"', 'put("requestHeaders"', 'put("headers"'):
    need(forbidden not in saved, f"Live Locator Bundle must not serialize executable request context: {forbidden}")
need("requestContext.variantUrls[variantId]" in locator and "requestHeaders = requestContext.headers" in locator,
     "Live Locator restore must reattach exact variant URLs and headers from process-local cache")
need("savedKind?.restoreMimeHint()" in locator and "kind = savedKind ?: base.kind" in locator,
     "Live Locator must restore semantic kind for opaque media URLs")
need("liveLocatorRecreationKeepsSecretsOutOfBundleAndContextInProcess" in app_contract and 'assertFalse(savedState.contains("put(\\\"url\\\""))' in app_contract,
     "Live Locator source contract must cover exact URL saved-state privacy")

need('@SuppressLint("WrongConstant")' in locator and "private fun enableCookieAwareRequestInterception(webView: WebView)" in locator,
     "Live Locator must narrowly suppress AndroidX WebKit COOKIE_INTERCEPT WrongConstant false positive")
need("WebViewFeature.isFeatureSupported(WebViewFeature.COOKIE_INTERCEPT)" in locator and
     "WebSettingsCompat.setCookiesIncludedInShouldInterceptRequest(webView.settings, true)" in locator,
     "Live Locator must retain the runtime COOKIE_INTERCEPT support guard")
need('@SuppressLint("SetJavaScriptEnabled", "WrongConstant")' not in locator and '@SuppressLint("WrongConstant", "SetJavaScriptEnabled")' not in locator,
     "WrongConstant suppression must not be broadened onto the whole onCreate path")

need('@SuppressLint("SetJavaScriptEnabled", "MissingOnRenderProcessGone")' in locator and
     "override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean" in locator and
     "(view.parent as? ViewGroup)?.removeView(view)" in locator and "view.destroy()" in locator,
     "Live Locator must keep real renderer-termination recovery while narrowly suppressing the AndroidX 1.17.0 Kotlin lint false positive")
need('name="media_locator_saving"' in strings and
     "status.text = getString(R.string.media_locator_saving)" in locator and
     'status.text = "Saving captured media…"' not in locator,
     "Live Locator saving status must come from Android resources instead of hardcoded setText text")

need("showTrackControls = hasTrackChoices" in card and "if (showTrackControls)" in card,
     "Media Options must share the meaningful track-choice gate")
need("showTrackControls = hasTrackChoices" in ux_contract and "if (showTrackControls)" in ux_contract,
     "UX contract must cover Options-sheet track gating")

entry = manifest.get("promise_delivery_audit_2026_09_08", {})
need(entry.get("status") == "implemented", "manifest promise audit must be implemented")
need(entry.get("room_schema_current") == 21 and entry.get("room_schema_changed") is False,
     "promise audit must retain Room schema 21")
need(entry.get("validation_deferred") is False, "promise audit validation must not be deferred")
need(entry.get("legacy_media_boolean_changes_native_wire_defaults") is False,
     "promise audit must record that the legacy boolean cannot change native wire defaults")
need(entry.get("androidx_webkit_cookie_intercept_lint_false_positive_scoped") is True,
     "promise audit must record scoped AndroidX COOKIE_INTERCEPT lint handling")
need(entry.get("lint_debug_required") is True,
     "promise audit must require app lint validation")
need(entry.get("recovery_resume_waits_for_unwinding_job") is True,
     "promise audit must record recovery-resume unwind synchronization")
need(entry.get("final_save_retry_without_network_redownload") is True,
     "promise audit must record final-save publication-only retry")
need(entry.get("androidx_webkit_renderer_lint_false_positive_scoped") is True,
     "promise audit must record scoped AndroidX Kotlin renderer lint handling")
need(entry.get("renderer_process_gone_handler_present") is True,
     "promise audit must record real renderer process termination handling")
need(entry.get("media_locator_saving_status_resource_backed") is True,
     "promise audit must record resource-backed saving status text")
need(manifest.get("execution_media_semantics_repair", {}).get("live_locator_bundle_contains_exact_request_urls") is False,
     "manifest must record no exact locator request URLs in Bundle")
need(manifest.get("execution_media_semantics_repair", {}).get("opaque_specialized_shape_survives_link_refresh") is True,
     "manifest must record opaque specialized shape refresh continuity")
need(manifest.get("add_media_ux_remodel", {}).get("track_picker_hidden_without_meaningful_choices") is True,
     "manifest must record direct-media Options gating")
need("Gaps found and closed" in report and "Legacy media boolean fallback" in report and "Live Locator recreation/privacy" in report,
     "promise-delivery audit report must describe the closed gaps")
need("tools/validate-promise-delivery-audit.py" in gate,
     "canonical final gate must own the promise-delivery audit")
need("legacy shape/wire isolation" in gate,
     "final gate summary must carry the legacy wire-isolation follow-up")

if errors:
    print("Promise delivery audit validator failed:")
    for error in errors:
        print(f"- {error}")
    sys.exit(1)
print("Promise delivery audit validator: OK")
