# Phase 8C: Queue Intelligence and Execution Policy

## Purpose

Phase 8C adds an explainable policy layer between queued records and backend execution. It does not add another transfer engine, a browser surface, or a Room migration.

```text
Queued download
  -> queue policy evaluation
  -> claimed execution slot
  -> foreground WorkManager ownership for automatic work
  -> existing TransferExecutionRuntime
  -> native, aria2, or compatible fallback backend
```

User-initiated starts continue through the established Android transfer launcher. Automatic schedule and condition-driven starts are claimed by `QueueIntelligenceCoordinator` and executed inside `QueueIntelligenceWorker`, which owns a data-sync foreground lifetime. The worker never launches a second foreground service from the background.

## Policy inputs

Each queue keeps its existing enable flag and concurrency limit. Enabled schedule rules provide browser-neutral JSON constraints for:

- any, unmetered, or Wi-Fi-only networking;
- charging requirement;
- minimum battery percentage;
- start and end time with day selection;
- storage-pressure protection and reserve bytes;
- automatic retry strategy and maximum attempts.

Network readiness requires both internet capability and Android validation. An explicit **Start anyway** action can bypass soft queue policy, including schedule, network type, charging, battery, storage reserve, concurrency, and retry posture. It cannot fabricate a validated internet connection.

Overnight windows belong to the day on which they start. A Friday 22:00–06:00 window therefore includes Saturday at 02:00, but not Saturday at 23:00.

## Priority and fairness

Eligible records are ranked by:

1. persisted download priority;
2. near-completion bonus;
3. short-transfer bonus;
4. age fairness;
5. stable creation time and ID tie-breakers.

Ranking chooses which record claims an available queue slot. It does not change backend selection or ownership.

## Retry policy

Failures are classified before automatic retry:

- transient network, DNS, timeout, HTTP 429, and HTTP 5xx failures use exponential backoff;
- authentication, permission, verification, unsupported/DRM, and permanent HTTP failures require manual review;
- unknown failures are not retried automatically;
- manual retry remains available;
- retry state is stored outside Room in a bounded private preference ledger.

## Decision history

Every distinct queue decision is recorded in a bounded, private, secret-free decision ledger. Activity and Downloads show recent starts, waits, backoff, limits, and manual-review decisions. Repeated periodic evaluation of the same unchanged hold does not flood the ledger.

Condition changes are coalesced through `QueueConditionMonitor` and unique WorkManager work. Network capabilities, charging, storage pressure, clock, and timezone changes request fresh evaluation. `ExistingWorkPolicy.KEEP` prevents a new condition event from cancelling a running foreground queue worker.

## Preserved contracts

- review-first Add Download remains unchanged;
- external browser and application handoff remains unchanged;
- native, aria2, Termux yt-dlp, resolver, scheduler runtime, recovery, library, and Media3 paths remain available;
- six routes remain Downloads, Add, Media, Library, Activity, and Settings;
- production Android WebKit remains absent;
- Room remains schema 14;
- `versionCode` remains 21;
- `versionName` remains `0.20.0-rc08`.
