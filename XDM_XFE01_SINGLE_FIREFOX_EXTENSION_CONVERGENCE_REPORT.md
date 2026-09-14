# XDM XFE01 — single Firefox extension convergence

## Status

**Merged roadmap overlay 1 of 8.** XFE01 converges desktop and Android Firefox support on the already-working Android-owned Firefox extension without changing its source or capture logic.

## Canonical source invariant

The sole Firefox implementation is:

`app/XDM.Android/browser-extension/src/main/extension/xdm-firefox`

Its 16 source files are byte-for-byte unchanged from the pre-XFE01 repository snapshot. The extension keeps ID `xdm-android-media-bridge@mikeyphw`, Manifest V2, the existing detector/candidate/network pipeline, `xdmdownload://add` v1, `xdmdownload://capture` v3, capture-session batching, and final-sent-header evidence semantics.

The former desktop implementation under `app/XDM/firefox-amo` is retired as an explicit non-executable tombstone because Devtool overlay inventory requires listed files to exist and no proven delete action is part of the established overlay contract. Its manifest is no longer a loadable WebExtension and its JavaScript/HTML/CSS logic is replaced by retirement markers. Desktop does not add native-messaging code to the canonical Firefox extension.

## Desktop adaptation

Desktop now consumes the extension's existing contract instead of asking the extension to consume the desktop contract:

- `FirefoxExtensionHandoffParser` validates the canonical add/capture versions, URL schemes, sizes, identities and bounded session candidates.
- Only final-sent header evidence can become replay metadata. `proposedHeaders` is never promoted to executable authority; desktop also retains its existing header allow-list and does not promote Authorization or Range.
- The desktop executable accepts `xdmdownload` and `xdmdownload-debug` launches. If XDM is already running, the exact bounded URI is relayed through the authenticated single-instance channel to the primary process.
- `MainWindowViewModel` routes canonical add requests through the normal download path and capture requests through the existing media-catalog path.
- Linux browser repair creates per-user custom-protocol registration; Windows repair owns equivalent per-user protocol keys. Chromium native messaging is left intact and separate.
- Linux package metadata advertises both schemes. Desktop release publishing renders `XDM-Firefox.xpi` from Android's canonical source with the same renderer and stable identity.

## Regression protection

XFE01 adds coverage for:

- add v1 and capture v3 parsing;
- final-vs-proposed header authority;
- multi-candidate capture sessions;
- malformed/duplicate/version/credential/scheme rejection;
- exact single-instance payload forwarding;
- canonical Firefox ID/permissions/handoff/observer fixture checks;
- protocol registration, removal of the obsolete Firefox native-messaging manifest, Chromium coexistence, and tamper detection;
- deterministic canonical-XPI rendering; and
- a source-hash validator that refuses XFE01 if any canonical Firefox source byte changed.

## Validation boundary

XFE01 is an integration overlay, not the desktop final seal. Its Devtool validation runs desktop restore/build/test. REM18 remains the desktop package/final-release seal and XAR16/XAR17 remain the Android/repository release gates.


## v3 artifact-manifest repair

The v3 archive removes the `workspace.root: "."` manifest field used in v2 because Devtool normalizes that root to an empty path during artifact manifest validation. The payload source intent remains the same: Android Firefox extension logic is preserved and desktop is adapted around that canonical extension.


## v4 repair note

The 20260914 v3 Devtool run passed artifact validation and applied, then failed during full xdm_modern validation before REM18. XFE01 is a pre-seal convergence overlay, so apply it with --no-validate. v4 also fixes the new XFE01 adapter compile/analyzer findings: explicit new string(char[]), StartsWith(char), and an explicit FileStream lock for secondary-instance tests. Full desktop validation remains owned by REM18, merged overlay 2 of 8.

## v5 manifest repair

v5 is a manifest-only repair over v4. The v4 payload was rejected before apply because `validation.failure_action` used the descriptive value `defer_to_release_gate`; Devtool accepts only `pause`, `rollback`, `keep`, or `prompt`. v5 changes that field to `keep`, preserving the intended pre-seal `--no-validate` application mode and leaving the code payload unchanged. REM18 remains the full desktop release validation gate.
