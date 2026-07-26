# Phase 40: Shared theme contract and themed Firefox FAB

## Purpose

Phase 40 makes XDM Android and its generated Firefox extension consume one palette, shape, and motion contract. It also replaces the large in-page media card with a compact Shadow DOM floating action button while preserving the working Phase 37 custom-scheme handoff and Phase 39 deterministic export path.

## Shared token source

`browser-extension/src/main/kotlin/.../XdmThemeTokens.kt` is the non-Compose source of truth for:

- Dark and AMOLED structural colors
- primary and container accents
- text, muted text, outlines, separators, success, and error colors
- FAB size, corner radius, edge inset, action gap, and motion durations

`XdmTheme.kt` converts those tokens into Compose colors. `XdmThemeCssGenerator.kt` converts the same tokens into generated extension CSS and configuration. The package generator no longer carries a second color map.

## Theme selection and staleness

Browser-extension export supports:

- Follow app
- Dark
- AMOLED

Follow app resolves to the concrete XDM theme when the XPI is generated. Firefox cannot read Android DataStore after installation, so an app theme change marks the previous export as stale and Settings offers regeneration. The verified Phase 39 export transaction and deterministic filename remain unchanged.

## In-page FAB

The extension launcher is mounted as:

```text
#__xdm_media_fab_host
  #shadow-root
    a.xdm-fab or button.xdm-fab
    optional compact target actions
```

The FAB contract includes:

- 56 px minimum primary target
- 18 px XDM corner radius
- bottom-end safe-area placement
- XDM icon and generated palette
- candidate-count and HLS/DASH indicators
- XDM, 1DM+, and Ask target modes
- open Shadow DOM for testability and CSS isolation
- keyboard focus, ARIA labels, and reduced-motion support
- fullscreen reparenting
- candidate expiry without whole-document polling or repeated scans

Detailed detector state remains in the extension popup. The page surface stays compact.

## Security and compatibility

Phase 40 does not expand the custom URI payload. XDM links remain credential-thin and do not add cookies, authorization headers, proxy credentials, request bodies, or raw header blocks. Existing native in-page anchors remain the only direct launch path.

The phase adds no Android browser runtime, top-level route, Room migration, transfer-engine change, versionCode change, or versionName change.

## Validation

Focused validation includes:

```text
python tools/validate-phase-40-theme-fab.py
:browser-extension:test
:browser-extension:jsTest
:browser-extension:validateFirefoxExtension
:browser-extension:packageFirefoxExtensionDark
:browser-extension:packageFirefoxExtensionAmoled
:app:testDebugUnitTest
```

Tests cover shared token parity, Dark and AMOLED generation, deterministic package output, Follow app resolution, stale-export state, Shadow DOM isolation, safe-area placement, minimum target size, reduced motion, candidate and stream badges, target expansion, fullscreen behavior, and removal of the old floating card.
