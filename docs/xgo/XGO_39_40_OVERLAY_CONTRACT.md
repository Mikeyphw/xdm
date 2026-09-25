# XGO-39..40 Overlay Contract

- Target: `xgo_backends`
- Validation required: yes
- Failure action: `pause`
- Deferred validation: no
- `validation.tasks`: absent; `xgo_backends#validate` is authoritative.
- Devtool installation/refresh: forbidden.

## Compatibility authority

- `NetworkIntent` remains canonical request identity; router requirements do not duplicate request fields.
- Native and aria2 implement the same `CanExecute` / `Preflight` inspection contract.
- Hard reject dimensions are typed: protocol, method/body, destination, credential mode, proxy, media shape, resume and mirror semantics.
- Soft hints affect ranking only after compatibility succeeds.
- The aria2 contract must not claim an RPC/execution capability before XGO-41+ implements and validates it.

## Selection authority

- `backends/router.Select` is canonical backend selection; hosts/frontends do not add independent routing heuristics.
- Selection inputs are compatibility, canonical user preference/fallback permission, runtime availability, health, operation shape and migration cost.
- Output includes selected backend, reason/explanation and deterministic candidate rejection evidence.
- Selection evidence is persisted as a safe attempt diagnostic only before execution begins.
- Selection never mutates durable backend ownership and never silently switches a started attempt. Migration is an explicit later contract.

## Specialized evidence

- `compatibility_matrix`: nine cases covering canonical request kinds, every typed reject dimension and preflight exactness.
- `selection_matrix`: ten cases covering determinism, preference, availability, degradation, direct HTTP, mirrors/FTP, media/external shapes, migration cost, start fencing and diagnostic persistence.
