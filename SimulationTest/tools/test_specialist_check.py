"""Read-only prerequisite checks cannot become a device, original or performance pass."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


class SpecialistCheckTest(unittest.TestCase):
    def test_gradle_templates_select_the_simulation_project_from_repo_root(self):
        catalog=Path(__file__).resolve().parents[1]/'public/specialist-entries.json'
        entries=json.loads(catalog.read_text(encoding='utf8'))['entries']
        for entry in entries:
            command=entry['commandTemplate']
            if command[0].endswith('gradlew.bat'):
                self.assertEqual('SimulationTest/gradlew.bat',command[0])
                self.assertIn('-p',command,entry['id'])
                self.assertEqual('SimulationTest',command[command.index('-p')+1],entry['id'])

    def test_missing_unreleased_ready_and_drift_preserve_distinct_non_execution_receipts(self):
        entry=Path(__file__).with_name('specialist_check.py')
        with tempfile.TemporaryDirectory(prefix='specialist-readonly-') as folder:
            root=Path(folder)
            tool=root/'must-not-execute.py'
            tool.write_text('raise RuntimeError("No specialist execution is permitted")',encoding='utf8')
            ref=dict(path=str(tool),sha256=hashlib.sha256(tool.read_bytes()).hexdigest())
            for name,bindings,code,status in [
                ('unreleased',dict(released=False,files={}),64,'blocked'),
                ('missing',dict(released=True,files={}),3,'evidence-insufficient'),
                ('ready',dict(released=True,files=dict(java=ref,javac=ref,hostSourcesManifest=ref)),0,'ready'),
                ('drift',dict(released=True,files=dict(java=ref,javac=ref,hostSourcesManifest=dict(ref,sha256='0'*64))),3,'evidence-insufficient'),
                ('invalid-type',[],3,'evidence-insufficient'),
                ('extra-role',dict(released=True,files=dict(java=ref,javac=ref,hostSourcesManifest=ref,extra=ref)),3,'evidence-insufficient')]:
                plan=root/(name+'.json');plan.write_text(json.dumps(bindings),encoding='utf8')
                output=root/name
                result=subprocess.run([sys.executable,'-B',str(entry),'--entry','reference-host','--bindings',str(plan),'--output',str(output)],capture_output=True)
                self.assertEqual(code,result.returncode,result.stderr)
                receipt=json.loads((output/'result.json').read_text(encoding='utf8'))
                self.assertEqual(status,receipt['status'])
                self.assertFalse(receipt['executed'])
                self.assertNotIn('passed',receipt)
                original=(output/'result.json').read_bytes()
                again=subprocess.run([sys.executable,'-B',str(entry),'--entry','reference-host','--bindings',str(plan),'--output',str(output)],capture_output=True)
                self.assertNotEqual(0,again.returncode)
                self.assertEqual(original,(output/'result.json').read_bytes())
            self.assertEqual('raise RuntimeError("No specialist execution is permitted")',tool.read_text(encoding='utf8'))


if __name__=='__main__':
    unittest.main()
