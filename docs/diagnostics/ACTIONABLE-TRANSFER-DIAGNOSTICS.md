# Actionable transfer diagnostics

## Architecture

Transfer instrumentation is defined in `XDM.Core.Diagnostics` so the download engine does not reference Avalonia, the diagnostics implementation, or support-bundle code.

- `ITransferDiagnosticSink` is the engine-facing write contract.
- `ITransferDiagnosticSource` is the diagnostics-facing read and retention contract.
- `TransferDiagnosticStore` owns bounded in-memory retention and secret redaction.
- `NullTransferDiagnosticSink` keeps engine construction and tests independent of diagnostics.

The engine treats the sink as optional and non-fatal. A diagnostics failure must never stop or alter a transfer.

## Instrumented transfer stages

The structured timeline covers:

1. scheduling admission and policy blocks;
2. backend selection and aria2 ownership;
3. proxy mode and connection-pipeline start;
4. HTTP response metadata;
5. range requests and validated or rejected resume behavior;
6. retry delay and mirror failover;
7. destination-capacity checks and segmented merge state;
8. expected and manual checksum verification;
9. atomic finalization and completion failures.

Each event carries a stable code, severity, transfer identifier, timestamp, human-readable explanation, and a small redacted context map. Source URLs are reduced to path-safe forms before emission, and the diagnostics implementation performs a second redaction pass before retention or export.

## Selected-transfer workbench

The Diagnostics page can select any download and shows up to 250 newest structured events while retaining up to 2,000 events globally. Clearing the selected timeline does not clear unrelated transfer diagnostics or the existing application event log.

## Bounded live health probe

The live probe is separate from transfer instrumentation. It measures:

- DNS resolution;
- direct TCP connection establishment;
- TLS negotiation and normal certificate validation for HTTPS targets;
- the configured `HttpClient` pipeline with a one-byte range request;
- a bounded 1 MiB write, flush, and deletion in the selected destination directory.

Default limits are five seconds per stage and twenty seconds total. The HTTP stage requests only byte `0-0` and uses response-headers completion, while the disk stage always attempts to remove its temporary file. Caller cancellation remains distinguishable from an internal stage or total timeout.

## Support bundle

Support bundles now include:

- `transfer-timeline.json` with redacted structured events;
- `live-health-probe.json` when a probe has run;
- existing sanitized application, download, settings, browser, recovery, and event data.

No engine component knows how events are displayed, retained, or exported.
