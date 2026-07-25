# Phase 8E: Activity diagnostics and operational visibility

## Intent

Phase 8E turns the existing Activity destination into XDM Android's browser-free operational flight recorder. It combines transfer transitions, queue-policy decisions, external handoffs, verification, finalization, and recovery into one searchable surface without moving download ownership out of Room or introducing a database migration.

## Activity topology

The single top-level Activity route contains:

- Overview
- Timeline
- Attention
- Decisions
- Queues
- Schedule
- Recovery
- Diagnostics

Timeline supports safe text search plus category, severity, and time-range filters. Attention shows unresolved failures and holds. Decisions explains Phase 8C network, power, storage, schedule, concurrency, and retry outcomes. Existing queue, schedule, recovery, and runtime controls remain available.

## Persistence and retention

`OperationalActivityStore` records transfer state transitions in a bounded SharedPreferences ledger:

- at most 300 events
- resolved events retained for 30 days
- unresolved locally recorded events survive normal cleanup
- dismissals are bounded separately
- clearing Activity history never deletes downloads, files, queues, recovery records, or Room rows

Queue-policy decisions remain in their dedicated secret-free ledger, expanded for a longer operational history. The Activity planner merges both ledgers with current recovery, verification, finalization, automation, and transfer state.

## Privacy-safe diagnostics

The diagnostics export includes:

- app and Android identity
- Room schema number
- configured downloader engines
- transfer and queue-policy states
- failure categories
- recent operational events

It never intentionally exports source URLs, destination paths, cookies, authorization values, tokens, signatures, or credential-bearing query values. Shared redaction replaces sensitive values with `<redacted>`, and already-redacted placeholders remain valid.

## Downloader preservation

Phase 8E does not change:

- the six top-level routes
- native or aria2 execution
- Termux or yt-dlp integration
- Phase 8C queue eligibility rules
- Phase 8D resolver and track selection
- external browser handoff
- offline library or Media3 playback
- Android manifest ownership
- Room schema 14
- versionCode 21
- versionName 0.20.0-rc08

The built-in browser remains absent.
