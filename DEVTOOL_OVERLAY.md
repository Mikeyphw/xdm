# XDM Avalonia Core Port Overlay

Base: commit `3072002`

This overlay adds the first active UI-independent projects to `XDM.Modern.sln`:

- `XDM.Core`
- `XDM.Platform`
- `XDM.Core.Tests`

It introduces modern category, queue, schedule, download snapshot, application state, platform information, and UI/platform abstraction types. The Avalonia shell now resolves these services through dependency injection and displays actual core/runtime state.

It also fixes both warnings from the bootstrap validation:

- CA1848 by using a source-generated `LoggerMessage` method.
- AVLN3001 by adding a public parameterless `MainWindow` constructor while retaining DI construction.

Legacy WPF, GTK, WinForms, MSIX, and `XDM_CoreFx.sln` projects remain excluded.
