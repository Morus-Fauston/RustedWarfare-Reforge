"""Fixed public layer CLI contracts; listing never starts a build or writes output."""
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


class PublicBatchTest(unittest.TestCase):
    def test_pr_and_long_select_all_applicable_suites_without_private_or_legacy_duplicates(self):
        entry = Path(__file__).with_name('public_batch.py')
        with tempfile.TemporaryDirectory(prefix='reforge-public-layer-') as tmp:
            output = Path(tmp) / 'must-not-exist'
            for kind, names in [
                ('pr', ['content-movement-knowledge', 'production-combat', 'build-economy',
                        'maintenance-defense', 'platform-basics', 'tooling']),
                ('long', ['content-movement-knowledge', 'production-combat', 'build-economy',
                          'maintenance-defense']),
            ]:
                result = subprocess.run([sys.executable, '-B', str(entry), '--kind', kind,
                    '--list', '--output', str(output)], capture_output=True, text=True)
                self.assertEqual(0, result.returncode, result.stderr)
                plan = json.loads(result.stdout)
                self.assertEqual(names, [row['name'] for row in plan['suites']])
                self.assertTrue(all(row['plan']['inputScope'] == 'public' for row in plan['suites']))
                for row in plan['suites']:
                    self.assertEqual(kind if row['name'] != 'tooling' else 'mechanism', row['plan']['kind'])
                self.assertFalse(output.exists())

    def test_child_build_failure_preserves_receipts_and_does_not_run_later_suites(self):
        with tempfile.TemporaryDirectory(prefix='reforge-layer-failure-') as tmp:
            repo = Path(tmp)
            ci = repo/'SimulationTest/ci'
            ci.mkdir(parents=True)
            for filename in ['public_batch.py', 'public_check.py']:
                shutil.copyfile(Path(__file__).with_name(filename), ci/filename)
            public = repo/'SimulationTest/public'
            (public/'suites').mkdir(parents=True)
            for name in ['content-movement-knowledge', 'production-combat', 'build-economy',
                         'maintenance-defense', 'platform-basics', 'tooling']:
                (public/'suites'/(name+'.json')).write_text(json.dumps(dict(schemaVersion=1,
                    suiteId=name, inputScope='public', tests={'tools': ['ProbeTest']})), encoding='utf8')
            for relative in ['Engine/core/build.gradle.kts', 'Engine/client/build.gradle.kts',
                'Engine/pc/build.gradle.kts', 'SimulationTest/build.gradle.kts',
                'SimulationTest/settings.gradle.kts', 'SimulationTest/gradle.properties']:
                p=repo/relative
                p.parent.mkdir(parents=True, exist_ok=True)
                p.write_text('', encoding='utf8')
            (repo/'SimulationTest/gradlew').write_text('#!/bin/sh\nexit 9\n', encoding='utf8')
            (repo/'SimulationTest/gradlew.bat').write_text('@exit /b 9\n', encoding='utf8')
            # These are source identity members; no imported helper is faked or replaced.
            for relative in ['platform_scenario.py', 'test_platform_scenario.py', 'test_platform_session_cli.py',
                'workflow.py', 'workflow_inputs.py', 'workflow_device.py', 'workflow_windows.py',
                'workflow_reuse.py', 'perf_report.py', 'platform_perf.py', 'test_workflow.py',
                'test_workflow_device.py', 'test_workflow_windows.py', 'test_workflow_reuse.py',
                'test_perf_analysis.py', 'specialist_check.py', 'test_specialist_check.py']:
                p=repo/'SimulationTest/tools'/relative
                p.parent.mkdir(parents=True, exist_ok=True)
                p.write_text('', encoding='utf8')
            for relative in ['verify_dec_evidence.py', 'test_verify_dec_evidence.py']:
                p=repo/'Decompile/tools'/relative
                p.parent.mkdir(parents=True, exist_ok=True)
                p.write_text('', encoding='utf8')
            output=repo/'batch'
            result=subprocess.run([sys.executable, '-B', str(ci/'public_batch.py'),
                '--kind', 'mechanism', '--output', str(output)], capture_output=True, text=True, timeout=30)
            self.assertEqual(1, result.returncode, result.stderr)
            batch=json.loads((output/'result.json').read_text(encoding='utf8'))
            self.assertEqual('execution-failed', batch['status'])
            self.assertEqual(1, len(batch['suites']))
            child=json.loads((output/'content-movement-knowledge/result.json').read_text(encoding='utf8'))
            self.assertEqual(9, child['commands'][0]['exitCode'])
            self.assertFalse((output/'production-combat').exists())
            original=(output/'result.json').read_bytes()
            retry=subprocess.run([sys.executable, '-B', str(ci/'public_batch.py'),
                '--kind', 'mechanism', '--output', str(output)], capture_output=True, text=True)
            self.assertEqual(2, retry.returncode)
            self.assertEqual(original, (output/'result.json').read_bytes())


if __name__ == '__main__':
    unittest.main()
