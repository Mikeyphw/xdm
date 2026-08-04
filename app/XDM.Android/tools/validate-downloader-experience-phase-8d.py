#!/usr/bin/env python3
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
errors: list[str] = []


def read(relative: str) -> str:
    path = ROOT / relative
    if not path.is_file():
        errors.append(f"Missing required file: {relative}")
        return ""
    return path.read_text(encoding="utf-8")


def require(text: str, marker: str, owner: str) -> None:
    if marker not in text:
        errors.append(f"{owner} missing marker: {marker}")


manifest = json.loads(read("PROJECT_MANIFEST.json") or "{}")
expected_overlay = "xdm_android_browser_removal_phase8d_media_resolver_format_selection_overlay.zip"
current_overlay = str(manifest.get("current_overlay", ""))
if current_overlay not in {"xdm_android_phase61_final_gate_validator_harmony_overlay.zip", "xdm_android_phase62_real_device_operational_smoke_seal_overlay.zip", "xdm_android_phase63_release_readiness_support_bundle_seal_r2_overlay.zip", "xdm_android_phase64_final_android_downloader_rc_seal_r2_overlay.zip"} and current_overlay != expected_overlay and not current_overlay.startswith("xdm_android_browser_removal_phase8e_"):
    errors.append("current_overlay must identify Phase 8D or its approved Phase 8E successor")
phase = manifest.get("downloader_experience_phase8d", {})
for key in (
    "first_class_resolver_workspace",
    "rich_format_comparison",
    "duration_based_size_estimates",
    "audio_subtitle_selection",
    "persistent_track_choices_outside_room",
    "redacted_session_review",
    "yt_dlp_probe_status",
    "protected_media_diagnostic_only",
    "resolver_history_from_media_captures",
    "review_first_queue_handoff",
    "external_handoff_preserved",
    "queue_intelligence_preserved",
    "all_download_engines_preserved",
    "browser_runtime_remains_absent",
):
    if phase.get(key) is not True:
        errors.append(f"downloader_experience_phase8d.{key} must be true")
if phase.get("stable_routes") != ["Downloads", "Add", "Media", "Library", "Activity", "Settings"]:
    errors.append("Stable downloader routes changed")
if phase.get("room_schema_unchanged") != 14:
    errors.append("Room schema must remain 14")
if phase.get("version_name_unchanged") != "0.21.0" or phase.get("version_code_unchanged") != 21:
    errors.append("App version must remain 0.21.0 / 21")

workspace = read("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaResolverWorkspace.kt")
for marker in (
    "enum class MediaResolverStage",
    "data class MediaResolverFormatRow",
    "data class MediaResolverTrackRow",
    "data class MediaResolverProbeDashboard",
    "data class MediaResolverSessionDashboard",
    "data class MediaResolverHistoryRow",
    "data class MediaResolverWorkspace",
    "object MediaTrackSelectionCodec",
    "class MediaResolverWorkspacePlanner",
    "estimatedSizeBytes",
    "Protected media remains diagnostic-only",
    "fun history(",
):
    require(workspace, marker, "Media resolver workspace")
for forbidden in ("android.content", "android.webkit", "WebView", "DownloadRepository", "RoomDatabase"):
    if forbidden in workspace:
        errors.append(f"Pure resolver workspace depends on forbidden runtime token: {forbidden}")

selection_store = read("app/src/main/kotlin/com/mikeyphw/xdm/android/MediaResolverSelectionStore.kt")
for marker in (
    "class MediaResolverSelectionStore",
    "xdm_media_resolver_selections",
    "MediaTrackSelectionCodec.encode",
    "MediaTrackSelectionCodec.decode",
    "Track selections are UX state, not download records",
    "fun remove(captureId: String)",
):
    require(selection_store, marker, "Resolver selection store")
for forbidden in ("sourceUrl", "pageUrl", "Cookie", "Authorization", "androidx.room"):
    if forbidden in selection_store:
        errors.append(f"Resolver selection store must not persist sensitive/source field: {forbidden}")

