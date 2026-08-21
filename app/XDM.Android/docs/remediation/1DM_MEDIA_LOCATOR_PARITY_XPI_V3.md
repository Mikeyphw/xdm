# XDM Android — 1DM-style Media Locator Parity + Keyless XPI v3

## Purpose

This remediation fixes two coupled browser/media problems:

1. The generated Firefox/IronFox XPI could become unusable after an Android app reinstall, rebuild, or key rotation and then report that the capture could not be verified / the XPI must be generated again.
2. Browser and app sniffing could promote resources that merely looked media-like (for example a JSON endpoint or a URL embedded in arbitrary text) into media candidates.

The new design treats the browser extension and XDM's live locator as **media observers**, not download authorities. Detection produces evidence-backed candidates; the Android media pipeline classifies them again; the user reviews the result; only then does the existing download planner select Native, aria2, or yt-dlp behavior.

## Behavioral reference: 1DM+ 18.2 APK

The supplied `1DM+_18.2.apk` was used only as a behavioral reference. No source code was copied. The APK's DEX strings show the following relevant mechanisms:

- XMLHttpRequest interception around `open`, `setRequestHeader`, and response completion.
- A `handleM3u8Url(...)` bridge carrying request URL, response URL/body, content type, content disposition, content length/range, page URL, request headers, and a source label.
- Fetch interception with response/body handling.
- DOM preview inspection of `body > video`, `video > source`, `body > audio`, and audio source elements.
- `loadedmetadata`, `videoWidth`, and `videoHeight` checks for actual media playback metadata.
- HLS markers such as `#EXT-X-MEDIA` and `#EXT-X-MEDIA-SEQUENCE`.
- DASH/HLS MIME evidence including `application/dash+xml` and `application/vnd.apple.mpegurl`.
- A setting named `SETTINGS_ADDITIONAL_FILE_TYPES_IN_SNIFFER`, consistent with treating additional file suffixes as configurable hints rather than universal proof.

The useful parity principle is therefore: **combine DOM/playback evidence, response headers, response bodies/manifests, and URL/type hints; do not equate every intercepted URL with downloadable media.**

## Root cause 1: XPI verification/regeneration loop

The previous XDM contract used an encrypted v2 browser handoff. Every generated XPI embedded Android-install-specific RSA public-key material. The Android side used a private key in AndroidKeyStore and rejected an envelope when its `captureKeyId` no longer matched the active app key. Consequently, ordinary events such as clearing app data or reinstalling a build could make a still-installed extension stale even though the extension code had not changed.

The XPI stale check also included Android app version metadata, causing regeneration for app-version changes that did not actually require different extension behavior.

### New contract

New packages use:

`xdmdownload://capture?v=3&url=...`

The v3 capture contract is direct and keyless. It carries:

- exact selected media URL;
- page/frame URL where useful;
- sanitized title/file/MIME/kind metadata;
- stable/revision metadata where present;
- content length/duration/thumbnail when known;
- only the bounded replay-header allowlist needed to reproduce the browser request:
  - Authorization
  - Cookie
  - Referer
  - User-Agent
  - Origin
  - Accept
  - Accept-Language
  - Range

Header values are control-character sanitized and bounded. The whole URI is bounded to 64 KiB.

### Migration behavior

- Newly generated XPI: emits v3 only.
- Android app: accepts v3 directly.
- Legacy encrypted v2 parser/journal support remains in place so a previously installed old extension can still be imported while its old key remains valid.
- v1 plaintext capture remains rejected; v1 direct **Add Download** remains a distinct compatibility path.

The AndroidKeyStore/encrypted-v2 classes are intentionally retained for legacy-reader migration only. They are no longer inputs to new XPI generation.

### XPI freshness behavior

A package is no longer stale merely because `appVersion` changed and no longer receives:

- `captureKeyId`
- `capturePublicKeySpki`
- `captureOaepHash`

A user must install this v3 XPI once because the extension implementation/contract itself changed. After that, normal XDM rebuilds/reinstalls do not invalidate it simply because the Android install key changed.

## Root cause 2: false media positives

There were three permissive promotion paths.

### Extension response classifier

A media-looking suffix could outweigh weak or conflicting evidence. The fix gives explicit hard non-media MIME types priority over URL suffixes.

Hard-rejected response MIME families include:

- `application/json`
- `application/ld+json`
- JavaScript MIME types
- `text/html`
- `text/css`
- `image/*`
- `font/*`

Thus `https://api.example/fake.mp4` with `Content-Type: application/json` is **not** media.

A bare `.mp4`, `.webm`, etc. on fetch/XHR/resource traffic is now only possible evidence rather than an automatic strong offer. An actual browser `media` request, media MIME, manifest MIME/body, media content-disposition, or playback evidence is required to promote it strongly.

### Extension response-body scan

The old body scan could discover URLs in arbitrary JSON/text and promote them too easily. The new body detector only accepts media URLs when the surrounding structure itself carries media semantics:

