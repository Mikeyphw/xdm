#!/usr/bin/env python3
"""XAR01 executable contract: build, signing and native-runtime provenance must fail closed."""
from __future__ import annotations

import json
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REPO = ROOT.parent.parent
errors: list[str] = []


def read(path: Path) -> str:
    if not path.is_file():
        errors.append(f"missing {path.relative_to(REPO) if path.is_relative_to(REPO) else path}")
        return ""
    return path.read_text(encoding="utf-8")


def require(condition: bool, message: str) -> None:
    if not condition:
        errors.append(message)


workflow = read(REPO / ".github/workflows/android.yml")
devtool = read(REPO / ".devtool.toml")
helper = read(REPO / "build-release-apk.sh")
root_gradle = read(ROOT / "build.gradle.kts")
app_gradle = read(ROOT / "app/build.gradle.kts")
ff_gradle = read(ROOT / "media-ffmpeg/build.gradle.kts")
aria_gradle = read(ROOT / "transfer-aria2/build.gradle.kts")
ff_install = read(ROOT / "tools/install-ffmpeg-runtime.py")
ff_verify = read(ROOT / "tools/verify-ffmpeg-runtime.py")
aria_install = read(ROOT / "tools/install-aria2-runtime.py")
aria_verify = read(ROOT / "tools/verify-aria2-runtime.py")
browser_gate = read(ROOT / "tools/run-browser-bridge-release-gate.sh")
release_gate = read(ROOT / "tools/run-bug-hunt-phase10-release-gate.sh")
bundletool = read(ROOT / "tools/ensure-bundletool.py")
phase10_validator = read(ROOT / "tools/validate-bug-hunt-phase10-release-upgrade-packaging.py")

# S01-01 / DS1-S01-03: exact NDK 29 provision and measured revision.
require("XDM_ANDROID_NDK_VERSION: '29.0.14206865'" in workflow, "CI does not pin NDK 29.0.14206865")
require(workflow.count('"ndk;${XDM_ANDROID_NDK_VERSION}"') >= 2, "both Android CI jobs must provision the pinned NDK")
require("read_ndk_revision" in ff_install and 'actual != version' in ff_install, "FFmpeg installer does not reject a wrong measured NDK revision")
require('"ndkRevision": measured_ndk["revision"]' in ff_install, "runtime lock does not record measured NDK revision")

# S01-02: native runtimes remain optional for ordinary source/debug compilation.
require("if (requireFfmpegRuntime.get())" in ff_gradle, "FFmpeg packaging is not guarded by the explicit runtime requirement")
require("if (requireAlignedAria2Runtime.get())" in aria_gradle, "aria2 packaging is not guarded by the explicit runtime requirement")
require('mustRunAfter(":media-ffmpeg:installPinnedFfmpegRuntime")' in app_gradle, "explicit FFmpeg APK verification ordering is missing")
require('dependsOn(":media-ffmpeg:installPinnedFfmpegRuntime")' not in app_gradle.split('tasks.matching { it.name == "assembleDebug" }',1)[-1].split('}',1)[0], "ordinary assembleDebug still forces FFmpeg source installation")

# S01-03: distributable aria2 bytes require an externally trusted digest.
for needle in ["XDM_ARIA2_ARCHIVE_SHA256", "--require-trusted-digest", "trustedAria2ArchiveSha256"]:
    require(needle in aria_gradle or needle in aria_install, f"aria2 trusted-digest contract missing {needle}")
require("expected_hash" in aria_install and "cached_download_path(manifest, cache_dir, expected_hash)" in aria_install, "aria2 cache identity is not bound to the trusted digest")
require("verifyAria2ReleaseRuntime" in aria_gradle and "Direct release packaging requires XDM_ARIA2_ARCHIVE_SHA256" in aria_gradle, "direct release packaging is not fail-closed on a trusted aria2 digest")
require('tasks.matching { it.name == "preReleaseBuild" }' in aria_gradle and "dependsOn(verifyAria2ReleaseRuntime)" in aria_gradle, "preReleaseBuild does not enforce strict aria2 provenance")

# S01-04: stable verifier reads the active root workflow.
require('File(repositoryRoot, ".github/workflows/android.yml")' in root_gradle, "stable toolchain verifier still targets the nested/inactive workflow")

# S01-05: browser artifact verification derives current versions.
require("extension_version()" in browser_gate and "app_version()" in browser_gate, "browser release gate does not derive current versions")
require("1.1.0" not in browser_gate, "browser release gate still hardcodes extension version 1.1.0")
require('--extension-version "$ext_version" --app-version "$app_ver"' in browser_gate, "browser artifact verifier is not passed current versions")

# S01-06: advertised helper is the canonical signed-release path.
require("run-bug-hunt-phase10-release-gate.sh" in helper, "release helper does not run the canonical release gate")
for env_name in ["XDM_RELEASE_SIGNER_SHA256", "XDM_ARIA2_ARCHIVE_SHA256"]:
    require(env_name in helper, f"release helper does not require {env_name}")
require("lintRelease testReleaseUnitTest assembleRelease" not in helper, "release helper still owns a weaker parallel release recipe")

