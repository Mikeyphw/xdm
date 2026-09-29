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

## RM05 v5 — correct focus-test precondition and teardown synchronization

The RM05 v4 fast precheck still reported only
`adaptiveSheetRequestsFocusAndRestoresFocusToOpener` as failing. Review of the
instrumentation itself found that the test did not establish the contract it was
asserting: it clicked the opener and then manually called
`markLastFocused("open-sheet")`. A Compose click is not proof that the opener
owned focus before the modal opened, so the test was manufacturing restoration
state rather than exercising the real focus lifecycle.

RM05 v5 corrects the test rather than weakening or deleting the accessibility
requirement. The test now:

1. calls the Compose test `requestFocus()` action on the opener;
2. proves the opener is focused before opening the sheet;
3. opens the sheet without manually mutating the restoration controller;
4. dismisses through Android back;
5. waits until the adaptive-sheet semantics node no longer exists; and
6. asserts that the same opener regained focus.

The Phase-9 static validator now requires both `requestFocus()` and
`waitUntilDoesNotExist`, preventing a return to the synthetic
`markLastFocused()`-only setup. Production focus code and the
`xgo_gate_final_continue` dependency graph are intentionally unchanged from v4;
the existing single-method RM05 precheck remains the authoritative fast runtime
check before lint and the complete 25-test instrumentation suite.

Qualification remains pending until `./devtoolw gate-final-continue` passes on
the Termux/emulator or device environment.


## RM05 v6 — non-experimental Compose teardown wait overload

The first focused-test rebuild of RM05 v5 stopped at
`:app:compileDebugAndroidTestKotlin` before instrumentation. The corrected test
used the legacy two-argument `ComposeTestRule.waitUntilDoesNotExist(matcher,
timeoutMillis)` overload, which is marked `ExperimentalTestApi` by the Compose
test library consumed by this project; this repository treats missing
experimental opt-ins as compilation errors.

RM05 v6 keeps the v5 lifecycle model intact and switches only to the current
non-experimental overload by passing `useUnmergedTree = false` explicitly. No
production focus behavior and no `xgo_gate_final_continue` dependency is
changed. The Phase-9 static validator now requires the explicit overload so a
future edit cannot silently fall back to the experimental signature.

Qualification remains pending until the Android test APK compiles and the
existing `./devtoolw gate-final-continue` focus precheck passes.


## RM05 v7 — explicit Compose ExperimentalTestApi opt-in

The RM05 v6 Android-test rebuild proved that the Compose test library resolved by
this repository does not provide the newer `waitUntilDoesNotExist(...,
useUnmergedTree = ...)` overload. The compiler reported both that the available
API remains experimental and that `useUnmergedTree` is not a recognized
parameter.

RM05 v7 therefore uses the repository's actual API surface: it restores the
two-argument `waitUntilDoesNotExist(matcher, timeoutMillis)` call and explicitly
opts only `adaptiveSheetRequestsFocusAndRestoresFocusToOpener` into
`ExperimentalTestApi`. Production focus code, the accessibility assertion, and
`xgo_gate_final_continue` remain unchanged.

The Phase-9 validator now requires the explicit experimental opt-in and rejects
the unsupported `useUnmergedTree = false` signature so this compatibility error
cannot recur.

Qualification remains pending until `:app:assembleDebugAndroidTest` compiles and
the existing focused instrumentation precheck passes.


## RM05 v8 — host-window-authoritative focus restoration

RM05 v7 compiled successfully far enough for the focused instrumentation method
to execute. The method dismissed the adaptive sheet and observed that its
semantics node no longer existed, but the surviving `Open sheet` button still
reported `Focused = false`. This proves the remaining residual is production
focus restoration rather than test synchronization or Compose-test API
compatibility.

The v4 production implementation published a restore request when
`XdmAdaptiveSheet(visible = false)` recomposed, and the surviving opener retried
`FocusRequester.requestFocus()` for twelve Compose frames. That lifecycle signal
was still too early: Material modal/dialog semantics can disappear before the
separate Android modal window has relinquished window focus back to the host
activity. A request can therefore look briefly successful and then be cleared by
final window teardown.

