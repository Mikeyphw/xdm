# XDM Android Phase 4 completion and Phase 5 storage

This overlay adds the Android application under `app/XDM.Android`, registers the native `xdm_android` DevTool target, closes the Phase 4 execution/build gates, and implements the Phase 5 destination system.

## Phase 4 completion

- Debug, beta, and release variants.
- UIDT/foreground-service execution with reboot restoration.
- Aggregate and per-download pause, resume, retry, cancel, and mute notification actions.
- Root Android CI that runs static contracts, lint, JVM tests, and debug/beta APK builds.
- Native DevTool Android target with structured Gradle progress and verified APK collection.

## Phase 5 storage

- MediaStore Downloads, Movies, Music, Pictures, and Documents destinations.
- SAF tree selection for user folders, SD cards, and document providers.
- Persisted URI permissions and destination-health records in Room schema v4.
- App-private staging/checkpoint/finalization artifacts.
- Atomic file promotion where supported and copy/flush/commit for content providers.
- Overwrite, resume, rename, skip, and compare conflict policies.
- Native transfer-engine integration without broad all-files access.

## DevTool validation

Select target `xdm_android` and use the normal `--validate` pipeline. DevTool 0.15+ performs Android restore/configuration, debug and beta builds, local tests, Android Lint, APK discovery, APK integrity/metadata/signing checks, Gradle cleanup, rollback on failure, and the artifact commit after successful validation.

No custom artifact validator, embedded validation workspace, or direct `build-apk` call is included. DevTool's Android runner manages Gradle 9.4.1 and the Android toolchain.

## DevTool Android compatibility

This target disables DevTool structured Gradle events because DevTool 0.15 currently appends a second `--console=plain` argument when that mode is enabled. The standard Android builder output remains enabled. Artifact validation permits one infrastructure warning for ignoring an incompatible Gradle found on `PATH` while the required managed Gradle 9.4.1 is selected.
