from __future__ import annotations

import contextlib
import importlib.util
import io
from pathlib import Path
import stat
import subprocess
import tempfile
import tomllib
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[3]
WRAPPER = ROOT/'tools/xgo/validate-devtool-wrapper.py'


def load_wrapper():
    spec=importlib.util.spec_from_file_location('xdm_xgo_wrapper_contract_phase01',WRAPPER)
    assert spec and spec.loader
    mod=importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


class Phase01ContractTests(unittest.TestCase):
    def _validate_isolated(self, original: str, launcher: str):
        module=load_wrapper()
        with tempfile.TemporaryDirectory(prefix='xgo-phase01-') as tmp:
            root=Path(tmp)
            config=root/'.devtool.toml'
            script=root/'devtoolw'
            config.write_text(original,encoding='utf-8')
            script.write_text(launcher,encoding='utf-8')
            script.chmod(script.stat().st_mode | stat.S_IXUSR)
            with patch.object(module,'CONFIG',config),patch.object(module,'LAUNCHER',script):
                with contextlib.redirect_stdout(io.StringIO()):
                    module.main()

    def test_wrapper_validates_actual_repository(self):
        self._validate_isolated((ROOT/'.devtool.toml').read_text(),(ROOT/'devtoolw').read_text())

    def test_schema_one_is_refused(self):
        text=(ROOT/'.devtool.toml').read_text()
        text=text.replace('[wrapper]\nschema = 2','[wrapper]\nschema = 1',1)
        self.assertNotEqual(text,(ROOT/'.devtool.toml').read_text())
        with self.assertRaisesRegex(SystemExit,'schema must be 2'):
            self._validate_isolated(text,(ROOT/'devtoolw').read_text())

    def test_unexpected_command_refused(self):
        text=(ROOT/'.devtool.toml').read_text()+'''\n[wrapper.commands.unreviewed-phase01]\ntarget = "xgo_ci"\nworkflow = "ci-local"\n'''
        with self.assertRaisesRegex(SystemExit,'unexpected commands'):
            self._validate_isolated(text,(ROOT/'devtoolw').read_text())

    def test_old_launcher_contract_refused(self):
        old=(ROOT/'devtoolw').read_text().replace('exec "$(command -v devtool)" --repo', 'exec totally-different --repo',1)
        with self.assertRaisesRegex(SystemExit,'launcher contract fragment'):
            self._validate_isolated((ROOT/'.devtool.toml').read_text(),old)

    def test_python_suite_uses_pytest_for_both_styles(self):
        config=tomllib.loads((ROOT/'.devtool.toml').read_text())
        command=config['targets']['xgo_foundation']['jobs']['foundation_tests']['command']
        self.assertEqual(command,['python3','-m','pytest','-q','tools/xgo/tests'])

    def test_jni_contract_test_uses_repository_root(self):
        location=Path(__file__).resolve().parent/'test_android_jni_adapter_contract.py'
        self.assertIn('ROOT = Path(__file__).resolve().parents[3]',location.read_text())

    def test_rem18_shell_preserves_python_failure(self):
        config=tomllib.loads((ROOT/'.devtool.toml').read_text())
        script=config['jobs']['xdm-rem18-firefox-audit']['shell_command']
        self.assertIn('status=$?',script)
        self.assertIn('exit "$status"',script)
        self.assertNotIn('| tee ',script)

    def test_shell_failure_is_not_masked_by_log_output(self):
        with tempfile.TemporaryDirectory(prefix='xgo-rem18-failure-') as tmp:
            root=Path(tmp)
            script=(
                'python3 -c "import sys; sys.exit(7)" > audit.log 2>&1\n'
                'status=$?\n'
                'cat audit.log\n'
                'exit "$status"\n'
            )
            process=subprocess.run(['sh','-c',script],cwd=root,capture_output=True,text=True)
            self.assertEqual(process.returncode,7)
            self.assertTrue((root/'audit.log').is_file())

    def test_ci_job_inputs_are_engine_relative(self):
        config=tomllib.loads((ROOT/'.devtool.toml').read_text())
        for name in ('ci-go-unit','ci-go-vet'):
            job=config['targets']['xgo_ci']['jobs'][name]
            self.assertEqual(job['root'],'engine')
            self.assertEqual(job['inputs'],['**','go.mod','go.sum'])


if __name__=='__main__':
    unittest.main()
