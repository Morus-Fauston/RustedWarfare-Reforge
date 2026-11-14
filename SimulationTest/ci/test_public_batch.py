"""Fixed public layer CLI contracts; listing never starts a build or writes output."""
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
import os


class PublicBatchTest(unittest.TestCase):
    def test_batch_mechanisms_keep_class_and_script_owners_with_distinct_fixtures(self):
        from development_check import plan_mechanisms

        selections = [
            ('one', dict(inputScope='public', fixture='a', tests={'core': ['Shared', 'OnlyOne']}, pythonTests=['first.py'])),
            ('two', dict(inputScope='public', fixture='a', tests={'core': ['Shared']}, pythonTests=['first.py', 'second.py'])),
            ('three', dict(inputScope='public', fixture='b', tests={'core': ['Shared']}, pythonTests=['second.py'])),
        ]
        plan = plan_mechanisms(selections)
        self.assertEqual([('a', {'core': ['Shared', 'OnlyOne']}), ('b', {'core': ['Shared']})],
                         [(group['fixture'], group['tests']) for group in plan['groups']])
        self.assertEqual({'Shared': ['one', 'two'], 'OnlyOne': ['one']}, plan['groups'][0]['owners']['core'])
        self.assertEqual({'Shared': ['three']}, plan['groups'][1]['owners']['core'])
        self.assertEqual({'first.py': ['one', 'two'], 'second.py': ['two', 'three']}, plan['pythonOwners'])

    def test_bounded_command_timeout_kills_the_spawned_tree_and_stops_the_batch(self):
        from public_check import REPO,source_files
        with tempfile.TemporaryDirectory(prefix='reforge-layer-timeout-') as tmp:
            repo=Path(tmp)
            # Minimal authored source closure, with the actual process executor.
            for source in source_files(REPO):
                relative=source.relative_to(REPO)
                if str(relative).replace('\\','/') not in {
                    'SimulationTest/ci/public_batch.py','SimulationTest/ci/public_check.py',
                    'SimulationTest/ci/development_check.py','SimulationTest/ci/public_distribution.py',
                    'SimulationTest/tools/workflow.py','SimulationTest/tools/workflow_inputs.py'}:
                    if source.name not in {'gradlew','gradlew.bat','build.gradle.kts','settings.gradle.kts','gradle.properties'}:continue
                target=repo/relative;target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(source,target)
            # Explicit nonimported identity members may be empty in this CLI-only fixture.
            for source in source_files(REPO):
                target=repo/source.relative_to(REPO)
                if not target.exists() and source.parent==REPO/'SimulationTest/tools':
                    target.parent.mkdir(parents=True,exist_ok=True);target.write_text('',encoding='utf8')
            for name in ['verify_dec_evidence.py','test_verify_dec_evidence.py']:
                p=repo/'Decompile/tools'/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text('',encoding='utf8')
            suites=repo/'SimulationTest/public/suites';suites.mkdir(parents=True)
            for name in ['content-movement-knowledge','production-combat','build-economy','maintenance-defense','platform-basics','content-three-formats','tooling']:
                (suites/(name+'.json')).write_text(json.dumps(dict(schemaVersion=1,suiteId=name,inputScope='public',tests={'tools':['ProbeTest']})),encoding='utf8')
            worker=repo/'worker.py'
            worker.write_text("import subprocess,sys,time\nfrom pathlib import Path\np=subprocess.Popen([sys.executable,'-c','import time;time.sleep(30)'])\nPath(__file__).with_name('child.pid').write_text(str(p.pid))\ntime.sleep(30)\n",encoding='utf8')
            (repo/'SimulationTest/gradlew.bat').write_text('@echo off\n"'+sys.executable+'" -B "'+str(worker)+'"\n',encoding='utf8')
            (repo/'SimulationTest/gradlew').write_text('#!/bin/sh\nexec "'+sys.executable+'" -B "'+str(worker)+'"\n',encoding='utf8')
            output=repo/'batch';pid=None
            def alive(pid):
                if os.name=='nt':
                    import ctypes
                    k=ctypes.WinDLL('kernel32',use_last_error=True)
                    k.OpenProcess.restype=ctypes.c_void_p;k.WaitForSingleObject.argtypes=[ctypes.c_void_p,ctypes.c_ulong];k.CloseHandle.argtypes=[ctypes.c_void_p]
                    h=k.OpenProcess(0x100000,False,pid)
                    if not h:return False
                    try:return k.WaitForSingleObject(h,0)==258
                    finally:k.CloseHandle(h)
                status=Path('/proc')/str(pid)/'stat'
                if status.exists() and status.read_text().rsplit(')',1)[1].split()[0]=='Z':return False
                try:os.kill(pid,0);return True
                except ProcessLookupError:return False
            try:
                result=subprocess.run([sys.executable,'-B',str(repo/'SimulationTest/ci/public_batch.py'),'--kind','mechanism','--command-timeout','1','--output',str(output)],capture_output=True,text=True,timeout=35)
                if (repo/'child.pid').exists():pid=int((repo/'child.pid').read_text())
                self.assertEqual(1,result.returncode,result.stderr)
                self.assertIsNotNone(pid,'The timeout must happen after the real child was spawned')
                batch=json.loads((output/'result.json').read_bytes());self.assertEqual(1,len(batch['suites']))
                child=json.loads((output/'content-movement-knowledge/result.json').read_bytes())
                self.assertEqual(124,child['commands'][0]['exitCode'])
                receipt=json.loads((output/'content-movement-knowledge/test-execution/command/result.json').read_bytes())
                self.assertEqual('timeout',receipt['firstError']['kind'])
                self.assertEqual(1,receipt['timeoutSeconds'])
                self.assertFalse(receipt['passed'])
                self.assertEqual(1,child['commandTimeoutSeconds'])
                self.assertFalse(alive(pid),'Timed-out child escaped process-tree cleanup')
                self.assertFalse((output/'production-combat').exists())
            finally:
                if pid and alive(pid):
                    if os.name=='nt':subprocess.run(['taskkill','/PID',str(pid),'/T','/F'],capture_output=True,timeout=10)
                    else:
                        import signal
                        os.kill(pid,signal.SIGKILL)

    def test_explicit_budget_is_bounded_without_changing_the_selected_contract(self):
        with tempfile.TemporaryDirectory(prefix='reforge-command-budget-') as tmp:
            output=Path(tmp)/'must-not-exist'
            for filename,scope in [('public_batch.py',['--kind','pr']),('public_check.py',['--suite','production-combat','--kind','pr'])]:
                argv=[sys.executable,'-B',str(Path(__file__).with_name(filename)),*scope,'--list','--output',str(output)]
                baseline=subprocess.run(argv,capture_output=True,text=True,timeout=20)
                extended=subprocess.run(argv+['--command-timeout','1800'],capture_output=True,text=True,timeout=20)
                self.assertEqual(0,baseline.returncode,baseline.stderr);self.assertEqual(0,extended.returncode,extended.stderr)
                self.assertEqual(json.loads(baseline.stdout),json.loads(extended.stdout))
                for invalid in ['0','-1','nan','inf','3601']:
                    rejected=subprocess.run(argv+['--command-timeout',invalid],capture_output=True,text=True,timeout=20)
                    self.assertEqual(2,rejected.returncode)
                self.assertFalse(output.exists())
    def test_pr_and_long_select_all_applicable_suites_without_private_or_legacy_duplicates(self):
        entry = Path(__file__).with_name('public_batch.py')
        with tempfile.TemporaryDirectory(prefix='reforge-public-layer-') as tmp:
            output = Path(tmp) / 'must-not-exist'
            for kind, names in [
                ('pr', ['content-movement-knowledge', 'production-combat', 'build-economy',
                        'maintenance-defense', 'platform-basics', 'content-three-formats', 'tooling']),
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
        from public_check import REPO, source_files

        with tempfile.TemporaryDirectory(prefix='reforge-layer-failure-') as tmp:
            repo = Path(tmp)
            ci = repo/'SimulationTest/ci'
            ci.mkdir(parents=True)
            for filename in ['public_batch.py', 'public_check.py', 'development_check.py', 'public_distribution.py']:
                shutil.copyfile(Path(__file__).with_name(filename), ci/filename)
            public = repo/'SimulationTest/public'
            (public/'suites').mkdir(parents=True)
            for name in ['content-movement-knowledge', 'production-combat', 'build-economy',
                         'maintenance-defense', 'platform-basics', 'content-three-formats', 'tooling']:
                (public/'suites'/(name+'.json')).write_text(json.dumps(dict(schemaVersion=1,
                    suiteId=name, inputScope='public', tests={'tools': ['ProbeTest']})), encoding='utf8')
            for name in ['workflow.py','workflow_inputs.py']:
                target = repo/'SimulationTest/tools'/name
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(REPO/'SimulationTest/tools'/name, target)
            # Populate the required files using the production selector on this fixture.
            for path in source_files(repo):
                if not path.exists():
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.touch()
            (repo/'SimulationTest/gradlew').write_text('#!/bin/sh\nexit 9\n', encoding='utf8')
            (repo/'SimulationTest/gradlew.bat').write_text('@exit /b 9\n', encoding='utf8')
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
            pr_output = repo/'pr-batch'
            prepared_failure = subprocess.run([sys.executable, '-B', str(ci/'public_batch.py'),
                '--kind', 'pr', '--output', str(pr_output)], capture_output=True, text=True, timeout=30)
            self.assertEqual(1, prepared_failure.returncode, prepared_failure.stderr)
            pr_batch = json.loads((pr_output/'result.json').read_text(encoding='utf8'))
            self.assertEqual(9, pr_batch['sharedBuild']['exitCode'])
            self.assertEqual([], pr_batch['suites'], 'Preparation failure must precede every suite')
            self.assertFalse((pr_output/'content-movement-knowledge').exists())


if __name__ == '__main__':
    unittest.main()
