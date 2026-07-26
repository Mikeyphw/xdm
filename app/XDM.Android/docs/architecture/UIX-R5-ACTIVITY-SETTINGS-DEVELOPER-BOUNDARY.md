# UIX R5 Activity, Settings, and Developer Boundary

UIX R5 completes the consumer-facing information architecture introduced by UIX R1–R4. Activity now explains what needs attention and what happened recently. Settings leads with ordinary preferences. Technical dashboards remain available only through an explicit, persisted Developer options gate.

## Activity contract

- Activity opens on **Needs attention**.
- The only primary views are **Needs attention** and **Recent**.
- Metrics show unresolved items, queue decisions waiting for the user, and events today.
- Rows use plain language, time, affected file, consequence, and one useful action.
- Queue decisions, Queues, Schedules, and Recovery remain fully operational through **Manage activity**.
- Diagnostics is not a normal Activity tab. Legacy Diagnostics state redirects to Settings > Developer tools only when Developer options is enabled; otherwise it safely returns to Needs attention.

## Settings contract

Settings groups everyday options in this order:

1. Downloads: save location, smart queue, notifications, advanced download rules.
2. Appearance: dark or AMOLED black theme and compact rows.
3. Privacy and support: privacy guidance, redacted support report, Developer options.
4. About: app version and release channel.

Advanced download rules retain destination and duplicate rules, proxy profiles, Termux integration, Termux aria2 enablement, conversion and post-processing, and secret-safe settings import/export.

## Developer options contract

`developerOptionsEnabled` is stored in DataStore and defaults to `false`. The developer workspace is unreachable from normal navigation while disabled. When enabled it contains grouped, redacted sections for:

- Runtime and engines
- Termux and aria2
- Media pipeline
- Dispatch and workers
- Privacy and cleanup
- Validation and release readiness
- Intake and clipboard diagnostics
- Redacted logs and exports

Developer mode changes visibility, never privacy. Clipboard URLs, support exports, headers, cookies, tokens, signatures, and credential-bearing query values remain redacted. XDM exposes typed actions only and never provides a raw shell textbox.

## Preserved behavior

Queues and schedules retain create, edit, enable, disable, delete, and evaluate actions. Recovery retains validation and record-only removal semantics. Termux, aria2, proxy, automation, destination rules, duplicate rules, external handoff, engines, routes, Room schema 14, and app version `0.20.0-rc08` / code 21 are unchanged.
