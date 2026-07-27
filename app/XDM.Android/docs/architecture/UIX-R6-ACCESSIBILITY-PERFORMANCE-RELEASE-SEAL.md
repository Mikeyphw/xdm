# UIX R6 Accessibility, Performance, and Release Seal

UIX R6 closes the Android redesign without changing a route, database schema, download engine, or app version. It turns the visual redesign into a release-qualified product contract rather than a screenshot promise.

## Accessibility contract

- Every primary action and navigation destination has a minimum **48 dp** interaction target.
- Stable semantics tags cover Downloads, Add review, Media capture and track selection, Library list/grid, Activity, Settings, and Developer tools.
- TalkBack receives readable labels and state descriptions for selection, expansion, theme, and Developer options.
- Shared headers and metric strips remain usable at **200%** font scale, with wrapping instead of fixed-height clipping.
- Long filenames, long translations, empty states, and error notices preserve readable hierarchy.
- Keyboard and safe-drawing insets protect editable Add fields and modal actions.

## Responsive layout contract

The shell and feature workspaces are qualified for **Compact, Medium, and Expanded** window classes. Compact and Medium use bottom navigation. Expanded uses the persistent sidebar and bounded content canvas. Add remains the same internal route while presenting as a phone bottom sheet or expanded dialog. Downloads preserves list/detail state through rotation and modal dismissal. Library retains list/grid semantics according to width.

## Performance and privacy contract

**Developer planners** are constructed only when persisted Developer options are enabled and the Developer tools panel is active. Normal Media, Library, Activity, Downloads, Add, and Settings sources may not render phase language, architecture dashboards, raw enum names, raw JSON, full secret-bearing URLs, cookies, authorization values, or command templates. Redacted support reporting remains available without opening the developer cockpit.

## Automated release gate

The final gate runs all historical validators plus the R6 validator, JVM contracts, lint for debug, unit tests across modules, debug packaging, and Android-test assembly. CI also verifies the packaged ARM64 aria2 payload. The accepted result is **zero warnings and zero errors**.

The device smoke helper is:

```bash
tools/run-uix-device-smoke.sh
```

It runs the adaptive-layout and current-product smoke classes on a connected Android device or emulator.

## Manual qualification checklist

### Clean install

1. Install a fresh debug or release candidate APK.
2. Confirm Downloads opens first and only five primary destinations are visible.
3. Open and dismiss Add with system Back. Confirm Downloads returns with its filter and selection state intact.
4. Verify TalkBack labels for navigation, New download, transfer actions, filters, switches, sheets, dialogs, and playback actions.
5. Repeat at default font size and 200% font scale in portrait and landscape.
6. Verify compact phone, medium landscape/foldable, and expanded tablet layouts.

### Upgrade

1. Upgrade from the validated R5 build without clearing data.
2. Confirm active, queued, completed, archived, media-capture, library, schedule, rule, and preference state remains intact.
3. Confirm Dark/AMOLED, compact rows, and Developer options preferences persist.
4. Confirm Room remains schema 14 and no migration runs.

### External handoff

1. Share a direct file URL from an external browser.
2. Trigger a typed download intent and a file-extension link.
3. Share a media page into Media inspection.
4. Confirm every path opens review first and none silently queues a transfer.
5. Confirm XDM is not offered as a general browser for ordinary HTTP navigation.

### Failure and recovery

1. Exercise empty, loading, destination-error, authentication, retry, verification, and recovery states.
2. Confirm no visible primary control is inert.
3. Confirm secrets remain redacted in support and Developer outputs.
4. Disable Developer options while Developer tools is open and confirm the workspace closes immediately.

## Frozen boundaries

- Routes: Downloads, Add, Media, Library, Activity, Settings.
- Visible destinations: Downloads, Media, Library, Activity, Settings.
- Room schema: 14.
- Version name: `0.20.0-rc08`.
- Version code: 21.
- Existing native, aria2, Termux, yt-dlp, Media3, queue, recovery, automation, and external-handoff behavior remains owned by its current modules.
