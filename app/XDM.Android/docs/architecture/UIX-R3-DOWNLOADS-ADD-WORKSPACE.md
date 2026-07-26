# UIX R3: Downloads and Add Workspace

## Purpose

UIX R3 turns Downloads into a transfer-first control center and rebuilds Add as a calm, review-first workflow. It lands on the UIX R2 adaptive shell without changing queue execution, engines, persistence, recovery, external handoff, or media resolution behavior.

## Downloads contract

The header shows only useful live metrics: active count, aggregate speed, estimated remaining time, and queued count. Active, Queued, Finished, and All are the visible filters. Search is an explicit toolbar action. Tags, saved searches, archive state, bulk management, and history live in **Organize downloads**, keeping rows near the top of compact screens.

Rows are flat and compact. They expose file type, name, human state or reason, progress, size, speed or ETA, and one primary action. Long press enters selection mode. A permanent Select control is forbidden.

Compact and medium layouts open details in an adaptive sheet. Expanded layouts use an adaptive list-detail workspace with a persistent details pane. Useful status, actions, destination, source, and verification appear first. Engine choice, resume state, request redaction, and migration controls remain folded under technical details.

## Add contract

Add is a two-step review workflow. Step 1 collects the link, optional file name, destination, and safe automatic method. Advanced engine override, conflict behavior, fallback, and checksum settings remain folded. Step 2 shows File, Destination, and Method before **Add to queue** becomes the final action.

External handoffs name the source app and remain review-first. Media URLs may use **Inspect media**, which opens resolution without auto-queueing a transfer. Cookies, request headers, backend probes, and planner output never appear in the normal Add surface.

## Preserved contracts

- Six internal routes remain stable, with five visible destinations.
- Room remains schema 14. No Room schema bump.
- `versionName` remains `0.20.0-rc08`; `versionCode` remains `21`.
- Native, aria2, Termux, yt-dlp, Media3, queue intelligence, scheduler, recovery, and external handoff behavior remain intact.
- Developer diagnostics remain behind the UIX R1 boundary.
