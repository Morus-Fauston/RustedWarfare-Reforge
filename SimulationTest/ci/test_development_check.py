"""Developer selection uses the public registry and preserves distinct input contexts."""
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from development_check import collect_tests


class DevelopmentSelectionTest(unittest.TestCase):
    def test_production_regression_is_in_fixed_public_selection(self):
        result = subprocess.run([sys.executable, '-B', str(Path(__file__).with_name('public_check.py')),
            '--suite', 'production-combat', '--kind', 'mechanism', '--list'],
            capture_output=True, text=True, encoding='utf8')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn('com.rustedwarfare.reforge.testing.ProducedWeaponArchiveTest',
                      json.loads(result.stdout)['tests']['tools'])

    def test_same_class_is_deduplicated_only_with_the_same_fixture(self):
        entry = Path(__file__).with_name('development_check.py')
        result = subprocess.run([sys.executable, '-B', str(entry), '--suite', 'platform-basics',
            '--suite', 'content-three-formats', '--suite', 'platform-basics', '--list'],
            capture_output=True, text=True, encoding='utf8')
        self.assertEqual(0, result.returncode, result.stderr)
        plan = json.loads(result.stdout)
        name = 'com.rustedwarfare.reforge.testing.PlatformSessionRunnerTest'
        contexts = [g['fixture'] for g in plan['groups'] if name in g['tests'].get('tools', [])]
        self.assertCountEqual(['inputs/content-movement-knowledge/P001-移动沙盒输入',
                              'inputs/three-format-tanks/ini'], contexts)
        self.assertEqual(2, len(plan['groups']))
        self.assertTrue(plan['deduplicated'])

    def test_duplicate_methods_skips_and_old_xml_cannot_establish_current_execution(self):
        with tempfile.TemporaryDirectory(prefix='reforge-developer-xml-') as temporary:
            folder = Path(temporary)
            leaf = folder/'client'; (leaf/'xml').mkdir(parents=True)
            (folder/'effective.json').write_text(json.dumps({'tests':[
                {'task':':client:test', 'classes':['OwnedTest']}]}), encoding='utf8')
            (folder/'task-executions.json').write_text(json.dumps([{'task':':client:test',
                'skipped':False, 'upToDate':False, 'noSource':False, 'failure':None}]), encoding='utf8')
            (leaf/'started.json').write_text('{"task": ":client:test", "millis": 1}', encoding='utf8')
            (leaf/'completed.json').write_text(json.dumps({'tests':2, 'failures':0, 'skipped':0}), encoding='utf8')
            (leaf/'xml/TEST-OwnedTest.xml').write_text(
                '<testsuite name="OwnedTest" tests="2" failures="0" errors="0" skipped="0">'
                '<testcase classname="OwnedTest" name="first"/><testcase classname="OwnedTest" name="first"/>'
                '</testsuite>', encoding='utf8')
            self.assertEqual('incomplete', collect_tests(folder, {'client':['OwnedTest']})['status'])
            xml = leaf/'xml/TEST-OwnedTest.xml'
            valid = xml.read_text(encoding='utf8').replace('name="first"/></testsuite>', 'name="second"/></testsuite>')
            xml.write_text(valid, encoding='utf8')
            self.assertEqual('passed', collect_tests(folder, {'client':['OwnedTest']})['status'])
            # Retain XML but omit this invocation's execution marker.
            (leaf/'started.json').rename(leaf/'retained-previous-start.json')
            self.assertEqual('incomplete', collect_tests(folder, {'client':['OwnedTest']})['status'])
            (leaf/'retained-previous-start.json').rename(leaf/'started.json')
            xml.write_text(valid.replace('name="second"/>', 'name="second"><skipped/></testcase>'), encoding='utf8')
            self.assertEqual('incomplete', collect_tests(folder, {'client':['OwnedTest']})['status'])
            xml.write_text(valid, encoding='utf8')
            executions = folder/'task-executions.json'
            executions.write_text(executions.read_text(encoding='utf8').replace('"upToDate": false', '"upToDate": true'), encoding='utf8')
            self.assertEqual('incomplete', collect_tests(folder, {'client':['OwnedTest']})['status'])

    def test_invalid_registry_or_warm_isolation_does_not_create_a_result(self):
        entry = Path(__file__).with_name('development_check.py')
        with tempfile.TemporaryDirectory(prefix='reforge-developer-invalid-') as temporary:
            for suite, options in [('unknown', []), ('development-air-production', ['--isolated'])]:
                output = Path(temporary)/suite
                result = subprocess.run([sys.executable, '-B', str(entry), '--suite', suite,
                    '--output', str(output), *options], capture_output=True)
                self.assertEqual(2, result.returncode)
                self.assertFalse(output.exists())


if __name__ == '__main__':
    unittest.main()
