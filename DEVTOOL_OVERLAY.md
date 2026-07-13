# XDM Overlay — Safe native and aria2 backend selection

This overlay applies on top of commit `fdd2615`.

## Unified backend ownership

- adds per-download Automatic, Native, and aria2 backend preferences;
- records the selected backend, aria2 task identifier, fallback policy, and routing reason in history and snapshots;
- keeps one XDM download as the owner of each backend task;
- restores persisted aria2 ownership without silently switching to native while aria2 is unavailable;
- adopts matching active aria2 tasks after restart when adoption is enabled;
- verifies the destination and expected length before accepting an adopted task;
- blocks active/waiting/paused aria2 destination collisions instead of starting a second writer;
- permits reuse after terminal aria2 tasks no longer own the destination.

## Automatic selection

- keeps the native engine as the default and mandatory backend for unsupported request types and FTPS;
- recommends aria2 for large downloads, mirrored downloads, FTP, and high requested connection counts;
- exposes configurable large-file and connection-count thresholds;
- supports global and per-download native fallback controls;
- allows fallback only before aria2 task ownership is established;
- refreshes aria2 before replacing a persisted task that appears to be missing.

## aria2 integration

- passes mirrors and expected SHA-256/SHA-512 checksums to `aria2.addUri`;
- rejects malformed checksum text rather than stripping invalid characters;
- routes ordinary HTTP, HTTPS, and FTP tasks created from the aria2 settings panel through the unified XDM manager;
- leaves non-XDM protocols such as magnet links as explicitly external aria2 tasks;
- routes pause, resume, retry, cancel, delete, repair, completion, and integrity verification through the owning backend.

## Persistence and UI

- advances portable download-list exports to schema version 2;
- preserves backend preference and fallback settings while importing schema version 1 safely;
- adds backend selection to the new-download form;
- shows backend ownership, task ID, and routing reason in download details;
- adds automatic-routing and task-adoption controls to aria2 settings.

## Quality

- fixes the existing `CA1861` warning in `QueueDependencyTests`;
- adds backend advisor, collision, persistence, RPC, settings, manager, and UI architecture tests;
- preserves all native transfer, resume-integrity, queue-policy, browser-security, and secure-update behavior;
- does not modify `docs/parity/features.json`.

## Validation

Devtool must restore, build, and test only `app/XDM/XDM.Modern.sln`. The build must have zero warnings and zero errors.
