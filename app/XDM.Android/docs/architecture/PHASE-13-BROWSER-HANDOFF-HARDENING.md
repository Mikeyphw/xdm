# Phase 13: Browser handoff hardening

Phase 13 strengthens external browser/share/Tasker intake without adding a new top-level route.

## Scope

- Normalize HTTP/HTTPS URLs before idempotency checks.
- Collapse duplicate external URL handoffs across browser, share sheet, Tasker, and deep links.
- Preserve safe origin attribution for Diagnostics.
- Redact sensitive headers before persistence.
- Persist structured rejection reasons for unsupported or malformed handoffs.

## Privacy contract

Sensitive headers such as `Authorization`, `Cookie`, `Set-Cookie`, `Proxy-Authorization`, `X-Api-Key`, and `X-Auth-Token` are redacted before command records are saved. Product UI surfaces only source, origin host, status, and rejection reason. It must never render raw headers or cookies.

## Topography

The existing Add, Media, Downloads, and Diagnostics routes absorb all browser handoff behavior. No new top-level route is introduced.
