# XDM Android XAR05 — Unified External Intake & Atomic Exact-request Admission

Overlay **5 of 17** closes the S03 external-intake root-cause group. It builds on XAR02/XAR03/XAR04 by moving every external/manual-adjacent entry point through a durable admission envelope before download/media execution can mutate durable transfer state.

## Canonical roots closed

`S03-01` `S03-02` `S03-03` `S03-04` `S03-05` `S03-06` `S03-07` `S03-08` `S03-09` `S03-10` `S03-11` `S03-12` `S03-13` `S03-14` `S03-15` `S03-16` `S03-17` `S03-18` `S03-19` `S03-20` `DS2-S03-01` `DS2-S03-02` `DS2-S03-03` `DS2-S03-04` `RERUN-S03-01` `RERUN23-S03-02`

## Implementation summary

- Added `ExternalAdmissionPolicy`, a common dispatch gate for share-sheet, VIEW, browser-extension, Tasker, deep-link, and replay commands.
- Re-keyed automation idempotency to a local `external-handoff-v5` identity derived from the executable request/provenance. Caller idempotency tokens, browser stable-media IDs, caller request fingerprints, and redacted URLs are now evidence/advisory values, not authoritative duplicate identity.
- Ordinary `ACTION_SEND`, `ACTION_SEND_MULTIPLE`, and generic VIEW handoffs now create Add Download review commands. They no longer default to media-only capture.
- `ACTION_SEND_MULTIPLE` is expanded into bounded per-URL durable commands. Unsupported `content://`-only payloads are persisted as structured rejected commands instead of disappearing.
- Browser direct/keyless capture no longer synthesizes private-network approval or uses an ephemeral internal-only import path. It is reviewed, persisted as an automation command, and restored through the same encrypted request-envelope path as other commands.
- Tasker/trusted automation no longer auto-enqueues network downloads merely because a token is valid. Token auto-execution remains only for non-network global controls; network commands still pass review/admission unless a later, explicit product bypass is introduced with its own documented policy.
- Proposed/final/browser headers, page-observation nonce/timestamps, request fingerprint, and direct-candidate batch metadata are preserved across durable command replay. Effective headers are stored in the encrypted command envelope before command persistence can succeed.
- Header reconstruction strips Cookie/Authorization/Origin when the page origin and target origin diverge, so editing an external URL cannot carry the original site's credentials to another host.
- Manual/private/LAN review now has an explicit admission outcome: local/private/reserved targets require scoped approval and the approval is stored only in the exact secure envelope.
- Download creation from automation keeps the exact request sidecar before the Download row is accepted; stale command/sidecar failures become structured failure records rather than executable rows without request material.
- Startup recovery drains pending automation commands to quiescence instead of processing only the first bounded startup batch.
- Add Download Inspect Media uses external credentials only if the displayed URL is still the reviewed URL; edited URLs are inspected through the manual path using the displayed URL.
- Magnet intake is now consistently accepted by URL normalization, Add review classification, and admission policy as a torrent review candidate.

## Regression evidence

Focused source/contract validator:

```text
python3 tools/validate-xar05-external-intake-admission.py
```

Model regression tests added/updated:

- `Xar05ExternalIntakeAdmissionContractTest`
- `AutomationModelsTest`
- `DownloadIntakePlannerTest` remains the scheme/classification baseline.

Final release gate and Gradle wiring now include `verifyXar05ExternalIntakeAdmission`, after XAR01–XAR04 validators.

## Exit criterion

All 26 S03 findings have a primary fix path, a focused validator assertion, and model tests for the adversarial identity/header/private-network/magnet cases. No external command can become executable unless the exact request envelope is admitted first.
