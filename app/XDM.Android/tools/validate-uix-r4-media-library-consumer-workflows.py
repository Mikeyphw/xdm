#!/usr/bin/env python3
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ERRORS: list[str] = []


def read(relative: str) -> str:
    path = ROOT / relative
    if not path.is_file():
        ERRORS.append(f"Missing required file: {relative}")
        return ""
    return path.read_text(encoding="utf-8", errors="replace")


def require(relative: str, *needles: str) -> str:
    text = read(relative)
    for needle in needles:
        if needle not in text:
            ERRORS.append(f"{relative} missing {needle!r}")
    return text


planner = require(
    "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaConsumerWorkspace.kt",
    "enum class MediaConsumerState",
    "data class MediaConsumerCaptureSummary",
    "enum class MediaLibraryFilter",
    "class MediaConsumerWorkspacePlanner",
    "fun summarizeCapture(",
    "fun filterLibrary(",
    "RecentlyAdded",
)
for forbidden in ("android.content", "androidx.compose", "RoomDatabase", "DownloadRepository"):
    if forbidden in planner:
        ERRORS.append(f"Pure consumer planner depends on forbidden runtime token: {forbidden}")

inbox = require(
    "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/media/MediaInboxScreen.kt",
    'title = "Media"',
    "Paste page URL",
    "Ready to download",
    "Recently queued",
    "Page session details stay private",
)
card = require(
    "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/media/MediaCaptureCard.kt",
    "MediaTrackPickerSheet",
    "Selected quality",
    "Estimated download size",
    "Audio track",
    "Subtitle track",
    "Source details",
    "Session values and temporary media links remain hidden",
)
library = require(
    "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/library/MediaLibraryScreen.kt",
    'title = "Library"',
    "MediaLibraryFilter.entries",
    "LazyVerticalGrid",
    'Text("Play")',
    'Text("Resume download")',
    'Text("Retry")',
    'Text("More")',
    "Remove library record",
)
player = require(
    "app/src/main/kotlin/com/mikeyphw/xdm/android/Media3PlayerScreen.kt",
    "playerError?.let",
    'actionLabel = "Retry"',
    'XdmTechnicalDetails(label = "Support details")',
    "Track availability",
    "Playback position",
)
app = require(
    "app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApp.kt",
    "AppRoute.Media -> MediaInboxScreen(",
    "onPastePageUrl = { viewModel.navigate(AppRoute.Add) }",
    "AppRoute.Library -> MediaLibraryScreen(",
    "onRemoveRecord = viewModel::removeMediaCapture",
)
shell = require(
    "app/src/main/kotlin/com/mikeyphw/xdm/android/XdmAdaptiveShell.kt",
    "setOf(AppRoute.Downloads, AppRoute.Media, AppRoute.Library)",
)

normal_surfaces = inbox + "\n" + card + "\n" + library
for forbidden in (
    "Resolver workspace",
    "Recent resolutions",
    "yt-dlp metadata preview",
    "Termux",
    "Post-processing automation",
    "Queue telemetry",
    "Worker bridge",
    "Session privacy audit",
    "Media final validation",
    "Sidecar:",
    "toRedactedJson()",
    "MediaDispatch",
    "QueueTelemetry",
    "WorkerBridge",
):
    if forbidden.lower() in normal_surfaces.lower():
        ERRORS.append(f"Normal Media/Library surface exposes engineering detail: {forbidden}")
for raw_render in (
    "Text(capture.sourceUrl",
    "Text(item.sidecar.redactedSourceUrl",
    "Text(download.requestHeaders",
    "Cookie:",
    "Authorization:",
):
    if raw_render.lower() in normal_surfaces.lower():
        ERRORS.append(f"Normal Media/Library surface renders sensitive or raw data: {raw_render}")
if "Player 2.0 diagnostics" in player:
    ERRORS.append("Normal playback still exposes the retired diagnostics deck")
if player.index("Support details") < player.index("playerError?.let"):
    ERRORS.append("Player support details must be nested after an actual playback error")

