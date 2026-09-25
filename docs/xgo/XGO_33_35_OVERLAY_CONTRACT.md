# XGO-33..35 overlay contract

Target: `xgo_gate_transfer`

Validation is required, `failure_action = pause`, `allow_deferred = false`, and
`validation.tasks` is intentionally absent so the authoritative workflow DAG is
not replaced.

## Promises

- `running` transport does not become `produced_artifact` merely because network
  bytes ended; it becomes durable `transport_complete`.
- Verification is bounded, cancellation-aware, algorithm-explicit and journaled.
- Checksum failure cannot create or publish an ArtifactGeneration.
- Repair invalidates only damaged checkpoint evidence after representation
  compatibility is established.
- Repair re-fetches exact damaged ranges and preserves good committed bytes.
- Finalization freezes staging writes before whole-artifact verification.
- Verified artifact creation and attempt promotion are atomic in SQLite.
- Publication requires the verified current artifact and a `produced_artifact`
  source attempt.
- Crash between artifact creation and publication remains recoverable.
- Gate-04 composes foundation, durable store, security and transfer workflows.
- Inherited store verification/publication audits must use the same
  `transport_complete -> produced_artifact` boundary as production finalization.
- Native Android/arm64 validation never invokes unsupported `go test -race`;
  supported-host race evidence is tracked separately.
