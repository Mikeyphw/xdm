# XDM Android — Post-DL03 Release Follow-up

Base: `b6928c32` (successful DL02+DL03 v3)

## Purpose

The MC01–MC05 / DL01–DL03 feature roadmap is complete. This follow-up closes release-governance drift discovered after DL03 rather than adding another transfer-engine feature phase.

## Release-gate closure

- `tools/run-final-release-gate.sh` now replays the current `validate-runtime-foundation-phase59-61.py` and `validate-dl02-dl03-progress-seal.py` validators.
- The final static gate no longer executes the Phase-11-owned Phase 1–10/backup/Phase 58 validators twice; it verifies their files, executes non-matrix validators once, then runs the retained static matrix once. Coverage is unchanged while gate latency is reduced.
- The new `validate-post-dl03-release-followup.py` validator is itself part of the canonical gate, so gate wiring, schema/version truth, CI toolchain pins, and roadmap completion remain source-enforced.
- `tools/run-final-common-validation.sh` remains the canonical non-device Gradle matrix and enters through `:app:finalRemediationStaticGate`.
- The root Android CI now uses Java 21 and Gradle 9.7.1 consistently with the Android project and Termux Devtool target.
- Signed-release CI keeps entering through the canonical final gate.
- The Phase 1 security validator now permits `XdmBrowserDeepLinkParser` only on the reviewed internal browser-direct import action/extra while still rejecting arbitrary external intent/shared-text parsing in `MainActivity`.
- The Phase 2 execution validator now carries DL02 forward by requiring `NativeRollingSpeedMeter` and incremental persisted-segment integrity instead of the superseded `bytesAtAttemptStart` baseline.

## Documentation truth

- The Android README now identifies the current app as 0.21.0 / Room schema 21.
- Phase 17 schema-v14 text is retained only as historical context, not presented as the current release boundary.
- Historical architecture documents are intentionally not rewritten.

## Database/runtime impact

None. This overlay adds no Room migration and changes no transfer/media runtime behavior.

## Roadmap status

MC01–MC05 and DL01–DL03 are release-gate sealed. No additional handoff roadmap phase is pending after this follow-up.
