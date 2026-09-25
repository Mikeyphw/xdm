# XGO-58+59 Overlay Contract

Target: `xgo_media`

Validation is required and non-deferred. The overlay adds target-local jobs only; it does not alter tool bootstrap, Devtool installation, or host package setup.

## Specialized jobs

- `dash_corpus`
- `dash_execution_lab`

## Capability ledger updates

- `XGO-CAP-DASH-001`: IMPLEMENTED by XGO-58.
- `XGO-CAP-DASH-002`: IMPLEMENTED by XGO-58.
- `XGO-CAP-DASH-003`: IMPLEMENTED by XGO-59.
- `XGO-CAP-DASH-004`: IMPLEMENTED by XGO-59.

## Merge-window decision

XGO-58 and XGO-59 are merged because parser/timeline expansion and dynamic execution are one coherent DASH fragment-ledger boundary. XGO-60 remains separate because FFmpeg planning starts a typed post-processing/tool-execution boundary.
