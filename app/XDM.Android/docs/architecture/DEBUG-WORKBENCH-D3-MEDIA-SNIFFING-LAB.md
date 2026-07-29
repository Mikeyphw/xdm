# Debug Workbench D3: Media Sniffing Lab

D3 adds **Settings → Debug Workbench → Media Sniffing Lab**, a support-facing diagnostic card for the shared Phase 47 app-side media sniffer.

## Scope

The lab lets a user paste a URL, HTML, JSON, or script snippet, optionally provide a base page URL and MIME hint, choose the origin label, and run the shared `MediaSniffingEngine` in static mode.

## Safety boundary

D3 is intentionally diagnostic-only:

- static sniff only
- no network page probe
- no arbitrary JavaScript execution
- no DRM bypass
- no download enqueue
- no automatic upload
- sanitized copy report only

The lab uses `PrivacyDiagnosticsRedactor` before showing copied URLs or diagnostics. Signed query values such as `token`, `sig`, `signature`, and `session` are redacted in the support report.

## User-visible output

The card shows:

- candidate count
- primary candidate label
- candidate kind, rank, reason, and redacted URL
- short diagnostics
- copy sanitized lab report action

The full raw candidate URLs remain inside the sniffing plan only. The copied report uses redacted URLs and redacted diagnostics.

## Relationship to Phase 47

D3 does not add a new sniffer. It wraps the existing Phase 47 `MediaSniffingEngine` so support can explain why HLS, DASH, progressive media, or noisy fragments were accepted or rejected.

## Relationship to D2

D3 extends the D2 Settings shell in place. It does not add a top-level route, database migration, network worker, or support-bundle upload path.

## Validation

Required checks:

- `MediaSniffingLabTest`
- `DebugWorkbenchD3MediaSniffingLabContractTest`
- `tools/validate-debug-workbench-d3-media-sniffing-lab.py`

The validator checks that the lab is reachable from the D2 shell, uses the shared `MediaSniffingEngine`, preserves static boundaries, redacts copied diagnostics, and records the D4 handoff.


## r3 UI seal correction

The lab renders user-facing source labels such as Manual page and Browser extension instead of raw enum names. This keeps the Debug Workbench within the normal UI release-seal boundary while still storing enum values internally for state restoration.


## r4 compile and UI seal correction

The lab uses private stable source keys for saveable state and human-facing labels for display. It does not render enum `.name` values in the UI. The D3 contract test also asserts this without embedding broken nested Kotlin string literals.
