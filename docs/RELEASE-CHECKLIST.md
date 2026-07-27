# Modern XDM prerelease checklist

## Source

- [ ] Working tree is clean and commit history identifies the intended release
- [ ] `VERSION` matches package and release names
- [ ] `XDM.Modern.sln` restores, builds, and tests with zero warnings and zero errors
- [ ] Bootstrap validation passes on Linux and Windows
- [ ] `FinalParityGateTests` reports 100% critical/high parity and valid evidence
- [ ] No active workflow restores or builds WPF, GTK, WinForms, or MSIX projects
- [ ] Known legacy application source paths are absent

## Functional smoke tests

- [ ] Add, pause, resume, cancel, retry, remove, and complete a download
- [ ] Resume against a range-capable HTTP server
- [ ] Download and resume a passive FTP source; verify explicit FTPS certificate validation
- [ ] Exercise direct, manual, PAC, Basic, and integrated proxy modes
- [ ] Verify safe restart when a server ignores Range or changes validators
- [ ] Run two queues simultaneously and verify queue-specific limits
- [ ] Verify an overnight schedule and missed-start behavior
- [ ] Capture a browser download and repair the native host
- [ ] Probe direct media, HLS, and DASH fixtures
- [ ] Verify the device-profile catalog exposes at least 100 fixed presets
- [ ] Check and stage a HTTPS-manifest update package without automatic execution
- [ ] Verify tray restore, explicit exit, notification, and second-instance activation
- [ ] Export a diagnostic bundle and inspect it for secrets
- [ ] Recover from an interrupted finalization marker

## Packages

- [ ] Linux x64 self-contained package launches on a clean supported system
- [ ] Linux ARM64 self-contained package launches on a clean supported system
- [ ] Windows x64 self-contained package launches on a clean supported system
- [ ] `XDM.NativeHost` is beside the application executable
- [ ] Browser manifests point to the packaged native-host path
- [ ] Recorded XML/JSON settings, history, and scheduler migration fixtures pass
- [ ] Upgrade preserves settings and history
- [ ] Uninstall leaves user data unless explicitly requested
- [ ] Package hashes are published

## Performance

- [ ] Large-history regression tests pass
- [ ] Bootstrap benchmark results are archived
- [ ] UI remains responsive with 10,000 history items
- [ ] Logs and timelines remain bounded

## Cutover

- [ ] Create a backup branch/tag before deleting legacy projects
- [ ] Run `remove-legacy-ui.sh --check`
- [ ] Run `remove-legacy-ui.sh --apply`
- [ ] Re-run full validation and package qualification

## XDM Android browser bridge

- [ ] `xdmdownload` and debug scheme variants resolve only to `ExternalAddDownloadActivity`
- [ ] Accepted and rejected deep-link diagnostics remain bounded and redact query values, cookies, authorization, and credentials
- [ ] Browser extension export directory permission survives restart or reports a specific recovery state
- [ ] Missing, modified, wrong-variant, stale-theme, and interrupted XPI exports are distinguished in Settings
- [ ] A verified prior XPI remains recoverable when replacement or SAF promotion fails
- [ ] Exported XPI opens through Android's package installer or a compatible Firefox extension installer
- [ ] IronFox setup instructions match the active application variant and custom scheme
- [ ] Phase 37 through Phase 41 validators pass before the Phase 42 browser-bridge release gate

## XDM Android browser bridge Phase 42 release gate

- [ ] `tools/validate-phase-42-kotlin-compile-recovery.py` passes
- [ ] Devtool compile runs `:app:resetKotlinValidationState` before `:app:compileDebugSources`
- [ ] Validation uses in-process, non-incremental Kotlin compilation with Gradle caches and parallelism disabled

### Automated

- [ ] `devtool --target xdm_android apply-overlay ... --validate` completes with zero warnings and zero errors
- [ ] `bash tools/run-browser-bridge-release-gate.sh --full` passes in the target Android build environment
- [ ] Dark and AMOLED XPIs pass exact-inventory, fixed-timestamp, stable-ID, minimal-permission, and SHA-256 verification
- [ ] `release-artifacts.json` matches the generated XPIs
- [ ] Debug, Android-test, unit-test, browser-extension, and lint tasks all pass
- [ ] Phase 37 through Phase 42 validators pass

### Device

- [ ] `bash tools/run-browser-bridge-device-acceptance.sh --adb` resolves the intended XDM variant and launches both `capture` and `add`
- [ ] The manual IronFox matrix in `app/XDM.Android/docs/browser-extension/DEVICE-ACCEPTANCE.md` is complete
- [ ] Direct MP4, HLS, DASH, blob/MediaSource, and cross-origin iframe fixtures surface the themed FAB
- [ ] XDM, 1DM+, Ask, deduplication, theme regeneration, SAF recovery, and release/debug coexistence pass
- [ ] URI, diagnostics, screenshots, logs, and release metadata contain no raw credentials

Record device model, Android version, IronFox version, XDM variant/version, extension SHA-256, tester, and date with the release evidence.
