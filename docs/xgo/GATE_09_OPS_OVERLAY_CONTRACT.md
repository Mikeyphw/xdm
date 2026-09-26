# GATE-09 operational core overlay contract

- Target: `xgo_gate_ops`.
- Validation is required and must not be deferred.
- `xgo_gate_ops#validate` composes the full upstream chain through media before `xgo_ops#validate`.
- `xgo_ops#validate` includes the explicit `ops_gate_suite` node after `import_faults`.
- The overlay does not refresh or reinstall Devtool.
- The overlay does not introduce dual-write import behavior.
