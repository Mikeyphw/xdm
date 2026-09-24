# XDM Android test contract hang hotfix v1

## Scope

This hotfix updates the stale Phase 08/09 source-contract test after the media-capture FFmpeg hotfix changed the legacy single-capture security path.

The runtime code now correctly rejects only replayable browser credential context in the legacy single-capture path and reports the user-facing failure as `Browser session capture required`. The old test still expected the earlier wording and the earlier broad URL-secret predicate, causing `:app:testDebugUnitTest` to fail before the later Phase 11/12 source-contract test appeared to hang under `--continue`.

## Fix

Updated `RemediationPhase08_09ContractTest.phase09EncryptedImportIsJournalFirstRoomFirstAndRequestIdentityBound` so the legacy-capture contract now checks for:

- `Browser session capture required`
- `ExternalUrlPolicy.hasReplayCredentialBearingQuery`

The test still verifies that the legacy single-capture path stores media only before `rememberPreparedRevision`, and it keeps the encrypted Firefox v3 capture-session contract intact.

## Audit loop result

- Confirmed the failing assertion was stale relative to `MainViewModel.captureMediaRequest`.
- Confirmed the runtime source contains the newer `hasReplayCredentialBearingQuery(facts.url)` path and the newer user-facing browser-session wording.
- Confirmed no production source is changed by this overlay.
- Confirmed the focused source-contract expectations now match the current runtime behavior.

## Validation guidance

Run the focused app test first:

```bash
REPO="$HOME/Code/xdm"

devtool -r "$REPO" --target xdm_android validate \
  --task :app:testDebugUnitTest
```

If Termux still appears stuck after the stale assertion is fixed, capture a Gradle/Test worker thread dump before killing it:

```bash
jps -lv
jcmd PID Thread.print > /sdcard/Download/xdm-test-hang-thread-dump.txt
```
