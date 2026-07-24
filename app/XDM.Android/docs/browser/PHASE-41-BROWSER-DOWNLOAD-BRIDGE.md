# Phase 41: Browser Download Bridge

Phase 41 turns WebView download events into a review-first Browser → Downloader handoff.

## Goals

- Treat direct file downloads in the Browser as downloader drafts, not media captures.
- Parse filename suggestions with Android `URLUtil.guessFileName` using URL, `Content-Disposition`, and MIME type.
- Show the user a visible **Download detected** card before leaving Browser.
- Preserve the Phase 36 safety posture: external/browser downloads never auto-queue.

## UX contract

When WebView reports a download:

1. Browser shows a **Download detected** card.
2. The card displays filename, MIME type, size when known, host, and source page.
3. Primary action: **Add download**.
4. Secondary action: **Inspect media** for media-like URLs that should enter the Media review flow.
5. Dismiss keeps the Browser on the current page.

## Safety contract

- Browser download listener does not call `addDownload` directly.
- Browser download listener does not start transfers.
- Browser download listener does not treat every direct file as a media capture.
- Cookies, tokens, and authorization headers are not displayed in the card and are not persisted as raw browser handoff data.
- Add Download remains the point where destination, backend, filename, and final Start Download are confirmed.

## Deferred

- File-type rules and “always capture” settings.
- Browser page-resource list.
- Advanced per-site download permissions.
- Full private tab isolation.
- Bookmark/history redesign.

## Reference notes

Android WebView exposes `setDownloadListener` for resources the rendering engine cannot handle. Android `URLUtil.guessFileName` provides a platform filename guess from URL, content-disposition, and MIME data. Phase 41 keeps that platform seam but routes it through XDM’s review-first Add Download model.
