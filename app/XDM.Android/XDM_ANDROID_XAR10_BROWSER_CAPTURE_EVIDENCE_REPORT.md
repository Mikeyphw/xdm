# XDM Android XAR10 Browser Capture Evidence Report

Overlay: **XAR10 — Browser Extension, WebView & Capture Evidence Correctness**  
Roadmap position: **overlay 10 of 17**  
Canonical section: **S10**  
Canonical findings closed: **18/18**

## Scope

XAR10 replaces the risky browser/WebView capture model with request-scoped evidence. Privileged browser evidence is no longer page-readable, same-URL evidence is not stored in a single slot, direct v3 handoffs are sender-bound and expiry-checked, and site-mode policy gates background capture plus manual popup sends. WebView page JavaScript can no longer override native Cookie/Authorization evidence, and document-start userscripts are scoped and removable.

## Closed canonical findings

- `S10-01`
- `S10-02`
- `S10-03`
- `S10-04`
- `S10-05`
- `S10-07`
- `S10-08`
- `S10-09`
- `S10-10`
- `S10-11`
- `S10-12`
- `S10-13`
- `DS5-S10-01`
- `DS5-S10-02`
- `DS5-S10-03`
- `DS5-S10-04`
- `DS5-S10-05`
- `RERUN45-S10-01`

## Implementation summary

- Firefox FAB now uses a closed Shadow DOM for capture links and status UI.
- Firefox evidence cache is keyed by exact URL + requestId + frame + generation, and navigation clears document evidence.
- Browser response classification uses final sent headers only; proposed headers remain audit context and are not replayed as executable runtime authority.
- Direct v3 links include sender nonce/created/expires proof and request fingerprints; Android rejects receiver-generated direct proof.
- Oversized multi-candidate sessions fail closed instead of falling back to a lossy one-candidate handoff.
- Background capture, popup manual send, and selected-candidate send all apply site-mode filtering.
- WebView native request evidence is document-generation scoped; bridge JSON input is bounded before parsing.
- WebView page JavaScript headers are stripped of sensitive names and cannot override native Cookie/Authorization.
- HLS/DASH child variants and tracks keep parent credentials only when the child origin is eligible.
- Userscript `@match`/`@include` matching supports normal wildcard semantics, emits a runtime matcher guard, and stores removable `ScriptHandler` handles.

## Validation evidence

The focused validator is `tools/validate-xar10-browser-capture-evidence.py`, wired into Gradle as `verifyXar10BrowserCaptureEvidence` and into `tools/run-final-release-gate.sh` after XAR09. It checks all 18 S10 IDs plus the code markers and manifest contract that prove the fixes are active.

This is an intermediate overlay. Apply with `--no-validate`; XAR16/XAR17 perform the full release and 313-root closure gates.
