# Overlay 17: queue completion actions

Overlay 17 extends the modern queue scheduler from one compatibility window to a
persisted collection of independent schedules. Each schedule selects a queue,
day set, local start/end window, missed-run policy, and optional completion
action.

## Scheduling behavior

- Up to 64 normalized schedule definitions are stored in `ApplicationSettings`.
- Each schedule supports any custom Monday–Sunday combination.
- Overnight windows retain the start day's day mask.
- The scheduler stores its last evaluation and the last started window per
  schedule in `scheduler-state.json` through an atomic temporary-file move.
- `Skip` ignores a start that elapsed while XDM was unavailable.
- `RunImmediately` starts the latest missed window once, then persists the
  window identity to prevent duplicate execution.
- Multiple schedules may run different queues concurrently. A queue is stopped
  only when no active schedule or unfinished scheduled run still owns it.

## Completion workflow

A scheduled run tracks the non-terminal downloads belonging to its queue. When
the tracked work reaches a terminal state, XDM can:

- do nothing;
- exit XDM;
- shut down the computer;
- request sleep or hibernation;
- log out the current user; or
- launch one configured executable directly.

Destructive and command actions wait for a configurable 0–300 second countdown.
The pending action is visible in the Scheduler page and can be cancelled before
execution.

## Platform safety

Power actions are enabled only when a compatible fixed system executable is
present. Linux uses direct `systemctl`/`loginctl` argument lists. Windows uses
fixed `shutdown.exe` and `rundll32.exe` actions. macOS exposes only the fixed
commands that can be identified without shell scripting.

Custom completion commands require an absolute existing executable path. XDM
uses `ProcessStartInfo.ArgumentList`, disables shell execution, captures bounded
output, applies a timeout, and terminates the process tree on timeout or user
cancellation.

## Antivirus scanning

The optional antivirus step runs before the completion countdown. It also
requires an absolute existing executable and a bounded argument list. `{file}`
is replaced inside an individual argument; when no placeholder is supplied, the
completed file path is appended as the final argument. A non-zero exit, timeout,
missing file, or unavailable scanner cancels the later completion action.

## Download priority

Downloads now persist `Low`, `Normal`, or `High` priority. When a stopped queue
is activated, higher-priority queued items start before lower-priority items,
while their stable queue order is preserved within the same priority class.
