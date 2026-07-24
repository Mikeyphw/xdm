#!/usr/bin/env python3
from pathlib import Path
import json
root = Path(__file__).resolve().parents[1]
checks = [
    (root / 'app/src/main/kotlin/com/mikeyphw/xdm/android/AppRoute.kt', ['Downloads("Downloads"', 'Media("Media"']),
    (root / 'app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApp.kt', ['MediaInboxScreen', 'viewModel::captureBrowserMediaUrl', 'viewModel::openAddFromBrowser']),
    (root / 'app/src/main/kotlin/com/mikeyphw/xdm/android/BrowserScreen.kt', ['WebView', 'shouldInterceptRequest', 'setDownloadListener', 'Browser media tray', 'Review media']),
    (root / 'app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt', ['fun captureBrowserMediaUrl', 'fun openAddFromBrowser', 'repository.saveMediaCapture']),
    (root / 'media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaInboxContract.kt', ['MediaCandidateClassifier', 'mimeTypeHint', 'candidatesFromHtml', 'application/dash+xml']),
    (root / 'media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaDownloadPlanner.kt', ['MediaDownloadStrategy', 'YtDlp', 'FfmpegLive', 'UnsupportedProtected']),
    (root / 'docs/architecture/UI_UX_TOPOGRAPHY_CONTRACT.md', ['Browser', 'media tray', 'explicit review']),
    (root / 'docs/architecture/PHASE-18-BUILT-IN-BROWSER-MEDIA-DOWNLOADER.md', ['Built-in Browser Media Downloader', 'Clean-room boundary']),
]
for path, needles in checks:
    if not path.is_file():
        raise SystemExit(f'missing {path.relative_to(root)}')
    text = path.read_text()
    for needle in needles:
        if needle not in text:
            raise SystemExit(f'{path.relative_to(root)} missing {needle!r}')
route_text = (root / 'app/src/main/kotlin/com/mikeyphw/xdm/android/AppRoute.kt').read_text()
manifest = json.loads((root / 'PROJECT_MANIFEST.json').read_text())
approved_browser_route_overlays = {
    'xdm_android_phase37b_dual_launcher_navigation_split_overlay.zip',
    'xdm_android_phase38_browser_reliability_foundation_overlay.zip',
    'xdm_android_phase39_browser_chrome_navigation_overlay.zip',
    'xdm_android_phase40_browser_tabs_session_ux_overlay.zip',
    'xdm_android_phase41_browser_download_bridge_overlay.zip',
    'xdm_android_phase42_browser_media_capture_cockpit_overlay.zip',
    'xdm_android_phase43_browser_library_surfaces_overlay.zip',
    'xdm_android_phase44_browser_settings_privacy_controls_overlay.zip',
}
if manifest.get('current_overlay') not in approved_browser_route_overlays and ('Browser("Browser"' in route_text or 'Icons.Rounded.Search' in route_text):
    raise SystemExit('Browser media downloader must live under Media before the Phase 37B explicit Browser route')

browser_text = (root / 'app/src/main/kotlin/com/mikeyphw/xdm/android/BrowserScreen.kt').read_text()
if 'BrowserBridge' in browser_text or 'object BrowserBridge' in browser_text:
    raise SystemExit('Browser screen must not keep WebView in a static bridge')
if 'private object' in browser_text and 'WebView?' in browser_text:
    raise SystemExit('Browser screen must not place WebView on a static object')
print('Browser media downloader validation passed')
