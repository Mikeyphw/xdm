from pathlib import Path
import importlib.util
import sys

ROOT = Path(__file__).resolve().parents[2]
BUILDER = ROOT / "tools/xgo/build_android_xdmcore.py"

spec = importlib.util.spec_from_file_location("build_android_xdmcore_rm04", BUILDER)
assert spec and spec.loader
mod = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = mod
spec.loader.exec_module(mod)


def test_real_jni_adapter_matches_kotlin_surface():
    kotlin = (ROOT / "app/XDM.Android/app/src/main/kotlin/com/mikeyphw/xdm/android/engine/AndroidGoEngineBridge.kt").read_text()
    adapter = (ROOT / "engine/bridge/cabi/jni_android.c").read_text()

    assert 'System.loadLibrary("xdmcore")' in kotlin
    assert "internal class NativeCallBuffer" in kotlin
    assert "private external fun nativeCreate(config: ByteArray, outHandle: LongArray): Int" in kotlin
    assert "private external fun nativeNextFrame(handle: Long, timeoutMillis: Int): NativeCallBuffer?" in kotlin
    assert "external fun releaseBuffer(token: Long)" in kotlin

    for symbol in mod.JNI_SYMBOLS:
        assert symbol in adapter

    assert "NewDirectByteBuffer" in adapter
    assert "xdm_buffer_free(buffer)" in adapter


def test_builder_requires_jni_exports():
    source = BUILDER.read_text()
    assert "required_jni_symbols" in source
    assert "_jni_include_dirs()" in source
    assert "missing required Android JNI entry points" in source
    assert len(mod.JNI_SYMBOLS) == 7


def test_jni_translation_unit_is_android_guarded():
    adapter = (ROOT / "engine/bridge/cabi/jni_android.c").read_text()
    assert adapter.startswith("#ifdef __ANDROID__")
    assert adapter.rstrip().endswith("#endif")

def test_jni_headers_are_system_includes_not_user_includes():
    source = BUILDER.read_text()
    execute = source[source.index("def execute"):source.index("def main")]
    assert '*(f"-isystem {path}" for path in jni_include_dirs)' in execute
    assert '*(f"-I{path}" for path in jni_include_dirs)' not in execute

    probe = source[source.index("def _probe_compiler"):source.index("def resolve_compiler")]
    assert "jni_include_dirs" not in probe

def test_android_runtime_log_dependency_is_explicit_and_verified():
    source = BUILDER.read_text()

    base_ldflags = source[source.index("def _base_ldflags"):source.index("def _probe_compiler")]
    assert '"-llog"' in base_ldflags

    verify = source[source.index("def verify_native_output"):source.index("def execute")]
    assert "REQUIRED_ANDROID_NEEDED_LIBRARIES" in verify
    assert "missing required Android runtime dependencies" in verify

    assert mod.REQUIRED_ANDROID_NEEDED_LIBRARIES == ["liblog.so"]

