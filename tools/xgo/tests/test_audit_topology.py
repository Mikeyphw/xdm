from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

import sys
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from audit_topology import TopologyError, audit, SUBSYSTEM_TARGETS, GATE_TARGETS


def make_config(path: Path, *, cycle: bool = False) -> None:
    lines = [
        "[workspace.groups]",
        "xgo = [" + ", ".join(f'"{x}"' for x in SUBSYSTEM_TARGETS) + "]",
        "",
    ]
    for name in SUBSYSTEM_TARGETS:
        lines += [
            f"[targets.{name}]",
            'runner = "command"',
            'root = "."',
            'execution_environment = "native-termux"',
            f"[targets.{name}.jobs.bootstrap_contract]",
            'runner = "command"',
            'command = ["true"]',
            f"[targets.{name}.workflows]",
            'validate = [{ id = "bootstrap", ref = "job:bootstrap_contract", depends_on = [] }]',
            "",
        ]
    for index, name in enumerate(GATE_TARGETS):
        child = "xgo_foundation"
        lines += [
            f"[targets.{name}]",
            'runner = "command"',
            'root = "."',
            'execution_environment = "native-termux"',
            f"[targets.{name}.workflows]",
            f'validate = [{{ id = "child", ref = "target:{child}#validate", depends_on = [] }}]',
            "",
        ]
    if cycle:
        # Create a local cycle in a subsystem workflow.
        marker = '[targets.xgo_foundation.workflows]\nvalidate = [{ id = "bootstrap", ref = "job:bootstrap_contract", depends_on = [] }]'
        repl = '[targets.xgo_foundation.workflows]\nvalidate = [{ id = "a", ref = "job:bootstrap_contract", depends_on = ["b"] }, { id = "b", ref = "job:bootstrap_contract", depends_on = ["a"] }]'
        text = "\n".join(lines).replace(marker, repl)
    else:
        text = "\n".join(lines)
    path.write_text(text)


class TopologyAuditTests(unittest.TestCase):
    def test_valid_topology(self) -> None:
        root = Path(tempfile.mkdtemp(prefix="xgo-topology-"))
        config = root/".devtool.toml"
        make_config(config)
        report = audit(config, None)
        self.assertEqual(report["subsystem_target_count"], len(SUBSYSTEM_TARGETS))
        self.assertEqual(report["gate_target_count"], len(GATE_TARGETS))

    def test_missing_target_rejected(self) -> None:
        root = Path(tempfile.mkdtemp(prefix="xgo-topology-"))
        config = root/".devtool.toml"
        make_config(config)
        text = config.read_text().replace("[targets.xgo_store]", "[targets.xgo_store_missing]", 1)
        config.write_text(text)
        with self.assertRaises(TopologyError):
            audit(config, None)

    def test_cycle_rejected(self) -> None:
        root = Path(tempfile.mkdtemp(prefix="xgo-topology-"))
        config = root/".devtool.toml"
        make_config(config, cycle=True)
        with self.assertRaises(TopologyError):
            audit(config, None)


if __name__ == "__main__":
    unittest.main()
