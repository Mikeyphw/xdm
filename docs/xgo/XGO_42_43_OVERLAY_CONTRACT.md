# XGO-42 + XGO-43 Overlay Contract

Target: `xgo_backends`

Validation is required and must pause on failure. Do not apply this overlay with `--no-validate`.

This overlay must prove:

1. aria2 GID/runtime ownership is generation scoped and cannot write without durable active ownership.
2. daemon restart reconciliation uses staging proof and fresh generation rebinding, not GID equality alone.
3. runtime rebinding is atomic across generation reservation, ownership/task binding, current-attempt update, and source retirement/abandonment.
4. backend migration fences the source at target-generation reservation, establishes the target as non-writing before activation, and retires the source only after target activation is durable.
5. every durable migration boundary and every external-effect-before-commit boundary is idempotently recoverable.
6. late source events are diagnostic-only after authority has moved.

This overlay must leave the tooling setup untouched.
