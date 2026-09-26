# XGO-75 Android legacy-authority deletion and seal contract

XGO-75 is the final Android implementation overlay before GATE-10. It deletes or reduces superseded Kotlin authority after the XGO-74 one-time Room import cutover, without absorbing the separate cross-subsystem qualification gate.

## Authority boundary

- Android notification receivers, foreground-service controls, and ViewModel transfer controls submit typed download commands to the single Go engine authority.
- WorkManager, FGS, UIDT, boot/package restore, and condition monitors remain host/wake mechanisms only; they do not rank queue candidates, choose retry policy, or claim transfer ownership.
- `QueueIntelligenceCoordinator` is retained only as a compatibility/status facade for older metadata/UI callers. It may wake Go or submit a Go command but may not use Kotlin queue planners, retry ledgers, admission gates, slot claims, or execution starters.
- `AndroidLegacyDownloadUiBroker` is deleted. Its replacement, `AndroidDownloadExecutionBroker`, performs only Go-authorized Android side effects and legacy-row materialization still required by Kotlin transfer backends.
- Application startup does not install Kotlin queue holds or run Kotlin transfer ownership recovery as an admission authority. The XGO-74 one-time Room importer remains the only legacy state ingress into Go authority.
- Kotlin persistence/telemetry helpers may remain when they are non-authoritative compatibility surfaces; GATE-10 qualifies the composed XGO-66..75 boundary separately.

## Forbidden-reference seal

The `android_legacy_authority_scan` validation node must fail if authoritative Android entry points regain any of the following: direct notification/service runtime pause/cancel/start policy, ViewModel calls to Kotlin start/pause-all/resume-all authority, queue ranking/retry/slot-claim machinery inside the compatibility coordinator, the deleted XGO-72 broker, live Room projection mirrors, or startup ownership recovery that authorizes transfer execution outside Go.

## Validation

`xgo_android#validate` contains 12 nodes after this overlay. `android_legacy_authority_scan` runs after the import matrix and before Android host runtime tests, so the Android target itself is the XGO-75 smoke/seal. GATE-10 remains a separate target that composes foundation through Android in one EXO DAG.
