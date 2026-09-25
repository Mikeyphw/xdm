# XGO-57 Overlay Contract

## Target

- Devtool target: `xgo_media`
- Validation required: yes
- Failure action: pause
- Validation task override: none

## New validation node

`hls_execution_lab` is added to `xgo_media#validate` after `hls_timeline_lab`.

The node verifies:

- VOD execution.
- AES-128 decrypt.
- byte range execution.
- init map fetch.
- restart recovery with committed fragment skip.
- live sliding-window polling and duplicate suppression.
- bounded user stop.
- typed unsupported-protection outcome.
- typed key/fetch/decrypt outcomes.

## Capability closure

- `XGO-CAP-HLS-006` is now implemented by XGO-57.
- `XGO-CAP-HLS-007` is now implemented by XGO-57.
