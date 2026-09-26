# GATE-10 Android authoritative-Go overlay contract

- Target: `xgo_gate_android`.
- Validation is required, cannot be deferred, and must not be run with `--no-validate`.
- GATE-10 is a lean integration seal, not a replay of every historical XGO subsystem gate.
- The gate runs lightweight Go hygiene/regression (`gofmt`, full engine test/vet), platform-broker and C-ABI smoke, capability/fixture/topology integrity, the authoritative `xgo_android#validate` boundary suite, then `target:xdm_android#validate` as `android_full_seal`.
- Previously closed subsystem matrices (store/security/transfer/backends/scheduler/media/ops) are not recursively re-run unless a later gate has a concrete dependency on one of them.
- The `xdm_android#validate` reference intentionally uses Devtool MP05 primary-runner fallback: `xdm_android` is an Android runner target and does not need a synthetic named workflow solely to invoke its native validation planner.
- The XGO topology auditor must accept that Devtool-supported fallback while continuing to require explicit workflows for XGO subsystem/gate targets.
- Android build tasks are `clean` and `assembleDebug`; FFmpeg/aria2 runtime installation remains packaging-owned and is verified by Android preflight/package tasks.
- Android validation uses one worker, one CPU, no Gradle daemon, split Gradle phases, and the repository's configured memory guards.
- Historical validators may be rebased only where XGO-70..75 intentionally superseded their old Kotlin authority assumptions. The validators must assert the new Go-command, wake-only scheduler, one-time import, narrow platform broker, and Go media authority boundaries rather than be weakened or skipped.
- Canonical static release validation remains mandatory through the Android runner seal and includes the Phase-11 validation matrix.
- The overlay adds no new product behavior, no dual-write path, no recursive Devtool execution, and no Devtool install/refresh/bootstrap hook.
- A device is not invented as a prerequisite: instrumentation follows `instrumentation = "when-device-present"`; all other configured Android runner phases remain required.
- GATE-10 is separate from XGO-76. The next roadmap implementation starts only after this gate succeeds in the user's repository.
