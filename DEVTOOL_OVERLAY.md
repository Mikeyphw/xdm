# XDM Overlay 21 — final parity gate

Base: confirmed successful commit `55ad296`

Target: `xdm_modern`

Active solution: `app/XDM/XDM.Modern.sln`

## Included

- 100% qualified critical and high-priority parity.
- Repository-local implementation and automated-test evidence validation.
- Unknown-length HTTP response qualification and segmented fallback coverage.
- Representative legacy settings, history and scheduler migration fixtures.
- Linux and Windows build/test/bootstrap/self-contained-package CI gates.
- Enforced absence of known WPF, GTK, WinForms, legacy CoreFx and compatibility source paths.
- Explicit modern replacement policies for FTP/FTPS and in-process self-update.
- Final release and parity documentation.

## Validation scope

Only the modern solution is allowed:

```bash
dotnet restore app/XDM/XDM.Modern.sln
dotnet build app/XDM/XDM.Modern.sln --configuration Release --no-restore
dotnet test app/XDM/XDM.Modern.sln --configuration Release --no-build
```
