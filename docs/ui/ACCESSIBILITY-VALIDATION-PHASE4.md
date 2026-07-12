# Accessibility and UI validation — Phase 4

This phase qualifies the responsive XDM shell and the Phase 3 Downloads/Settings workflows for keyboard, screen-reader, and headless regression use.

## Keyboard model

- `F6` moves focus between the application navigation region and the current page heading.
- `Escape` closes overlay navigation before invoking page-level cancellation behavior.
- `Ctrl+1` through `Ctrl+8` select the eight application sections.
- `Ctrl+N` focuses the new-download URL editor.
- `Ctrl+F` focuses download search.
- `Ctrl+S` saves Settings while the Settings section is active.
- Tab navigation continues through each page in source order with visible `:focus-visible` treatment.

## Accessibility tree

- The shell exposes Navigation, Main, Region, Search, Form, and ContentInfo landmarks.
- The current page title is a level-one heading; section titles are level-two headings.
- Every text box, combo box, numeric input, list, and tab control has an accessible name and stable automation ID.
- Download and aria2 task rows expose names and item status.
- Dynamic operation status remains a polite live region.
- Settings validation uses an assertive live region and blocks persistence until the configuration is valid.

## Settings validation

The save command validates cross-field conditions that numeric bounds alone cannot express:

- maximum connections must be at least the default connection count;
- manual proxy mode requires a host;
- proxy auto-configuration requires an absolute HTTP, HTTPS, or file URL;
- enabled aria2 requires an absolute HTTP or HTTPS RPC endpoint;
- managed aria2 mode requires an executable path.

## Automated qualification

`XDM.App.Tests` uses `Avalonia.Headless.XUnit` to load the actual control tree without a desktop. It covers:

- all eight page views at the supported minimum shell width;
- overlay, compact, and expanded navigation bands;
- focus transfer to new-download and search inputs;
- runtime automation-ID uniqueness.

`AccessibilityUiArchitectureTests` separately validates the serialized AXAML contract: landmarks, heading levels, labels, automation IDs, validation announcements, and the Settings save accelerator.

## Manual release checklist

At 100%, 125%, 150%, and 175% UI scale:

1. Traverse each page using Tab and Shift+Tab.
2. Use F6 to move between navigation and content.
3. Verify the overlay navigation closes with Escape below 900 logical pixels.
4. Confirm focus remains visible in normal and high-contrast modes.
5. Use Orca, Narrator, or VoiceOver to navigate landmarks and headings.
6. Trigger each Settings validation condition and confirm the error is announced.
7. Verify RTL navigation order and page headings.
