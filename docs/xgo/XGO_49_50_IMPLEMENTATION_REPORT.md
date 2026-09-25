# XGO-49+50 implementation report

## Scope

This overlay starts Wave 7 media authority by merging XGO-49 and XGO-50. XGO-49 defines the canonical versioned `CaptureEnvelope`; XGO-50 adds document/session generation fencing so late browser events remain observable but cannot mutate current media state.

XGO-51 remains separate because canonical media resource identity and credential inheritance start the logical media identity boundary after capture evidence is normalized.

## Implemented invariants

- Android and Desktop browser handoffs normalize to one bounded `CaptureEnvelope`.
- Replay-relevant request evidence is preserved separately from logical observation identity.
- POST captures carry a body reference, never raw body bytes.
- Secret-bearing headers are rejected as raw capture fields and must be represented by references.
- Safe diagnostics expose header names and body-reference presence only.
- Stale document generations are retained as historical evidence but cannot mutate active media graph state.
- Same URL reloads use a new document generation and fence older events.

## Validation

- `go test ./engine/media ./engine/cmd/xgo-media-audit`
- `go vet ./engine/media ./engine/cmd/xgo-media-audit`
- `go run ./engine/cmd/xgo-media-audit --mode capture_corpus`
- `go run ./engine/cmd/xgo-media-audit --mode capture_generation`
- full `go test ./engine/...`
- full `go vet ./engine/...`
