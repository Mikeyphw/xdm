# XDM Android XAR08 — aria2 Ownership, Session Ordering & Runtime Truth

**Overlay:** 8 of 17  
**Canonical section:** S08 — Embedded aria2 Backend  
**Findings closed:** **23/23**  
**Validation mode:** intermediate `--no-validate` apply; focused source/contract validator retained for XAR16/XAR17.

## Implementation summary

XAR08 makes aria2 an explicitly owned backend instead of a best-effort child process. The backend now gates URI eligibility, mirror and credential handling, mapping/session ordering, removal, cancellation, recovery, and runtime capability truth through deterministic ownership states.

Major changes:

- aria2 capabilities are conservative and feature-aware through `lastKnownEnabledFeatures`; SFTP and magnet are not advertised as normal XDM-owned aria2 protocols.
- `prepare()` rejects magnet, SFTP, torrent/metalink/provider-owned inputs and rejects credential-bearing source or mirror URLs.
- Sensitive request headers cannot be applied across aria2 mirror origins.
- GID creation commits Room mapping, ownership metadata, and `xdm.session` as one ordered operation; save-session failure tears down the paused GID before activation.
- Removal is fail-closed: if RPC is unreachable or `aria2.saveSession` fails, the mapping moves to `RemovalPending` and XDM retains ownership for retry instead of deleting local truth.
- Cancel wins at the complete-but-unpublished boundary: a completed aria2 RPC result is not promoted if the user cancel arrives before XDM publication metadata is durable.
- Recovered `complete` no longer becomes safe `ActiveTaskVerified`; XDM returns verification-required recovery until final output/publication checks run.
- `RetiredForMigration` mappings cannot be resurrected by reconciliation status refresh.
- `FinalizationFailed` and `RemovalPending` are recoverable owner states, not terminal dead ends.
- The last mapping update to `Completed` is mandatory. Completion is not acknowledged if terminal mapping/ownership metadata persistence fails.
- Startup removes stale temporary RPC launch configs, rotates bounded runtime logs, and sanitizes `xdm.session` against owned task metadata before launch.
- Runtime lease records the child process id when available, so app-process ownership evidence is bound to the managed child it started.
- aria2 active task discovery is batched and the event poller backs off low-activity waiting states.
- Lifecycle probes use transient GIDs and the runtime includes `purgeSavedSession` support so diagnostic/probe results do not persist as durable download truth.
- Add Download method guidance recommends aria2 only for unsigned direct large files without captured credentials or expiring policy.

## Canonical coverage

- `S08-01` — fixed by XAR08 ownership/session/runtime truth controls.
- `S08-02` — fixed by XAR08 ownership/session/runtime truth controls.
- `S08-03` — fixed by XAR08 ownership/session/runtime truth controls.
- `S08-04` — fixed by XAR08 ownership/session/runtime truth controls.
- `S08-05` — fixed by XAR08 ownership/session/runtime truth controls.
- `S08-06` — fixed by XAR08 ownership/session/runtime truth controls.
- `S08-07` — fixed by XAR08 ownership/session/runtime truth controls.
- `S08-08` — fixed by XAR08 ownership/session/runtime truth controls.
- `S08-09` — fixed by XAR08 ownership/session/runtime truth controls.
- `S08-10` — fixed by XAR08 ownership/session/runtime truth controls.
- `S08-11` — fixed by XAR08 ownership/session/runtime truth controls.
- `S08-12` — fixed by XAR08 ownership/session/runtime truth controls.
- `S08-13` — fixed by XAR08 ownership/session/runtime truth controls.
- `S08-15` — fixed by XAR08 ownership/session/runtime truth controls.
- `S08-16` — fixed by XAR08 ownership/session/runtime truth controls.
- `S08-17` — fixed by XAR08 ownership/session/runtime truth controls.
- `DS4-S08-01` — fixed by XAR08 ownership/session/runtime truth controls.
- `DS4-S08-02` — fixed by XAR08 ownership/session/runtime truth controls.
- `DS4-S08-03` — fixed by XAR08 ownership/session/runtime truth controls.
- `DS4-S08-04` — fixed by XAR08 ownership/session/runtime truth controls.
- `DS4-S08-05` — fixed by XAR08 ownership/session/runtime truth controls.
- `DS4-S08-06` — fixed by XAR08 ownership/session/runtime truth controls.
- `RERUN34-S08-01` — fixed by XAR08 ownership/session/runtime truth controls.

## Focused validation

Run:

```bash
python3 tools/validate-xar08-aria2-ownership.py
```

Expected result:

```text
XAR08 aria2 ownership contract passed: 39 checks; 23/23 S08 findings covered.
```

## Exit criteria

XAR08 is complete when aria2 GIDs cannot outlive or contradict XDM ownership, completed output is not acknowledged before durable terminal mapping/publication evidence is written, removal/cancel/recovery cannot fail open, and capability/selection truth no longer advertises backend behavior XDM cannot safely own.
