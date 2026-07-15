# XDM Desktop Topography, UI, and UX Guide

This is the authoritative guide for the Avalonia desktop app in `app/XDM`. Treat "topography" as the structure of the interface: landmarks, shell regions, page layout, responsive behavior, and the relationship between dense work areas.

## Scope

- Applies to `XDM.App` desktop XAML, code-behind layout helpers, localization resources, and desktop UI tests.
- Supplements the phase-specific notes in this folder. When guidance conflicts, this document wins.
- New desktop features must update this guide when they introduce a new page pattern, shell behavior, or control convention.

## Shell Topography

- The desktop shell owns the app-level landmarks.
- There must be exactly one `AutomationProperties.LandmarkType="Main"` at runtime: `PageContentHost`.
- Hosted page `UserControl` roots must not declare `LandmarkType="Main"`. Use page-local `Form`, `Search`, `Region`, or no landmark.
- Navigation landmarks are reserved for the primary navigation rail and settings category tabs.
- Content info is reserved for the shell footer/status area.
- Utility overlays such as command palette, notification center, and status banners must have stable automation IDs and accessible names.

## Layout Rules

- Minimum supported shell width is `760`.
- Dense work pages must define compact behavior below `920`.
- Fixed multi-column dashboards, split panes, and inventories must reflow to one column at compact width.
- Splitters may exist in wide layouts, but compact layouts should stack panes and hide splitters when resizing no longer makes sense.
- Horizontal scrolling is not a substitute for responsive layout in primary workflows.
- Repeated cards may use framed surfaces. Do not nest page sections inside decorative cards.
- Long forms should use grouped sections, persistent labels, and vertical rhythm that supports scanning.

## Typography

- Use semantic text classes from `MainWindow.axaml`: `display`, `page-title`, `section-title`, `card-title`, `form-label`, `supporting`, `caption`, `metric`, and `label`.
- Avoid direct `FontSize` overrides unless the element is an intentional exception documented in code.
- Placeholder text must not be the only label for primary form fields.
- Compact panels and cards should use `section-title`, `card-title`, or `label`; reserve hero-scale text for top-level page headings.

## Controls

- Numeric settings must use bounded `NumericUpDown`, not raw `TextBox`.
- Option sets use `ComboBox`, menus, or tabs depending on density and persistence.
- Binary settings use `CheckBox` or toggles.
- Commands use buttons with localized text, or icon buttons with tooltip plus automation name.
- Use `PathIcon` for symbolic controls and empty states. Avoid font-dependent glyph controls such as standalone `×`, `⌘`, `●`, arrows, or search symbols.
- Destructive actions must use the established `danger` button class.

## Localization

- User-facing text in XAML should bind through `Localization[...]`.
- Automation names, placeholder text, tooltips, flyout headings, context menus, and empty states are user-facing text.
- Literal strings are allowed only for data examples, units that are not localized elsewhere, or values supplied by the model.
- New localization keys belong in `XDM.App/Localization/strings.en.json`.

## Accessibility

- Every interactive control that is not self-describing through localized text needs an automation name.
- Runtime automation IDs must be unique.
- Keyboard access must preserve the existing shell shortcuts: F6, Escape, Ctrl+K, Ctrl+F, Ctrl+N, and Ctrl+1 through Ctrl+8.
- Status and notification surfaces must use live regions only where screen-reader interruption is intentional.
- Headings should identify page and major section structure without creating duplicate app landmarks.

## Regression Tests

The desktop test suite must continue to enforce these contracts:

- All desktop pages load at minimum shell width.
- Runtime automation IDs are unique.
- Runtime exposes exactly one `Main` landmark.
- Hosted page XAML does not declare `Main` landmarks.
- Shell dynamic resources referenced by desktop XAML are defined.
- Header utility controls expose stable IDs, accessible names, and shortcuts.
- Dense desktop pages reflow at compact width.
- Numeric settings stay on `NumericUpDown`.

When a new desktop page or dense workflow is added, add or extend tests in `XDM.App.Tests` before considering the UI contract complete.
