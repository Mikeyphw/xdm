# XGO-51+52 implementation report — media identity and graph convergence

## Scope

This overlay implements XGO-51 and XGO-52 for `xgo_media`.

## Merge-window decision

The evaluated window was XGO-51 + XGO-52 + XGO-53. XGO-51 and XGO-52 are merged because media graph convergence depends on the canonical media resource identity and credential-scope model introduced by XGO-51. XGO-53 remains separate because deterministic variant/track selection is a policy layer over the graph rather than the graph authority itself.

## Delivered behavior

- Separates transport URL, canonical resource identity, signed-token policy, origin and credential scope.
- Canonicalizes query ordering without blindly dropping unknown query parameters.
- Treats common signed-token parameters as transport-only by default, while preserving explicit identity-relevant policy.
- Re-evaluates credential forwarding for every child resource by origin, path, resource kind and explicit allowlist.
- Adds a bounded logical media graph with item/source/variant/track/rendition/manifest/fragment-set/protection entities.
- Merges repeated observations and signed URL rotations into stable graph entities with provenance.
- Enforces graph growth limits.

## Audit-loop fixes

The first audit pass exposed capability-ledger drift from earlier merged overlay names (`XGO-49+50`) and from the new `XGO-51+52` target labels. The ledger now uses individual roadmap IDs (`XGO-49`, `XGO-50`, `XGO-51`, `XGO-52`) so the shared ledger audit is clean.

The fixture secret scan also rejected realistic signed-query values. The fixtures now use placeholder query values while the Go test matrix still exercises actual signed-token behavior.

## Validation evidence

- `go test ./engine/media ./engine/cmd/xgo-media-audit`
- `go run ./engine/cmd/xgo-media-audit --mode capture_corpus`
- `go run ./engine/cmd/xgo-media-audit --mode capture_generation`
- `go run ./engine/cmd/xgo-media-audit --mode media_identity`
- `go run ./engine/cmd/xgo-media-audit --mode media_graph`
- capability ledger audit
- fixture lint
- fixture secret scan
- `xgo_media` topology audit
- `go test ./engine/...`
- `go vet ./engine/...`
