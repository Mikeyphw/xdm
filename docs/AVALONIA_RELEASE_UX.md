# XDM Avalonia release UX

This phase prepares the direct Avalonia port for daily use without restoring any WPF, GTK, WinForms, or MSIX build path.

## Included

- Cross-platform single-instance locking and activation handoff.
- Persistent main-window size, position, and maximized state.
- `--reset-window-state` support wired to the persisted placement store.
- Download search across filename, URL, destination, and queue.
- Download status filters.
- Checkbox-based bulk pause, resume, cancel, and history removal.
- Self-contained publish smoke tests for Linux and Windows.
- Analyzer fixes carried from the engine-hardening phase.

## Validation

```bash
./app/XDM/eng/validate-modern.sh
```

Full Linux release qualification, including a self-contained publish and bootstrap smoke test:

```bash
./app/XDM/eng/qualify-modern.sh
```

Windows package smoke test:

```powershell
./app/XDM/eng/smoke-package.ps1
```
