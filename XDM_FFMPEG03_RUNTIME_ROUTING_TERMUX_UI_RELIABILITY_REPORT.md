# XDM Android FF03 runtime routing / Termux / UI / reliability report

FF03 is the third full FFmpeg roadmap overlay. It converts runtime choice from an implicit implementation detail into an explicit persisted policy and adds a production-safe Termux FFmpeg fallback without making Termux a normal dependency.

Delivered scope:

- Automatic / Embedded FFmpeg / Termux FFmpeg persisted runtime preference, defaulting to Automatic.
- Embedded-first automatic routing with verified Termux fallback only for public, header-free, non-tokenized, non-private-network sessions.
- Dedicated Termux FFmpeg adaptive/live lanes, worker classification, dispatcher runbooks, and typed tool requirements.
- FFmpeg + FFprobe fresh-probe readiness gate before external execution.
- Durable public-only fallback input model with no captured Cookie/Authorization/header injection.
- Stream-copy FFmpeg fallback with mandatory non-empty output and FFprobe playable-stream verification before Android-owned publication.
- Expected-duration persistence and `out_time_us` progress conversion for external FFmpeg jobs.
- Two-hour adaptive and 24-hour live managed-job timeout policy.
- Advanced Settings runtime selector, Developer Center runtime truth, and redacted support-bundle diagnostics.
- Routing/UI regression tests and a dedicated FF03 validator chained to FF02/FF01.
- Devtool preflight and final static release-gate wiring.

FF03 intentionally keeps yt-dlp as a distinct extractor/site fallback. It does not send authenticated media to Termux and does not weaken the app-owned embedded FFmpeg path.
