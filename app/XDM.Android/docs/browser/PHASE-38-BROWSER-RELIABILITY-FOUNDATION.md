# Phase 38: Browser Reliability Foundation

Phase 38 turns the first-class Browser surface from Phase 37B into a reliable entry point instead of a hidden or blank WebView. It does not add browser chrome, bookmarks, history management surfaces, adblock, proxy chains, or a media cockpit. Those remain later phases.

## Goals

- Show a real Browser start page when no URL is loaded.
- Eliminate the white-screen cold start.
- Surface loading progress from WebChromeClient.
- Surface main-frame WebView errors, HTTP errors, and SSL failures as visible UI.
- Cancel SSL failures instead of proceeding through certificate problems.
- Detect pages that finish loading without visible content and show a retry/help card.
- Let users retry, open the URL externally, or send the URL to Add Download.
- Let XDM Browser receive http/https VIEW links directly into the Browser route.

## Non-goals

- No new Room migration.
- No version bump.
- No transfer engine changes.
- No media execution changes.
- No silent auto-queue.
- No Browser extension system.

## Implementation notes

The Browser route now owns a small reliability state model: start page, loading, loaded, error, and blank-page states. The embedded WebView reports progress through WebChromeClient and reports main-frame failures through WebViewClient callbacks. SSL errors are cancelled and displayed. A delayed blank-page probe checks whether the finished document exposes visible text, media, iframe, canvas, or a meaningful body box before declaring a blank page.

BrowserActivity now accepts generic http/https VIEW links with `android:autoVerify="false"`, because XDM Browser is not claiming verified ownership of arbitrary domains. The link is normalized, written into browser startup state, and consumed by BrowserScreen.
