# XDM Avalonia desktop completion overlay

Base commit: `a69a6e7`
Target: `xdm_modern`
Framework: `.NET 10`

## Scope

- Clears the remaining CA1859 warning.
- Adds tray/background behavior and explicit tray exit.
- Adds best-effort native desktop completion/failure notifications.
- Adds a virtualized downloads list, selected-download details, and a bounded event timeline.
- Adds `XDM.NativeHost` and user-level browser native-host manifest repair.
- Publishes the native host beside XDM for Linux and Windows.
- Adds tests for notification command selection and browser-host manifest repair.
- Adds an explicit legacy UI cleanup script.

## Validation

```bash
dotnet restore app/XDM/XDM.Modern.sln
dotnet build app/XDM/XDM.Modern.sln --no-restore
dotnet test app/XDM/XDM.Modern.sln --no-build
```
