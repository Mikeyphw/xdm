# XDM Avalonia prerelease cutover overlay

Base commit: `96ab637`
Target: `xdm_modern`
Framework: `.NET 10`
Version: `9.0.0-preview.1`

## Scope

- Establishes modern-only CI and disables the old WPF workflow.
- Adds preview versioning, changelog, migration guide, and release checklist.
- Adds large-history performance regression tests and bootstrap benchmark output.
- Adds Linux and Windows packaged-build qualification scripts.
- Makes devtool packaging execute full prerelease qualification.
- Expands the explicit legacy cleanup script without deleting files during overlay validation.

## Validation

```bash
dotnet restore app/XDM/XDM.Modern.sln
dotnet build app/XDM/XDM.Modern.sln --configuration Release --no-restore
dotnet test app/XDM/XDM.Modern.sln --configuration Release --no-build
dotnet run --project app/XDM/src/XDM.App/XDM.App.csproj --configuration Release --no-build -- --validate-bootstrap
```
