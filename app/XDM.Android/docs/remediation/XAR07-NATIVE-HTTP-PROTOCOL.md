# XAR07 — Native HTTP Protocol, Cancellation & Integrity Correctness

**Overlay:** 7 of 17  
**Scope:** S07 Native HTTP Protocol / Cancellation / Integrity  
**Canonical findings closed:** **21/21**  
**Apply mode:** intermediate roadmap overlay, `--no-validate`

## Summary

XAR07 hardens the native HTTP backend at the wire, retry, checkpoint, and finalization boundaries. The backend no longer relies on default redirect credential propagation, response bodies remain cancellable while bytes are being streamed, every bounded transfer checks that the body cannot exceed the declared segment/full-response limit before writing beyond it, and final publication is blocked until a generation-owned checkpoint graph re-verifies the staged bytes.

## Implemented fixes

- Sensitive headers are stripped on cross-origin redirects: `Authorization`, `Cookie`, `Proxy-Authorization`, `Referer`, `Origin`, and token/key-like headers cannot cross the original scheme/host/port boundary.
- Active OkHttp calls stay registered through response-body streaming, so pause/cancel can interrupt body reads instead of only cancelling the request setup phase.
- HEAD metadata no longer overrides a valid 206 range probe with an unknown total; unknown range totals stay unknown and are not promoted to trusted capacity/UI length.
- Final-save retry and normal finalization both reload and verify the persisted checkpoint graph before promotion.
- Native capabilities no longer advertise selective repair as a normal production capability while it remains a guarded exact-request repair path.
- Dormant selective repair now has fail-closed request-security validation by default, per-target single-flight locking, UUID temp/backup names, and whole-manifest verification including untouched trusted blocks.
- Binary transfer rejection now catches textual/structured error payloads broadly (`text/*`, JSON/XML variants, XHTML/problem+json) when a binary file/media artifact was expected.
- Metadata HEAD/range probes now use the same retry/backoff wrapper as execution.
- `Retry-After` supports both delta-seconds and HTTP-date forms.
- Backoff can charge retry policy to the actual host returned by the response after redirect, not only the pre-redirect host.
- Ranged and trusted-length non-ranged body writes are capped before disk writes can exceed the declared boundary.
- Complete cold GET responses must match the probed representation validator when one exists.
- `Content-Range` is validated semantically: start/end order, declared total greater than end, known expected total cannot be replaced by `*`, and requested range/total must match.
- Pause cannot downgrade Failed/RecoveryRequired/Completed/Cancelled into clean Paused; cancel cannot overwrite committed or recovery-required publication states.
- Focused XAR07 source/behavior contract is registered in Gradle, final release static gate, and `PROJECT_MANIFEST.json`.

## Canonical findings closed

- `S07-01`
- `S07-02`
- `S07-03`
- `S07-04`
- `S07-05`
- `S07-06`
- `S07-07`
- `S07-08`
- `S07-09`
- `S07-10`
- `S07-11`
- `S07-12`
- `S07-13`
- `S07-14`
- `S07-15`
- `DS4-S07-01`
- `DS4-S07-02`
- `DS4-S07-03`
- `DS4-S07-04`
- `DS4-S07-05`
- `RERUN34-S07-01`

## Focused validation

`tools/validate-xar07-native-http-protocol.py` asserts the code, test, manifest, and gate wiring for all 21 canonical S07 findings. The transfer-native source contract test records the four core behavioral invariants: body-read cancellation tracking, metadata/representation truth, finalization/downgrade protection, and redirect/retry/selective-repair boundaries.

## Notes

This overlay intentionally does not change the aria2 ownership layer, scheduler retry owner, browser evidence, or media resolver. Those are owned by later overlays XAR08–XAR12.
