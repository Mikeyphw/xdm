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
