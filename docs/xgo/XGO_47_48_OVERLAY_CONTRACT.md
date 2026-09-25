# XGO-47 + XGO-48 overlay contract

- Target: `xgo_scheduler`
- Validation: required
- Failure action: pause
- `validation.tasks`: absent
- Covered capabilities:
  - `XGO-CAP-SCHED-003`
  - `XGO-CAP-SCHED-004`
  - `XGO-CAP-SCHED-005`
  - `XGO-CAP-SCHED-006`

Forbidden shortcuts:

- No Devtool reinstall or refresh commands.
- No platform-owned scheduler decision copy.
- No randomization in runnable ordering.
- No completion action without idempotency key.
- No destructive platform action without host approval.
