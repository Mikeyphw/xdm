# XDM Avalonia release UX overlay

Apply after `xdm_avalonia_engine_hardening_overlay.zip` / commit `8a78305`.

Target: `xdm_modern`

This overlay fixes the three remaining analyzer warnings and adds single-instance activation, persisted window placement, download search/status filters, checkbox-based bulk actions, and package smoke-test scripts.

Validation:

```bash
./app/XDM/eng/validate-modern.sh
```

Optional full Linux package qualification:

```bash
./app/XDM/eng/qualify-modern.sh
```

Commit message:

```text
Add single instance release UX and package qualification
```
