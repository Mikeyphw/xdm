# Avalonia diagnostics, recovery, and packaging

This phase adds a bounded structured diagnostic event stream, secret-redacted ZIP export, unclean-shutdown detection, `--safe-mode`, `--reset-window-state`, and self-contained packaging scripts.

## Recovery

```bash
dotnet run --project app/XDM/src/XDM.App/XDM.App.csproj -- --safe-mode
dotnet run --project app/XDM/src/XDM.App/XDM.App.csproj -- --reset-window-state
```

Safe mode starts the settings and download history services but skips automatic scheduler and browser-integration startup.

## Packaging

```bash
bash app/XDM/eng/package-linux.sh 0.1.0-dev
pwsh app/XDM/eng/package-windows.ps1 -Version 0.1.0-dev
```

Outputs are written under `artifacts/packages`. Linux packages are self-contained tarballs; a DEB is also produced when `dpkg-deb` is available. Windows packaging produces a self-contained ZIP.
