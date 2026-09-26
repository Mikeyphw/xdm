# XGO-76..78 overlay contract

## Scope

This overlay implements only the desktop host boundary promised by XGO-76, XGO-77, and XGO-78.

### Required XGO-76 outcomes

- Desktop loads the same versioned `xdmcore` engine through the stable C ABI/P/Invoke boundary.
- The C# bridge owns engine lifecycle and platform dispatch, not transfer/retry/queue policy.
- ABI and protocol metadata are checked before the host is considered started.
- Native desktop publish/smoke paths carry `xdmcore` for Linux and Windows.
- Non-platform Go frames are exposed to the desktop presentation layer for XGO-79, without reconnecting that UI in this overlay.

### Required XGO-77 outcomes

- Filesystem publication is initiated by Go and executed as a platform mechanism.
- Rename/replace/collision handling is host-side mechanics only.
- Publication has a durable journal and idempotency key.
- A crash after platform commit reconciles to the original receipt instead of republishing.
- The platform receipt returns durable result identity/location to Go.

### Required XGO-78 outcomes

- System/manual/PAC proxy information is supplied through typed platform requests.
- Secret lookup accepts an opaque secret reference and does not publish the secret through normal Go event/projection frames.
- Desktop wakeups register an OS/host timer and report only a due fact to Go.
- C# must not recreate scheduler eligibility, retry backoff, queue ordering, or transfer policy.

## Explicit exclusions

- No Avalonia ViewModel/command cutover: XGO-79.
- No deletion of the legacy C# transfer/media execution stack: XGO-80 after UI reconnection.
- No Devtool install, bootstrap, editable-install, or package-refresh hook.
- No unrelated Android changes; GATE-10 is already closed and its final `.devtool.toml` artifact/resumable configuration is preserved as the baseline.

## Apply/validation contract

The artifact must apply atomically to a clean post-GATE-10 tree and validate with target `xgo_desktop`. Validation must remain target-scoped; it must not recursively rerun earlier roadmap gates.
