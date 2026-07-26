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
