#!/usr/bin/env python3
"""Audit XGO orchestration for Devtool-owned Gradle execution.

XGO workflows must express Android/Gradle work as first-class Devtool runner or
cross-target workflow actions. Repository-owned orchestration must not bypass
Devtool by launching ``gradle``/``gradlew`` directly, and workflow jobs must not
use the legacy ``gradle_tasks`` shortcut because that execution surface has not
historically gone through the canonical Android/Gradle planner.

The policy deliberately covers XGO orchestration surfaces, not historical
Android project documentation/runbooks outside the XGO execution graph:

* executable job declarations in .devtool.toml;
* the checked-in devtoolw launcher;
* executable Python helpers under tools/xgo/.
"""

from __future__ import annotations

import argparse
import ast
import json
import re
import shlex
import sys
import tomllib
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any, Iterable


POLICY = "xgo-devtool-owned-gradle-execution"
RAW_GRADLE_BASENAMES = {"gradle", "gradlew", "gradlew.bat"}
SHELL_LAUNCHERS = {"sh", "bash", "dash", "zsh", "ksh"}
PYTHON_PROCESS_CALLS = {
    "subprocess.run",
    "subprocess.Popen",
    "subprocess.call",
    "subprocess.check_call",
    "subprocess.check_output",
    "subprocess.getoutput",
    "subprocess.getstatusoutput",
    "os.system",
    "os.popen",
    "os.execl",
    "os.execle",
    "os.execlp",
    "os.execlpe",
    "os.execv",
    "os.execve",
    "os.execvp",
    "os.execvpe",
    "asyncio.create_subprocess_exec",
    "asyncio.create_subprocess_shell",
}

# Match an executable token, not Gradle namespace/property text such as
# ``org.gradle.parallel`` or a config section named ``[target.gradle]``.
_RAW_GRADLE_TOKEN = re.compile(
    r"(?<![A-Za-z0-9_.-])"
    r"(?:\./|(?:[A-Za-z0-9_$~{}.-]+/)+)?"
    r"(?:gradlew(?:\.bat)?|gradle)"
    r"(?![A-Za-z0-9_.-])"
)


class ExecutionPolicyError(RuntimeError):
    """Raised when the XGO Devtool execution policy is violated."""


@dataclass(frozen=True)
class Violation:
    source: str
    location: str
    kind: str
    detail: str


def _basename(value: str) -> str:
    token = value.strip().strip('"\'')
    return Path(token).name.lower()


def _contains_raw_gradle_token(text: str) -> bool:
    return bool(_RAW_GRADLE_TOKEN.search(text))


def _structured_command_uses_raw_gradle(command: Any) -> bool:
    if isinstance(command, str):
        return _contains_raw_gradle_token(command)
    if not isinstance(command, list) or not command:
        return False

    argv = [str(part) for part in command]
    if _basename(argv[0]) in RAW_GRADLE_BASENAMES:
        return True

    # Structured jobs occasionally delegate to a shell. Inspect the command
    # payload as shell text as well so ["sh", "-c", "./gradlew ..."] cannot
    # bypass the policy.
    if _basename(argv[0]) in SHELL_LAUNCHERS:
        return any(_contains_raw_gradle_token(part) for part in argv[1:])
    return False


def _iter_job_tables(config: dict[str, Any]) -> Iterable[tuple[str, dict[str, Any]]]:
    jobs = config.get("jobs", {})
    if isinstance(jobs, dict):
        for name, job in jobs.items():
            if isinstance(job, dict):
                yield f"jobs.{name}", job

    targets = config.get("targets", {})
    if not isinstance(targets, dict):
        return
    for target_name, target in targets.items():
        if not isinstance(target, dict):
            continue
        target_jobs = target.get("jobs", {})
        if not isinstance(target_jobs, dict):
            continue
        for job_name, job in target_jobs.items():
            if isinstance(job, dict):
                yield f"targets.{target_name}.jobs.{job_name}", job


def audit_config(config_path: Path) -> list[Violation]:
    with config_path.open("rb") as handle:
        config = tomllib.load(handle)

    violations: list[Violation] = []
    for location, job in _iter_job_tables(config):
        if job.get("gradle_tasks"):
            violations.append(
                Violation(
                    source=str(config_path.name),
                    location=f"{location}.gradle_tasks",
                    kind="legacy-gradle-tasks-bypass",
                    detail=(
                        "gradle_tasks bypasses the canonical Devtool Android/Gradle "
                        "runner boundary; use runner:<runner>#<action> or "
                        "target:<target>#<workflow>."
                    ),
                )
            )

        command = job.get("command")
        if command is not None and _structured_command_uses_raw_gradle(command):
            violations.append(
                Violation(
                    source=str(config_path.name),
                    location=f"{location}.command",
                    kind="raw-gradle-command",
                    detail="job directly launches Gradle/Gradle Wrapper outside the Devtool runner",
                )
            )

        shell_command = job.get("shell_command")
        if isinstance(shell_command, str) and _contains_raw_gradle_token(shell_command):
            violations.append(
                Violation(
                    source=str(config_path.name),
                    location=f"{location}.shell_command",
                    kind="raw-gradle-shell-command",
                    detail="job shell_command directly launches Gradle/Gradle Wrapper outside the Devtool runner",
                )
            )
    return violations


def _call_name(node: ast.AST) -> str:
    if isinstance(node, ast.Name):
        return node.id
    if isinstance(node, ast.Attribute):
        prefix = _call_name(node.value)
        return f"{prefix}.{node.attr}" if prefix else node.attr
    return ""


