"""Installed real JVM adapter checks; run after :installDist, never substitute synthetic rows."""
import os
import tempfile
import unittest
from pathlib import Path
import json
import shutil
import hashlib


class PlatformSessionCliTest(unittest.TestCase):
    def distribution(self, root):
        directory = os.environ.get('REFORGE_SESSION_TEST_DISTRIBUTION')
        return Path(directory) if directory else root/'SimulationTest/build/install/simulation-test'

    def test_partial_or_rebound_real_session_archive_is_rejected(self):
        from platform_scenario import run_session, compare_archive, PlatformError
        root=Path(__file__).resolve().parents[2]
        public=root/'SimulationTest/public/platform'
        with tempfile.TemporaryDirectory(prefix='platform-archive-negative-') as folder:
            base=Path(folder)/'positive'
            result=run_session(public/'scenarios/production.json',public/'contracts/production.json',
                               self.distribution(root),base)
            self.assertEqual(0,result['exitCode'],result)
            for fault in ('empty','duplicate','omit-diagnostic','missing-manifest','missing-input','extra-file',
                          'same-length-input',
                          'rebound-scenario','rebound-contract','rebound-input','backend'):
                with self.subTest(fault=fault):
                    altered=Path(folder)/fault
                    shutil.copytree(base,altered)
                    archive=json.loads((altered/'archive.json').read_bytes())
                    if fault=='empty': archive['files']=[]
                    elif fault=='duplicate': archive['files'].append(archive['files'][0])
                    elif fault=='omit-diagnostic': archive['files']=[r for r in archive['files'] if r['path']!='session/client-diagnostic.jsonl']
                    elif fault=='missing-manifest':(altered/'manifest.json').unlink()
                    elif fault=='missing-input':(altered/'inputSnapshot/production.properties').unlink()
                    elif fault=='extra-file':(altered/'extra.txt').write_bytes(b'extra')
                    elif fault=='same-length-input':
                        path = altered/'inputSnapshot/production.properties'
                        times = path.stat()
                        data = path.read_bytes()
                        path.write_bytes(bytes([data[0] ^ 1]) + data[1:])
                        os.utime(path, ns=(times.st_atime_ns, times.st_mtime_ns))
                    elif fault=='backend':archive['backend']='invented'
                    else:
                        manifest=json.loads((altered/'manifest.json').read_bytes())
                        if fault=='rebound-input':manifest['inputFiles'][0]['sha256']='0'*64
                        else:manifest[('scenario' if fault=='rebound-scenario' else 'contract')+'Sha256']='0'*64
                        (altered/'manifest.json').write_text(json.dumps(manifest), encoding='utf8')
                        next(r for r in archive['files'] if r['path']=='manifest.json')['sha256']=hashlib.sha256((altered/'manifest.json').read_bytes()).hexdigest()
                    (altered/'archive.json').write_text(json.dumps(archive), encoding='utf8')
                    with self.assertRaises(PlatformError):compare_archive(altered)

    def test_installed_adapter_freezes_inputs_and_compares_real_production_calls(self):
        from platform_scenario import run_session
        root = Path(__file__).resolve().parents[2]
        public = root / 'SimulationTest/public/platform'
        with tempfile.TemporaryDirectory(prefix='platform-cli-') as folder:
            output = Path(folder) / 'run'
            result = run_session(public / 'scenarios/production.json', public / 'contracts/production.json',
                                 self.distribution(root), output)
            self.assertEqual(0, result['exitCode'], result)
            self.assertEqual(7, len(result['judgments']))
            self.assertTrue((output / 'inputSnapshot/production.properties').is_file())
            self.assertTrue((output / 'session/client-diagnostic.jsonl').is_file())
            self.assertTrue((output / 'manifest.json').is_file())

    def test_read_only_session_skips_input_copytree_passes_standalone_compare_cli_and_detects_tamper(self):
        import subprocess
        import sys
        import time
        from platform_scenario import run_session
        root = Path(__file__).resolve().parents[2]
        public = root / 'SimulationTest/public/platform'
        tool_script = Path(__file__).with_name('platform_scenario.py')
        with tempfile.TemporaryDirectory(prefix='platform-ro-cli-') as folder:
            tmp = Path(folder)
            staged_root = tmp / 'staged'
            shutil.copytree(
                root / 'SimulationTest/public/inputs/platform-basics/production',
                staged_root / 'inputs/platform-basics/production',
            )
            (staged_root / 'platform/scenarios').mkdir(parents=True)
            (staged_root / 'platform/contracts').mkdir(parents=True)
            scen = staged_root / 'platform/scenarios/production.json'
            cont = staged_root / 'platform/contracts/production.json'
            shutil.copyfile(public / 'scenarios/production.json', scen)
            shutil.copyfile(public / 'contracts/production.json', cont)

            output = tmp / 'ro-run'
            before_unix = time.time() - 1.0
            result = run_session(scen, cont, self.distribution(root), output, read_only_inputs=True)
            self.assertEqual(0, result['exitCode'], result)
            self.assertFalse((output / 'inputSnapshot').exists())
            manifest = json.loads((output / 'manifest.json').read_text(encoding='utf8'))
            self.assertEqual(
                str((staged_root / 'inputs/platform-basics/production').resolve()),
                manifest['readOnlyInputRoot'],
            )

            # Standalone platform_scenario.py compare CLI contract check
            cli_ok = subprocess.run(
                [sys.executable, '-B', str(tool_script), 'compare',
                 '--run', str(output), '--scenario', str(scen), '--contract', str(cont),
                 '--started-after', str(before_unix)],
                capture_output=True, text=True, timeout=30,
            )
            self.assertEqual(0, cli_ok.returncode, cli_ok.stdout + cli_ok.stderr)
            self.assertEqual('passed', json.loads(cli_ok.stdout)['status'])

            # Same-length mutation of read-only input must fail standalone compare CLI
            ro_prop = staged_root / 'inputs/platform-basics/production/production.properties'
            st = ro_prop.stat()
            raw = ro_prop.read_bytes()
            ro_prop.write_bytes(bytes([raw[0] ^ 1]) + raw[1:])
            os.utime(ro_prop, ns=(st.st_atime_ns, st.st_mtime_ns))
            cli_bad = subprocess.run(
                [sys.executable, '-B', str(tool_script), 'compare',
                 '--run', str(output), '--scenario', str(scen), '--contract', str(cont)],
                capture_output=True, text=True, timeout=30,
            )
            self.assertNotEqual(0, cli_bad.returncode)
            self.assertEqual('archive-identity', json.loads(cli_bad.stdout)['category'])

    def test_persistent_worker_pool_rotates_at_cap_recovers_after_timeout_and_stops_on_failure(self):
        import sys
        import time
        root = Path(__file__).resolve().parents[2]
        sys.path.insert(0, str(root / 'SimulationTest/ci'))
        sys.path.insert(0, str(root / 'SimulationTest/tools'))
        from public_scenarios import PersistentScenarioWorkerPool, run_scenarios
        from workflow import process_create_time

        dist = self.distribution(root)
        scen_ini = root / 'SimulationTest/public/scenarios/content-three-formats-ini.json'
        cont_ini = root / 'SimulationTest/public/contracts/content-three-formats-ini.json'
        scen_long = root / 'SimulationTest/public/scenarios/phase-five-ini.json'
        cont_long = root / 'SimulationTest/public/contracts/phase-five-ini.json'

        with tempfile.TemporaryDirectory(prefix='persistent-worker-pool-') as folder:
            tmp = Path(folder)
            pids_to_verify_dead = set()
            with PersistentScenarioWorkerPool(root, dist, workers=1, max_tasks_per_worker=2) as pool:
                r1 = pool.execute_core_step(
                    tmp, 'step-1', ['simulation-test', 'run'],
                    dict(cmd='core-run', scenario=str(scen_ini), contract=str(cont_ini), output=str(tmp / 'step-1')),
                    timeout=60,
                )
                r2 = pool.execute_core_step(
                    tmp, 'step-2', ['simulation-test', 'run'],
                    dict(cmd='core-run', scenario=str(scen_ini), contract=str(cont_ini), output=str(tmp / 'step-2')),
                    timeout=60,
                )
                self.assertTrue(r1['passed'], r1)
                self.assertTrue(r2['passed'], r2)
                self.assertEqual(r1['pid'], r2['pid'])
                self.assertEqual(1, r1['workerGeneration'])
                self.assertEqual(1, r2['workerGeneration'])
                first_pid = r1['pid']
                pids_to_verify_dead.add(first_pid)

                # Reaching max_tasks_per_worker=2 must rotate to generation 2 with a fresh JVM
                r3 = pool.execute_core_step(
                    tmp, 'step-3', ['simulation-test', 'run'],
                    dict(cmd='core-run', scenario=str(scen_ini), contract=str(cont_ini), output=str(tmp / 'step-3')),
                    timeout=60,
                )
                self.assertTrue(r3['passed'], r3)
                self.assertEqual(2, r3['workerGeneration'])
                self.assertNotEqual(first_pid, r3['pid'])
                self.assertIsNone(process_create_time(first_pid))

                # Timeout on a long scenario must kill the wedged worker and recover cleanly on generation 3
                gen2_pid = r3['pid']
                pids_to_verify_dead.add(gen2_pid)
                r_timeout = pool.execute_core_step(
                    tmp, 'step-timeout', ['simulation-test', 'run'],
                    dict(cmd='core-run', scenario=str(scen_long), contract=str(cont_long), output=str(tmp / 'step-timeout')),
                    timeout=0.05,
                )
                self.assertFalse(r_timeout['passed'])
                self.assertEqual(124, r_timeout['exitCode'])
                self.assertEqual('timeout', r_timeout['firstError']['kind'])
                self.assertIsNone(process_create_time(gen2_pid))

                r4 = pool.execute_core_step(
                    tmp, 'step-4', ['simulation-test', 'run'],
                    dict(cmd='core-run', scenario=str(scen_ini), contract=str(cont_ini), output=str(tmp / 'step-4')),
                    timeout=60,
                )
                self.assertTrue(r4['passed'], r4)
                self.assertEqual(3, r4['workerGeneration'])
                pids_to_verify_dead.add(r4['pid'])

                # Verify GIL-isolated Python session evaluation helper via execute_session_step
                pub_plat = root / 'SimulationTest/public/platform'
                r_sess = pool.execute_session_step(
                    tmp, 'step-sess', ['python', 'platform_scenario.py', 'session'],
                    pub_plat / 'scenarios/production.json', pub_plat / 'contracts/production.json',
                    60, 60,
                )
                self.assertTrue(r_sess['passed'], r_sess)
                self.assertFalse((tmp / 'step-sess/inputSnapshot').exists())
                sess_judgment = json.loads((tmp / 'step-sess/result.json').read_text(encoding='utf8'))
                self.assertEqual('passed', sess_judgment['status'])

            for pid in pids_to_verify_dead:
                self.assertIsNone(process_create_time(pid))

            # Early-stop on first failure in run_scenarios with persistent pool enabled
            bad_repo = tmp / 'bad-suite'
            pub = bad_repo / 'SimulationTest/public'
            shutil.copytree(root / 'SimulationTest/public/inputs/three-format-tanks', pub / 'inputs/three-format-tanks')
            shutil.copytree(root / 'SimulationTest/public/inputs/production-combat', pub / 'inputs/production-combat')
            (pub / 'scenarios').mkdir(parents=True)
            (pub / 'contracts').mkdir(parents=True)
            shutil.copyfile(scen_ini, pub / 'scenarios/s.json')
            bad_contract = json.loads(cont_ini.read_text(encoding='utf8'))
            bad_contract['rules'][0]['assertions'][0]['value'] = 9999
            (pub / 'contracts/bad.json').write_text(json.dumps(bad_contract), encoding='utf8')
            shutil.copyfile(cont_ini, pub / 'contracts/good.json')
            out_dir = tmp / 'early-stop-out'
            out_dir.mkdir()
            cases = [
                dict(id='bad-first', kind='short', scenario='scenarios/s.json', contract='contracts/bad.json'),
                dict(id='never-run', kind='short', scenario='scenarios/s.json', contract='contracts/good.json'),
            ]
            exec_res = run_scenarios(
                bad_repo, out_dir, cases, dist, 1, 60, lambda c: None, lambda *a: None,
                use_worker_pool=True,
            )
            self.assertEqual('execution-failed', exec_res['status'])
            self.assertEqual(['failed', 'not-started'], [r['status'] for r in exec_res['items']])
            self.assertEqual(2, exec_res['firstError']['cause']['exitCode'])
            self.assertFalse((out_dir / 'never-run').exists())


if __name__ == '__main__':
    unittest.main()
