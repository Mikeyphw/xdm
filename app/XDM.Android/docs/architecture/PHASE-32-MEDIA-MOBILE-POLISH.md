# Phase 32: Media Mobile Polish

Phase 32 turns the dense Media stack into a phone-first experience while keeping Browser inside Media and without adding routes, database migrations, workers, or new execution side effects.

## Scope

- Sticky current-job summary for active, attention, or ready media.
- Compact primary action strip for launch, retry, cleanup, and capture actions.
- Collapsed diagnostics drawers for player, privacy, cleanup, and capture quality.
- Explicit empty, offline, and error states.
- Accessibility labels for every planned section.
- Touch target guidance: labels first, icons only as decoration, at least 48 dp targets.
- Foldable/two-pane guidance without creating a new top-level route.
- No tiny scroll islands: Media remains one vertical list instead of nested independent scrollers.
- Secret-safe summaries only. No cookies, headers, bearer tokens, tokenized URLs, proxy credentials, raw shell, or sidecar secret persistence.

## Non-goals

- No Room schema migration.
- No runtime worker/service launch.
- No new route.
- No DRM bypass.
- No durable session storage.

## Source of truth

`MediaMobilePolishPlanner` is a pure Kotlin planner. Compose reads the dashboard and displays the Phase 32 card inside the existing Media route. Final Gradle/devtool validation remains deferred until the final media validation phase.
