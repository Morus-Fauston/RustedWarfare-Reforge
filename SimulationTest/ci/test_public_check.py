"""Public CLI selection checks; no build, private input or test-runner mocking."""
from concurrent.futures import ThreadPoolExecutor
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from contextlib import contextmanager


_CACHED_REPO_SOURCE_NAMES = None
_CACHED_REPO_META = None
_CACHED_PRODUCTION_COMBAT_PR = None


def setUpModule():
    global _CACHED_REPO_SOURCE_NAMES, _CACHED_REPO_META, _CACHED_PRODUCTION_COMBAT_PR
    from public_check import REPO, plan_suite, repo_metadata_snapshot
    _CACHED_REPO_META = repo_metadata_snapshot(REPO)
    _CACHED_REPO_SOURCE_NAMES = {row[0] for row in _CACHED_REPO_META}
    _CACHED_PRODUCTION_COMBAT_PR = plan_suite(REPO, 'production-combat', 'pr', _CACHED_REPO_SOURCE_NAMES)


def tearDownModule():
    global _CACHED_REPO_SOURCE_NAMES, _CACHED_REPO_META, _CACHED_PRODUCTION_COMBAT_PR
    _CACHED_REPO_SOURCE_NAMES = None
    _CACHED_REPO_META = None
    _CACHED_PRODUCTION_COMBAT_PR = None


def _populate_minimal_repo(repo):
    from public_check import _EXPLICIT_BUILD_FILES, _EXPLICIT_TOOL_NAMES, _EXPLICIT_DEC_TOOL_NAMES
    dirs = {repo / 'SimulationTest/tools', repo / 'Decompile/tools'}
    dirs.update((repo / rel).parent for rel in _EXPLICIT_BUILD_FILES)
    for d in dirs:
        d.mkdir(parents=True, exist_ok=True)
    for rel in _EXPLICIT_BUILD_FILES:
        (repo / rel).write_bytes(b'')
    for name in _EXPLICIT_TOOL_NAMES:
        (repo / 'SimulationTest/tools' / name).write_bytes(b'')
    for name in _EXPLICIT_DEC_TOOL_NAMES:
        (repo / 'Decompile/tools' / name).write_bytes(b'')


@contextmanager
def owned_scenario_processes(specs):
    """Control only external scenario processes; use the actual public executor and receipts."""
    import shlex
    from public_check import identities
    from public_distribution import seal
    with tempfile.TemporaryDirectory(prefix='reforge-scenario-queue-') as temporary:
        repo=Path(temporary)
        _populate_minimal_repo(repo)
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
if spec.get('delay'): time.sleep(spec['delay'])
row['ended']=time.time();(out/'lifecycle.json').write_text(json.dumps(row))
if spec.get('mutate'): pathlib.Path(__file__).with_name('changed-package.txt').write_text('owned mutation')
if spec.get('mutatePackageSameStat'):
 target=pathlib.Path(__file__)
 raw=target.read_bytes()
 assert len(raw)>0
 edited=(b'#' if raw[0:1]!=b'#' else b' ')+raw[1:]
 assert len(edited)==len(raw)
 st=target.stat()
 target.write_bytes(edited)
 import os
 os.utime(target, ns=(st.st_atime_ns, st.st_mtime_ns))
if spec.get('mutateContract'):
 target=pathlib.Path(value('--contract')); timestamps=target.stat()
 target.write_bytes(b'[]')
 import os
 os.utime(target, ns=(timestamps.st_atime_ns, timestamps.st_mtime_ns))
