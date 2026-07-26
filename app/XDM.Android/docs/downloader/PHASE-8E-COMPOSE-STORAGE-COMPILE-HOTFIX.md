# Phase 8E Compose, Storage, and Test Compile Repair

This cumulative hotfix repairs the target-environment compilation failures found after Phase 8E. It supersedes the earlier Compose/storage-only hotfix and applies directly to the Phase 8E activity-diagnostics tree.

## Compose scope repair

`Modifier.weight` is a scoped `RowScope` or `ColumnScope` extension. Explicitly importing
`androidx.compose.foundation.layout.weight` made Kotlin resolve Compose's internal
`RowColumnParentData.weight` property with the current Kotlin and Compose toolchain. The
invalid imports are removed while every existing scoped `.weight(1f)` call remains in its
`Row` or `Column` scope.

## Storage condition monitoring

The deprecated Java fields `Intent.ACTION_DEVICE_STORAGE_LOW` and
`Intent.ACTION_DEVICE_STORAGE_OK` are replaced by their stable platform action strings.
These broadcasts are only reevaluation hints. The queue policy continues to measure actual
available storage before admitting a transfer, so a broadcast never authorizes or blocks a
download by itself.


## Core-model test framework repair

`core-model` is configured with JUnit 4 through `testImplementation(libs.junit)` and
`tasks.test { useJUnit() }`. `OperationalActivityTest.kt` incorrectly imported
`kotlin.test`, but the module does not declare the Kotlin test library. The test now uses
`org.junit.Test` and `org.junit.Assert`, matching every established core-model test and the
module's actual Gradle contract.

## Product boundary

The hotfix does not change routes, manifest intent ownership, download engines, Room schema,
queue policy, activity diagnostics, media resolution, versionCode, or versionName. The
built-in browser remains absent.
## Gradle architecture-contract repair

The Android unit-test gate also exposed source-shape assertions that predated the
Phase 8A–8E downloader refinements. The repaired contracts now assert the current
behavioral seams instead of obsolete formatting:

- external Add drafts enter the `MutableStateFlow` and are projected into `MainUiState`;
- Downloads sorting is owned by `DownloadDashboardOrdering`;
- Add submission is controlled by the review planner and does not require a filename;
- Activity and Library own queue, schedule, recovery, diagnostics, and completed-media surfaces;
- stored routes restore through `AppRoute.restore`; and
- an empty retired `docs/browser` directory is harmless, while any active file inside it fails the contract.

All 56 architecture and browser-removal contract methods pass in the focused JVM harness.
