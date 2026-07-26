# UIX R1: Surface Contract and Modularization

UIX R1 creates the implementation runway for the approved dark, flat, adaptive Android redesign without changing transfer engines, persistence, intent ownership, or top-level routes.

## Source ownership

The former `Screens.kt` monolith is now a compatibility marker. Runtime composables live under `app/src/main/kotlin/com/mikeyphw/xdm/android/ui/` and are grouped by Downloads, intake, Media, Library, Activity, recovery, Settings, developer tools, and common helpers.

Public composable signatures remain in the existing `com.mikeyphw.xdm.android` package so `XdmApp`, external handoff, state restoration, and existing ViewModel wiring remain stable while later UIX overlays replace presentation details.

## Audience boundary

`UiAudience` and `UiSurface` classify source-level intent:

- **User** surfaces describe current state, consequence, and useful actions.
- **Advanced** surfaces expose queue, scheduling, recovery, and optional integration controls.
- **Developer** surfaces expose redacted planners, runtime probes, telemetry, privacy audits, and release-readiness diagnostics.

Normal Media and Library no longer render phase labels, dispatch control towers, queue telemetry, worker bridges, runtime adapters, validation gates, privacy audits, or raw execution-planner decks. These remain available lazily inside the existing Diagnostics workspace until a later UIX overlay adds the persisted Developer options gate.

## Compatibility

- Stable routes remain Downloads, Add, Media, Library, Activity, and Settings.
- Room remains schema 14.
- Manifest intent filters are unchanged.
- External handoff remains review-first and never auto-queues.
- Queue, scheduler, recovery, native, aria2, Termux, yt-dlp, Media3, and post-processing behavior is unchanged.
- Historical source validators may temporarily use the non-runtime marker index in `Screens.kt`; active architecture tests use the package-aware `UiSourceTree` helper.
