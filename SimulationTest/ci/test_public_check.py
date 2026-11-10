"""Public CLI selection checks; no build, private input or test-runner mocking."""
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from contextlib import contextmanager


@contextmanager
def owned_scenario_processes(specs):
    """Control only external scenario processes; use the actual public executor and receipts."""
    import os, shutil, shlex
    from public_check import source_files, identities
    from public_distribution import seal
    with tempfile.TemporaryDirectory(prefix='reforge-scenario-queue-') as temporary:
        repo=Path(temporary)
        for path in source_files(repo):
            path.parent.mkdir(parents=True,exist_ok=True);path.write_text('',encoding='utf8')
        package=repo/'package';(package/'bin').mkdir(parents=True)
        program='''import json,pathlib,subprocess,sys,time
args=sys.argv[1:]
def value(key): return args[args.index(key)+1]
if args[0]=='compare':
 out=pathlib.Path(value('--run')); row=json.loads((out/'lifecycle.json').read_text())
 assert row['ended']>row['started']; row['compared']=time.time()
 (out/'lifecycle.json').write_text(json.dumps(row)); raise SystemExit(0)
spec=json.loads(pathlib.Path(value('--scenario')).read_text())
out=pathlib.Path(value('--output'));out.mkdir(parents=True)
row={'started':time.time()};(out/'lifecycle.json').write_text(json.dumps(row))
if spec.get('barrier'):
 stop=time.monotonic()+3
 while len(list(out.parent.glob('*/lifecycle.json')))<2 and time.monotonic()<stop: time.sleep(.01)
if spec.get('child'):
 child=subprocess.Popen([sys.executable,'-c','import time;time.sleep(30)'])
 (out/'child.pid').write_text(str(child.pid))
time.sleep(spec.get('delay',.1))
row['ended']=time.time();(out/'lifecycle.json').write_text(json.dumps(row))
if spec.get('mutate'): pathlib.Path(__file__).with_name('changed-package.txt').write_text('owned mutation')
if spec.get('fail'): raise SystemExit(7)
if spec.get('noarchive'): raise SystemExit(0)
if args[0]=='session': (out/'result.json').write_text(json.dumps({'status':'passed','exitCode':0}))
else:
 run=out/'run-owned';(run/'comparison').mkdir(parents=True)
 (run/'comparison/result.json').write_text(json.dumps({'exitCode':0}))
 (run/'manifest.json').write_text(json.dumps({'complete':not spec.get('incomplete',False)}))
'''
        (package/'bin/fixture.py').write_text(program,encoding='utf8')
        (package/'bin/simulation-test.bat').write_text('@echo off\n"'+sys.executable+'" -B "%~dp0fixture.py" %*\n',encoding='utf8')
        (package/'bin/simulation-test').write_text('#!/bin/sh\nexec '+shlex.quote(sys.executable)+' -B "$(dirname "$0")/fixture.py" "$@"\n',encoding='utf8')
        (repo/'SimulationTest/tools/platform_scenario.py').write_text(program,encoding='utf8')
        cases=[]
        for index,spec in enumerate(specs):
            relative='scenarios/owned-'+str(index)+'.json';path=repo/'SimulationTest/public'/relative
            path.parent.mkdir(parents=True,exist_ok=True);path.write_text(json.dumps(spec),encoding='utf8')
            contract=path.with_name('contract-'+str(index)+'.json');contract.write_text('{}',encoding='utf8')
            cases.append(dict(id='owned-'+str(index),kind='long',backend=spec.get('backend','core'),
                              scenario=relative,contract=contract.relative_to(repo/'SimulationTest/public').as_posix()))
        plan=dict(suiteId='owned-processes',kind='long',inputScope='public',fixture=None,tests={},pythonTests=[],scenarios=cases)
        receipt=repo/'distribution.json';pin=seal(package,receipt,identities(repo))
        yield repo,plan,(receipt,pin)


