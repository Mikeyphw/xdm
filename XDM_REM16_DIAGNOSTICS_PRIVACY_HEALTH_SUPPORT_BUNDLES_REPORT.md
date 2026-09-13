# XDM Desktop REM16 — Diagnostics, Privacy Redaction, Health, Logs, and Support Bundles

**Overlay:** REM16, overlay 16 of 18  
**Artifact:** `xdm_desktop_rem16_diagnostics_privacy_health_support_bundles_v2.tar.gz`  
**Target:** `xdm_modern`  
**Roadmap objective:** Make diagnostics persistent and useful without leaking credentials or lying about health.  
**Apply mode:** Intermediate overlay; validation is intentionally left to later gates via `--no-validate`.
**Repack note:** v2 contains the same REM16 payload, rebuilt without root or directory archive members after Devtool rejected the v1 tar member `.` as an unsafe empty path.

## Scope implemented

REM16 closes the diagnostics/privacy findings owned by the roadmap:

- `S14-01` — support-bundle redaction leaked real credentials.
- `S14-02` — normal download-engine logging bypassed the diagnostics redaction boundary.
- `S14-03` — diagnostic rings were volatile and lost post-crash evidence.
- `S14-04` — support bundles reintroduced full local filesystem paths and user-identifying environment data.
- `S14-05` — browser/extension health remained healthy indefinitely after the extension disappeared.
- `S14-06` — disk probes suppressed cleanup failures while reporting success.
- `S14-07` — independent subsystem checks were not failure-isolated.
- `S14-08` — support-bundle publication was non-atomic.
- `S14-09` — health deadlines did not cover synchronous filesystem/native checks.
- `S16-06` — diagnostics commands could outlive ViewModel/service teardown and touch disposed synchronization primitives.

## Implementation details

### Central diagnostics redaction boundary

Added `XDM.Core.Diagnostics.DiagnosticRedactor` as the shared privacy primitive for code that cannot reference `XDM.Diagnostics` directly. The diagnostics-layer `SecretRedactor` now delegates to that core redactor.

The redactor now handles:

- `Authorization` and `Proxy-Authorization` variants including Basic/Digest/Bearer material.
- `Cookie` and `Set-Cookie` lines.
- `X-XDM-Token`, API key, password, client secret, access token, and refresh token header forms.
- Query secrets including `access_token`, `refresh_token`, `api_key`, `apikey`, `client_secret`, `x-amz-signature`, `x-amz-credential`, and `x-amz-security-token`.
- URI userinfo such as `https://user:pass@example.test/...`.
- Windows and Unix local paths collapsed to `[LOCAL-PATH]/<filename>`.
- Safe origin rendering that never uses `Uri.GetLeftPart(UriPartial.Authority)` and therefore never retains URI userinfo.

### Normal logging privacy

`DownloadEngineLog` now routes source URLs, destination paths, exception messages, retry messages, history persistence failures, and diagnostics-sink failures through the central redactor before writing to Microsoft logging. Source URLs are logged as credential-free origins; destinations are logged as minimized local-path tokens.

`DownloadManager.GetSafeSource` now uses the same safe-origin helper, so transfer diagnostics do not serialize full path/query/userinfo material.

### Durable crash-safe diagnostic rings

`DiagnosticEventStore` and `TransferDiagnosticStore` gained explicit persistent factory constructors used by app DI. The default constructors remain in-memory for tests.

Persistent rings are:

- count bounded (`500` diagnostic events and `2000` transfer events);
- redacted on both write and reload;
- written through temp files then atomically moved over the durable ring file;
- failure tolerant so a diagnostics-store write cannot crash application startup or runtime.

`CrashDiagnosticWiring` now records `AppDomain.CurrentDomain.UnhandledException` and `TaskScheduler.UnobservedTaskException` into the durable diagnostics ring with redacted exception detail.

### Atomic, privacy-normalized support bundles

`DiagnosticBundleService.ExportAsync` now writes to a same-directory `.xdm-finalizing` file first. The final `.zip` path is published only after the ZIP is closed and flushed. Failed exports delete the staging file instead of leaving final-named partial archives.

Every JSON entry is serialized through `DiagnosticRedactor.WriteRedactedJsonAsync`, making privacy normalization a final bundle boundary instead of relying only on individual producers.

The bundle now avoids unsafe origin construction and minimizes browser granted-origin data through credential-free origins.

### Health truthfulness and isolation

`SubsystemHealthService` now runs each subsystem through an independent timeout/error boundary. A failure in native-host status, aria2, FFmpeg, proxy, browser, or destination disk is converted into that subsystem's own unavailable/degraded result instead of aborting the entire refresh.

Browser extension health now expires after a bounded freshness window and rejects future-dated health timestamps beyond small clock skew tolerance. The bridge can no longer remain healthy forever from one old extension heartbeat.

Destination disk checks now report cleanup failures as degraded instead of saying the test file was not retained. FFmpeg executable and destination-directory details are path-minimized before entering diagnostics.

`TransferHealthProbe` now also reports disk probe cleanup failures as warnings and uses safe-origin endpoint metadata.

### ViewModel/service lifecycle ownership

Diagnostics commands are now bound to a ViewModel-owned cancellation source. Refresh, repair, deterministic test download, and bundle export receive that token. `MainWindowViewModel.Dispose()` cancels diagnostics work before event unsubscription.

`SubsystemHealthService` and `DeterministicDownloadTestService` cancel their lifetime tokens during dispose and intentionally avoid disposing their semaphores while in-flight operations may still be unwinding, preventing late `SemaphoreSlim.Release()` or dispatcher continuations from touching disposed synchronization primitives.

## Tests added or expanded

- `SecretRedactorTests` now covers Basic/Digest headers, cookie lines, userinfo URLs, OAuth/API query secrets, local path minimization, and safe origin rendering.
- `DiagnosticEventStoreTests` now proves the durable ring reloads redacted events.
- `TransferDiagnosticStoreTests` now proves the durable transfer timeline reloads without secrets.
- `DiagnosticBundleServiceTests` now proves bundle export creates a final ZIP atomically and redacts combined support-bundle contents.
- `SubsystemHealthServiceTests` now proves stale extension health becomes degraded and one failing subsystem does not abort the whole health snapshot.

## Files changed

See `XDM_REM16_CHANGED_FILES.txt` in the artifact root.

## Local packaging validation

The artifact was checked locally for:

- Devtool manifest JSON validity.
- Declared-file existence.
- Required REM16 sentinel strings.
- Tar extraction and manifest readability.

The container does not include the .NET SDK, so no `dotnet test` result is claimed here.
