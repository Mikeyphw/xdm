#!/usr/bin/env python3
from pathlib import Path
import json
root = Path(__file__).resolve().parents[1]
errors = []
def text(path):
    p = root / path
    if not p.is_file(): errors.append(f"missing file: {path}"); return ""
    return p.read_text(encoding="utf-8")
def require(path, needle):
    if needle not in text(path): errors.append(f"missing {needle!r} in {path}")
manifest = json.loads(text("PROJECT_MANIFEST.json") or "{}")
project = manifest.get("project", {})
parity = manifest.get("desktop_parity", {})
if project.get("version") != "0.18.0-rc01": errors.append("project.version must be 0.18.0-rc01")
if 18 not in project.get("implemented_phases", []): errors.append("implemented_phases must include post-17 parity marker 18")
if manifest.get("database", {}).get("version") != 14: errors.append("desktop parity must keep Room schema v14")
for key in ["settings_import_export", "history_file_management", "proxy_credentials_ui", "conversion_post_processing", "protocol_expansion_polish", "release_non_debug_packaging", "no_secrets_in_settings_export"]:
    if parity.get(key) is not True: errors.append(f"desktop_parity.{key} is not true")
if parity.get("top_level_route_added") is not False: errors.append("desktop parity must not add a top-level route")
build = text("app/build.gradle.kts")
if 'versionName = "0.18.0-rc01"' not in build or 'versionCode = 19' not in build: errors.append("build metadata must be 0.18.0-rc01 / versionCode 19")
for path, needle in [
    ("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/DesktopParityModels.kt", "SettingsExchangeCodec"),
    ("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/DesktopParityModels.kt", "HistoryManagementPolicy"),
    ("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/DesktopParityModels.kt", "ProtocolExpansionPolish"),
    ("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/DesktopParityModels.kt", "ReleasePackagingGate"),
    ("core-model/src/test/kotlin/com/mikeyphw/xdm/android/model/DesktopParityModelsTest.kt", "settingsSnapshotRoundTripsWithoutSecrets"),
    ("app/src/main/kotlin/com/mikeyphw/xdm/android/UserPreferencesStore.kt", "importSnapshot"),
    ("app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt", "importSettingsSnapshot"),
    ("app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt", "removeDownloadFromHistory"),
    ("app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt", "Settings import/export"),
    ("app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt", "History management"),
    ("app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt", "Proxy and credentials"),
    ("app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt", "Conversion and post-processing"),
    ("app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt", "Protocol expansion"),
    ("app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt", "Update compatibility"),
    ("tools/build-release-artifacts.sh", "assembleRelease"),
    ("tools/build-release-artifacts.sh", "sha256sum"),
    ("docs/architecture/POST-17-DESKTOP-PARITY.md", "No new top-level route"),
    ("app/src/test/kotlin/com/mikeyphw/xdm/android/ArchitectureContractTest.kt", "postSeventeenDesktopParityContractsArePresent"),
]: require(path, needle)
routes = text("app/src/main/kotlin/com/mikeyphw/xdm/android/AppRoute.kt")
for forbidden in ['History("History"', 'Proxy("Proxy"', 'Convert("Convert"', 'Packaging("Packaging"']:
    if forbidden in routes: errors.append("desktop parity added a forbidden top-level route")
for rel in ["core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/DesktopParityModels.kt", "app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt", "app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt"]:
    data = (root / rel).read_bytes()
    if [b for b in data if b < 9 or (13 < b < 32)]: errors.append(f"control characters found in {rel}")
if errors: raise SystemExit("\n".join(errors))
print("Post-17 desktop parity validation passed")