def _literal_strings(node: ast.AST) -> list[str]:
    values: list[str] = []
    if isinstance(node, ast.Constant) and isinstance(node.value, str):
        values.append(node.value)
    elif isinstance(node, (ast.List, ast.Tuple, ast.Set)):
        for element in node.elts:
            values.extend(_literal_strings(element))
    elif isinstance(node, ast.Dict):
        for key in node.keys:
            if key is not None:
                values.extend(_literal_strings(key))
        for value in node.values:
            values.extend(_literal_strings(value))
    elif isinstance(node, ast.JoinedStr):
        # Literal portions of f-strings are enough to catch common forms such
        # as f"./gradlew {task}" without trying to evaluate expressions.
        values.append("".join(
            part.value for part in node.values
            if isinstance(part, ast.Constant) and isinstance(part.value, str)
        ))
    return values


def audit_python(path: Path, root: Path) -> list[Violation]:
    text = path.read_text(encoding="utf-8")
    try:
        tree = ast.parse(text, filename=str(path))
    except SyntaxError as exc:
        raise ExecutionPolicyError(f"cannot parse {path}: {exc}") from exc

    violations: list[Violation] = []
    relative = path.relative_to(root).as_posix()
    for node in ast.walk(tree):
        if not isinstance(node, ast.Call):
            continue
        name = _call_name(node.func)
        if name not in PYTHON_PROCESS_CALLS:
            continue
        strings: list[str] = []
        for arg in node.args:
            strings.extend(_literal_strings(arg))
        for keyword in node.keywords:
            strings.extend(_literal_strings(keyword.value))
        if any(_contains_raw_gradle_token(value) for value in strings):
            violations.append(
                Violation(
                    source=relative,
                    location=f"line {getattr(node, 'lineno', '?')}",
                    kind="raw-gradle-python-launch",
                    detail=f"{name} launches or embeds a raw Gradle/Gradle Wrapper command",
                )
            )
    return violations


def _shell_command_tokens(line: str) -> list[str]:
    # Strip ordinary comments and common control/list prefixes. This scanner is
    # intentionally command-position-aware so documentation-like echo strings
    # do not become false positives.
    stripped = line.strip()
    if not stripped or stripped.startswith("#"):
        return []
    try:
        tokens = shlex.split(stripped, comments=True, posix=True)
    except ValueError:
        return []
    while tokens and tokens[0] in {"if", "then", "elif", "else", "do", "!", "command", "exec", "env"}:
        tokens.pop(0)
    # Skip leading variable assignments.
    while tokens and re.match(r"^[A-Za-z_][A-Za-z0-9_]*=", tokens[0]):
        tokens.pop(0)
    return tokens


def audit_shell(path: Path, root: Path) -> list[Violation]:
    violations: list[Violation] = []
    relative = path.relative_to(root).as_posix()
    for lineno, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        tokens = _shell_command_tokens(line)
        if not tokens:
            continue
        # Split simple shell list/control operators that shlex leaves as words
        # in some forms, and inspect only executable-position tokens.
        candidates = [tokens[0]]
        for index, token in enumerate(tokens[:-1]):
            if token in {";", "&&", "||", "|"}:
                candidates.append(tokens[index + 1])
        if any(_basename(token) in RAW_GRADLE_BASENAMES for token in candidates):
            violations.append(
                Violation(
                    source=relative,
                    location=f"line {lineno}",
                    kind="raw-gradle-shell-launch",
                    detail="shell launcher directly invokes Gradle/Gradle Wrapper outside Devtool",
                )
            )
    return violations


def audit(root: Path, config_path: Path | None = None) -> dict[str, Any]:
    root = root.resolve()
    config_path = (config_path or (root / ".devtool.toml")).resolve()
    if not config_path.is_file():
        raise ExecutionPolicyError(f"Devtool config not found: {config_path}")

    violations = audit_config(config_path)

    wrapper = root / "devtoolw"
    if wrapper.is_file():
        violations.extend(audit_shell(wrapper, root))

    tools_root = root / "tools" / "xgo"
    if tools_root.is_dir():
        for path in sorted(tools_root.rglob("*.py")):
            violations.extend(audit_python(path, root))
        for pattern in ("*.sh", "*.bash"):
            for path in sorted(tools_root.rglob(pattern)):
                violations.extend(audit_shell(path, root))

    report = {
        "schema_version": 1,
        "status": "pass" if not violations else "fail",
        "policy": POLICY,
        "scope": [
            ".devtool.toml executable job declarations",
            "devtoolw launcher",
            "tools/xgo executable helpers",
        ],
        "violation_count": len(violations),
        "violations": [asdict(item) for item in violations],
    }
    return report


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, default=Path.cwd(), help="repository root")
    parser.add_argument("--config", type=Path, default=None, help="Devtool config path")
    parser.add_argument("--output", type=Path, default=None, help="write JSON report")
    args = parser.parse_args(argv)

    try:
        report = audit(args.repo, args.config)
    except (ExecutionPolicyError, OSError, tomllib.TOMLDecodeError) as exc:
        print(f"XGO Devtool execution policy audit failed: {exc}", file=sys.stderr)
        return 2

    payload = json.dumps(report, indent=2, sort_keys=True) + "\n"
    if args.output is not None:
        output = args.output
        if not output.is_absolute():
            output = args.repo / output
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(payload, encoding="utf-8")
    print(payload, end="")

    if report["status"] != "pass":
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
