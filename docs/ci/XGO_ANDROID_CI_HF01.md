# XGO Android SDK CI repair (non-release)

Baseline xgo source: 90d3c8c00695ce4b5f3924c40a196aa635be4aa8. Immutable Android setup action:
android-actions/setup-android@be39fa834029ff78f1a44aa3bb0819b8fc2bd8fd (v4.0.4).

Fixes hosted error "Failed to find package tools" from setup-android@v3.
SDK packages still installed explicitly afterward: platforms;android-36,
build-tools;36.0.0, ndk;29.0.14206865.

Manual workflow dispatch must select a tag ref beginning refs/tags/android-v
AND input signed_release=true to enter the signed-release job.
Normal pushes/PRs and manual validation dispatch stay unsigned.

Source only: no actual Android build, emulator, release, signature or device
acceptance has been obtained for this patch. Preserve the Go and Devtool
integration paths for a separate qualification wave.
