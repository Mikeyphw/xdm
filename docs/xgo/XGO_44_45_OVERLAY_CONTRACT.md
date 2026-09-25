# XGO-44+45 overlay contract

- Target: `xgo_scheduler`
- Validation required: yes
- Deferred validation: no
- Failure action: pause
- Validation tasks override: absent
- Tooling setup changes: forbidden and not present

## Target-local validation DAG

`xgo_scheduler#validate` runs:

1. `runner:go#validate`
2. `queue_model_matrix`
3. `dependency_graph_stress`

## Boundary

XGO-44+45 is a scheduler-state overlay. It introduces canonical queue/dependency behavior without starting XGO-46 runtime conditions, XGO-47 arbitration, or XGO-48 completion-action policy.
