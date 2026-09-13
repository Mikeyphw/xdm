# XDM Android XAR04 Navigation Session Ownership Report

Overlay **4 of 17** implements **XAR04 — Navigation Truth & Add Download Session Ownership**. It owns S02 and closes **16/16** canonical navigation/session findings.

## Delivered behavior

- Added `AddDownloadNavigationSession` and `AddDownloadNavigationPolicy` as the single owner for Add route return destination, external draft ownership, and duplicate-prompt URL binding.
- Replaced composition-local `lastPrimaryRouteName` Add return state with ViewModel-owned session state collected once by `XdmApp`.
- Manual Add now uses `beginManualAddDownload()` and clears stale external drafts before creating a new keyed form session.
- External Add reviews create a session tied to the command/draft id; the Add sheet renders an external draft only when the active session owns it.
- Add form `rememberSaveable` state is keyed by Add session id so a previous external draft cannot restore into a later manual session.
- Edited external URLs are treated as manual/headerless requests for Add and Inspect Media instead of reusing stale captured credentials/proof.
- Duplicate decisions are fenced by Add session id and record the exact URL that produced the prompt.
- Activity action dispatch now prefers `OperationalActivityActionId` and falls back to legacy labels only for retained rows.
- Problem notification targets persist through `UserPreferencesStore.setProblemNavigation()` and restore through durable preferences.
- Foldable posture selection now considers all `FoldingFeature` instances and prefers separating/half-open/large hinges.
- Expanded-width bottom-navigation fallback is tagged/described as expanded fallback, not Medium.
- Automatic two-pane detail selection no longer persists a user-owned detail id that can auto-open a compact modal after restore.

## Canonical closure matrix

- `S02-01` — cold external Add Download restores to the wrong previous route: fixed by XAR04.
- `S02-02` — navigation return state has split ownership: fixed by XAR04.
- `S02-03` — expanded adaptive instrumentation had inconsistent shell evidence: fixed by XAR04.
- `S02-04` — missing behavioral process-death navigation restoration proof: fixed by XAR04.
- `S02-05` — source-text navigation tests instead of behavior contracts: fixed by XAR04.
- `S02-06` — only first FoldingFeature considered: fixed by XAR04.
- `S02-07` — expanded fallback tagged Medium: fixed by XAR04.
- `S02-08` — Activity collected uiState twice in composition: fixed by XAR04.
- `DS1-S02-01` — reentrant Add navigation resets active admission: fixed by XAR04.
- `DS1-S02-02` — external Add review uses a single mutable slot: fixed by XAR04.
- `DS1-S02-03` — edited Add form diverges from captured duplicate request: fixed by XAR04.
- `DS1-S02-04` — Activity actions dispatch by display text: fixed by XAR04.
- `DS1-S02-05` — two-pane auto detail selection can auto-open compact modal: fixed by XAR04.
- `DS1-S02-06` — problem notification target not durable: fixed by XAR04.
- `DS1-S02-07` — nullable override resurrects stale durable selections: fixed by XAR04.
- `RERUN-S02-01` — stale external draft restored into later manual Add session: fixed by XAR04.

## Validation

- Focused validator: `tools/validate-xar04-navigation-session-ownership.py`
- Gradle task: `verifyXar04NavigationSessionOwnership`
- Final release gate carries the XAR04 validator.
- This is an intermediate roadmap overlay and should be applied with `--no-validate`; final Gradle/device validation is reserved for XAR16/XAR17.
