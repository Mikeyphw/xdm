# Phase 6 — Embedded aria2 backend

XDM Android owns the aria2 process, every GID, and every destination claim. The backend is optional at runtime, but when installed it is a complete on-device backend rather than a remote-control client.

## Activation invariant

A new GID is created paused. XDM then persists the Room mapping, writes private ownership metadata, attaches the ownership generation transactionally, and only then calls `aria2.unpause`. A failure before activation removes the GID and leaves no writer behind.

## Durable identity

Schema v6 records the GID, source and mirrors, destination identity, physical output and control paths, session path, expected length, ownership generation, installation identity, process-session identity, synchronization time, and redacted errors. Legacy rows are discarded because they cannot be adopted safely.

## Recovery

Startup reconciles Room, destination ownership, the authenticated loopback RPC service, the saved session, GID source URIs, output path, expected size, backend installation identity, and ownership generation. Verified active or paused tasks are re-observed. Conflicts are quarantined. Artifact-only jobs become explicit recovery records and never switch to the native backend silently.

## Completion

aria2 completion is provisional. XDM validates the owned staging file against aria2 and expected lengths, emits verification/finalization states, and promotes only the owned staging artifact through the destination abstraction. Full cryptographic checksum and selective-repair policy remains Phase 8.

## Runtime packaging

The official ARM64 Android release is installed with `tools/install-aria2-runtime.py`. The installer accepts a local archive or downloads the official release, validates the payload and ELF64 AArch64 header, then enforces the same Android `PT_INTERP`/`DT_NEEDED` policy used by the embedded media runtime before installing it under `jniLibs/arm64-v8a/libaria2c.so`. Runtime lock schema v2 binds the binary digest, ABI, dependency policy, exact needed-library set, and ELF interpreter. `tools/verify-aria2-runtime.py --require-payload` rechecks those properties from the installed bytes and can prove that the APK contains the exact attested payload. Ordinary source/debug builds remain native-only when the optional payload is absent.

At runtime the capability probe hashes the installed `nativeLibraryDir` executable and requires that hash, ABI, dependency policy, and dependency list to match the packaged lock before it advertises aria2 as available. Startup diagnostics preserve the failure class, exit code, bounded runtime-log tail, binary path/ABI/hash, dependency list, and a recovery hint. `Repair aria2` repairs app-owned transient state (launch configs, saved-session ownership rows, runtime lease/log, and the private RPC secret) and then re-attests the same packaged binary before restart. A linker/binary-load failure is deliberately **not** presented as repairable in-place because Android-installed native payloads are immutable; that state instructs the user to install/update an attested XDM build instead.

The RPC service binds only to `127.0.0.1`, uses a random installation secret, never logs the secret, and saves its session before managed shutdown.
