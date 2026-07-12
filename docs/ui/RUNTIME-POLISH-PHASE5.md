# Runtime polish — Phase 5

## Goals

This phase improves how XDM communicates state at runtime without changing download-engine or persistence behavior.

## Shell behavior

The current operation message is shown as a dismissible live-status banner. Section changes cross-fade the existing page host rather than creating or destroying page view models. Compact widths hide the decorative section icon and constrain the status banner, leaving more space for the page title.

## Downloads states

The Downloads page now distinguishes these states:

1. the core is still loading;
2. no downloads exist yet;
3. downloads exist but the active filters match none;
4. downloads exist but no detail row is selected;
5. the selected download has an error.

Each state has a dedicated accessible surface and an immediate recovery action where appropriate.

## Responsive layout

At widths below 920 DIPs:

- URL and destination inputs stack;
- the add/browse actions remain grouped;
- download options use two columns rather than four;
- search and status filtering use two rows;
- the list/details split changes from 2:1 to 3:2.

The detail pane remains available and resizable in compact mode.

## Tests

Headless tests exercise compact and wide layout transitions, shell compact state, and runtime status controls. Source-level tests verify the transition, localization keys, and loading/empty/filter/detail/error surfaces. The shell architecture test continues to forbid page-root visibility bindings while allowing descendant controls to represent loading, empty, filtered, and error states.
