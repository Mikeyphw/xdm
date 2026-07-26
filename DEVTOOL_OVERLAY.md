# XDM Android UIX R2 Overlay

UIX R2 installs the flat, dark, adaptive Android shell on top of the UIX R1 modular surface boundary.

## Included

- Dark-first Material 3 theme with near-black backgrounds, semantic status colors, transparent surface tint, and zero-elevation primary surfaces.
- Compact, medium, and expanded window classes at 600 dp and 840 dp breakpoints.
- Five visible destinations: Downloads, Media, Library, Activity, and Settings.
- Bottom navigation for compact and medium layouts.
- Persistent 224 dp sidebar and bounded content canvas for expanded layouts.
- `AppRoute.Add` preserved internally and displayed through an adaptive sheet/dialog instead of permanent navigation.
- Shared responsive UI primitives, safe drawing insets, IME padding, accessibility state semantics, architecture documentation, tests, and static validation.
- Flat grouped surfaces applied to the primary user workflows without changing their business logic.

## Preserved

Routes remain source-compatible, Room remains at schema 14, and the application remains at `versionName 0.20.0-rc08` / `versionCode 21`. Native, aria2, Termux, yt-dlp, Media3, queue intelligence, scheduling, recovery, external handoff, and developer diagnostics are unchanged.

## Deliberately deferred

UIX R3 redesigns the Downloads control center and Add workflow using the shell and primitive system introduced here. Media, Library, Activity, Settings, accessibility qualification, and release sealing remain later roadmap stages.
