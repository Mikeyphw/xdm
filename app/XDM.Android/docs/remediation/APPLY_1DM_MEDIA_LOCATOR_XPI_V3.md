# Apply — 1DM media locator parity + XPI v3

From Termux:

```bash
devtool --yes --copy \
  -r ~/Code/xdm \
  --target xdm_android \
  apply overlay --no-validate \
  ~/Downloads/xdm_ov_1dm_media_locator_xpi_v3_r2.zip
```

Then validate with each Gradle task passed through its own `--task` flag:

```bash
devtool -r ~/Code/xdm validate \
  --task :browser-extension:test \
  --task :browser-extension:jsTest \
  --task :browser-extension:validateFirefoxExtension \
  --task :browser-integration:test \
  --task :media:test \
  --task :app:testDebugUnitTest \
  --task :app:lintDebug
```

This overlay changes the browser capture contract from encrypted per-install v2 generation to direct keyless v3 generation. Install the new v3 XPI once after applying. Normal Android app rebuilds/reinstalls no longer require XPI regeneration solely because AndroidKeyStore material changed.


R2 adds a promise-closure validator to the current static final gate. Before the Gradle/device lanes, you can run:

```bash
cd ~/Code/xdm/app/XDM.Android
python3 tools/validate-1dm-media-locator-xpi-v3.py
bash tools/run-final-release-gate.sh --ci
```

The original `xdm_ov_1dm_media_locator_xpi_v3.zip` is superseded by the R2 overlay.
