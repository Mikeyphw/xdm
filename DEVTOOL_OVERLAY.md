# XDM Overlay — Responsive shell and dedicated page views

This overlay implements the second UI architecture phase on top of commit `3772fbc`.

It includes:

- a responsive Avalonia `SplitView` shell;
- full navigation at wide widths;
- a compact icon rail at medium widths;
- overlay navigation at narrow widths;
- a reusable section header with the selected section icon, title, summary, and live operation status;
- eight dedicated page views for Downloads, Queues, Scheduler, Browser Integration, Media, Conversion, Settings, and Diagnostics;
- page-local file and folder picker behavior;
- preserved Ctrl+N and Ctrl+F focus behavior through the extracted Downloads view;
- application-scoped numeric conversion resources for extracted views;
- shell architecture and page fixture qualification tests;
- a reduced `MainWindow.axaml`, from 1,258 lines to under 400 lines.

The overlay does not change download-engine behavior, persistence formats, parity evidence, or the existing eight-section view-model contract.

Validation is limited to `app/XDM/XDM.Modern.sln`. Legacy WPF, GTK, WinForms, CoreFx, and MSIX projects are not included or built.
Repair notes for this revision:

- compact navigation styles now target concrete Avalonia controls, preventing `AVLN2200`;
- the shell architecture test uses the predicate overload of `Assert.Single`, preventing `xUnit2031`.

