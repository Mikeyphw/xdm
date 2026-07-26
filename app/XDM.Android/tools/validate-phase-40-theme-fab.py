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


tokens = text("browser-extension/src/main/kotlin/com/mikeyphw/xdm/android/browserextension/XdmThemeTokens.kt")
css_generator = text("browser-extension/src/main/kotlin/com/mikeyphw/xdm/android/browserextension/XdmThemeCssGenerator.kt")
package_generator = text("browser-extension/src/main/kotlin/com/mikeyphw/xdm/android/browserextension/BrowserExtensionPackageGenerator.kt")
source_contract = text("browser-extension/src/main/kotlin/com/mikeyphw/xdm/android/browserextension/BrowserExtensionSourceContract.kt")
compose_theme = text("app/src/main/kotlin/com/mikeyphw/xdm/android/XdmTheme.kt")
models = text("app/src/main/kotlin/com/mikeyphw/xdm/android/BrowserExtensionExportModels.kt")
preferences = text("app/src/main/kotlin/com/mikeyphw/xdm/android/UserPreferencesStore.kt")
settings = text("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/settings/BrowserExtensionSettingsScreen.kt")
fab = text("browser-extension/src/main/extension/xdm-firefox/fab.js")
frame = text("browser-extension/src/main/extension/xdm-firefox/frame-bridge.js")
background = text("browser-extension/src/main/extension/xdm-firefox/network-observer.js")
theme_template = text("browser-extension/src/main/extension/xdm-firefox/generated-theme.template.css")
config_template = text("browser-extension/src/main/extension/xdm-firefox/generated-config.template.js")
extension_css = text("browser-extension/src/main/extension/xdm-firefox/extension.css")
build = text("browser-extension/build.gradle.kts")
workflow = text(".github/workflows/android.yml")
root_workflow = text("../../.github/workflows/android.yml")
routes = text("app/src/main/kotlin/com/mikeyphw/xdm/android/AppRoute.kt")

for token in (
    "object XdmThemeTokenCatalog", "val Dark", "val Amoled", "fabSizePx = 56",
    "fabCornerRadiusPx = 18", "motionStandardMs = 220", "0xFF090B0F", "0xFF000000",
):
    require(tokens, token, "shared theme token catalog")
for token in ("XdmThemeTokenCatalog.forMode", "@@BACKGROUND@@", "@@FAB_SIZE@@", "@@MOTION_STANDARD@@"):
    require(css_generator, token, "theme CSS generator")
require(compose_theme, "XdmThemeTokenCatalog", "Compose theme")
require(compose_theme, "Color(tokens.background)", "Compose theme")
forbid(compose_theme, "private val XdmBackground", "Compose duplicate palette")
require(package_generator, "XdmThemeCssGenerator.render", "shared XPI generator")
forbid(package_generator, '"@@BACKGROUND@@" to', "package-local color map")

for token in ('FollowApp("follow-app"', "fun resolve(appTheme"):
    require(source_contract, token, "theme selection contract")
for token in ("ThemeSelection.FollowApp", "resolvedTheme", "isThemeStale"):
    require(models, token, "export theme state")
require(preferences, "ThemeSelection.entries", "persisted theme migration")
for token in ("Follow app", "Regeneration needed", "Regenerate XPI", "preferences.resolvedTheme(state.themeMode)"):
    require(settings, token, "browser extension settings")

for token in (
    "attachShadow({ mode: \"open\" })", "__xdm_media_fab_host", "env(safe-area-inset-bottom)",
    "env(safe-area-inset-right)", "prefers-reduced-motion", "aria-haspopup", "fullscreenchange",
    "candidateCount", "streamKind", "XdmLauncherUiV2", "fabSizePx: 56",
):
    require(fab, token, "Shadow DOM FAB")
for token in ("setInterval", "querySelectorAll(\"*\")", "max-width:390px", "xdm-media-card"):
    forbid(fab, token, "FAB polling/card regression")
require(frame, "XdmLauncherUiV1.update", "frame bridge FAB refresh")
require(background, "candidateCount: candidateStore.size(tabId)", "tab candidate count")
require(background, "candidateStreamKind", "stream-kind aggregation")

for token in (
    "--xdm-fab-size", "--xdm-fab-radius", "--xdm-motion-standard", "--xdm-primary-container",
    "--xdm-on-primary-container", "--xdm-separator",
):
    require(theme_template, token, "generated theme template")
for token in ("contractVersion: 1", "theme: Object.freeze", "@@FAB_SIZE@@", "@@MOTION_STANDARD@@"):
    require(config_template, token, "generated config theme contract")
for token in ("var(--xdm-bg)", "var(--xdm-primary-container)", "prefers-reduced-motion"):
    require(extension_css, token, "popup shared theme")

for token in ("test_fab.js", "1.1.0"):
    require(build, token, "browser-extension Gradle tasks")
for source, label in ((workflow, "Android workflow"), (root_workflow, "root Android workflow")):
    require(source, "validate-phase-40-theme-fab.py", label)
    require(source, ":browser-extension:packageFirefoxExtensionDark", label)
    require(source, ":browser-extension:packageFirefoxExtensionAmoled", label)

for path in (
    "browser-extension/tests/test_fab.js",
    "browser-extension/src/test/kotlin/com/mikeyphw/xdm/android/browserextension/XdmThemeTokensTest.kt",
    "app/src/test/kotlin/com/mikeyphw/xdm/android/BrowserExtensionPhase40ContractTest.kt",
    "docs/architecture/PHASE-40-SHARED-THEME-FAB.md",
):
    if not (ROOT / path).is_file():
        ERRORS.append(f"Missing Phase 40 contract asset: {path}")

forbid(routes, "BrowserExtension", "top-level route contract")

manifest_path = ROOT / "PROJECT_MANIFEST.json"
try:
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
except Exception as exc:
    ERRORS.append(f"PROJECT_MANIFEST parse failed: {exc}")
    manifest = {}
phase = manifest.get("browser_bridge_phase40_extension_theme_fab", {})
if phase.get("program") != "android_browser_bridge": ERRORS.append("Phase 40 project manifest entry missing")
if phase.get("compose_and_xpi_single_source") is not True: ERRORS.append("Phase 40 must share Compose and XPI tokens")
if phase.get("fab_size_px") != 56: ERRORS.append("Phase 40 FAB must remain 56 px")
if phase.get("whole_document_polling") is not False: ERRORS.append("Phase 40 must ban whole-document polling")
if phase.get("custom_scheme_payload_expanded") is not False: ERRORS.append("Phase 40 must not expand deep-link credentials")
if phase.get("top_level_route_added") is not False: ERRORS.append("Phase 40 must preserve top-level routes")
if phase.get("room_schema_unchanged") != 14: ERRORS.append("Phase 40 must preserve Room schema 14")

if ERRORS:
    print("Phase 40 shared theme/FAB validation failed:")
    for error in ERRORS:
        print(f"- {error}")
    raise SystemExit(1)
print("Phase 40 shared theme/FAB validation passed.")
