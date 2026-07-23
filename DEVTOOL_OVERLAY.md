# xdm_android_media_resolver_player_overlay

Target: `xdm_android`

Adds Phase 19 media resolver/player work on top of the browser media continuity state:

- HLS/DASH grouped picker UI for video quality, audio tracks, and subtitles.
- yt-dlp metadata preview before download, including title, thumbnail, duration, extractor, and format count.
- Redacted cookie/header/session handoff models for yt-dlp, aria2, and native planning.
- Typed Termux yt-dlp extra arguments and redacted media pipeline diagnostics.
- Media3 direct player card for non-adaptive direct media.
- Protected-media diagnostics panel that does not bypass or queue DRM-protected media.
- Static validators and unit contracts for resolver planning and no cookie/token diagnostic leaks.
- Termux/chroot build hardening: keep packaged JNI debug symbols to avoid the x86_64 Linux `llvm-strip` path on ARM hosts.
- Lint hardening for existing `mipmap-anydpi-v26` checkout drift when `minSdk` is already 26.

Validation entry point: `app/XDM.Android/tools/validate-media-resolver-player.py`.
