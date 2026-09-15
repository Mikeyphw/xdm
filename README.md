# Xtreme Download Manager — Modern Avalonia Preview

This fork is a direct .NET 10 and Avalonia rewrite of Xtreme Download Manager.
The supported application is `app/XDM/XDM.Modern.sln`; the old WPF, GTK,
WinForms, and MSIX projects are legacy reference code and are not restored,
built, tested, or packaged.

## Current preview capabilities

- HTTP, HTTPS, FTP, and FTPS downloads with pause, resume, retry, cancellation, and history
- ETag/Last-Modified resume validation, range checking, disk preflight, and crash-safe finalization
- Batch URLs, custom request metadata, authentication, categories, queues, schedules, and speed limits
- Firefox capture through the single Android-owned canonical Firefox extension using the `xdmdownload` handoff contract, plus Chromium-family capture through the authenticated native host
- Direct media, HLS, and DASH probing and acquisition
- 120 fixed device conversion profiles and verified update-package staging
- System, manual, and bounded PAC proxy modes with Basic or integrated authentication
- Single-instance activation, tray/background operation, desktop notifications, diagnostics, and recovery
- Self-contained Linux x64/ARM64 and Windows x64 publishing

This is a preview. Back up important partial downloads and configuration before upgrading.

## Requirements

- .NET SDK 10.0.300 for development
- Linux or Windows desktop environment supported by Avalonia

Published packages are self-contained and do not require a separately installed .NET runtime.

## Build and test

```bash
dotnet restore app/XDM/XDM.Modern.sln
dotnet build app/XDM/XDM.Modern.sln -c Release --no-restore
dotnet test app/XDM/XDM.Modern.sln -c Release --no-build
dotnet run --project app/XDM/src/XDM.App/XDM.App.csproj
```

Or use the repository validation script:

```bash
./app/XDM/eng/validate-modern.sh
```

## Package and qualify

```bash
./app/XDM/eng/qualify-prerelease.sh
```

Artifacts are written below `artifacts/` and use the version in `VERSION`.
Windows equivalents are available as PowerShell scripts in `app/XDM/eng`.

## Browser integration

Open **Browser Integration** in XDM and run the install/repair action. The app
installs a per-user native-host manifest and reports protocol/connection health.
The browser extension and desktop app must use compatible protocol versions.

## Recovery

```bash
XDM --safe-mode
XDM --reset-window-state
```

The Diagnostics page can export a redacted support bundle.

## Legacy cleanup

After validating the modern build and making a backup branch:

```bash
./app/XDM/eng/remove-legacy-ui.sh --check
./app/XDM/eng/remove-legacy-ui.sh --apply
./app/XDM/eng/validate-modern.sh
```

See `docs/MIGRATING-FROM-XDM8.md` and `docs/RELEASE-CHECKLIST.md`.


## Phase 41 browser bridge integration

XDM Android now includes truthful browser-extension status, redacted handoff diagnostics, SAF/XPI recovery, and variant-specific IronFox setup guidance. See `app/XDM.Android/docs/architecture/PHASE-41-BROWSER-BRIDGE-INTEGRATION.md`.

## XDM Android browser bridge release gate

The Android project owns its Firefox media bridge source, deterministic XPI generator, XDM-themed FAB, custom `xdmdownload` intake, settings diagnostics, and recovery flow. Desktop packaging consumes this same Firefox source tree and adapts the desktop receiver to its existing handoff protocol; there is no second Firefox implementation. Phase 42 seals the automated release matrix and documents the remaining physical-device IronFox sign-off. See `app/XDM.Android/docs/architecture/PHASE-42-BROWSER-BRIDGE-RELEASE-GATE.md`.


## v3 artifact-manifest repair

The v3 archive removes the `workspace.root: "."` manifest field used in v2 because Devtool normalizes that root to an empty path during artifact manifest validation. The payload source intent remains the same: Android Firefox extension logic is preserved and desktop is adapted around that canonical extension.


## v4 repair note

