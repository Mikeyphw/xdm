# XDM Runtime Foundation Phases 59–61

Baseline: landed XDM runtime foundation through Phase 58 r3 (`8303554`).

## Phase 59 — first-class browser capture sessions

- Added a non-secret `BrowserCaptureSessionSummary` / `BrowserCaptureCandidateSummary` model.
- Added `BrowserCaptureSessionRegistry`, a durable app-private index that stores only grouping metadata: session id, revision, page title/host, candidate ids, quality/evidence labels, counts, and truncation state.
- The registry deliberately does not persist raw media URLs, cookies, authorization headers, or request header bags. The legacy browser-handoff coordinator is process-local; durable exact URL/header execution context is promoted only into the existing Android-Keystore-backed `MediaRequestHandoffStore`.
- Imported Firefox captures are grouped in the Media inbox under **Firefox capture sessions**.
- Removing a media capture removes it from its browser session group; empty groups are deleted.

## Phase 60 — secure Firefox to Android capture handoff

- Bumped the browser bridge contract to v2 while retaining v1 legacy URL handoffs.
- Added an encrypted capture-envelope handoff with only these visible custom-scheme fields: `v`, `sid`, `kid`, `ek`, `iv`, and `ct`.
- Added `BrowserCaptureEnvelopeManager`, which owns an AndroidKeyStore RSA key pair. The generated Firefox XPI receives only the public key and key id.
- The Firefox extension wraps a per-handoff AES-256 key using RSA-OAEP/SHA-256 and encrypts the candidate set with AES-GCM.
- The encrypted payload carries exact URLs and allowlisted browser request headers into the protected Android runtime path without placing cookies, Authorization values, or signed media URLs in the plaintext URI.
- Stale app keys, expired capture sessions, oversized envelopes, malformed envelope parameters, unusable candidate sets, and replayed session revisions are rejected or ignored with visible Phase 58 intake feedback.

## Phase 61 — captured media inbox

- The Firefox extension now exports a bounded candidate snapshot instead of only `candidateStore.best()`. Same-best-URL dedupe refreshes the handoff whenever candidate count or capture revision changes, so late audio/subtitle/alternate candidates are not frozen out.
- The background observer assigns a stable capture session id/revision per tab and sends up to 24 ranked candidates in the encrypted envelope.
- Android imports all usable candidates, creates normal review records/variants, remembers secure request headers with `MediaRequestHandoffStore`, and groups the records by browser session.
- The Media inbox displays total browser observations, imported reviewable candidates, evidence labels, and a truncation warning if the page exposed more candidates than the bounded handoff could carry.
- The old best-candidate flow remains a fallback when the generated extension has no app public key or WebCrypto is unavailable.

## Guardrails retained

- No DRM bypass was added.
- No new Room migration; schema remains 17.
- No new top-level app route; the Media inbox is reused.
- SAF/MediaStore/direct-storage work from Phases 55–58 is preserved.
- Plaintext custom-scheme URLs still reject raw headers and unsupported URI credentials.

## Validation performed in this environment

- Firefox extension JavaScript tests passed:
  - `test_background.js`
  - `test_detector.js`
  - `test_fab.js`
  - `test_handoff.js`
  - `test_phase43a_bridge.js`
  - `test_release_gate.js`
  - `test_secure_handoff.js`
- Source contract coverage added in `BrowserCapturePhases59_61ContractTest`.
- `BrowserCaptureSessionRegistryTest` verifies that only non-secret grouping metadata is persisted.
- Repository validator `tools/validate-runtime-foundation-phase59-61.py` passes and is called by the embedded artifact validator.
- Clean-apply, inventory, and embedded validator checks are included in the overlay.

Full Android compile/lint/unit validation remains the Devtool boundary.


## Promise-closure audit r2

The first Phase 59-61 artifact correctly added encrypted Firefox capture-session import, but a deeper promise audit found that encrypted capture imports still persisted exact signed candidate and variant URLs in ordinary Room rows after decryption. r2 keeps exact URL and session context in MediaRequestHandoffStore capture/variant entries, while durable MediaCaptureRecord and MediaVariant rows now use ExternalUrlPolicy.persistableUrl(...) or a queryless fallback. The Media Inbox remains grouped and reviewable, but exact temporary URLs, signed query values, Cookie and Authorization material stay outside ordinary UI and Room rows.

## r3 navigation/compile closure

r3 supersedes r2 by resolving the Kotlin/JVM platform-signature clash in `MainViewModel`: the nullable and non-null URL redaction helpers were split into `persistableBrowserCaptureUrl(String)` and `persistableBrowserCaptureUrlOrNull(String?)`, preserving durable-row redaction while making `:app:compileDebugKotlin` compile-safe.
