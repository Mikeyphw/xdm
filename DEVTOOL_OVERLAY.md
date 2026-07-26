# XDM Android UIX R5 Overlay

UIX R5 completes the consumer information architecture on top of UIX R4 by refocusing Activity and Settings while placing engineering dashboards behind persisted Developer options.

## Included

- Activity defaults to Needs attention with Recent as the second primary view.
- Compact metrics for unresolved items, waiting decisions, and events today.
- Plain-language activity rows with time, affected file, consequence, and one relevant action.
- Manage activity adaptive sheet for queue decisions, queues, schedules, and recovery.
- Settings reordered around Save location, Smart queue, Notifications, Appearance, Privacy and support, and About.
- Persisted Dark and AMOLED black themes plus compact row preference.
- Persisted Developer options, disabled by default.
- Grouped developer workspace for runtime, Termux/aria2, media, dispatch/workers, privacy/cleanup, validation/release, intake/clipboard, and redacted logs/exports.
- Redacted support report remains available with Developer options disabled.
- R5 planner tests, source contracts, static validator, architecture documentation, CI, and final-gate integration.

## User/developer boundary

Normal Activity has no Diagnostics tab. Normal Settings does not inline runtime probes, backend matrices, planner dashboards, telemetry, worker bridges, privacy audits, release checks, or raw intake output. Developer mode changes visibility only; URL, header, cookie, token, signature, and credential redaction remains mandatory. No raw shell input is introduced.

## Preserved

Room remains schema 14 and the app remains `versionName 0.20.0-rc08` / `versionCode 21`. Routes, download engines, queue and schedule operations, recovery validation, Termux, aria2, proxy, post-processing, destination and duplicate rules, settings exchange, and external handoff remain operational.

## Dependency

Apply after `xdm_android_uix_r4_media_library_consumer_workflow_overlay.zip`. UIX R6 will run the accessibility, responsive-layout, performance, and final release seal.