RM05 v8 moves the retry gate to the actual Android ownership boundary.
`xdmFocusRestorePoint()` now captures `LocalView.current` and, after a keyed
restoration request, waits until the host view reports `hasWindowFocus()`. Only
then does it request Compose focus. It continues a bounded retry loop for up to
90 frames and requires the opener to remain focused for three consecutive
window-focused frames before considering restoration stable. If the modal window
briefly retakes ownership, stability is reset and no focus request is issued
until host ownership returns.

The Phase-9 static validator now requires the host-window focus gate and stable
focus tracking. The runtime assertion itself is intentionally unchanged: the
opener must genuinely regain focus after Android-back dismissal.

No Gate-12 continuation topology change is needed. The existing
`gate-final-continue` path already rebuilds the Android APK/test APK before the
RM05 focused precheck, so this production source change is naturally
requalified at that boundary. Qualification remains pending until the focused
instrumentation method passes on the Termux emulator/device.

## RM05 v9 — generation-scoped opener transaction and host-focus event handoff

The RM05 v8 focused instrumentation still failed after Android-back dismissal:
the adaptive-sheet semantics node disappeared, yet the surviving `Open sheet`
button reported `Focused = false`. Repeating focus requests for more frames is
therefore not an acceptable remediation. The remaining defect is the restoration
model itself.

RM05 v9 replaces the frame-polling model with a modal-lifetime restore
transaction:

1. `XdmAdaptiveSheet` captures the exact *currently focused and registered*
   restore point synchronously when the modal first enters composition, before
   Material3 creates the separate dialog window.
2. The captured `XdmFocusRestoreToken` belongs only to that modal lifetime.
   Dismissal cannot fall back to an unrelated historical `lastFocusedKey`.
3. When the modal leaves composition, the sheet publishes a generation-scoped
   restore request for that token.
4. `rememberXdmFocusRestorationController()` observes the host Android view with
   `ViewTreeObserver.OnWindowFocusChangeListener`. Restoration is scheduled from
   the real host-window focus-return event rather than by polling
   `hasWindowFocus()` for an arbitrary number of Compose frames.
5. The actual `FocusRequester.requestFocus()` call crosses a host-view
   `postOnAnimation` traversal boundary after host ownership returns. A `true`
   return is treated only as admission; it is not accepted as proof of restored
   focus.
6. Success requires a new `onFocusChanged(true)` acquisition whose focus
   generation is newer than the restore request baseline. This prevents stale or
   transitional `focusedKey` state from satisfying the transaction.
7. Two post-acquisition host traversals verify that the target remains focused.
   Loss of focus during that settlement window reopens the bounded request path.
   Retries are capped and are a recovery mechanism, not the synchronization
   mechanism.
8. Restore-point registration is requester-identity aware. Disposal callbacks
   from an old adaptive-layout representation cannot clear a newer requester for
   the same logical key.

The public compatibility methods `markLastFocused()`,
`requestRestoreLastFocus()`, and `restoreLastFocus()` remain available for
existing contracts, but `XdmAdaptiveSheet` no longer uses global last-focus
history. If a sheet is opened from a control that is not a registered restore
point, no unrelated target is focused on dismissal.

The Phase-9 static validator now requires the token capture, window-focus
listener, focus-acquisition generation, host-view traversal handoff, bounded
retry constants, and modal-scoped restore call. It explicitly rejects the v8
`repeat(90)` / `stableFocusedFrames` heuristic and rejects a return to
`XdmAdaptiveSheet` calling `requestRestoreLastFocus()` globally.

The end-to-end device assertion is deliberately unchanged: after real Android
Back dismissal and sheet teardown, the original opener must report Compose
`Focused = true`. The existing `gate-final-continue` focused precheck remains the
runtime authority before lint and the complete instrumentation suite.

## RM05 v10 — AndroidComposeView owner-focus handoff

The RM05 v9 focused precheck still failed only
`adaptiveSheetRequestsFocusAndRestoresFocusToOpener`. The v9 transaction was
correctly scoped to the captured opener and no longer depended on frame-count
polling, but it still treated `View.hasWindowFocus()` as sufficient evidence
that the surviving Compose focus owner was ready for child restoration.

