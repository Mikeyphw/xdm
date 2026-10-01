# XGO-RM08 — Packaged aria2 Android execution qualification

Date: 2026-09-30
Gate: GATE-12 remediation
Status: implementation complete; authoritative package/device evidence is produced when Gate-12 resumes.

## Outcome

RM08 closes the final aria2 embedding gap left after RM06 runtime/recovery convergence and RM07 source/provenance qualification. Gate-12 can no longer close from source contracts alone: it must materialize XGO's pinned source-built aria2 runtime, package those exact attested bytes into the Android APK, and execute the packaged daemon on a real Android instrumentation target.

## Implemented contract

- `xgo_rm08_aria2_contract` owns bounded apply-time validation through `:app:verifyRm08Aria2PackagedExecutionContract`; it does not trigger the expensive source build or device run.
- `xgo_rm08_aria2_package` owns source materialization, Android packaging, and exact APK-byte attestation. Its `xgo_rm08_aria2_source` child first runs the bounded Android-runner contract so Devtool resolves/bootstrap the SDK and NDK, then launches `tools/build-aria2-runtime.py --build-pinned --jobs 2` as a Devtool-owned command job outside Gradle. The long native build therefore survives unrelated Gradle daemon shutdowns without giving up Android bootstrap authority.
- `xgo_rm08_aria2_device` is a dedicated Android target with `policy = "required"` instrumentation.
- `xgo_rm08_aria2` is the fresh Gate-12 aggregate Android host authority; gate composition remains canonical because every XGO cross-target edge references `#validate`.
- Gate-12 package execution builds aria2 from the RM07 pinned source recipe before APK packaging. The authoritative path is Devtool -> RM08 Android bootstrap contract -> Devtool command job -> `tools/build-aria2-runtime.py --build-pinned --jobs 2`; Gradle resumes only for APK packaging/verification after the native runtime exists.
- `tools/xgo/verify_rm08_aria2_package.py` requires schema-v3 `source-build` provenance, runs the strict aria2 ELF/APK verifier, and proves the APK's `lib/arm64-v8a/libaria2c.so` and embedded runtime lock match the source-tree attestation byte-for-byte.
- Gate-12 device execution uses Devtool's Android instrumentation runner with a required-device policy rather than an ad-hoc ADB shell gate.
- The instrumented test launches the actual executable from `ApplicationInfo.nativeLibraryDir`, verifies runtime-lock attestation and Android dependency policy, verifies authenticated RPC startup, rejects a TLS server signed by an untrusted test CA, downloads successfully from a loopback HTTPS server signed by the injected trusted test CA, checkpoints the aria2 session, performs clean daemon shutdown, restarts the packaged daemon, and proves an XDM-owned paused GID is recovered from the saved session.
- Production still defaults to `AndroidAria2TrustBundleProvider`; RM08 adds only a narrow `Aria2TrustBundleProvider` seam so instrumentation can provide deterministic test CA trust without weakening production certificate validation.

## Gate-12 integration

The checkpoint continuation now routes:

1. Android package refresh through `target:xgo_rm08_aria2_package#validate`.
2. Package attestation into `.devtool/reports/xgo/gates/final-continue/rm08-aria2-package.json`.
3. The retained `gate-final-continue` path is refreshed so its Android resume boundary is: Devtool policy -> topology -> RM08 source build/package/attestation -> APK checkpoint -> RM05 residual precheck -> canonical lint proxy -> required RM08 device execution -> desktop -> final Wave-12 verifier.
4. Its checkpoint/precheck jobs are target-local to `xgo_gate_final_continue`, and all cross-target edges use canonical `target:<name>#validate` refs.
5. Remaining Android instrumentation runs through `target:xgo_rm08_aria2_device#validate`, where a missing device is a failure rather than a skip.
6. The final Wave-12 verifier requires both the RM08 package receipt and a successful non-empty Android instrumentation receipt.

A fresh `xgo_gate_final` run routes its Android host node through `target:xgo_rm08_aria2#validate`.

## Local construction evidence

