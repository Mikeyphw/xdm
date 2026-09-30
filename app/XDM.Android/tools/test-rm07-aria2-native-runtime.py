#!/usr/bin/env python3
"""Focused XGO-RM07 regressions for aria2 source provenance and ELF hardening."""
from __future__ import annotations

import json
import struct
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
from android_elf_runtime import (  # noqa: E402
    DF_BIND_NOW, DT_FLAGS, DT_NEEDED, DT_NULL, DT_STRTAB, DT_STRSZ,
    ET_DYN, PF_X, PT_DYNAMIC, PT_GNU_RELRO, PT_GNU_STACK, PT_INTERP, PT_LOAD,
    ElfPolicyError, validate_android_runtime_elf,
)

MANIFEST = json.loads((ROOT / "transfer-aria2/runtime/aria2-runtime.json").read_text())


def ph64(ptype: int, flags: int, offset: int, vaddr: int, filesz: int, memsz: int, align: int) -> bytes:
    return struct.pack("<IIQQQQQQ", ptype, flags, offset, vaddr, 0, filesz, memsz, align)


def synthetic_elf(*, align=16384, elf_type=ET_DYN, relro=True, bind_now=True, exec_stack=False, interpreter="/system/bin/linker64") -> bytes:
    phnum = 5
    size = 0x1000
    data = bytearray(size)
    data[:16] = b"\x7fELF" + bytes([2, 1, 1, 0]) + bytes(8)
    struct.pack_into("<HHIQQQIHHHHHH", data, 16, elf_type, 183, 1, 0, 64, 0, 0, 64, 56, phnum, 0, 0, 0)
    interp = interpreter.encode() + b"\0"
    interp_off, dyn_off, str_off = 0x300, 0x400, 0x500
    data[interp_off:interp_off+len(interp)] = interp
    strings = b"libc.so\0libdl.so\0"
    data[str_off:str_off+len(strings)] = strings
    entries = [(DT_STRTAB, str_off), (DT_STRSZ, len(strings)), (DT_NEEDED, 0), (DT_NEEDED, len(b"libc.so\0"))]
    if bind_now:
        entries.append((DT_FLAGS, DF_BIND_NOW))
    entries.append((DT_NULL, 0))
    cursor = dyn_off
    for tag, value in entries:
        struct.pack_into("<QQ", data, cursor, tag, value); cursor += 16
    headers = [
        ph64(PT_LOAD, 5, 0, 0, size, size, align),
        ph64(PT_INTERP, 4, interp_off, interp_off, len(interp), len(interp), 1),
        ph64(PT_DYNAMIC, 6, dyn_off, dyn_off, cursor-dyn_off, cursor-dyn_off, 8),
        ph64(PT_GNU_STACK, 7 if exec_stack else 6, 0, 0, 0, 0, 16),
        ph64(PT_GNU_RELRO if relro else 0, 4, 0x600, 0x600, 0x100, 0x100, 1),
    ]
    for index, ph in enumerate(headers):
        start = 64 + index * 56
        data[start:start+56] = ph
    return bytes(data)


def strict(data: bytes) -> None:
    validate_android_runtime_elf(
        data,
        required_load_alignment=16384,
        require_pie=True,
        require_gnu_relro=True,
        require_bind_now=True,
        require_non_executable_stack=True,
        expected_interpreter="/system/bin/linker64",
    )


