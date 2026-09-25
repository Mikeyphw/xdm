# XGO-06 + XGO-07 overlay contract

## Covered roadmap items

- XGO-06 — separate Download, Request, Attempt, and Artifact aggregates
- XGO-07 — canonical failure taxonomy and retry metadata

XGO-08 was considered under the three-item merge window and intentionally not
included. Its concurrent runtime/event lifecycle is a distinct failure surface
and deserves its own runtime-oriented validation boundary.

## Devtool ownership

Artifact target: `xgo_foundation`

New target-local jobs:

```text
state_machine_contracts
failure_mapping_audit
```

Validation remains Devtool-native:

```text
runner:go#validate
  -> gofmt_check
  -> state_machine_contracts
  -> failure_mapping_audit
  -> existing donor/fixture/topology jobs
```

`validation.tasks` remains absent.

## Authoritative outcomes

1. Download lifetime, request intent, attempt execution, and verified artifact
   are separate Go aggregates.
2. Cancellation and execution failure belong to Attempt, not Download.
3. Retry means creating a later attempt; terminal attempts are immutable.
4. Download completion requires acceptance of a verified Artifact from the
   current attempt generation.
5. A stale attempt cannot attach an Artifact to the Download aggregate.
6. Request values are immutable/revisioned and copy caller-owned slices.
7. Request secret/body material is represented only by opaque references.
8. Artifact existence means verified bytes; publication state is separate.
9. Canonical failures have machine category, explicit retry disposition,
   explicit user action, safe public rendering, and a process-local cause.
10. Donor error mappings are keyed by stable semantic identifiers rather than
    localized exception text.

## Intentional deferrals

- Persistence/CAS and durable generation fencing remain XGO-11..14.
- Complete HTTP request/header/body semantics remain XGO-20+.
- Publication saga semantics remain XGO-18.
- Runtime command/event concurrency remains XGO-08.
- Retry timing/backoff scheduling remains XGO-31 and scheduler overlays.

## Exit criteria

- all Go tests/vet pass through Devtool native Go validation;
- state-machine contract audit passes;
- failure mapping audit passes;
- donor/fixture/topology audits remain green;
- XGO-CAP-DOMAIN-001..004 are IMPLEMENTED;
- no Download API owns cancellation or typed execution failure;
- no Artifact can be accepted from a stale attempt generation.
