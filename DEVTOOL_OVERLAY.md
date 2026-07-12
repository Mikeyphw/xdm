# XDM Overlay 18 — settings and workflow parity

Base: confirmed successful commit `1a0eb3a`

Target: `xdm_modern`

Active solution: `app/XDM/XDM.Modern.sln`

## Included

- Schema-v3 bounded network and download behavior settings.
- Connection and whole-request timeouts.
- Retry attempts and exponential retry base delay.
- Default/maximum segmented connections and minimum segmented file size.
- System, direct, and manual authenticated proxy modes with bypass rules.
- Exact-host and optional-subdomain server credential manager.
- Default duplicate handling, category selection, directory creation, clipboard,
  and request-metadata behavior.
- Versioned atomic settings exports with passwords redacted by default.
- Modern JSON plus legacy JSON, Java-properties, XML, and directory migration.
- Recorded legacy settings/category/queue fixture and deterministic tests.
- Settings, migration, and parity documentation updates.

## Runtime behavior

Settings are initialized before the shared HTTP client is created. Proxy,
timeout, retry, and segmentation policy changes therefore apply to the next XDM
process. New downloads use the saved default connection count immediately.
Explicit request credentials always take precedence over saved host credentials.

## Safety

Imports are parsed as bounded data only and are never executed. All imported
values pass through the normal settings schema limits. Exports omit proxy and
server passwords unless the user explicitly enables secret export. Files are
written through a private temporary path and atomically replaced.

## Validation scope

Only the modern solution is allowed:

```bash
dotnet restore app/XDM/XDM.Modern.sln
dotnet build app/XDM/XDM.Modern.sln --configuration Release --no-restore
dotnet test app/XDM/XDM.Modern.sln --configuration Release --no-build
```
