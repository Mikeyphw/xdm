# Changelog

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

### Changed

- `app/XDM/XDM.Modern.sln` is the supported solution
- WPF, GTK, WinForms, MSIX, and .NET Framework projects are no longer part of active builds

### Intentional modern replacements and preview boundaries

- FTP/FTPS transport is replaced by HTTPS downloads or a dedicated file-transfer client
- In-process binary self-update is replaced by externally installed, checksum-qualified release packages
- Browser extension store distribution and signing remain release-channel tasks
- Linux desktop integration may vary between desktop environments
- Retain a backup before migrating legacy state even though recorded migration fixtures are qualified
