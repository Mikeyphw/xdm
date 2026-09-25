# GATE-07 Media Identity Contract

- Target: `xgo_gate_media_identity`.
- Validation is mandatory and non-deferred.
- The gate composes `foundation -> store -> security -> transfer -> backends -> scheduler -> media`.
- `xgo_media#validate` must include explicit nodes for XGO-49..53 targeted tests, capture convergence equivalence, and credential-scope security.
- Wave 8 remains blocked until this gate passes.
