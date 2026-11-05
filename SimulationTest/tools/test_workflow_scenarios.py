"""Consumer qualification through the workflow CLI; no build or simulated producer."""
import copy
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


class ScenarioQualificationTest(unittest.TestCase):
    def test_buffered_terminal_failure_is_recorded_before_returning(self):
        import io
        from unittest.mock import patch
        from workflow_scenarios import finish_result
        class ClosedConsumer(io.RawIOBase):
            def writable(self): return True
            def write(self, data): raise BrokenPipeError('controlled closed output consumer')
        terminal=io.TextIOWrapper(io.BufferedWriter(ClosedConsumer()),encoding='utf8')
        try:
            with tempfile.TemporaryDirectory(prefix='workflow-buffered-terminal-') as temporary:
                output=Path(temporary)
                result=dict(passed=True,firstError=None,postErrors=[],consumption=dict(status='passed'))
                with patch('sys.stdout',terminal):
                    code=finish_result(result,output)
                self.assertEqual(4,code)
                saved=json.loads((output/'result.json').read_text(encoding='utf8'))
                self.assertEqual('passed',saved['consumption']['status'])
                self.assertEqual('failed',saved['printing']['status'])
                self.assertEqual('printing',saved['firstError']['stage'])
        finally:
            try: terminal.close()
            except BrokenPipeError: pass

    def test_replay_and_restore_never_write_inside_the_sealed_source(self):
        with tempfile.TemporaryDirectory(prefix='workflow-sealed-source-') as temporary:
            run=Path(temporary)/'run';run.mkdir();data=run/'owned.txt';data.write_bytes(b'owned')
            payload=run/'payload.json';payload.write_text(json.dumps(dict(schemaVersion=1,
                kind='scenario-archive-manifest',complete=False,files=[dict(path=data.name,
                sha256=hashlib.sha256(data.read_bytes()).hexdigest())])),encoding='utf8')
            (run/'seal.json').write_text(json.dumps(dict(payloadSha256=hashlib.sha256(payload.read_bytes()).hexdigest())),encoding='utf8')
            entry=Path(__file__).with_name('workflow.py')
            for operation in ('scenario-compare','scenario-restore'):
                target=run/operation
                result=subprocess.run([sys.executable,'-B',str(entry),operation,'--run',str(run),
                    '--output',str(target)],capture_output=True)
                self.assertEqual(64,result.returncode,result.stderr)
                self.assertFalse(target.exists())
            data.write_bytes(b'ownee')
            target=Path(temporary)/'tampered-restore'
            result=subprocess.run([sys.executable,'-B',str(entry),'scenario-restore','--run',str(run),
                '--output',str(target)],capture_output=True)
            self.assertEqual(64,result.returncode,result.stderr)
            self.assertFalse(target.exists())

    def test_scenario_run_rejects_changed_qualification_before_build_or_output(self):
        with tempfile.TemporaryDirectory(prefix='workflow-qualification-pin-') as temporary:
            root=Path(temporary);index=root/'qualification.json';index.write_text('{}',encoding='utf8')
            plan=root/'plan.json';plan.write_text(json.dumps(dict(schemaVersion=1,
                root=str(Path(__file__).resolve().parents[2]),suite='content-movement-knowledge',kind='long',
                cases=['movement-knowledge-chain'],qualifications=[dict(path=str(index),sha256='0'*64)])),encoding='utf8')
            result=subprocess.run([sys.executable,'-B',str(Path(__file__).with_name('workflow.py')),
                'scenario-run','--plan',str(plan),'--output',str(root/'must-not-exist')],capture_output=True)
            self.assertEqual(64,result.returncode,result.stderr)
            self.assertIn(b'qualification digest',result.stderr)
            self.assertFalse((root/'must-not-exist').exists())

    def test_malformed_qualification_is_rejected_before_output(self):
        with tempfile.TemporaryDirectory(prefix='workflow-invalid-qualification-') as temporary:
            root=Path(temporary);index=root/'qualification.json'
            index.write_text(json.dumps(dict(schemaVersion=1,backend='session',fixtureKind='legacy-raw',
                cases=['not-a-case-object'])),encoding='utf8')
            result=subprocess.run([sys.executable,'-B',str(Path(__file__).with_name('workflow.py')),
                'scenario-qualify','--index',str(index),'--output',str(root/'must-not-exist')],capture_output=True)
            self.assertEqual(64,result.returncode,result.stderr)
            self.assertFalse((root/'must-not-exist').exists())

    def test_workflow_qualifies_good_and_semantic_bad_raw_before_a_long_run(self):
        with tempfile.TemporaryDirectory(prefix='workflow-scenario-qualification-') as temporary:
            root=Path(temporary)
            sample=dict(worldId='w',stepIndex=0,simulationTime=0,balance=0,
                units=[dict(unitId='a',ownerPlayerId='p1',typeId='builder',health=100,maxHealth=100,alive=True)],weapons=[])
            rows=[dict(sequence=0,kind='observation',phase='initial',sample=sample),
                dict(sequence=1,kind='intent-start',intentId='choose'),
                dict(sequence=2,kind='observation',phase='before-action',sample=sample),
                dict(sequence=3,kind='action',type='SelectAction',unitId='a',actorPlayerId='p1',result=dict(actionId='click-1',accepted=True)),
                dict(sequence=4,kind='observation',phase='after-action',sample=sample),
                dict(sequence=5,kind='intent-end',intentId='choose'),
                dict(sequence=6,kind='observation',phase='before-close',sample=sample),dict(sequence=7,kind='closed')]
            plan=dict(scenarioId='s',intents=[dict(intentId='choose',kind='select')])
            contract=dict(scenarioId='s',rules=[dict(intentId='choose')],history=dict(initialBalance=0))
            for name,document in [('plan.json',plan),('contract.json',contract)]:
                (root/name).write_text(json.dumps(document),encoding='utf8')
            bad=copy.deepcopy(rows);bad[4]['sample']['balance']=1
            cases=[]
            for name,data,code in [('good',rows,0),('bad',bad,2)]:
                path=root/(name+'.jsonl');path.write_text(''.join(json.dumps(r)+'\n' for r in data),encoding='utf8')
                cases.append(dict(case=name,path=path.name,sha256=hashlib.sha256(path.read_bytes()).hexdigest(),
                                  expectedExitCode=code,expectedCategory='history-ledger' if code else None))
            index=root/'qualification.json'
            index.write_text(json.dumps(dict(schemaVersion=1,backend='session',fixtureKind='legacy-raw',
                scenario='plan.json',contract='contract.json',cases=cases)),encoding='utf8')
            entry=Path(__file__).with_name('workflow.py')
            result=subprocess.run([sys.executable,'-B',str(entry),'scenario-qualify','--index',str(index),
                '--output',str(root/'result')],capture_output=True)
            self.assertEqual(0,result.returncode,result.stderr)
            measured=json.loads((root/'result/result.json').read_text(encoding='utf8'))
            self.assertTrue(measured['passed'])
            self.assertEqual([0,2],[c['actualExitCode'] for c in measured['cases']])
            from unittest.mock import patch
            from workflow_scenarios import qualify
            workspace=root/'controlled-workspace';workspace.mkdir()
            with patch('workflow_scenarios.ROOT',workspace):
                forbidden=workspace/'unrelated-output'
                with self.assertRaisesRegex(ValueError,'source or unrelated'):
                    qualify(index,forbidden)
                self.assertFalse(forbidden.exists())
            from workflow import resources
            repo=Path(__file__).resolve().parents[2]
            for module in ('Engine','SimulationTest'):
                refused=root/('occupied-'+module)
                with resources(['build:'+str(repo/module)]):
                    busy=subprocess.run([sys.executable,'-B',str(entry),'scenario-qualify','--index',str(index),
                        '--output',str(refused)],capture_output=True,timeout=20)
                self.assertEqual(64,busy.returncode,busy.stderr)
                self.assertIn(b'Resource occupied',busy.stderr)
                self.assertFalse(refused.exists())


if __name__=='__main__':
    unittest.main()