if spec.get('mutateComparator'):
 pathlib.Path(__file__).with_name('spider_platform.py').write_text('changed', encoding='utf8')
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
    def test_long_and_missing_representative_cannot_remove_scenario_coverage(self):
        from public_check import plan_suite

        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            public = root/'SimulationTest/public'
            (public/'suites').mkdir(parents=True)
            (public/'scenario.json').write_text('{}', encoding='utf8')
            (public/'contract.json').write_text('{}', encoding='utf8')
            (public/'scenario-equivalence.json').write_text('{"families":[]}', encoding='utf8')
            case = dict(id='example', scenario='scenario.json', contract='contract.json')
            for kind in ('long', 'short'):
                cases = [dict(case, equivalentTo='representative')]
                if kind == 'long':
                    cases.append(dict(case, id='representative'))
                suite = dict(schemaVersion=1, suiteId='owned', **{kind: cases})
                (public/'suites/owned.json').write_text(json.dumps(suite), encoding='utf8')
                with self.subTest(kind=kind), self.assertRaisesRegex(ValueError, 'Invalid Core short'):
                    plan_suite(root, 'owned', kind, set())
            suite['short'].append(dict(case, id='representative'))
            (public/'suites/owned.json').write_text(json.dumps(suite), encoding='utf8')
            with self.assertRaisesRegex(ValueError, 'Unregistered Core short'):
                plan_suite(root, 'owned', 'short', set())

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
                    if failure_recorded.wait(0.1):
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
                result = public_scenarios.run_scenarios(output, output, cases, output, 2, 5, lambda case: None, execute)
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
            _populate_minimal_repo(repo)
            ci=repo/'SimulationTest/ci';ci.mkdir(parents=True,exist_ok=True)
            for name in ('public_check.py','public_distribution.py','development_check.py','python_check.py','public_scenarios.py'):
                shutil.copyfile(REPO/'SimulationTest/ci'/name,ci/name)
            for name in ('workflow.py','workflow_inputs.py'):
                shutil.copyfile(REPO/'SimulationTest/tools'/name,repo/'SimulationTest/tools'/name)
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
                self.assertEqual(1,run(repo,output,plan,prepared,0.3,1,2))
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
        with owned_scenario_processes([{'delay':.01,'mutate':True,'barrier':True},
                                       {'delay':.05,'barrier':True},{}]) as (repo,plan,prepared):
            output=repo/'changed'
            self.assertEqual(1,run(repo,output,plan,prepared,10,1,2))
            result=json.loads((output/'result.json').read_text(encoding='utf8'))
            self.assertIn('members or dependency bytes',result['scenarioExecution']['firstError']['error'])
            self.assertEqual('not-started',result['scenarioExecution']['items'][2]['status'])
            self.assertFalse((output/'owned-2').exists())

    def test_scenario_receipt_measures_identity_checks_and_archive_work(self):
        from public_check import run
        from public_scenarios import session_timeout_seconds
        self.assertEqual(300, session_timeout_seconds({'kind': 'long'}, 900))
        self.assertEqual(300, session_timeout_seconds({'kind': 'long'}, 600))
        self.assertEqual(180, session_timeout_seconds({'kind': 'short'}, 600))
        self.assertEqual(10, session_timeout_seconds({'kind': 'long'}, 10))
        with owned_scenario_processes([{'backend': 'session'}]) as (repo, plan, prepared):
            output = repo/'timed'
            self.assertEqual(0, run(repo, output, plan, prepared, 10, 1, 1))
            result = json.loads((output/'result.json').read_text(encoding='utf8'))
            self.assertIn('--timeout', result['commands'][0]['argv'])
            self.assertGreater(result['identityChecks']['scenario']['count'], 0)
            self.assertGreaterEqual(result['identityChecks']['scenario']['elapsedSeconds'], 0)
            queue = result['scenarioExecution']
            self.assertGreater(queue['elapsedSeconds'], 0)
            row, = queue['items']
            self.assertGreaterEqual(row['archiveSeconds'], 0)
            self.assertGreater(row['commands'][1]['elapsedSeconds'], 0)

    def test_same_length_and_timestamp_contract_drift_stops_next_scenario(self):
        from public_check import run
        with owned_scenario_processes([{'mutateContract': True}, {}]) as (repo, plan, prepared):
            output = repo/'changed'
            self.assertEqual(1, run(repo, output, plan, prepared, 10, 1, 1))
            result = json.loads((output/'result.json').read_text(encoding='utf8'))
            queue = result['scenarioExecution']
            self.assertIn('Public scenario input changed', queue['firstError']['error'])
            self.assertEqual(['failed', 'not-started'], [row['status'] for row in queue['items']])
            self.assertEqual(0, queue['items'][0]['commands'][0]['exitCode'])

    def test_changed_session_comparator_dependency_prevents_independent_recompare(self):
        from public_check import run
        with owned_scenario_processes([{'backend': 'session', 'mutateComparator': True}, {}]) as (repo, plan, prepared):
            output = repo/'changed'
            self.assertEqual(1, run(repo, output, plan, prepared, 10, 1, 1))
            queue = json.loads((output/'scenario-execution.json').read_text(encoding='utf8'))
            self.assertIn('Public Session comparator changed: spider_platform.py', queue['firstError']['error'])
            self.assertEqual(['failed', 'not-started'], [row['status'] for row in queue['items']])
            self.assertEqual(['owned-0'], [command['name'] for command in queue['items'][0]['commands']])

    def test_standalone_package_drift_after_build_prevents_first_dispatch(self):
        import shutil
        from public_check import run
        with owned_scenario_processes([{}]) as (repo, plan, _):
            project = repo/'SimulationTest'
            package = project/'build/install/simulation-test'
            shutil.copytree(repo/'package', package)
            package_member = package/'bin/fixture.py'
            python_case = project/'ci/test_replace_package.py'
            python_case.parent.mkdir()
            python_case.write_text(
                'import unittest\nfrom pathlib import Path\n'
                'class ReplacePackage(unittest.TestCase):\n'
                f' def test_publish_replacement(self): Path({str(package_member)!r}).write_bytes(b"replacement")\n',
                encoding='utf8')
            from public_check import REPO
            shutil.copyfile(REPO/'SimulationTest/ci/python_check.py', project/'ci/python_check.py')
            (project/'gradlew').write_text('#!/bin/sh\nexit 0\n', encoding='utf8')
            (project/'gradlew.bat').write_text('@exit /b 0\n', encoding='utf8')
            plan['pythonTests'] = ['SimulationTest/ci/test_replace_package.py']
            output = repo/'changed'
            self.assertEqual(1, run(repo, output, plan, None, 10, 1, 1))
            queue = json.loads((output/'scenario-execution.json').read_text(encoding='utf8'))
            self.assertIn('Scenario distribution changed', queue['firstError']['error'])
            self.assertEqual('not-started', queue['items'][0]['status'])
            self.assertEqual([], queue['items'][0]['commands'])

    def test_standalone_same_stat_package_mutation_during_scenario_fails_final_byte_closeout(self):
        from public_check import run
        with owned_scenario_processes([{'mutatePackageSameStat': True}]) as (repo, plan, _):
            project = repo/'SimulationTest'
            package = project/'build/install/simulation-test'
            shutil.copytree(repo/'package', package)
            (project/'gradlew').write_text('#!/bin/sh\nexit 0\n', encoding='utf8')
            (project/'gradlew.bat').write_text('@exit /b 0\n', encoding='utf8')
            output = repo/'same-stat-package-tamper'
            self.assertEqual(1, run(repo, output, plan, None, 10, 1, 1))
            saved = json.loads((output/'result.json').read_text(encoding='utf8'))
            self.assertEqual('execution-failed', saved['status'])
            self.assertIn('Scenario distribution changed during verification', saved['error'])
            self.assertGreaterEqual(saved['identityChecks']['final-package']['count'], 1)

    def test_source_closure_and_metadata_snapshot_reject_symlinks_and_junctions(self):
        from unittest.mock import patch
        from public_check import identities, python_source_closure, repo_metadata_snapshot, source_files
        with tempfile.TemporaryDirectory(prefix='reforge-symlink-guard-') as tmp:
            base = Path(tmp)
            repo = base/'repo'
            _populate_minimal_repo(repo)
            outside = base/'outside'
            outside.mkdir()
            secret_py = outside/'injected.py'
            secret_py.write_text('# outside\n', encoding='utf8')
            ci_dir = repo/'SimulationTest/ci'
            ci_dir.mkdir(parents=True, exist_ok=True)
            (ci_dir/'legit.py').write_text('# ok\n', encoding='utf8')
            p003_dir = repo/'Decompile/tools/p003'
            p003_dir.mkdir(parents=True, exist_ok=True)
            (p003_dir/'legit_p003.py').write_text('# ok\n', encoding='utf8')

            # Verify baseline clean repo passes all four functions
            self.assertTrue(source_files(repo))
            self.assertTrue(identities(repo))
            self.assertTrue(repo_metadata_snapshot(repo))
            self.assertIn('SimulationTest/ci/legit.py', python_source_closure(repo))

            # 1) Real filesystem link/junction check (junction on Windows requires zero elevation; os.symlink on POSIX/DevMode)
            link_dir = ci_dir/'linked_subdir'
            created_real_link = False
            try:
                os.symlink(outside, link_dir, target_is_directory=True)
                created_real_link = True
            except OSError:
                if os.name == 'nt':
                    proc = subprocess.run(['cmd', '/c', 'mklink', '/J', str(link_dir), str(outside)], capture_output=True)
                    created_real_link = (proc.returncode == 0)
            if created_real_link:
                try:
                    for fn in (source_files, identities, repo_metadata_snapshot, python_source_closure):
                        with self.assertRaisesRegex(ValueError, 'symlink'):
                            fn(repo)
                finally:
                    if os.name == 'nt' and link_dir.exists():
                        os.rmdir(link_dir)
                    else:
                        link_dir.unlink(missing_ok=True)

            # 2) Deterministic entry-level & root-level symlink rejection across _SOURCE_TREE_ROOTS, p003, and explicit files
            real_is_symlink = Path.is_symlink
            for target_rel in ('SimulationTest/ci', 'Decompile/tools/p003', 'SimulationTest/tools/workflow.py'):
                target_path = repo / target_rel
                with patch.object(Path, 'is_symlink', autospec=True,
                                  side_effect=lambda self_p, tp=target_path: True if self_p == tp else real_is_symlink(self_p)):
                    for fn in (source_files, identities, repo_metadata_snapshot, python_source_closure):
                        with self.assertRaisesRegex(ValueError, 'symlink'):
                            fn(repo)
            build_path = repo / 'SimulationTest/build.gradle.kts'
            with patch.object(Path, 'is_symlink', autospec=True,
                              side_effect=lambda self_p, tp=build_path: True if self_p == tp else real_is_symlink(self_p)):
                for fn in (source_files, identities, repo_metadata_snapshot):
                    with self.assertRaisesRegex(ValueError, 'symlink'):
                        fn(repo)

    def test_scenario_failure_stops_dispatch_preserves_inflight_success_and_original_exit(self):
        from public_check import run
        with owned_scenario_processes([{'delay':.01,'fail':True,'barrier':True},
                                       {'delay':.05,'barrier':True},{}]) as (repo,plan,prepared):
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
        with owned_scenario_processes([{'delay':.05,'barrier':True},
                                       {'delay':.01,'barrier':True,'backend':'session'}]) as (repo,plan,prepared):
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
        from contextlib import redirect_stderr, redirect_stdout
        import io
        from unittest.mock import patch
        import public_batch
        import public_check
        expected_default_forks = public_check.default_test_forks()

        def invoke_inproc(mod, argv):
            out, err = io.StringIO(), io.StringIO()
            with patch.object(sys, 'argv', [mod.__file__, *argv]), redirect_stdout(out), redirect_stderr(err):
                try:
                    code = mod.main()
                except SystemExit as exc:
                    code = exc.code if isinstance(exc.code, int) else 1
            return code, out.getvalue(), err.getvalue()

        with tempfile.TemporaryDirectory(prefix='reforge-scenario-workers-') as temporary:
            output=Path(temporary)/'must-not-exist'
            scopes = [
                ('public_check.py', public_check, ['--suite','production-combat','--kind','pr']),
                ('public_batch.py', public_batch, ['--kind','pr']),
            ]
            # Real subprocess CLI contract smoke on public_batch.py (public_check.py real CLI is covered below)
            proc = subprocess.run(
                [sys.executable, '-B', str(Path(__file__).with_name('public_batch.py')), '--kind', 'pr', '--list'],
                capture_output=True, text=True,
            )
            self.assertEqual(0, proc.returncode, proc.stderr)
            self.assertEqual(2, json.loads(proc.stdout)['scenarioConcurrency']['requested'])
            for filename, mod, scope in scopes:
                for workers in (1,2,4,8):
                    code, stdout_text, stderr_text = invoke_inproc(
                        mod, [*scope, '--list', '--output', str(output), '--scenario-workers', str(workers)])
                    self.assertEqual(0, code, stderr_text)
                    plan = json.loads(stdout_text)
                    self.assertEqual(workers, plan['scenarioConcurrency']['requested'])
                    self.assertEqual('after-build-and-tests', plan['scenarioConcurrency']['phase'])
                    self.assertEqual(expected_default_forks, plan['testConcurrency']['requested'])
                for invalid in ('0','3','5','9','-1','two'):
                    code, _, _ = invoke_inproc(
                        mod, [*scope, '--output', str(output), '--scenario-workers', invalid])
                    self.assertEqual(2, code)
                self.assertFalse(output.exists())

    def test_prepared_pr_still_executes_selected_python_and_rejects_a_long_package(self):
        from public_check import run, identities
        from public_distribution import seal, artifact_identities
        with tempfile.TemporaryDirectory(prefix='reforge-pr-consumer-') as temporary:
            repo = Path(temporary)
            _populate_minimal_repo(repo)
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
        names=_CACHED_REPO_SOURCE_NAMES
        self.assertTrue('SimulationTest/tools/platform_windows.py' in names,'Shared native driver missing')
        self.assertTrue('SimulationTest/tools/owned_window.py' in names,'Owned capture/input guard missing')
        self.assertTrue('SimulationTest/tools/test_owned_window.py' in names,'Owned capture contract missing')
        self.assertTrue('SimulationTest/tools/action_layout.py' in names,'Drawn action locator missing')
        self.assertTrue('SimulationTest/tools/test_action_layout.py' in names,'Drawn action contract missing')

    def test_transport_contract_survives_an_isolated_source_copy(self):
        names=_CACHED_REPO_SOURCE_NAMES
        self.assertIn('SimulationTest/tools/test_transport_platform.py',names)

    def test_air_transport_uses_registered_public_mechanisms_and_all_three_format_scenarios(self):
        from public_check import REPO
        plan=_CACHED_PRODUCTION_COMBAT_PR
        self.assertIn('com.rustedwarfare.reforge.core.content.DropshipContentFailureTest',plan['tests']['core'])
        self.assertIn('com.rustedwarfare.reforge.client.DropshipProductionSandboxTest',plan['tests']['client'])
        self.assertIn('com.rustedwarfare.reforge.pc.DropshipOriginalProfileTest',plan['tests']['pc'])
        self.assertNotIn('com.rustedwarfare.reforge.testing.DropshipProductionSessionTest',plan['tests']['tools'])
        cases={row['id']:row for row in plan['scenarios']}
        for fmt in ('ini','json','hjson'):
            self.assertEqual('core',cases['air-transport-'+fmt+'-short']['backend'])
            self.assertEqual('session',cases['air-transport-'+fmt+'-production-chain']['backend'])
            contract = REPO/'SimulationTest/public'/cases['air-transport-'+fmt+'-production-chain']['contract']
            self.assertEqual('air-transport', json.loads(contract.read_bytes())['history']['productionSession'])
        names=_CACHED_REPO_SOURCE_NAMES
        for name in ('air_transport_fixture.py','air_transport_scenarios.py'):
            self.assertIn('SimulationTest/tools/'+name,names)

    def test_aa_beam_uses_the_public_mechanisms_and_three_format_core_and_session_contracts(self):
        from public_check import REPO
        plan=_CACHED_PRODUCTION_COMBAT_PR
        for module,name in [('core','core.sim.AaBeamAdmissionTest'),('client','client.AaBeamPresentationTest'),
                            ('pc','pc.AaBeamOriginalProfileTest')]:
            self.assertIn('com.rustedwarfare.reforge.'+name,plan['tests'][module])
        cases={row['id']:row for row in plan['scenarios']}
        for family, test in [('aa-beam', 'AaBeam'), ('air-transport', 'Dropship'), ('transport', 'LandingCraft')]:
            self.assertNotIn('com.rustedwarfare.reforge.testing.'+test+'ArchiveTest', plan['tests']['tools'])
            self.assertEqual(family, cases[family+'-json-short']['archiveAssertions'])
        for fmt in ('ini','json','hjson'):
            self.assertEqual('core',cases['aa-beam-'+fmt+'-short']['backend'])
            self.assertIs(cases['aa-beam-'+fmt+'-short']['recompare'], True)
            aa = 'aa-beam-'+fmt+'-production-chain'
            afterburn = cases['afterburn-'+fmt+'-production-chain']
            self.assertNotIn(aa, cases)
            public = REPO/'SimulationTest/public'
            old = json.loads((public/'platform/scenarios'/(aa+'.json')).read_bytes())
            new = json.loads((public/afterburn['scenario']).read_bytes())
            self.assertEqual(23, len(old['intents']))
            self.assertEqual(old['intents'], new['intents'][:23])
            self.assertEqual(old['inputs'], new['inputs'])
            self.assertEqual(old['waitPlan'], {name: new['waitPlan'][name] for name in old['waitPlan']})
            old_contract = json.loads((public/'platform/contracts'/(aa+'.json')).read_bytes())
            new_contract = json.loads((public/afterburn['contract']).read_bytes())
            self.assertEqual(old_contract['rules'], new_contract['rules'][:23])
            self.assertEqual(old_contract['history']['finalUnits'],
                             new_contract['history']['unitsAfterIntents']['stop-home'])
        names=_CACHED_REPO_SOURCE_NAMES
        for name in ('aa_beam_fixture.py','aa_beam_scenarios.py','test_aa_beam_archive.py'):
            self.assertIn('SimulationTest/tools/'+name,names)

    def test_afterburn_registers_actions_trail_and_all_three_normal_production_contracts(self):
        from public_check import REPO
        plan=_CACHED_PRODUCTION_COMBAT_PR
        for module,names in {
            'core':['core.content.AfterburnContentTest','core.sim.AfterburnActionTest','core.sim.AfterburnLifecycleTest'],
            'client':['client.AfterburnInputTest','client.AfterburnTrailTest'],
            'tools':['testing.AfterburnArchiveTest']}.items():
            for name in names:self.assertIn('com.rustedwarfare.reforge.'+name,plan['tests'][module])
        cases={row['id']:row for row in plan['scenarios']}
        for fmt in ('ini','json','hjson'):
            self.assertEqual('core',cases['afterburn-'+fmt+'-short']['backend'])
            self.assertEqual('session',cases['afterburn-'+fmt+'-production-chain']['backend'])
            contract = REPO/'SimulationTest/public'/cases['afterburn-'+fmt+'-production-chain']['contract']
            self.assertEqual('aa-afterburn', json.loads(contract.read_bytes())['history']['productionSession'])
        self.assertIn('SimulationTest/tools/test_afterburn_platform.py',plan['pythonTests'])
        names=_CACHED_REPO_SOURCE_NAMES
        self.assertIn('SimulationTest/tools/afterburn_scenarios.py',names)

    def test_bunker_registers_three_format_core_and_normal_session_chains_and_independent_checks(self):
        from public_check import REPO
        plan=_CACHED_PRODUCTION_COMBAT_PR
        for module,names in {
            'core':['core.content.BunkerContentTest','core.sim.BunkerActionTest','core.sim.BunkerWeaponTest',
                    'core.sim.BunkerFootprintTest','core.sim.BunkerHeightTest','core.sim.BunkerLifecycleTest',
                    'core.sim.BunkerProductionTest','core.sim.BunkerTransactionTest','core.sim.BunkerValidationTest'],
            'client':['client.BunkerProjectionTest','client.BunkerSandboxTest'],
            'pc':['pc.BunkerOriginalProfileTest'],
            'tools':['testing.BunkerArchiveTest','testing.BunkerProductionSessionTest']}.items():
            for name in names:self.assertIn('com.rustedwarfare.reforge.'+name,plan['tests'][module])
        cases={row['id']:row for row in plan['scenarios']}
        equivalence = json.loads((REPO/'SimulationTest/public/scenario-equivalence.json').read_bytes())
        bunker = next(row for row in equivalence['families'] if row['name'] == 'bunker-short')
        self.assertEqual('core', cases['bunker-json-short']['backend'])
        self.assertEqual(['bunker-json-short', 'bunker-ini-short', 'bunker-hjson-short'],
                         [member['id'] for member in bunker['members']])
        for fmt in ('ini','json','hjson'):
            self.assertEqual('session',cases['bunker-'+fmt+'-production-chain']['backend'])
        self.assertIn('SimulationTest/tools/test_bunker_platform.py',plan['pythonTests'])
        names=_CACHED_REPO_SOURCE_NAMES
        for name in ('bunker_fixture.py','bunker_scenarios.py','test_bunker_platform.py'):
            self.assertIn('SimulationTest/tools/'+name,names)

    def test_the_shared_live_reader_and_its_contract_survive_an_isolated_source_copy(self):
        names=_CACHED_REPO_SOURCE_NAMES
        self.assertTrue('SimulationTest/tools/diagnostic_reader.py' in names,'Shared live reader missing from isolated source')
        self.assertTrue('SimulationTest/tools/test_diagnostic_reader.py' in names,'Live reader contract missing from isolated source')

    def test_public_record_chain_fixture_is_in_the_isolated_source_closure(self):
        from public_check import REPO, python_source_closure
        names=_CACHED_REPO_SOURCE_NAMES
        self.assertIn('SimulationTest/fixtures/record-chain/scenario.json',names)
        self.assertIn('SimulationTest/fixtures/record-chain/contract.json',names)
        meta_names = [row[0] for row in _CACHED_REPO_META]
        self.assertEqual(sorted(names), meta_names)
        py_names = python_source_closure(REPO)
        self.assertTrue(py_names.issubset(names))
        for p in names:
            if p.startswith(('SimulationTest/ci/', 'SimulationTest/tools/', 'Decompile/tools/')) and p.endswith('.py'):
                self.assertIn(p, py_names)

    def test_portable_corner_and_failure_preservation_mechanisms_are_not_left_out(self):
        from contextlib import redirect_stdout
        import io
        from unittest.mock import patch
        import public_check
        for name, module, classes in [
            ('content-movement-knowledge', 'core', ['com.rustedwarfare.reforge.core.sim.CornerCaptureTest']),
            ('platform-basics', 'pc', ['com.rustedwarfare.reforge.pc.EvidenceInitializationFailureTest',
                                     'com.rustedwarfare.reforge.pc.FailurePreservationTest']),
        ]:
            out = io.StringIO()
            with patch.object(sys, 'argv', [public_check.__file__, '--suite', name, '--kind', 'mechanism', '--list']), redirect_stdout(out):
                self.assertEqual(0, public_check.main())
            for cls in classes:
                self.assertIn(cls, json.loads(out.getvalue())['tests'][module])

    def test_tooling_includes_complete_layer_contract_tests(self):
        from contextlib import redirect_stdout
        import io
        from unittest.mock import patch
        import public_check
        out = io.StringIO()
        with patch.object(sys, 'argv', [public_check.__file__, '--suite', 'tooling', '--kind', 'mechanism', '--list']), redirect_stdout(out):
            self.assertEqual(0, public_check.main())
        self.assertIn('SimulationTest/ci/test_public_batch.py', json.loads(out.getvalue())['pythonTests'])

    def test_tooling_plan_names_python_and_jvm_mechanisms_without_specialist_execution(self):
        from contextlib import redirect_stdout
        import io
        from unittest.mock import patch
        import public_check
        out = io.StringIO()
        with patch.object(sys, 'argv', [public_check.__file__, '--suite', 'tooling', '--kind', 'mechanism', '--list']), redirect_stdout(out):
            self.assertEqual(0, public_check.main())
        plan = json.loads(out.getvalue())
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
            from contextlib import redirect_stderr, redirect_stdout
            import io
            from unittest.mock import patch
            import public_check

            for name, fields in cases:
                manifest = dict(schemaVersion=1, suiteId=name, inputScope='public', **fields)
                (registry / (name + '.json')).write_text(json.dumps(manifest), encoding='utf8')
                output = repo / ('must-not-create-' + name)
                out, err = io.StringIO(), io.StringIO()
                with (patch.object(public_check, 'REPO', repo),
                      patch.object(sys, 'argv', [str(copied), '--suite', name, '--kind', 'all', '--list', '--output', str(output)]),
                      redirect_stdout(out), redirect_stderr(err)):
                    with self.assertRaises(SystemExit) as ctx:
                        public_check.main()
                self.assertEqual(2, ctx.exception.code, name + out.getvalue() + err.getvalue())
                self.assertFalse(output.exists())
            # Also verify real subprocess entrypoint rejection on path escape
            escaped = subprocess.run([sys.executable, '-B', str(copied), '--suite', '../escape', '--list'], capture_output=True)
            self.assertEqual(2, escaped.returncode)


if __name__ == '__main__':
    unittest.main()
