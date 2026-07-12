# Optional aria2 backend integration

## Scope

This enhancement adds an optional aria2 JSON-RPC backend to the modern XDM application. It is a new backend option rather than a claim about functionality inherited from legacy XDM, so it is deliberately documented outside `docs/parity/features.json`.

The built-in download engine remains the default. aria2 is inactive until the user enables it in **Settings → aria2 backend**.

## Connection modes

### Managed process

XDM starts and supervises a local `aria2c` process with RPC bound to loopback only. The process is launched without a shell and every argument is passed through `ProcessStartInfo.ArgumentList`.

When the user has not configured an RPC secret, XDM generates an in-memory random secret for the managed process and uses it for local RPC calls. The generated value is not written back to settings.

Configurable managed-process behavior includes:

- aria2c executable and session-file paths;
- automatic startup;
- session loading and periodic saving;
- maximum concurrent downloads;
- split count and minimum split size;
- continuation and certificate checking;
- additional arguments, entered one complete argument per line.

### External RPC

XDM can connect to an existing aria2 JSON-RPC endpoint. Loopback HTTP endpoints are accepted. Non-loopback endpoints must use HTTPS and must have an RPC secret; insecure remote configurations are rejected before a request is sent.

## Supported operations

The integration provides:

- authenticated `aria2.getVersion` health checks;
- `aria2.addUri` for HTTP, HTTPS, FTP, SFTP, and magnet sources;
- active, waiting, and stopped task polling;
- progress, transfer speed, connection count, destination, and error reporting;
- pause, resume, remove, and forced removal fallback;
- managed-process start, graceful shutdown, and session saving.

RPC secrets are inserted as the first `token:<secret>` JSON-RPC parameter, as required by aria2. Exporting settings without secrets removes both the proxy/server credentials already handled by XDM and the aria2 RPC secret.

## Settings compatibility

`Aria2IntegrationSettings` is an optional trailing member of `ApplicationSettings`. Existing schema-v5 settings files therefore continue to deserialize and are normalized with disabled aria2 defaults. Modern JSON import/export and legacy key/value import both preserve the aria2 configuration, while secret-redacted exports intentionally replace the RPC secret with an empty string.

## UI

The existing eight-section navigation is unchanged. The aria2 editor and task monitor are contained in the Settings section to preserve bootstrap and accessibility surface expectations.

The UI supports:

- enabling the backend and selecting managed or external mode;
- editing RPC, process, session, polling, concurrency, and split settings;
- browsing for the executable, session file, and destination folder;
- starting, stopping, applying, and refreshing the backend;
- adding a URL or magnet task;
- selecting and pausing, resuming, or removing a task.

## Qualification

Automated coverage includes settings normalization and backward-compatible defaults, secret-redacted settings round trips, RPC authentication ordering, URI option mapping, task parsing, RPC error propagation, and task-control parameter ordering.

This document is enhancement documentation only. No aria2 feature is added to the legacy parity manifest because there is no upstream legacy source to cite.
