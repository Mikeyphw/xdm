# XGO-RM04 Android Real JNI Adapter

The first physical Gate-12 instrumentation run crashed before the first test
because `JniNativeXdmCore.nativeCreate(byte[])` had no JNI implementation.

`libxdmcore.so` was present and loadable, but exported only the stable XGO C
ABI. RM04 adds an Android-only C translation unit to the existing cgo
`c-shared` package so the same `libxdmcore.so` also exports the seven ART JNI
entry points required by `JniNativeXdmCore`.

The adapter forwards create/command/next-frame/platform-reply/metadata/shutdown
to the canonical `xdm_engine_*` ABI. Native frame/metadata buffers remain
zero-copy direct `ByteBuffer`s and retain the existing token-based
`NativeBufferLease` release path through `xdm_buffer_free`.

The Android builder now resolves JNI headers and verifies the JNI symbols are
actually exported. The XGO Android audit also checks the real adapter source,
closing the gap where the former model-only JNI audit could pass without a
runtime JNI implementation.

Because the native library changes, the previously built APK is invalidated.
Keep the earlier dependency/unit/XGO-seal checkpoint, but rebuild the Android
package boundary before lint and physical instrumentation.

## RM04 v2 builder correction

RM04 v1 accidentally attached the JNI include flags to `_probe_compiler()`
instead of the real Android build environment. That made the compiler probe
reference `jni_include_dirs` before it existed and left `execute()` without
the JNI include paths.

RM04 v2 keeps the generic compiler probe independent of JNI headers and applies
the resolved JNI include directories only to the actual Android cgo build.
No JNI ABI or Kotlin bridge semantics changed.

## RM04 v3 NDK header classification correction

RM04 v2 placed the JNI include directories in the correct real-build scope,
but used `-I` for the Android NDK sysroot include directory.

That changes NDK headers from compiler system headers into ordinary user
headers. Go/cgo compiles parts of `runtime/cgo` with strict warnings promoted
to errors, so Clang surfaced the NDK's C99 declaration ordering as:

`-Werror,-Wdeclaration-after-statement`

RM04 v3 keeps JNI header discovery explicit but emits each JNI include
directory with `-isystem`. This preserves the NDK's system-header semantics and
warning suppression while still making `jni.h` available to the Android JNI
translation unit.

The compiler probe remains JNI-independent. No Java/native ABI, buffer
ownership, or XGO engine behavior changes.

