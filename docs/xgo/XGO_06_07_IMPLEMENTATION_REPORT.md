# XGO-06 + XGO-07 implementation report

This overlay establishes the canonical domain split that later storage and
runtime work will persist and execute.

Key choices:

- `Download` owns stable lifetime identity and current Request/Attempt/Artifact
  pointers; it intentionally does not own execution cancellation/failure.
- `Request` is immutable and revisioned. `Revise` returns a new value and
  requires an exact +1 revision step.
- `Attempt` owns the execution state machine. Failed and cancelled attempts
  are terminal; retry is modeled by a later attempt generation.
- `Artifact` can only be constructed through `NewVerified`; it then owns a
  small publication-state machine without pretending the later publication
  saga already exists.
- `failure.Failure` never incorporates the wrapped low-level cause into its
  public string or JSON representation. `errors.Is/As` can still traverse the
  process-local cause via `Unwrap`.
- Donor mappings use stable semantic keys and explicit retry/user-action
  metadata so scheduler logic never has to parse exception strings.
