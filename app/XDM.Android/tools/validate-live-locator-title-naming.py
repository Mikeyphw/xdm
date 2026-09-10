#!/usr/bin/env python3
from pathlib import Path
import json
import sys

ROOT = Path(__file__).resolve().parents[1]
errors = []

def read(rel: str) -> str:
    try:
        return (ROOT / rel).read_text(encoding="utf-8")
    except Exception as exc:
        errors.append(f"cannot read {rel}: {exc}")
        return ""

def need(condition: bool, message: str) -> None:
    if not condition:
        errors.append(message)

locator = read("app/src/main/kotlin/com/mikeyphw/xdm/android/MediaLocatorActivity.kt")
strings = read("app/src/main/res/values/strings.xml")
media = read("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaInboxContract.kt")
media_test = read("media/src/test/kotlin/com/mikeyphw/xdm/android/media/MediaCaptureServiceTest.kt")
frame = read("browser-extension/src/main/extension/xdm-firefox/frame-bridge.js")
store = read("browser-extension/src/main/extension/xdm-firefox/candidate-store.js")
observer = read("browser-extension/src/main/extension/xdm-firefox/network-observer.js")
handoff = read("browser-extension/src/main/extension/xdm-firefox/handoff.js")
envelope = read("app/src/main/kotlin/com/mikeyphw/xdm/android/BrowserCaptureEnvelopeManager.kt")
view_model = read("app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt")
background_test = read("browser-extension/tests/test_background.js")
handoff_test = read("browser-extension/tests/test_handoff.js")
contract = read("app/src/test/kotlin/com/mikeyphw/xdm/android/LiveLocatorTitleNamingContractTest.kt")
report = read("XDM_LIVE_LOCATOR_TITLE_NAMING_WEB01_WEB03_NAME01_REPORT.md")
manifest = json.loads(read("PROJECT_MANIFEST.json") or "{}")
entry = manifest.get("live_locator_title_naming_web01_web03_name01", {})

for token in (
    "override fun onReceivedError", "override fun onReceivedHttpError", "override fun onReceivedSslError",
    "handler.cancel()", "showMainFrameError(", "updateNavigationState()", "updatePageSummary()",
    "resultsExpanded = !resultsExpanded", "setAcceptThirdPartyCookies(webView, true)",
    "builtInZoomControls = true", "EditorInfo.IME_ACTION_GO",
):
    need(token in locator, f"Live Locator missing {token}")
need("notifyUser = true" in locator and 'dedupeKey = "media-locator-$kind"' in locator,
     "SSL/actionable page failures must use OBS01 problem reporting without losing dedupe")
for token in ("media_locator_retry", "media_locator_error_network_title", "media_locator_error_http_title", "media_locator_error_ssl_title", "media_locator_candidates_collapsed"):
    need(token in strings, f"Live Locator strings missing {token}")

need("pageArtwork" in locator and "durationMs = durationMs" in locator and "thumbnailUrl = thumbnailUrl" in locator,
     "Live Locator poster/page artwork and duration must reach MediaSniffingInput")
need("meta[property=\"og:image\"]" in locator and "application/ld+json" in locator,
     "Live Locator must discover Open Graph and JSON-LD artwork")
need("durationMs = candidate.record.durationMs" in locator and "thumbnailUrl = candidate.record.thumbnailUrl" in locator,
     "Live Locator process-local restore context must retain media metadata")

need("pageArtworkUrl()" in frame and "thumbnailUrl: candidate.thumbnailUrl || pageArtworkUrl()" in frame,
     "extension frame playback must carry poster/page artwork")
need("durationMs: Math.max(0, Number(candidate.durationMs || 0))" in frame,
     "extension frame playback must carry duration")
need("thumbnailUrl: candidate.thumbnailUrl || previous.thumbnailUrl" in store and "durationMs: Math.max(0, Number(candidate.durationMs || previous.durationMs || 0))" in store,
     "extension candidate store must preserve media metadata")
