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

RM05 remediation changes Android app and `androidTest` sources, so package/APK,
artifact, lint, and device evidence are no longer treated as retained after an
RM05 edit. The continuation therefore runs:

1. cheap Android topology sanity;
2. app + instrumentation APK refresh and embedded-runtime verification;
3. APK integrity/native/provenance artifact checkpoint;
4. the focused RM05 opener-restoration instrumentation precheck;
5. authoritative Android lint;
6. the complete device instrumentation suite when a device is present;
7. full `xdm_modern` host qualification;
8. `xgo_final_wave12`.

The focused RM05 precheck exists only to fail fast during repair cycles. RM05 v3
qualified the legacy-v2 OAEP residual on-device, so RM05 v4 prechecks only the
remaining adaptive-sheet opener-restoration method before paying the lint cost.
It does not replace lint or the complete device suite.

`./devtoolw gate-final` remains the from-scratch authoritative workflow.
