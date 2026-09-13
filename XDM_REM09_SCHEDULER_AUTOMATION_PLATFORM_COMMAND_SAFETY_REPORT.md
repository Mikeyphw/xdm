# XDM Desktop REM09 — Scheduler, completion automation, platform command safety, and destructive-action cancellation

## Scope

Closes the REM09 ownership set from the frozen S00–S16 audit: S06-01 through S06-17, S13-01 through S13-05, S13-09, and S16-05.

## Implementation

- Scheduler evaluations are coalesced through a single request pump instead of starting a durable evaluation from every application-state/progress snapshot.
- Application-state events are additionally debounce-gated before scheduling evaluation work.
- Active schedule runs now have durable versioned identities derived from the schedule definition, queue, window, completion action, and profile override.
- Runtime state persists active schedule runs and prunes stale once-per-window keys against the live versioned schedule set.
- Schedule edits create a new schedule-version key instead of mixing the old run and new definition.
- Expired runs no longer absorb later unrelated queue downloads; downloads are associated only while the run window is active, including downloads completed entirely inside that active window.
- Queue ownership is reference-counted so overlapping schedules and manual queue activity are not represented by one uncounted scheduler root.
- Completion workflows are independent; one schedule finishing no longer cancels another schedule's countdown/scan/action workflow.
- The visible Cancel action now cancels antivirus scanning and countdown/execution preparation because antivirus scans publish a pending cancellable action.
- A final cancellation gate runs immediately before any destructive completion action is invoked.
- Periodic-loop, event-triggered evaluation, and completion-workflow exceptions are contained and surfaced as scheduler status instead of killing the automation loop silently.
- Schedule editor save validation now rejects malformed HH:mm values, zero-weekday schedules, over-limit countdowns, missing RunCommand executable paths, invalid antivirus timeout values, and more than 64 schedules.
- Completion countdown and antivirus timeout normalization now preserve the full UI-supported 24-hour range.
- The application no longer silently drops schedules above 64 during normalization; the UI refuses to create/save invalid overflow instead.
- Scheduler state loading recovers from `.bak` if the primary state is corrupt or transiently unreadable, and corrupt primary JSON is quarantined.
- Linux logout no longer uses `loginctl terminate-user`; it is offered only for the current `XDG_SESSION_ID` via `terminate-session`.
- Windows Sleep/Hibernate no longer use unsupported `rundll32 powrprof.dll,SetSuspendState`; they are reported unsupported unless a future native implementation is provided.
- Power capability discovery now reports current-session availability and unsupported reasons instead of equating executable presence with authorization.
- `PlatformCommandRunner` now captures stdout/stderr incrementally with a hard bounded buffer, applies hard timeout/kill semantics, and reports unconfirmed process-tree termination as failure.
- Platform shell/open failures are converted into user-facing operation errors.

## Regression coverage

- Scheduler normalization disables zero-weekday schedules instead of running them every day.
- Completion countdown and antivirus timeout retain the 86,400-second UI range.
- Scheduler state recovers once-per-window identity and active runs from backup when the primary file is corrupt.
- Platform command capabilities report unsupported reasons.
- Linux logout discovery is guarded against unsafe `terminate-user`.
- Platform command output capture is bounded before data is accumulated in memory.

## Validation policy

REM09 is an intermediate remediation overlay. Apply it with `--no-validate`; the full explicit desktop validation matrix remains reserved for REM18.
