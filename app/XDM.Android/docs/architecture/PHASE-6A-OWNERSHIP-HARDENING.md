# Phase 6A — Cross-backend ownership hardening

This slice establishes the safety boundary required before an embedded aria2 process is allowed to write transfer data.

## Prepared ownership

A backend must resolve the destination and its actual artifact set before Room grants ownership. A preparation contains:

- the canonical destination key;
- the backend-specific artifact format;
- the primary writable partial artifact;
- companion checkpoint, journal, control, or session artifacts;
- the stable backend installation instance ID;
- the current process/session ID.

Preparing a destination does not authorize writes. The backend may begin only after the coordinator transactionally claims that exact destination and artifact set.

If a backend task starts but its task ID cannot be durably attached to the ownership generation, the coordinator must stop and detach that task while preserving its artifacts. When safe detachment cannot be confirmed, the claim remains quarantined as an orphaned backend task; ownership is never released underneath a possible writer.

## Reconciliation

Persisted claims are never released merely because the current Kotlin process has no matching in-memory task. Startup asks the recorded backend to classify each claim as:

- active task verified;
- resumable artifact;
- backend task orphaned;
- orphaned artifact;
- missing artifact;
- conflicting artifact; or
- backend unavailable.

Unsafe or unknown claims are quarantined and the download enters `RecoveryRequired`. Artifacts recorded by a different backend installation instance are conflicting rather than adoptable. A resumable artifact remains paused until the user or execution flow explicitly resumes it.

## Adoption

A new process may adopt only a claim that has been reconciled as a resumable artifact. Adoption must match the download, destination, backend, physical artifact set, and previous generation. It creates a new generation and records the current backend runtime identity.

Legacy schema-v4 claims contain only synthesized partial identifiers. Migration preserves them as `legacy-partial-v1`; they are quarantined because guessing their physical files would weaken collision protection.

## Phase 6 dependency

The aria2 placeholder now participates in preparation and reconciliation contracts, but remains unable to start downloads. Process packaging, authenticated loopback RPC, session persistence, and GID reconciliation belong to the next overlay.
