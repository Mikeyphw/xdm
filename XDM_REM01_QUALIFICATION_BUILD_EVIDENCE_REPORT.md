# XDM Desktop REM01 — Qualification/build/evidence foundation

Closes S00-01 through S00-07.

- Bootstrap qualification now validates the exact nine-section navigation topology.
- Release builds enforce warnings as errors and PowerShell validation explicitly fails on every native non-zero exit.
- The repository SDK is pinned to the .NET 10.0.100 feature band with latest-patch roll-forward instead of latest-major drift.
- CI package qualification covers linux-x64, linux-arm64, win-x64, and win-arm64. Native-architecture packages execute bootstrap/native-host smoke; cross-architecture packages are still fully published and topology-checked.
- Completed parity entries must carry executable automated-test evidence, not only implementation/evidence paths.
- Regression contracts protect bootstrap topology, warning/native-exit enforcement, and the release RID matrix.

REM01 is intentionally an intermediate roadmap overlay. Apply with `--no-validate`; REM18 owns the full final validation/release seal.
