# Phase 30: Browser Capture Quality Pass

Phase 30 improves the browser/media sniffer decision layer without adding new WebView hooks, Room migrations, or top-level routes.

## Goals

- Group related captures by host, media kind, and stable path without retaining tokenized query strings in diagnostics.
- Reduce junk captures such as analytics beacons, pixels, tiny/noisy assets, and duplicate manifests.
- Label stale metadata, expired session URLs, live media, and protected media before dispatch.
- Provide confidence scores and safe diagnostics inside the existing Media route.

## Guardrails

- No raw shell.
- No cookie/header/token persistence.
- No new top-level route.
- No DRM bypass.
- Validation remains deferred until the final media gate.
