# XGO-47 + XGO-48 implementation report

## Scope

This overlay implements Wave 6 scheduler execution policy:

- XGO-47 queue arbitration, fairness, retry activation, and durable global pause.
- XGO-48 bandwidth profile precedence and idempotent completion actions.

XGO-49 is not included because it starts the `xgo_media` wave boundary.

## Ownership

The Go scheduler now owns runnable-work decisions. Hosts supply runtime condition snapshots and execute platform completion requests, but they do not decide scheduler eligibility, bandwidth precedence, or completion-action idempotency.

## Core invariants

- Scheduler decisions are deterministic under a fake clock and stable input.
- Global pause is durable and revision-fenced through canonical engine metadata.
- Dependency blocks, retry deadlines, runtime holds, queue capacity, global capacity, and host capacity produce explicit explanations.
- Bandwidth precedence is global -> schedule -> queue -> download, with the narrowest present scope selected.
- Completion actions produce canonical idempotency keys and fire only once per terminal event/action identity.
- Host acceptance or decline is persisted; destructive actions require host approval.

## Validation

`xgo_scheduler#validate` now includes six nodes:

1. native Go runner
2. `queue_model_matrix`
3. `dependency_graph_stress`
4. `conditions_time_matrix`
5. `scheduler_stress`
6. `bandwidth_completion_matrix`

The audit loop also ran fixture lint, fixture secret scan, full `go test ./engine/...`, and full `go vet ./engine/...`.
