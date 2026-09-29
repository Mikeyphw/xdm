# XGO-RM05 Android Host Instrumentation Convergence

## Trigger

After RM04 v4, Android native loading and JNI crossed the physical/emulator
boundary successfully. The instrumentation runner executed all 25 tests rather
than crashing before test execution. Fourteen passed and eleven failed.

Those failures split into four ownership groups:

1. primary-navigation accessibility semantics;
2. legacy encrypted-v2 AndroidKeyStore test isolation/contract versioning;
3. FFmpeg androidTest asset ownership;
4. adaptive Compose test-harness/profile semantics.

## Navigation semantics

Bottom-navigation and sidebar destinations already expose visible labels, but
their stable semantics did not expose `contentDescription = route.label`.
AppSmokeTest therefore could not address Downloads/Settings through the
accessibility contract.

RM05 adds the route label to both navigation surfaces.

## Pane semantics

`xdmPane()` exposed paneTitle but not contentDescription. Adaptive-sheet tests
and assistive navigation require the stable pane label to be discoverable.
RM05 exposes the same label as contentDescription without changing traversal
or layout behavior.

## Legacy encrypted-v2 test isolation

New browser extensions are v3 and no longer depend on AndroidKeyStore capture
keys. Encrypted-v2 support remains reader-only migration compatibility.

The instrumentation test previously constructed encrypted fields with
`CurrentVersion` (v3), which cannot satisfy `hasEncryptedCaptureEnvelope`.
It now uses `EncryptedCaptureVersion`.

Repeated `adb install -r` runs preserve AndroidKeyStore entries. The debug-only
instrumentation setup deletes the two legacy capture aliases before each test
so the test validates the current key-generation contract rather than stale
debug APK state.

## FFmpeg test assets

The FFmpeg fixtures are under `src/androidTest/assets`, therefore they belong
to the instrumentation APK. The acceptance test incorrectly opened them from
`targetContext.assets` (the app APK). RM05 reads fixtures from instrumentation
context while continuing to run the actual FFmpeg/storage behavior against
targetContext.

## Adaptive Compose harness

The screenshot matrix called `setContent` repeatedly on one Compose rule,
which is illegal after the activity has already set content. It now installs
content once and mutates the active matrix case.

The sheet focus-restoration test now dismisses the sheet through Android back
rather than pretending the sheet surface itself is a dismiss button.

The UIX R6 shell helper now provides a `LocalXdmWindowProfile` consistent with
the requested Compact/Medium/Expanded class, matching the production
XdmAdaptiveShell contract.

## Qualification scope

RM05 does not modify XGO native/JNI code. The already-qualified Wave-12 XGO
seal and RM04 native ABI remain checkpointed. Because RM05 changes Android app
and androidTest sources, the invalidated boundary is Android package + lint +
physical instrumentation, followed by desktop/final Gate-12 continuation.

## RM05 v2 — residual instrumentation convergence

RM05 v1 reduced the device suite from eleven failures to five. RM05 v2
addresses those five observed residuals without changing XGO native/JNI code:

- synchronize the Add Download smoke test with the current helper copy;
- make the legacy-v2 RSA-OAEP self-test browser-faithful by encrypting with a
  software/JCA public key reconstructed from exported SPKI while retaining
  AndroidKeyStore private-key decryption;
- preserve the MP4 extension on FFmpeg publication staging so muxer inference
  matches the production media path;
- restore opener focus one frame after the adaptive sheet leaves composition;
- derive adaptive shell identity from `windowClass`, not navigation treatment.

Because this remediation changes Android app and androidTest sources, it
invalidates the Android package/artifact/lint/instrumentation checkpoint. The
`xgo_gate_final_continue` workflow is therefore refreshed to begin with its
cheap topology sanity check and then execute package refresh -> artifact
verification -> lint -> all device instrumentation -> desktop host -> Wave-12
final verifier. It deliberately does not replay the already-qualified
`xgo_seal` or earlier Android unit/dependency phases.

Qualification is deferred to `./devtoolw gate-final-continue`; this report does
not claim the five failures are closed until that continuation is green.

## RM05 v3 — final two residuals

The RM05 v2 continuation executed all 25 device tests and reduced the residual
set from five failures to two. The Add Download, FFmpeg mux, and adaptive shell
identity fixes are therefore device-qualified by that run.

The two remaining failures were:

1. legacy-v2 RSA-OAEP software/WebCrypto-side encryption succeeded, but
   AndroidKeyStore private-key decryption failed at KeyMint `finish()`;
2. adaptive-sheet dismissal requested opener focus only once and the focus
   controller reported success without propagating `FocusRequester.requestFocus()`'s
   actual result.

RM05 v3 pins newly generated legacy-v2 capture keys to the interoperable
SHA-1/SHA-1 OAEP contract used by WebCrypto and AndroidKeyStore's MGF1 default.
The legacy SHA-256 alias remains discoverable for reader-only migration attempts
rather than being deleted from production state. The self-test continues to
encrypt through a reconstructed software/JCA public key and decrypt through the
AndroidKeyStore private key, so the browser/app boundary remains real.

Focus restoration now returns the real `FocusRequester.requestFocus()` result
and retries for four bounded composition frames after the modal leaves the
visible state. The accessibility assertion remains unchanged.

The Gate-12 continuation also gains an RM05 residual precheck immediately after
APK refresh/artifact verification. It runs only the Keystore OAEP and adaptive
focus methods, so a repair regression fails before the expensive lint phase. A
green precheck still proceeds through full lint and all 25 instrumentation tests;
it is not accepted as final evidence by itself.

Qualification remains deferred to `./devtoolw gate-final-continue`.

## RM05 v4 — surviving-opener focus ownership

The RM05 v3 fast precheck qualified the legacy-v2 AndroidKeyStore OAEP fix on
`emulator-5554`. Only the adaptive-sheet opener-focus assertion remained red.
The precheck prevented the already-known focus defect from triggering another
full lint run.

The v3 implementation still issued `FocusRequester.requestFocus()` from the
sheet teardown coroutine. Even with bounded frame retries, that request raced
the modal/popup focus owner finishing disposal and could be cleared after the
requester reported success.

RM05 v4 moves fulfilment to the surviving opener subtree.
`XdmFocusRestorationController` now publishes a keyed restoration generation.
`xdmFocusRestorePoint()` observes that request on the registered opener, makes
the focus request from that subtree, and retries for a bounded twelve frames
until the controller observes focus stable across two frames. The controller
tracks actual focused state through `onFocusChanged`, while `restoreLastFocus()`
remains as a compatibility entrypoint that delegates to the new request model.

`XdmAdaptiveSheet` publishes a single restoration request when visibility
transitions from open to closed and restores the explicit
`dismissAndRestoreFocus()` dismissal boundary used by the Phase-9 static
contract. The production accessibility assertion is unchanged.

The Gate-12 continuation remains fail-fast. Since the RM05 v3 precheck already
qualified the OAEP method and v4 changes only the focus-restoration owner, the
fast precheck now runs only
`Phase9AccessibilityAdaptiveLayoutInstrumentedTest#adaptiveSheetRequestsFocusAndRestoresFocusToOpener`.
A green precheck must still proceed through authoritative lint, all 25 device
instrumentation tests, desktop qualification, and the Wave-12 final verifier.

Qualification remains deferred to `./devtoolw gate-final-continue`.