def reject(data: bytes, expected: str) -> None:
    try:
        strict(data)
    except ElfPolicyError as error:
        if expected.lower() not in str(error).lower():
            raise AssertionError(f"expected {expected!r} rejection, got {error}") from error
    else:
        raise AssertionError(f"expected rejection containing {expected!r}")


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def main() -> None:
    strict(synthetic_elf())
    reject(synthetic_elf(align=4096), "16 KiB")
    reject(synthetic_elf(elf_type=2), "PIE")
    reject(synthetic_elf(relro=False), "GNU_RELRO")
    reject(synthetic_elf(bind_now=False), "BIND_NOW")
    reject(synthetic_elf(exec_stack=True), "non-executable")
    reject(synthetic_elf(interpreter="/lib/ld-linux-aarch64.so.1"), "non-Android")

    require(MANIFEST["schemaVersion"] == 3, "aria2 runtime manifest is not schema v3")
    require(MANIFEST["ndkVersion"] == "29.0.14206865", "aria2 source build does not pin the campaign NDK")
    require(MANIFEST["requiredLoadAlignment"] == 16384, "aria2 manifest lost 16 KiB contract")
    require(MANIFEST["compatibilityPrebuilt"]["releaseEligible"] is False, "legacy prebuilt became release eligible")
    for name in ("aria2", "openssl", "cares", "expat", "libssh2", "zlib"):
        source = MANIFEST["sources"][name]
        require(source["url"].startswith("https://"), f"{name} source URL is not HTTPS")
        require(len(source["sha256"]) == 64, f"{name} source digest is not pinned")

    gradle = (ROOT / "transfer-aria2/build.gradle.kts").read_text()
    require("buildPinnedAria2Runtime" in gradle and '"--build-pinned"' in gradle, "Gradle strict path does not own the source build")
    require('"--require-source-build"' in gradle, "Gradle release verifier does not require source provenance")
    require("dependsOn(buildPinnedAria2Runtime)" in gradle, "strict JNI packaging is not ordered after source build")
    require("dependsOn(installCompatibilityAria2Runtime)" not in gradle, "release graph depends on compatibility prebuilt")
    verify_block = gradle.split('val verifyAria2Runtime = tasks.register<Exec>("verifyAria2Runtime")', 1)[1].split('val verifyAria2ReleaseRuntime', 1)[0]
    require("doFirst" not in verify_block and "doLast" not in verify_block, "verifyAria2Runtime is not configuration-cache safe")
    require("commandLine(verificationCommand)" in verify_block, "verifyAria2Runtime command is not configured during task configuration")
    require("verifyAria2Runtime.success" in verify_block and '"--success-marker"' in verify_block, "verifyAria2Runtime lost incremental success-marker ownership")

    architecture_contract = (ROOT / "app/src/test/kotlin/com/mikeyphw/xdm/android/ArchitectureContractTest.kt").read_text()
    elf_policy = (ROOT / "tools/android_elf_runtime.py").read_text()
    require('verifier.contains("verify_elf_policy")' in architecture_contract, "app architecture contract still expects verifier-local ELF parsing")
    require('elfPolicy.contains("PT_GNU_RELRO")' in architecture_contract, "app architecture contract does not follow shared ELF policy ownership")
    require("PT_LOAD" in elf_policy and "PT_GNU_RELRO" in elf_policy and "required_load_alignment" in elf_policy, "shared ELF policy lost alignment/RELRO ownership")

    builder = (ROOT / "tools/build-aria2-runtime.py").read_text()
    verifier = (ROOT / "tools/verify-aria2-runtime.py").read_text()
    shared_openssl = (ROOT / "tools/android_native_openssl.py").read_text()
    require("toolchainAuthoritySha256" in builder and "buildCacheIdentitySha256" in builder, "builder does not attest toolchain/cache authority")
    require("build_or_reuse_shared_openssl" in builder and "sharedOpenSslAuthoritySha256" in builder and '"sharedOpenSsl": shared_openssl' in builder,
            "aria2 builder does not consume/attest the shared OpenSSL dependency")
    require("xdm-android-shared-openssl" in shared_openssl and "XDM_ANDROID_NATIVE_DEPS_CACHE" in shared_openssl,
            "shared OpenSSL authority is missing consumer-neutral cache identity")
    require("resuming compatible in-progress" in shared_openssl and "adopted completed" in shared_openssl,
            "shared OpenSSL cache cannot adopt/resume pre-v4 native work")
    require('"sourceKind": "source-build"' in builder, "builder does not attest source-build identity")
    require("-static-libstdc++" in builder and "-z,relro,-z,now" in builder, "builder lost static C++ runtime / RELRO+NOW linkage")
    require('run(["make", f"-j{jobs}"], aria, aria_env, label="aria2 build")' in builder,
            "aria2 builder must invoke the upstream top-level default make target")
    require('run(["make", f"-j{jobs}", "aria2c"]' not in builder,
            "aria2 builder must not request nonexistent top-level make target aria2c")
    require('lock.get("schemaVersion") != 3' in verifier, "verifier does not fail closed on schema-v3 source provenance")
    require("buildRecipeSha256" in verifier and "toolchainAuthoritySha256" in verifier, "verifier does not bind recipe/toolchain authority")
    require("sharedOpenSslAuthoritySha256" in verifier and "dependencyIdentitySha256" in verifier,
            "aria2 verifier does not fail closed on shared OpenSSL provenance")

    release_gate = (ROOT / "tools/run-bug-hunt-phase10-release-gate.sh").read_text()
    workflow = (ROOT.parent.parent / ".github/workflows/android.yml").read_text()
    require("build-aria2-runtime.py --build-pinned" in release_gate, "signed release gate does not build pinned aria2 sources")
    require("--require-source-build" in release_gate, "signed release gate does not verify source-built aria2")
    require("XDM_ARIA2_ARCHIVE_SHA256" not in release_gate, "signed release still trusts external aria2 binary digest")
    require("Build pinned ARM64 aria2 runtime from source" in workflow, "CI does not build aria2 from source")
    require("XDM_ARIA2_ARCHIVE_SHA256" not in workflow, "CI still consumes aria2 binary digest secret")

    result = subprocess.run([sys.executable, str(ROOT / "tools/build-aria2-runtime.py"), "--plan"], cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    require(result.returncode == 0 and '"buildProfile": "android-arm64-source-v1"' in result.stdout, "source builder plan is not executable")
    print("XGO-RM07 aria2 native runtime/provenance regressions passed (source pins + ELF hardening + release wiring).")


if __name__ == "__main__":
    main()
