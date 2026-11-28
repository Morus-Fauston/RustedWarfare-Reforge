"""Host integration checks for the public workflow/process and receipt boundary."""
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

import workflow
import workflow_inputs


class WorkflowChecks(unittest.TestCase):
    def test_known_suite_cli_outcomes_preserve_real_exit_and_separate_summary(self):
        expected={0:'passed',2:'failed',3:'evidence-insufficient',4:'execution-error',64:'blocked',7:'execution-error'}
        with tempfile.TemporaryDirectory() as temp:
            rows={}
            for code,status in expected.items():
                receipt=workflow.execute_step([sys.executable,'-c',f'raise SystemExit({code})'],temp,Path(temp)/str(code))
                outcome=workflow.suite_cli_outcome(receipt)
                self.assertEqual(outcome['status'],status)
                self.assertEqual(outcome['exitCode'],code)
                self.assertEqual(receipt['firstError']['kind'] if code else None,'process-exit' if code else None)
                rows[str(code)]=outcome
            self.assertEqual(workflow.target_summary(rows)['evidence-insufficient'],1)
            self.assertEqual(workflow.target_summary(rows)['execution-error'],2)
            self.assertEqual(workflow.suite_cli_outcome({'exitCode':2,'timedOut':True,'preservationErrors':[]})['status'],'execution-error')
    def test_external_output_cannot_append_to_a_frozen_ticket_batch(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);base=root/'.scratch/开发票据/测试系统/P003-工作流整合改进/evidence/TEST-P003-001'
            base.mkdir(parents=True);plan={'root':temp,'evidenceRoot':str(base)}
            (root/'AGENTS.md').write_text('fixture')
            self.assertEqual(workflow.cli_output_domain(plan,base/'new-batch'),base/'new-batch')
            with self.assertRaisesRegex(ValueError,'never a child'):
                workflow.cli_output_domain(plan,base/'old-batch/new-result.json')
    def test_actual_source_drift_stops_before_build_and_has_end_receipt(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);subprocess.run(['git','init','-q',temp],check=True)
            (root/'Engine/core/src/main').mkdir(parents=True)
            guard=root/'Decompile/tools/workspace_layout.py';guard.parent.mkdir(parents=True)
            guard.write_text('from pathlib import Path\nr=Path(__file__).parents[2]\np=r/"Engine/core/src/main/Drift.kt"\np.write_text(p.read_text()+"x" if p.exists() else "first")\nprint("{\\"ok\\":true}")\n')
            plan=dict(root=temp,java=sys.executable,gradleUserHome=temp,owner='fixture',targets=['core-pc-headless'],inputs={})
            actual_check_output=subprocess.check_output
            # This owned fixture has no commit. Only the irrelevant Git label
            # is substituted; layout subprocesses and file drift are real.
            def git_metadata(argv,**kwargs):
                return 'fixture-without-commit\n' if argv==['git','rev-parse','HEAD'] else actual_check_output(argv,**kwargs)
            with patch.object(workflow.subprocess,'check_output',side_effect=git_metadata):
                result=workflow.run_plan(plan,root/'attempt')
            self.assertFalse(result['passed'])
            self.assertIn('Source drift',result['failureContext'])
            self.assertEqual([r['name'] for r in result['stages']],['layout'])
            self.assertTrue(result['endedUnix'])
            self.assertTrue((root/'attempt/result.json').is_file())
    def test_formal_performance_refuses_functional_overlap_in_both_orders(self):
        # This fixture models both resource states; the enclosing CI task may
        # legitimately own a real machine-wide functional lease throughout.
        with tempfile.TemporaryDirectory(prefix='workflow-qualification-') as temp:
            with patch.object(workflow.tempfile,'gettempdir',return_value=temp):
                with workflow.resources(['qualification-functional']):
                    with self.assertRaisesRegex(ValueError,'never queues'):
                        with workflow.resources([],formal=True):self.fail('must not enter formal window')
                with workflow.resources([],formal=True):
                    with self.assertRaisesRegex(ValueError,'formal performance'):
                        with workflow.resources(['qualification-functional']):self.fail('must not enter functional window')
    def test_manifest_cannot_omit_seed_or_transitive_reference(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp)
            with self.assertRaises(ValueError):
                workflow_inputs.prepare({'schemaVersion':1,'seeds':['missing.tmx'],'files':[]},root,root)
            (root/'map.tmx').write_text('<map><tileset source="tile.tsx"/></map>')
            (root/'tile.tsx').write_text('<tileset/>')
            m=workflow_inputs.freeze(root,['map.tmx'])
            m['files']=[r for r in m['files'] if r['path']!='tile.tsx']
            with self.assertRaisesRegex(ValueError,'omits'):
                workflow_inputs.verify(m,root)

    def test_required_task_missing_is_not_hidden_by_another_passing_task(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);task=root/'_core_test';task.mkdir();(task/'xml').mkdir()
            (task/'started.json').write_text('{}')
            (task/'completed.json').write_text(json.dumps({'task':':core:test','tests':1,'skipped':0}))
            (task/'xml/TEST-one.xml').write_text('<testsuite tests="1"/>')
            self.assertEqual(workflow.test_evidence(root,[':core:test'])['status'],'passed')
            self.assertEqual(workflow.test_evidence(root,[':core:test',':pc:test'])['status'],'failed')

    def test_new_untracked_product_source_is_part_of_execution_identity(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);subprocess.run(['git','init','-q',temp],check=True)
            before=workflow.source_identity(root)
            p=root/'Engine/core/src/main/kotlin/Untracked.kt';p.parent.mkdir(parents=True);p.write_text('new source')
            after=workflow.source_identity(root)
            self.assertNotEqual(before,after)
            self.assertIn('Engine/core/src/main/kotlin/Untracked.kt',after)

    def test_output_outside_registered_domain_is_refused_without_side_effects(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp)
            # A workspace root itself may never become the output batch.
            with self.assertRaises(ValueError):workflow.output_domain(root,root)

    def test_cleanup_timeout_preserves_the_first_timeout_and_end_receipt(self):
        with tempfile.TemporaryDirectory() as temp:
            cleanup = (patch.object(workflow.subprocess,'run',side_effect=subprocess.TimeoutExpired('taskkill',30))
                       if sys.platform=='win32' else
                       patch.object(workflow.os,'killpg',side_effect=OSError('owned process group termination unavailable')))
            with cleanup:
                r=workflow.execute_step([sys.executable,'-c','import time;time.sleep(.3)'],temp,Path(temp)/'attempt',timeout=.03)
            self.assertEqual(r['exitCode'],124)
            self.assertEqual(r['firstError']['kind'],'timeout')
            self.assertTrue(r['preservationErrors'])
            self.assertTrue(r['endedUnix'])
    def test_timeout_is_preserved_and_does_not_look_successful(self):
        with tempfile.TemporaryDirectory() as temp:
            r = workflow.execute_step([sys.executable,'-c','import time;time.sleep(20)'], temp, Path(temp)/'timeout', timeout=.15)
            self.assertEqual(r['exitCode'],124)
            self.assertEqual(r['firstError']['kind'],'timeout')
            self.assertFalse(r['passed'])

    def test_managed_resource_is_not_taken_over_by_another_process(self):
        with tempfile.TemporaryDirectory() as temp:
            name = 'test-resource:'+temp
            with workflow.resources([name]):
                script = 'import workflow;\nwith workflow.resources(['+repr(name)+']): print("taken")'
                r = subprocess.run([sys.executable,'-B','-c',script], cwd=Path(workflow.__file__).parent,
                                   capture_output=True)
                self.assertNotEqual(r.returncode,0)
                self.assertIn(b'Resource occupied',r.stderr)
            with workflow.resources([name]):
                pass

    def test_bounded_parallel_jobs_keep_separate_results_and_continue_assertion_failure(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp)
            jobs=[{'name':str(i),'argv':[sys.executable,'-c',f'import time;time.sleep(.1);raise SystemExit({2 if i==1 else 0})'],
                   'cwd':temp,'workers':1,'resources':[]} for i in range(4)]
            rows=workflow.run_jobs(jobs,root,workers=4)
            self.assertEqual([r['exitCode'] for r in rows],[0,2,0,0])
            self.assertTrue(all((root/str(i)/'result.json').is_file() for i in range(4)))
            with self.assertRaisesRegex(ValueError,'budget'):
                workflow.run_jobs(jobs,root,workers=5)
    def test_scenario_content_references_are_relative_to_scenario_directory(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); (root/'scenes').mkdir()
            (root/'input.bin').write_bytes(b'fixed')
            import hashlib
            digest = hashlib.sha256(b'fixed').hexdigest()
            (root/'scenes/scenario.json').write_text(json.dumps({'kind':'scenario', 'content': {
                'map': {'path':'../input.bin','sha256':digest}}}))
            frozen = workflow_inputs.freeze(root, ['scenes/scenario.json'])
            self.assertEqual([r['path'] for r in frozen['files']], ['input.bin','scenes/scenario.json'])
    def test_old_xml_is_not_evidence_without_this_attempt_task_receipts(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root/'TEST-old.xml').write_text('<testsuite tests="8" failures="0" errors="0" skipped="0"/>')
            self.assertEqual(workflow.test_evidence(root)['status'], 'not-executed')
    def test_noncanonical_output_is_rejected_before_creating_files(self):
        with tempfile.TemporaryDirectory() as temp:
            output = str(Path(temp)/'missing'/'..'/'attempt')
            with self.assertRaisesRegex(ValueError, 'canonical'):
                workflow.execute_step([sys.executable, '-c', 'print(1)'], temp, output)
            self.assertFalse((Path(temp)/'missing').exists())

    def test_receipt_failure_keeps_process_failure_and_end_record(self):
        with tempfile.TemporaryDirectory() as temp:
            receipt = workflow.execute_step(
                [sys.executable, '-c', 'from pathlib import Path; Path("result.json").mkdir(); raise SystemExit(7)'],
                Path(temp)/'attempt', Path(temp)/'attempt', cwd_is_output=True)
            self.assertEqual(receipt['exitCode'], 7)
            self.assertEqual(receipt['firstError']['kind'], 'process-exit')
            self.assertTrue(receipt['endedUnix'])
            self.assertEqual(len(receipt['preservationErrors']), 1)
            self.assertTrue((Path(temp)/'attempt/receipt-fallback.json').is_file())
    def test_prepare_finds_transitive_image_and_refuses_conflicting_destination(self):
        with tempfile.TemporaryDirectory(prefix='rw inputs ') as temp:
            root = Path(temp)/'source'; root.mkdir()
            (root/'map.tmx').write_text('<map><tileset source="tiles.tsx"/></map>')
            (root/'tiles.tsx').write_text('<tileset><image source="图.png"/></tileset>', encoding='utf-8')
            with self.assertRaisesRegex(ValueError, '图.png'):
                workflow_inputs.freeze(root, ['map.tmx'])
            (root/'图.png').write_bytes(b'fixed-image')
            manifest = workflow_inputs.freeze(root, ['map.tmx'])
            target = Path(temp)/'target'; target.mkdir()
            (target/'图.png').write_bytes(b'old-data')
            with self.assertRaisesRegex(ValueError, 'conflict'):
                workflow_inputs.prepare(manifest, root, target)
            self.assertFalse((target/'map.tmx').exists())
            self.assertEqual((target/'图.png').read_bytes(), b'old-data')
    def test_arguments_and_failure_are_preserved_without_shell_parsing(self):
        with tempfile.TemporaryDirectory(prefix='rw workflow ') as temp:
            root = Path(temp)
            args = ['space here', '-Pkotlin.compiler.execution.strategy=in-process', '汉字', '$literal']
            receipt = workflow.execute_step(
                [sys.executable, '-c', 'import json,sys;print(json.dumps(sys.argv[1:]));sys.exit(7)', *args],
                root, root/'attempt', expected=0)
            self.assertEqual(receipt['exitCode'], 7)
            self.assertFalse(receipt['passed'])
            self.assertEqual(json.loads((root/'attempt/stdout.log').read_text()), args)
            self.assertEqual(json.loads((root/'attempt/result.json').read_text())['exitCode'], 7)

    def test_host_heavy_lock_queues_across_worktrees_refuses_zero_wait_and_cleans_dead_or_reused_pids(self):
        import os
        import time
        from concurrent.futures import ThreadPoolExecutor

        with tempfile.TemporaryDirectory(prefix='rw-host-lock-') as temp:
            lock_root = Path(temp) / 'rw-reforge-workflow-locks'
            lock_root.mkdir()
            lock_file = lock_root / 'host-heavy-verification.lock'
            with patch.object(workflow.tempfile, 'gettempdir', return_value=temp):
                # 1. Dead PID orphan lock is cleaned up and taken over automatically
                lock_file.write_text(json.dumps({
                    'resource': workflow.HOST_HEAVY_LOCK,
                    'pid': 99999999,
                    'processCreateTime': 123456789,
                    'cwd': 'D:/dead-worktree',
                    'startedUnix': time.time() - 60,
                }), encoding='utf-8')
                with workflow.resources([workflow.HOST_HEAVY_LOCK], wait_seconds=0):
                    active_record = json.loads(lock_file.read_text(encoding='utf-8'))
                    self.assertEqual(os.getpid(), active_record['pid'])
                    self.assertIsNotNone(active_record['processCreateTime'])
                self.assertFalse(lock_file.exists())

                # 2. Reused PID (live PID, mismatched processCreateTime) is cleaned up safely
                my_ctime = workflow.process_create_time(os.getpid())
                lock_file.write_text(json.dumps({
                    'resource': workflow.HOST_HEAVY_LOCK,
                    'pid': os.getpid(),
                    'processCreateTime': (my_ctime or 0) + 1,
                    'cwd': 'D:/reused-pid-worktree',
                    'startedUnix': time.time() - 60,
                }), encoding='utf-8')
                with workflow.resources([workflow.HOST_HEAVY_LOCK], wait_seconds=0):
                    reclaimed = json.loads(lock_file.read_text(encoding='utf-8'))
                    self.assertEqual(my_ctime, reclaimed['processCreateTime'])

                # 3. Live owner is never deleted; wait_seconds=0 immediately raises Resource occupied
                child_script = (
                    'import sys, time, workflow\n'
                    'from unittest.mock import patch\n'
                    f'with patch.object(workflow.tempfile, "gettempdir", return_value={temp!r}):\n'
                    '    with workflow.resources([workflow.HOST_HEAVY_LOCK, "build:D:/wt-a/Engine"], wait_seconds=0):\n'
                    '        print("LOCKED", flush=True)\n'
                    '        time.sleep(0.6)\n'
                )
                holder = subprocess.Popen(
                    [sys.executable, '-B', '-c', child_script],
                    cwd=Path(workflow.__file__).parent,
                    stdout=subprocess.PIPE,
                    stderr=subprocess.PIPE,
                    text=True,
                )
                try:
                    self.assertEqual('LOCKED', holder.stdout.readline().strip())
                    with self.assertRaisesRegex(ValueError, 'Resource occupied: host:heavy-verification'):
                        with workflow.resources([workflow.HOST_HEAVY_LOCK, 'build:D:/wt-b/Engine'], wait_seconds=0):
                            self.fail('wait_seconds=0 must refuse immediately')
                    holder_released_before = time.monotonic()
                    with workflow.resources([workflow.HOST_HEAVY_LOCK, 'build:D:/wt-b/Engine'], wait_seconds=5):
                        acquired_at = time.monotonic()
                    self.assertEqual(0, holder.wait(timeout=5))
                    self.assertGreaterEqual(acquired_at - holder_released_before, 0.2)
                finally:
                    if holder.poll() is None:
                        holder.kill()
                        holder.wait(timeout=5)
                    if holder.stdout:
                        holder.stdout.close()
                    if holder.stderr:
                        holder.stderr.close()

                # 4. Two distinct worktrees requesting host:heavy-verification serialize with zero overlap
                worker_prog = (
                    'import json, sys, time, workflow\n'
                    'from unittest.mock import patch\n'
                    'wt = sys.argv[1]\n'
                    f'with patch.object(workflow.tempfile, "gettempdir", return_value={temp!r}):\n'
                    '    with workflow.resources([workflow.HOST_HEAVY_LOCK, "build:" + wt], wait_seconds=10):\n'
                    '        s = time.monotonic(); time.sleep(0.25); e = time.monotonic()\n'
                    '        print(json.dumps({"wt": wt, "start": s, "end": e}), flush=True)\n'
                )
                def launch(wt):
                    proc = subprocess.run(
                        [sys.executable, '-B', '-c', worker_prog, wt],
                        cwd=Path(workflow.__file__).parent,
                        capture_output=True,
                        text=True,
                        timeout=15,
                    )
                    self.assertEqual(0, proc.returncode, proc.stderr)
                    return json.loads(proc.stdout.strip())

                with ThreadPoolExecutor(max_workers=2) as pool:
                    first, second = sorted(
                        pool.map(launch, ['D:/wt-one', 'D:/wt-two']),
                        key=lambda r: r['start'],
                    )
                self.assertGreaterEqual(second['start'], first['end'] - 0.01)


if __name__ == '__main__':
    unittest.main()
