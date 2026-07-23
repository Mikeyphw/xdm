# Phase 27: Media Native Direct Download Engine

Phase 27 introduces the Android-native direct media download planner. It handles direct MP4, WebM, MP3, M4A, and similar finite media requests while adaptive HLS/DASH/page-context jobs stay in the yt-dlp/Termux lane.

## Contracts

- No raw shell strings.
- No persistent header values; only transient header names and redacted previews.
- Resume is represented by safe Range previews and validator labels before byte writing exists.
- Destination policy covers app-private, MediaStore, SAF, and legacy file destinations.
- Adaptive or protected media is rejected by this lane and routed through diagnostics or Termux.
- The feature stays inside the Media route.
