# XDM Android Gradle Task-Graph Optimization

## Scope

This overlay optimizes XDM Android's repeated Devtool/Gradle validation without removing a release check.
It targets duplicated static validators, always-dirty runtime installation, split-phase duplication, and
unnecessary heavyweight ordering on ARM64 Termux.

## Changes

- Gradle now owns the FF01 → FF02 → FF03 prerequisite chain. FF04 and the post-seal audit share that DAG
  and use `--skip-prerequisites` internally, so downstream Python validators no longer recursively rerun it.
- Static validator tasks declare repository inputs and per-task success stamps, enabling ordinary Gradle
  up-to-date reuse across Devtool's split Gradle phases.
- `finalRemediationStaticGate` keeps the historical `run-final-release-gate.sh --ci` invocation but exports
  `XDM_GRADLE_ORCHESTRATED=1`; the shell gate skips only the FFmpeg/execution validators Gradle already ran.
  Direct shell use stays complete and executes the same FFmpeg chain once in flattened order.
- `installOfficialAria2Runtime` now has real inputs/outputs, JNI packaging depends on it, and the installer
  reuses a deterministic URL/version-keyed download cache. Cached raw ELF payloads are ABI/header-validated
  before reuse and corrupt entries are discarded/redownloaded. The forced `outputs.upToDateWhen { false }` was removed.
- Devtool's `build` and `package` phases no longer list both native installers and `assembleDebug` redundantly.
  Packaging correctness is owned by Gradle dependencies; `build` assembles debug, while `package` owns the
  Android-test APK and FFmpeg APK attestation.
- FFmpeg runtime verification, exact debug-APK FFmpeg attestation, and aria2 runtime verification now declare
  their exact payload/lock/script inputs plus success-marker outputs, so unchanged verification work is reusable
  across Devtool split phases without weakening byte-level attestation.
- Firefox `jsTest` and rendered-extension validation now have source/script inputs plus success-marker outputs,
  allowing unchanged checks to become UP-TO-DATE in later split phases.
- The FF04 lifecycle no longer serializes app tests → Android-test APK → APK attestation → static gate. These
  independent roots may overlap. Lint remains ordered after the static/runtime gate to preserve the known
  generated-JNI race fix.

## Coverage preservation

The canonical final shell gate still contains and validates FF01, FF02, FF03, execution/media semantics, FF04,
and the post-seal roadmap audit. Standalone validator invocation remains self-contained by default. Only callers
that explicitly own the prerequisite DAG use `--skip-prerequisites`.

New executable regression coverage:

- `tools/validate-gradle-task-graph-optimization.py`
- `tools/test-aria2-runtime-installer-cache.py`
- `GradleTaskGraphOptimizationContractTest`

## Expected effect

The largest gain is from eliminating recursive FFmpeg validator subprocesses and from preventing repeated aria2
network/install work. Unchanged static and Firefox checks can now be reused by Gradle across split phases. No
claim is made that Android compilation/lint itself becomes cheaper; the optimization removes orchestration waste
around those authoritative tasks.

## Local orchestration benchmark

On the supplied post-v7 snapshot in the analysis container, running the recursive FF04 + post-seal validators
took about **13.62 s**. Running the equivalent flattened FF01/FF02/FF03/execution/FF04/post-seal chain took
about **5.06 s**, a **62.8% reduction (2.69× faster)** in that static FFmpeg validation slice. This does not predict the
full Termux build duration; compile, lint, APK packaging, and device work remain authoritative costs.

## v2 real-Gradle correction

The first Termux validation of optimization v1 reached Gradle 9.7 work validation and exposed two overlay-specific defects. `verifyFfmpegRuntime` and `verifyAria2Runtime` declared the generated native binaries/lockfiles as inputs while their installer tasks declared the same files as outputs, but the verifier tasks did not always carry an explicit producer dependency. Gradle therefore rejected the graph as an implicit-dependency hazard. v2 makes both verifier tasks depend directly on their installer task; because the installers have real inputs/outputs, unchanged payloads remain `UP-TO-DATE` instead of rebuilding.

The same run compiled `GradleTaskGraphOptimizationContractTest` but emitted nullable Java-interop warnings for `System.getProperty("user.dir")` and nullable parent traversal. v2 resolves both through `requireNotNull`/`firstOrNull` with diagnostic errors. The optimization validator now enforces these corrections so this exact failure cannot recur while the graph still claims optimized status.
