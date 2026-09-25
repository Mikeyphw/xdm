# XGO-04 + XGO-05 implementation report

This overlay establishes the first production Go code in the unified XDM
engine campaign.

Key implementation choices:

- A root `go.work` keeps Devtool's target root at the repository root while
  the module remains under `engine/`. This preserves existing target-local
  Python job paths and lets GoRunner expand `./...` across workspace modules.
- Go 1.23 is the minimum language baseline; no third-party dependency is
  introduced.
- `clock.Source` exposes civil and monotonic time separately.
- `idgen.Generator` makes entropy injectable, with deterministic test
  counters and a crypto/rand production implementation.
- Entity IDs use stable prefixes and 128-bit lowercase hexadecimal tokens.
- Resource identities are SHA-256-derived opaque keys; sensitive values use
  a redacting wrapper for ordinary string/JSON presentation.
- Native Go validation is authoritative. A single target-local formatting
  job supplements Devtool's restore/build/test/vet phases.
