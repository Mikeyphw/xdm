# XDM Android RM01 — Transfer Core / Recovery Integrity Report

Status: **implemented and statically sealed in source; packaged as Devtool overlay**  
Roadmap: **Overlay 1 of 4**

## Root cause

The observed external-handoff failure was a post-transfer publication failure, not a network transfer failure. Backend preparation could occur while `DownloadRequest.attemptGeneration` was still `0`/stale. Destination staging and the publication closure were created from that request. Durable backend ownership then allocated another generation. Once all bytes were transferred, publication enforced its positive/generation-bound contract and failed.

This also exposed a broader ordering problem: durable Download generation was updated only after backend startup, so a writer could theoretically begin before the Download row carried the exact ownership generation. Backend migration had the same prepare-before-generation-allocation shape.

## Implementation

RM01 now reserves the ownership generation before any backend preparation and uses the exact same value through preparation, ownership claim/adoption/transfer, backend task creation, durable Download persistence, activation, and publication.

Key changes:

- `BackendOwnershipStore.reserveGeneration()` added for in-memory and Room stores.
- `claim`, `adopt`, and `transfer` can consume an exact reserved generation.
- `BackendCoordinator` reserves before preparation and supports a `beforeActivation` durability fence.
- `TransferExecutionRuntime` persists the owned positive generation before Native/aria2 activation.
- `BackendMigrationCoordinator` reserves its target generation before target preparation and persists it before activation.
- `DestinationWriter.prepareExisting()` allows safe re-binding of an already reconciled staging set.
- Native adoption preserves the exact physical artifact set and rewrites the existing checkpoint to the new ownership generation before activation.
- Publication recovery continues to reuse completed staging data rather than requiring a network redownload.

## Safety properties

After RM01:

- no newly coordinated execution is intentionally prepared with attempt generation `0`;
- prepare, ownership, add, durable Download state, activation, and publication share one generation;
- one failed pre-activation durability step cannot start payload writing;
- Native resumable adoption cannot silently switch to a different physical staging artifact;
- migration target preparation is generation-bound before source ownership is transferred;
- completed staging data remains usable for publication-only recovery.

## Tests added or strengthened

- `BackendCoordinatorTest.generationIsReservedBeforePreparationAndBoundBeforeActivation`
- `BackendCoordinatorTest.beforeActivationFailureNeverStartsPayloadWriterAndPreservesRecoveryOwnership`
- `TransferExecutionRuntimeTest.executionPersistsReservedGenerationBeforeBackendActivation`
- migration generation/persistence-before-activation assertions in `BackendMigrationCoordinatorTest`
- `BackendOwnershipStoreTest.reservedGenerationIsExactAcrossPreparationClaimAndAdoption`
- `NativeHttpDownloadBackendTest.reconciledNativeArtifactsRebindToNewGenerationWithoutPayloadCopy`
- retained `NativeHttpDownloadBackendTest.finalSaveRecoveryRetriesPublicationWithoutNetworkRedownload`

## Validation performed in the build container

Passed static/source contracts after the final ownership-snapshot fencing change:

- `tools/validate-ownership-hardening.py`
- `tools/validate-xar03-persistence-generation-integrity.py`
- `tools/validate-xar06-storage-publication.py`
- `tools/validate-xar09-scheduler-recovery.py`
- `tools/validate-rm01-transfer-core-recovery.py`

The targeted Gradle unit-test command was attempted, but this execution environment has no cached Gradle 9.7.1 distribution and no network access to `services.gradle.org`. Therefore this report does **not** claim that the Gradle/Kotlin test suites ran here. The added tests are part of the overlay and should run in the user's normal Android/Termux Devtool environment.

## Exit criteria represented by this overlay

- positive generation exists before preparation;
- exact generation is durable before activation;
- recovery/adoption is idempotently fenced by current ownership;
- completed Native staging can survive publication failure and be reused;
- migration cannot prepare one generation and own another;
- RM01 remains part of the final release static gate.


## Devtool overlay

This work is packaged as roadmap **Overlay 1 of 4** for target `xdm_android`. The artifact is designed for atomic conflict-checked application against the supplied 2026-09-16 repository snapshot. Intermediate Gradle validation remains deferred by campaign policy; the final roadmap overlay performs the full release seal.
