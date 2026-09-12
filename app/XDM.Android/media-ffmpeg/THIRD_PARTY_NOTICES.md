# Embedded media runtime third-party notices

The generated XDM Android FFmpeg runtime contains components built from the exact pinned source archives declared in `runtime/ffmpeg-runtime.json`.

- FFmpeg 9.0.1 — configured for `LGPL-2.1-or-later` operation with GPL and nonfree features disabled.
- OpenSSL 3.5.8 — Apache License 2.0.

The build copies the authoritative license files from the hash-verified source archives into APK assets. The generated `runtime/ffmpeg-runtime.lock.json` records the hashes of those license files together with binary and source provenance.

XDM invokes FFmpeg/FFprobe as separate app-owned processes. XDM application code is not linked to FFmpeg libraries.
