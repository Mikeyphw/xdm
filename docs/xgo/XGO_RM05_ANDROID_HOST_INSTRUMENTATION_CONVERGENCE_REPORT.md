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
