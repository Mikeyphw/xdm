# Overlay 15 — streaming media parity

Overlay 15 replaces media probing-only behavior with an end-to-end streaming
media workflow in the active Avalonia application.

## Native HLS

The HLS pipeline parses master and media playlists, variants, alternate audio,
subtitles, initialization maps, byte ranges, discontinuities and media sequence
numbers. VOD playlists are downloaded deterministically. Live playlists are
refreshed at a bounded cadence until the requested capture duration expires.

Identity-key AES-128 playlists are decrypted with explicit IVs or the HLS media
sequence IV rule. SAMPLE-AES, non-identity key formats and unsupported DRM are
rejected rather than downloaded incorrectly.

## Native DASH

The DASH pipeline loads MPDs with DTD processing prohibited, resolves inherited
BaseURL values, exposes video and audio representations, and expands
SegmentTemplate timelines and SegmentList entries. Dynamic MPDs refresh using
the declared minimum update period and honor the selected live-capture window.

## Recovery and safety

Each format owns an atomic JSON checkpoint. Completed fragment IDs and byte
counts survive cancellation and process restart. Downloads use bounded retries,
cancellation-aware exponential backoff, bounded manifest/key reads and HTTP or
HTTPS URLs only. Temporary assembly and finalization files are atomically moved
into place.

External tools are never invoked through a shell. FFmpeg receives an explicit
argument list and performs stream-copy muxing. yt-dlp is used only as a
structured catalog provider; cookies and request headers are written to a
private temporary configuration file, removed after the invocation, and never
placed on the process command line.

## Avalonia workflow

The Media page discovers native HLS/DASH formats or yt-dlp-supported pages,
shows video, audio and subtitle choices, reports FFmpeg and yt-dlp health,
accepts a bounded live duration, exposes progress and cancellation, and keeps
fragment checkpoints for resume.

## Deterministic coverage

Recorded HLS, DASH and yt-dlp fixtures cover parsing, representation discovery,
AES-128 decryption, retry/checkpoint behavior, deterministic fragment ordering,
tool argument safety, provider fallback, direct downloads and FFmpeg
finalization.