That assumption is too weak for Material3 `ModalBottomSheet` on Android. The
sheet is hosted in a separate focus-owning modal Android host (dialog/application
panel depending on the Material3 revision). When that modal host is dismissed,
the activity window can regain window focus before its `AndroidComposeView` has
re-entered the Android View focus hierarchy. AndroidComposeView's owner-focus
path is distinct from window-focus notification, so an opener `FocusRequester`
can be asked to restore before the surviving Compose owner is authoritative
again.

RM05 v10 makes restoration a two-stage host/child transaction:

1. after the original activity window regains window focus, the controller
   explicitly reacquires focus on the surviving host `AndroidComposeView`;
2. only on a later traversal, after `view.isFocused` is true, does it request
   focus on the exact captured opener `FocusRequester`;
3. success still requires a fresh observed Compose focus acquisition and two
   stable traversal checks; `requestFocus()` return values alone are not treated
   as completion;
4. restore-point observation now uses `FocusState.hasFocus`, which is the
   correct contract for a restore modifier wrapping a Material component whose
   concrete focus target may be a descendant; and
5. bounded diagnostics record owner-focus and target-focus attempts and their
   acceptance results.

The Phase-9 instrumentation remains strict. It now additionally proves that the
controller observed the real opener before the sheet opens and that dismissal
published a restoration transaction. If final focus still fails, the assertion
includes the controller's owner/target diagnostic state rather than another
opaque `Focused=false` result.

The Gate-12 continuation topology remains unchanged: the focused RM05 precheck
must pass before lint and the complete instrumentation suite are allowed to run.
Qualification remains deferred to `./devtoolw gate-final-continue` on the
Termux/emulator or device environment.

## RM05 v11 — Devtool-owned Gradle execution convergence

The Gate-12 repair continuation previously owned three Android lifecycle steps as
repository shell jobs. In particular, APK refresh and lint called `./gradlew`
directly from `.devtool.toml`, while complete instrumentation was implemented as
a hand-written install/`am instrument` loop. That bypassed the Devtool Android
runner's SDK/JDK/AAPT2/provider/resource/telemetry boundary and made the same
Gate-12 work behave differently from ordinary `xdm_android` validation. On a
Termux session where project-local SDK discovery differed, the bypass surfaced
as a missing-SDK failure even though Devtool's Android runner already knew how
to resolve the toolchain.

RM05 v11 removes those orchestration bypasses:

- `xdm_android` now owns the Gate-12 package task set as its native Devtool
  `package` action;
- narrow `gate12_package`, `gate12_lint`, and `gate12_instrumentation` workflows
  expose the corresponding native Android runner actions;
- `xgo_gate_final_continue` composes those workflows through
  `target:xdm_android#...` references instead of shelling out to Gradle or
  reimplementing full instrumentation;
- the focused RM05 ADB precheck remains intentionally direct because it is a
  single-method device fail-fast probe, not a Gradle/build lifecycle; and
- a new `xgo_devtool_policy` target fails closed if executable XGO orchestration
  in `.devtool.toml`, `devtoolw`, or `tools/xgo/**` directly launches
  `gradle`/`gradlew` or declares the legacy `gradle_tasks` job shortcut.

The same policy is now the first node of both `xgo_gate_final_continue` and the
authoritative `xgo_gate_final`. Gate-12 policy data was updated to include that
node, while the ten-node `xgo_seal` workflow and its retained reports remain
unchanged. This preserves the expensive seal checkpoint instead of invalidating
it for an orchestration-only correction.

The policy scope is deliberately the XGO Devtool execution graph. Historical
Android project runbooks, documentation, and CI outside `.devtool.toml`,
`devtoolw`, and `tools/xgo/**` are not rewritten by this remediation. Within the
XGO graph, Gradle execution must enter through a native Devtool runner action;
seeing the project wrapper later in Devtool's own Android-runner log is expected
because Devtool itself ultimately launches Gradle.

`./devtoolw gate-final-continue` remains a repair-cycle continuation. A green
continuation requalifies the invalidated Android/desktop/final-wave boundary;
it does not replace a later from-scratch `./devtoolw gate-final` when full
Gate-12 closure is required.
