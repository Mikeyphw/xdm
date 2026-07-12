# XDM Overlay 17 — queue completion actions

Base: confirmed functional commit `92c63a4`

Target: `xdm_modern`

Active solution: `app/XDM/XDM.Modern.sln`

## Included

- Multiple independent queue schedules with day masks and overnight windows.
- Atomic persisted scheduler checkpoints and `Skip` / `RunImmediately`
  missed-run policies.
- Low, normal and high per-download priority with durable queue ordering.
- Queue-run completion tracking across application-state transitions.
- Optional antivirus scan before completion actions.
- Cancellable countdown for exit, shutdown, sleep, hibernate, logout and direct
  executable actions.
- Linux, Windows and macOS capability discovery without shell scripts.
- Avalonia schedule editor, capability health, pending-action status and cancel
  control.
- Deterministic scheduler, persistence, command-safety, antivirus and priority
  tests.
- The deferred `CA1861` conversion-queue test warning fix.
- Executable parity-ledger and documentation updates.

## Safety properties

Configurable commands and antivirus scanners require absolute existing
executable paths. Arguments are passed through `ProcessStartInfo.ArgumentList`;
no shell is started and argument text is never interpreted as shell syntax.
Output capture is bounded, execution has a timeout, and cancellation terminates
the process tree. Platform power actions use only fixed discovered system
commands. A failed or unavailable antivirus scan prevents the later destructive
action.

## Validation scope

Only the modern solution is allowed:

```bash
dotnet restore app/XDM/XDM.Modern.sln
dotnet build app/XDM/XDM.Modern.sln --configuration Release --no-restore
dotnet test app/XDM/XDM.Modern.sln --configuration Release --no-build
```
