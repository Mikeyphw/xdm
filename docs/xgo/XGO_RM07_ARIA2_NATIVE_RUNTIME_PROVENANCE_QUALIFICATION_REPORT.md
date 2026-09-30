# XGO-RM07 — aria2 native runtime/provenance qualification

**Gate:** retained GATE-12 remediation loop  
**Position:** aria2 remediation 2 of 3  
**Predecessor:** XGO-RM06 Android aria2 runtime/recovery convergence

## Outcome

Release-qualified Android aria2 is no longer accepted as a third-party prebuilt selected by an
external digest. XDM owns a pinned source-build recipe and records the complete native provenance
needed to reproduce and qualify the packaged executable.

## Source-build authority

`transfer-aria2/runtime/aria2-runtime.json` is schema v3 and pins:

- aria2 1.37.0;
- OpenSSL 3.5.8;
- c-ares 1.34.8;
- Expat 2.8.5;
- libssh2 1.11.1;
- zlib 1.3.2;
- Android API 26;
- Android NDK 29.0.14206865;
- exact SHA-256 for every source archive;
- the source-build feature/configuration profile;
- 16 KiB/PT_LOAD and ELF hardening requirements.

`tools/build-aria2-runtime.py --build-pinned` builds the graph from those sources. It reuses the
campaign's already-sealed FFmpeg NDK/Termux compiler discovery, link probing and OpenSSL Android
adapter rather than introducing a competing toolchain implementation. The resulting lock records
source pins, measured NDK revision/source.properties hash, toolchain and compiler fingerprints,
build-recipe hash, toolchain-authority hash, deterministic cache identity and final binary hash.

The historical devgianlu/aria2-android ARM64 payload remains available only through
`installCompatibilityAria2Runtime`; its manifest contract is explicitly `releaseEligible=false`.
`--require-source-build` rejects that compatibility lane.

## ELF qualification

`tools/android_elf_runtime.py` now owns a shared strict ELF policy in addition to Android SONAME
checks. Release aria2 must prove:

- AArch64 PIE (`ET_DYN`);
- Android linker64 interpreter;
- every PT_LOAD is at least 16 KiB aligned and offset/vaddr congruent at 16 KiB;
- PT_GNU_RELRO exists;
- BIND_NOW is set;
- PT_GNU_STACK is explicitly non-executable;
- DT_NEEDED contains no Linux/versioned SONAMEs.

The lock mirrors the measured ELF security state and the APK verifier checks the exact same bytes.

## Release graph convergence

Strict JNI packaging, signed-release CI, Phase-10 release gating and XAR16 artifact construction now
build pinned aria2 sources and require schema-v3 source-build verification. The obsolete
`XDM_ARIA2_ARCHIVE_SHA256` release secret is removed from the authoritative paths.

## Focused validation

`xgo_rm07_aria2#validate` executes `:transfer-aria2:verifyRm07Aria2NativeRuntime` through Devtool.
The regression suite uses adversarial synthetic ELF fixtures to prove rejection of:

- 4 KiB PT_LOAD alignment;
- non-PIE executable type;
- missing GNU RELRO;
- missing BIND_NOW;
- executable/missing-safe stack policy;
- non-Android interpreter.

It also checks source pins, compatibility-prebuilt exclusion, release/CI wiring, and executes the
source builder's no-network `--plan` mode.

The expensive network/source compilation is intentionally not duplicated during RM07 artifact
apply. It is forced by strict Android release/GATE-12 packaging via `buildPinnedAria2Runtime`, where
the generated source-built bytes and APK identity become authoritative evidence.

## Next

**XGO-RM08 — packaged aria2 Android execution qualification**: launch the exact packaged executable
on Android, authenticate RPC, perform deterministic HTTP/HTTPS transfers, checkpoint/terminate,
restart/recover and bind that device evidence into the retained GATE-12 final audit.

## Live Devtool integration repair (v2)

The first real Termux apply of RM07 v1 exposed two integration gaps outside the focused synthetic
ELF regressions:

- `ArchitectureContractTest.launcherIconAndAria2AlignmentContractsArePresent` still expected
  verifier-local `PT_LOAD` parsing after RM07 moved ELF ownership into `tools/android_elf_runtime.py`;
- `verifyAria2Runtime` configured its `Exec` command and success marker in `doFirst`/`doLast`, which
  Gradle 9.7.1 rejects when storing the configuration cache.

RM07 v2 keeps the same native/provenance scope and repairs both contracts. The architecture test now
asserts the shared ELF-policy ownership, and `verifyAria2Runtime` is fully configured during Gradle
task configuration. Incremental validation is preserved by passing `--success-marker` to
`verify-aria2-runtime.py`, which writes the marker only after successful verification. The focused
RM07 regression rejects any reintroduction of execution-time `Exec` configuration.

The artifact validation contract is also narrowed from the ambiguous generic `test` task to the
exact `:transfer-aria2:verifyRm07Aria2NativeRuntime` Gradle task so apply-time validation cannot
expand into the entire Android unit-test graph.
