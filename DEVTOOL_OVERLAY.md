# XDM Overlay — Runtime polish and responsive Downloads states

This overlay implements the runtime-polish phase on top of commit `d9778e3`.

## Shell polish

- turns the operation message into a dismissible, accessible status banner;
- adds a lightweight opacity transition when switching application sections;
- tightens the header at compact widths while preserving F6 and overlay-navigation behavior;
- extends the normal and high-contrast palettes for information, success, and empty-state surfaces.

## Downloads workflow states

- adds explicit loading, first-run empty, filtered-empty, and no-selection detail states;
- adds a selected-download error panel with assertive accessibility semantics;
- keeps the virtualized list, all commands, and the resizable details pane;
- refines download rows into a denser filename/status/progress/metadata hierarchy;
- selects the first visible download when the current filter leaves no selection;
- adds one-click filter reset and status-banner dismissal commands.

## Responsive behavior

Below 920 DIPs, the Downloads add form, option fields, and search controls reflow into fewer columns. The list/details workspace remains resizable and gives more width to the list. Wide layout is restored automatically above the breakpoint.

## Qualification

- adds headless tests for compact and wide Downloads layouts;
- adds shell status/compact-header headless coverage;
- adds source-level tests for transitions and all new loading/empty/error states;
- narrows the shell architecture guard to reject root-level page visibility only, allowing legitimate descendant state surfaces;
- preserves the modern-solution project allowlist and parity feature evidence.
