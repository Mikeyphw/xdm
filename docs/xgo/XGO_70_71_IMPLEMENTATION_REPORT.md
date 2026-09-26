# XGO-70 + XGO-71 Implementation Report

## Merge decision

XGO-70 and XGO-71 are intentionally delivered together because both are Android host-boundary prerequisites around the single Go engine. XGO-72 remains separate because it starts UI/ViewModel projection and command reconnection.

## XGO-70 — Android network/security platform broker

Delivered production wiring, not a detached model:

- `AndroidNetworkPolicyBroker` calls `NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted(host)` for real cleartext policy.
- Runtime facts come from `ConnectivityManager`, `NetworkCapabilities`, `BatteryManager`, sticky battery state, and `StatFs`.
- System proxy facts come from `ConnectivityManager.defaultProxy`, including PAC URL, manual host/port, and exclusion list.
- TLS facts probe `AndroidCAStore`; network-security-config remains Android's policy layer.
- `AndroidMediaRequestCredentialSource` resolves header SecretRefs from the existing `MediaRequestHandoffStore`, whose durable implementation uses AndroidKeyStore AES/GCM.
- `AndroidSecretResolution.toString()` deliberately excludes the raw value.
- `AndroidPlatformRequestDispatcher` handles `runtime_conditions`, `network_policy`, `system_proxy`, and `secret_lookup` `platform.request` frames and returns API-v1 `platformReply` envelopes.
- `AndroidEngineProcessAuthority` owns the frame pump, so those platform requests are reachable from the live JNI engine rather than dead helper classes.

SecretRef format for current request credentials:

`media-request/<download|capture|variant|command>/<id>/header/<header-name>`

Scope and generation are checked against the encrypted request handoff before a value is returned.

## XGO-71 — Android scheduler host authority

The production Android scheduler entry points now delegate execution opportunities to one Go engine:

- `QueueIntelligenceWorker` no longer calls `evaluateAndClaim`, authorizes claims, or executes transfers.
- `TransferRestoreWorker` no longer performs Kotlin recovery; boot/package replacement wakes/restores Go first.
- `TransferBootReceiver` no longer chains a second Kotlin queue-evaluation worker.
- FGS `ACTION_START` only hosts/wakes Go.
- `UserInitiatedTransferJobService` only establishes the legal Android job/notification host and wakes Go.
- Automatic `QueueIntelligenceCoordinator.reconcile()` no longer calls `evaluateAndClaim()` or `TransferExecutionStarter.start()`.
- Existing manual/user-command compatibility paths remain for XGO-72 rather than being deleted prematurely.
- The process authority converts every Android wake into the real API-v1 `android.scheduler_wake` command.
- The Go runtime registers that command, requests `runtime_conditions` through the platform broker, evaluates the host opportunity in Go, and emits `android.scheduler.decision`.
- Duplicate fences exist in both the process authority and the mutex-protected Go Android scheduler host. WorkManager also retains unique-work coalescing.
- Android retry scheduling accepts only an absolute deadline already supplied by Go; no Kotlin backoff calculation is introduced.

## Validation

The XGO Android validation graph now contains eight targeted nodes. XGO-70/71 add/strengthen:

- `android_network_security`
- `android_scheduler_authority`
- `android_host_runtime_tests` (`go test ./engine/androidhost ./engine/runtime`)

The specialized audits inspect the actual production worker/service/job/application/engine files, not only the newly-added model files. They reject the placeholder proxy/cleartext implementation and reject old Kotlin scheduler authority on the automatic execution paths.

Local audit-loop evidence before packaging:

- all six current `xgo-android-audit` modes pass;
- `go test ./engine/androidhost ./engine/runtime` passes, including the real scheduler wake -> platform conditions -> Go decision round trip;
- full `go test ./engine/...` and `go vet ./engine/...` pass;
- capability ledger, fixture lint, and fixture secret scan pass;
- the retained XAR09 scheduler/recovery source contract was rebased to the XGO-71 authority boundary and passes;
- local Android Gradle compilation was not counted as passed because the isolated build environment had no cached Gradle 9.7.1 and could not resolve `services.gradle.org`. Full Android build/seal remains a GATE-10 responsibility; the overlay itself keeps validation mandatory and non-deferred.

## Deferred by roadmap

- XGO-72: UI/ViewModels reconnect to Go projections and commands.
- XGO-73: support bundle authority moves to Go.
- XGO-74: legacy Room -> Go import and dual-write removal.
- XGO-75: delete/reduce obsolete authoritative Kotlin paths and run the Android authority seal.
