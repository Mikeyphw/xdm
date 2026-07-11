# Avalonia functional downloader

This overlay makes the .NET 10 Avalonia application capable of direct HTTP/HTTPS downloads.

Implemented:

- live progress, speed, ETA, and state snapshots;
- resumable `.part` files using HTTP Range requests;
- pause, resume, cancel, retry, and history removal;
- atomic final move from `.part` to the requested destination;
- atomic JSON history persistence and startup restoration;
- destination-folder picker;
- focused completion, resume, and persistence tests;
- removal of the six analyzer warnings reported after phase 2.

The legacy WPF, GTK, WinForms, MSIX, and `XDM_CoreFx.sln` projects remain excluded.
