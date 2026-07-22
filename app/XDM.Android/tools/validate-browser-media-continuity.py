#!/usr/bin/env python3
from pathlib import Path
import json
import sys

ROOT = Path(__file__).resolve().parents[1]

def read(path: str) -> str:
    full = ROOT / path
    if not full.is_file():
        raise AssertionError(f"missing file: {path}")
    return full.read_text(encoding="utf-8")

def require(path: str, *needles: str) -> None:
    text = read(path)
    for needle in needles:
        if needle not in text:
            raise AssertionError(f"{path} missing {needle!r}")

def reject(path: str, *needles: str) -> None:
    text = read(path)
    for needle in needles:
        if needle in text:
            raise AssertionError(f"{path} must not contain {needle!r}")

def main() -> int:
    manifest = json.loads(read("PROJECT_MANIFEST.json"))
    browser = manifest.get("built_in_browser_media_downloader", {})
    for key in (
        "browser_tabs",
        "browser_history",
        "cookie_profiles",
        "hls_media_groups",
        "hls_live_and_protection_detection",
        "dash_adaptation_sets",
        "dash_content_protection_detection",
        "yt_dlp_page_url_probe",
        "offline_library_groundwork",
    ):
        if not browser.get(key):
            raise AssertionError(f"manifest missing built-in browser continuity key: {key}")

    require(
        "app/src/main/kotlin/com/mikeyphw/xdm/android/BrowserScreen.kt",
        "Icons.AutoMirrored.Rounded.ArrowBack",
        "Icons.AutoMirrored.Rounded.ArrowForward",
        "BrowserSessionStore",
        "BrowserCookieProfile",
        "loadHistory",
        "saveTabs",
        "DesktopUserAgent",
        "WeakReference<WebView>",
        "@SuppressLint(\"SetJavaScriptEnabled\")\nprivate fun WebView.applyBrowserSettings",
    )
    reject(
        "app/src/main/kotlin/com/mikeyphw/xdm/android/BrowserScreen.kt",
        "BrowserBridge",
        "databaseEnabled",
        "Icons.Rounded.ArrowBack",
        "Icons.Rounded.ArrowForward",
        "private object",
    )

    require(
        "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaInboxContract.kt",
        "MediaManifestSummary",
        "#EXT-X-MEDIA",
        "inspectHlsPlaylist",
        "inspectDashManifest",
        "ContentProtection",
        "AdaptationSet",
        "attributeList",
        "decorateRecordWithManifestSummary",
    )

    require(
        "media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaDownloadPlanner.kt",
        "metadataProbeUrl",
        "OfflineMediaLibrarySummary",
        "MediaPlaybackCandidate",
        "needsCookieContext",
        "UnsupportedProtected",
    )

    require(
        "app/src/main/kotlin/com/mikeyphw/xdm/android/termux/TermuxMediaPipelineManager.kt",
        "metadataProbeUrl(record)",
        "page URL",
        "inputOverride",
    )

    require(
        "app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt",
        "Offline library and player",
        "yt-dlp page metadata",
        "Copy probe URL",
        "summary.adaptiveCount",
    )

    require(
        "media/src/test/kotlin/com/mikeyphw/xdm/android/media/MediaCaptureServiceTest.kt",
        "hlsMediaGroupsLiveAndProtectionAreClassified",
        "dashAdaptationSetsExposeAudioSubtitlesAndDrm",
        "plannerPrefersPageUrlForYtDlpAndSummarizesPlaybackLibrary",
    )

    approute = read("app/src/main/kotlin/com/mikeyphw/xdm/android/AppRoute.kt")
    if 'Browser("Browser")' in approute or 'label == "Browser"' in approute:
        raise AssertionError("Browser must remain inside Media, not AppRoute")

    print("Browser media continuity validation passed")
    return 0

if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except AssertionError as exc:
        print(f"validation failed: {exc}", file=sys.stderr)
        raise SystemExit(1)
