# FF01 — Embedded FFmpeg/FFprobe runtime and media execution

FF01 is the first full overlay in the XDM Android FFmpeg roadmap. It establishes FFmpeg and FFprobe as app-owned media runtimes and cuts live FFmpeg execution away from mandatory Termux ownership. It does **not** attempt FF02 track-pair mux orchestration or the later runtime-selection preference UI; those remain separate full overlays.

## Runtime boundary

- FFmpeg: **9.0.1 (Lei)**, pinned source tarball + SHA-256.
- OpenSSL: **3.5.8 LTS**, pinned source tarball + SHA-256.
- Android ABI: **arm64-v8a**.
- Android API floor: **26**.
- NDK: **29.0.14206865**.
- ELF: AArch64 PIE/ET_DYN, 16 KiB PT_LOAD alignment required for strict payload builds.
- License profile: FFmpeg LGPL-2.1-or-later, GPL/nonfree disabled; OpenSSL Apache-2.0. Upstream license texts are copied from the pinned source archives into the packaged assets.
- Generated executables are packaged as `libxdm_ffmpeg.so` and `libxdm_ffprobe.so` so Android installs/extracts them into `ApplicationInfo.nativeLibraryDir`; XDM never copies executable code into writable app storage.

`tools/install-ffmpeg-runtime.py` owns source download, digest verification, path-safe extraction, OpenSSL cross-build, FFmpeg cross-build, 16 KiB linker flags, executable installation, license extraction, and the generated provenance lock. `tools/verify-ffmpeg-runtime.py` owns source-only validation plus strict runtime/APK attestation when a payload is present or required.

RM03 hardens this boundary further: the FFmpeg configure environment clears the host `PKG_CONFIG_PATH`, exposes only the pinned OpenSSL pkg-config metadata through `PKG_CONFIG_LIBDIR`, and puts the NDK API sysroot library directory ahead of all other link search paths. Every generated FFmpeg/FFprobe payload is parsed before installation and its `PT_INTERP`/`DT_NEEDED` set must satisfy `android-unversioned-sonames-v1`; Linux/Termux dependencies such as `libz.so.1` or `libc.so.6` are rejected. The exact dependency lists are hash-bound in the runtime lock and rechecked both from installed payloads and from the APK.

## HTTPS trust

The embedded runtime does not disable certificate verification. `AndroidTrustBundleProvider` reads `AndroidCAStore`, materializes X.509 roots into an app-private `noBackupFilesDir` PEM bundle, and the typed command compiler supplies `-tls_verify 1 -ca_file <bundle>` for HTTPS probe/record operations. Header names and values are validated before process launch and no shell command is constructed.

## Typed runtime API

The `media-ffmpeg` module owns:

- runtime manifest and capability probing;
- FFmpeg/FFprobe binary discovery from `nativeLibraryDir`;
- typed operations for version/protocol checks, probe, recording, remux, track mux, audio extraction, and faststart;
- bounded stdout/stderr capture, timeout and coroutine cancellation behavior;
- failure classification and credential-bearing diagnostic redaction;
- FFprobe JSON parsing into typed stream/format metadata.

FF01 exposes operations that later overlays will reuse, but only the live-recording execution path is product-wired in this overlay.

## Media execution ownership

`MediaDownloadStrategy.FfmpegLive` no longer implies `requiresTermux`. The media execution planner maps it to `MediaExecutionLane.EmbeddedFfmpegLive`, the dispatcher gates it on embedded-runtime readiness, and the worker/queue/telemetry models understand the app-owned lane. The Termux adapter explicitly refuses to own embedded FFmpeg work; Termux remains relevant for yt-dlp and other user-managed external tooling.

`EmbeddedFfmpegMediaManager` owns live-job admission and lifecycle:

1. capability probe must report the embedded runtime ready;
2. a durable `MediaOutputRecord` with `MediaOutputOwnerKind.EmbeddedFfmpeg` is created;
3. exact URLs/headers remain process-local in the coroutine closure rather than being copied into durable output rows;
4. the destination writer allocates staging;
5. typed FFmpeg stream-copy recording executes;
6. FFprobe must confirm at least one media stream;
7. destination publication is promoted atomically through the existing writer;
8. only then is the media output marked completed.

Interrupted queued/active app-owned jobs become `RecoveryRequired` at startup. User removal cancels an active embedded job and tombstones its library output lineage.

## Container correctness

Captured live playlist names are not reused as final playlist outputs. Inputs ending in `.m3u8`, `.m3u`, `.mpd`, an extensionless source, or generic `.media` normalize to an `.mkv` final artifact for FF01 live recording. MP4-family outputs receive `+faststart`; Matroska outputs do not receive MOV-only flags. Output MIME is derived from the produced container.

## Product and diagnostics integration

The application container owns one `EmbeddedFfmpegRuntime` and `EmbeddedFfmpegMediaManager`. MainViewModel includes runtime probing, dispatch readiness, live enqueue, removal/cancellation, and a Developer Center status card with version/HTTPS state plus a runtime self-test. Diagnostic summaries are bounded and redact signed query strings, cookies, authorization headers, API keys, tokens, signatures, and bearer credentials.

## Build and validation wiring

The Android settings graph includes `:media-ffmpeg`; the app depends on it and retains its native payload symbols. `.devtool.toml` installs the pinned runtime for build/package, tests the module, and runs provenance + FF01 source-contract verification in preflight. `tools/run-final-release-gate.sh` includes the FF01 validator in its matrix.

Source-only validation:

```bash
python3 tools/validate-ffmpeg01-embedded-runtime-media-execution.py
python3 tools/verify-ffmpeg-runtime.py
```

Strict target-environment payload validation:

```bash
python3 tools/install-ffmpeg-runtime.py --build-pinned
python3 tools/verify-ffmpeg-runtime.py --require-payload --require-16kb-alignment
gradle :media-ffmpeg:testDebugUnitTest :media-ffmpeg:verifyFfmpegRuntime :app:verifyFfmpeg01EmbeddedRuntimeContract -Pxdm.requireFfmpegRuntime=true
```

FF01 is intentionally an **intermediate** roadmap overlay and is applied with `--no-validate`; it still contains its production tests and verification wiring. The final FF04 seal owns full lint/build/device/release validation.
