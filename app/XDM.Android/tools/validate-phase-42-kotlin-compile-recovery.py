#!/usr/bin/env python3
from __future__ import annotations

import tomllib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REPOSITORY_ROOT = ROOT.parents[1]


def read(path: Path) -> str:
    if not path.is_file():
        raise SystemExit(f"Kotlin compile recovery missing required file: {path}")
    return path.read_text(encoding="utf-8")


with (REPOSITORY_ROOT / ".devtool.toml").open("rb") as stream:
    config = tomllib.load(stream)

target = config.get("targets", {}).get("xdm_android", {})
gradle = target.get("gradle", {})
validation = config.get("validation", {})
phases = validation.get("phases", {})
compile_profile = validation.get("phase_profiles", {}).get("compile", {})

expected_gradle_values = {
    "max_workers": 1,
    "no_daemon": True,
    "parallel": False,
    "build_cache": False,
}
for key, expected in expected_gradle_values.items():
    actual = gradle.get(key)
    if actual != expected:
        raise SystemExit(f"Kotlin compile recovery Gradle setting mismatch: {key}={actual!r}")

required_args = {
    "--no-build-cache",
    "--no-configuration-cache",
    "-Dorg.gradle.parallel=false",
    "-Dorg.gradle.vfs.watch=false",
    "-Dorg.gradle.jvmargs=-Xmx1536m -XX:MaxMetaspaceSize=768m -Dfile.encoding=UTF-8",
    "-Pkotlin.incremental=false",
    "-Pkotlin.incremental.useClasspathSnapshot=false",
    "-Pkotlin.compiler.execution.strategy=in-process",
    "-Pxdm.cleanKotlinValidation=true",
}
actual_args = set(str(item) for item in gradle.get("args", []))
missing_args = sorted(required_args - actual_args)
if missing_args:
    raise SystemExit(f"Kotlin compile recovery Gradle arguments missing: {missing_args}")

compile_tasks = list(phases.get("compile", []))
expected_compile_tasks = [":app:resetKotlinValidationState", ":app:compileDebugSources"]
if compile_tasks != expected_compile_tasks:
    raise SystemExit(
        "Kotlin compile recovery phase must reset app compiler state before compileDebugSources: "
        f"{compile_tasks!r}"
    )

for key, expected in {
    "max_workers": 1,
    "cpu_limit": 2,
    "gradle_heap_mb": 1536,
    "memory_guard_mb": 256,
    "no_daemon": True,
    "low_priority": True,
}.items():
    if compile_profile.get(key) != expected:
        raise SystemExit(f"Kotlin compile recovery compile profile mismatch: {key}={compile_profile.get(key)!r}")

build_gradle = read(ROOT / "app/build.gradle.kts")
for needle in (
    'import org.gradle.api.tasks.Delete',
    'providers.gradleProperty("xdm.cleanKotlinValidation")',
    'val resetKotlinValidationState by tasks.registering(Delete::class)',
    'layout.buildDirectory.dir("kotlin")',
    'layout.buildDirectory.dir("intermediates/built_in_kotlinc")',
    'layout.buildDirectory.dir("tmp/kotlin-classes")',
    'onlyIf { cleanKotlinValidationState.get() }',
    'it.name.startsWith("compile") && it.name.endsWith("Kotlin")',
    'dependsOn(resetKotlinValidationState)',
):
    if needle not in build_gradle:
        raise SystemExit(f"Kotlin compile recovery Gradle task contract missing: {needle}")

for forbidden in (
    'delete(rootProject.layout.projectDirectory)',
    'delete(layout.projectDirectory)',
    'dependsOn("clean")',
):
    if forbidden in build_gradle:
        raise SystemExit(f"Kotlin compile recovery must remain targeted, found: {forbidden}")

browser_gate = read(ROOT / "tools/run-browser-bridge-release-gate.sh")
final_gate = read(ROOT / "tools/run-final-release-gate.sh")
for source, label in ((browser_gate, "browser bridge release gate"), (final_gate, "final release gate")):
    for needle in (
        "-Pxdm.cleanKotlinValidation=true",
        "-Pkotlin.incremental=false",
        "-Pkotlin.incremental.useClasspathSnapshot=false",
        "-Pkotlin.compiler.execution.strategy=in-process",
        "--no-build-cache",
        "--no-configuration-cache",
    ):
        if needle not in source:
            raise SystemExit(f"Kotlin compile recovery missing {needle} from {label}")

print("Phase 42 Kotlin compile recovery validation passed.")
