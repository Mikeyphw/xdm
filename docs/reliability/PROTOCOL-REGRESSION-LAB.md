# XDM Protocol and Regression Laboratory

The protocol laboratory is a deterministic loopback HTTP/TLS environment used by the
`XDM.DownloadEngine.Tests` project. It exercises the real `SocketsHttpHandler` pipeline
without depending on public websites, external proxies, or long-lived fixture services.

## Covered scenarios

`ProtocolLabScenarioServer` exposes bounded endpoints for:

- valid byte ranges and `416` completion,
- contradictory `Content-Range` metadata,
- servers that ignore `Range`,
- redirect chains,
- one-use/expiring URLs,
- changing `ETag` values,
- premature response termination and range recovery,
- HTTP/1.1 chunked bodies,
- incorrect `Content-Length`,
- Basic authentication challenges,
- proxy authentication failures,
- `429 Too Many Requests` with `Retry-After`,
- self-signed TLS failures,
- multi-terabyte logical files without allocating multi-terabyte buffers,
- malformed filename and header inputs,
- deterministic HLS and DASH manifests and segments.

The server binds only to an ephemeral loopback port. Response bodies and time bounds are
small by default, and tests always dispose the server and clients.

## Layers

- `DeterministicHttpFaultServer` is the raw TCP/TLS response engine. It supports declared
  length mismatches, connection aborts, chunked framing, response delays, raw malformed
  header lines, and self-signed TLS.
- `ProtocolLabScenarioServer` provides named, repeatable protocol scenarios.
- `ProtocolRegressionLabTests` validates the laboratory and the platform HTTP stack.
- `DownloadManagerProtocolLabTests` runs critical scenarios through the real XDM transfer
  engine, checkpoint store, retry policy, resume validation, and atomic finalization path.

## Running the laboratory

```bash
dotnet test app/XDM/src/XDM.DownloadEngine.Tests/XDM.DownloadEngine.Tests.csproj \
  --filter 'FullyQualifiedName~ProtocolRegressionLabTests|FullyQualifiedName~DownloadManagerProtocolLabTests'
```

Run the complete regression suite before merging transfer, proxy, retry, resume, or media
changes:

```bash
dotnet test app/XDM/XDM.Modern.sln
```

## Adding a regression

Prefer extending `ProtocolLabScenarioServer` when a fault is reusable. Use the lower-level
`DeterministicHttpFaultServer` for a one-off malformed wire response. Every new scenario
should have:

1. a bounded laboratory behavior test,
2. an engine-level test when XDM owns the expected behavior,
3. an assertion that partial and final files remain safe after failure.

Do not call public test-download services from the regression suite.
