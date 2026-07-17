## XDM Android 0.8.0-alpha01

Adds Phase 8 checksum verification, persisted verification results, trusted block manifests, and selective repair planning.

# Changelog

## 0.9.0-alpha01

- Added Phase 9 startup recovery scanning and recovery actions.
- Added schema v9 finalization-journal metadata and deterministic promotion stages.
- Added recovery UI actions without changing Android topography.


## XDM Android 0.7.0-alpha01 — 2026-07-17

### Added

- Added a live backend capability matrix and explainable automatic selection using protocol, destination, authentication, expiry, mirrors, expected size, metering, and previous host performance.
- Persisted requested and selected backends, recommendation reasons, explanations, and fallback policy in Room schema v7.
- Added pre-start-only fallback. Backend failures after task creation never jump engines.
- Added journaled native-to-aria2 and aria2-to-native migration with transactional ownership generation transfer.
- Added source task retirement, physical artifact inspection, distinct target preparations, and recovery-required failure states.
- Added Settings history and compatible migration actions while preserving the Android topography contract.

### Safety

- Cross-backend partial files are never interpreted by another engine.
- Existing source artifacts are preserved when migration restarts from zero.
- Unavailable or destination-incompatible migration controls are not presented.

## 0.6.0-beta01

- Completed the on-device aria2 backend with durable Room-to-GID mappings and every task operation.
- Added paused-before-ownership activation, authenticated event polling, session reconciliation, orphan/conflict handling, and provisional completion promotion.
- Added database schema v6 and migration coverage.
- Added official ARM64 runtime installation, ELF validation, SHA-256 attestation, CI packaging, and exact APK payload verification.


## XDM Android 0.6.0-alpha01 — 2026-07-16

### Added

- Added the Phase 6B embedded aria2 runtime foundation for ARM64 Android packages.
- Added APK-native executable discovery and ARM64 ELF validation through `ApplicationInfo.nativeLibraryDir`.
- Added a supervised shell-free aria2 process with ephemeral loopback RPC, a random per-installation secret, authenticated JSON-RPC, session persistence, bounded shutdown, and unexpected-exit handling.
- Added distinct `.xdm.aria2.part` artifact identities and preserved the Phase 6A ownership quarantine boundary.
- Added a Diagnostics smoke probe that starts aria2, authenticates RPC, saves the session, and shuts it down.
- Added runtime, RPC authentication, and lifecycle regression tests plus a Phase 6B static contract validator.

### Security

- aria2 is never copied into writable app storage and RPC is never bound beyond loopback.
- Short-lived launch configuration files are owner-only and deleted after RPC readiness.
- RPC secrets are redacted from object rendering and failure messages.

### Deferred

- Production aria2 task creation remains disabled until durable GID mapping, polling, and process-death reconciliation are implemented.

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
