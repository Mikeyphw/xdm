# Phase 2 — Devtool affected ownership and DAG correctness

## Changes

- Adds opt-in `targets.*.metadata.affected_ownership = "explicit"` to XDM targets.
- Implements the opt-in in Devtool `devtool/affected.py`; default remains unchanged.
- In opt-in mode the target's execution `root` is **not** an implicit ownership
  wildcard. Only target `inputs` and direct workflow-job `inputs` classify files.
- Any path with no owner still selects **all candidate targets** through the
  existing fail-conservative fallback; reverse target dependencies still apply.
- Unknown or empty ownership mode fails closed. The opt-in has tests upstream.

This is a correctness step, **not** a promise that affected execution is
optimized: many XDM gate targets explicitly declare `engine/**`; such broad
sources still legitimately select many targets. Do not enable affected-only
required CI until the matrix has been evaluated and coverage reviewed.

## Local acceptance (Termux)

    cd ~/Code/devtool
    python3 -m pytest -q tests/test_phase02_explicit_affected.py

    cd ~/Code/xgo-unified-go-engine
    python3 -m pytest -q tools/xgo/tests
    python3 scripts/devtool_phase02_audit.py --devtool ~/Code/devtool
    python3 tools/xgo/audit_topology.py audit
    python3 tools/xgo/validate-devtool-wrapper.py
    git diff --check

## Negative tests

- A Go-only change must not mark unrelated Android device targets directly.
- Android-only changes must not mark Go storage targets directly.
- Desktop-only changes must not directly mark Go storage.
- Unknown paths must select every candidate, not be silently skipped.
- `.devtool.toml` changes must select every target.
- An invalid or input-free explicit-ownership target must be refused.
- Reverse dependencies must remain intact.

## Follow-up / controlled rollout

Phase 2 scope is the affected-selection contract and its real-config audits.
Scheduling write-set normalization and source-owner granularity refinement
remain separate qualification work, not an untested automatic parallel enable.
Keep GitHub signing, Android builds and release gates untouched.
