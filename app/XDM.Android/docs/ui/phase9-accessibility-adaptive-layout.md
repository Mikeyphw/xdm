# Phase 9 Accessibility and Adaptive Layout

Phase 9 makes adaptive behavior a policy instead of a width-only guess.

## Production contracts

- `XdmWindowProfile` combines width, height, font scale, fold posture, and pane-width minimums.
- Downloads two-pane mode is allowed only when the list and details panes can both keep their minimum usable widths.
- A separating hinge disables split panes until hinge-aware physical bounds are available.
- Dialogs and bottom sheets expose pane titles and scroll within bounded heights for short screens and large font scales.
- Reusable rows avoid overriding all text with duplicate content descriptions.
- Icon-only actions carry their label on the button/item, while child icons use `contentDescription = null`.
- Progress bars do not announce every byte-percentage update; phase and terminal changes use polite live regions.
- Settings switch rows expose one switch control instead of a row plus nested duplicate switch node.
- Media variant rows expose one radio-style row instead of nested row and chip controls.
- The media player receives a bounded height policy for compact-height landscape and large-font layouts.

## Test surfaces

The Phase 9 source gate checks phone, split-screen, 840 dp threshold, tablet, landscape/short-height, large-font, five-item bottom navigation, media variant semantics, settings switches, adaptive sheets, and live-region suppression contracts.
