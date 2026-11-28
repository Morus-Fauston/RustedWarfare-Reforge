"""Developer selection uses the public registry and preserves distinct input contexts."""
from concurrent.futures import ThreadPoolExecutor
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from development_check import collect_tests
from public_check import test_concurrency, default_build_workers


class DevelopmentSelectionTest(unittest.TestCase):
    def test_cloud_and_local_pool_decouple_gradle_workers_while_keeping_pc_serial(self):
        mods = {'core': [], 'client': [], 'tools': [], 'pc': []}
        cloud = test_concurrency(mods, forks=4, workers=None, cloud=True, cpu_count=4)
        self.assertEqual(4, cloud['requested'])
        self.assertEqual(4, cloud['maxWorkers'])
        self.assertEqual({'core': 4, 'client': 4, 'tools': 4, 'pc': 1},
                         {k: v['maxParallelForks'] for k, v in cloud['modules'].items()})
        self.assertEqual(4, default_build_workers(None, cloud=True, cpu_count=24))
        self.assertEqual(12, default_build_workers(None, cloud=False, cpu_count=24))

        local_pool = test_concurrency(mods, forks=None, workers=None, cloud=False, cpu_count=24)
        self.assertEqual(3, local_pool['requested'])
        self.assertEqual(8, local_pool['maxWorkers'])
        self.assertEqual({'core': 3, 'client': 2, 'tools': 2, 'pc': 1},
                         {k: v['maxParallelForks'] for k, v in local_pool['modules'].items()})
        self.assertLessEqual(sum(v['maxParallelForks'] for v in local_pool['modules'].values()), 8)

        local_three = test_concurrency(mods, forks=3, workers=None, cloud=False, cpu_count=12)
        self.assertEqual(3, local_three['requested'])
        self.assertEqual(6, local_three['maxWorkers'])
        self.assertEqual({'core': 3, 'client': 2, 'tools': 2, 'pc': 1},
                         {k: v['maxParallelForks'] for k, v in local_three['modules'].items()})
        self.assertLessEqual(sum(v['maxParallelForks'] for v in local_three['modules'].values()), 8)

        explicit = test_concurrency(mods, forks=4, workers=12, cloud=False, cpu_count=24)
        self.assertEqual(4, explicit['requested'])
        self.assertEqual(12, explicit['maxWorkers'])
        self.assertEqual({'core': 3, 'client': 2, 'tools': 2, 'pc': 1},
                         {k: v['maxParallelForks'] for k, v in explicit['modules'].items()})
        self.assertEqual(8, sum(v['maxParallelForks'] for v in explicit['modules'].values()))

        high_explicit = test_concurrency(mods, forks=8, workers=16, cloud=False, cpu_count=24)
        self.assertEqual(8, high_explicit['requested'])
        self.assertEqual(16, high_explicit['maxWorkers'])
        self.assertEqual({'core': 3, 'client': 2, 'tools': 2, 'pc': 1},
                         {k: v['maxParallelForks'] for k, v in high_explicit['modules'].items()})
        self.assertEqual(8, sum(v['maxParallelForks'] for v in high_explicit['modules'].values()))

        two_mods = test_concurrency({'core': [], 'client': []}, forks=4, workers=12, cloud=False, cpu_count=24)
        self.assertEqual({'core': 4, 'client': 4},
                         {k: v['maxParallelForks'] for k, v in two_mods['modules'].items()})

    def test_cli_fork_budget_is_bounded_explicit_and_listing_is_read_only(self):
        from contextlib import redirect_stderr, redirect_stdout
        import io
        from unittest.mock import patch
        import development_check
        import public_batch
        import public_check

        def invoke_inproc(mod, argv):
            out, err = io.StringIO(), io.StringIO()
            with patch.object(sys, 'argv', [mod.__file__, *argv]), redirect_stdout(out), redirect_stderr(err):
                try:
                    code = mod.main()
                except SystemExit as exc:
                    code = exc.code if isinstance(exc.code, int) else 1
            return code, out.getvalue(), err.getvalue()

        ci = Path(__file__).parent
        with tempfile.TemporaryDirectory(prefix='reforge-fork-cli-') as temporary:
            output = Path(temporary)/'must-not-exist'
            for entry, mod, selection in [
                ('development_check.py', development_check, ['--suite', 'production-combat']),
                ('public_check.py', public_check, ['--suite', 'production-combat', '--kind', 'pr']),
                ('public_batch.py', public_batch, ['--kind', 'pr']),
            ]:
                # Real subprocess CLI smoke for valid decoupled flags and invalid rejection
                ok_proc = subprocess.run(
                    [sys.executable, '-B', str(ci/entry), *selection,
                     '--test-forks', '2', '--gradle-workers', '6', '--list', '--output', str(output)],
                    capture_output=True, text=True, encoding='utf8',
                )
                self.assertEqual(0, ok_proc.returncode, entry + ok_proc.stderr)
                budget = json.loads(ok_proc.stdout)['testConcurrency']
                self.assertEqual(2, budget['requested'])
                self.assertEqual(6, budget['maxWorkers'])
                self.assertEqual(6, budget['gradleWorkers'])
                self.assertEqual(1, budget['modules']['pc']['maxParallelForks'])
                self.assertEqual(2, budget['modules']['tools']['maxParallelForks'])

                bad_proc = subprocess.run(
                    [sys.executable, '-B', str(ci/entry), *selection, '--gradle-workers', '17', '--output', str(output)],
                    capture_output=True,
                )
                self.assertEqual(2, bad_proc.returncode, entry)

                for forks in (1, 2, 3, 4, 8):
                    code, stdout_text, stderr_text = invoke_inproc(
                        mod, [*selection, '--test-forks', str(forks), '--list', '--output', str(output)])
                    self.assertEqual(0, code, entry + stderr_text)
                    budget = json.loads(stdout_text)['testConcurrency']
                    self.assertEqual(forks, budget['requested'])
                    self.assertGreaterEqual(budget['maxWorkers'], forks)
                    self.assertLessEqual(budget['maxWorkers'], 16)
                    self.assertEqual(1, budget['modules']['pc']['maxParallelForks'])
                    self.assertLessEqual(budget['modules']['tools']['maxParallelForks'], forks)
                    self.assertFalse(output.exists())
                for forks in ('0', '5', '9', '-1', 'two'):
                    code, _, _ = invoke_inproc(mod, [*selection, '--test-forks', forks, '--output', str(output)])
                    self.assertEqual(2, code, entry)
                    self.assertFalse(output.exists())
                for invalid_gw in ('0', '17', '-1', 'many'):
                    code, _, _ = invoke_inproc(mod, [*selection, '--gradle-workers', invalid_gw, '--output', str(output)])
                    self.assertEqual(2, code, entry)
                    self.assertFalse(output.exists())

    def test_production_regression_is_in_fixed_public_selection(self):
        result = subprocess.run([sys.executable, '-B', str(Path(__file__).with_name('public_check.py')),
            '--suite', 'production-combat', '--kind', 'mechanism', '--list'],
            capture_output=True, text=True, encoding='utf8')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn('com.rustedwarfare.reforge.testing.ProducedWeaponArchiveTest',
                      json.loads(result.stdout)['tests']['tools'])

    def test_same_class_is_deduplicated_only_with_the_same_fixture(self):
        entry = Path(__file__).with_name('development_check.py')
        result = subprocess.run([sys.executable, '-B', str(entry), '--suite', 'platform-basics',
            '--suite', 'content-three-formats', '--suite', 'platform-basics', '--list'],
            capture_output=True, text=True, encoding='utf8')
        self.assertEqual(0, result.returncode, result.stderr)
        plan = json.loads(result.stdout)
        name = 'com.rustedwarfare.reforge.testing.PlatformSessionRunnerTest'
        contexts = [g['fixture'] for g in plan['groups'] if name in g['tests'].get('tools', [])]
        self.assertCountEqual(['inputs/content-movement-knowledge/P001-移动沙盒输入',
                              'inputs/three-format-tanks/ini'], contexts)
        self.assertEqual(2, len(plan['groups']))
        self.assertTrue(plan['deduplicated'])

    def test_duplicate_methods_skips_and_old_xml_cannot_establish_current_execution(self):
        with tempfile.TemporaryDirectory(prefix='reforge-developer-xml-') as temporary:
            folder = Path(temporary)
            leaf = folder/'client'; (leaf/'xml').mkdir(parents=True)
            (folder/'effective.json').write_text(json.dumps({'testForks':2, 'maxWorkers':2, 'tests':[
                {'task':':client:test', 'classes':['OwnedTest'], 'maxParallelForks':2, 'forkEvery':0,
                 'excludePatterns':[], 'commandLineIncludePatterns':[], 'fileIncludes':[], 'fileExcludes':[],
                 'junitOptions':{'includeTags':[], 'excludeTags':[], 'includeEngines':[], 'excludeEngines':[]}}]}), encoding='utf8')
            (folder/'task-executions.json').write_text(json.dumps([{'task':':client:test',
                'skipped':False, 'upToDate':False, 'noSource':False, 'failure':None}]), encoding='utf8')
            (leaf/'started.json').write_text('{"task": ":client:test", "millis": 1}', encoding='utf8')
            (leaf/'completed.json').write_text(json.dumps({'tests':2, 'failures':0, 'skipped':0}), encoding='utf8')
            (leaf/'xml/TEST-OwnedTest.xml').write_text(
                '<testsuite name="OwnedTest" tests="2" failures="0" errors="0" skipped="0">'
                '<testcase classname="OwnedTest" name="first"/><testcase classname="OwnedTest" name="first"/>'
                '</testsuite>', encoding='utf8')
            self.assertEqual('incomplete', collect_tests(folder, {'client':['OwnedTest']})['status'])
            xml = leaf/'xml/TEST-OwnedTest.xml'
            valid = xml.read_text(encoding='utf8').replace('name="first"/></testsuite>', 'name="second"/></testsuite>')
            xml.write_text(valid, encoding='utf8')
            (leaf/'methods.json').write_text(json.dumps([
                dict(className='OwnedTest', name=name, started=1, completed=2, result='SUCCESS')
                for name in ('first', 'second')]), encoding='utf8')
            (leaf/'expected-methods.json').write_text(json.dumps([
                dict(className='OwnedTest', name=name) for name in ('first','second')]), encoding='utf8')
            self.assertEqual('passed', collect_tests(folder, {'client':['OwnedTest']})['status'])
            # Listener, XML and completed agree but jointly omit an independently expected method.
            methods = leaf/'methods.json'; completed = leaf/'completed.json'
            full_methods, full_completed = methods.read_bytes(), completed.read_bytes()
            xml.write_text(valid.replace('tests="2"','tests="1"').replace('<testcase classname="OwnedTest" name="second"/>',''), encoding='utf8')
            methods.write_text(json.dumps([dict(className='OwnedTest',name='first',started=1,completed=2,result='SUCCESS')]),encoding='utf8')
            completed.write_text(json.dumps(dict(tests=1,failures=0,skipped=0)),encoding='utf8')
            self.assertEqual('incomplete', collect_tests(folder, {'client':['OwnedTest']})['status'])
            xml.write_text(valid,encoding='utf8'); methods.write_bytes(full_methods); completed.write_bytes(full_completed)
            discovery = leaf/'expected-methods.json'
            discovery.rename(leaf/'missing-expected.json')
            self.assertEqual('incomplete', collect_tests(folder, {'client':['OwnedTest']})['status'])
            (leaf/'missing-expected.json').rename(discovery)
            effective = folder/'effective.json'
            original_config = effective.read_bytes()
            for field in ('excludePatterns','commandLineIncludePatterns','fileIncludes','fileExcludes'):
                config = json.loads(original_config); config['tests'][0][field] = ['partial']
                effective.write_text(json.dumps(config),encoding='utf8')
                self.assertEqual('incomplete', collect_tests(folder, {'client':['OwnedTest']})['status'], field)
            for field in ('includeTags','excludeTags','includeEngines','excludeEngines'):
                config = json.loads(original_config); config['tests'][0]['junitOptions'][field] = ['partial']
                effective.write_text(json.dumps(config),encoding='utf8')
                self.assertEqual('incomplete', collect_tests(folder, {'client':['OwnedTest']})['status'], field)
            effective.write_bytes(original_config)
            (leaf/'methods.json').rename(leaf/'old-methods.json')
            self.assertEqual('incomplete', collect_tests(folder, {'client':['OwnedTest']})['status'])
            (leaf/'old-methods.json').rename(leaf/'methods.json')
            # Retain XML but omit this invocation's execution marker.
            (leaf/'started.json').rename(leaf/'retained-previous-start.json')
            self.assertEqual('incomplete', collect_tests(folder, {'client':['OwnedTest']})['status'])
            (leaf/'retained-previous-start.json').rename(leaf/'started.json')
            xml.write_text(valid.replace('name="second"/>', 'name="second"><skipped/></testcase>'), encoding='utf8')
            self.assertEqual('incomplete', collect_tests(folder, {'client':['OwnedTest']})['status'])
            xml.write_text(valid, encoding='utf8')
            executions = folder/'task-executions.json'
            executions.write_text(executions.read_text(encoding='utf8').replace('"upToDate": false', '"upToDate": true'), encoding='utf8')
            self.assertEqual('incomplete', collect_tests(folder, {'client':['OwnedTest']})['status'])
            executions.write_text(executions.read_text(encoding='utf8').replace('"upToDate": true', '"upToDate": false'), encoding='utf8')
            effective = folder/'effective.json'
            decoupled = json.loads(original_config)
            decoupled.update(testForks=3, gradleWorkers=8, maxWorkers=8,
                             moduleMaxParallelForks={':client:test': 3})
            decoupled['tests'][0]['maxParallelForks'] = 3
            effective.write_text(json.dumps(decoupled), encoding='utf8')
            self.assertEqual('passed', collect_tests(folder, {'client':['OwnedTest']})['status'])
            decoupled['maxWorkers'] = 7
            effective.write_text(json.dumps(decoupled), encoding='utf8')
            self.assertEqual('incomplete', collect_tests(folder, {'client':['OwnedTest']})['status'])
            decoupled['maxWorkers'] = 8
            decoupled['gradleWorkers'] = True
            effective.write_text(json.dumps(decoupled), encoding='utf8')
            self.assertEqual('incomplete', collect_tests(folder, {'client':['OwnedTest']})['status'])
            decoupled['gradleWorkers'] = 8
            (folder/'selection.json').write_text(json.dumps({'tasks': {':client:test': {'maxParallelForks': 2}}}), encoding='utf8')
            effective.write_text(json.dumps(decoupled), encoding='utf8')
            self.assertEqual('incomplete', collect_tests(folder, {'client':['OwnedTest']})['status'])
            (folder/'selection.json').unlink()
            effective.write_bytes(original_config)
            effective.write_text(effective.read_text(encoding='utf8').replace('"maxWorkers": 2', '"maxWorkers": 1'), encoding='utf8')
            self.assertEqual('incomplete', collect_tests(folder, {'client':['OwnedTest']})['status'])

    def test_invalid_registry_or_warm_isolation_does_not_create_a_result(self):
        entry = Path(__file__).with_name('development_check.py')
        with tempfile.TemporaryDirectory(prefix='reforge-developer-invalid-') as temporary:
            for suite, options in [('unknown', []), ('development-air-production', ['--isolated'])]:
                output = Path(temporary)/suite
                result = subprocess.run([sys.executable, '-B', str(entry), '--suite', suite,
                    '--output', str(output), *options], capture_output=True)
                self.assertEqual(2, result.returncode)
                self.assertFalse(output.exists())

    def test_concurrent_python_mechanisms_preserve_deterministic_order_and_admit_boundaries(self):
        import threading
        import time
        from unittest import mock
        import development_check

        scripts = ['tests/slow.py', 'tests/fast.py', 'tests/medium.py']
        delays = {'tests/slow.py': 0.25, 'tests/fast.py': 0.02, 'tests/medium.py': 0.10}
        plan = dict(
            groups=[],
            pythonTests=scripts,
            pythonOwners={path: ['tooling'] for path in scripts},
            deduplicated=[],
        )
        lock = threading.Lock()
        running = [0]
        peak = [0]
        admit_calls = [0]

        def fake_execute(argv, cwd, out_dir, timeout=600, env=None):
            out_dir.mkdir(parents=True, exist_ok=True)
            test_path = Path(argv[argv.index('--test') + 1])
            summary_path = Path(argv[argv.index('--output') + 1])
            rel = 'tests/' + test_path.name
            with lock:
                running[0] += 1
                peak[0] = max(peak[0], running[0])
            try:
                time.sleep(delays[rel])
            finally:
                with lock:
                    running[0] -= 1
            failed = rel == 'tests/fast.py' and fail_fast[0]
            summary_path.write_text(json.dumps(dict(
                status='failed' if failed else 'passed',
                tests=1,
                sourceUnchanged=True,
            )), encoding='utf8')
            return dict(passed=not failed, exitCode=1 if failed else 0, argv=argv)

        def fake_admit():
            admit_calls[0] += 1

        fail_fast = [False]
        with (tempfile.TemporaryDirectory(prefix='reforge-py-concurrent-') as temporary,
              mock.patch.object(development_check, 'identities', return_value=[]),
              mock.patch.object(development_check, 'toolchain_identity', return_value={'python': '3.13'}),
              mock.patch.object(development_check, 'execute_step', side_effect=fake_execute)):
            repo = Path(temporary) / 'repo'
            repo.mkdir()
            ok_out = Path(temporary) / 'ok'
            code = development_check.run_development(repo, ok_out, plan, False, forks=4, admit=fake_admit)
            self.assertEqual(0, code)
            self.assertGreaterEqual(peak[0], 2, 'Multiple Python mechanisms must overlap when forks > 1')
            self.assertEqual(2, admit_calls[0], 'Batch admit should run once before and once after Python mechanisms')
            receipt = json.loads((ok_out / 'result.json').read_text(encoding='utf8'))
            self.assertEqual('passed', receipt['status'])
            self.assertEqual(scripts, [row['path'] for row in receipt['pythonCoverage']])
            for idx in range(len(scripts)):
                self.assertTrue((ok_out / f'python-{idx}.json').is_file())

            fail_fast[0] = True
            admit_calls[0] = 0
            fail_out = Path(temporary) / 'fail'
            fail_code = development_check.run_development(repo, fail_out, plan, False, forks=4, admit=fake_admit)
            self.assertEqual(1, fail_code)
            self.assertEqual(2, admit_calls[0])
            fail_receipt = json.loads((fail_out / 'result.json').read_text(encoding='utf8'))
            self.assertEqual('execution-failed', fail_receipt['status'])
            self.assertIn('tests/fast.py', fail_receipt['error'])
            self.assertEqual(scripts, [row['path'] for row in fail_receipt['pythonCoverage']])


if __name__ == '__main__':
    unittest.main()
