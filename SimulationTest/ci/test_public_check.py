"""Public CLI selection checks; no build, private input or test-runner mocking."""
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


class PublicSelectionTest(unittest.TestCase):
    def test_named_plan_lists_mechanisms_and_continuous_scenario_without_running(self):
        entry = Path(__file__).with_name('public_check.py')
        with tempfile.TemporaryDirectory(prefix='reforge-suite-cli-') as tmp:
            output = Path(tmp) / 'must-not-be-created'
            result = subprocess.run([sys.executable, '-B', str(entry), '--suite',
                                     'content-movement-knowledge', '--kind', 'all', '--list',
                                     '--output', str(output)], capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stderr)
            plan = json.loads(result.stdout)
            self.assertEqual('public.content-movement-knowledge.v1', plan['suiteId'])
            self.assertIn('com.rustedwarfare.reforge.core.content.LoadFailureTest', plan['tests']['core'])
            self.assertIn('movement-knowledge-chain', [case['id'] for case in plan['scenarios']])
            self.assertFalse(output.exists(), 'Listing must not execute or create evidence')

    def test_invalid_or_missing_inputs_fail_before_any_build_or_output(self):
        import shutil
        entry = Path(__file__).with_name('public_check.py')
        with tempfile.TemporaryDirectory(prefix='reforge-invalid-suite-') as tmp:
            repo = Path(tmp)
            copied = repo / 'SimulationTest/ci/public_check.py'
            copied.parent.mkdir(parents=True)
            shutil.copyfile(entry, copied)
            registry = repo / 'SimulationTest/public/suites'
            registry.mkdir(parents=True)
            cases = [
                ('empty', {'tests': {}}),
                ('invalid-module', {'tests': {'shell': ['Run']}}),
                ('missing-scenario', {'short': [{'id': 'missing', 'scenario': 'absent.json', 'contract': 'absent-contract.json'}]}),
                ('escaping-fixture', {'fixture': '../../../secret', 'tests': {'core': ['ValidTest']}}),
            ]
            for name, fields in cases:
                manifest = dict(schemaVersion=1, suiteId=name, inputScope='public', **fields)
                (registry / (name + '.json')).write_text(json.dumps(manifest), encoding='utf8')
                output = repo / ('must-not-create-' + name)
                result = subprocess.run([sys.executable, '-B', str(copied), '--suite', name,
                    '--kind', 'all', '--list', '--output', str(output)], capture_output=True, text=True)
                self.assertEqual(2, result.returncode, name + result.stdout + result.stderr)
                self.assertFalse(output.exists())
            for name in ['../escape', 'unregistered']:
                result = subprocess.run([sys.executable, '-B', str(copied), '--suite', name, '--list'], capture_output=True)
                self.assertEqual(2, result.returncode)


if __name__ == '__main__':
    unittest.main()
