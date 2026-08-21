# XDM Android Firefox extension source

This module owns the canonical Firefox Android extension source for XDM. It is deliberately WebKit-free and does not embed a browser runtime in the Android app.

## Development build

```bash
./gradlew :browser-extension:prepareFirefoxExtension
```

Load the unpacked extension from:

```text
browser-extension/build/firefox/unpacked/
```

## Validation

```bash
./gradlew :browser-extension:test :browser-extension:jsTest :browser-extension:validateFirefoxExtension
```

Phase 38 does not create or commit an XPI. Phase 39 will reuse the source templates and validation contract to produce deterministic XPIs from Gradle and the Android app.

XDM media capture uses the direct `xdmdownload://capture?v=3&url=...` media-locator contract. Generated XPIs are no longer bound to an AndroidKeyStore RSA key, so rebuilding/reinstalling XDM does not invalidate an installed extension. The handoff remains bounded and sanitized, but media URLs and the small allowlisted request-header set needed to reproduce browser requests are intentionally transported directly. Legacy encrypted-v2 links remain readable by the Android app during migration.


## Phase 39 packages

```bash
./gradlew :browser-extension:packageFirefoxExtension
./gradlew :browser-extension:packageFirefoxExtensionDark
./gradlew :browser-extension:packageFirefoxExtensionAmoled
```

Outputs are deterministic, validated XPIs under `build/outputs/xpi/`. The Android app calls the same Kotlin generator when exporting through a persisted SAF folder.

## Phase 40 shared theme and FAB

The Android Compose theme and generated Firefox packages now consume `XdmThemeTokenCatalog` as one palette, shape, and motion source. `Follow app` resolves to Dark or AMOLED when the XPI is generated; later app-theme changes mark the previous export as stale.

The webpage launcher is a 56 px Shadow DOM FAB with safe-area placement, candidate and HLS/DASH badges, reduced-motion support, and XDM, 1DM+, or Ask target behavior. Detailed status remains in the popup rather than a large page card.
