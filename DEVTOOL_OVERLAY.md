# XDM Overlay 14 — browser takeover and extension parity

Base: confirmed successful commit `188d2a3`  
Target: `xdm_modern`  
Active solution: `app/XDM/XDM.Modern.sln`

## Included

- Protocol 2.0 native messaging with strict schemas, framing limits, bounded
  batches and per-connection session authentication.
- Automatic browser takeover that cancels only after XDM queues the download.
- Cookies, referer, user agent, filename, MIME, size, safe request headers and
  bounded GET/POST metadata.
- Site, size, MIME, extension, temporary-disable and incognito rules.
- Download with XDM, media capture and Download all links context commands.
- Firefox and Chromium-family support, including compatible Edge, Brave,
  Vivaldi and Opera paths.
- Native-host install/repair/uninstall and compatibility health.
- Recorded native-message fixtures and deterministic integration tests.
- Executable parity ledger and browser takeover documentation updates.

## Security properties

The protocol has no arbitrary execution message. Only explicitly allowlisted
request headers are accepted; messages, metadata and request bodies have explicit
limits; URL query
strings and secrets are not emitted in integration status. POST bodies are
memory-only and unfinished POST captures cannot be replayed after restart.

## Validation scope

Only the modern solution is allowed:

```bash
dotnet restore app/XDM/XDM.Modern.sln
dotnet build app/XDM/XDM.Modern.sln --configuration Release --no-restore
dotnet test app/XDM/XDM.Modern.sln --configuration Release --no-build
```
