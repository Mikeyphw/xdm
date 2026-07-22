#!/usr/bin/env python3
from pathlib import Path
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
if 'Browser("Browser"' in route_text or 'Icons.Rounded.Search' in route_text:
    raise SystemExit('Browser media downloader must live under Media and must not add a top-level route')

browser_text = (root / 'app/src/main/kotlin/com/mikeyphw/xdm/android/BrowserScreen.kt').read_text()
if 'BrowserBridge' in browser_text or 'object BrowserBridge' in browser_text:
    raise SystemExit('Browser screen must not keep WebView in a static bridge')
if 'private object' in browser_text and 'WebView?' in browser_text:
    raise SystemExit('Browser screen must not place WebView on a static object')
print('Browser media downloader validation passed')