view_model = read("app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt")
for marker in (
    "val mediaTrackSelections: Map<String, MediaTrackSelection>",
    "mediaResolverSelectionStore.save(record.id, selection)",
    "fun updateMediaTrackSelection",
    "mediaResolverSelectionStore.remove(record.id)",
):
    require(view_model, marker, "MainViewModel resolver wiring")

application = read("app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApplication.kt")
for marker in (
    "val mediaResolverSelectionStore = MediaResolverSelectionStore(this)",
    "mediaResolverSelectionStore = mediaResolverSelectionStore",
):
    require(application, marker, "Application resolver wiring")

media_inbox = read("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/media/MediaInboxScreen.kt")
media_card = read("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/media/MediaCaptureCard.kt")
consumer_media = media_inbox + "\n" + media_card
for marker in (
    "Paste page URL",
    "Ready to download",
    "Recently queued",
    "MediaTrackPickerSheet",
    "Selected quality",
    "Audio track",
    "Subtitle track",
    "onTrackSelectionChanged",
):
    require(consumer_media, marker, "Consumer media UX")
for forbidden in (
    "Recent resolutions",
    "Resolver workspace",
    "yt-dlp metadata preview",
    "Cookie/header session handoff",
    "Authorization present / redacted",
    "Sidecar:",
):
    if forbidden.lower() in consumer_media.lower():
        errors.append(f"Normal Media surface exposes retired resolver/debug copy: {forbidden}")

routes = read("app/src/main/kotlin/com/mikeyphw/xdm/android/AppRoute.kt")
shell = read("app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApp.kt")
for route in ("Downloads", "Add", "Media", "Library", "Activity", "Settings"):
    require(routes, f'{route}("{route}"', "AppRoute")
for forbidden in ("Browser(", "AppRoute.Browser", "android.webkit", "WebView(", "WebViewClient", "WebChromeClient"):
    if forbidden in routes + shell + consumer_media + workspace:
        errors.append(f"Browser runtime token returned: {forbidden}")

for preserved in (
    "core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/QueueIntelligence.kt",
    "scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/QueueIntelligenceCoordinator.kt",
    "transfer-native/src/main/kotlin/com/mikeyphw/xdm/android/transfer/nativeengine/NativeHttpDownloadBackend.kt",
    "transfer-aria2/src/main/kotlin/com/mikeyphw/xdm/android/transfer/aria2/EmbeddedAria2Backend.kt",
    "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaTermuxRuntimeAdapter.kt",
    "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaExecutionLibrary.kt",
    "app/src/main/kotlin/com/mikeyphw/xdm/android/Media3PlayerScreen.kt",
    "browser-integration/src/main/kotlin/com/mikeyphw/xdm/android/browser/SharedLinkParser.kt",
):
    if not (ROOT / preserved).is_file():
        errors.append(f"Preserved downloader implementation missing: {preserved}")

build = read("app/build.gradle.kts")
if 'versionName = "0.21.0"' not in build or not re.search(r"versionCode\s*=\s*22\b", build):
    errors.append("Phase 8D must not change app version")
database = read("persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/AppDatabase.kt")
if not re.search(r"version\s*=\s*14\b", database):
    errors.append("Phase 8D must not change Room schema")

for path in (
    "media/src/test/kotlin/com/mikeyphw/xdm/android/media/MediaResolverWorkspaceTest.kt",
    "app/src/test/kotlin/com/mikeyphw/xdm/android/DownloaderExperiencePhase8DContractTest.kt",
    "docs/downloader/PHASE-8D-MEDIA-RESOLVER-POLISH.md",
):
    if not (ROOT / path).is_file():
        errors.append(f"Phase 8D contract path missing: {path}")

validator = "tools/validate-downloader-experience-phase-8d.py"
final_gate = read("tools/run-final-release-gate.sh")
workflow = read(".github/workflows/android.yml")
require(final_gate, validator, "Final release gate")
require(workflow, validator, "Android CI")

if errors:
    print("Downloader experience Phase 8D validation failed:")
    for error in errors:
        print(f"- {error}")
    sys.exit(1)

print("Downloader experience Phase 8D validation passed: resolver comparison, persistent track review, redaction, and engine preservation are sealed")
