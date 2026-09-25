# GATE-05 overlay contract

## Authoritative target

- Target: `xgo_gate_backends`.
- Validation: required.
- Failure action: pause.
- Validation task override: absent.

## Architecture rule

The gate target is pure composition. It must call subsystem workflows through target references, not target-local jobs.

## Validation requirements

The selected gate target must validate the full backend convergence chain:

1. foundation target;
2. store target;
3. security target;
4. transfer target;
5. backend target.

The backend target must retain the Wave 5 evidence DAG ending in aria2 ownership faults and backend migration faults.

## Exit criteria

GATE-05 is closed only when Devtool validates `xgo_gate_backends` with zero diagnostics and the cumulative backend graph remains intact.
