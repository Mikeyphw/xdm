#!/usr/bin/env python3
from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ERRORS: list[str] = []


def text(relative: str) -> str:
    path = ROOT / relative
    if not path.is_file():
        ERRORS.append(f"Missing required file: {relative}")
        return ""
    return path.read_text(encoding="utf-8", errors="replace")


def require(source: str, token: str, label: str) -> None:
    if token not in source:
        ERRORS.append(f"{label}: missing {token}")


def forbid(source: str, token: str, label: str) -> None:
    if token in source:
        ERRORS.append(f"{label}: forbidden {token}")

build = text("browser-extension/build.gradle.kts")
contract = text("browser-extension/src/main/kotlin/com/mikeyphw/xdm/android/browserextension/BrowserExtensionSourceContract.kt")
generator = text("browser-extension/src/main/kotlin/com/mikeyphw/xdm/android/browserextension/BrowserExtensionPackageGenerator.kt")
validator = text("browser-extension/src/main/kotlin/com/mikeyphw/xdm/android/browserextension/BrowserExtensionPackageValidator.kt")
config = text("browser-extension/src/main/kotlin/com/mikeyphw/xdm/android/browserextension/BrowserExtensionBuildConfig.kt")
manager = text("app/src/main/kotlin/com/mikeyphw/xdm/android/BrowserExtensionExportManager.kt")
transaction = text("app/src/main/kotlin/com/mikeyphw/xdm/android/BrowserExtensionExportTransaction.kt")
preferences = text("app/src/main/kotlin/com/mikeyphw/xdm/android/UserPreferencesStore.kt")
settings_panel = text("app/src/main/kotlin/com/mikeyphw/xdm/android/SettingsPanel.kt")
settings_screen = text("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/settings/SettingsScreen.kt")
extension_screen = text("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/settings/BrowserExtensionSettingsScreen.kt")
app_build = text("app/build.gradle.kts")
routes = text("app/src/main/kotlin/com/mikeyphw/xdm/android/AppRoute.kt")
workflow = text(".github/workflows/android.yml")
root_workflow = text("../../.github/workflows/android.yml")

for token in (
    "BrowserExtensionBuildConfig", "BrowserExtensionPackageGenerator", "BrowserExtensionPackageValidator",
    "BrowserExtensionExportResult", "BrowserExtensionHash",
):
    required_file = ROOT / "browser-extension/src/main/kotlin/com/mikeyphw/xdm/android/browserextension" / f"{token}.kt"
    if not required_file.is_file(): ERRORS.append(f"Missing shared packager type: {token}")

for token in (
    'resources.srcDir("src/main/extension")', "packageFirefoxExtension", "packageFirefoxExtensionDark",
    "packageFirefoxExtensionAmoled", 'outputs/xpi',
):
    require(build, token, "browser-extension Gradle packaging")
require(app_build, 'implementation(project(":browser-extension"))', "Android runtime packager dependency")
require(app_build, 'tasks.register("checkBrowserIntegration")', "aggregate browser integration task")

for token in (
    "toSortedMap()", "ZipEntry.STORED", "DeterministicZipEpochMillis", "LocalDateTime.of(1980, 1, 1, 0, 0)",
    "ZoneId.systemDefault()", "CRC32", "BrowserExtensionPackageValidator.validate",
):
    require(generator, token, "deterministic generator")
for token in ("Unsafe archive entry", "Duplicate archive entry", "Archive entries are not sorted", "Missing archive entry"):
    require(validator, token, "package validator")
generator_tests = text("browser-extension/src/test/kotlin/com/mikeyphw/xdm/android/browserextension/BrowserExtensionPackageGeneratorTest.kt")
for token in ("America/Sao_Paulo", "Asia/Tokyo", "byte-identical across device time zones"):
    require(generator_tests, token, "cross-timezone reproducibility tests")
require(config, "XDM-Android-Firefox-$extensionVersion-${channel.wireValue}-${themeMode.fileToken}.xpi", "deterministic filename")

for token in (
    "browser_extension_export_tree_uri", "browser_extension_default_target", "browser_extension_last_export_theme",
    "browser_extension_last_export_app_version", "browser_extension_last_export_extension_version",
    "browser_extension_last_export_sha256", "browser_extension_last_export_epoch_ms",
):
    require(preferences, token, "persisted browser extension export metadata")

for token in (
    "takePersistableUriPermission", "File.createTempFile", "BrowserExtensionExportTransaction", "BrowserExtensionHash::digest",
    "Exported XPI byte-count mismatch", "Exported XPI checksum mismatch",
):
    require(manager, token, "SAF export manager")
for token in (".part", ".backup", "writeAndVerify", "snapshot", "renameSupported"):
    if token == "renameSupported":
        require(text("app/src/test/kotlin/com/mikeyphw/xdm/android/BrowserExtensionExportTransactionTest.kt"), token, "SAF fallback tests")
    else:
        require(transaction, token, "SAF export transaction")

require(settings_panel, 'BrowserExtension("Browser extension")', "Settings subpanel")
require(settings_screen, "SettingsPanel.BrowserExtension", "Settings navigation")
require(extension_screen, "ActivityResultContracts.OpenDocumentTree()", "SAF folder picker")
require(extension_screen, "Generate XPI", "generation action")
forbid(routes, "BrowserExtension", "top-level route contract")

for required in (
    "manifest.template.json", "generated-config.template.js", "generated-theme.template.css",
):
    require(contract, f'"{required}"', "extension package inventory")
if list((ROOT / "browser-extension/src").rglob("*.xpi")):
    ERRORS.append("Generated XPI committed under browser-extension/src")

for source, label in ((workflow, "Android workflow"), (root_workflow, "root Android workflow")):
    require(source, "validate-phase-39-xpi-export.py", label)
    require(source, ":browser-extension:packageFirefoxExtension", label)

manifest_path = ROOT / "PROJECT_MANIFEST.json"
try:
    project_manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
except Exception as exc:
    ERRORS.append(f"PROJECT_MANIFEST parse failed: {exc}")
    project_manifest = {}
phase = project_manifest.get("browser_bridge_phase39_xpi_generation_export", {})
if phase.get("program") != "android_browser_bridge": ERRORS.append("Phase 39 project manifest entry missing")
if phase.get("top_level_route_added") is not False: ERRORS.append("Phase 39 must preserve top-level routes")
if phase.get("room_schema_unchanged") != 14: ERRORS.append("Phase 39 must preserve Room schema 14")

if ERRORS:
    print("Phase 39 XPI export validation failed:")
    for error in ERRORS: print(f"- {error}")
    raise SystemExit(1)
print("Phase 39 XPI export validation passed.")
