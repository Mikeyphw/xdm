# XGO RM08 canonical contract reconciliation v20

## Trigger

The Gradle task `:app:verifyRm08Aria2PackagedExecutionContract` failed before any native rebuild because its Python verifier still required the pre-v16 implementation token `tellStatus(recoveryGid)`.

## Findings

1. RM08 v16 intentionally rewrote recovery around bounded `awaitStatus(...)`, durable session-file observation, `recoveredRpc::tellStatus`, and cleanup observation. The canonical validator had not been migrated with that test redesign.
2. Once that stale token was corrected, the same canonical verifier exposed a second drift: `install-ffmpeg-runtime.py` no longer suppressed Clang default configuration files for the Termux-hosted Android cross-driver, despite the RM08 v8 contract and report requiring that isolation.

## Remediation

- Replace the obsolete `recoveryGid` implementation-name assertion with semantic recovery assertions covering checkpoint durability, restart, recovered RPC status, and cleanup.
- Restore `--no-default-config` as the first compiler-wrapper argument before the explicit Android target/sysroot/runtime flags.
- Add a focused v20 validator that runs the canonical RM08 verifier end-to-end.

No aria2 native link flags, Gate-12 workflow ordering, or instrumentation test behavior are changed by v20.
