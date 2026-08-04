# Bug Hunt Phase 11 — Validation Matrix

Phase 11 turns the remediation roadmap's final validation matrix into an executable release gate.

## Source of truth

- Roadmap section: `docs/ANDROID_BUG_HUNT_REMEDIATION_ROADMAP.md`, `Phase 11: Validation Matrix`
- Machine-readable matrix: `tools/bug-hunt-phase11-validation-matrix.json`
- Static validator: `tools/validate-bug-hunt-phase11-validation-matrix.py`
- Gate runner: `tools/run-bug-hunt-phase11-validation-matrix.sh`
- Contract test: `app/src/test/kotlin/com/mikeyphw/xdm/android/BugHuntPhase11ValidationMatrixContractTest.kt`

The matrix contains all **80 roadmap requirements**, from `BH11-001` through `BH11-080`. Each row records the original roadmap text, an execution level, concrete evidence files, required validators, required Gradle tasks, and the owning gate command.

## Acceptance contract

A Phase 11 row is complete only when it has executable evidence. Documentation alone is never enough. The validator fails rows that are missing test/tool evidence, missing files, missing validators, missing Gradle task mapping, or missing CI/gate invocation.

The gate has three target modes:

```bash
bash tools/run-bug-hunt-phase11-validation-matrix.sh --static-only
bash tools/run-bug-hunt-phase11-validation-matrix.sh --device-only
bash tools/run-bug-hunt-phase11-validation-matrix.sh --release-only
```

The default mode runs static validators and the common JVM/unit matrix. Device and signed-release gates remain separate because they require a connected Android device/emulator and release signing inputs.

## Current schema note

The uploaded roadmap still mentions long-path migration validation to schema 14 in one older row. The app's current Room schema is 17 after the landed post-processing publication-journal work. Phase 11 preserves the roadmap wording in the matrix and binds the executable gate to the current schema 17 exports and migrations.

## CI integration

Android CI runs the static Phase 11 matrix gate before the debug Gradle matrix. Signed release validation continues through the Phase 10 release gate and is referenced by the Phase 11 matrix rows that require APK/AAB signing, bundletool, install/upgrade, 16 KB alignment, and publication attestation.

## R2 evidence-closure rule

The validator also rejects **self-only Phase 11 evidence**. A row may reference the matrix and runner for traceability, but it must also include at least one phase-specific executable test outside Phase 11 and at least one phase-specific validator beyond `validate-bug-hunt-phase11-validation-matrix.py`.

The r2 gap closure explicitly repaired the six rows that were previously self-referential only: `BH11-003`, `BH11-014`, `BH11-019`, `BH11-032`, `BH11-040`, and `BH11-047`.
