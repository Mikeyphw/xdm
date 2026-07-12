# Flat application icon

XDM now uses a single modern flat icon across the desktop window, system tray, executable metadata, source artwork, and Linux desktop packages.

## Design

- dark rounded-square tile: `#111827`;
- blue download arrow: `#4F8CFF`;
- green destination tray: `#34D399`;
- no gradients, shadows, text, or small decorative details;
- optimized for recognition from 16 px through 1024 px.

## Assets

- `app/XDM/xdm-logo.svg` is the editable vector source;
- `app/XDM/xdm-logo.png` is the 512 px raster fallback;
- `app/XDM/xdm-logo.ico` contains 16, 20, 24, 32, 40, 48, 64, 128, and 256 px frames.

The Avalonia window and tray use the ICO resource. The Debian package installs both PNG and scalable SVG launcher icons under the freedesktop hicolor icon theme.
