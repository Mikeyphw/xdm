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

The extension defaults to XDM through the Phase 37 `xdmdownload://capture?v=1&url=...` contract, retains an optional 1DM+ in-page fallback, and never launches a custom protocol from the popup or background context.


## Phase 39 packages

```bash
./gradlew :browser-extension:packageFirefoxExtension
./gradlew :browser-extension:packageFirefoxExtensionDark
./gradlew :browser-extension:packageFirefoxExtensionAmoled
```

Outputs are deterministic, validated XPIs under `build/outputs/xpi/`. The Android app calls the same Kotlin generator when exporting through a persisted SAF folder.