- RM08 static contract validator: PASS.
- Full XGO topology audit: PASS.
- `xgo_gate_final_continue` target-ready topology audit: PASS.
- RM08 source-execution regression: PASS; source materialization must bootstrap through the Android runner first, then execute the long native build as a bounded Devtool command job outside Gradle.
- Gate-12 auditor regressions: 5 passed.
- Python compile checks for the new/changed Gate-12 verification scripts: PASS.

The actual source build, APK-byte receipt, and Android packaged-runtime execution are intentionally deferred to the existing authoritative Gate-12 host boundary.

## RM08 v3 source-bootstrap repair

The first Gate-12 continuation attempt after RM08 v2 exposed an ownership defect: `xgo_rm08_aria2_source` was a generic command target that invoked `tools/build-aria2-runtime.py --build-pinned` directly. That bypassed Devtool's Android environment/bootstrap layer, so SDK/NDK discovery could fail even though later Gradle/package nodes were correctly Android-runner-owned.

RM08 v3 keeps the continuation graph unchanged but repairs the child boundary itself:

```text
xgo_gate_final_continue
  -> target:xgo_rm08_aria2_package#validate
     -> target:xgo_rm08_aria2_source#validate
        -> runner:android#build
           -> :transfer-aria2:buildPinnedAria2Runtime
              -> tools/build-aria2-runtime.py --build-pinned
```

The static RM08 validator now fails closed if source materialization returns to a generic command job or stops using the exact Gradle task. This ensures Devtool resolves and bootstraps Android SDK/NDK/toolchain state before the source builder executes.

## RM08 v4 shared OpenSSL dependency cache

The first Android-runner-owned continuation reached the pinned aria2 source build correctly, but then spent several minutes compiling OpenSSL even though XGO's FFmpeg runtime pins the exact same OpenSSL 3.5.8 source, Android API 26, NDK 29.0.14206865 and measured ARM64 toolchain authority. RM08 v4 removes that duplicated native dependency build.

`tools/android_native_openssl.py` is now the consumer-neutral OpenSSL authority. Its cache identity binds the exact OpenSSL source digest/URL, ABI, Android API, measured NDK provenance, configure profile and measured toolchain identity. FFmpeg and aria2 both call `build_or_reuse_shared_openssl()` and record the resulting `dependencyIdentitySha256`, `libssl.a` digest and `libcrypto.a` digest in their own provenance surfaces; aria2's release verifier fails closed if the shared authority or identity is stale.

The shared cache lives under `~/.cache/xdm/android-native-deps/openssl/<version>-<identity>/` by default and is process-locked. Fresh construction uses OpenSSL's supported out-of-tree build mode: the pinned source extraction remains immutable/verifiable while Configure output and native objects live under a separate `build/` directory. Interrupted fresh shared builds therefore resume without invalidating the source-tree digest contract.

Migration is deliberately restart-safe. Before compiling OpenSSL, the authority:

1. reuses an already-qualified shared cache when present;
2. adopts a compatible completed legacy FFmpeg or aria2 OpenSSL install when available;
3. otherwise resumes a compatible pre-v4 in-progress legacy object tree (including the interrupted RM08 aria2 build), completes `build_libs`/`install_sw` in place once, then imports only OpenSSL headers/static libraries/pkg-config metadata into the shared prefix;
4. seeds the pinned source archive from the existing FFmpeg/aria2 download caches before using the network;
5. falls back to a new out-of-tree shared build only when no qualified/adoptable cache exists.

The migration never copies the consumer's whole native prefix: zlib, c-ares, Expat, libssh2 and FFmpeg/aria2 outputs remain consumer-owned. This prevents cross-component dependency leakage while still eliminating the duplicate OpenSSL build.

Focused validation covers shared identity convergence, API/toolchain invalidation, selective legacy-prefix import, pkg-config prefix rewriting, shared dependency provenance, RM07 aria2 source/ELF contracts, FF01/FF04 native-runtime seals, XAR01 provenance, Phase-10 packaging, Phase-13 final-gate contracts, Gradle task-graph optimization, full XGO topology, and the `xgo_gate_final_continue` target-ready topology.

