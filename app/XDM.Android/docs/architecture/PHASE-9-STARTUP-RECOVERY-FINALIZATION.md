# Phase 9 — Startup recovery and atomic finalization

Phase 9 makes startup recovery and destination promotion deterministic.

The startup coordinator scans incomplete Room records, backend ownership claims, backend migration journals, native checkpoints, aria2 session/control artifacts, finalization journals, and app-private orphan files. Recovered jobs remain paused until the user explicitly acts.

Finalization uses the ordered journal stages:

1. Prepared
2. VerificationComplete
3. PromotionStarted
4. DestinationStaged
5. DestinationCommitted
6. MetadataCommitted
7. Completed

A crash after destination commit but before Room metadata commit is classified as `CompletionRecovered`. A crash before commit is classified as `FinalizationInterrupted`. Malformed recovery artifacts are converted into recovery records and never crash app startup.

The Recovery screen exposes real actions inside the existing Android topography: validate/resume, verify and repair, restart, adopt or remove. No new top-level route is introduced.
