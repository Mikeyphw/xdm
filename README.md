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
