# XDM Android FF04 embedded FFmpeg/FFprobe full release seal

FF04 is the final full overlay in the embedded media-runtime roadmap. It closes FF01, FF02, and FF03 as one release-qualified capability rather than adding another partial execution path.

## Closed promises

- **FF01 runtime foundation:** app-owned FFmpeg and FFprobe, NDK 29 (`29.0.14206865`), arm64-v8a, AArch64 PIE payloads, verified HTTPS, AndroidCAStore trust, provenance and license assets, shell-free typed execution, and embedded live ownership.
- **FF02 media processing:** separate video/audio mux, adaptive/HLS finalization, stream-copy-first post-processing, machine progress, cancellation, staged publication, atomic final replacement, and FFprobe correctness checks.
- **FF03 routing/product integration:** Automatic/Embedded/Termux runtime policy, embedded-first ownership, safe public-session-only Termux fallback, yt-dlp resolver boundary, settings/Developer Center truth, and redacted diagnostics.
- **FF04 release seal:** runtime SHA-256 attestation before readiness, pinned configure-profile verification, stripped/size-budgeted runtime packaging, exact APK payload/license verification, deterministic media fixtures, no-Termux device acceptance, stale historical gate repair, and final Devtool/release validation wiring.

## No-Termux acceptance

`Ffmpeg04EmbeddedRuntimeAcceptanceInstrumentedTest` copies deterministic video-only and audio-only fixtures from the Android test APK, requires the packaged embedded runtime to be attested and configuration-valid, muxes both tracks with `FfmpegPostProcessor`, then probes the published MP4 with the packaged FFprobe and requires both a video and an audio stream. The test does not invoke the Termux bridge or any Termux executable.

`MediaRuntimeNoTermuxFf04Test` separately proves Automatic routing selects the Embedded source when the embedded runtime is healthy even when the Termux bridge, Termux FFmpeg, and Termux FFprobe are all unavailable.

## Native packaging and licensing

The pinned build remains FFmpeg 9.0.1 + OpenSSL 3.5.8 under the existing LGPL-2.1-or-later / Apache-2.0 distribution model with GPL, nonfree, and version3 features disabled. FF04 additionally strips unneeded native symbols before recording hashes and disables the unused `avdevice` surface. FFmpeg 9.0.1 no longer ships `libpostproc`, so the obsolete `--disable-postproc` option is intentionally absent from the attested profile. The verifier enforces per-binary, combined-runtime, and compressed-APK size budgets.

A publishable debug artifact must pass `verifyFfmpegDebugApkRuntime`, which requires the NDK 29-built payload, 16 KB `PT_LOAD` compatibility, exact runtime-lock SHA-256 equality, exact packaged license equality, and APK runtime-size limits.

## Historical validation repair

The old execution/media semantics gate previously rejected the newer generic typed aria2 runtime-adapter cleanup test solely because its name contained aria2. FF04 preserves the actual rule—that progressive/direct media must not regress to aria2 ownership—while explicitly retaining the generic aria2 adapter cleanup test. No production execution behavior was weakened.

## Final validation authority

FF04 is registered in the canonical static final gate and the `xdm_android` Devtool preflight. The package phase also carries the exact APK runtime verifier and Android-test APK assembly. The final overlay is intended to run with validation enabled and explicit FF04/runtime/unit/lint tasks.

## Validation hotfix v2

The target Termux validation exposed an AGP lifecycle bug in v1: `tasks.named("assembleDebug")` was evaluated before AGP registered the debug variant task. v2 uses lazy task matching and makes debug packaging depend directly on the pinned runtime installer. v2 also makes the builder resilient to Gradle daemon environment propagation and ARM64 Termux hosts by reading `sdk.dir` and using native Termux LLVM against the pinned NDK 29 sysroot when the NDK host compiler is not runnable.

## Validation hotfix v3

The second target-device run reached AGP 9.2.1 configuration and exposed a legacy library source-set cast incompatibility at `media-ffmpeg/build.gradle.kts`. v3 removes that API path, registers runtime assets through `androidComponents` variant sources, and orders media asset/JNI merge tasks after the pinned runtime installer. This preserves parallel builds without allowing generated runtime metadata or native payloads to race packaging.


## FF04 v4 target-validation repair
Real Termux validation of v3 reached unit tests and native runtime installation and exposed issues that source-only gates could not observe. v4 repairs: the OpenSSL 3.5.8 release URL, Developer Tools readiness exhaustiveness, embedded-vs-yt-dlp transient-cookie ownership, adaptive default-track normalization, and stale FF02/FF03 assertions. The final seal still requires the same target-side Gradle/NDK/APK validation.
## v5 target-validation closure

The v4 Termux validation reached the real pinned source build and proved the source URLs/AGP wiring were fixed. Two blockers remained: OpenSSL's Perl-based `Configure` frontend was absent on the minimal Termux host, and one legacy resolver-picker test still required yt-dlp format state after explicit selected tracks had moved to embedded FFmpeg. v5 bootstraps the bounded host build-tool set (`perl`, `make`, `pkg-config`) through Termux `pkg` when missing and updates that test to assert the production embedded-adaptive contract. Automatic host-tool installation can be disabled for hermetic builders with `XDM_FFMPEG_AUTO_INSTALL_HOST_TOOLS=0`.

