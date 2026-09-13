# XDM Desktop REM10 — Browser Bridge, Native Host, Request Identity, IPC Lifecycle

## Status

Final REM10 overlay generated from the REM09 source snapshot plus the REM10 WIP snapshot contained in the handoff ZIP. This completes the browser bridge / native host / request identity / single-instance IPC / shutdown lifecycle remediation slice.

## Completed scope

- Browser capture identity is first-class across the extension, loopback service, app acknowledgement path, and download engine. Browser request IDs are now preferred over local capture IDs for download idempotency when both are present.
- POST captures are fail-closed when the browser cannot provide an exact replay body, preventing silent conversion of POST downloads into incorrect GET downloads.
- Browser capture metadata carries source page and expected file size into `DownloadRequest`, so desktop admission can keep the original page context and size expectation.
- Native messaging protocol advertises REM10 bridge/lifecycle capabilities and keeps batch acknowledgements isolated per item, so a later malformed batch item cannot erase earlier successful acknowledgements.
- Native host HTTP calls use explicit per-request budgets instead of one unbounded client timeout.
- Loopback browser integration tracks in-flight request handlers independently of listener shutdown and waits for accepted/rejected capture decisions during `StopAsync`.
- Single-instance IPC now uses a lock-token acknowledgement handshake. Secondary instances only report successful activation after the primary proves it consumed the correct token.
- Stale-lock recovery, poisoned activation-port behavior, idle activation clients, durable browser acknowledgement replay, browser manifest metadata, and extension startup/request-metadata behavior are covered by tests/contracts.

## Notable fixes added while completing the WIP

- Fixed `MainWindowViewModel` to prefer `request.BrowserRequestId` before the local `request.RequestId` when constructing `DownloadRequest`.
- Restored `DownloadSession.BrowserRequestId` during persistence rollback so the durable idempotency key is not lost after failed mutations.
- Added a one-byte acknowledgement handshake to `SingleInstanceCoordinator.SignalPrimaryAsync` / `ListenAsync`.
- Changed loopback request handling so listener shutdown does not cancel an already accepted HTTP capture handler before it has returned its acknowledgement.
- Added REM10 capability assertions and lifecycle tests for poisoned single-instance activation ports and in-flight loopback shutdown.

## Validation performed in this container

The container does not have the .NET SDK installed, so `dotnet test` could not be run here. Static/package checks performed successfully:

```text
json_ok=45 json_bad=0
sentinel_checks_ok
node --check app/XDM/chrome-extension/app.js
node --check app/XDM/chrome-extension/connector.js
node --check app/XDM/firefox-amo/app/app.js
node --check app/XDM/firefox-amo/app/connector.js
node_syntax_ok
```

## Recommended apply command

```bash
cd "$HOME/Code/xdm"

gradle --stop || true

devtool --copy --target xdm_modern apply overlay   /sdcard/Download/xdm_desktop_rem10_browser_bridge_ipc_lifecycle_v1.tar.gz   --no-validate   --resource-profile standard   --max-workers 4   --cpu-limit 4   --gradle-heap-mb 2048   --gradle-metaspace-mb 768   --memory-guard-mb 0   --parallel
```

## Optional targeted test command after applying, when .NET SDK is available

```bash
cd "$HOME/Code/xdm"

dotnet test app/XDM/XDM.Modern.sln   --filter "FullyQualifiedName~BrowserCaptureProtocolTests|FullyQualifiedName~BrowserNativeProtocolTests|FullyQualifiedName~BrowserHostInstallerTests|FullyQualifiedName~BrowserExtensionSecurityTests|FullyQualifiedName~LoopbackAcknowledgementTests|FullyQualifiedName~SingleInstanceCoordinatorTests|FullyQualifiedName~DownloadManagerTests|FullyQualifiedName~BrowserCaptureAcknowledgementStoreTests"
```

## Roadmap continuation

Continue with REM11 after this overlay applies cleanly. Preserve the remediation campaign rule from the handoff: REM10 through REM17 are intermediate overlays and should use `--no-validate`; REM18 is the final validation seal.
