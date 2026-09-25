# XGO-46 implementation report — runtime conditions and schedule windows

## Scope

XGO-46 keeps the Wave 6 merge boundary standalone. It adds one canonical scheduler policy input for host-supplied runtime conditions and timezone-aware schedule windows. XGO-47 will consume the hold decisions during queue arbitration; XGO-48 will consume bandwidth/completion policy later.

## Produced behavior

- Runtime snapshots include online, metered, Wi-Fi, charging, battery, storage and power source.
- Go evaluates runtime eligibility and emits typed hold reasons.
- Schedules use injected timezone resolution and local civil-time windows.
- Overnight windows, DST forward/back transitions, missed-run policy and restart persistence are covered by tests.
- Queue policies can reference persisted schedules and runtime condition names without moving evaluation back to host code.

## Audit-loop fixes

The first condition-policy draft made metered rejection the zero value. The audit loop corrected this by making `RequireUnmetered` explicit so an empty condition policy remains neutral, while the `unmetered` queue condition still produces the `metered` hold reason.

## Validation

- `go test ./engine/scheduler ./engine/store/sqlite ./engine/cmd/xgo-scheduler-audit`
- `xgo-scheduler-audit --mode queue_model`
- `xgo-scheduler-audit --mode dependency_graph`
- `xgo-scheduler-audit --mode conditions_time`
- fixture lint and secret scan
- Devtool topology audit
- `go test ./engine/...`
- `go vet ./engine/...`
