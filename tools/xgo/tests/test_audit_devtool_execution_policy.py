from __future__ import annotations

import importlib.util
import sys
from pathlib import Path

import pytest

MODULE_PATH = Path(__file__).resolve().parents[1] / "audit_devtool_execution_policy.py"
spec = importlib.util.spec_from_file_location("audit_devtool_execution_policy", MODULE_PATH)
assert spec and spec.loader
mod = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = mod
spec.loader.exec_module(mod)


def write_repo(tmp_path: Path, config: str, *, helper: str | None = None) -> Path:
    (tmp_path / ".devtool.toml").write_text(config, encoding="utf-8")
    (tmp_path / "devtoolw").write_text("#!/bin/sh\nexec devtool wrapper exec \"$@\"\n", encoding="utf-8")
    tools = tmp_path / "tools" / "xgo"
    tools.mkdir(parents=True)
    if helper is not None:
        (tools / "helper.py").write_text(helper, encoding="utf-8")
    return tmp_path


def test_current_repository_passes_policy() -> None:
    root = MODULE_PATH.parents[2]
    report = mod.audit(root)
    assert report["status"] == "pass", report["violations"]
    assert report["violation_count"] == 0


def test_structured_gradlew_job_is_rejected(tmp_path: Path) -> None:
    root = write_repo(
        tmp_path,
        '''
[jobs.bad]
runner = "command"
command = ["./gradlew", ":app:lintDebug"]
''',
    )
    report = mod.audit(root)
    assert report["status"] == "fail"
    assert report["violations"][0]["kind"] == "raw-gradle-command"


def test_shell_gradlew_job_is_rejected(tmp_path: Path) -> None:
    root = write_repo(
        tmp_path,
        '''
[jobs.bad]
runner = "command"
shell_command = """
set -eu
./gradlew :app:assembleDebug
"""
''',
    )
    report = mod.audit(root)
    assert any(v["kind"] == "raw-gradle-shell-command" for v in report["violations"])


def test_gradle_tasks_shortcut_is_rejected(tmp_path: Path) -> None:
    root = write_repo(
        tmp_path,
        '''
[jobs.bad]
runner = "command"
gradle_tasks = [":app:lintDebug"]
''',
    )
    report = mod.audit(root)
    assert any(v["kind"] == "legacy-gradle-tasks-bypass" for v in report["violations"])


def test_python_subprocess_gradlew_is_rejected(tmp_path: Path) -> None:
    root = write_repo(
        tmp_path,
        '''
[targets.good]
runner = "android"
root = "app"
[targets.good.gradle]
version = "9.7.1"
''',
        helper='''import subprocess\nsubprocess.run(["./gradlew", ":app:test"], check=True)\n''',
    )
    report = mod.audit(root)
    assert any(v["kind"] == "raw-gradle-python-launch" for v in report["violations"])


def test_gradle_configuration_and_variable_names_are_allowed(tmp_path: Path) -> None:
    root = write_repo(
        tmp_path,
        '''
[targets.good]
runner = "android"
root = "app"
[targets.good.gradle]
version = "9.7.1"
args = ["-Dorg.gradle.parallel=false"]
''',
        helper='''from pathlib import Path\ngradle = Path("build.gradle.kts").read_text()\nassert "plugins" in gradle\n''',
    )
    report = mod.audit(root)
    assert report["status"] == "pass", report["violations"]
