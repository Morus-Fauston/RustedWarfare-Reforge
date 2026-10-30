"""Installed real JVM adapter checks; run after :installDist, never substitute synthetic rows."""
import tempfile
import unittest
from pathlib import Path
import json
import shutil
import hashlib


class PlatformSessionCliTest(unittest.TestCase):
    def test_partial_or_rebound_real_session_archive_is_rejected(self):
        from platform_scenario import run_session, compare_archive, PlatformError
        root=Path(__file__).resolve().parents[2]
        public=root/'SimulationTest/public/platform'
        with tempfile.TemporaryDirectory(prefix='platform-archive-negative-') as folder:
            base=Path(folder)/'positive'
            result=run_session(public/'scenarios/production.json',public/'contracts/production.json',
                               root/'SimulationTest/build/install/simulation-test',base)
            self.assertEqual(0,result['exitCode'],result)
            for fault in ('empty','duplicate','omit-diagnostic','missing-manifest','missing-input',
                          'rebound-scenario','rebound-contract','rebound-input','backend'):
                with self.subTest(fault=fault):
                    altered=Path(folder)/fault
                    shutil.copytree(base,altered)
                    archive=json.loads((altered/'archive.json').read_text())
                    if fault=='empty': archive['files']=[]
                    elif fault=='duplicate': archive['files'].append(archive['files'][0])
                    elif fault=='omit-diagnostic': archive['files']=[r for r in archive['files'] if r['path']!='session/client-diagnostic.jsonl']
                    elif fault=='missing-manifest':(altered/'manifest.json').unlink()
                    elif fault=='missing-input':(altered/'inputSnapshot/production.properties').unlink()
                    elif fault=='backend':archive['backend']='invented'
                    else:
                        manifest=json.loads((altered/'manifest.json').read_text())
                        if fault=='rebound-input':manifest['inputFiles'][0]['sha256']='0'*64
                        else:manifest[('scenario' if fault=='rebound-scenario' else 'contract')+'Sha256']='0'*64
                        (altered/'manifest.json').write_text(json.dumps(manifest))
                        next(r for r in archive['files'] if r['path']=='manifest.json')['sha256']=hashlib.sha256((altered/'manifest.json').read_bytes()).hexdigest()
                    (altered/'archive.json').write_text(json.dumps(archive))
                    with self.assertRaises(PlatformError):compare_archive(altered)

    def test_installed_adapter_freezes_inputs_and_compares_real_production_calls(self):
        from platform_scenario import run_session
        root = Path(__file__).resolve().parents[2]
        public = root / 'SimulationTest/public/platform'
        with tempfile.TemporaryDirectory(prefix='platform-cli-') as folder:
            output = Path(folder) / 'run'
            result = run_session(public / 'scenarios/production.json', public / 'contracts/production.json',
                                 root / 'SimulationTest/build/install/simulation-test', output)
            self.assertEqual(0, result['exitCode'], result)
            self.assertEqual(7, len(result['judgments']))
            self.assertTrue((output / 'inputSnapshot/production.properties').is_file())
            self.assertTrue((output / 'session/client-diagnostic.jsonl').is_file())
            self.assertTrue((output / 'manifest.json').is_file())


if __name__ == '__main__':
    unittest.main()
