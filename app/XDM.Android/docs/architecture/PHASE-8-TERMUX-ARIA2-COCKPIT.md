# Phase 8: Termux aria2 Cockpit

Phase 8 promotes the Phase 7 Termux bridge from generic tool probing to a controlled aria2 operations cockpit.

## Rules

- XDM must not expose a raw shell or raw root endpoint.
- The Termux aria2 backend is optional and disabled by default.
- The Android app generates and stores the RPC secret.
- The daemon listens only on loopback.
- The session file, logs, and default download directory live under Termux home.
- Actions are typed: start, stop, probe, save session, list active tasks, pause all and resume all.
- Native Android and embedded aria2 remain available when Termux is missing or disabled.
- Root is not required for the cockpit.

## Default paths

The app resolves Termux paths from the installed Termux package data directory, not by hardcoding Android data paths.
The default state directory is `~/.local/share/xdm/aria2` inside Termux, with `aria2.session` and `aria2.log` stored there.

## Safety

The cockpit is an operator panel, not a shell launcher. Every command is generated from `XdmTermuxCommand` templates and sent through Termux `RUN_COMMAND` with a result `PendingIntent`.
