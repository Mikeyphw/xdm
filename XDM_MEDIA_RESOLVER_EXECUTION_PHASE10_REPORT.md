# XDM Android Overlay 10 — media resolver/execution report

Base source: applied Phase 08+09 v3 commit `79df336c`.

Roadmap scope: M-031, app/media side of M-032, M-047, media side of M-055.

## Delivered

1. **Exact selected request execution** — selected-variant URL and headers now reach the real queue plan; selected variant headers override capture-level headers of the same name; configured destination is preserved.
2. **Dispatch is authoritative** — the real media download action evaluates readiness before any Download or external-job mutation.
3. **Single execution owner** — app-owned media creates an app Download; Termux-owned yt-dlp/live work creates a real durable external job with no synthetic queued Download.
4. **Durable one-to-many outputs** — schema 20 `media_outputs` records owner + attempt generation and supports multiple outputs for one capture; migration 19→20 backfills legacy links.
5. **Retry and redownload lineage** — app ownership-generation changes synchronize a distinct output row, fresh-redownload/restart-from-zero clones media lineage to the replacement Download, and Termux retries atomically create a new durable job/output generation without overwriting previous generations.
6. **Repeat-output workflow** — captures remain reviewable after the first output, and encrypted capture/variant request handoffs remain available for another selected output until capture removal/expiry.
7. **Library identity correction** — output ID/generation is the Library identity. App Download state is authoritative only when its generation matches the output row; historical generations retain their own snapshot and never borrow a newer generation's state. Removing an app Library record tombstones only that generation so synchronization cannot resurrect it; Termux job/output metadata is removed atomically.
8. **Verified completion persistence** — normal Download state upserts now persist the verified completed artifact URI/generation/byte count, and current app outputs use the Download's validated playback grant rather than trusting an output URI by scheme alone.
9. **Structured media failure classification** — protection, strategy, freshness, transfer state, and backend classify failures; message text is detail only.
10. **Overlay 11 secret boundary preserved** — authenticated Termux yt-dlp sessions are held rather than copied to a process command line.

## Pre-delivery verification

- targeted Phase-10 source-contract assertions pass under `kotlinc` with a minimal JUnit stub;
- schema 19→20 SQL was simulated against a schema-19 SQLite database and legacy capture/download state was backfilled successfully;
- static promise checks verify no Public Downloads substitution in the real media enqueue function, dispatch-before-mutation ordering, absence of a synthetic Termux Download, atomic job/output writes, output-generation Library keys, per-generation deletion without capture cascade/resurrection, and structured media failure classification;
- schema-v2 overlay packaging is SHA-256/preimage pinned and simulated onto the exact applied-v3 source tree before delivery.

Full Gradle compile/unit/lint validation is deliberately not run for this intermediate artifact and remains part of the final Overlay 13 gate.
