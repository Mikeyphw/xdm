# Subsystem health, repair, and transfer evidence

This slice completes the actionable diagnostics workbench after the selected-transfer timeline and bounded live connection probe.

## Subsystem health matrix

The Diagnostics page can refresh independent checks for:

- browser bridge listening state, extension handshake, and protocol compatibility;
- native-host executable and Firefox/Chromium registration compatibility;
- aria2 enablement, managed-process state, RPC availability, version, task count, and observed RPC latency;
- FFmpeg executable health and detected H.264, H.265, AV1, AAC, MP3, and Opus capabilities;
- normalized proxy mode, endpoint shape, authentication mode, and bypass configuration;
- destination free space plus a bounded 64 KiB write-and-flush test.

Repairs are deliberately narrow. Diagnostics can repair native-host registration and start or reconnect aria2. It does not silently rewrite proxy, FFmpeg, or download settings.

## Deterministic test download

The configured HTTP and proxy pipeline can perform a bounded one-mebibyte test download. The service:

- has a 30-second deadline;
- rejects declared lengths other than one mebibyte;
- stops and fails if more than one mebibyte arrives;
- requires exactly one mebibyte before success;
- computes a local SHA-256 result without retaining the payload;
- supports an HTTPS endpoint override through `XDM_DIAGNOSTIC_TEST_URL` for controlled environments.

## Selected-transfer evidence

The transfer workbench derives:

- an allowlisted response-header view with secret redaction;
- retry history;
- segment ranges, state, and completion bytes;
- an explicit resume-availability explanation.

The download engine still depends only on the `XDM.Core` transfer-diagnostics sink. UI projection, retention, repair orchestration, and support-bundle export remain outside the engine.

## Support bundle

Support bundles now add:

- `subsystem-health.json`;
- `deterministic-download-test.json` when a test has run;
- `transfer-insights.json` containing headers, retries, segment state, and resume reasoning per transfer.

The existing raw structured timeline and bounded live-probe exports remain available.