need("thumbnailUrl: value.thumbnailUrl || evidence.thumbnailUrl" in observer and "durationMs: Math.max(0, Number(value.durationMs || evidence.durationMs || 0))" in observer,
     "privileged playback merge must preserve page metadata")
need("thumbnailUrl: safeHttpUrl(candidate.thumbnailUrl || \"\")" in handoff and "durationMs: Math.max(0, Math.trunc(Number(candidate.durationMs || 0)))" in handoff,
     "bounded capture-session handoff must serialize thumbnail/duration")
need("val durationMs: Long?" in envelope and "val thumbnailUrl: String?" in envelope,
     "Android capture envelope candidate must decode thumbnail/duration")
need("durationMs = candidate.durationMs" in view_model and "thumbnailUrl = candidate.thumbnailUrl" in view_model,
     "extension metadata must reach Android MediaSniffingInput")

need("fileName = fileNameFor(sourceUrl, safeTitle, kind, mimeType, variants, hasExplicitTitle = titleIsPageDerived)" in media,
     "captured-media naming must use the canonical safe page title and explicit page-title provenance")
need("preferredMediaExtension(pathName, kind, mimeType)" in media and 'MediaSourceKind.HlsPlaylist) return ".m3u8"' in media,
     "captured-media title naming must preserve the real media extension")
need('"video/mp4", "application/mp4" -> ".mp4"' in media and '"audio/mpeg" -> ".mp3"' in media,
     "captured-media extension choice must be MIME backed")
need('assertEquals("My Holiday in London.mp4", progressive.fileName)' in media_test and 'assertEquals("Episode 7.m3u8", adaptive.fileName)' in media_test,
     "page-title naming regression tests missing")
need('assertEquals("session-token.mp3", record.fileName)' in media_test,
     "no-page-title MIME fallback regression test missing")
need('thumbnailUrl, "https://img.example/video-poster.jpg"' in background_test or 'thumbnailUrl, "https://img.example/video-poster.jpg"' in background_test.replace('metadataHandoff.candidates[0].', ''),
     "background extension test must prove playback artwork survives merge")
need('batch[1].durationMs,630000' in handoff_test and 'batch[1].thumbnailUrl,"https://img.example/poster.jpg"' in handoff_test,
     "handoff regression test must prove compact candidate metadata survives")
need("pageTitleIsCanonicalDefaultForCapturedMediaIncludingExtensionImports" in contract,
     "cross-stack NAME01 contract test missing")
need("All browser-extension Node test files pass" in report and "Full Android Gradle/lint validation is intentionally deferred" in report,
     "report must state actual validation scope")

need(entry.get("status") == "implemented_intermediate", "manifest must mark WEB/NAME overlay implemented intermediate")
need(entry.get("webview_main_frame_error_surface") is True and entry.get("webview_retry") is True,
     "manifest must record WebView recovery delivery")
need(entry.get("webview_page_summary") is True and entry.get("collapsible_capture_panel") is True,
     "manifest must record Live Locator UX delivery")
need(entry.get("webview_poster_artwork") is True and entry.get("extension_artwork_metadata") is True,
     "manifest must record capture artwork delivery")
need(entry.get("extension_duration_metadata") is True and entry.get("extension_title_first_naming") is True,
     "manifest must record extension capture metadata/title naming")
need(entry.get("room_schema_unchanged") == 21 and entry.get("validation_deferred") is True,
     "intermediate overlay must preserve Room schema and deferred validation")
need(entry.get("next_overlay") == "xdm_android_list_quick_actions_final_seal_v1.zip",
     "manifest must point to ACT01/ACT02 final seal")

if errors:
    print("WEB01-WEB03/NAME01 validator failed:")
    for error in errors:
        print(f"- {error}")
    sys.exit(1)
print("WEB01-WEB03/NAME01 Live Locator/title naming validator: OK")
