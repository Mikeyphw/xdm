# XDM Android RM03 Implementation Report

**Roadmap:** Overlay 3 of 4 — Embedded Runtimes + HLS Networking
**Date:** 2026-09-16
**Base:** RM02 Persistence + Downloads UX applied

## Implemented

### Android ELF runtime dependency policy
- Added a dependency-free ELF parser for packaged executable payloads.
- Validates `PT_INTERP` and `DT_NEEDED` directly instead of relying on host tooling.
- Rejects Linux/versioned SONAMEs including the observed `libz.so.1` failure class.
- Shared by FFmpeg/FFprobe and aria2 installation/verification.

### Embedded FFmpeg / FFprobe
- Sealed pkg-config so Termux/host metadata cannot leak into the Android cross-build.
- Prioritizes the NDK API-level AArch64 sysroot for `-lz` and other Android system libraries.
- Rejects a generated runtime before installation if Android dependency policy fails.
- Records exact FFmpeg/FFprobe `DT_NEEDED` lists in the provenance lock.
- Re-verifies dependency policy and lock parity for installed runtime bytes and APK payloads.
- Runtime attestation requires the dependency-policy marker and rejects versioned SONAMEs in the packaged lock.

### Embedded aria2
- Raised runtime manifest/lock contract to schema v2.
- Installer/verifier enforce Android ELF dependencies and attest needed libraries + interpreter.
- On-device capability probe verifies installed binary SHA-256, ABI, dependency policy and dependency list against packaged attestation before advertising aria2 as available.
- Startup diagnostics include ABI, binary hash, native dependencies and a recovery hint in addition to exit/log details.
- Runtime-state repair now clears transient state, sanitizes saved session ownership, clears the runtime lease, rotates logs and RPC secret, re-attests the same packaged binary and restarts.
- Binary/linker failures are explicitly non-repairable in place; the UI points to install/update instead of offering a misleading native-binary repair.

### Native HLS networking
- Added bounded typed DNS resolution to the transfer security guard.
- Separates transient DNS resolution failure from unsafe/private resolved routes.
- Returns the exact addresses that passed network policy.
- Native HLS pins OkHttp DNS to that validated address set, closing the validate-then-resolve-again race.
- Every explicit redirect target is revalidated and re-pinned independently.
- Existing cross-origin credential stripping and exact URL private-network approvals remain intact.
- Added bounded retry backoff so transient DNS/CDN failures are not retried immediately in a tight loop.

## Regression coverage
- Scheduler tests for DNS retry, public CDN transition, private route rejection/approval, and typed DNS failure.
- aria2 process-manager regression ensuring linker failures do not trigger fake in-place repair.
- RM03 cross-module/static contract proving ELF policy, runtime attestation, pinned HLS DNS and release wiring.
- Existing XAR08/XAR12/FF01/FF02 contracts remain passing in source-only validation.

## Validation policy
RM03 is an intermediate overlay. Source/static runtime verification and compatibility validators are run here. Full payload build, connected-device lifecycle tests, signed APK evidence, 16 KiB/native symbol evidence, APK-set install/upgrade and final release validation remain assigned to Overlay 4 of 4.

## Next
**Overlay 4 of 4 — Diagnostics / Release Final Seal.**
