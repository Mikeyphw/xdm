# XDM overlay — Typography foundation and shell cleanup

This overlay implements phase 1 of the modern Avalonia UI/UX audit on top of commit `e2ca125`.

## Included

- semantic display, page-title, section-title, form-label, supporting, caption, and metric typography roles;
- revised high-contrast dark palette with a primary blue that supports white button text;
- consistent primary, secondary, quiet, and destructive button treatments;
- explicit keyboard-focus borders for buttons and form controls;
- 38 persistent form labels across the download entry form and primary settings groups;
- 19 bounded `NumericUpDown` fields backed by a two-way invariant string/decimal converter;
- 43 stable automation IDs for navigation, status, primary actions, and settings fields;
- vector `PathIcon` navigation graphics in place of platform-dependent text glyphs;
- bootstrap checks for numeric conversion, icon geometry, and initial dashboard visibility;
- removal of the outer two-axis window scroller in favor of page-owned scrolling;
- dashboard summaries shown only on Downloads and Queues;
- narrower navigation rail, reduced minimum window width, and cleaner page headers;
- updated high-contrast handling for combo boxes and numeric controls;
- implementation notes in `docs/ui/TYPOGRAPHY-SHELL-PHASE1.md`.

## Deliberately deferred

This overlay does not split `MainWindow.axaml` into separate views, redesign the download list, add secondary Settings navigation, or move live aria2 task management out of Settings. Those are later workflow and architecture phases.

Validation is limited to `app/XDM/XDM.Modern.sln`.
