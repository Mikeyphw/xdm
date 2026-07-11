# XDM Avalonia queue and scheduler runtime overlay

Base commit: `84a0e06`

Target: `xdm_modern`

This overlay makes queues and schedules executable. It persists queue membership/order, supports simultaneous active queues, enforces global and per-queue concurrency and bandwidth policies, and evaluates the configured schedule at startup and while XDM is running.

Validation:

```bash
./app/XDM/eng/validate-modern.sh
```

Commit message:

```text
Add queue execution and scheduler runtime
```
