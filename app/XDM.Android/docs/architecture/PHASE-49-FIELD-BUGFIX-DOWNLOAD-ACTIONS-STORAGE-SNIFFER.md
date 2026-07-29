# Phase 49 Field Bugfix: download actions, storage labels, and media sniffing

## Problems fixed

1. Download item menu actions were visually present but many of them reused the details fallback. That made every option feel like the same action.
2. App-side media sniffing reported opaque 403 failures for sites that require the live browser session.
3. Completed public files are saved through Android scoped storage, so the persisted handle is often a `content://` URI. Showing that raw handle in normal UI made it look like XDM had not written a normal file.
4. The Firefox extension accepted too many non-video resources when a JSON response or API route merely contained generic URL-ish fields.

## Repair shape

- The download action sheet now has explicit dispatch for open file, share file, cancel, redownload, queue movement, delete record, and delete file plus record.
- Destructive and redownload actions still require confirmation before execution.
- Normal UI renders destination labels such as “Downloads folder” or “Saved in Android shared storage” instead of raw destination handles.
- MediaStore commits continue to use Android scoped storage, but final promotion clears `IS_PENDING` and refreshes `DATE_MODIFIED` so file managers have a fresh committed item to show.
- App-side page probing uses a bounded browser-like GET, checks HTTP status before reading the body, and turns 401/403 into a useful diagnostic: use browser-extension capture when cookies, referer, or the live session are required.
- Extension detection keeps strong MIME/manifest/media-extension signals, keeps 1DM-style XHR/fetch observations, and rejects generic JSON `url`/`src` fields or small API endpoints unless they point at an actual media/manifest resource.

## 1DM APK inspection boundary

The APK was inspected only with string-level evidence. The useful design signals were XHR/fetch observation, content type/disposition/length/range, referer/user-agent awareness, media-element observation, and strict media/manifest extension recognition. No implementation code was copied.

## Android storage note

On modern Android, apps generally should not write arbitrary absolute public paths. Public Downloads/Movies/Documents are represented by MediaStore content handles. That is normal and safer than requesting broad all-files access. XDM now keeps those handles internally while explaining the human destination in normal UI.
