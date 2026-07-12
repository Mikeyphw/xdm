# Typography and shell cleanup — phase 1

This phase establishes a consistent visual and interaction foundation for the modern Avalonia application without changing download behavior.

## Typography

The main window now uses semantic text roles instead of scattered font-size and weight combinations:

- display: product identity;
- page title: current navigation destination;
- section title: major workflow or settings group;
- card title: self-contained sub-surface;
- form label: persistent input identity;
- supporting text and caption: secondary context;
- metric: operational summary values.

The default body size is 14 px. Form labels use 13 px medium weight, supporting text uses 12 px, and captions are reserved for low-priority metadata at 11 px.

## Color and controls

- The primary action blue is darkened so white button labels have stronger contrast.
- Muted, summary, subtle, and disabled text colors are brighter on dark surfaces.
- Buttons have explicit primary, secondary, quiet, and danger roles.
- Text fields, combo boxes, and numeric fields share a 38 px minimum height.
- Keyboard focus receives an explicit high-contrast border.
- Destructive actions no longer look identical to neutral toolbar actions.

## Forms and accessibility

- The download entry form and primary settings groups use persistent labels.
- Numeric settings use bounded `NumericUpDown` controls rather than unrestricted text entry.
- A two-way invariant converter preserves the existing string-backed settings model.
- Primary navigation and important inputs expose stable automation IDs.
- Navigation uses vector geometry instead of font-dependent symbol glyphs.
- Bootstrap validation checks the numeric converter, icon geometry, and initial dashboard state.

## Shell cleanup

- The outer window-level scroll viewer was removed; each page owns its scrolling surface.
- Operational summary cards are limited to Downloads and Queues.
- Settings and specialist tools begin directly with their own content.
- Page headers use a smaller semantic title, supporting summary, and a contained live-status surface.
- The fixed navigation rail is reduced from 228 px to 220 px.
- The minimum window width is reduced from 1000 px to 860 px.
- Horizontal page scrolling is disabled to prevent nested two-axis scrolling.

## Scope boundaries

This phase deliberately does not split the monolithic window into separate views, redesign the downloads table, categorize Settings with secondary navigation, or move live aria2 operations out of Settings. Those remain follow-up workflow and architecture phases.
