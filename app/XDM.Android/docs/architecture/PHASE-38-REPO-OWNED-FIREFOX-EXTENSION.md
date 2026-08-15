# Phase 38 — Repository-owned Firefox extension

Phase 38 makes the Firefox Android media bridge canonical source inside XDM. It depends on the Phase 37 custom-scheme receiver and does not add a browser runtime, WebView, top-level route, Room migration, or generated XPI artifact.

## Source boundary

`browser-extension` is a pure Kotlin/JVM and source-packaging module. Its extension source lives under `src/main/extension/xdm-firefox`. The Android app does not execute this JavaScript or embed Gecko/WebKit.

## Detection layers

The repository-owned detector retains the v6.4 layered approach: response MIME observation, extensionless streams, bounded fetch/XHR response inspection, HLS and DASH body recognition, inline player data extraction, DOM media events, all-frame aggregation, blob/MediaSource correlation, segment/ad rejection, candidate ranking, expiration, deduplication, and diagnostic URL redaction.

Response inspection is capped at 768 KiB. Binary media bodies are never buffered.

## Handoff targets

- **XDM** is the default. The original Phase 38 plaintext `capture?v=1&url=...` transport has been superseded; current browser media capture requires an encrypted v2 handoff.
- **1DM+** remains an optional in-page `idmdownload:` fallback.
- **Ask every time** exposes both choices in the injected page launcher.

The extension popup never contains custom-protocol anchors and never launches an app through background tab navigation. It only asks the content script to place a genuine anchor inside a normal webpage.

The current XDM capture URI carries only encrypted-envelope fields (`sid`, `kid`, `ek`, `iv`, `ct`). Exact media/page URLs and bounded request context live inside authenticated ciphertext; production browser runtime has no plaintext capture fallback.

## Development

```bash
./gradlew :browser-extension:prepareFirefoxExtension
./gradlew :browser-extension:test :browser-extension:jsTest :browser-extension:validateFirefoxExtension
```

Load the unpacked result from `browser-extension/build/firefox/unpacked`. Phase 39 will add deterministic XPI generation and Android SAF export.
