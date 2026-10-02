# XGO RM08 Direct Contract Boundary v23

The `xgo_rm08_aria2_source` workflow previously used `xgo_rm08_aria2_contract` as an Android-runner bootstrap even though that target only executed the Python static verifier `validate-rm08-aria2-packaged-execution.py` through a Gradle `Exec` task.

The 2026-10-02 source-validation run failed before any Gradle task executed: the single-use Gradle daemon received an external stop command while calculating the task graph. Devtool telemetry reported `executions=0` and `completed=0`, so the native RM08 v22 source build never ran.

v23 makes the static contract truthful and bounded:

- `xgo_rm08_aria2_contract` is a Devtool command target.
- Its only validation node directly runs the canonical Python verifier.
- It declares no Android/Gradle runner configuration, Gradle tasks, or build outputs.
- `xgo_rm08_aria2_source` runs that contract first, then its existing direct `build_pinned` native job.
- The native builder remains responsible for pinned NDK/toolchain discovery and fails closed when its Android toolchain authority is unavailable.

This does not change v22's native link recipe, Gate-12 ordering, package attestation, device instrumentation, or the canonical Gradle verification task itself. It removes Gradle only as an unnecessary transport for a Python-only preflight boundary.
