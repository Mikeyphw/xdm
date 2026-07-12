# XDM Overlay — Accessibility and UI validation

This overlay implements Phase 4 on top of commit `18a36ab`.

## Accessibility tree

- adds Navigation, Main, Search, Form, Region, and ContentInfo landmarks;
- marks the current page title as heading level 1 and section titles as heading level 2;
- gives every text, selector, numeric, list, and tab input an accessible name and stable automation ID;
- exposes download and aria2 task item status;
- keeps operation status as a polite live region;
- uses an assertive live region for Settings validation.

## Keyboard and focus

- adds F6 focus switching between navigation and the current page heading;
- closes overlay navigation with Escape before page-level cancellation;
- adds Ctrl+S for Settings while preserving Ctrl+N, Ctrl+F, Ctrl+1–8, Ctrl+P, and Ctrl+R;
- switches focus styling to `:focus-visible`;
- keeps page tab navigation in source order.

## Validation

Settings now rejects invalid cross-field combinations before persistence: connection-count ordering, missing manual-proxy host, invalid PAC URL, invalid aria2 RPC endpoint, and missing managed aria2 executable.

## Automated qualification

- adds `XDM.App.Tests` using Avalonia 12.1 headless XUnit integration;
- registers `XDM.App.Tests` as an approved active project in the modern-solution parity gate;
- tests all eight page views and all three responsive shell bands;
- tests the primary Downloads focus targets;
- tests runtime automation-ID uniqueness;
- adds source-level AXAML accessibility architecture tests.

The overlay does not modify download-engine behavior, persistence formats, or parity evidence.