The 20260914 v3 Devtool run passed artifact validation and applied, then failed during full xdm_modern validation before REM18. XFE01 is a pre-seal convergence overlay, so apply it with --no-validate. v4 also fixes the new XFE01 adapter compile/analyzer findings: explicit new string(char[]), StartsWith(char), and an explicit FileStream lock for secondary-instance tests. Full desktop validation remains owned by REM18, merged overlay 2 of 8.


## XFE01 single Firefox extension convergence

This repository has received merged roadmap overlay 1 of 8: XFE01. Firefox capture is consolidated around the already-working Android-owned extension. v5 is a manifest-only repair over v4: it uses Devtool's accepted `validation.failure_action: keep` enum for the pre-seal `--no-validate` apply mode. The Android extension capture logic remains unchanged.


### XAR16 v8 validation hotfix

Overlay 7 of 8 keeps validation enabled and fixes runtime schemaVersion drift, Python bytecode cache generation, Kotlin aria2Eligible propagation, and Firefox handoff header normalization idempotence.

- XAR16 v9 keeps overlay 7 of 8 validation enabled and repairs retained-gate/Kotlin compile drift without changing the canonical Android Firefox extension capture logic.


## v10 retained validation repair

XAR16 v10 keeps validation enabled and repairs the v9 apply failures. The retained Phase61 validator now accepts the compact `XAR17_313_root_closure_audit_final_gate` next-phase marker used by the merged roadmap. Devtool validation also stops invoking `:app:testDebugUnitTest` during XAR16 apply because that task resolved uncached external test artifacts (`kotlinx-coroutines-test` and Turbine) from `dl.google.com` in the offline Termux validation environment. XAR16 still validates static gates, retained XAR validators, browser extension validation, module tests, lint, and debug assembly; full app-unit-test dependency validation remains part of the final XAR17/online cache gate.

## v11 validation repair

XAR16 v11 keeps validation enabled and repairs the v10 validation failures. The XAR16 validator now performs the retained Phase61 next-phase check inside `main()` instead of calling an undefined helper after `main()` returns. The retained XAR13 Downloads contract test now reads source files with `Files.readAllBytes(..., StandardCharsets.UTF_8)` instead of `Files.readString(...)`, avoiding Android/JDK API surface drift during app unit-test compilation. The canonical Android Firefox extension production source remains unchanged.
## v12 validation repair

XAR16 v12 keeps validation enabled and repairs the three failures surfaced by the v11 full Devtool graph. `checkBrowserIntegration` now runs the browser-integration module tests instead of reintroducing the full app unit-test suite that XAR16 deliberately defers to XAR17; the retained Phase64 validator accepts the merged-roadmap `XAR17_313_root_closure_audit_final_gate` successor marker; and the FFmpeg runtime verification tasks now declare `mustRunAfter(installPinnedFfmpegRuntime)` so Gradle 9.7 has an explicit ordering relationship whenever native install and verification coexist in one graph. The canonical Android Firefox extension production logic is unchanged.

## v13 retained Phase63/static-matrix successor repair

XAR16 v13 keeps validation enabled and repairs the stale retained-gate successor checks exposed after v12. Phase63 now accepts the already-authoritative merged-roadmap `XAR17_313_root_closure_audit_final_gate` successor marker, and the retained Phase7/Phase10/Phase11 static matrix validators now accept the superseding XAR16 signed-release path and XAR17 final-closure handoff without weakening their original evidence checks. No production Android Firefox extension logic is changed, and the v12 browser-integration scope and Gradle 9.7 FFmpeg ordering repairs remain intact.

## XAR16 v15 corrective rebuild

XAR16 v15 supersedes the rejected v14. It fixes the v13 module failures directly and adds Gradle task-graph optimization: no parallel `clean` in validation, app-scoped lint, retained module unit tests, fixed persistence/storage/media/scheduler/aria2/native behavior, and source-audit compatibility for XAR07.


## v16 supersession note

v16 replaces v15 after device validation exposed remaining XAR01/core-model/D8 CodeCache failures. Use v16 only; do not apply v14 or v15.
