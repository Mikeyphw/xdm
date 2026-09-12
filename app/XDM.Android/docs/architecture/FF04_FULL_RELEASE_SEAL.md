# FF04 full release seal

FF04 is the final authority for the XDM Android embedded FFmpeg/FFprobe roadmap.

## Runtime trust chain

1. Pinned FFmpeg/OpenSSL source archives are SHA-256 verified.
2. Android NDK 29 builds arm64-v8a PIE executables with 16 KB linker page-size settings.
3. Unneeded native symbols are stripped before the runtime lock records binary hashes.
4. `verify-ffmpeg-runtime.py` validates provenance, license hashes, binary hashes/sizes, ELF identity/alignment, configure-profile attestation, and APK-member equality.
5. At runtime, `EmbeddedFfmpegRuntime` reads `ffmpeg-runtime.lock.json`, hashes the actual files in `nativeLibraryDir`, checks the build profile reported by `ffmpeg -version`, verifies HTTPS support, and only then reports Ready.

## Acceptance boundary

Normal adaptive media processing requires no Termux installation. Device acceptance uses deterministic separate video/audio fixtures and the same `FfmpegPostProcessor` / `FFprobe` path used by production. Termux remains an optional, policy-controlled fallback for safe public sessions and yt-dlp/site-specific extraction.

## Release gates

- `:app:verifyFfmpeg04FullReleaseSeal`
- `:media-ffmpeg:installPinnedFfmpegRuntime`
- `:media-ffmpeg:verifyFfmpegRuntime`
- `:app:verifyFfmpegDebugApkRuntime`
- `:media-ffmpeg:testDebugUnitTest`
- `:media:test`
- `:app:testDebugUnitTest`
- `:app:assembleDebugAndroidTest`
- `:app:lintDebug`

Device environments can additionally execute the instrumentation suite containing `Ffmpeg04EmbeddedRuntimeAcceptanceInstrumentedTest`.

## Validation hotfix v2

The final seal configures AGP variant packaging lazily. `assembleDebug` depends on the pinned runtime installer through `tasks.matching(...).configureEach`, avoiding eager `tasks.named("assembleDebug")` resolution before AGP has registered variant tasks. The runtime builder also supports `local.properties` SDK discovery and native ARM64 Termux LLVM wrappers against the pinned NDK sysroot.

## AGP 9.2 packaging compatibility

FF04 v3 registers `media-ffmpeg/runtime` as an asset source through `androidComponents` / variant sources rather than the legacy library `sourceSets` container. Asset and JNI merge tasks depend on `installPinnedFfmpegRuntime`, preventing parallel Gradle packaging from reading the runtime tree before the binary, license, and attestation lock outputs exist.
## v4 target-validation closure

The first complete Termux/AGP 9.3.1 validation run reached the executable unit-test and native-runtime paths and exposed failures that the source-only seal could not observe. FF04 v4 closes those target-only gaps without weakening the production contract:

- OpenSSL 3.5.8 is fetched from the permanent official GitHub release asset and remains SHA-256 pinned.
- Embedded FFmpeg plans no longer retain yt-dlp-only selectors or create transient Netscape cookie files. Credential headers remain app-owned for the embedded lane.
- Adaptive normalization auto-selects only renditions explicitly marked default/autoselect; an ambiguous unmarked companion track falls back to the resolver instead of silently choosing a stream.
- Developer Tools handles `NeedsEmbeddedFfmpegRuntime` explicitly.
- FF02/FF03 tests now assert the typed execution representation and use truly public/header-free fixtures when exercising safe Termux fallback.

The final target gate still builds the pinned NDK 29 runtime, runs unit tests/lint, packages the Android test APK, validates the debug APK payload, and keeps the no-Termux device acceptance test packaged for execution.
## Native Termux bootstrap closure (v5)

Target validation showed that a minimal Termux host can provide NDK 29 and Gradle while still lacking Perl, which OpenSSL requires for its `Configure` frontend. The final runtime installer therefore verifies the bounded host tool set (`perl`, `make`, `pkg-config`) and, only on native Termux, installs missing packages through `pkg`. The behavior is disable-able with `XDM_FFMPEG_AUTO_INSTALL_HOST_TOOLS=0` for builders that manage dependencies externally. Explicit selected adaptive tracks remain app-owned embedded FFmpeg work and must not regain yt-dlp-only format state.

