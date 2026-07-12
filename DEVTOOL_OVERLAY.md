# XDM Overlay — Modern flat application icon

This overlay replaces the legacy XDM icon on top of commit `d9c9756`.

## Included

- minimal flat SVG source artwork;
- 512 px transparent PNG fallback;
- multi-resolution ICO with nine embedded sizes;
- explicit Avalonia window icon;
- existing tray and executable icon references continue using the replaced ICO;
- Linux desktop entry now declares `Icon=xdm-modern`;
- Debian packaging installs PNG and scalable SVG launcher icons.

The design uses a dark rounded tile, blue download arrow, and green destination tray with no gradients, shadows, or fine decorative detail.
