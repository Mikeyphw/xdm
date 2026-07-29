# Debug Workbench D2 — Shell

D2 adds the first user-facing Debug Workbench page under **Settings → Debug Workbench**. It is a shell over the D1 event recorder foundation and does not add download execution, background probing, or automatic uploads.

## Scope

- Settings secondary destination: `SettingsPanel.DebugWorkbench`.
- Live status card for recorder, redaction, support-bundle, instrumentation, and developer-boundary checks.
- Session controls that copy a redacted debug status summary and the existing support report.
- Support bundle readiness card describing the D1 local ZIP skeleton.
- Runtime self-check list driven by `DebugWorkbenchShellPolicy`.
- App-wide `DebugRecorderProvider` installed in `XdmApplication` with `RollingJsonlDebugEventRecorder` rooted in app-private `files/debug-sessions`.
- ViewModel planners are wired to the recorder so D1 events can actually flow when runtime code emits them.

## Non-goals

- No top-level navigation route.
- No Room migration.
- No automatic upload.
- No arbitrary shell input.
- No network probes or JavaScript scraping.
- No Media Sniffing Lab yet; that is D3.

## Privacy contract

All copy/export surfaces are redacted. The page exposes only copy actions that use current app text and `DebugWorkbenchShellReport.toClipboardReport()`. Support bundles remain user-shared only.

## Next phase

`debug_workbench_phase_d3_media_sniffing_lab` should add a safe manual lab for `MediaSniffingEngine` with paste input, base URL, candidate ranking, rejected-fragment explanations, and copyable sanitized reports.

## r4 assertion compatibility repair

D2 wires the D1 recorder into `MainViewModel`, so the shared sniffer is now constructed with
`MediaSniffingEngine(mediaCaptureService, debugRecorder = debugEventRecorder)` instead of the
pre-D2 exact one-line constructor call. The Phase 47 contract now checks the semantic requirement:
shares, browser-extension automation, batch intake, and external review still route through the
same shared sniffer, whether the constructor is the original form or the recorder-backed D2 form.

The D2 contract suite also checks this compatibility assertion so future shell overlays do not
break the Phase 47 shared-sniffer seal while adding debug instrumentation.