## Resumable native build (v6)

The native build cache is now a release-seal concern because final validation can spend most of its time inside OpenSSL/FFmpeg compilation. v6 keeps the existing deterministic cache-key formula unchanged and adds three persistent state markers inside that cache: OpenSSL configured, OpenSSL installed, and FFmpeg configured. A marker is written atomically only after the corresponding phase succeeds. An interrupted compile therefore resumes with `make` rather than rerunning configure.

OpenSSL configuration no longer passes `-D__ANDROID_API__`; the Android Clang target already defines the API level. Default compilation uses quiet GNU make (`make -s`) so warnings/errors stay visible but full compiler command lines do not dominate Devtool output. The builder prints periodic progress heartbeats and accepts `XDM_FFMPEG_VERBOSE_MAKE=1` for full command diagnostics. A process lock serializes writers of the same deterministic cache.

OpenSSL uses the `build_libs` target and keeps the existing static/TLS-compatible disable profile. v6 intentionally does not remove additional cryptographic/TLS capabilities without evidence that FFmpeg HTTPS, Android CA-backed verification, redirects, and real media endpoints remain compatible.



## FFmpeg 9.0.1 configure compatibility (v7)

The first v6 prewarm reached FFmpeg configuration and exposed a release-profile mismatch: FFmpeg 9.0.1 has removed `libpostproc`, so its configure script rejects the old `--disable-postproc` switch. v7 removes that switch from the production manifest and derives the real configure command from the manifest after checking every profile option against the verified source tree's `./configure --help` output. This converts future removed/renamed options into an immediate focused profile error instead of a late opaque configure traceback.

The deterministic native cache remains compatible with v5/v6. Cache identity alone reconstructs the previous manifest's obsolete postproc flag so the same NDK/path/host resolves to the existing cache; runtime configuration, lock attestation, and `ffmpeg -version` verification use only the corrected FFmpeg 9 profile.

## Post-seal roadmap ownership gate

A successful payload/APK seal is necessary but not sufficient evidence that every roadmap execution path is reachable. The post-v7r4 audit found that the native-HLS finalizer could satisfy the old source contract without any production caller.

FF04 now carries `verifyFfmpegRoadmapPostSealHotfix`, and FF02 itself requires the production `NativeHlsMediaManager` call path plus embedded-first completed-file FFmpeg/FFprobe routing. The explicit Termux fallback remains separately marked and verified. This prevents future replacement seals from reporting roadmap closure when the runtime classes exist but the app never dispatches work to them.

## Post-seal v2 literal roadmap acceptance

The post-seal gate now carries a literal FF01–FF04 closure audit. FF04 device acceptance does not stop at producing a cache file: deterministic separate video/audio fixtures are muxed by the attested embedded runtime into `AndroidDestinationWriter` staging, FFprobe verifies staged media, app-private publication must report atomic promotion, and embedded FFprobe verifies the committed file. Native-HLS startup recovery is ordered after the canonical publication-journal scanner so a crash after destination commit cannot cause duplicate remux/publication.

## Post-seal roadmap audit v3

The FF04 staged lifecycle now includes the strengthened post-seal ownership gate. In addition to the pinned runtime/APK/license/16-KB/no-Termux checks, the gate proves crash-safe embedded adaptive/live publication, codec-compatible audio-only output containers, native-HLS generation/map-range-safe recovery, and explicit Termux fallback separation. Provider commits observed mid-copy are never adopted on size alone: content destinations require SHA-256 equality with preserved staged bytes.

## Post-seal v4 recovery gate

The FF04 carry-forward seal now treats a visible Retry action as part of FF02 lifecycle correctness. An `EmbeddedFfmpeg` generation in Failed/Cancelled/RecoveryRequired must have an executable embedded-only recovery path in every Library surface; it may not silently fall through to Termux or an unrelated ordinary Download retry. Native-HLS recovery remains owner-routed to `NativeHlsMediaManager`.
