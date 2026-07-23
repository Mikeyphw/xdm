# XDM Android Phase 36 External Download Handoff Overlay

Target: `xdm_android`

This overlay adds the Phase 36 external download handoff layer after the Phase 35 release-candidate polish succeeded.

## Included

- Dedicated exported `ExternalAddDownloadActivity` labeled `Add to XDM` for browser and Android Sharesheet resolver visibility.
- Browser/share/download-manager handoffs now route to the existing Add Download prompt first via `PromptAddDownload`.
- `ACTION_SEND` support for `text/plain`, `text/*`, and `*/*`.
- `ACTION_VIEW` and browser download action support for `http`, `https`, and `ftp`.
- Common downloadable path patterns for APK, archives, PDF, media, playlists, and torrents.
- FTP URL normalization in the shared browser handoff policy.
- Add screen handoff card now shows source label, filename suggestion, and no-auto-queue/redaction safety copy.
- Phase 36 architecture document and validator.
- Project manifest ledger advanced to Phase 36 while keeping `next_phase = complete`.
- Final release gate, Android CI static validators, and app architecture contract coverage updated.
- Phase 34 and Phase 35 validators updated to accept the later Phase 36 current overlay.

## Not included

- No new top-level route.
- No Room migration.
- No app version bump.
- No transfer engine, queue, storage finalization, media execution, or player behavior changes.
- No raw shell exposure.
- No DRM bypass.
- No durable cookie/header/token persistence.

## Validation intent

Phase 36 fixes the external download funnel: browsers and Sharesheet entries should surface a clear `Add to XDM` option and open the existing Add Download prompt with the incoming link prefilled, without auto-queueing or leaking request secrets.

- Repaired browser/download-manager VIEW filters to set `android:autoVerify="false"` so Android lint treats them as explicit chooser-style handoff filters rather than verified App Links.