## v6 resumable native-build closure

The v5 validation run did not expose a compiler defect; it reached a real OpenSSL 3.5.8 ARM64 build and was interrupted while compiling `libcrypto`. v6 keeps every FF04 attestation, NDK 29, 16 KB ELF, APK-byte, license, FFprobe, deterministic-fixture, and no-Termux promise while making that native build practical to resume.

The deterministic cache-key formula is intentionally unchanged, so the existing `build-1d480174b70d7e78` object tree remains reusable on the same NDK path/host. OpenSSL and FFmpeg now write configuration fingerprints immediately after successful configure and do not repeat configure merely because the final library or executable is not finished. OpenSSL no longer receives `-D__ANDROID_API__=26`; Android Clang already defines the API from its target triple. Default `make -s` suppresses the thousands of repeated compiler command lines without suppressing real compiler diagnostics, while periodic heartbeat messages keep long builds visibly alive. `XDM_FFMPEG_VERBOSE_MAKE=1` restores full command output for diagnosis.

The cache is process-locked so a second Gradle/process caller cannot mutate the same deterministic object tree concurrently. `--force` remains the explicit opt-in path that discards the build root. OpenSSL compilation targets `build_libs`; the existing `no-tests`, `no-apps`, `no-module`, `no-legacy`, static-only profile is retained. Additional crypto/TLS feature removals were deliberately not added because they would trade compatibility/security coverage for unmeasured build savings.

`tools/test-ffmpeg-runtime-builder-resume.py` provides fast executable coverage for the v6 marker/profile behavior, and the FF04 final validator runs it as part of the release seal.



## v7 FFmpeg 9 configure-surface correction

The first v6 prewarm failed before FFmpeg compilation because the pinned 9.0.1 configure script no longer accepts `--disable-postproc`; upstream removed `libpostproc` before the FFmpeg 9 release line. v7 corrects the manifest and runtime attestation profile, builds the configure arguments from that manifest, and preflights the exact profile through the verified source tree’s configure parser before invoking the real configure step. The preflight appends `--help` only after all profile flags, so FFmpeg parses and rejects genuinely removed switches while still accepting supported generic inverse options that are not individually advertised in help output.

This correction does not discard the expensive v5/v6 native cache. For cache-path calculation only, the builder reconstructs the previous manifest shape by appending the obsolete postproc flag, preserving the same deterministic cache directory on the same NDK path and host. The real command, marker, lock, packaged manifest, and runtime `ffmpeg -version` verification use only the corrected profile. All NDK 29, 16 KB, licensing, HTTPS, FFprobe, APK-byte, deterministic-fixture, and no-Termux release promises remain unchanged.
## v7r2 Termux packaging and validation scheduling closure

The v7r1 target run passed the FF04 seal and the complete canonical static gate, installed and packaged the pinned FFmpeg/FFprobe runtime, then spent more than 200 seconds in the remaining Gradle graph before the invocation was interrupted. The two native-library messages immediately before the quiet period were not missing libraries: AGP could not run its x86_64/glibc strip helper under native ARM64 Termux for `libandroidx.graphics.path.so` and `libdatastore_shared_counter.so`, and explicitly fell back to packaging both libraries unchanged. v7r2 makes that fallback intentional with two exact `keepDebugSymbols` entries, eliminating the doomed strip attempts without permitting broad native-symbol retention.

The final validation graph is also made deterministic. `:app:verifyFfmpeg04FinalReleaseValidation` owns the final contract/test/APK/static/lint roots; lint tasks are ordered after the final static/runtime stages, and `media-ffmpeg` lint-model work must run after `installPinnedFfmpegRuntime` whenever both tasks are present. This prevents parallel lint-model generation from racing the generated JNI/runtime source tree while retaining Gradle parallelism for independent compilation and test work.

The v7r1 log's `:media-ffmpeg:generateDebugLintModel` / `lintAnalyzeDebug` failures were interruption bookkeeping after the process received exit 130, not lint diagnostics. The static final gate had already passed and there were no source/lint findings before interruption.
## v7r3 FF03 Gradle test-root discovery closure

The v7r2 target validation reached the real `:app:testDebugUnitTest` task and ran 441 app unit tests. Exactly two tests failed, both in `Ffmpeg03RuntimeRoutingUiContractTest`, with `FileNotFoundException` from the helper that opened source files. The production/runtime implementation was not the cause. The test assumed `user.dir` would be either the Android root or the repository root; under the real Gradle unit-test worker it can instead be the `app` module directory, producing a nonexistent nested `app/app/XDM.Android` path.

v7r3 resolves the Android project root by walking upward from the canonical `user.dir` and accepting either an Android-root marker (`app/src/main`) or a repository-root marker (`app/XDM.Android/app/src/main`). It also requires non-null `user.dir`, removing the Kotlin Java-platform-type warning seen in the v7r2 log. The FF04 static seal now asserts this ancestor-aware resolver and rejects the old brittle construction.

The remainder of v7r2 is preserved unchanged: exact Termux-native strip exceptions, staged final validation, embedded FFmpeg/FFprobe runtime installation and APK attestation, deterministic cache reuse, NDK 29 and 16 KB checks, HTTPS/OpenSSL profile, licensing restrictions, fixtures, FFprobe verification, and no-Termux acceptance.

