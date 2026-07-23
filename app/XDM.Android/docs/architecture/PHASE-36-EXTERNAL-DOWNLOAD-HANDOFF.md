# Phase 36: External Download Handoff

Phase 36 turns browser and share-sheet intake into a first-class Add Download prompt instead of a media-first side path.

## Why this exists

The Phase 35 audit found that XDM already had broad `ACTION_SEND`, `ACTION_VIEW`, and browser download intent filters, but they were attached to the main launcher activity and routed through `CaptureMedia` first. That made external download handoff feel indirect: normal links could disappear into media detection before Add Download was shown.

Download Navi uses a dedicated add-download entry point: an exported add activity reads `intent.data` or `Intent.EXTRA_TEXT` and immediately opens an add dialog. Phase 36 mirrors that funnel without copying Download Navi code or changing XDM's app topology.

## Runtime contract

- `ExternalAddDownloadActivity` is the dedicated exported external receiver.
- The activity label is `Add to XDM` so browsers and Android Sharesheet can surface a clear external download option.
- `MainActivity` remains the launcher and Tasker/custom command surface.
- External browser/share/download-manager handoffs are converted to `PromptAddDownload`.
- `PromptAddDownload` opens the existing Add Download screen with a prefilled draft.
- External browser/share handoffs never auto-queue.
- Explicit media capture remains available through `CAPTURE_MEDIA`, Tasker, and internal browser sniffing flows.
- `ACTION_SEND` receives `text/plain`, `text/*`, and `*/*`.
- `ACTION_VIEW` receives `http`, `https`, and `ftp` URLs plus common downloadable path patterns.
- Browser download actions are accepted for `http`, `https`, and `ftp`.
- Cookies, bearer values, tokens, and request headers remain redacted before persistence.

## UI and UX contract

The Add screen now treats external handoff as a visible landing pad:

- It shows `Link received` for external drafts.
- It shows the handoff source label.
- It shows a filename suggestion when one is present.
- It states that cookies, tokens, and request headers stay redacted.
- It keeps the primary `Start download` CTA in the sticky bottom action area.

## Topography constraints

- No new top-level route.
- No Room migration.
- No app version bump.
- No raw shell exposure.
- No DRM bypass.
- No durable cookie/header/token/session persistence.

## Device-side probes

After applying the overlay, confirm resolver visibility on a device with:

```bash
adb shell cmd package query-intent-activities \
  -a android.intent.action.SEND \
  -t text/plain

adb shell cmd package query-intent-activities \
  -a android.intent.action.VIEW \
  -d 'https://example.com/file.zip' \
  -t 'application/zip'

adb shell cmd package query-intent-activities \
  -a com.android.browser.action.DOWNLOAD \
  -d 'https://example.com/file.mp4' \
  -t 'video/mp4'
```

Expected result: an `Add to XDM` external entry appears and opens the Add Download prompt with the link filled in.

## Release note

Phase 36 is a runtime handoff and UX patch. It intentionally changes the browser/share intake path, but does not alter transfer engines, queues, storage finalization, media execution, or release version metadata.

## Lint posture

Browser/download-manager VIEW filters intentionally use `android:autoVerify="false"` because XDM is claiming generic downloadable URLs as a user-selected external download target, not verified ownership of arbitrary web domains.
