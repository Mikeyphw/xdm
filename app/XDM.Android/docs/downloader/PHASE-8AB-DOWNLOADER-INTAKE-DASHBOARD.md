# Phase 8A + 8B: Downloader Intake and Dashboard

## Purpose

Phase 8A and 8B refine XDM Android after the downloader-only architecture seal. They do not add a browser, transfer engine, database migration, or background execution path.

## Phase 8A: review-first Add Download

Add Download now applies the same neutral review planner to manual entry and explicit external handoff.

The planner:

- normalizes HTTP, HTTPS, and FTP input;
- classifies direct files, direct media, HLS/DASH, torrents, and page/unknown endpoints;
- exposes Link, Destination, and Review readiness steps;
- recommends Media inspection for adaptive playlists and page URLs;
- never persists, queues, resolves, or executes a transfer;
- allows a direct download only through an explicit user action after a destination is selected.

Clipboard access is explicit. The user selects **Paste detected URL**, XDM extracts the first supported URL, and the result still passes through the review workflow.

Manual and external links can select **Inspect in Media**. This seeds the existing media workbench through `DownloadIntakeDraft` and `ExternalMediaReviewPlanner`; it does not auto-probe or auto-queue.

## Phase 8B: Downloads control center

Downloads are grouped into stable operational sections:

1. Needs attention
2. Active
3. Queued
4. Completed
5. History

Smart ordering prioritizes recovery work, active priority and speed, queued priority, and recent completed/history items. Users can switch to newest, name, or progress ordering.

Failure messages are classified into user-facing recovery signals for authentication, storage, destination permission, verification, network, recovery, or generic retry. These signals are advisory and reuse existing pause, resume, backend migration, recovery, and diagnostics actions.

## Preserved contracts

- six stable destinations remain Downloads, Add, Media, Library, Activity, and Settings;
- external browser sharing and typed download intents remain review-first;
- native, aria2, Termux, scheduler, worker, queue, resolver, library, and Media3 paths remain unchanged;
- Room remains schema 14;
- `versionCode` remains 21;
- `versionName` remains `0.20.0-rc08`;
- production Android WebKit remains absent.
