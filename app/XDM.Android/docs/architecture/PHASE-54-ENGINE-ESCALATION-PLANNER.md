# Phase 54 - Engine Escalation Planner

Phase54 adds a review-only method planner to external Add Download handoffs. The planner explains the safest next path before a transfer is queued, especially when a request is likely to fail through the plain Native path.

## Goals

- Recommend **XDM Native with captured session** for expiring or signed-in requests while the browser context is still fresh.
- Recommend **media resolver or yt-dlp** for HLS, DASH, watch pages, and unknown page-shaped handoffs.
- Recommend **aria2 segmented transfer** for large direct files that do not depend on browser session context.
- Recommend **Refresh from browser** when a server asks for browser access instead of encouraging blind retry loops.

## UI contract

The Add Download sheet shows only human labels:

- Suggested method
- Next action
- Reason
- Request shape
- Session context
- Fallback path
- Safe alternatives

The planner does not show raw URLs, raw header names, Cookie values, Authorization values, bearer tokens, credential-bearing query values, backend enum names, or machine diagnostics in normal UI.

## Safety boundary

Phase54 is a planner only. It does not start transfers, fetch pages, inspect files, persist session headers, add an automatic upload path, require all-files access, add a top-level route, reopen Debug Workbench, or change Room schema 14.
