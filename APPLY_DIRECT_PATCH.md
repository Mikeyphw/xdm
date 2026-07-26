# Apply the XDM Android UIX R6 overlay

UIX R6 must be applied after `xdm_android_uix_r5_activity_settings_developer_boundary_overlay.zip`. The Devtool artifact validates source hashes and rolls back atomically if the final gate fails.

```bash
cd "$HOME/Code/xdm" && \
  devtool --copy --auto-hud --hud-mode desktop-window --yes \
  -r "$HOME/Code/xdm" \
  --target xdm_android \
  apply-overlay "$HOME/Downloads/xdm_android_uix_r6_accessibility_performance_release_seal_overlay.zip" \
  --validate
```

The artifact owns the commit message `Seal the XDM Android UI UX redesign`. Do not pass `--commit` on the command line.

The full target gate includes lintDebug, lintBeta, module and app unit tests, Android-test assembly, assembleDebug, and assembleBeta. A connected-device smoke pass can also be run from `app/XDM.Android` with `tools/run-uix-device-smoke.sh`.
