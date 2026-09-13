# XDM Desktop REM17 — Updater, Rollback, Packaging, Signing, Release Metadata, and Distribution Trust

Overlay: REM17, overlay 17 of 18
Artifact: xdm_desktop_rem17_updater_rollback_release_trust_v1.tar.gz
Target: xdm_modern
Validation mode: intermediate overlay; apply with --no-validate and reserve the full gate for REM18.

## Scope

REM17 owns the frozen S15 release/update findings: crash-safe updater recovery, delayed health marking, package-manager separation, Debian native dependency declarations, official Windows signing enforcement, release metadata commit/tag binding, bounded updater waits, staging cleanup, malformed manifest containment, mandatory-update UX, release metadata validation, and parity documentation alignment.

## Implementation summary

- Extended update manifest, package descriptor, verification receipt, check result, and transaction documents with release commit/tag, install model, signature/provenance, recovery marker, and observed-health fields.
- Changed update package selection to prefer portable self-update ZIPs and treat .deb/.rpm artifacts as package-manager owned instead of letting the in-app updater mutate package-owned installations.
- Added package-managed install protection for Linux package roots and the `XDM_PACKAGE_MANAGED=1` environment guard.
- Changed updater apply into an externally recoverable transaction: Applying is persisted before destructive swaps, recovery markers are written beside the install root, interrupted swaps restore backup before continuing, and old PID waits are bounded.
- Delayed Healthy marking until the transaction `healthyAfterUtc` observation window elapses; the app now runs the health marker in a background task instead of marking immediately during framework initialization.
- Added staging lifecycle pruning for stale downloads, runners, and older update version directories.
- Added mandatory-update state and an assertive Settings warning, with mandatory-specific stage/apply status text.
- Hardened stable release metadata generation with semantic-version validation, full commit SHA validation, exact stable tag binding, manifest `releaseCommitSha`/`releaseTag`, and per-package commit/install-model metadata.
- Made official Windows packaging/signing fail closed through workflow checks and `verify-windows-signatures.ps1`.
- Added Debian native dependency declarations for Avalonia/.NET desktop runtime support.
- Updated parity and release documentation so it no longer claims staged packages are never executed.

## Test/contract coverage added

- `UpdateTransactionExecutorTests.RestoresBackupWhenInterruptedApplyLeftInstallRootAbsent`
- `UpdateTransactionExecutorTests.MarkHealthyWaitsUntilObservedHealthWindowExpires`
- `VerifiedUpdateServiceTests.SelectsPortableSelfUpdatePackageWhenPackageManagerArtifactIsAlsoPublished`
- `VerifiedUpdateServiceTests.RejectsPackageManagedInstallRootForAutomaticApply`
- `VerifiedUpdateServiceTests.RejectsOfficialWindowsPackageWithoutReleaseTrustMetadata`
- Release infrastructure assertions for commit/tag metadata binding, stable Windows signing enforcement, signature verification script wiring, and Debian dependency declarations.

## Local validation available in this container

The container still does not include the .NET SDK, so `dotnet test` could not be executed here. Static/package checks performed:

- Python release metadata generator compiles with `py_compile`.
- Release metadata generator successfully emits schema-2 stable metadata with commit/tag binding in a local fixture.
- `strings.en.json` and `docs/parity/features.json` parse as JSON.
- C# brace-balance scan passes for modified source and test files.
- Overlay manifest parses as JSON.
- Tar member audit confirms no directory entries, no `.` entry, no empty names, and no unsafe `./` prefixes.

## Recommended post-apply validation before REM18

Run the focused .NET tests if the environment has the SDK:

```bash
dotnet test app/XDM/XDM.Modern.sln -c Release --filter "FullyQualifiedName~UpdateTransactionExecutorTests|FullyQualifiedName~VerifiedUpdateServiceTests|FullyQualifiedName~ReleaseInfrastructureTests"
```

Full cross-platform package/signing/update gates belong to REM18.
