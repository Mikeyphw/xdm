# Avalonia direct cutover: core port

The first modern core slice intentionally does not compile source from the legacy shared project. Instead, it establishes clean .NET 10 domain and service boundaries that subsequent overlays can populate with downloader behavior.

## Active model boundaries

- Downloads: state and immutable progress snapshots
- Categories: normalized extension matching
- Queues: ordered, duplicate-safe download membership
- Scheduling: timezone-aware normal and overnight windows
- Application state: thread-safe snapshots and change notifications
- Platform: runtime and operating-system information
- UI abstractions: dispatcher, dialogs, lifetime, and platform actions

The next overlay should introduce the real HTTP download engine behind these models without creating dependencies on Avalonia.