# S01-07: FFmpeg 16 KiB evidence is part of the signed release gate and inventory.
for needle in ["install-ffmpeg-runtime.py --build-pinned", "verify-ffmpeg-runtime.py --require-payload --require-16kb-alignment", "-Pxdm.requireFfmpegRuntime=true", "xdm.validation.ffmpegPayloadVerified=true"]:
    require(needle in release_gate, f"signed release gate missing FFmpeg evidence step: {needle}")
require("verifyFfmpegReleaseRuntime" in ff_gradle and "dependsOn(verifyFfmpegReleaseRuntime)" in ff_gradle, "direct release packaging does not require prebuilt attested FFmpeg bytes")
inventory = json.loads(read(ROOT / "tools/phase10-release-inventory.json") or "{}")
for member in ["lib/arm64-v8a/libxdm_ffmpeg.so", "lib/arm64-v8a/libxdm_ffprobe.so"]:
    require(member in inventory.get("requiredApkEntries", []), f"release inventory missing {member}")

# S01-08: the target cleans before build and requires artifact verification.
require('external_verification = "required"' in devtool, "Devtool artifact verification is not required")
require(re.search(r'\[targets\.xdm_android\.tasks\][\s\S]*?build\s*=\s*\[\s*"clean",\s*"assembleDebug"', devtool) is not None, "Devtool standard Android build does not clean stale outputs before assembleDebug")

# S01-09: the XAR01 behavioral contract is wired to final validation and stale Room truth is derived.
require("validate-xar01-build-provenance.py" in read(ROOT / "tools/run-final-release-gate.sh"), "XAR01 validator is not wired to the final static gate")
require("db_match=re.search" in phase10_validator and "CurrentRoomSchemaVersion = {current_room_schema}" in phase10_validator, "Phase10 validator still hardcodes stale Room schema truth")

# S01-10: aria2 license/source notices are generated, attested and package-verified.
aria_manifest = json.loads(read(ROOT / "transfer-aria2/runtime/aria2-runtime.json") or "{}")
for key in ["licenseAsset", "sourceNoticeAsset"]:
    require(bool(aria_manifest.get(key)), f"aria2 runtime manifest missing {key}")
    if aria_manifest.get(key):
        require((ROOT / aria_manifest[key]).is_file(), f"aria2 notice asset missing: {aria_manifest[key]}")
require("sourceNoticeSha256" in aria_install and "assets/licenses/SOURCE-NOTICE.txt" in aria_verify, "aria2 notice is not attested and APK-verified")

# S01-11: workflow path filters include build/release authority outside app/XDM.Android.
for path in ["- '.devtool.toml'", "- 'build-release-apk.sh'"]:
    require(workflow.count(path) >= 2, f"Android workflow path filter missing {path}")

# S01-12: bundletool is self-provisioned at an immutable digest.
require('VERSION = "1.18.3"' in bundletool and re.search(r'SHA256 = "[0-9a-f]{64}"', bundletool), "bundletool provisioner is not version+digest pinned")
require("ensure-bundletool.py --print-path" in release_gate and "ensure-bundletool.py --print-path" in workflow, "release paths do not self-provision pinned bundletool")

# DS1-S01-01 / DS1-S01-05: signer pin is bound to real cert on every release packaging entry point.
for needle in ["KeyStore.getInstance", "getCertificate(alias)", 'MessageDigest.getInstance("SHA-256")', "require(actual == expected)", "release-signer-attestation.json"]:
    require(needle in app_gradle, f"release signer certificate binding missing {needle}")
for prefix in ['name.startsWith("assemble")', 'name.startsWith("bundle")', 'name.startsWith("package")', 'name.startsWith("sign")', 'name.startsWith("validateSigning")']:
    require(prefix in app_gradle, f"direct release packaging preflight coverage missing {prefix}")

# DS1-S01-02 / DS1-S01-04: source/cache/tool provenance is content-addressed.
for needle in ["tool_fingerprint", "select_toolchain_identity", '"toolchain": toolchain_identity', "archive_tree_matches", "provenance-verified deterministic cache", "buildCacheIdentitySha256"]:
    require(needle in ff_install, f"FFmpeg/OpenSSL provenance hardening missing {needle}")
require('"schemaVersion": 2' in ff_verify and "toolchainIdentity" in ff_verify, "FFmpeg verifier does not require v2 toolchain provenance")

# DS1-S01-06: generated license assets are declared Gradle outputs.
for asset in ["runtime/licenses/FFmpeg-LGPL-2.1.txt", "runtime/licenses/OpenSSL-Apache-2.0.txt"]:
    require(f'layout.projectDirectory.file("{asset}")' in ff_gradle, f"FFmpeg installer output declaration missing {asset}")

if not errors:
    result = subprocess.run([sys.executable, str(ROOT / "tools/test-xar01-build-provenance.py")], cwd=ROOT)
    if result.returncode != 0:
        errors.append("XAR01 focused behavioral regression suite failed")

if errors:
    print("XAR01 build/signing/native provenance validation failed:")
    for error in errors:
        print(f"- {error}")
    raise SystemExit(1)

print("XAR01 build/signing/native provenance validation passed (18/18 S01 canonical roots covered).")
