# XGO-RM03 Android Artifact Path Contract Report

## Trigger

After XGO-RM02 repaired Gradle generated-source ownership, the Android host
build completed successfully:

- `:app:assembleDebugAndroidTest` completed;
- `:app:assembleDebug` completed;
- `:app:verifyFfmpegDebugApkRuntime` completed;
- both the debug APK and AndroidTest APK existed.

Devtool then copied the artifacts into a duplicated path:

`app/XDM.Android/app/XDM.Android/app/build/outputs/apk/debug/...`

and failed artifact verification with `ARTIFACT_MISSING`.

## Root cause

`targets.xdm_android.root` is `app/XDM.Android`, while its artifact
`output_directory` and `paths` also began with `app/XDM.Android/...`.

Android artifact paths are resolved relative to the target root. Repeating the
target root therefore doubled the prefix and made Devtool verify/copy the wrong
location.

## Repair

The artifact contract is now target-root-relative:

- `app/build/outputs/apk/debug`
- `app/build/outputs/apk/debug/app-debug.apk`
- `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`

The external verification policy remains unchanged.

## Regression invariant

`tools/xgo/audit_topology.py` now rejects any non-root target whose artifact
`output_directory` or `paths` repeat that target's own root prefix. This makes
the duplicated-root mistake fail during topology validation before expensive
host qualification.

The topology unit tests cover both the rejected duplicated-root form and the
accepted target-relative form.

## Gate position

RM02 successfully cleared the Gradle packaging blocker. RM03 owns only Devtool
artifact path resolution/verification. Apply RM03 with `--no-validate`, then
rerun the authoritative `./devtoolw gate-final` workflow.
