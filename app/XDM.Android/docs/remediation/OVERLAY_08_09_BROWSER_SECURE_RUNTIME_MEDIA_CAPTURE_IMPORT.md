# Master remediation Overlay 08+09 v3 — browser secure runtime + media capture import

Base: applied commit `ecbdda2c`.

This intermediate overlay combines the dependency-ordered browser runtime and Android media-capture import phases. Campaign validation remains deferred; the artifact is applied with `--no-validate` and retains the final validation task contract in its schema-v2 manifest.

The v3 promise audit closes additional gaps found after v2: the Debug Workbench no longer generates a plaintext capture-v1 URI, release packaging is key-bound regardless of default target, crash-leftover persistence files participate in the real-filesystem privacy scan, and the historical Phase 38 validator/docs now describe the encrypted-v2 outer envelope.

## Phase 08 — browser extension secure runtime

- Treat page/main-world observations as untrusted hints only. They may enrich a candidate only when correlated to recent extension-owned `webRequest` evidence for the same request URL/frame.
- Do not trust page-supplied request headers, page/referrer attribution, or a public `window.postMessage` marker as request authority.
- Preserve signed query parameters in request identity and add a request fingerprint containing browser request/frame/generation context so repeated authenticated requests for the same URL cannot merge credentials.
- Disable plaintext XDM capture-v1 generation and legacy top-frame fallback. XDM launch is unavailable unless an encrypted-v2 link was produced.
- Carry request fingerprint inside the encrypted capture payload.
- Bind every release Firefox package to the Android capture public key **and its explicit OAEP hash**, even when `ask` or `1dm` is the default target. Gradle release tasks, the Kotlin CLI/shared build config, the direct Python packager, and release artifact verification verify that key ID equals `SHA-256(SPKI DER).take(24)`. Only keyless debug rendering may default the OAEP hash; encrypted runtime/release paths never guess it.
- Keep normal unpacked source/development checks keyless through debug/Ask mode. Use the explicit `browserExtensionReleaseGate` for key-bound release packaging.
- Bind release metadata to Android app version `0.21.0` rather than the historic release-candidate version.

## Phase 09 — media capture identity and import

- Preserve encrypted-v2 capture envelopes across the exported review boundary; do not flatten ciphertext into a legacy automation draft.
- Journal the encrypted envelope in app-private storage before decrypt/import work using Android `AtomicFile` (`startWrite`/`finishWrite`/`failWrite`). The journal stores ciphertext/key-envelope fields plus receipt time only, persists no caller/package label, and rejects conflicting reuse of a session ID.
- MainActivity receives only an internal pending-session ID and consumes it once on a fresh launch or `onNewIntent`.
- Compute Android capture identity from exact request URL plus session and request fingerprint. Browser-declared stable IDs are compatibility diagnostics, not authority.
- Commit sanitized Room capture/variant rows before auxiliary exact secure request handoffs; commit the non-secret browser-session registry last. Recovery is mutex-serialized; stale lower revisions cannot replace newer durable sessions; missing sidecars are repaired after partial commits, and the encrypted journal remains until all auxiliary state is durable. Durable automation CaptureMedia uses its encrypted command envelope as retry authority and is also Room-first.
- Preserve captures already linked to a download rather than allowing a later browser observation to rewrite their exact request/variant identity; on retry, preserve sidecars that already exist but fill any missing capture/variant/session sidecars. Browser session/index file replacement is non-destructive and atomic where supported. The legacy direct capture API refuses sensitive headers/signed-query context because it has no encrypted outer journal, and its non-sensitive compatibility path is Room-first.
- Explicitly replace a capture's variant set, including an empty set, for non-linked retries.
- Extend the privacy audit from model/string checks to bounded inspection of the real app-private request-envelope, import-journal, session-index and queue-recovery surfaces, including interrupted `.bak`, `.new`, and `.tmp-*` replacement artifacts, without decrypting secure envelopes.
- Classify Media3 playback failures from structured `PlaybackException` error codes/cause class rather than free-text substring heuristics; message text remains redacted human detail only.
- Treat observed Android caller package as diagnostics only; encrypted review/import UI never claims that Firefox (or any browser) cryptographically sent the handoff.

## Deferred validation contract

The artifact declares but does not run:

- `:app:compileDebugKotlin`
- `:app:testDebugUnitTest`
- `:app:lintDebug`

Apply with:

```bash
devtool -r "$HOME/Code/xdm" --yes apply-overlay \
  "/sdcard/Download/xdm_android_browser_secure_runtime_media_capture_import_overlay_v3.zip" \
  --no-validate
```