media_call_match = re.search(r"AppRoute\.Media -> MediaInboxScreen\((.*?)\n\s*\)\n\s*AppRoute\.Library", app, re.S)
media_call = media_call_match.group(1) if media_call_match else ""
if not media_call:
    ERRORS.append("Unable to locate the consumer Media wiring block")
for forbidden in ("termuxMediaPipeline", "postProcessingAutomation", "mediaDispatch", "queueTelemetry", "workerBridge"):
    if forbidden in media_call:
        ERRORS.append(f"Consumer Media signature still receives internal state: {forbidden}")

require(
    "media/src/test/kotlin/com/mikeyphw/xdm/android/media/MediaConsumerWorkspaceTest.kt",
    "readyCaptureSummarizesSelectedQualityTracksAndEstimatedSize",
    "expiredCaptureUsesSafeRefreshCopyWithoutLeakingUrlsOrTokens",
    "libraryFiltersMediaTypeAndRecentItemsThenSortsNewestFirst",
)
require(
    "app/src/test/kotlin/com/mikeyphw/xdm/android/UixR4MediaLibraryContractTest.kt",
    "mediaIsConsumerFirstAndKeepsEngineeringInternalsOut",
    "libraryLeadsWithPlaybackAndOnlyShowsSupportDetailsAfterErrors",
    "appWiringKeepsInternalPipelinesOutOfTheConsumerMediaSignature",
)
require(
    "docs/architecture/UIX-R4-MEDIA-LIBRARY-CONSUMER-WORKFLOWS.md",
    "consumer-first Media",
    "playable-first Library",
    "No Room schema bump",
)

routes = read("app/src/main/kotlin/com/mikeyphw/xdm/android/AppRoute.kt")
for route in ("Downloads", "Add", "Media", "Library", "Activity", "Settings"):
    if f'{route}("{route}"' not in routes:
        ERRORS.append(f"Stable route missing: {route}")
database = read("persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/AppDatabase.kt")
if not re.search(r"version\s*=\s*14\b", database):
    ERRORS.append("Room schema must remain 14")
build = read("app/build.gradle.kts")
if 'versionName = "0.20.0-rc08"' not in build or not re.search(r"versionCode\s*=\s*21\b", build):
    ERRORS.append("App version must remain 0.20.0-rc08 / 21")

manifest = json.loads(read("PROJECT_MANIFEST.json") or "{}")
r4 = manifest.get("uix_r4_media_library_consumer_workflows", {})
expected = {
    "consumer_first_media": True,
    "quality_and_track_review": True,
    "recent_queue_summary": True,
    "playable_first_library": True,
    "library_filters": ["All", "Video", "Audio", "Recently added"],
    "adaptive_library_grid": True,
    "normal_debug_decks_removed": True,
    "player_support_details_error_bound": True,
    "room_schema_unchanged": 14,
    "version_name_unchanged": "0.20.0-rc08",
    "version_code_unchanged": 21,
}
for key, value in expected.items():
    if r4.get(key) != value:
        ERRORS.append(f"PROJECT_MANIFEST uix_r4_media_library_consumer_workflows.{key} must equal {value!r}")
current_uix_overlay = manifest.get("current_uix_overlay")
allowed_current_uix_overlays = {
    "xdm_android_uix_r4_media_library_consumer_workflow_overlay.zip",
    "xdm_android_uix_r5_activity_settings_developer_boundary_overlay.zip",
}
if current_uix_overlay not in allowed_current_uix_overlays:
    ERRORS.append("current_uix_overlay must identify UIX R4 or a later compatible UIX overlay")

validator = "tools/validate-uix-r4-media-library-consumer-workflows.py"
for gate in ("tools/run-final-release-gate.sh", ".github/workflows/android.yml"):
    if validator not in read(gate):
        ERRORS.append(f"{gate} does not run {validator}")

if ERRORS:
    print("UIX R4 Media and Library consumer workflow validation failed:")
    for error in ERRORS:
        print(f"- {error}")
    sys.exit(1)
print("UIX R4 Media and Library consumer workflow validation passed")
