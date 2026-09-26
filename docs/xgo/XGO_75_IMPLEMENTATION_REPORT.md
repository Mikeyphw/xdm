# XGO-75 implementation report

## Merge decision

XGO-75 is intentionally standalone. GATE-10 is the separate authoritative-Go qualification gate, and XGO-76 begins the desktop host boundary. Merging either with XGO-75 would mix implementation cleanup with qualification or a different host architecture.

## Delivered

The superseded XGO-72 `AndroidLegacyDownloadUiBroker` is removed. `AndroidDownloadExecutionBroker` replaces it as a narrow platform-side adapter invoked only after a Go `android_download_command`: it can materialize a legacy backend row, pause/cancel active Android owners, retire Android notification IDs, and schedule a correlated wake back into Go. It contains no queue planner, retry ledger, queue coordinator, or Kotlin start-policy call.

`TransferActionReceiver` and `TransferForegroundService` no longer obtain `TransferRuntimeProvider`/`QueueIntelligenceProvider` for user controls. Pause/resume/cancel/retry/pause-all/resume-all submit through `AndroidGoDownloadCommands`, backed by the application-scoped `AndroidGoDownloadCommandHost` and the same `AndroidEngineProcessAuthority.submitDownloadUiCommand` path used by Compose.

`QueueIntelligenceCoordinator` is reduced from a Kotlin policy engine to a compatibility/status facade. Candidate ranking, runtime-condition policy, retry-ledger evaluation, durable admission holds, slot claims, and `TransferExecutionStarter` ownership are removed from it. Compatibility calls either submit a Go command, wake Go, perform metadata-only queue deletion, or return an empty claimed-owner set.

`XdmApplication` no longer constructs `TransferExecutionStarter`, installs/clears Kotlin startup admission holds, or calls `transferRuntime.recoverForStartup()` as a scheduler authority. Startup performs Android-local data migration/monitor setup and wakes Go. Terminal transfer events trigger post-processing/diagnostics and a Go scheduler wake rather than a Kotlin queue decision.

Remaining ViewModel start/retry and automation pause/resume paths were routed through `AndroidDownloadUiClient`; direct calls to Kotlin `requestStart`, `pauseAllDurably`, `resumeAllManual`, and `transferRuntime.pauseAll()` were removed from the ViewModel.

XGO-CAP-ANDROID-007 records the deleted/reduced legacy-authority boundary. XGO-CAP-ANDROID-005 was also advanced from PLANNED to IMPLEMENTED to match the already-landed XGO-74 durable import cutover.

## Validation evidence

`xgo_android#validate` now contains 12 nodes. The new `android_legacy_authority_scan` checks the real application, ViewModel, notification receiver, foreground service, worker, compatibility coordinator, and execution broker for both required Go command wiring and forbidden Kotlin authority references. The exact target graph, all prior Android specialized audits, capability-ledger audit, fixture lint, fixture secret scan, full `go test ./engine/...`, and full `go vet ./engine/...` pass in the implementation worktree.

A full Android Gradle compilation is not claimed from this environment; GATE-10 remains the next separate qualification target after the user applies this overlay successfully.
