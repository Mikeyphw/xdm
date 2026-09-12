# XDM Android embedded FFmpeg / FFprobe runtime

XDM owns an embedded FFmpeg and FFprobe runtime for media operations that should not require Termux. The runtime is built from pinned upstream source instead of accepting opaque prebuilt executables.

## Pinned inputs

- FFmpeg 9.0.1 (`Lei`), SHA-256 pinned in `runtime/ffmpeg-runtime.json`.
- OpenSSL 3.5.8, SHA-256 pinned in the same manifest and used to provide HTTPS. The source URL is the permanent official GitHub release asset referenced by the OpenSSL downloads service.
- Android NDK `29.0.14206865`, API 26, `arm64-v8a`.
- FFmpeg is configured without GPL, nonfree, or version3 features. The build fails rather than silently changing those inputs.

`tools/install-ffmpeg-runtime.py --build-pinned` downloads the two source archives, verifies their hashes before extraction, cross-builds OpenSSL and FFmpeg/FFprobe, checks that both executables are AArch64 PIE/ET_DYN files, and installs them under JNI-library names:

- `libxdm_ffmpeg.so`
- `libxdm_ffprobe.so`

The `lib*.so` filenames are intentional. Android extracts them into the app's native library directory; XDM executes those app-owned files directly with `ProcessBuilder` and never through `sh -c`.

The installer also copies the exact FFmpeg LGPL 2.1 and OpenSSL Apache 2.0 license files from the verified source archives into the runtime assets, then records hashes for binaries and license texts in `ffmpeg-runtime.lock.json`.

## Verification

Source-only checkouts may omit generated native payloads. A publishable/runtime-bearing artifact must use strict verification:

```sh
python3 tools/verify-ffmpeg-runtime.py --require-payload --require-16kb-alignment
```

An APK can be checked against the same attestation lock:

```sh
python3 tools/verify-ffmpeg-runtime.py \
  --require-payload \
  --require-16kb-alignment \
  --apk app/build/outputs/apk/debug/app-debug.apk
```

The verifier checks the pinned provenance fields, binary hashes and sizes, AArch64/ET_DYN ELF identity, 16 KB `PT_LOAD` compatibility, packaged APK binary equality, and packaged third-party license equality.

## Runtime boundary

`EmbeddedFfmpegRuntime` resolves binaries only from `ApplicationInfo.nativeLibraryDir`. Operations compile to argument vectors, not shell source. The runtime probes the exact FFmpeg/FFprobe version and requires HTTPS support before it reports itself ready. Captured request headers remain process-local and are not written to the durable media-output record.

FF01 makes `FfmpegLive` an embedded XDM execution lane. Termux remains the owner of yt-dlp/site-specific fallback work; it is no longer a prerequisite for the FFmpeg live lane.

## FF04 final release seal

FF04 promotes the runtime from source-contract coverage to release-attested payload coverage. The pinned builder strips unneeded native symbols before the binary hashes are recorded, disables the unused `avdevice` surface; FFmpeg 9.0.1 has already removed `libpostproc`, so no obsolete postproc configure option is passed, and enforces explicit uncompressed and APK-compressed size budgets from `runtime/ffmpeg-runtime.json`.

Release validation must build the pinned runtime with NDK 29, then verify both the installed native payload and the exact APK members:

```sh
./gradlew :media-ffmpeg:installPinnedFfmpegRuntime :app:assembleDebug :app:verifyFfmpegDebugApkRuntime
```

At application runtime, readiness is fail-closed until `ffmpeg-runtime.lock.json` matches the actual `nativeLibraryDir` FFmpeg/FFprobe SHA-256 values and the binaries report the pinned configure profile. A binary that merely answers `-version` is not considered healthy.

`Ffmpeg04EmbeddedRuntimeAcceptanceInstrumentedTest` is the no-Termux device acceptance path: it copies deterministic separate video/audio fixtures, muxes them with the packaged embedded FFmpeg, and verifies both streams with the packaged FFprobe.

### Native Termux host compatibility

The pinned builder does not assume the NDK host executables match the device CPU. It first uses a runnable NDK-hosted LLVM toolchain; on ARM64 Termux with an x86_64-only NDK host package, it creates private wrappers around Termux clang/LLVM while still targeting the pinned NDK 29 sysroot and API 26. SDK discovery also reads `sdk.dir` from `local.properties`, so Gradle daemon environment propagation is not required for locating the pinned NDK. The selected backend is recorded in `ffmpeg-runtime.lock.json` and verified by the release gate.
### Native Termux host build tools

The pinned runtime builder verifies `perl`, `make`, and `pkg-config` before configuring OpenSSL/FFmpeg. On native Termux, missing tools are bootstrapped with `pkg install -y` so a minimal Termux installation can complete the reproducible build without a manual prerequisite step. Set `XDM_FFMPEG_AUTO_INSTALL_HOST_TOOLS=0` to disable this behavior on hermetic/managed builders; in that mode missing tools fail closed with an explicit dependency message.

### Resuming an interrupted native build (FF04 v6)

Normal validation should **not** use `--force`. The build key remains derived from the pinned runtime manifest, NDK path, and host architecture exactly as in v5, so a valid partial cache is reused. OpenSSL and FFmpeg persist atomic configuration markers and resume GNU make from existing object files. A per-build cache lock prevents concurrent callers from modifying the same object tree.

OpenSSL is configured without an explicit `-D__ANDROID_API__` because the Android Clang target already defines the API level. The default compiler command stream is quiet (`make -s`) while warnings/errors remain visible, and the builder emits periodic elapsed-time heartbeats. Use `XDM_FFMPEG_VERBOSE_MAKE=1` only when full compiler commands are needed for diagnosis; use `XDM_FFMPEG_BUILD_HEARTBEAT_SECONDS=0` to disable the builder heartbeat.

The OpenSSL build targets `build_libs` and retains the existing static, no-tests/no-apps/no-module/no-legacy profile. No extra cryptographic feature exclusions are part of the seal because HTTPS/TLS interoperability and certificate handling take priority over speculative build-size reductions.



### FFmpeg 9 configure-surface compatibility (FF04 v7)

FFmpeg 9.0.1 no longer contains `libpostproc` and rejects the historical `--disable-postproc` option. The pinned manifest therefore records only flags that are valid for 9.0.1. Before the real configure step, the installer reads `./configure --help` from the verified source tree and fails early with a focused diagnostic if any manifest profile flag is unsupported. The build command is generated from that same manifest list, preventing the builder and runtime attestation profile from drifting apart.

Because v5/v6 hashed the old manifest into the deterministic native-cache path, v7 reconstructs that legacy manifest only while calculating the cache identity. The actual configure command, runtime lock, packaged manifest, and runtime capability check never contain `--disable-postproc`. This keeps the existing OpenSSL object/prefix cache reusable without mis-attesting the final FFmpeg binary.
