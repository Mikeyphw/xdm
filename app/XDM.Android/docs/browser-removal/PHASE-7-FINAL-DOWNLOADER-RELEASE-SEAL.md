# Phase 7: Final Downloader Regression and Release Seal

Phase 7 closes the built-in-browser removal program. It adds no downloader feature and does not change Room schema or application version.

## Final product identity

XDM Android is a download manager. External browsers and applications may explicitly share content or delegate typed download intents to XDM. XDM does not claim ordinary web navigation and contains no browsing engine.

The authoritative long-lived contract is `docs/architecture/DOWNLOADER_PRODUCT_CONTRACT.md`.

## Manifest hardening

The scheme-only HTTP, HTTPS, and FTP `ACTION_VIEW` filter is removed from `ExternalAddDownloadActivity`. Typed MIME handlers and file-extension-specific handlers remain, as do share-sheet and Android browser download-manager actions.

This keeps XDM discoverable for actual downloads without making it a candidate for opening normal web pages.

## Permanent regression contracts

The final validator and tests lock:

- absence of Browser routes, activities, screens, launchers, WebKit, tabs, bookmarks, history, private sessions, and browser persistence;
- one normal application launcher;
- no generic browser intent claim;
- preservation of explicit external handoff and review-first intake;
- preservation of native, aria2, Termux, scheduler, worker, queue, resolver, offline-library, and Media3 paths;
- Room schema 14 and browser-free schema/preferences;
- the stable six-destination downloader shell;
- unchanged `versionCode 21` and `versionName 0.20.0-rc08`.

## Release gate

The target Android environment must run:

```bash
./gradlew -Pxdm.requireAria2Runtime=true --stacktrace   lintDebug lintBeta   :media:test :transfer-api:test :storage:test   :transfer-native:test :transfer-aria2:test :scheduler:test   :persistence:testDebugUnitTest testDebugUnitTest   :app:assembleDebugAndroidTest assembleDebug assembleBeta
```

The Android test APK is assembled so the PackageManager non-browser test remains compile-checked. The final manual release pass should execute that instrumentation test on a device or emulator.

## Manual release checklist

- clean install and open Downloads;
- upgrade from a browser-enabled build without startup failure;
- confirm an ordinary HTTPS page does not offer XDM as a browser;
- share a page from IronFox or another browser to Add or Media review;
- open typed APK, archive, PDF, audio, video, HLS, and DASH downloads with XDM;
- run native, aria2, and Termux/yt-dlp jobs;
- pause, resume, cancel, retry, recover, and verify transfers;
- play completed direct media from Library;
- confirm diagnostics remain redacted;
- confirm no WebView classes are packaged.

After these checks, the browser-removal roadmap is complete.
