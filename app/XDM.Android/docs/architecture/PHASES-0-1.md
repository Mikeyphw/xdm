# Phases 0 and 1 implementation contract

## Phase 0

The repository is split into application, domain, persistence, storage, backend, scheduler, media, diagnostics, browser, Tasker, and protocol-lab modules. The app module may depend on feature modules; transfer modules may depend on `transfer-api` and `core-model`; no engine module depends on Compose or Room.

## Phase 1

The Room schema is deliberately broader than the first UI so future transfer work does not require destructive schema redesign. The fake activity engine updates seeded records through the repository and exercises active, queued, completed, failed, verification, and recovery states.

## Non-goals

No real network transfer, foreground service, SAF writer, or aria2 process is started in this slice. Their module boundaries and interfaces are present, but implementation begins in later phases.
