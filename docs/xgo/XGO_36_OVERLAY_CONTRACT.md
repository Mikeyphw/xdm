# XGO-36 Overlay Contract

- Target: `xgo_backends`
- Validation required: yes
- Failure action: `pause`
- Deferred validation: no
- `validation.tasks`: absent; `xgo_backends#validate` is authoritative.
- Devtool installation/refresh: forbidden; this artifact validates against the already-installed Devtool.

## Authority boundaries

- Canonical `NetworkIntent` remains the only request-semantics model.
- Request-body source kinds are explicit: immutable bytes, immutable file, secret reference, and one-shot.
- Immutable bytes/file/secret-reference bodies are replayable through opaque runtime providers.
- One-shot body execution is explicitly unsupported for durable downloads.
- Request replayability does not imply byte-range resumability.
- Replayable POST retries restart from response byte zero; a non-zero POST transfer offset is rejected before I/O.
- Only bodyless GET may use transfer-owned `Range`/`If-Range` resume headers.
- Redirect semantics are delegated to the existing canonical security redirect state machine.
- Runtime body/credential material and opaque body/credential refs are absent from safe diagnostics.

## Deferred work

FTP/FTPS remains XGO-37. Metalink expansion remains XGO-38. Exact backend compatibility and deterministic selection remain XGO-39/40. aria2 work remains XGO-41..43. GATE-05 remains open.
