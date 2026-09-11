# XDM Media Parity04 Browser UX, Userscripts, Notifications & Release Seal Report

Date: 2026-09-11
Overlay: `xdm_media_parity04_browser_ux_userscripts_notifications_release_seal_v1.zip`
Target: `xdm_android`
Preimage: post-Parity03 tree after `xdm_media_parity03_native_hls_execution_admission_integrity_v2.zip`
Room schema: 24

## Purpose

Parity04 is the final product and release seal for the V2 media-parity campaign that began from the 1DM+ comparison. Parity01 made diagnostics/runtime truth fail-closed, Parity02 collapsed raw browser observations into logical media, and Parity03 admitted supported HLS as one durable native segmented job. Parity04 turns those foundations into the user-facing experience requested in the original prompt.

## Implemented scope

### Lightweight WebView browser shell

The Live Locator / embedded WebView surface is now browser-first. The page viewport is no longer dominated by a fixed raw-media overlay. Navigation remains visible and the detected-media affordance moves to a floating `Media` action.

### Floating media review before admission

Detected logical media opens through a chooser/review dialog rather than a permanently visible raw list. This preserves Parity02's logical-media graph and prevents the original failure mode where one real video appears as dozens of visible parts.

### Firefox and WebView parity carry-forward

Parity02's Firefox logical chooser/direct-v3 metadata and Android import deduplication remain the accepted capture boundary. Parity04 seals this as product behavior so Firefox and WebView both present logical media before app-side admission rather than exposing raw observations as downloads.

### Add Download UX

The Add surface keeps one primary `Download` action. Link and filename fields are allowed to wrap, and the media/adaptive path exposes quality and track review guidance before the user admits a download.

### Userscripts

A local app-private userscript store is added for constrained Tampermonkey-style scripts. Supported metadata includes `@name`, `@match`, `@include`, and `@grant none`. Scripts are injected at document-start when supported by the WebView environment and fall back to page-finish injection otherwise. Privileged extension APIs are deliberately not promised.

### No silent errors

The browser path now surfaces visible feedback for invalid URLs, main-frame WebView/network/HTTP/SSL failures, save/import success, and userscript validation/injection failures. Recoverable problems should not disappear silently into logs only.

### Completed download notifications

Completed transfer notifications now include actionable outcomes: open the artifact, view details, and fallback to XDM details when direct artifact opening cannot be resolved.

### Text and filename resilience

Known clipping/eclipsing surfaces were updated to prefer wrapping over one-line ellipsis for long URLs, filenames, media candidate titles/details, and related review text. This includes explicit resource-backed text so validators can guard against regressions.

### Developer / Debug Center consolidation

Developer Center is the current validation-truth surface. It carries forward Parity01 diagnostics, Parity02 logical capture evidence, Parity03 native HLS/admission/completion integrity, and Parity04 browser UX/userscript/notification evidence. It explains blocked/deferred Gradle/device/release validation instead of presenting stale boolean success.

## Source changes

Added:

- `app/src/main/kotlin/com/mikeyphw/xdm/android/WebViewUserscriptStore.kt`
- `core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/MediaParity04UxModels.kt`
- `app/src/test/kotlin/com/mikeyphw/xdm/android/MediaParity04BrowserUxReleaseSealContractTest.kt`
- `tools/validate-media-parity04-browser-ux-release-seal.py`
- `XDM_MEDIA_PARITY04_BROWSER_UX_USERSCRIPTS_NOTIFICATIONS_RELEASE_SEAL_REPORT.md`

Modified key surfaces:

- `MediaLocatorActivity.kt`
- `XdmAccessibility.kt`
- `AddDownloadSurface.kt`
- `TransferNotifications.kt`
- `TerminalNotificationActionPolicy.kt`
- `DeveloperToolsScreen.kt`
- `strings.xml`
- carry-forward validators and final-gate runner
- `PROJECT_MANIFEST.json`
- `README.md`

## Validation performed in this environment

Passed:

- `tools/validate-media-parity04-browser-ux-release-seal.py`
- Firefox/browser-extension Node suite
- `strings.xml` XML parse
- Phase-11 static matrix validators, including the retained 80-row roadmap evidence matrix
- Parity01 runtime-truth validator
- Parity02 logical capture validator
- Parity03 native HLS/admission/completion validator
- all validators listed by `tools/run-final-release-gate.sh --ci` through the Parity04 validator passed individually
- `tools/run-bug-hunt-phase11-validation-matrix.sh --static-only --ci` passed, including the retained 80-row evidence matrix
- overlay source/final SHA-256 replay against a clean reconstructed post-Parity03 preimage

Not claimed in this container:

- one-shot all-in-one `tools/run-final-release-gate.sh --ci` process exit capture in this container; its constituent validators were run and passed individually
- Gradle unit/lint/release build execution
- Android connected instrumentation
- real-device APK install/upgrade/reboot matrix

Those remain owned by the target Termux/Android Devtool environment where Gradle 9.7.1, Android SDK/NDK 29+, Java 21, and device context are available.

## Apply policy

Parity04 is the final overlay in the four-overlay campaign. Unlike Parity01-Parity03, it is intended to be followed by the exhaustive validation task matrix in the target environment after apply. The overlay itself is packaged with exact source SHA-256 preimage checks.
