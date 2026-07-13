# Deterministic HTTP fault laboratory

`XDM.DownloadEngine.Tests/FaultLab/DeterministicHttpFaultServer.cs` is a dependency-free loopback HTTP/1.1 server for transfer-integrity regression tests.

## Why it exists

Mocked `HttpMessageHandler` tests do not reproduce every behavior of `SocketsHttpHandler`, especially premature EOF handling, response framing, connection closure, and parser validation. The fault lab sends real bytes over a loopback TCP socket while remaining deterministic and offline.

## Current controls

Each request is passed to a response factory with an incrementing attempt number. A test can control:

- HTTP status;
- arbitrary response headers;
- `ETag`, `Content-Range`, `Location`, `Retry-After`, and authentication headers;
- the declared `Content-Length` independently from the body size;
- how many body bytes are actually written before the connection closes;
- different behavior for each retry attempt.

The request model exposes parsed headers, `Range` start, and `If-Range`, so tests can assert that XDM sent the correct resume contract.

## Implemented regression cases

1. A full-length response is cut in half at the socket. XDM must classify the premature HTTP EOF as transient, retry, send `Range` plus `If-Range`, and complete byte-for-byte.
2. A complete partial receives `416 bytes */length` but a changed strong `ETag`. XDM must reject finalization and preserve the partial.
3. A `206` response declares a range larger than its `Content-Length`. XDM must reject it before appending.

## Next fixtures

Extend the server or add sibling fixtures for chunked framing, delayed throttled writes, redirect loops, proxy negotiation failures, TLS certificate failures, large sparse logical files, malformed filename headers, and deterministic HLS/DASH manifests.


The expanded scenario catalogue and maintenance rules are documented in [`PROTOCOL-REGRESSION-LAB.md`](PROTOCOL-REGRESSION-LAB.md).
