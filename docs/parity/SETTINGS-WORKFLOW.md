# Overlay 18 — settings and workflow parity

Overlay 18 moves the remaining network and download defaults into the modern
settings schema and adds a deterministic import/export and XDM 8 migration path.

## Network behavior

The settings model now persists and validates:

- connection timeout and optional whole-request timeout;
- maximum retry attempts and exponential retry base delay;
- default and maximum segmented-transfer connection counts;
- the minimum size eligible for segmented transfer;
- system proxy, direct connection, or a manual HTTP/HTTPS/SOCKS proxy;
- proxy username/password, local-address bypass, and bypass patterns.

The shared `HttpClient` is constructed after settings initialization, so the
network handler receives the persisted proxy and timeout configuration. Handler,
proxy, retry, and segmentation policy changes apply after restart. The default
per-download connection count is used immediately for newly queued work.

## Server credentials

Host credentials are matched exactly or, when explicitly enabled, to
subdomains. A credential is used only when the download dialog or browser
capture did not provide explicit credentials. Exports redact both proxy and
server passwords by default. Including secrets requires an explicit checkbox
and the UI warns that the resulting file is sensitive.

## Import and export

Modern exports use a versioned `xdm-modern-settings` JSON envelope and are
published through an atomic temporary-file replacement. Imports accept:

- the versioned modern envelope;
- raw modern `ApplicationSettings` JSON;
- flattened legacy JSON;
- Java-style `.properties` files;
- XML entries with `key`/`name` and optional `value` attributes.

A directory import searches for conventional XDM settings filenames. The legacy
mapper recognizes download directory, concurrency, speed, clipboard, retry,
timeout, segmented transfer, proxy, duplicate-file, category, and queue keys.
If a legacy source omits categories or queues, the current modern definitions
are retained instead of being erased.

## Safety and limits

All imported values pass through the same schema normalization as normal
settings. Counts, timeouts, ports, retries, connection limits, bypass entries,
categories, queues, schedules, and credentials are bounded. Import never
executes source content, and export paths are written directly without a shell.
