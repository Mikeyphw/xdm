# Phase 48 — Final UX and Release Gate

Phase 48 is the final seal for the Firefox Video AutoDL / Android browser-bridge roadmap. It does not add a new runtime feature. It records the Phase 47 green baseline, preserves the review-first UX contract, and makes the release gate explicit enough that future overlays cannot accidentally reintroduce browser runtime behavior, placeholder actions, raw URI notification payloads, or unchecked extension artifacts.

## Baseline

- Clean baseline commit: `6e3ad8d`.
- Landed overlay: `xdm_android_phase47_ui_contract_hotfix_r6_overlay.zip`.
- Validation result: 358 passed, 0 failed, 0 skipped.
- Diagnostics result: 0 warnings, 0 errors.

## Final UX contract

The user-visible experience must remain review-first:

1. External Add Download handoffs open review UI first and never create a transfer automatically.
2. Direct media URLs can recommend media inspection, but ordinary manual links do not nag the user with media analysis.
3. Media batch input exposes real actions: Inspect all, Add selected, Clear invalid, and Copy rejected lines.
4. Completed notification taps go through the non-exported open-file trampoline and revalidate completed state before launching an external viewer.
5. Failed, paused, and recovery notifications open XDM details instead of guessing at files.
6. No placeholder click handlers are allowed in primary UI surfaces.
7. No new top-level app route is introduced by this bridge program.

## Browser and extension release contract

Phase 48 keeps the extension path, not a built-in browser path:

- Browser runtime must not be reintroduced.
- Generated XPI files must not be committed as source.
- Dark and AMOLED extension packages must be produced by the build gate.
- Extension release artifact inventory verification must pass.
- The repo-owned extension source contract remains the only source for packaged extension artifacts.

## Ship / no-ship gate

A publishable handoff requires all of the following:

- Gradle build tasks complete successfully.
- Unit-test matrix completes successfully.
- Android lint produces 0 errors.
- Devtool diagnostics report 0 warnings and 0 errors.
- Release extension artifact verification passes.
- Static Phase 48 validator passes.
- Raw secret diagnostics remain redacted.
- Room schema remains 14 for this release gate.
- Version metadata remains `0.20.0-rc08` / version code 21.

## Full validation command shape

The release gate is intentionally the same broad task matrix that sealed Phase 47 r6:

```text
assembleDebug
:app:assembleDebugAndroidTest
:browser-extension:packageFirefoxExtensionDark
:browser-extension:packageFirefoxExtensionAmoled
:browser-extension:verifyFirefoxExtensionReleaseArtifacts
:browser-extension:test
:browser-extension:jsTest
:browser-extension:validateFirefoxExtension
:app:checkBrowserIntegration
:core-model:test
:core-utils:test
:transfer-api:test
:browser-integration:testDebugUnitTest
:storage:testDebugUnitTest
:transfer-native:testDebugUnitTest
:transfer-aria2:test
:scheduler:testDebugUnitTest
:media:test
:persistence:testDebugUnitTest
:app:testDebugUnitTest
```

## Non-goals

Phase 48 does not add JavaScript page execution, DRM bypass, browser runtime, WebView or GeckoView surfaces, Room migrations, or new transfer execution behavior.
