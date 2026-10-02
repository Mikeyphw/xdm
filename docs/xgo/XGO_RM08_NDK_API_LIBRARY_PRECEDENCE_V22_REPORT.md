# XGO RM08 NDK API-library precedence v22

## Finding

RM08 v21's effective linker trace proved that the Android driver was configured for a dynamic PIE, yet the probe contained `getaddrinfo`/`freeaddrinfo` definitions and had no `DT_NEEDED` entries. Device-side inspection showed the NDK's version-generic `usr/lib/aarch64-linux-android` directory contains `libc.a`, while the API-specific `usr/lib/aarch64-linux-android/26` directory contains the `libc.so` system stub.

v21 explicitly prepended the version-generic directory with `-L` to help native-Termux C++ linkage. That changed normal Clang search precedence: `-lc` could resolve the generic static archive before the API-specific shared stub. The resulting executable retained `/system/bin/linker64` and PIE metadata but embedded Bionic libc implementation objects, producing `DT_NEEDED=[]` and a native startup crash.

## Remediation

v22 removes that explicit generic NDK `-L` from aria2/probe `LDFLAGS`. The NDK runtime directory is still inspected to fail closed if required libc++ assets are absent, but Clang owns the actual target library ordering. `-static-libstdc++` remains the supported static-libc++ selector, and the dynamic-Bionic probe/final qualification still requires `DT_NEEDED libc.so` and rejects `libc++_shared.so`.

The aria2 recipe marker is bumped to schema 6 so no schema-5 native artifact can be reused.
