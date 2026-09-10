# XDM Android intermediate overlay compile repair hotfix v2

This prerequisite hotfix repairs the compile and contract-test regressions exposed by full Gradle validation after the 2026-09-10 OBS/THUMB-MIME/WEB-NAME intermediate overlays.

## Repairs

- Escapes the Live Locator main-frame error newline so `MediaLocatorActivity.kt` compiles.
- Routes thumbnail loader trace failures through the existing `DebugArea.Thumbnail` enum.
- Replaces the two OBS/THUMB contract tests' cwd-relative `app/XDM.Android` fallback with upward XDM.Android root discovery, preventing `app/app/XDM.Android` paths under Gradle's app-module working directory.
- Makes `user.dir` handling null-safe in those tests.
- Uses the AutoMirrored QueueMusic icon to remove the Compose deprecation warning exposed by validation.
- Adds a regression contract covering all of the above.

## Compatibility

The hotfix deliberately avoids every path in `xdm_android_list_quick_actions_final_seal_v1.zip`, so that final overlay can be reused unchanged after this prerequisite succeeds.

Room schema is unchanged. No runtime data migration is introduced.
