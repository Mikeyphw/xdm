# UIX R2: Flat Dark Adaptive Shell

UIX R2 establishes the shared application frame and visual foundation for the Android redesign. It changes presentation and navigation exposure without changing transfer engines, persistence, queue policy, or external handoff behavior.

## Dark-first visual system

XDM now installs one dark-first Material 3 color scheme with a near-black background, quiet grouped surfaces, semantic success/warning/error containers, and transparent surface tint. Primary application surfaces use zero tonal and shadow elevation. Visual grouping comes from spacing, typography, and subtle tonal contrast instead of borders or stacked cards.

The shared primitives introduced here cover page headers, metric strips, notices, grouped lists, list rows, segmented controls, file-type icons, progress lines, section labels, technical-detail disclosure, adaptive sheets, and empty states. Later UIX overlays use these primitives to redesign individual workflows without inventing a second design language.

## Window classes

- Compact: below 600 dp
- Medium: 600–839 dp
- Expanded: 840 dp and above

Compact and medium layouts use a bottom navigation bar. Expanded layouts use a persistent 224 dp sidebar and a content canvas capped at 1480 dp. The shell handles safe drawing insets and IME padding so edge-to-edge rendering does not collide with system bars or keyboards.

## Navigation exposure

The visible shell has exactly five visible destinations: Downloads, Media, Library, Activity, and Settings. The internal route enum remains unchanged for state restoration and external intent compatibility.

`AppRoute.Add` remains a real internal route, but it is not shown in bottom navigation or the expanded sidebar. New download actions open it through an adaptive modal: a bottom sheet on compact and medium devices, and a bounded dialog-style workspace on expanded devices. Dismissing it returns to the previously selected primary destination.

## Compatibility and scope

No Room schema bump. Room remains at schema 14. `versionName` remains `0.20.0-rc08` and `versionCode` remains 21. Native, aria2, Termux, yt-dlp, Media3, scheduler, recovery, queue intelligence, external handoff, and developer diagnostics remain wired.

UIX R2 intentionally does not complete the Downloads and Add workflow redesign. Those information-architecture changes belong to UIX R3, built on this shell and primitive layer.
