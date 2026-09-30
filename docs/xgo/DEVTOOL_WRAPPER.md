# XGO Devtool wrapper

`./devtoolw` is the repository-local entry point for Devtool. It is intentionally thin: the launcher only resolves the repository root and delegates to `devtool wrapper exec`; command ownership stays in `.devtool.toml`.

The default command is `android`. Direct Android and desktop commands allow normal Devtool passthrough arguments, so focused work remains possible without bypassing Devtool, for example:

```sh
./devtoolw android --task :app:testDebugUnitTest
./devtoolw android --task :media-ffmpeg:installPinnedFfmpegRuntime
./devtoolw android-lint
./devtoolw desktop
```

XGO implementation boundaries are exposed as `xgo-foundation`, `xgo-store`, `xgo-security`, `xgo-transfer`, `xgo-backends`, `xgo-scheduler`, `xgo-media`, `xgo-ops`, `xgo-android`, `xgo-desktop`, and `xgo-seal`.

Authoritative gates are exposed as `gate-spec`, `gate-foundation`, `gate-durable`, `gate-security`, `gate-transfer`, `gate-backends`, `gate-scheduler`, `gate-media-identity`, `gate-media-exec`, `gate-ops`, `gate-android`, `gate-cross-host`, and `gate-final`. `final-wave12` runs only the final Wave-12 verifier and is not a substitute for `gate-final`.

Use `./devtoolw wrapper-check` for the cheap non-Gradle wrapper contract. It validates the launcher, command map, aliases, targets, and referenced workflows.

Do not put build, validation, or gate logic in `devtoolw`; add or change authoritative behavior in `.devtool.toml` and keep the launcher generated-style.


## Gate-12 checkpoint continuation

`./devtoolw gate-final-continue` is the repair-cycle continuation for the
current Gate-12 checkpoint.

It deliberately does **not** rerun:

- `xgo_seal`;
- Android preflight;
- Android dependency resolution;
- the earlier Android unit-test qualification phases.

RM05 through RM08 changed Android app, native-runtime, packaging, and
`androidTest` sources. Retained APK/lint/device evidence is therefore invalidated,
while the already-closed earlier Gate-12 checkpoints remain retained. The
continuation now runs:

1. the XGO Devtool-execution policy;
2. cheap Android topology sanity;
3. RM08's pinned/source-built aria2 materialization;
4. Debug app + instrumentation APK refresh;
5. RM08 packaged-byte attestation proving the APK's `libaria2c.so` matches the
   source-build runtime lock;
6. APK integrity/native/provenance artifact checkpoint;
7. the focused RM05 opener-restoration precheck;
8. authoritative Android lint;
9. **required** RM08 Android instrumentation, including real packaged aria2
   launch, authenticated loopback RPC, deterministic TLS rejection/success,
   session save, stop/restart, and recovery;
10. full `xdm_modern` host qualification;
11. `xgo_final_wave12`, which consumes both RM08 host receipts.

The continuation's three local checkpoint/precheck jobs are target-local jobs of
`xgo_gate_final_continue`; cross-target work is composed only through canonical
`target:<name>#validate` references. XGO gate jobs must not call raw
`gradle`/`gradlew`; Devtool owns the underlying Android/Gradle execution after
resolving SDK/JDK/AAPT2/provider/resource policy.

The focused RM05 precheck remains a fail-fast repair-cycle check only. It does
not replace lint or RM08's required full device proof.

`./devtoolw gate-final` remains the from-scratch authoritative workflow.
