# aria2 Android payload packaging

XDM executes aria2 only from `ApplicationInfo.nativeLibraryDir`. Never copy an executable into writable app storage.

Install the official 1.37.0 ARM64 Android release:

```bash
python3 tools/install-aria2-runtime.py --archive /path/to/aria2-1.37.0-aarch64-linux-android-build1.zip --expected-archive-sha256 <trusted-sha256>
python3 tools/verify-aria2-runtime.py --require-payload
```

The installer writes `src/main/jniLibs/arm64-v8a/libaria2c.so` and `runtime/aria2-runtime.lock.json`. Both are verified before a distribution build when `-Pxdm.requireAria2Runtime=true` is supplied. After packaging, verify the exact APK entry with:

```bash
python3 tools/verify-aria2-runtime.py --require-payload --apk app/build/outputs/apk/beta/app-beta.apk
```

The payload remains GPL-2.0-or-later and must be distributed with corresponding license/source notices.
