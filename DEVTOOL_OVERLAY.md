# XDM Avalonia diagnostics, recovery, and packaging overlay

Apply after `xdm_avalonia_browser_media_overlay.zip` / commit `17087f0`.

Target: `xdm_modern`

This overlay clears the four existing CA1826 warnings, adds structured diagnostics and redacted bundle export, crash-marker recovery with safe mode, and self-contained Linux/Windows packaging scripts.

Validation:

```bash
./app/XDM/eng/validate-modern.sh
```

Commit message:

```text
Add diagnostics recovery and packaging
```
