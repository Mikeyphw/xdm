# XDM Android master remediation Overlay 08+09 v3: browser secure runtime + media capture import

Target: `xdm_android`.

This intermediate schema-v2 artifact is based on applied commit `ecbdda2c` and combines the next two dependency-ordered remediation phases:

1. browser extension secure runtime
2. Android media capture identity / durable encrypted import

## Manifest-controlled defaults

- `target`: `xdm_android`
- retained final validation tasks:
  - `:app:compileDebugKotlin`
  - `:app:testDebugUnitTest`
  - `:app:lintDebug`
- `validation.allow_deferred`: `true`
- `apply.commit.enabled`: `true`
- `apply.commit.strategy`: `single`
- `apply.commit.message`: `Apply XDM Android secure browser runtime and media capture import`

Campaign validation is intentionally deferred. Apply this intermediate overlay with `--no-validate`; the declared tasks remain the final-campaign validation contract.

### v3 promise-audit corrections

- removes the remaining production Debug Workbench plaintext `capture?v=1` URI generator while retaining the separate non-sensitive `add?v=1` compatibility probe;
- requires capture key/SPKI/OAEP inputs for **every** release package target (`xdm`, `ask`, or `1dm`), with keyless rendering restricted to debug;
- scans interrupted `.bak`, `.new`, `.tmp`, and `.tmp-*` app-private persistence artifacts in the bounded privacy audit;
- updates the repo-owned Phase 38 validator and browser documentation so they enforce encrypted-v2 outer fields instead of the superseded plaintext URL parameter.

## Phase 08 — browser extension secure runtime

- correlates untrusted page-world media hints to privileged extension-owned `webRequest` evidence instead of accepting page `postMessage` data as request authority;
- ignores page-supplied credential headers and derives page/frame provenance from extension/browser context;
- preserves exact signed-query request identity and adds a per-request fingerprint so distinct authenticated requests cannot merge candidate/header state;
- disables plaintext XDM capture-v1 generation and all XDM plaintext fallback paths;
- requires encrypted-v2 handoff before the launcher can offer XDM;
- carries request fingerprint inside encrypted capture sessions;
- updates Firefox release metadata to Android app `0.21.0`;
- makes every release packaging entry point key-bound: Gradle release XPI tasks, the Kotlin CLI/shared build config, direct Python release packaging for every default target, and release artifact verification require Android capture key ID/public key plus an explicit OAEP hash; each path verifies the key ID is `SHA-256(SPKI DER).take(24)`, and the encrypted runtime never guesses a missing OAEP hash;
- exposes an explicit `browserExtensionReleaseGate` while keeping ordinary unpacked development checks keyless/debug.

## Phase 09 — media capture identity / durable encrypted import

- keeps encrypted capture-v2 intact through the exported review boundary and refuses to flatten it into a legacy automation draft;
- writes a ciphertext-only app-private import journal through Android `AtomicFile` before decrypt/import work, persists no observed caller/package label, and rejects conflicting reuse of a capture session ID;
- forwards only an internal session ID to MainActivity and consumes it once;
- binds Android capture identity to exact request URL + browser session + request fingerprint; browser-declared stable IDs are not authoritative;
- commits sanitized Room capture/variant state before exact secure handoff sidecars, then records the non-secret browser-session index last; recovery is serialized, stale revisions cannot replace newer durable sessions, and interrupted imports remain journaled until missing sidecars/index entries are repaired; durable automation captures use their encrypted command envelope as retry authority and are also Room-first;
- preserves already-linked captures/variant identity, fills only missing sidecars during partial-import recovery, and explicitly replaces empty variant sets for non-linked imports; browser session/index file replacement is non-destructive and atomic where supported; the legacy direct capture API fails closed for sensitive headers/signed-query context because it lacks a durable encrypted outer journal;
- adds real bounded filesystem privacy scanning over app-private media/browser persistence surfaces, including interrupted `.bak`, `.new`, and `.tmp-*` replacement artifacts, without decrypting secure envelopes;
- makes Media3 diagnostics classify from structured `PlaybackException` error codes/cause class rather than message substring heuristics;
- treats observed Android caller package as diagnostic metadata only and does not claim that Firefox/browser identity was cryptographically authenticated by the envelope.

## Apply

```bash
devtool -r "$HOME/Code/xdm" --yes apply-overlay \
  "/sdcard/Download/xdm_android_browser_secure_runtime_media_capture_import_overlay_v3.zip" \
  --no-validate
```

Do not start the next remediation phase until this artifact applies cleanly. Campaign validation remains deferred until the final overlay.
