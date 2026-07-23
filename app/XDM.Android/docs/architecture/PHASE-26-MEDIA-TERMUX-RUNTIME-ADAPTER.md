# Phase 26: Media Termux Runtime Adapter

Phase 26 converts worker bridge requests into typed Termux runtime launch plans for yt-dlp and aria2. It remains a planning layer while validation is deferred.

## Contracts

- No raw shell strings are exposed.
- yt-dlp, aria2c, ffmpeg, and ffprobe are represented as capability probes with install/help diagnostics only.
- Netscape cookie files, aria2 input files, aria2 session files, and transient header manifests are process-scoped and delete-after-terminal.
- Cookies, Authorization headers, bearer values, tokenized URLs, and signed parameters are redacted from previews, diagnostics, cleanup summaries, and sidecars.
- The feature stays inside the Media route.