- HLS/DASH manifest keys/body markers;
- explicit video/audio/media/file keys **plus a real media suffix**;
- no promotion of extensionless `streamUrl` merely because its JSON property name says `stream`.

Generic JSON such as:

```json
{
  "url": "https://cdn.example/not-media.mp4",
  "poster": "https://cdn.example/poster.mp4",
  "analytics": "https://cdn.example/event.m3u8"
}
```

produces no browser media candidate.

### App-side page/body scan

The app's `MediaSniffingEngine` previously scanned arbitrary HTTP URLs, generic HTML attributes, and CSS URLs. A URL suffix could then promote a CSS background, poster, API value, or other non-media artifact.

For fetched pages/network observations the app now extracts only:

- explicit structured media keys;
- `<video src>`, `<audio src>`, and `<source src>`;
- the response URL itself when response MIME/body evidence supports it;
- verified HLS/DASH manifest bodies.

Generic URL extraction remains only for explicitly user-supplied text/batch/manual input, where the user has intentionally asked XDM to inspect those URLs.

The shared `MediaCandidateClassifier` also hard-rejects explicit JSON/HTML/JS/CSS/image/font MIME even when the URL ends in a media suffix.

## Evidence model

### Strong evidence

A candidate can be promoted normally when one or more of these is present:

- `video/*` response MIME;
- `audio/*` response MIME;
- HLS MIME;
- DASH MIME;
- HLS body begins with `#EXTM3U` / contains HLS markers;
- DASH body contains `<MPD`/DASH namespace evidence;
- actual `<video>`, `<audio>`, or `<source>` DOM source;
- actual media playback observation;
- content-disposition names a known media/manifest file;
- actual browser `media` request with suitable corroboration.

### Possible evidence

By default, weak candidates are not auto-offered. Examples:

- a media-looking suffix seen only in generic network/resource traffic;
- octet-stream with a stream-shaped URL but no stronger evidence.

The extension keeps its existing possible-candidate preference for users who deliberately opt into weak candidates, but these no longer bypass hard non-media MIME rejection.

### Rejected/noise

- JSON/HTML/JS/CSS/image/font responses;
- ad/tracker URLs;
- HLS/DASH segments such as `.ts` and `.m4s`;
- chunk/segment/init paths;
- poster/image attributes;
- CSS background URLs;
- arbitrary generic JSON `url` values;
- unsupported/non-HTTP media locator schemes such as `blob:` as final downloadable URLs.

For `blob:` playback, XDM must discover the underlying HTTP(S) media request/manifest rather than trying to download the blob identifier.

## Standalone live media locator

A new non-exported `MediaLocatorActivity` complements the existing bounded static page probe.

The Media screen now exposes:

- **Static sniff** — bounded HTTP page probe, no JavaScript; useful for simple pages/manifests.
- **Live media locator** — loads the page in an app WebView and observes runtime media behavior.

The live locator observes:

- `video`, `audio`, and `source` DOM elements;
- later DOM changes through `MutationObserver`;
- actual playback events;
- Fetch responses;
- XMLHttpRequest responses;
- Resource Timing entries only when the browser marks the initiator as actual `video` or `audio`; suffix-only performance resources are ignored.

For fetch/XHR it collects, where browser policy exposes them:

- response URL;
- content type;
- content disposition;
- content length;
- a bounded body only for manifests or small inspectable JSON/text responses;
- allowed request headers.

The runtime then sends every observation into the **same `MediaSniffingEngine` used by the rest of XDM**. The WebView bridge itself does not create a download.

When the user taps a located item, the activity builds the same direct v3 capture contract and opens `ExternalHandoffReviewActivity`. XDM's normal review, persistence, resolver, variant/track selection, and download planner remain authoritative.

For session-bound media the live locator augments the selected candidate with WebView session context needed for the later request:

- Cookie for the media origin;
- WebView User-Agent;
- Referer page URL;
- allowed fetch/XHR request headers that were visible to page JavaScript.

## Download behavior

The downloader itself is intentionally not replaced. The parity change is about how media arrives at it.

The final flow is:

```text
Browser extension OR live app locator OR static app probe
                |
                v
       observation / candidate
                |
                v
  evidence gate + shared classifier
                |
                v
       XDM review / Media inbox
                |
                v
 manifest resolver / variant selection
                |
                v
 existing Download planner
   Native / aria2 / yt-dlp
```

This preserves XDM's existing queueing, persistence, retries, post-processing, and destination behavior while making media acquisition much closer to 1DM's locator model.

## R2 promise-closure audit

A post-implementation audit found and closed four gaps in the initial v3 overlay:

