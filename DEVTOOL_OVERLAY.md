# XDM Protocol Regression Laboratory Fixed Overlay

## Purpose

This overlay completes priority 10 from the modernization plan and includes the six
deferred desktop-productivity warning fixes.

## Protocol laboratory

- Expands the existing deterministic raw TCP server with:
  - optional self-signed TLS,
  - chunked transfer framing,
  - declared-length mismatches,
  - bounded response delays,
  - connection aborts,
  - raw malformed response headers.
- Adds named loopback scenarios for:
  - valid, invalid, ignored, and unsatisfied ranges,
  - redirect chains,
  - expiring URLs,
  - changed ETags,
  - interrupted transfers,
  - chunked responses,
  - incorrect content lengths,
  - Basic authentication,
  - proxy authentication failures,
  - rate limiting,
  - TLS trust failures,
  - 5 TiB logical files,
  - malformed filenames and headers,
  - HLS and DASH manifests and segments.
- Adds 17 laboratory behavior tests and 7 real download-engine integration tests.
- Restores real-socket resume-integrity coverage that later full-file overlays had displaced.

## Warning cleanup

- Uses `string[]` for the concrete drag-and-drop path collection (`CA1859`).
- Adds a public parameterless `MiniWindow` constructor for Avalonia runtime loading (`AVLN3001`).
- Reuses a static expected-ID array in productivity tests (`CA1861`).
- Passes `TestContext.Current.CancellationToken` through asynchronous productivity tests (`xUnit1051`).

## Validation

The artifact requests restore, build, and all tests for `app/XDM/XDM.Modern.sln` and is
intended to finish with zero warnings and zero errors.

## Compile correction

- Uses `paths.Length` after the drag-and-drop collection was intentionally materialized as a `string[]`; this fixes the `CS0019` method-group comparison reported by the first artifact.
