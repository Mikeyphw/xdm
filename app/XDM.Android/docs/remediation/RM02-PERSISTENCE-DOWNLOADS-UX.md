# RM02 — Persistence + Downloads UX

Roadmap position: **Overlay 2 of 4**.

RM02 seals two user-visible integrity boundaries together: a media capture may never remain linked to a missing Download row, and the Downloads surface may never describe a completed transfer's publication/finalization failure as an ordinary network failure.

## Persistence integrity

- `createDownloadFromMediaCapture` remains a Room transaction.
- The legacy `markMediaDownloadCreated` compatibility path now refuses to link a capture to a missing Download row.
- Startup repair hides stale app-owned media outputs and repairs `DownloadCreated` captures whose `downloadId` is null or missing.
- Download graph deletion hides the deleted app output and rebinds its capture to another valid app-owned output when one exists; otherwise the capture returns to `MetadataReady` before the Download FK deletion occurs.
- The Debug Center media transaction probe now checks the `Cancelled` state it actually writes instead of incorrectly demanding `Queued`.

## Download identity

`DownloadPresentationPolicy` prevents UUID/hash-like internal identifiers from becoming the normal card title. The display fallback is the reviewed name, usable URL path filename, page title, then a stable host-based `download-<host>` name with a known extension when available. Intake preflight remains the authority for server `Content-Disposition` suggestions.

## Downloads card

The card now gives the title the content width, places status/source metadata beneath it, renders only one visible state-aware quick action with a text label, and retains overflow for secondary/advanced actions. Finalization failures show `Needs attention`, `Transfer complete`, and a friendly explanation rather than a raw attempt-generation exception. The 100% payload bar is suppressed for this post-transfer failure phase.

Destination copy is state-aware: completed rows may say `Saved to …`; incomplete/finalization-failed rows say `Destination: …` instead of claiming a successful save.

## Notifications

Final-save recovery uses the friendly title `Couldn't finish download` with the explanation that the payload was transferred but could not be saved. Recovery-required final-save notifications expose `Retry finalization`. Durable terminal records replay their persisted action model rather than recalculating a potentially different action after restart. Historical positive-attempt-generation failures are routed to recovery review rather than an ordinary network retry.

## Validation

Static seal: `tools/validate-rm02-persistence-downloads-ux.py`

Gradle task: `:app:verifyRm02PersistenceDownloadsUx`

RM02 is an intermediate roadmap overlay. Full release/device validation remains assigned to Overlay 4 of 4.
