# XGO-46 overlay contract

- **Target:** `xgo_scheduler`
- **Validation:** required, `failure_action = pause`
- **Validation tasks override:** absent
- **Covered capability IDs:** `XGO-CAP-SCHED-001`, `XGO-CAP-SCHED-002`
- **State ownership:** Go owns runtime-condition and schedule-window eligibility; hosts only supply runtime snapshots and execute platform effects.
- **Forbidden shortcuts:** no host-owned scheduling decisions, no wall-clock-only tests, no Devtool setup changes, no generic validation wrapper.
- **Merge decision:** standalone after reviewing XGO-46+47+48 because runtime/timezone semantics have a different failure surface from arbitration and bandwidth/completion actions.
