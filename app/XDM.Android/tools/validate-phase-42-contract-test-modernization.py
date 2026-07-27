#!/usr/bin/env python3
"""Guard Phase 42 contract tests against stale pre-modular source assumptions."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TEST_ROOT = ROOT / "app/src/test/kotlin/com/mikeyphw/xdm/android"


def read(name: str) -> str:
    path = TEST_ROOT / name
    if not path.is_file():
        raise SystemExit(f"Missing contract test: {path.relative_to(ROOT)}")
    return path.read_text(encoding="utf-8")


def require(text: str, needle: str, label: str) -> None:
    if needle not in text:
        raise SystemExit(f"{label} missing {needle!r}")


def reject(text: str, needle: str, label: str) -> None:
    if needle in text:
        raise SystemExit(f"{label} still contains stale contract {needle!r}")

architecture = read("ArchitectureContractTest.kt")
phase41 = read("BrowserExtensionPhase41ContractTest.kt")
removal = read("BrowserRemovalPhase6ContractTest.kt")
activity = read("DownloaderExperiencePhase8EContractTest.kt")
uix2 = read("UixR2AdaptiveShellContractTest.kt")
uix3 = read("UixR3DownloadsAddContractTest.kt")
uix5 = read("UixR5ActivitySettingsContractTest.kt")

for needle in (
    "private fun appSources(root: File)",
    'appSources.contains("viewModel::runTermuxToolProbe")',
    'screens.contains("External tools through Termux")',
    'UiSourceTree.readUser(root).contains("Media track selection")',
):
    require(architecture, needle, "Architecture contract modernization")

require(phase41, "private val repo = androidRoot()", "Phase 41 test root discovery")
require(phase41, 'File(it, "settings.gradle.kts").isFile', "Phase 41 test root discovery")
reject(phase41, 'private val repo = File(System.getProperty("user.dir"))', "Phase 41 test root discovery")

require(removal, "val userScreens = UiSourceTree.readUser(root)", "Browser-removal user-surface contract")
require(activity, '"Overview" to "Needs attention"', "Activity label contract")
require(activity, 'val activitySources = shell + "\\n" + screens', "Activity modular source contract")

require(uix2, 'shell.contains(".width(224.dp)")', "Adaptive shell contract")
require(uix2, 'background = 0xFF090B0F', "Shared theme token contract")
require(uix2, 'fun(?:\\\\s+<[^>]+>)?\\\\s+$primitive\\\\(', "Generic primitive declaration contract")
require(uix2, "XdmMinimumTouchTarget", "Touch-target token contract")
reject(uix2, "private val XdmBackground = Color(0xFF090B0F)", "Shared theme token contract")

require(uix3, 'onClick = { onInspectMedia(url, name) }', "Review-first media inspection contract")
reject(uix3, 'Regex("onInspectMedia\\\\([^)]*\\\\).*onAdd"', "Review-first media inspection contract")
require(uix5, "DeveloperWorkspacePolicy.shouldCompose", "Developer workspace gate contract")

affected = [architecture, phase41, removal, activity, uix2, uix3, uix5]
for text in affected:
    reject(text, 'Modifier.width(224.dp)', "Stale exact-chain assertion")

print("Phase 42 contract-test modernization validation passed.")
