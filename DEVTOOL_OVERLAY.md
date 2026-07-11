# XDM Avalonia essential workflows overlay

Base commit: `5fcc69c`

Target: `xdm_modern`

This overlay adds multiline batch downloads, request headers and authentication, duplicate-file policies, persisted settings, category and queue definitions, scheduler configuration, clipboard URL capture, startup concurrency limits, and default/per-download speed limits.

Validation:

```bash
./app/XDM/eng/validate-modern.sh
```

Commit message:

```text
Add essential Avalonia download workflows
```

Known transitional limits:

- Queue and scheduler definitions are persisted, but execution gating is deferred to the next engine integration pass.
- Authentication and custom headers are intentionally not persisted in download history.
- Changing maximum concurrency takes effect after restarting XDM; speed-limit changes are read live.

Retry fixes included:

- Treat an existing `.part` file as resumable state instead of an auto-rename collision.
- Guard Avalonia clipboard access when the platform clipboard is unavailable.
