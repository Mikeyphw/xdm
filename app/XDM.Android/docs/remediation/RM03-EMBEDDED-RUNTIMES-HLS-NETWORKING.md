# RM03 — Embedded Runtimes + HLS Networking

**Roadmap:** Overlay 3 of 4
**Dependency:** RM02 Persistence + Downloads UX
**Validation:** targeted/static in this intermediate overlay; full signed/device seal is RM04

## Objective

RM03 closes three independent runtime defects observed on Android 16 ARM64 devices:

1. embedded FFmpeg/FFprobe could be an AArch64 PIE yet still carry Linux/Termux dynamic dependencies such as `libz.so.1`;
2. aria2 startup failures did not bind the on-device executable to a dependency-aware package attestation and `Repair aria2` could imply that an immutable packaged linker failure was repairable in place;
3. native HLS validated a hostname and then let OkHttp resolve it again, while transient DNS failures were retried immediately and surfaced as a generic unsafe-resolution failure.

## FFmpeg / FFprobe runtime boundary

`tools/android_elf_runtime.py` parses ELF program headers and the dynamic table directly. It validates `PT_INTERP` and exact `DT_NEEDED` values without trusting host `ldd`/`readelf`. Android payloads reject Linux loaders, known glibc/versioned dependencies and generic versioned SONAMEs such as `lib*.so.N`.

The pinned FFmpeg build now:

- clears inherited `PKG_CONFIG_PATH`;
- exposes only the pinned OpenSSL metadata through `PKG_CONFIG_LIBDIR`;
- explicitly prioritizes the NDK API-level AArch64 sysroot library directory;
- validates the completed FFmpeg and FFprobe ELF payloads before installation;
- records each binary's exact `DT_NEEDED` set in the runtime lock.

Verification repeats the ELF policy against installed bytes and APK entries and compares the dependency set with the hash-bound lock. `EmbeddedFfmpegRuntime` also requires the packaged dependency-policy marker and rejects locks that contain a versioned SONAME before declaring the runtime attested.

## aria2 runtime boundary

aria2 lock schema v2 binds:

- packaged binary SHA-256;
- ARM64 ABI;
- Android dynamic-dependency policy;
- exact `DT_NEEDED` list;
- ELF interpreter.

The installer and verifier both enforce the shared ELF policy. At runtime, `AndroidAria2CapabilityProbe` hashes the installed `nativeLibraryDir` binary and refuses availability unless the packaged lock matches the binary, ABI and dependency policy.

Startup diagnostics retain the failure class, exit code, bounded log tail, binary path, ABI, SHA-256, needed libraries and recovery hint. Runtime-state/RPC failures remain repairable by stopping XDM's process, clearing transient launch state and runtime lease, sanitizing saved session ownership, rotating the log and private RPC secret, re-attesting the unchanged packaged binary and restarting.

A `BinaryLoadFailure` is deliberately different: Android-installed native code is immutable. `Repair aria2` no longer pretends it can rewrite the payload; diagnostics instruct the user to install/update an attested XDM build and rerun the lifecycle test.

## HLS DNS and redirect security

`AndroidTransferRequestSecurityGuard` now has a typed `validateAndResolveTarget` path. A target is parsed, exact-URL approval scopes are checked, DNS receives a short bounded retry window, and the exact approved address list is returned as `ValidatedTransferNetworkTarget`.

`NativeHlsMediaManager` uses those addresses in a request-specific OkHttp `Dns` implementation. The transport therefore connects only to the addresses that passed policy instead of performing a second DNS lookup after validation. Redirects remain explicit (`followRedirects=false`): every redirect target is resolved and validated again before a request is sent, and sensitive headers remain stripped on cross-origin transitions.

This preserves private/local/link-local/multicast/CGNAT/ULA protections and exact private-network approval scopes while permitting legitimate public CDN hostname transitions. Transient DNS failures are classified separately from unsafe routes and receive bounded backoff at both DNS-validation and HLS-part retry layers.

## Regression coverage

- transient DNS failure then public success;
- public origin -> public CDN transition;
- private resolved route rejected without exact approval;
- exact approved private route accepted;
- DNS failure classification remains distinct from unsafe-target classification;
- aria2 linker failure cannot invoke an in-place binary repair or rotate secrets;
- aria2 diagnostics retain attested binary/dependency evidence;
- static ELF policy rejects `libz.so.1`/versioned SONAMEs and accepts ordinary Android SONAMEs;
- FFmpeg/aria2 install + verify scripts and Gradle inputs are bound to the shared ELF policy;
- native HLS consumes the exact validated address set and retains explicit redirect revalidation.

## Release boundary

RM03 does not claim that optional runtime bytes are present in a source-only checkout. Source-only verification may report FFmpeg/aria2 payloads absent. A publishable build must still create/obtain the pinned payloads and pass strict installed/APK verification plus the full RM04 device/release evidence matrix.
