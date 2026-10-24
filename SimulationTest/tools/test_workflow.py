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

    @unittest.skipUnless(sys.platform=='win32','Windows process cleanup boundary')
    def test_cleanup_timeout_preserves_the_first_timeout_and_end_receipt(self):
        with tempfile.TemporaryDirectory() as temp:
            with patch.object(workflow.subprocess,'run',side_effect=subprocess.TimeoutExpired('taskkill',30)):
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


if __name__ == '__main__':
    unittest.main()
