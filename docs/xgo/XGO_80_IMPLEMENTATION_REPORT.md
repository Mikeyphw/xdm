# XGO-80 Desktop legacy import and legacy-engine authority cutover

## Outcome

XGO-80 establishes durable Go desktop authority before retiring the superseded C# transfer/media/recovery/scheduler owners from normal production composition. Legacy donor implementations remain in the source tree only so the XGO-81 differential campaign can still execute and classify their behavior; they are no longer instantiated by the normal desktop application path.

## One-time migration

`DesktopLegacyStateImporter` runs after the desktop Go engine starts and before projections initialize. If `go-desktop-state-v1.json` already exists, no legacy state is reread. Otherwise it builds package version 1 from meaningful legacy desktop state:

- download history from `downloads.json`, with `.bak` fallback;
- safe checkpoint summaries from `.xdm.resume.json` files;
- non-secret settings summary;
- queue and schedule identities/names;
- an installation marker so a genuinely empty installation can still establish authority.

Source URLs in the imported UI projection discard userinfo, query strings, and fragments. Secret settings and raw aria2 credentials are never copied into the import package. An unreadable primary download history with no readable backup aborts migration rather than committing an empty cutover.

Go canonicalizes and hashes the package, imports it through `ops.DesktopJSONImportAdapter`, and commits `go-desktop-state-v1.json`. The committed snapshot contains the import identity, imported canonical objects, and the Go-owned desktop UI projection. Replaying the identical package is idempotent. A changed package after cutover is rejected as `legacy_changed_after_cutover`. A failed authority commit leaves the store unimported and can be retried.

## Authority removal

Normal desktop DI/startup no longer instantiates:

- `DownloadManager`;
- `DownloadRecoveryCoordinator`;
- `QueueSchedulerRuntime`;
- stateful `Aria2Service`;
- `MediaDownloadService`.

Compatibility interfaces still required by older Avalonia surfaces are implemented by Go-backed/passive shims. Primary add/pause/resume/cancel/retry verbs route through Go. Removed legacy-only mutations fail explicitly rather than silently recreating authority.

The physical download broker is now a narrow host mechanism. Go supplies canonical download identity, chosen backend, and authoritative backend task reference. Native transfers perform HTTP/file I/O only. aria2 execution uses stateless RPC calls and stores the resulting GID in the Go projection; the host does not keep a second aria2 ownership map. A host-returned download ID cannot replace the canonical Go ID.

The execution observer consumes only broker physical facts and merges them with the current Go projection before reporting observations. It no longer watches `ApplicationState` as a competing transfer-state authority.

## Host discriminator

C ABI create configuration now carries `host_platform`. Android explicitly sends `android`; Avalonia explicitly sends `desktop`. This prevents desktop and Android durable authority stores from being selected implicitly from the same state-path semantics.

## Validation

The `xgo_desktop#validate` workflow has eight focused nodes and ends with `desktop_legacy_authority_scan`. The scan forbids production composition/startup references that instantiate the superseded engines and verifies the one-shot importer, durable Go authority, host discriminator, narrow native/aria2 broker, passive compatibility surfaces, and Go projection path.

Go validation additionally covers representative import, duplicate import, durable reopen, changed-package rejection, interrupted authority commit followed by successful retry, Go-owned add identity, and backend task reference propagation on resume.

Full desktop .NET compilation is intentionally left for GATE-11's cross-host desktop full seal because this implementation environment does not contain a .NET SDK. The donor source/tests remain available for that compile and for XGO-81 differential classification.