## RM08 v5 aria2 top-level build-target repair

The first Gate-12 continuation after the shared OpenSSL convergence proved that the new dependency authority works in the live Termux/Devtool path: the aria2 build adopted the completed legacy FFmpeg OpenSSL cache and seeded the pinned OpenSSL source archive without recompiling OpenSSL. The continuation then failed later because `tools/build-aria2-runtime.py` invoked `make -jN aria2c` from aria2's top-level source directory. aria2 1.37.0 does not expose a top-level `aria2c` make target; its supported source-package build contract is the default top-level `make`, which produces `src/aria2c`.

RM08 v5 changes only that source-build boundary: the builder now runs the upstream top-level default `make -jN`, then retains the existing fail-closed check that `src/aria2c` exists before stripping, attesting and packaging it. RM07 and RM08 static regressions now reject a return to the nonexistent top-level `aria2c` target.

The Gate-12 continuation graph does not need another structural rewrite for this repair. It already routes Android package refresh through `xgo_rm08_aria2_package -> xgo_rm08_aria2_source -> runner:android#build -> :transfer-aria2:buildPinnedAria2Runtime`, so the corrected builder is automatically exercised at the same authoritative continuation checkpoint.

## RM08 v6 native-Termux Autoconf/Bionic capability repair

The next authoritative Gate-12 continuation (`b85f0fad272c416a8a65fe58ef7791f0`) proved both preceding repairs: the shared OpenSSL cache was reused directly and the aria2 builder entered the supported top-level `make -jN` build. The failure moved into real aria2 C++ compilation. `configure` had reported `checking whether we are cross compiling... no` even though the target is `aarch64-linux-android`, then falsely reported Android/Bionic APIs such as `getaddrinfo` and `gettimeofday` as absent. aria2 therefore enabled its bundled compatibility headers, which collided with NDK 29/API-26 Bionic declarations (`struct addrinfo`, `getnameinfo`, and `gettimeofday`). The same configure pass also falsely reported pinned dependency functions such as OpenSSL `EVP_DigestInit_ex`/`EVP_sha*`, zlib `gzbuffer`/`gzsetparams`, and c-ares `ares_set_servers` as absent.

RM08 v6 repairs the configure boundary instead of patching generated compatibility headers or suppressing compiler diagnostics:

- the builder follows aria2's own Android configuration model and always supplies both an explicit Linux build triplet from the pinned source's `config.guess` and `--host=aarch64-linux-android`; this prevents native Termux from being mistaken for a non-cross build merely because an ARM64 Android probe executable can run on the ARM64 Android host;
- before aria2 configure, the exact measured Android C++ compiler compile-links header-correct Bionic probes without executing the target binaries;
- only ordinary `AC_CHECK_FUNCS` results are cache-seeded from those successful probes; aria2's custom header-aware `asctime_r` and `localtime_r` checks are deliberately left to upstream configure and are independently verified afterward;
- the exact pinned zlib, shared OpenSSL, and c-ares libraries are separately compile-linked before their observed false-negative Autoconf answers are cache-seeded;
- after configure and before the expensive `make`, the builder requires the Android libc capability macros that prevent aria2's conflicting compatibility headers from being enabled. A disagreement between the independent target probe and upstream configure now fails immediately at the configure boundary rather than several minutes into C++ compilation;
- the deterministic aria2 source-build recipe records the explicit build triplet and both measured capability sets, so changing this configure authority invalidates the cached runtime/provenance lock.

This remains an RM08 revision, not a new roadmap item or Gate-12 node. `xgo_gate_final_continue` already owns the authoritative `xgo_rm08_aria2_source` checkpoint, so the same continuation path exercises the corrected source builder without duplicating or reordering the gate DAG.


## RM08 v7 Gradle-daemon lifetime decoupling

The next Gate-12 continuation (`e13819d49cbe4a5698fc6d65f128ff2f`) confirmed that the v6 Autoconf repair is effective: Expat reported `checking whether we are cross compiling... yes`, the explicit `--build=aarch64-unknown-linux-gnu --host=aarch64-linux-android` split was active, and the previous Bionic compatibility-header collision did not recur before the run was interrupted. The failure was instead `DaemonStoppedException: Gradle build daemon has been stopped: stop command received` while the long native source subprocess was still executing. This is an execution-lifetime failure, not an aria2/Autoconf compilation failure.

