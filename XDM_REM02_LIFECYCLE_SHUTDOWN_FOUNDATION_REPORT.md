# XDM Desktop REM02 — Lifecycle, admission freeze, shutdown, and window-state foundation

## Scope

Closes the REM02 ownership set from the frozen S00–S16 audit: S01-01, S01-02, S01-03, S01-04, S01-07, S01-09, S16-02, S16-03, and S16-04.

## Implementation

- Application state now starts with `CoreReady=false`; required startup services explicitly transition it to ready only after successful initialization.
- Download admission has an atomic shutdown barrier (`FreezeAdmission`) checked both before request work and again under the organization admission lock, eliminating post-snapshot transfer admission.
- Shutdown is coordinated as an owned async transaction: freeze admission → snapshot active work → durable checkpoint drain → service teardown → clean-session marker deletion.
- The running-session marker is retained if checkpointing or service teardown fails.
- Download-manager shutdown and disposal share one 15-second deadline; disposal no longer reintroduces unlimited waits after the drain budget expires, and it avoids disposing synchronization primitives beneath still-running timed-out tasks.
- Window close persistence is no longer `async void`; expected filesystem failures are contained by the window-state store.
- Restored window placement is validated against current display working areas and recovered onto the primary available display when stale/off-screen.
- Exit intent is explicit (`UserRequested`, `ApplicationRequested`, `RecoveryRestart`) instead of a single overloaded Boolean state.
- Escape cancellation is contextual and cancels at most one active subsystem in priority order instead of broadcasting to media, conversion, and scheduler completion actions.

## Regression coverage

- Core readiness begins false and changes only explicitly.
- Admission is rejected after the shutdown barrier closes.
- Existing active-transfer checkpoint shutdown coverage remains in place.
- Off-screen placement recovery and valid-placement preservation are covered in App tests.

## Validation policy

REM02 is an intermediate remediation overlay. Apply with `--no-validate` per the remediation campaign policy. Full cross-overlay validation remains reserved for the final seal; targeted tests can still be run manually when desired.
