# XDM Avalonia engine-hardening overlay

Apply after `xdm_avalonia_diagnostics_recovery_packaging_overlay.zip` / commit `0298638`.

Target: `xdm_modern`

This overlay fixes the remaining diagnostics analyzer warning and adds validated resume, retry/backoff, disk preflight, atomic checkpoints, and crash-safe finalization recovery.

Validation:

```bash
./app/XDM/eng/validate-modern.sh
```

Commit message:

```text
Harden download resume recovery and finalization
```
