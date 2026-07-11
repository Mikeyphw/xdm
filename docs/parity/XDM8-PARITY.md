# XDM 8 functional parity ledger

This ledger is executable through `XDM.Parity.Tests`. The source of truth is
`docs/parity/features.json`; this document explains how to maintain it.

## Status rules

- `complete`: implementation paths and automated tests are mandatory.
- `partial`: a useful slice exists, but legacy behavior is still missing.
- `missing`: no modern implementation is available.
- `intentionallyReplaced`: modern behavior supersedes the legacy feature.
- `notApplicable`: the legacy behavior is no longer relevant.

## Current baseline

The manifest inventories 47 feature contracts across the download engine,
browser integration, media, conversion, queues, settings, desktop integration,
diagnostics, localization, packaging and migration.

Overlay 13 owns the first critical gap: `download.segmented-transfer`. Later
parity overlays must update the status, implementation paths and tests in the
same commit that supplies the behavior.

## Gate policy

The test project enforces:

1. Unique stable feature IDs.
2. A named legacy/public-contract source for every feature.
3. An assigned target overlay for all work.
4. Implementation and test references for every feature marked complete.

The final parity gate will additionally require every critical and high feature
to be complete, intentionally replaced, or explicitly not applicable.
