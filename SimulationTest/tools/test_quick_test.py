"""Unit tests for SimulationTest/tools/quick_test.py."""
from contextlib import redirect_stdout
import io
import json
import os
from pathlib import Path
import sys
import tempfile
import types
import unittest
from unittest import mock

TOOLS = Path(__file__).resolve().parent
if str(TOOLS) not in sys.path:
    sys.path.insert(0, str(TOOLS))

import quick_test


class QuickTestRunnerTest(unittest.TestCase):
    def make_repo(self, root, suites=('build-economy', 'tooling')):
        (root / 'SimulationTest').mkdir(parents=True)
        (root / 'SimulationTest' / 'build.gradle.kts').write_text('// stub\n', encoding='utf8')
        (root / quick_test.PUBLIC_P001_RELATIVE).mkdir(parents=True)
        suites_dir = root / 'SimulationTest' / 'public' / 'suites'
        suites_dir.mkdir(parents=True)
        for name in suites:
            (suites_dir / f'{name}.json').write_text('{"schemaVersion":1}\n', encoding='utf8')
        return root

    def test_default_test_forks_maps_to_allowed_ci_values(self):
        with mock.patch.object(quick_test.os, 'cpu_count', return_value=None):
            self.assertEqual(1, quick_test.default_test_forks(None))
        self.assertEqual(1, quick_test.default_test_forks(0))
        self.assertEqual(1, quick_test.default_test_forks(1))
        self.assertEqual(1, quick_test.default_test_forks(2))
        self.assertEqual(1, quick_test.default_test_forks(3))
        self.assertEqual(2, quick_test.default_test_forks(4))
        self.assertEqual(2, quick_test.default_test_forks(6))
        self.assertEqual(4, quick_test.default_test_forks(8))
        self.assertEqual(4, quick_test.default_test_forks(16))
        for allowed in (1, 2, 3, 4, 8):
            self.assertEqual(allowed, quick_test.parse_test_forks(str(allowed)))
        for gw in (1, 4, 8, 12, 16):
            self.assertEqual(gw, quick_test.parse_gradle_workers(str(gw)))
        with self.assertRaises(quick_test.argparse.ArgumentTypeError):
            quick_test.parse_test_forks('9')
        with self.assertRaises(quick_test.argparse.ArgumentTypeError):
            quick_test.parse_gradle_workers('17')

    def test_high_core_eight_forks_propagates_to_core_and_suites_while_keeping_pc_serial(self):
        with tempfile.TemporaryDirectory() as tmp:
            repo = self.make_repo(Path(tmp) / 'wt')
            plan = quick_test.build_plan(
                repo,
                core=['OpenGroundPlanningTest'],
                pc=['WindowsLaunchOptionsTest'],
                suites=['tooling'],
                forks=8,
                gradle_workers=10,
                is_windows=True,
                cloud=False,
            )
            self.assertEqual(8, plan['testForks'])
            self.assertEqual(10, plan['gradleWorkers'])
            argv = plan['steps'][0]['argv']
            self.assertIn('--max-workers=10', argv)
            # Active modules are core + pc -> pc=1, core=7 so total resident JUnit JVMs <= 8
            self.assertIn('-Pcore.test.maxParallelForks=7', argv)
            self.assertIn('-Ptools.test.maxParallelForks=1', argv)
            self.assertNotIn('-Ppc.test.maxParallelForks=8', argv)
            suite_argv = plan['steps'][1]['argv']
            self.assertEqual('8', suite_argv[suite_argv.index('--test-forks') + 1])
            self.assertEqual('10', suite_argv[suite_argv.index('--gradle-workers') + 1])

            cloud_plan = quick_test.build_plan(
                repo,
                core=['OpenGroundPlanningTest'],
                pc=['WindowsLaunchOptionsTest'],
                forks=8,
                gradle_workers=10,
                is_windows=True,
                cloud=True,
            )
            self.assertIn('-Pcore.test.maxParallelForks=8', cloud_plan['steps'][0]['argv'])

            single_mod = quick_test.build_plan(
                repo, core=['OpenGroundPlanningTest'], forks=8, gradle_workers=10, is_windows=True, cloud=False)
            self.assertIn('-Pcore.test.maxParallelForks=8', single_mod['steps'][0]['argv'])

            all_four = quick_test.build_plan(
                repo,
                core=['OpenGroundPlanningTest'],
                client=['ConstructionQTest'],
                pc=['WindowsLaunchOptionsTest'],
                tools=['StreamTraceEvaluationTest'],
                forks=8,
                gradle_workers=12,
                is_windows=True,
                cloud=False,
            )
            all_argv = all_four['steps'][0]['argv']
            self.assertIn('-Pcore.test.maxParallelForks=3', all_argv)
            self.assertIn('-Pclient.test.maxParallelForks=2', all_argv)
            self.assertIn('-Ptools.test.maxParallelForks=2', all_argv)

            ci_dir = Path(__file__).resolve().parents[1] / 'ci'
            if str(ci_dir) not in sys.path:
                sys.path.insert(0, str(ci_dir))
            import public_check
            for subset in [('core',), ('core', 'pc'), ('core', 'client', 'tools'), ('core', 'client', 'tools', 'pc')]:
                for cap in (1, 2, 3, 4, 8):
                    self.assertEqual(
                        public_check.allocate_module_test_forks(subset, cap, is_cloud=False),
                        quick_test.allocate_module_test_forks(subset, cap, is_cloud=False),
                    )

    def test_targeted_client_and_core_plan_uses_warm_simulation_root_and_public_fixture(self):
        with tempfile.TemporaryDirectory() as tmp:
            repo = self.make_repo(Path(tmp) / 'wt')
            plan = quick_test.build_plan(
                repo,
                core=['OpenGroundPlanningTest'],
                client=['com.rustedwarfare.reforge.client.ConstructionQTest'],
                forks=4,
                rerun=True,
                is_windows=True,
                cloud=False,
            )
            self.assertEqual(4, plan['testForks'])
            self.assertEqual(1, len(plan['steps']))
            step = plan['steps'][0]
            self.assertEqual('targeted-tests', step['name'])
            self.assertEqual('L1', step['layer'])
            self.assertEqual(str((repo / 'SimulationTest').resolve()), step['cwd'])
            argv = step['argv']
            self.assertTrue(argv[0].endswith('gradlew.bat'))
            self.assertIn(':core:test', argv)
            self.assertIn(':client:test', argv)
            self.assertIn('--tests', argv)
            self.assertIn('OpenGroundPlanningTest', argv)
            self.assertIn('com.rustedwarfare.reforge.client.ConstructionQTest', argv)
            self.assertIn('--daemon', argv)
            self.assertIn('--rerun', argv)
            self.assertIn('-Pcore.test.maxParallelForks=4', argv)
            self.assertIn('-Pclient.test.maxParallelForks=4', argv)
            self.assertNotIn('--rerun-tasks', argv)
            self.assertNotIn('--no-daemon', argv)
            self.assertNotIn('--stop', argv)
            expected_prop = f'-Dpublic.inputs.dir={(repo / quick_test.PUBLIC_P001_RELATIVE).resolve()}'
            self.assertIn(expected_prop, argv)

    def test_combined_plan_orders_l1_before_suites_before_dev_ab_in_temp_root(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp)
            repo = self.make_repo(base / 'wt')
            temp_out = base / 'tmp-out'
            plan = quick_test.build_plan(
                repo,
                pc=['WindowsLaunchOptionsTest'],
                suites=['build-economy', 'tooling'],
                dev_ab=True,
                forks=2,
                temp_root=temp_out,
                is_windows=False,
            )
            self.assertEqual(['targeted-tests', 'mechanism-suites', 'dev-ab'],
                             [s['name'] for s in plan['steps']])
            self.assertEqual('sh', plan['steps'][0]['argv'][0])
            suite_step = plan['steps'][1]
            self.assertEqual(str(repo.resolve()), suite_step['cwd'])
            self.assertIn('development_check.py', suite_step['argv'][2])
            self.assertIn('--test-forks', suite_step['argv'])
            self.assertTrue(Path(suite_step['output']).is_relative_to(temp_out.resolve()))
            dev_ab_step = plan['steps'][2]
            self.assertEqual('L2', dev_ab_step['layer'])
            self.assertIn(':movementProbe', dev_ab_step['argv'])
            self.assertTrue(Path(dev_ab_step['output']).is_relative_to(temp_out.resolve()))

    def test_dev_ab_quotes_paths_with_spaces_and_rejects_unsafe_characters(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp)
            repo = self.make_repo(base / 'wt')
            spaced = base / 'John Doe Temp'
            for is_windows in (True, False):
                plan = quick_test.build_plan(
                    repo,
                    dev_ab=True,
                    forks=2,
                    temp_root=spaced,
                    is_windows=is_windows,
                )
                step = plan['steps'][0]
                expected_out = step['output']
                self.assertIn('John Doe Temp', expected_out)
                self.assertIn(f"--args=--scene dev-ab --assert true --output '{expected_out}'", step['argv'])

            self.assertEqual("'a'\"'\"'b c'", quick_test.quote_gradle_arg("a'b c", is_windows=False))
            with self.assertRaises(ValueError):
                quick_test.quote_gradle_arg('C:\\Users\\bad"dir\\out', is_windows=True)
            with self.assertRaises(ValueError):
                quick_test.quote_gradle_arg("C:\\Users\\bad'dir\\out", is_windows=True)
            with self.assertRaises(ValueError):
                quick_test.quote_gradle_arg('bad\npath', is_windows=False)

    def test_relative_temp_root_resolves_to_absolute_paths_for_all_steps(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp).resolve()
            repo = self.make_repo(base / 'wt')
            caller_cwd = base / 'caller'
            caller_cwd.mkdir()
            previous_cwd = Path.cwd()
            try:
                os.chdir(caller_cwd)
                plan = quick_test.build_plan(
                    repo,
                    suites=['build-economy'],
                    dev_ab=True,
                    forks=2,
                    temp_root=Path('rel-tmp'),
                    is_windows=True,
                )
            finally:
                os.chdir(previous_cwd)

            expected_root = (caller_cwd / 'rel-tmp').resolve()
            for step in plan['steps']:
                out_path = Path(step['output'])
                self.assertTrue(out_path.is_absolute())
                self.assertTrue(out_path.is_relative_to(expected_root))
                self.assertFalse(out_path.is_relative_to(repo))
                self.assertTrue(any(str(out_path) in token for token in step['argv']))

    def test_rejects_empty_selection_invalid_filter_or_unknown_suite(self):
        with tempfile.TemporaryDirectory() as tmp:
            repo = self.make_repo(Path(tmp) / 'wt')
            with self.assertRaises(ValueError):
                quick_test.build_plan(repo)
            with self.assertRaises(ValueError):
                quick_test.build_plan(repo, client=['bad filter with space'])
            with self.assertRaises(ValueError):
                quick_test.build_plan(repo, core=['--rerun-tasks'])
            with self.assertRaises(ValueError):
                quick_test.build_plan(repo, suites=['build-economy', '   '])
            with self.assertRaises(ValueError):
                quick_test.build_plan(repo, suites=['non-existent-suite'])

    def test_cli_dry_run_and_list_print_json_without_executing(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp)
            repo = self.make_repo(base / 'wt')
            temp_out = base / 'dry-tmp'
            for flag in ('--dry-run', '--list'):
                buf = io.StringIO()
                with redirect_stdout(buf):
                    code = quick_test.main([
                        '--repo', str(repo),
                        '--client', 'ConstructionQTest',
                        '--temp-root', str(temp_out),
                        flag,
                    ])
                self.assertEqual(0, code)
                payload = json.loads(buf.getvalue())
                self.assertEqual('targeted-tests', payload['steps'][0]['name'])
                self.assertFalse(temp_out.exists())

    def test_run_plan_stops_at_first_failure_and_preserves_suite_new_output_dir(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp)
            repo = self.make_repo(base / 'wt')
            temp_out = base / 'tmp-out'
            plan = quick_test.build_plan(
                repo,
                core=['OpenGroundPlanningTest'],
                suites=['tooling'],
                dev_ab=True,
                forks=2,
                temp_root=temp_out,
                is_windows=True,
            )
            called = []

            def fake_runner(argv, cwd):
                called.append((argv, cwd))
                if len(called) == 2:
                    # development_check requires --output not to exist beforehand.
                    out_path = Path(argv[argv.index('--output') + 1])
                    self.assertFalse(out_path.exists())
                    return types.SimpleNamespace(returncode=1)
                return types.SimpleNamespace(returncode=0)

            summary = quick_test.run_plan(plan, runner=fake_runner)
            self.assertEqual('failed', summary['status'])
            self.assertEqual('mechanism-suites', summary['failedStep'])
            self.assertEqual(2, len(called))


if __name__ == '__main__':
    unittest.main()
