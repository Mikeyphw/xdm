# Changelog

## XDM Android 0.5.1-alpha01 — 2026-07-15

### Changed

- Replaced synthesized partial ownership keys with backend-prepared physical artifact identities.
- Added stable backend instance identities, per-process session identities, and Room schema v5 persistence.
- Added startup reconciliation, quarantine classifications, and generation-safe artifact adoption.
- Prevented startup from releasing stale claims before the owning backend has validated its task and artifacts.
- Prepared the ownership boundary required before the embedded aria2 backend can write transfer data.

## 9.0.0-preview.1 — 2026-07-11

First modern Avalonia preview from the `Mikeyphw/xdm` fork.

### Added

- .NET 10 and Avalonia 12 desktop application for Linux and Windows
- Resumable HTTP/HTTPS download engine with validated range requests
- Crash-safe state checkpoints and finalization recovery
- Batch downloads, request metadata, categories, queues, scheduler, concurrency, and speed limits
- Authenticated browser capture and native-host installation/repair
- Direct media, HLS, and DASH probing
- Structured diagnostics, redacted bundle export, safe mode, and recovery tools
- Single-instance activation, tray/background mode, notifications, search, filters, bulk actions, and timeline
- Self-contained Linux x64/ARM64 and Windows x64 packaging
- Modern-only Linux/Windows CI, package qualification, final parity certification, and large-history performance checks
- Full browser takeover, HLS/DASH media acquisition, conversion, completion actions, migration, file management, localization, and accessibility
- FTP/FTPS transport with resume and TLS-protected data channels
- Bounded PAC proxy rules, integrated enterprise proxy authentication, 120 device profiles, and verified update staging

### Changed

- `app/XDM/XDM.Modern.sln` is the supported solution
- WPF, GTK, WinForms, MSIX, and .NET Framework projects are no longer part of active builds

### Product scope and preview boundaries

- Verified update packages are staged in-app but never executed automatically
- macOS is outside the maintained Linux/Windows modernization scope
- Adobe HDS remains a documented stale upstream claim because no retained working parser exists
- Browser extension store distribution and signing remain release-channel tasks
- Linux desktop integration may vary between desktop environments
- Retain a backup before migrating legacy state even though recorded migration fixtures are qualified
