# XDM functional parity audit overlay

Target: `xdm_modern`  
Framework: `.NET 10`

## Scope

- Adds a machine-readable XDM 8 parity manifest.
- Adds a reusable parity loader, validator and summary model.
- Adds executable tests for schema validity, unique IDs, ownership and complete-feature evidence.
- Establishes Overlay 13 as the owner of segmented multi-connection downloading.

## Validation

```bash
dotnet restore app/XDM/XDM.Modern.sln
dotnet build app/XDM/XDM.Modern.sln --configuration Release --no-restore
dotnet test app/XDM/XDM.Modern.sln --configuration Release --no-build
```
