# Built-in Browser Removal: Phase 0 and Phase 1

## Status

This phase is deliberately non-destructive. It records the current browser/downloader boundary and installs preservation contracts before any browser runtime code is removed.

- No production Kotlin source is changed.
- `AndroidManifest.xml` is not changed.
- No route, activity, WebView, preference, database table, or browser document is removed yet.
- Android version metadata and Room schema remain unchanged.

The machine-readable inventory is `docs/browser-removal/BROWSER-DOWNLOADER-BOUNDARY.json`.

## Phase 0: Baseline inventory

### Browser runtime to remove in later phases

The built-in browser currently owns `BrowserActivity`, `BrowserScreen`, `AppRoute.Browser`, its launcher entry, generic HTTP/HTTPS browser handling, Android WebKit, tabs, browsing history, bookmarks, private browsing, site permissions, browser settings, the resource inspector, and browser-specific download rules.

### Mixed files requiring extraction before deletion

The following files combine browser ownership with downloader behavior and must be edited surgically rather than reverted:

- `app/src/main/AndroidManifest.xml`
- `AppRoute.kt`
- `XdmApp.kt`
- `MainActivity.kt`
- `MainViewModel.kt`
- `MediaBrowserCaptureQuality.kt`

`MainActivity` is especially important. It contains both the BrowserActivity startup path and the external download receiver path. Later removal must delete only the internal-browser startup branch while retaining share text, subject text, ClipData, browser download-manager actions, URL normalization, header sanitization, and review-first automation ingestion.

### Browser-named code that belongs to external downloader integration

These surfaces survive browser removal unless renamed in a later migration:

- `ExternalAddDownloadActivity`
- `BrowserHandoffContract`
- `BrowserHandoffPolicy`
- `SharedLinkParser`
- the `browser-integration` module

Their responsibility is accepting explicit handoff from external browsers and applications. They do not make XDM a browser, and the module currently contains no `android.webkit`, `WebView`, `WebViewClient`, or `WebChromeClient` runtime dependency.

### Downloader runtime protected by this phase

The preservation ledger covers native and aria2 transfer backends, scheduler execution, media resolution, track selection, execution dispatch, queue telemetry and actions, worker bridging, the Termux yt-dlp adapter, the offline media library, privacy auditing, player diagnostics, and Media3 playback.

## Manifest boundary

Three activity contracts must remain distinct:

1. `MainActivity` is the downloader launcher and typed action shell.
2. `BrowserActivity` is the general built-in browser entry point and will be removed later.
3. `ExternalAddDownloadActivity` is the dedicated download receiver and must remain.

The future manifest edit must not delete all HTTP/HTTPS filters indiscriminately. It must remove the generic browsing claim from `BrowserActivity` while preserving explicit review-first handoff into `ExternalAddDownloadActivity`.

## Phase 1: Preservation contracts

### External intake

The new contract suite protects:

- `ACTION_SEND` and `ACTION_SEND_MULTIPLE`
- download-oriented `ACTION_VIEW`
- HTTP, HTTPS, and FTP normalization
- text, subject, ClipData URI, and ClipData coerced-text inspection
- common external download URL and filename extras
- review-first `PromptAddDownload`
- explicit `CaptureMedia`
- no direct transfer start from `MainActivity`

### Privacy

The contracts verify that Authorization, Cookie, and token-bearing surfaces are redacted, that safe headers remain visible, and that an already-redacted placeholder stays redacted rather than being treated as a new secret.

### Downloader engines and media stack

Source-presence and symbol contracts protect:

- native HTTP execution
- embedded aria2 execution
- transfer scheduling and process recovery
- media download planning
- execution-lane dispatch
- queue actions and telemetry
- worker bridge planning
- Termux runtime planning
- offline library V2
- Media3 playback
- player diagnostics

These tests are intended to remain green through later browser removal. If a protected file is intentionally renamed or split, its preservation contract must move with the behavior in the same overlay.

## Baseline gate finding

The existing `tools/run-final-release-gate.sh --ci` does not currently reach Gradle. `validate-browser-media-downloader.py` still enforces the pre-Phase-37 rule that the browser lives under Media, while the current source has a first-class Browser route. This phase records that mismatch rather than weakening or deleting historical browser validators prematurely.

The Phase 49 and Phase 50 validator path typo in `run-final-release-gate.sh` is corrected here. Full browser-era validator reconciliation belongs to the later contract/documentation cleanup phase, when the browser runtime is actually removed.

## Validation introduced

- `BrowserRemovalPreservationContractTest`
- `DownloaderHandoffPreservationTest`
- expanded `SharedLinkParserTest`
- `tools/validate-browser-removal-phase-0-1.py`

The dedicated validator is included in Android CI and the final-gate validator list. It can also be run independently:

```bash
cd app/XDM.Android
python3 tools/validate-browser-removal-phase-0-1.py
./gradlew :core-model:test :browser-integration:testDebugUnitTest :app:testDebugUnitTest
```

## Exit criteria

Phase 0 and Phase 1 are complete when:

- the inventory and ownership classification are present;
- the new static validator passes;
- the three preservation test suites pass;
- no production Android runtime source changed;
- external handoff, media, queue, engine, playback, and redaction contracts are locked before browser extraction begins.