1. The live WebView locator still treated any Resource Timing URL ending in a media suffix as evidence. R2 requires `initiatorType` to be `video` or `audio`; suffix-only performance resources are ignored.
2. `Accept-Language` was allowed by the v3 handoff/parser but was missing from the extension's privileged and page-visible capture allowlists. Both producers now preserve it.
3. Weak `possible` candidates were filtered before being stored, preventing a later `#EXTM3U`/DASH body or playback event from promoting the same privileged request. R2 stores weak evidence internally, exposes only allowed candidates, promotes correlated manifest/playback evidence to `strong`, and prevents later weak observations from downgrading a promoted candidate.
4. If Android rejected a browser media observation, `executeCaptureMediaCommand` fell back to generic Add Download. R2 rejects the automation with `NoMediaDetected`, shows `Non-media capture ignored`, and does not create a generic download.

R2 also adds explicit v3 content-length/duration/thumbnail regression coverage, updates legacy-v2 stale-key guidance, and wires `tools/validate-1dm-media-locator-xpi-v3.py` into the current final static release gate.

## Files changed

### Browser/XPI lifecycle

- `browser-integration/.../XdmBrowserDeepLinkContract.kt`
- `browser-integration/.../XdmBrowserDeepLinkParser.kt`
- `browser-integration/.../XdmBrowserDeepLinkPayload.kt`
- `browser-extension/.../BrowserExtensionBuildConfig.kt`
- `browser-extension/.../BrowserExtensionPackageGenerator.kt`
- `browser-extension/.../BrowserExtensionPackageValidator.kt`
- `browser-extension/.../BrowserExtensionPackageCli.kt`
- `browser-extension/.../BrowserExtensionSourceContract.kt`
- `browser-extension/src/main/extension/xdm-firefox/generated-config.template.js`
- `browser-extension/src/main/extension/xdm-firefox/handoff.js`
- `browser-extension/build.gradle.kts`
- `browser-extension/tools/prepare_extension.py`
- `browser-extension/tools/verify_release_artifacts.py`
- `app/.../BrowserExtensionExportModels.kt`
- `app/.../MainViewModel.kt`

### Media parity

- `browser-extension/.../detector-core.js`
- `browser-extension/.../page-sniffer.js`
- `browser-extension/.../network-observer.js`
- `browser-extension/.../frame-bridge.js`
- `media/.../MediaInboxContract.kt`
- `media/.../MediaSniffingEngine.kt`
- `app/.../MediaLocatorActivity.kt` (new)
- `app/.../ui/media/MediaInboxScreen.kt`
- `app/src/main/AndroidManifest.xml`

### Regression/static gates

The overlay updates the browser JS tests, browser-integration/media tests, historical contract tests that encoded encrypted-v2 as the only accepted implementation, and the active final static gate.

## Validation performed in the generation environment

Passed:

- all browser-extension JavaScript tests;
- generated release extension validation;
- runtime-foundation migration-aware static validator;
- complete `tools/run-final-release-gate.sh --ci`, including:
  - bug-hunt phases 1–11;
  - media quality/privacy/mobile/final validators;
  - final Phase 13 validator;
  - 80-row Phase 11 static validation matrix.

Not executable in the generation environment:

- Gradle/JVM/Android compilation and unit tests. The checked-in wrapper requires Gradle 9.7.1, which is not cached in this sandbox, and this sandbox cannot reach `services.gradle.org`.

Run the Gradle tasks on the normal Termux validator after applying the overlay.

## Recommended Devtool validation

```bash
devtool -r ~/Code/xdm validate \
  --task :browser-extension:test \
  --task :browser-extension:jsTest \
  --task :browser-extension:validateFirefoxExtension \
  --task :browser-integration:test \
  --task :media:test \
  --task :app:testDebugUnitTest \
  --task :app:lintDebug
```

If your current XDM Devtool target already defines the full release matrix, running the target's normal validation after these focused tasks is preferred.

## Installation/migration sequence

1. Apply the source overlay.
2. Run the focused Devtool validation above.
3. Build/install the Android app.
4. Generate/install the new **1.3.0 / contract-v3** XPI once, or use the included preview XPI for smoke testing if your browser permits unsigned local XPIs.
5. Remove/replace the old v2 extension copy so the browser is definitely running the v3 code.
6. Test:
   - normal MP4 page;
   - HLS page;
   - DASH page;
   - authenticated/signed media page;
   - JSON API whose URL ends in `.mp4` (must not offer);
   - page containing poster/image/CSS URLs ending in media-like names (must not offer);
   - app **Live media locator** on a JS-driven player.
7. Rebuild/reinstall XDM without changing the extension. v3 captures should continue working without a regenerate-XPI/key verification failure.

## Deliberate non-goals

- No DRM bypass.
- No attempt to turn HLS/DASH segments into individual downloads.
- No direct download of `blob:` identifiers.
- No promise that JavaScript can see browser-internal request headers hidden by browser policy.
- No claim of byte-for-byte or UI parity with proprietary 1DM implementation; this is behavioral parity around media locator/capture evidence and handoff semantics.
