# FF03 — Runtime routing, Termux fallback, product UI, and reliability

FF03 makes the FFmpeg runtime source an explicit product policy while keeping the embedded FFmpeg/FFprobe pair as XDM Android's normal owner.

## Runtime policy

`Automatic` is the default and always chooses the embedded runtime when its capability probe passes. It may use Termux only when the embedded runtime is unavailable, the Termux RUN_COMMAND bridge has a fresh successful probe, both `ffmpeg` and `ffprobe` are verified, and every media input is a public header-free URL without credential-bearing query parameters.

`Embedded FFmpeg` never crosses into Termux. `Termux FFmpeg` is an explicit advanced choice and is still subject to the same session-safety boundary. Cookies, Authorization headers, signed/tokenized URLs, user-info URLs, private/loopback/local targets, and variant-specific request headers never cross into the external process boundary.

## yt-dlp boundary

yt-dlp remains a separate site/extractor fallback. FF03 does not make yt-dlp the normal download owner and does not couple the FFmpeg runtime selector to yt-dlp. Resolved adaptive tracks continue through XDM's FFmpeg execution plan; yt-dlp is used only when the resolver plan explicitly requires it.

## External FFmpeg fallback

A safe fallback becomes its own `TermuxFfmpegAdaptive` or `TermuxFfmpegLive` execution lane. The durable job carries only public session-safe URLs and non-secret stream roles. FFmpeg receives argument-quoted inputs, stream-copies selected tracks, writes into the Android-owned bridge staging artifact, and must produce a non-empty playable output that FFprobe can inspect before Android publication is allowed.

The generic 30-minute automation timeout is not reused for this lane: adaptive fallback gets a bounded two-hour window and live fallback gets the maximum 24-hour managed-job window. Expected duration is persisted as non-secret job metadata and is used to turn FFmpeg `out_time_us` progress into user-facing percentages when duration is known.

## Product and diagnostics

Advanced settings expose Automatic / Embedded FFmpeg / Termux FFmpeg with the security boundary explained next to the selector. Developer Center reports the selected source policy, embedded runtime health, and fresh Termux FFmpeg/FFprobe verification state. The support bundle includes the same routing truth without raw URLs, cookies, Authorization values, or signed query data.

## Failure behavior

Automatic fallback fails closed. If embedded FFmpeg is unavailable and the media session is not safe for Termux, XDM asks for embedded-runtime repair instead of silently leaking the session externally. Explicit Termux selection also fails closed when the probe is stale or either FFmpeg tool is missing. Cancellation, timeout, publication preparation/commit/reconciliation, and recovery remain owned by the existing durable Termux job state machine.
