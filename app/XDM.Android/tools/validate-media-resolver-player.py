#!/usr/bin/env python3
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
checks = {
    "planner": (ROOT / "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaDownloadPlanner.kt", [
        "MediaTrackSelection", "MediaVariantPickerGroup", "MediaSessionHandoff", "YtDlpMetadataProbeResult", "ProtectedMediaDiagnostic", "redactedSummary",
    ]),
    "screens": (ROOT / "app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt", [
        "Choose variant", "Selected variant", "VariantSelectorRow", "Choose tracks", "Audio track", "Subtitle track",
        "yt-dlp metadata preview", "Cookie/header session handoff", "Protected media diagnostics", "Media3DirectPlayerCard",
    ]),
    "player": (ROOT / "app/src/main/kotlin/com/mikeyphw/xdm/android/Media3PlayerScreen.kt", [
        "ExoPlayer.Builder", "PlayerView", "Media3 player",
    ]),
    "termux models": (ROOT / "app/src/main/kotlin/com/mikeyphw/xdm/android/termux/TermuxBridgeModels.kt", [
        "extraArguments", "YtDlpMetadata", "YtDlpDownload",
    ]),
    "termux manager": (ROOT / "app/src/main/kotlin/com/mikeyphw/xdm/android/termux/TermuxMediaPipelineManager.kt", [
        "MediaTrackSelection", "sessionHintHeaders", "redactedSession", "ytDlpFormatSelector",
    ]),
    "termux diagnostics": (ROOT / "app/src/main/kotlin/com/mikeyphw/xdm/android/termux/TermuxMediaPipelineModels.kt", [
        "redactedSession", "session\\t",
    ]),
    "version catalog": (ROOT / "gradle/libs.versions.toml", [
        "androidx.media3:media3-exoplayer", "androidx.media3:media3-ui",
    ]),
    "app dependencies": (ROOT / "app/build.gradle.kts", [
        "libs.androidx.media3.exoplayer", "libs.androidx.media3.ui", "jniLibs.keepDebugSymbols += \"**/*.so\"", "\"ObsoleteSdkInt\"",
    ]),
    "tests": (ROOT / "media/src/test/kotlin/com/mikeyphw/xdm/android/media/MediaCaptureServiceTest.kt", [
        "sessionHandoffDiagnosticsRedactCookiesTokensAndAuthorization", "resolverPickerGroupsCreateFormatSelectorAndPreviewMetadata",
    ]),
}
errors = []
for label, (path, tokens) in checks.items():
    if not path.is_file():
        errors.append(f"missing {label}: {path}")
        continue
    text = path.read_text()
    for token in tokens:
        if token not in text:
            errors.append(f"{label} missing token: {token}")

manifest = (ROOT / "PROJECT_MANIFEST.json").read_text()
if '"media_resolver_player"' not in manifest or '"top_level_route_added": false' not in manifest:
    errors.append("manifest does not record media_resolver_player without route change")


planner_text = (ROOT / "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaDownloadPlanner.kt").read_text()
for forbidden in [
    "count(MediaPlaybackCandidate::",
    "sumOf(MediaPlaybackCandidate::",
    " in adaptiveMimeTypes",
    "toBooleanStrictOrNull",
    "fun ytdlpArguments(): List<String> = buildList",
    "fun aria2Options(): Map<String, String> = buildMap",
]:
    if forbidden in planner_text:
        errors.append(f"planner keeps Kotlin/BTAPI-fragile construct: {forbidden}")

models = (ROOT / "app/src/main/kotlin/com/mikeyphw/xdm/android/termux/TermuxMediaPipelineModels.kt").read_text()
for forbidden in ["secret-cookie", "secret-auth", "secret-csrf", "Cookie:", "Authorization:"]:
    if forbidden in models:
        errors.append(f"raw sensitive diagnostic token found in media pipeline models: {forbidden}")

if errors:
    for error in errors:
        print(f"FAIL: {error}")
    sys.exit(1)
print("media resolver/player validation passed")
