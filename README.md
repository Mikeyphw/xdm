# Xtreme Download Manager — Modern Avalonia Preview

This fork is a direct .NET 10 and Avalonia rewrite of Xtreme Download Manager.
The supported application is `app/XDM/XDM.Modern.sln`; the old WPF, GTK,
WinForms, and MSIX projects are legacy reference code and are not restored,
built, tested, or packaged.

## Current preview capabilities

- HTTP, HTTPS, FTP, and FTPS downloads with pause, resume, retry, cancellation, and history
- ETag/Last-Modified resume validation, range checking, disk preflight, and crash-safe finalization
- Batch URLs, custom request metadata, authentication, categories, queues, schedules, and speed limits
- Firefox and Chromium-family browser capture through an authenticated native host
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
