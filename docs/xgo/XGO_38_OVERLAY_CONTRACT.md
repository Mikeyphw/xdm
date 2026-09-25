# XGO-38 Overlay Contract

- Target: `xgo_backends`
- Validation required: yes
- Failure action: `pause`
- Deferred validation: no
- `validation.tasks`: absent; `xgo_backends#validate` remains authoritative.
- Devtool installation/refresh: forbidden.

## Authority boundaries

- Metalink is metadata/intention expansion only; it is not a transfer backend and owns no attempt/checkpoint/publication state.
- Parsed sources become the ordinary canonical `NetworkIntent` transport URL plus mirror list.
- Generated Metalink transfers are bodyless `GET` requests; request-body execution remains XGO-36 authority.
- Expected size and whole-artifact checksums are merged into canonical request integrity metadata and fail closed on conflict.
- Supported Metalink whole-file hash algorithms are exactly the algorithms admitted by `transfer/checksum`.
- Source priority/preference hints influence deterministic source ordering; canonical URL normalization and duplicate suppression remain `NetworkIntent` authority.
- Metalink file names are metadata, not direct filesystem authority, and reject absolute/traversal/Windows-drive forms before later host publication can observe them.
- Multi-file documents derive distinct logical resource identities for each expanded file.

## Deferred work

Exact backend compatibility and deterministic selection remain XGO-39/40. aria2 work remains XGO-41..43. GATE-05 remains open.