RM08 v7 separates Android environment bootstrap from the long native build without returning to the unbootstrapped v2 design:

```text
xgo_gate_final_continue
  -> target:xgo_rm08_aria2_package#validate
     -> target:xgo_rm08_aria2_source#validate
        -> target:xgo_rm08_aria2_contract#validate
           -> runner:android#test
              # Devtool resolves/bootstrap SDK, NDK and Android toolchain
        -> job:build_pinned
           -> python3 tools/build-aria2-runtime.py --build-pinned --jobs 2
              # long autotools/make build is not a child of a Gradle daemon
     -> target:xgo_rm08_aria2_android_package#validate
        -> runner:android#package
     -> target:xgo_rm08_aria2_attest#validate
```

The source-build job remains fully Devtool-owned and retains the same declared inputs/outputs, shared OpenSSL authority, source pins, NDK/toolchain provenance, ELF policy, and lock generation. The direct build is bounded to two native jobs to reduce pressure on Termux while Gradle's own worker settings remain independent. No project script invokes raw `gradle`/`gradlew`; Gradle is still entered only through Devtool's Android runner for the bootstrap contract and later Android packaging/instrumentation boundaries.

The static RM08 validator now fails closed unless the bootstrap node precedes the direct source job, the direct job uses the exact pinned builder command, and the source target contains no Gradle task mapping. This supersedes v3's stronger "no command target" rule while preserving the reason for that rule: a direct native builder is allowed only after Devtool has successfully established the Android SDK/NDK environment.

### v7 pre-apply completion: NDK libc++ link-root repair

A complete v6 Gate-12 log supplied before v7 was applied exposed a second failure that the original v7 daemon-lifetime-only payload would not have fixed. After the explicit Autoconf build/host split succeeded and the pinned C dependencies finished, the independent Android C++ capability probe stopped on `asctime_r` because `ld.lld` could not resolve `-lc++_shared`. The Termux-native compiler wrapper had the NDK sysroot and compiler-rt resource directory, but aria2's C++ probe/configure/make linker flags did not explicitly include the NDK libc++ root.

The revised v7 therefore also resolves and validates `<NDK toolchain>/sysroot/usr/lib/aarch64-linux-android`, requires both `libc++_shared.so` and `libc++_static.a` to exist there, and prepends that directory to the common aria2 linker flags. The existing `-static-libstdc++` policy is retained, so release output still requests the static NDK libc++ variant rather than introducing a new packaged `libc++_shared.so` dependency. The same linker flags are consumed by the Bionic capability probes, pinned-dependency probes, upstream configure checks, and the final aria2 link, preventing the probe-only repair from diverging from the real build.

The RM08 static contract validator now fails closed if this explicit libc++ authority disappears. This repair remains inside v7 because v7 had not yet been applied; there is no additional overlay or Gate-12 node.


### Post-v7 live continuation: Termux clang host-config leakage and v8 repair

The first live Gate-12 continuation after v7 proved that resolving the NDK libc++ directory was necessary but not sufficient. The independent `asctime_r` C++ probe still failed because LLD was asked for `-lc++_shared`. That spelling came from the native Termux `clang++` driver's default host configuration, not from aria2 or the NDK-target linker flags. Adding the NDK library directory therefore could not make the wrapper a clean Android cross driver.

RM08 v8 fixes the owning toolchain boundary in `tools/install-ffmpeg-runtime.py`: Termux-backed Android compiler wrappers now begin with `--no-default-config` before the explicit Android target, NDK sysroot, NDK Clang resource directory, compiler-rt, libunwind, AArch64 runtime directory, and LLD selections. This prevents Termux host-driver runtime policy from leaking into Android probes/builds while retaining the already-attested NDK inputs. The RM08 contract validator asserts this isolation so a future wrapper refactor cannot silently reintroduce host `clang.cfg` behavior.
