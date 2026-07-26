# XDM Android UIX R1 Overlay

UIX R1 establishes the source and contract boundary required by the Android UI redesign.

## Included

- Splits the former `Screens.kt` monolith into feature-owned UI sources.
- Classifies screens as user, advanced, or developer surfaces.
- Removes engineering dashboards and phase-oriented copy from normal Media and Library workflows.
- Preserves those diagnostics in a lazy developer workspace instead of deleting capabilities.
- Rebases architecture tests and static validation on the modular source tree.
- Preserves routes, Room schema 14, versioning, engines, queue behavior, external handoff, and recovery behavior.

## Deliberately deferred

The flat dark visual system, adaptive navigation shell, download workspace redesign, and Developer-options preference gate are delivered by later UIX overlays. R1 changes information ownership and source topology first so those overlays do not fight obsolete contracts.