class PublicSelectionTest(unittest.TestCase):
    def test_failure_and_dispatch_share_a_boundary_and_queued_work_does_not_start_after_failure(self):
        import threading
        import time
        from concurrent.futures import ThreadPoolExecutor
        from types import SimpleNamespace
        from unittest.mock import patch
        import public_scenarios

        submitting_second = threading.Event()
        failure_recorded = threading.Event()
        late_submissions, calls = [], []

        class StopSignal:
            def is_set(self):
                return failure_recorded.is_set()

            def set(self):
                failure_recorded.set()

        class DelayedAcceptancePool:
            """Exercise a slow executor acceptance and a submitted, not-yet-started job."""
            def __init__(self, max_workers):
                self.pool = ThreadPoolExecutor(max_workers=max_workers)
                self.count = 0

            def __enter__(self):
                self.pool.__enter__()
                return self

            def __exit__(self, *args):
                return self.pool.__exit__(*args)

            def submit(self, work, index):
                self.count += 1
                if self.count == 2:
                    submitting_second.set()
                    # If failure can win while this acceptance is pending, record it.
                    # A synchronized dispatch keeps failure outside this boundary;
                    # the timeout only bounds that deliberately blocked interleaving.
                    if failure_recorded.wait(1):
                        late_submissions.append(index)

                    def start_after_failure():
                        if not failure_recorded.wait(5):
                            raise AssertionError('The controlled first failure was not recorded')
                        return work(index)
                    return self.pool.submit(start_after_failure)
                return self.pool.submit(work, index)

        def execute(output, name, argv, cwd, timeout):
            calls.append(name)
            if name == 'first':
                if not submitting_second.wait(5):
                    raise AssertionError('The controlled second acceptance did not begin')
                return dict(argv=argv, passed=False, exitCode=7, elapsedSeconds=0,
                    startedUnix=time.time(), endedUnix=time.time(),
                    firstError=dict(kind='process-exit', exitCode=7))
            run = output/name/'run-owned'
            (run/'comparison').mkdir(parents=True)
            (run/'comparison/result.json').write_text('{"exitCode":0}', encoding='utf8')
            (run/'manifest.json').write_text('{"complete":true}', encoding='utf8')
            return dict(argv=argv, passed=True, exitCode=0, elapsedSeconds=0,
                startedUnix=time.time(), endedUnix=time.time(), firstError=None)

        with tempfile.TemporaryDirectory(prefix='reforge-stop-dispatch-boundary-') as temporary:
            output = Path(temporary)
            cases = [dict(id=name, kind='long', scenario='owned.json', contract='owned.json')
                     for name in ('first', 'later')]
            signals = SimpleNamespace(Lock=threading.Lock, Event=StopSignal)
            with patch.object(public_scenarios, 'threading', signals), \
                    patch.object(public_scenarios, 'ThreadPoolExecutor', DelayedAcceptancePool):
                result = public_scenarios.run_scenarios(output, output, cases, output, 2, 5, lambda: None, execute)
            self.assertEqual(['failed', 'not-started'], [r['status'] for r in result['items']])
            self.assertEqual(['first'], calls)
            self.assertEqual([], late_submissions, 'An executor accepted work after the first failure was recorded')
            self.assertEqual([], result['items'][1]['commands'])
            self.assertFalse((output/'later').exists())
            self.assertEqual(7, result['firstError']['cause']['exitCode'])

    def test_cli_releases_build_locks_before_python_consumers_without_bypassing_other_owners(self):
        import shutil
        from public_check import REPO, identities, plan_suite, source_files
        from public_distribution import artifact_identities, seal
        sys.path.insert(0,str(REPO/'SimulationTest/tools'))
        from workflow import resources
        with tempfile.TemporaryDirectory(prefix='reforge-python-lock-phase-') as temporary:
            repo=Path(temporary).resolve()
            for path in source_files(repo):
                path.parent.mkdir(parents=True,exist_ok=True);path.write_text('',encoding='utf8')
            shutil.copytree(REPO/'SimulationTest/ci',repo/'SimulationTest/ci',dirs_exist_ok=True,
                            ignore=shutil.ignore_patterns('__pycache__'))
            for name in ('workflow.py','workflow_inputs.py'):
                shutil.copyfile(REPO/'SimulationTest/tools'/name,repo/'SimulationTest/tools'/name)
            ci=repo/'SimulationTest/ci'
            (ci/'test_owned_lock.py').write_text(
                'import pathlib,sys,unittest\n'
                'root=pathlib.Path(__file__).resolve().parents[2]\n'
                'sys.path.insert(0,str(root/"SimulationTest/tools"))\n'
                'from workflow import resources\n'
                'class OwnLock(unittest.TestCase):\n'
                ' def test_real_nested_consumer(self):\n'
                '  with resources(["build:"+str(root/"Engine"),"build:"+str(root/"SimulationTest")]): pass\n',encoding='utf8')
            registry=repo/'SimulationTest/public/suites';registry.mkdir(parents=True,exist_ok=True)
            (registry/'owned-lock.json').write_text(json.dumps(dict(schemaVersion=1,suiteId='owned-lock',
                tests={},pythonTests=['SimulationTest/ci/test_owned_lock.py'])),encoding='utf8')
            plan=plan_suite(repo,'owned-lock','pr')
            package=repo/'package';package.mkdir();(package/'owned.jar').write_bytes(b'owned')
            compiled=repo/'compiled.bin';compiled.write_bytes(b'owned compiled fixture')
            receipt=repo/'prepared.json'
            pin=seal(package,receipt,identities(repo),kind='pr',preparation=dict(repo=str(repo),
                selection=[plan],tasks=[':installDist'],executions=[dict(task=':installDist',failure=None)],
                artifacts=artifact_identities([compiled])))
            entry=[sys.executable,'-B',str(ci/'public_check.py'),'--suite','owned-lock','--kind','pr']
            accepted=repo/'accepted'
            result=subprocess.run(entry+['--output',str(accepted),'--distribution-receipt',str(receipt),
                '--distribution-sha256',pin],capture_output=True,timeout=30)
            self.assertEqual(0,result.returncode,result.stdout+result.stderr)
            measured=json.loads((accepted/'result.json').read_text(encoding='utf8'))
            self.assertEqual(1,measured['pythonCoverage'][0]['tests'])
            # A different live owner must still prevent the actual build phase.
            refused=repo/'refused'
            with resources(['build:'+str(repo/'Engine'),'build:'+str(repo/'SimulationTest')]):
                result=subprocess.run(entry+['--output',str(refused)],capture_output=True,timeout=30)
            self.assertNotEqual(0,result.returncode)
            saved=refused/'result.json'
            diagnostic=saved.read_text(encoding='utf8') if saved.exists() else (result.stdout+result.stderr).decode('utf8',errors='replace')
            self.assertIn('Resource occupied',diagnostic)
            if saved.exists():self.assertEqual([],json.loads(diagnostic)['commands'])

    def test_successful_process_without_complete_archive_is_a_failed_scenario(self):
        from public_check import run
        for spec in ({'noarchive':True},{'incomplete':True}):
            with self.subTest(spec=spec),owned_scenario_processes([spec,{}]) as (repo,plan,prepared):
                output=repo/'result'
                self.assertEqual(1,run(repo,output,plan,prepared,10,1,1))
                result=json.loads((output/'result.json').read_text(encoding='utf8'))
                self.assertEqual(['failed','not-started'],[r['status'] for r in result['scenarioExecution']['items']])
                self.assertEqual(0,result['scenarioExecution']['items'][0]['commands'][0]['exitCode'])
                self.assertEqual([],result['archives'])

    def test_timeout_cleans_real_owned_child_and_never_starts_remaining_scenario(self):
        import os
        from public_check import run
        def alive(pid):
            if os.name=='nt':
                import ctypes
                k=ctypes.WinDLL('kernel32',use_last_error=True)
                k.OpenProcess.restype=ctypes.c_void_p
                k.WaitForSingleObject.argtypes=[ctypes.c_void_p,ctypes.c_ulong]
                k.CloseHandle.argtypes=[ctypes.c_void_p]
                handle=k.OpenProcess(0x100000,False,pid)
                if not handle:return False
                try:return k.WaitForSingleObject(handle,0)==258
                finally:k.CloseHandle(handle)
            stat=Path('/proc')/str(pid)/'stat'
            if stat.exists() and stat.read_text().rsplit(')',1)[1].split()[0]=='Z':return False
            try:os.kill(pid,0);return True
            except ProcessLookupError:return False
        with owned_scenario_processes([{'delay':30,'child':True},{'delay':30},{}]) as (repo,plan,prepared):
            output=repo/'result';pid=None
            try:
                self.assertEqual(1,run(repo,output,plan,prepared,1,1,2))
                queue=json.loads((output/'scenario-execution.json').read_text(encoding='utf8'))
                self.assertEqual('timeout',queue['firstError']['cause']['kind'])
                self.assertEqual(['failed','failed','not-started'],[r['status'] for r in queue['items']])
                pid=int((output/'owned-0/child.pid').read_text())
                self.assertFalse(alive(pid),'The real scenario child escaped timeout tree cleanup')
                self.assertFalse((output/'owned-2').exists())
            finally:
                if pid and alive(pid):
                    if os.name=='nt':subprocess.run(['taskkill','/PID',str(pid),'/T','/F'],capture_output=True,timeout=10)
                    else:
                        import signal
                        os.kill(pid,signal.SIGKILL)

    def test_conflicting_session_outputs_are_refused_before_any_process_and_package_drift_stops_dispatch(self):
        from public_check import run
        with owned_scenario_processes([{'backend':'session'},{}]) as (repo,plan,prepared):
            plan['scenarios'][1]['id']='owned-0-recompare'
            output=repo/'conflict'
            self.assertEqual(1,run(repo,output,plan,prepared,10,1,2))
            result=json.loads((output/'result.json').read_text(encoding='utf8'))
            self.assertEqual([],result['commands'])
            self.assertEqual(['not-started','not-started'],[r['status'] for r in result['scenarioExecution']['items']])
        with owned_scenario_processes([{'delay':.05,'mutate':True,'barrier':True},
                                       {'delay':.3,'barrier':True},{}]) as (repo,plan,prepared):
            output=repo/'changed'
            self.assertEqual(1,run(repo,output,plan,prepared,10,1,2))
            result=json.loads((output/'result.json').read_text(encoding='utf8'))
            self.assertIn('members or dependency bytes',result['scenarioExecution']['firstError']['error'])
            self.assertEqual('not-started',result['scenarioExecution']['items'][2]['status'])
            self.assertFalse((output/'owned-2').exists())

    def test_scenario_failure_stops_dispatch_preserves_inflight_success_and_original_exit(self):
        from public_check import run
        with owned_scenario_processes([{'delay':.05,'fail':True,'barrier':True},
                                       {'delay':.3,'barrier':True},{}]) as (repo,plan,prepared):
            output=repo/'result'
            self.assertEqual(1,run(repo,output,plan,prepared,20,1,2))
            result=json.loads((output/'result.json').read_text(encoding='utf8'))
            queue=result['scenarioExecution']
            self.assertEqual(['failed','passed','not-started'],[r['status'] for r in queue['items']])
            self.assertEqual(7,queue['firstError']['cause']['exitCode'])
            self.assertEqual('process-exit',queue['firstError']['cause']['kind'])
            self.assertFalse((output/'owned-2').exists())
            self.assertEqual(['owned-1'],[r['id'] for r in result['archives']])

    def test_parallel_scenarios_keep_registration_order_and_session_compare_inside_its_item(self):
        from public_check import run
        with owned_scenario_processes([{'delay':.3,'barrier':True},
                                       {'delay':.05,'barrier':True,'backend':'session'}]) as (repo,plan,prepared):
            output=repo/'result'
            self.assertEqual(0,run(repo,output,plan,prepared,20,1,2))
            result=json.loads((output/'result.json').read_text(encoding='utf8'))
            self.assertEqual(['owned-0','owned-1'],[r['id'] for r in result['archives']])
            first=json.loads((output/'owned-0/lifecycle.json').read_text())
            second=json.loads((output/'owned-1/lifecycle.json').read_text())
            self.assertLess(second['started'],first['ended'],'Independent scenario processes must overlap')
            self.assertGreaterEqual(second['compared'],second['ended'])
            self.assertEqual(['passed','passed'],[r['status'] for r in result['scenarioExecution']['items']])
            self.assertEqual(2,result['scenarioExecution']['peakRunning'])

    def test_scenario_workers_are_bounded_and_listing_keeps_build_and_scenario_phases_separate(self):
        with tempfile.TemporaryDirectory(prefix='reforge-scenario-workers-') as temporary:
            output=Path(temporary)/'must-not-exist'
            for filename,scope in [('public_check.py',['--suite','production-combat','--kind','pr']),
                                   ('public_batch.py',['--kind','pr'])]:
                entry=Path(__file__).with_name(filename)
                default=subprocess.run([sys.executable,'-B',str(entry),*scope,'--list'],capture_output=True,text=True)
                self.assertEqual(0,default.returncode,default.stderr)
                self.assertEqual(2,json.loads(default.stdout)['scenarioConcurrency']['requested'])
                for workers in (1,2,4):
                    result=subprocess.run([sys.executable,'-B',str(entry),*scope,'--list',
                        '--output',str(output),'--scenario-workers',str(workers)],capture_output=True,text=True)
                    self.assertEqual(0,result.returncode,result.stderr)
                    plan=json.loads(result.stdout)
                    self.assertEqual(workers,plan['scenarioConcurrency']['requested'])
                    self.assertEqual('after-build-and-tests',plan['scenarioConcurrency']['phase'])
                    self.assertEqual(1,plan['testConcurrency']['requested'])
                for invalid in ('0','3','5','-1','two'):
                    result=subprocess.run([sys.executable,'-B',str(entry),*scope,'--output',str(output),
                        '--scenario-workers',invalid],capture_output=True)
                    self.assertEqual(2,result.returncode)
                self.assertFalse(output.exists())

    def test_prepared_pr_still_executes_selected_python_and_rejects_a_long_package(self):
        import shutil
        from public_check import run, source_files, identities
        from public_distribution import seal, artifact_identities
        with tempfile.TemporaryDirectory(prefix='reforge-pr-consumer-') as temporary:
            repo = Path(temporary)
            for path in source_files(repo):
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text('', encoding='utf8')
            ci = repo/'SimulationTest/ci'
            ci.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(Path(__file__).with_name('python_check.py'), ci/'python_check.py')
            test = ci/'test_owned.py'
            test.write_text('import unittest\nclass Current(unittest.TestCase):\n def test_current(self): self.assertEqual(2,1+1)\n', encoding='utf8')
            package = repo/'package'; package.mkdir()
            (package/'app.jar').write_bytes(b'owned package fixture; no JVM launch')
            compiled = repo/'compiled'; compiled.mkdir()
            (compiled/'owned.bin').write_bytes(b'owned preparation identity fixture')
            plan = dict(suiteId='owned', kind='pr', inputScope='public', fixture=None,
                        tests={}, pythonTests=['SimulationTest/ci/test_owned.py'], scenarios=[])
            preparation = dict(repo=str(repo.resolve()), selection=[plan], tasks=[':installDist'],
                executions=[dict(task=':installDist', failure=None)], artifacts=artifact_identities([compiled]))
            source = identities(repo)
            pr = repo/'pr.json'; pr_pin = seal(package, pr, source, kind='pr', preparation=preparation)
            output = repo/'accepted'
            self.assertEqual(0, run(repo, output, plan, (pr, pr_pin)))
            result = json.loads((output/'result.json').read_text(encoding='utf8'))
            self.assertEqual(1, result['pythonCoverage'][0]['tests'])
            self.assertTrue(result['commands'][0]['argv'])
            long = repo/'long.json'; long_pin = seal(package, long, source)
            refused = repo/'refused'
            self.assertEqual(1, run(repo, refused, plan, (long, long_pin)))
            self.assertEqual([], json.loads((refused/'result.json').read_text(encoding='utf8'))['commands'])

    def test_native_driver_and_capture_contract_are_in_the_isolated_source_closure(self):
        from public_check import REPO,source_files
        names={p.relative_to(REPO).as_posix() for p in source_files(REPO)}
        self.assertTrue('SimulationTest/tools/platform_windows.py' in names,'Shared native driver missing')
        self.assertTrue('SimulationTest/tools/owned_window.py' in names,'Owned capture/input guard missing')
        self.assertTrue('SimulationTest/tools/test_owned_window.py' in names,'Owned capture contract missing')
        self.assertTrue('SimulationTest/tools/action_layout.py' in names,'Drawn action locator missing')
        self.assertTrue('SimulationTest/tools/test_action_layout.py' in names,'Drawn action contract missing')

    def test_transport_contract_survives_an_isolated_source_copy(self):
        from public_check import REPO,source_files
        names={p.relative_to(REPO).as_posix() for p in source_files(REPO)}
        self.assertIn('SimulationTest/tools/test_transport_platform.py',names)

    def test_air_transport_uses_registered_public_mechanisms_and_all_three_format_scenarios(self):
        from public_check import REPO,plan_suite,source_files
        plan=plan_suite(REPO,'production-combat','pr')
        self.assertIn('com.rustedwarfare.reforge.core.content.DropshipContentFailureTest',plan['tests']['core'])
        self.assertIn('com.rustedwarfare.reforge.client.DropshipProductionSandboxTest',plan['tests']['client'])
        self.assertIn('com.rustedwarfare.reforge.pc.DropshipOriginalProfileTest',plan['tests']['pc'])
        self.assertIn('com.rustedwarfare.reforge.testing.DropshipProductionSessionTest',plan['tests']['tools'])
        cases={row['id']:row for row in plan['scenarios']}
        for fmt in ('ini','json','hjson'):
            self.assertEqual('core',cases['air-transport-'+fmt+'-short']['backend'])
            self.assertEqual('session',cases['air-transport-'+fmt+'-production-chain']['backend'])
        names={p.relative_to(REPO).as_posix() for p in source_files(REPO)}
        for name in ('air_transport_fixture.py','air_transport_scenarios.py'):
            self.assertIn('SimulationTest/tools/'+name,names)

    def test_aa_beam_uses_the_public_mechanisms_and_three_format_core_and_session_contracts(self):
        from public_check import REPO,plan_suite,source_files
        plan=plan_suite(REPO,'production-combat','pr')
        for module,name in [('core','core.sim.AaBeamAdmissionTest'),('client','client.AaBeamPresentationTest'),
                            ('pc','pc.AaBeamOriginalProfileTest'),('tools','testing.AaBeamArchiveTest')]:
            self.assertIn('com.rustedwarfare.reforge.'+name,plan['tests'][module])
        cases={row['id']:row for row in plan['scenarios']}
        for fmt in ('ini','json','hjson'):
            self.assertEqual('core',cases['aa-beam-'+fmt+'-short']['backend'])
            self.assertEqual('session',cases['aa-beam-'+fmt+'-production-chain']['backend'])
        names={p.relative_to(REPO).as_posix() for p in source_files(REPO)}
        for name in ('aa_beam_fixture.py','aa_beam_scenarios.py','test_aa_beam_archive.py'):
            self.assertIn('SimulationTest/tools/'+name,names)

    def test_afterburn_registers_actions_trail_and_all_three_normal_production_contracts(self):
        from public_check import REPO,plan_suite,source_files
        plan=plan_suite(REPO,'production-combat','pr')
        for module,names in {
            'core':['core.content.AfterburnContentTest','core.sim.AfterburnActionTest','core.sim.AfterburnLifecycleTest'],
            'client':['client.AfterburnInputTest','client.AfterburnTrailTest'],
            'tools':['testing.AfterburnArchiveTest','testing.AfterburnProductionSessionTest']}.items():
            for name in names:self.assertIn('com.rustedwarfare.reforge.'+name,plan['tests'][module])
        cases={row['id']:row for row in plan['scenarios']}
        for fmt in ('ini','json','hjson'):
            self.assertEqual('core',cases['afterburn-'+fmt+'-short']['backend'])
            self.assertEqual('session',cases['afterburn-'+fmt+'-production-chain']['backend'])
        self.assertIn('SimulationTest/tools/test_afterburn_platform.py',plan['pythonTests'])
        names={p.relative_to(REPO).as_posix() for p in source_files(REPO)}
        self.assertIn('SimulationTest/tools/afterburn_scenarios.py',names)

    def test_bunker_registers_three_format_core_and_normal_session_chains_and_independent_checks(self):
        from public_check import REPO,plan_suite,source_files
        plan=plan_suite(REPO,'production-combat','pr')
        for module,names in {
            'core':['core.content.BunkerContentTest','core.sim.BunkerActionTest','core.sim.BunkerWeaponTest',
                    'core.sim.BunkerFootprintTest','core.sim.BunkerHeightTest','core.sim.BunkerLifecycleTest',
                    'core.sim.BunkerProductionTest','core.sim.BunkerTransactionTest','core.sim.BunkerValidationTest'],
            'client':['client.BunkerProjectionTest','client.BunkerSandboxTest'],
            'pc':['pc.BunkerOriginalProfileTest'],
            'tools':['testing.BunkerArchiveTest','testing.BunkerProductionSessionTest']}.items():
            for name in names:self.assertIn('com.rustedwarfare.reforge.'+name,plan['tests'][module])
        cases={row['id']:row for row in plan['scenarios']}
        for fmt in ('ini','json','hjson'):
            self.assertEqual('core',cases['bunker-'+fmt+'-short']['backend'])
            self.assertEqual('session',cases['bunker-'+fmt+'-production-chain']['backend'])
        self.assertIn('SimulationTest/tools/test_bunker_platform.py',plan['pythonTests'])
        names={p.relative_to(REPO).as_posix() for p in source_files(REPO)}
        for name in ('bunker_fixture.py','bunker_scenarios.py','test_bunker_platform.py'):
            self.assertIn('SimulationTest/tools/'+name,names)

    def test_the_shared_live_reader_and_its_contract_survive_an_isolated_source_copy(self):
        from public_check import REPO,source_files
        names={p.relative_to(REPO).as_posix() for p in source_files(REPO)}
        self.assertTrue('SimulationTest/tools/diagnostic_reader.py' in names,'Shared live reader missing from isolated source')
        self.assertTrue('SimulationTest/tools/test_diagnostic_reader.py' in names,'Live reader contract missing from isolated source')

    def test_public_record_chain_fixture_is_in_the_isolated_source_closure(self):
        from public_check import REPO, source_files
        names={p.relative_to(REPO).as_posix() for p in source_files(REPO)}
        self.assertIn('SimulationTest/fixtures/record-chain/scenario.json',names)
        self.assertIn('SimulationTest/fixtures/record-chain/contract.json',names)

    def test_portable_corner_and_failure_preservation_mechanisms_are_not_left_out(self):
        for name, module, classes in [
            ('content-movement-knowledge', 'core', ['com.rustedwarfare.reforge.core.sim.CornerCaptureTest']),
            ('platform-basics', 'pc', ['com.rustedwarfare.reforge.pc.EvidenceInitializationFailureTest',
                                     'com.rustedwarfare.reforge.pc.FailurePreservationTest']),
        ]:
            result = subprocess.run([sys.executable, '-B', str(Path(__file__).with_name('public_check.py')),
                '--suite', name, '--kind', 'mechanism', '--list'], capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stderr)
            for cls in classes:
                self.assertIn(cls, json.loads(result.stdout)['tests'][module])

    def test_tooling_includes_complete_layer_contract_tests(self):
        result = subprocess.run([sys.executable, '-B', str(Path(__file__).with_name('public_check.py')),
            '--suite', 'tooling', '--kind', 'mechanism', '--list'], capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn('SimulationTest/ci/test_public_batch.py', json.loads(result.stdout)['pythonTests'])

    def test_tooling_plan_names_python_and_jvm_mechanisms_without_specialist_execution(self):
        result = subprocess.run([sys.executable, '-B', str(Path(__file__).with_name('public_check.py')),
            '--suite', 'tooling', '--kind', 'mechanism', '--list'], capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)
        plan = json.loads(result.stdout)
        self.assertIn('com.rustedwarfare.reforge.testing.cross.CrossComparisonTest', plan['tests']['tools'])
        self.assertIn('SimulationTest/tools/test_workflow.py', plan['pythonTests'])
        self.assertIn('Decompile/tools/p003/test_original_input_transform.py', plan['pythonTests'])
        self.assertNotIn('SimulationTest/tools/test_original_preparation_v2.py', plan['pythonTests'])
        self.assertEqual([], plan['scenarios'])

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
            for filename in ('scenario.json', 'contract.json'):
                (registry.parent / filename).write_text('{}', encoding='utf8')
            cases = [
                ('empty', {'tests': {}}),
                ('invalid-module', {'tests': {'shell': ['Run']}}),
                ('python-private', {'pythonTests': ['SimulationTest/tools/test_original_preparation_v2.py']}),
                ('python-escape', {'pythonTests': ['../private/test_original.py']}),
                ('python-duplicate', {'pythonTests': ['SimulationTest/ci/test_public_check.py'] * 2}),
                ('missing-scenario', {'short': [{'id': 'missing', 'scenario': 'absent.json', 'contract': 'absent-contract.json'}]}),
                ('escaping-fixture', {'fixture': '../../../secret', 'tests': {'core': ['ValidTest']}}),
                ('invalid-backend', {'short': [{'id': 'unsupported', 'backend': 'windows-input',
                    'scenario': 'scenario.json', 'contract': 'contract.json'}]}),
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
