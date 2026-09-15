# XDM Desktop REM18 v7 — Cross-Boundary Final Release Seal After XFE01

Overlay: REM18  
Original desktop overlay index: 18 of 18  
Merged roadmap position: 2 of 8  
Artifact: `xdm_desktop_rem18_cross_boundary_final_release_seal_v7.tar.gz`  
Target: `xdm_modern`

## Why v7 exists

XFE01 passed first in the unified XDM roadmap and made the Android-owned Firefox extension the single canonical Firefox implementation. REM18 v7 rebases the desktop final release seal after that change so the full desktop gate validates the same canonical Firefox extension instead of sealing an immediately-stale pre-XFE desktop surface.


## v7 repairs after Android XAR16 v18 became the base

- Rebased the desktop final seal onto the new post-XAR16-v18 repository snapshot supplied by the user.
- Updated the XFE01 validator hash ledger for the current Android-owned canonical Firefox extension bytes without modifying the Android extension payload.
- Preserved the XAR16 low-memory Gradle task-graph defaults in `.devtool.toml` while changing only the `xdm_modern` package command to the REM18 final-seal package step.
- Removed the global Devtool artifact requirement from `xdm_modern` restore/build/test so the required evidence archive is produced and checked during package validation instead of causing premature `ARTIFACT_MISSING` on restore.
- Repaired parked analyzer/compiler failures: CA1873 logging wrapper guards, CA1416 file-lock reachability on macOS, `YtDlpProvider` network-policy constructor wiring, yt-dlp diagnostic catalog fallback, and analyzer-prone test assertions.
- Kept the Android Firefox extension implementation canonical and unchanged.

## v7 integration rules

- The Android Firefox extension remains the canonical source of truth.
- REM18 does not modify `app/XDM.Android/browser-extension/src/main/extension/xdm-firefox`.
- Desktop packaging keeps using `package-canonical-firefox-extension.py` to build `XDM-Firefox.xpi` from the Android-owned source.
- The old `app/XDM/firefox-amo` fork remains retired/non-loadable.
- The final gate now runs `validate-xfe01-single-firefox-extension.py` before REM18 ledger and release-matrix audits.
- `docs/parity/features.json` preserves both REM18's release-gate corrections and XFE01's canonical Firefox references.

## v5 repairs preserved

- `DownloadEngineLog.cs` CA1873: wrapper-guarded `LoggerMessage` core methods use `SkipEnabledCheck = true`.
- `QueueSchedulerRuntime.cs` CA1822: `StartScheduleRunAsync` became the static synchronous helper `StartScheduleRun`.
- `DownloadManager.cs` CA1859: `ResolveDestinationPath` accepts `HashSet<string>?`.
- `AccessibilityUiArchitectureTests.cs` xUnit2020: explicit failure uses `Assert.Fail(...)`.
- `MediaDownloadServiceTests.cs` CS1026: subtitle mux failure assertion closes `Assert.ThrowsAsync` correctly.
- `AtomicFile.cs`, `MediaHttp.cs`, `DashManifestParser.cs`, `FragmentIdentity.cs`, and `ConversionService.cs` retain the v5 compiler/analyzer repairs from earlier REM18 attempts.

## Validation expectation

REM18 remains a full final-seal overlay. Apply it with validation enabled; do not use `--no-validate`.

```bash
gradle --stop 2>/dev/null || true

devtool -r "$HOME/Code/xdm" --target xdm_modern --yes apply-overlay \
  "/sdcard/Download/xdm_desktop_rem18_cross_boundary_final_release_seal_v7.tar.gz" \
  --commit "REM18: seal desktop release after Firefox convergence" \
  --resource-profile standard --max-workers 2 --cpu-limit 2 \
  --gradle-heap-mb 1280 --gradle-metaspace-mb 512 \
  --memory-guard-mb 384
```

The `xdm_modern` target runs `restore`, `build`, `test`, and `package`; the package step maps to `rem18-final-release-seal.sh --devtool-package-step` and now includes the XFE01 single-Firefox audit.

## Static validation performed while packaging v6

The packaging workspace validated archive path safety, manifest/file coverage, Python syntax, bash syntax, REM18 ledger closure, REM18 release-matrix contracts, the XFE01 canonical Firefox validator, and preservation of the XFE01 parity references. The local packaging container still has no .NET SDK, so the authoritative compile/test/package proof remains the Devtool validation run on the target repo.
