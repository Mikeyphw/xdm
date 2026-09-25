# XGO-36 Implementation Report

## Delivered

- Activated `xgo_backends` on Devtool's native Go runner.
- Added explicit body source kinds to canonical `NetworkIntent`: immutable bytes, immutable file, secret reference, and one-shot.
- Preserved compatibility with pre-XGO-36 persisted body intents by normalizing the old replayability-only shape.
- Added whole-request replayability and separate Range-resume eligibility helpers.
- Added `backends/http.Factory`, which resolves opaque runtime body and credential material without creating a parallel request model.
- Connected the factory to the existing checkpointed HTTP executor through a narrow request-factory hook; Range/If-Range ownership remains in the transfer layer.
- Replayable bodies expose `GetBody` re-openers. One-shot bodies are rejected for durable execution.
- POST with a non-zero response offset is rejected before I/O, preventing unsafe append/resume semantics.
- Added redirect projection through the existing security redirect state machine; 303 drops POST body and 307 preserves only replayable body semantics.
- Added safe diagnostics that exclude query strings, body refs/body bytes, credential refs, and resolved credential material. Provider failures are intentionally collapsed to opaque safe errors so storage paths or secret values cannot leak through error text.
- Preserved Desktop-compatible successful POST response handling: non-partial 2xx responses are accepted while unsolicited 206 remains a range contradiction.
- Closed `XGO-CAP-REQUEST-002` and upgraded its fixture to a detailed eight-case contract.

## Validation topology

`xgo_backends#validate` now runs:

1. native Go validate (`go test`, build target, vet),
2. `backend_contract_audit`,
3. `post_replay_lab`.

The specialized lab covers body-source kinds, POST download, replayable retry, one-shot retry refusal, redirects, credentialed POST, POST Range-resume rejection, and safe-log redaction.

## Scope decision

The initial Wave-5 merge window was XGO-36..38. XGO-36 remains standalone because POST/body replayability changes canonical request execution and retry/resume safety, while XGO-37 introduces FTP/FTPS protocol/authentication behavior and XGO-38 introduces Metalink parsing/intent expansion. Keeping them separate preserves fault isolation without reducing Wave-5 scope.

## Audit-loop evidence

The final local audit loop completed cleanly after closing two review findings (provider-error leakage and POST 2xx compatibility):

- focused backend/request/redirect/transfer/retry tests: pass,
- shared capability-ledger audit: pass (96 capabilities),
- shared fixture audit: pass (96 fixtures; 48 detailed),
- `xgo_backends` topology audit: pass with three authoritative validate nodes,
- scoped `go vet`: pass,
- `xgo-backends-audit` build: pass,
- `post_replay_lab`: 8/8 pass,
- full `go test ./engine/...`: pass.

The packaging environment does not provide a `devtool` executable, so actual Devtool transaction execution is intentionally left to artifact apply. The artifact itself requires `xgo_backends#validate`; no validation task override or Devtool reinstall/refresh hook is included.
