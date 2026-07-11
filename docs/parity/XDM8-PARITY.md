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

The manifest inventories 50 feature contracts across the download engine,
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

## Overlay 13 result

`download.segmented-transfer` is now complete. Fresh eligible downloads probe
range support, split the remote object into bounded non-overlapping ranges,
resume each segment from its own durable file, merge in deterministic order,
and fall back to the established single-stream path when the server ignores the
probe range.

## Overlay 14 result

Browser takeover parity is complete. The Firefox and Chromium-family extensions
now use the versioned native-message protocol, forward bounded request metadata,
apply shared capture rules, expose manual and download-all context commands, and
cancel the browser transfer only after the modern app queues it. Native-host
installation now supports compatibility inspection, repair and uninstall.
Security and deterministic fixture coverage are documented in
`docs/parity/BROWSER-TAKEOVER.md`.
