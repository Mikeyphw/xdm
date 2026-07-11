# XDM direct Avalonia cutover

`XDM.Modern.sln` is now the active development solution.

The legacy WPF, GTK, WinForms integration, MSIX packaging, and old aggregate solution are intentionally excluded from restore, build, run, test, and package commands. They remain in the repository only as source references until their useful behavior is ported.

## Active stack

- .NET SDK 10.0.300
- Target framework `net10.0`
- Avalonia 12.1.0
- CommunityToolkit.Mvvm 8.4.2
- Microsoft.Extensions dependency injection and console logging 10.0.9

## Commands

```bash
dotnet restore app/XDM/XDM.Modern.sln
dotnet build app/XDM/XDM.Modern.sln -c Release --no-restore
dotnet run --project app/XDM/src/XDM.App/XDM.App.csproj
dotnet run --project app/XDM/src/XDM.App/XDM.App.csproj -c Release --no-build -- --validate-bootstrap
```

## Next overlay

Port the reusable XDM core, messaging, persistence, and downloader code into normal .NET 10 class libraries referenced by `XDM.Modern.sln`. UI-coupled behavior may be stubbed or removed rather than preserving WPF/GTK compatibility.
