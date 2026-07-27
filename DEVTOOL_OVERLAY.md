# XDM Android Phase 40 shared theme and themed FAB overlay

## Included

- A non-Compose Dark/AMOLED token catalog shared by XDM Compose and Firefox package generation.
- Generated CSS and extension configuration for palette, shape, and motion tokens.
- Follow app, Dark, and AMOLED export choices with stale-export detection and regeneration guidance.
- A 56 px safe-area-aware Shadow DOM FAB with XDM, 1DM+, and Ask targets, candidate and stream badges, accessibility semantics, reduced motion, and fullscreen reparenting.
- Focused Kotlin, JavaScript, static, package, documentation, project-manifest, and CI validation.
- No custom-scheme payload expansion, browser runtime, top-level route, Room migration, transfer-engine change, or app version change.

## Validation policy

The embedded validator replays Phases 37 through 40 static contracts, JavaScript syntax and behavior suites, Dark and AMOLED unpacked rendering, deterministic XPI generation, shared token tests, app contract tests, and focused Gradle tasks. Phase 42 remains responsible for the complete release matrix.

## Apply

Use Devtool with `--validate`. The schema-v2 artifact owns its commit message; do not add a CLI `--commit` flag.

# XDM Android Phase 37 browser scheme contract overlay

## Included

- Variant-specific `xdmdownload`, `xdmdownload-beta`, and `xdmdownload-debug` manifest contracts.
- A pure-Kotlin version-1 parser in `browser-integration`.
- `capture` routing to Media review and `add` routing to Add Download.
- Strict inner URL policy, payload bounds, credential rejection, and no sensitive-header transport.
- Existing stable automation idempotency for repeated deliveries.
- JVM, source-contract, Android manifest-resolution, static validator, documentation, manifest, and CI updates.

## Validation policy

The embedded Devtool validator runs the Phase 37 static validator plus focused Gradle tasks for `browser-integration`, `core-model`, app unit contracts, and Android-test assembly. It does not run the full release gate reserved for Phase 42. Any failure rolls the overlay back atomically.

## Apply

Use Devtool with `--validate`. The schema-v2 artifact owns its commit message; do not add a CLI `--commit` flag.

# XDM Android Phase 38 repository-owned Firefox extension overlay

## Included

- A WebKit-free `:browser-extension` Kotlin/JVM source and validation module.
- Stable XDM-owned extension identity `xdm-android-media-bridge@mikeyphw`.
- The layered v6.4 detector split into detector, candidate-store, network, page, frame, handoff, and launcher sources.
- XDM, 1DM+, and Ask target modes, with XDM as the default.
- Phase 37 version-1 `xdmdownload://capture` URI construction with no standalone cookies, authorization, proxy credentials, bodies, or raw headers.
- Unpacked development rendering, source validation, Node behavior tests, Kotlin contracts, docs, project manifest, and CI wiring.
- No generated XPI, Android browser runtime, top-level route, Room migration, app version change, or transfer-engine change.

## Validation policy

The embedded validator runs Phase 37 and Phase 38 static contracts, JavaScript syntax checks, detector/handoff/background behavior tests, unpacked-extension preparation and validation, then the focused Gradle module suite. The Phase 42 release overlay remains responsible for the full Android release matrix.

# XDM Android Phase 39 deterministic XPI generation and SAF export overlay

## Included

- One Kotlin generator, validator, filename contract, and SHA-256 implementation shared by Gradle and the Android runtime.
- Sorted, timestamp-normalized, traversal-safe Firefox XPI archives with exact inventory validation.
- Dark, AMOLED, and default package tasks under `browser-extension/build/outputs/xpi/`.
- A Settings subpanel using `OpenDocumentTree`, persisted URI permission, default target and theme selection, generation status, and last verified export metadata.
- Cache staging, SAF `.part` verification, provider rename and backup promotion, no-rename local snapshot recovery, byte-count verification, checksum verification, and partial cleanup.
- Focused JVM, Android contract, JavaScript, archive, workflow, documentation, and Devtool validation.
- No top-level route, browser runtime, Room migration, transfer-engine change, app version change, or custom-scheme payload expansion.

## Validation policy

The embedded validator runs the Phase 37 through Phase 39 static contracts, JavaScript syntax and behavior tests, unpacked-extension rendering, deterministic Dark and AMOLED XPI generation, shared generator tests, Android export transaction tests, app browser-integration contracts, and archive integrity checks. The Phase 42 release overlay remains responsible for the complete Android release matrix.

## Apply

Use Devtool with `--validate`. The schema-v2 artifact owns its commit message; do not add a CLI `--commit` flag.


## Phase 41 browser bridge integration

Apply `xdm_android_phase41_browser_bridge_integration_overlay.zip` after Phase 40. The artifact owns the commit message `Add browser bridge settings diagnostics and recovery` and runs the focused Phase 37–41 validators plus browser-extension and Android unit tests.
