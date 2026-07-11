# XDM direct Avalonia bootstrap overlay

Repository: `https://github.com/Mikeyphw/xdm`

This overlay starts the direct UI cutover. It creates a new .NET 10/Avalonia 12.1 application and makes `app/XDM/XDM.Modern.sln` the only devtool target.

## Included

- Dark, border-light XDM application shell.
- Downloads, Queues, Scheduler, Browser Integration, Settings, and Diagnostics navigation.
- CommunityToolkit MVVM view model.
- Microsoft.Extensions dependency injection and console logging.
- Headless `--validate-bootstrap` smoke check.
- Linux and Windows validation scripts.
- Repository-root `.devtool.toml` targeting only `xdm_modern`.

## Deliberately excluded

The overlay does not restore, build, test, run, or package the existing WPF, GTK, WinForms, MSIX, or `XDM_CoreFx.sln` projects.

Suggested commit message: `Bootstrap the .NET 10 Avalonia application`.
