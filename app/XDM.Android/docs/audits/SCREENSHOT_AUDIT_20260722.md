# Screenshot audit: Android handoff, release gates, and small-screen polish

Date: 2026-07-22
Target: `xdm_android`
Screens reviewed: Downloads, Queues, Scheduler, Add, Media, Diagnostics, Settings.

## Summary

The app is visually coherent and the dark, card-forward shell is now recognizable across tabs. The biggest functional issue visible from the screenshots is not visual: external handoff is brittle. Sharing a link into XDM can silently do nothing after the first attempt because duplicate automation commands without a created download or media capture were marked as duplicate and then dropped. External download-manager discovery also needs broader, more specific intent filters instead of relying on a generic browser VIEW filter.

This overlay now fixes the handoff path and folds in the screenshot-audit remediation pass for the overflow, density, and false release-blocker findings.

## Applied fixes

1. Expanded browser/share/external-downloader intent filters.
   - `ACTION_SEND` text shares are separate from binary/media shares.
   - `ACTION_SEND_MULTIPLE` is accepted for media-style shares.
   - `ACTION_VIEW` now declares typed HTTP/HTTPS handlers for common download payloads: generic streams, APKs, archives, torrents, video, audio, HLS, and DASH.
   - Download-manager compatibility aliases are declared: `android.intent.action.DOWNLOAD`, `android.intent.action.DOWNLOAD_URI`, `com.android.browser.action.DOWNLOAD`, and `com.android.browser.intent.action.DOWNLOAD`.

2. Hardened incoming intent parsing.
   - URL extraction now checks `dataString`, XDM extras, Tasker extras, `EXTRA_TEXT`, browser-style URL extras, Mozilla-style URI extras, subject fallback, and all clip items.
   - Filename/title extraction now accepts XDM, Tasker, Android, and browser-style filename/title extras.
   - Header extraction now accepts XDM headers, browser headers, generic headers, and cookie extras while keeping the existing sanitizer path.

3. Fixed duplicate external handoffs.
   - A repeated share/view of a link that previously opened Add Download now reopens Add Download with the URL populated instead of being swallowed.
   - Duplicates with an existing media capture still reopen Media.
   - Duplicates with an existing download still reopen Downloads.

4. Linked Add Download completion back to automation history.
   - When a shared/browser handoff opens the Add screen and the user starts the download, the automation command is updated with the created `downloadId`.
   - Future duplicate handoffs can now navigate to the concrete download instead of returning to a stale draft.

5. Folded in the screenshot-audit UI remediation pass.
   - Phone-width action/chip strips now use wrapping flow rows instead of one-line horizontal rows that hide labels.
   - Downloads organization tools now open collapsed and reveal bulk/tag/search controls only after tapping Show.
   - Add Download uses wrapped destination/backend/checksum chips and a full-width submit button, so the CTA is not squeezed beside helper text.
   - Diagnostics summary rows now stack label and value below narrow widths instead of squeezing into a two-column row.
   - Disabled secondary actions that created low-contrast ghost labels are hidden until actionable.
   - Queue limits, scheduler times, battery percent, and proxy port request numeric keyboards.

6. Aligned release gate schema expectations to the actual Room schema v14.
   - Release security, install/update readiness, and final public gate models now treat schema v14 as current.
   - Debug/beta builds no longer show an unrun full validation pass as a blocking final-release failure; it remains a release-build gate.
   - Phase docs, validators, and model tests were updated to keep the schema contract consistent.

## Screenshot findings and remediation status

### Release and runtime blockers

- Diagnostics previously showed release gates in blocking state:
  - `App integrity`: unexpected schema version.
  - `Update compatibility`: unexpected schema migration.
  - `Release readiness`: unexpected Room schema for public gate, aria2 payload verification pending, full validation not passed.
- Status: fixed for false schema blockers. Real release-build blockers remain visible; debug/beta full validation is now a warning instead of a blocking runtime-health issue.

### Horizontal clipping and overflow

- Settings / Termux card: action row clips `Kill stu...` on narrow width.
- Downloads / Organization card: `Pause se...` is clipped.
- Add / Destination segmented chips: `App-private Downloa...` is clipped.
- Status: fixed. Phone-width chip/action rows now wrap through `XdmActionFlowRow`, and the dense Downloads organization controls are collapsed by default.

### Density and hierarchy

- Downloads has too many chips/actions visible at once: overview, organization tools, search, state chips, sort chips, select button, FAB, and bottom nav compete in a tight viewport.
- Status: fixed in-place. Search/state chips stay visible, while bulk tools, tags, and saved-search inputs stay behind Show until the user needs them.

### Card layout and responsive text

- Status: fixed. Diagnostics summary rows now use stacked label/value cards on the phone layout.
- Media automation rules are legible but heavy; after three rules, use compact rule rows with an expandable details panel.

### Disabled states and contrast

- Status: partially fixed. Disabled secondary actions that caused ghost labels are hidden until actionable; primary disabled actions remain visible when they explain the form state.

### Form ergonomics

- Status: partially fixed. Queue concurrent downloads, scheduler time fields, scheduler battery percent, and proxy port now request numeric keyboards. A full time picker can still land in a later editor pass.
- Add screen validation is good: the bottom action clearly explains that URL and destination are required.

### Navigation and safe areas

- Bottom navigation safe-area behavior looks consistent.
- The Add screen bottom submit card is positioned well, but it should be verified with keyboard open to ensure the URL field and CTA do not duel behind the IME.

## Manual verification plan

Run on device after applying the overlay and reinstalling the APK:

```bash
adb shell am start \
  -a android.intent.action.SEND \
  -t text/plain \
  --es android.intent.extra.TEXT 'https://example.com/file.zip' \
  com.mikeyphw.xdm.android/.MainActivity
```

Expected: XDM opens Add Download with URL populated.

```bash
adb shell am start \
  -a android.intent.action.VIEW \
  -d 'https://example.com/file.zip' \
  -t application/octet-stream \
  com.mikeyphw.xdm.android/.MainActivity
```

Expected: XDM opens Add Download with URL populated.

```bash
adb shell am start \
  -a com.android.browser.action.DOWNLOAD \
  -d 'https://example.com/file.zip' \
  -t application/octet-stream \
  com.mikeyphw.xdm.android/.MainActivity
```

Expected: XDM opens Add Download with URL populated.

Then repeat each command. Expected: the second attempt reopens Add Download, Media, or Downloads according to the existing command state instead of silently doing nothing.

IronFox verification:

1. Reinstall the patched APK.
2. Open IronFox external download manager settings.
3. Check whether XDM appears in the manager picker.
4. If it still does not appear, test an actual download handoff from IronFox. Some forks do not enumerate every resolver in settings but still call the resolver for the live download intent.
5. If the settings list still excludes XDM, capture the action/type/data from `adb logcat` around the picker open; that will tell us the exact intent IronFox is querying.

## Validation

Static validation completed:

- `AndroidManifest.xml` parses as XML.

Gradle validation could not complete in this sandbox because the wrapper attempted to download Gradle from `services.gradle.org`, and DNS/network access is unavailable here. The failing command was:

```bash
cd app/XDM.Android && ./gradlew :browser-integration:test --offline
```

Failure excerpt: `java.net.UnknownHostException: services.gradle.org`.
