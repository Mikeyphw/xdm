# Phase 37: XDM Browser Scheme Contract

## Purpose

Phase 37 gives external browser integrations a uniquely addressable, review-first Android entry point without restoring an embedded browser or claiming ordinary web navigation.

Phase 37 originally introduced these version-1 links:

```text
xdmdownload://capture?v=1&url=<encoded-media-url>&page=<encoded-page-url>&title=<encoded-title>&filename=<encoded-name>&mime=<encoded-mime>&kind=hls
xdmdownload://add?v=1&url=<encoded-download-url>&page=<encoded-page-url>&title=<encoded-title>&filename=<encoded-name>&mime=<encoded-mime>
```

**Current compatibility note:** browser-extension media capture no longer emits the plaintext `capture?v=1` form. It is retained only as a legacy parser boundary. Current browser capture uses encrypted v2 (`sid`, `kid`, `ek`, `iv`, `ct`) and the production extension/acceptance tooling never manufactures a v1 capture URL. The non-sensitive `add?v=1` route remains supported.

Build variants use distinct schemes:

| Build | Scheme |
|---|---|
| Release | `xdmdownload` |
| Debug | `xdmdownload-debug` |

The selected scheme is exposed as `BuildConfig.XDM_BROWSER_SCHEME` and substituted into the manifest through `xdmBrowserScheme`.

## Receiver ownership

`ExternalAddDownloadActivity` is the only exported activity that owns the custom scheme. Its filter requires:

- `android.intent.action.VIEW`
- `android.intent.category.DEFAULT`
- `android.intent.category.BROWSABLE`
- host `capture` or `add`

`MainActivity` parses the custom envelope before applying the generic external-receiver rule:

- `capture` becomes `AutomationCommandAction.CaptureMedia`
- `add` becomes `AutomationCommandAction.PromptAddDownload`
- source becomes `AutomationCommandSource.BrowserExtension`

All existing share-sheet, typed `ACTION_VIEW`, browser download actions, Tasker actions, and ordinary launcher behavior remain unchanged.

## Security boundary

The outer custom URI is a compact doorbell, not a credential transport. It may carry only:

- media/download URL
- page URL
- page title
- suggested filename
- MIME type
- non-sensitive media kind

It never carries standalone cookies, authorization headers, proxy credentials, POST bodies, or unrestricted request headers.

The parser accepts only an inner `http`, `https`, or `ftp` URL and rejects nested or unsafe schemes including `javascript`, `data`, `file`, `content`, `blob`, `intent`, and XDM custom schemes. URLs containing user-info credentials are rejected. The parser also enforces these bounds:

| Field | Limit |
|---|---:|
| Entire deep link | 64 KiB |
| Media URL | 32 KiB |
| Page URL | 8 KiB |
| Page title | 240 characters |
| Filename | 160 characters |
| MIME type | 120 characters |

Signed query values that are already part of the media URL remain intact because they are required by many CDN links. They are still subject to the existing privacy-safe diagnostics redaction policy.

## Idempotency

The resulting `AutomationCommandDraft` uses the existing stable command identity. Repeated delivery of the same source URL, action, filename, and page URL resolves to the same idempotency key and does not create duplicate work.

## Architecture constraints

Phase 37:

- adds no top-level route;
- adds no WebView or `android.webkit` dependency;
- keeps `ExternalAddDownloadActivity` as the sole browser-download receiver;
- leaves Room schema 14 unchanged;
- leaves `versionCode 21` and `versionName 0.20.0-rc08` unchanged.

## Validation

Run:

```bash
python3 tools/validate-phase-37-browser-scheme-contract.py
./gradlew --no-daemon --max-workers=1 \
  :browser-integration:testDebugUnitTest \
  :core-model:test \
  :app:testDebugUnitTest \
  :app:assembleDebugAndroidTest
```

Device resolution can be checked with the installed build's scheme:

```bash
adb shell am start -W \
  -a android.intent.action.VIEW \
  -c android.intent.category.BROWSABLE \
  -d 'xdmdownload://add?v=1&url=https%3A%2F%2Fexample.com%2Ffile.zip'
```

Use `xdmdownload` for release builds and `xdmdownload-debug` for debug builds.
