# XAR16 v15 error-closure and Gradle task-graph optimization report

XAR16 v15 supersedes and retires the rejected v14 scope-only attempt. It keeps overlay 7 of 8 on XAR16, preserves validation, and fixes every distinct failure reported by the v13 Gradle log instead of removing the failing tasks.

## Closed failure classes

- Persistence Phase6 R2: `upsertDownloadPreservingNewerState` now performs insert-first adoption and updates an existing row only when the stored attempt generation/timestamp is older than the incoming owner.
- Storage XAR06: rename publication now fails closed when a rename target is created between prepare and promote; it does not overwrite the raced target and preserves staging for review/retry.
- Media FF02/MC04/XAR11: adaptive planning no longer auto-pairs unrelated audio for an explicit video-only selection; DASH `BaseURL` inheritance and executable segment templates are reconciled; protected HLS closed captions remain anchored to the master while ordinary CC stays in-band; incomplete bounded manifest probes are non-authoritative and do not expose fallback variants.
- Scheduler notification trampoline: completed terminal events are emitted with the generation-bound committed artifact URI, so later notification taps re-open the durable artifact instead of the original destination placeholder.
- Aria2 repair: repair rotates the private RPC secret and relies on the launch path for exactly one transient launch-configuration cleanup before restart.
- Native HTTP: legacy `.xdm.part` checkpoints are adopted into the current publication staging/checkpoint path before validation. Valid checkpoints resume with a ranged request, and changed strong validators become `RecoveryRequired` instead of silently restarting from zero and completing.
- XAR07 native static contracts: source-audit mirror files restore the historical module-relative static root expected by the protocol tests without adding those files to Gradle source sets.

## Gradle task-graph optimization

- Removed `clean` from the parallel validation/build task list. Devtool transactions already provide source cleanliness, and mixing Gradle `clean` with tests/lint/assemble in one parallel invocation expands destroyer dependencies and can stall unrelated work.
- Replaced root `lintDebug` aggregate validation with `:app:lintDebug` for the XAR16 graph, keeping app release lint coverage while avoiding unnecessary root fan-out across every Android subproject.
- Kept all failing module test tasks in the XAR16 validation list: media, persistence, scheduler, storage, transfer-aria2, and transfer-native still run.
- Preserved the FFmpeg install → runtime verify → APK attestation ordering and the existing browser-extension/browser-integration gates.

## Validation performed here

Static validators executed successfully in this environment:

- `tools/validate-gradle-task-graph-optimization.py`
- `tools/validate-xar16-release-evidence-seal.py`
- `tools/validate-phase63-release-readiness-support-bundle-seal.py`
- `tools/validate-phase64-final-android-downloader-rc-seal.py`
- `tools/validate-bug-hunt-phase11-validation-matrix.py`

Android Gradle unit tests were not executed here because the local wrapper attempted to fetch Gradle from `services.gradle.org` and this sandbox has no network access. The overlay leaves `--validate` enabled for the device-side run.


## v16 correction

The v15 validation log exposed three remaining failure classes and v16 fixes them directly:

- Restores the XAR01-required `clean` -> `assembleDebug` standard build ordering while keeping clean out of package/test/lint phases.
- Keeps external automation MIME/content metadata review-only so deduplication keys do not split on caller-supplied hints.
- Fixes support-bundle redaction report wording so the final scanner does not classify its own explanation text as a path credential.
- Keeps queued download supporting text equal to the factual queue position instead of mixing backend labels into the same UI field.
- Accepts reviewed Room schema v24+ in legacy install/update and release-security model gates while XAR16/XAR17-specific gates may demand newer schema evidence separately.
- Reserves Gradle JVM CodeCache for D8/R8 Android-test dex merging on Termux and records this in the Gradle task-graph validator.


## XAR16 v18 Resource Interruption Closure

The v17 payload applied and artifact validation passed, then Gradle was stopped by Devtool memory guard during build, unit-test, and lint phases on a native Termux device with about 3.0 GiB storage free and ~230-240 MiB RAM remaining. v18 keeps the broad validation suites enabled but moves the default execution contract to daemon-free, single-worker, non-parallel Gradle phases, removes duplicate `org.gradle.jvmargs` injection, lowers the default Gradle heap/metaspace envelope, and removes the accidental nested `transfer-native/transfer-native` mirror from the artifact payload.
