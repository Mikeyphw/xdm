# XDM Media Parity01 — Runtime Truth, Diagnostic Integrity and Backend Reliability

Artifact: `xdm_media_parity01_runtime_truth_diagnostics_backend_reliability_v2.zip`

Baseline: post-v5r1 XDM Android, Room schema 22.

Status: implemented as the first intermediate overlay of the four-overlay Media Parity V2 campaign. This overlay intentionally does not change the Room schema and is intended to be applied with `--no-validate`; the final Parity04 overlay owns the exhaustive Android/device/release validation matrix.

## Why this overlay exists

The supplied `diagnostics/Xdm.zip` proved that several old diagnostics could report success without proving the artifact or runtime they described:

- `debug-events.jsonl` contained a truncated/malformed record;
- signed media query values such as `md5` and `sess` survived into the finished export even though the privacy test reported success;
- Firefox encrypted-envelope tests failed even though current product requirements use the direct keyless v3 handoff and do not require encrypted Firefox handoff;
- aria2 could be reported as packaged/ready without proving launch, authenticated RPC, a real local transfer, lifecycle controls and shutdown;
- support reports described the product as downloader-only / built-in browser absent even though Live Locator ships a constrained WebView;
- Developer/release truth still carried stale schema and shared proxy booleans for unrelated checks.

Parity01 turns those observations into executable contracts.

## Delivered scope

### 1. Whole-record JSONL integrity

`RollingJsonlDebugEventRecorder` no longer character-truncates exported JSON records. Timeline copies select complete newline-delimited records only. Final diagnostic scanning uses a dependency-free JSON grammar parser and rejects malformed or truncated records, including balanced-but-invalid objects.

### 2. Signed-media redaction coverage

The shared redaction policy now covers the credential/query families observed in the supplied evidence and common equivalents, including `md5`, `sess`, session aliases, token/signature/key aliases, `hdnea`, `hdnts`, AWS/Google signed-query credentials, sensitive authorization/cookie headers and structured nested diagnostic fields.

### 3. Exact-final-ZIP privacy/integrity boundary

`DiagnosticExportIntegrity` is the final share boundary. Exports are preflight-scanned, written deterministically, then the exact ZIP bytes are reopened and scanned before the file is considered shareable. Unsafe exports are deleted and sharing fails closed.

The scanner verifies:

- bounded/safe ZIP entry paths and sizes;
- no duplicate entry names;
- no ambiguous `(1)`-style export filename;
- strict JSONL record grammar;
- unredacted sensitive query/header/structured values;
- authorization token patterns;
- a generated deterministic manifest whose SHA-256/byte inventory matches every payload entry.

### 4. Deterministic diagnostic manifest

Every verified Debug Center ZIP contains `diagnostic-manifest.json` with diagnostics schema/version, app/build identity, Room schema, run ID, test summary, SHA-256 inventory and the final scanner version/result. `bundle-readme.txt` documents the roles of bundle entries and the no-auto-upload rule.

### 5. Privacy self-test uses the production exporter

The Debug Center `Final ZIP privacy & integrity` test creates an export through `DebugTestStore`, rescans that same final artifact and exercises the production final-artifact contract. It no longer certifies privacy only from a synthetic string-level smoke check.

### 6. Current product topology truth

Operational/support/Settings truth now states the shipped topology: XDM is a download manager with constrained Live Locator WebView capture plus external browser/Firefox handoff. Historical browser-free Phase-7 assertions remain historical evidence rather than current release blockers.

### 7. Room schema truth

Current Developer/release surfaces report Room schema 22. Active release-security/readiness/final-RC wording no longer carries stale schema-21 truth.

### 8. Independent validation evidence

BuildConfig and release truth distinguish static validation, full validation, real-device smoke, aria2 payload verification, diagnostic export verification, release docs, route topology, lint and native-symbol evidence. Independent facts no longer silently inherit one `staticPassed` boolean.

### 9. Current Firefox contract replaces obsolete crypto blockers

Required Debug Center tests no longer include the RSA key-wrap or encrypted-v2 envelope decoder as current release blockers. They are replaced by a direct-v3 deep-link handoff test using the production parser. Legacy encrypted parsing remains compatibility-only for older installed extension payloads.

### 10. aria2 lifecycle truth

The Debug Center aria2 test now executes the real lifecycle smoke rather than treating a packaged capability row as health. The smoke path covers launch, authenticated loopback RPC, unauthenticated rejection, local loopback transfer, pause/resume/status, session save, result cleanup and shutdown.

If the optional packaged runtime is absent it is a warning with repair guidance and does not poison Native backend truth. If a packaged runtime fails lifecycle, it is a failure and includes the redacted runtime state/failure kind/exit code/log tail when available.

A dedicated regression reproduces the supplied `ProcessExited` / exit-code-1-before-RPC failure and asserts that it can never be reported as Ready.

Backend capability routing is runtime-aware as well: after a managed launch/RPC failure, aria2 stops advertising protocols and new prepare/reconcile work sees it as unavailable until Repair/retest restores health. The package probe remains separately available so Debug Center can still diagnose and repair the optional backend.

### 11. One runtime-truth hierarchy

`Diagnostics & support` is the authoritative runnable health/export surface for normal support use. Developer Center presents advanced inspection and mirrors independent validation evidence without creating a second synthetic certification model. Full visual consolidation remains reserved for Parity04.

### 12. Xdm.zip-derived regressions

Regression coverage explicitly includes:

- malformed/truncated JSONL;
- nested signed URLs with `md5`, `sess`, token/signature/session variants;
- planted secret refusal;
- strict JSON grammar beyond brace balancing;
- final manifest metadata/hash verification;
- ambiguous duplicate-style export names;
- direct-v3 Firefox qualification and absence of obsolete crypto blockers;
- schema/topology truth;
- independent validation booleans;
- aria2 real lifecycle and the exact early-process-exit failure class.

## Validation hooks

The canonical static gate now carries `tools/validate-media-parity01-runtime-truth.py`. The signed release-gate path emits the new independent evidence properties so later overlays and Parity04 can prove each gate independently.

Key unit/contract suites added or extended:

- `DiagnosticExportIntegrityTest`
- `DebugWorkbenchShellModelsTest`
- `Aria2ProcessManagerTest`
- `MediaParity01RuntimeTruthContractTest`
- existing final-RC/release-security/readiness tests updated for current truth

## Deferred by design

Parity01 does **not** implement the logical media graph, Firefox chooser, native HLS execution, browser-shell redesign or userscripts. Those remain Parity02–04 scope. It also does not remove compatibility parsing for legacy encrypted Firefox payloads; it only removes that old mechanism from the current required blocker set.

No Room migration is introduced in Parity01.
