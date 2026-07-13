# Smart transfer and queue automation

XDM combines the existing global, queue, and per-download limits with a dynamic transfer policy.

## Profiles

Each profile defines a maximum number of concurrent downloads, a concurrent-download limit per host, and an optional per-transfer speed ceiling. The effective policy uses the most restrictive values from the active base profile, environment profile, and any active schedule profile.

## Environment policy

Network availability is detected through the platform network APIs. Linux battery state is read from `/sys/class/power_supply`. Metered and battery state can be overridden in the UI, or through `XDM_NETWORK_METERED` and `XDM_ON_BATTERY` for managed deployments and deterministic testing.

Environment rules may be ignored, apply a profile, or pause transfers. Paused policy state returns active downloads to their queues and resumes them when the environment becomes eligible again.

## Queue dependencies

Starting a queue recursively requests its prerequisites, then keeps the dependent queue waiting until all configured dependency queues reach terminal states. Successful-only dependencies additionally reject failed or cancelled prerequisite queues. Dependencies are normalized into an acyclic graph when settings are saved.

## Scheduled profiles

A schedule can select a bandwidth profile. While the schedule window or its tracked run remains active, the profile participates in effective-policy calculation.

## Live policy changes

Concurrency waiters are notified when settings, schedules, or environment rules change. Higher limits take effect immediately; lower limits apply as existing transfer slots finish, without terminating healthy downloads.
