# XGO-76..78 Desktop host/publication/platform implementation report

## Merge decision

XGO-76, XGO-77, and XGO-78 are delivered as one overlay because they close one architectural boundary: the desktop operating-system host for the authoritative Go engine. XGO-76 establishes the P/Invoke ABI lifecycle, XGO-77 implements the filesystem publication mechanism used by Go, and XGO-78 supplies the remaining desktop OS mechanisms (proxy/PAC, secret resolution, and wakeups) through the same typed platform-request channel.

XGO-79 remains separate. It owns Avalonia UI/ViewModel command and projection reconnection. XGO-80 remains later cleanup after that reconnection and may remove legacy C# transfer/media authority only when the UI no longer depends on it.

## XGO-76 — desktop Go engine host

`DesktopGoEngineHost` owns the desktop `xdmcore` ABI lifecycle only. It creates the versioned engine through the C ABI, verifies ABI/protocol metadata, pumps typed frames, routes `platform.request` frames privately to the desktop dispatcher, forwards non-platform frames for the later Avalonia projection cutover, and shuts down the native engine with the application.

The host stores Go desktop state under the user's local application-data directory rather than reusing legacy C# transfer state. Desktop packaging entry points build or consume the matching `xdmcore` native library and copy it alongside the published desktop application. A mismatched host/runtime must use a prebuilt native library rather than pretending CGo cross-compilation is portable.

## XGO-77 — filesystem publication broker

Go issues a typed `desktop.publication_commit` command, then requests the platform `publication` mechanism. `DesktopGoPublicationBroker` performs only filesystem publication mechanics and returns a durable receipt identity to Go.

The broker journals publication intent before the final move. Repeating the same idempotency key returns the existing receipt. If a process dies after the filesystem move but before the journal is marked committed, restart reconciliation observes the destination plus missing staging artifact and commits the existing receipt without republishing. Collision policy supports explicit replace or deterministic unique-path selection; unspecified collisions fail rather than silently overwriting.

## XGO-78 — desktop proxy, secrets, and wakeups

Go owns the policy decision and asks the desktop platform for mechanisms through typed platform requests. The desktop dispatcher supplies system/manual/PAC proxy facts, resolves an opaque secret reference from existing desktop credential/settings sources, and registers an exact host wakeup. Secret values are returned only in the private platform reply and are not emitted as public Go events. Wakeup expiry is sent back to Go as `desktop.wakeup_due`; the C# timer does not choose retry eligibility, queue order, or retry delay.

## Boundary retained for XGO-79/80

The existing Avalonia `IDownloadManager`, transfer-policy runtime, and presentation paths intentionally remain registered. This overlay starts the authoritative Go desktop host but does not claim that existing ViewModels are already projection-driven. XGO-79 must reconnect UI commands/projections, and only then may XGO-80 delete the legacy C# transfer/media engines and enforce the no-bypass architecture seal.

## Validation

The `xgo_desktop` Devtool target validates six ordered nodes: topology readiness, desktop engine-host source/package audit, C ABI harness, publication audit, platform-services audit, and targeted Go desktop runtime tests. Capability/fixture integrity and the broader Go regression suite are also run during the overlay audit loop before packaging.
