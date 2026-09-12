# XDM Android embedded FFmpeg / FFprobe runtime

XDM owns an embedded FFmpeg and FFprobe runtime for media operations that should not require Termux. The runtime is built from pinned upstream source instead of accepting opaque prebuilt executables.

## Pinned inputs

- FFmpeg 9.0.1 (`Lei`), SHA-256 pinned in `runtime/ffmpeg-runtime.json`.
- OpenSSL 3.5.8, SHA-256 pinned in the same manifest and used to provide HTTPS.
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
