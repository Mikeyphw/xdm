# XGO-44+45 implementation report — queue model and dependency graph

## Scope

This overlay merges XGO-44 and XGO-45 after the XGO-44+45+46 merge-window review. XGO-44 and XGO-45 share one durable scheduler-state boundary: persisted queue membership and dependency eligibility both require canonical download identity, revisioned mutation, and crash-safe SQLite ownership. XGO-46 remains separate because runtime conditions and timezone schedule windows introduce clock/timezone and host snapshot failure surfaces.

## Delivered

- Added `engine/scheduler` as the canonical queue/dependency package.
- Activated `xgo_scheduler` on the native Go runner.
- Added queue create/update/delete, explicit enabled/priority/concurrency/bandwidth/conditions/schedule/retry policy persistence, revision conflicts, single-active queue membership, move/reorder semantics, and delete-with-members policy handling.
- Added durable dependency edges with `completion_required` and `success_required` requirements.
- Added cycle-safe mutation and deterministic eligibility explanations for blocked dependencies.
- Added the `download_dependencies` SQLite table and indexes.
- Promoted `XGO-CAP-QUEUE-001` and `XGO-CAP-QUEUE-002` to IMPLEMENTED.
- Added target-local validation jobs `queue_model_matrix` and `dependency_graph_stress` to `xgo_scheduler#validate`.

## Audit loop evidence

- queue model package tests: PASS
- dependency graph package tests: PASS
- `queue_model_matrix`: PASS
- `dependency_graph_stress`: PASS
- full engine tests: PASS
- scoped vet: PASS

## Non-scope

XGO-46 runtime host conditions and timezone schedule windows are intentionally deferred to the next overlay. XGO-47 arbitration/fairness and XGO-48 bandwidth/completion-action policy remain planned.
