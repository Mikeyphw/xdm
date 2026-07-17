# Phase 7: backend strategy, migration, and fallback

## Selection contract

XDM evaluates the capabilities reported by the installed backend runtime. Automatic selection considers source protocol, Android destination provider, selective-repair needs, media playlists, expiring URLs, captured authentication headers, mirrors, expected size, network metering, and prior host throughput.

Every decision persists the requested backend, selected backend, reason, human-readable explanation, and whether compatible pre-start fallback is allowed. The Downloads and Add screens expose the explanation instead of presenting the policy as a black box.

Fallback is deliberately narrow. XDM may choose a compatible alternate only before a backend task is created and owns the destination. A crash, protocol failure, or write failure after task creation never causes an automatic engine switch.

## Migration contract

Migration is a journaled transaction, not an editable backend field:

1. Require a paused or otherwise non-writing transfer.
2. Probe the target backend and reject unavailable or incompatible targets.
3. Pause the source and inspect its physical artifacts.
4. Require explicit restart-from-zero when source bytes exist.
5. Prepare a distinct target artifact set.
6. Retire the source backend task and remove its active mapping while preserving physical artifacts.
7. Transfer destination ownership transactionally to a new generation.
8. Create the target task suspended, attach its task ID to durable ownership, then activate it.
9. Persist the new backend and complete the migration journal.

Sparse segments, aria2 control files, native checkpoints, and backend-specific partials are never reinterpreted by another engine. If a migration fails after ownership transfer, the target generation is quarantined for recovery rather than reviving the old writer.

## UI contract

Phase 7 remains inside the existing topography:

- Downloads displays the selected backend, decision explanation, and a migration action only when the target is currently available and destination-compatible.
- Add download previews the recommendation and any pre-start fallback, and blocks submission when an explicitly requested backend is incompatible and fallback is disabled.
- Settings displays the capability matrix and recent migration journal entries.
- No additional top-level route is introduced.
