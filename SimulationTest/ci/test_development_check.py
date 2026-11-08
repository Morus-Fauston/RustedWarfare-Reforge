"""Developer selection uses the public registry and preserves distinct input contexts."""
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from development_check import collect_tests


class DevelopmentSelectionTest(unittest.TestCase):
    def test_cli_fork_budget_is_bounded_explicit_and_listing_is_read_only(self):
        ci = Path(__file__).parent
        with tempfile.TemporaryDirectory(prefix='reforge-fork-cli-') as temporary:
            output = Path(temporary)/'must-not-exist'
            for entry, selection in [('development_check.py', ['--suite', 'production-combat']),
                                     ('public_check.py', ['--suite', 'production-combat', '--kind', 'pr']),
                                     ('public_batch.py', ['--kind', 'pr'])]:
                for forks in (1, 2, 4):
                    result = subprocess.run([sys.executable, '-B', str(ci/entry), *selection,
                        '--test-forks', str(forks), '--list', '--output', str(output)],
                        capture_output=True, text=True, encoding='utf8')
                    self.assertEqual(0, result.returncode, entry+result.stderr)
                    budget = json.loads(result.stdout)['testConcurrency']
                    self.assertEqual(forks, budget['requested'])
                    self.assertEqual(forks, budget['maxWorkers'])
                    self.assertEqual(1, budget['modules']['pc']['maxParallelForks'])
                    self.assertEqual(forks, budget['modules']['tools']['maxParallelForks'])
                    self.assertFalse(output.exists())
                for forks in ('0', '3', '5', '-1', 'two'):
                    result = subprocess.run([sys.executable, '-B', str(ci/entry), *selection,
                        '--test-forks', forks, '--output', str(output)], capture_output=True)
                    self.assertEqual(2, result.returncode, entry)
                    self.assertFalse(output.exists())

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
            (folder/'effective.json').write_text(json.dumps({'testForks':2, 'maxWorkers':2, 'tests':[
                {'task':':client:test', 'classes':['OwnedTest'], 'maxParallelForks':2, 'forkEvery':0,
                 'excludePatterns':[], 'commandLineIncludePatterns':[], 'fileIncludes':[], 'fileExcludes':[],
                 'junitOptions':{'includeTags':[], 'excludeTags':[], 'includeEngines':[], 'excludeEngines':[]}}]}), encoding='utf8')
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
            (leaf/'methods.json').write_text(json.dumps([
                dict(className='OwnedTest', name=name, started=1, completed=2, result='SUCCESS')
                for name in ('first', 'second')]), encoding='utf8')
            (leaf/'expected-methods.json').write_text(json.dumps([
                dict(className='OwnedTest', name=name) for name in ('first','second')]), encoding='utf8')
            self.assertEqual('passed', collect_tests(folder, {'client':['OwnedTest']})['status'])
            # Listener, XML and completed agree but jointly omit an independently expected method.
            methods = leaf/'methods.json'; completed = leaf/'completed.json'
            full_methods, full_completed = methods.read_bytes(), completed.read_bytes()
            xml.write_text(valid.replace('tests="2"','tests="1"').replace('<testcase classname="OwnedTest" name="second"/>',''), encoding='utf8')
            methods.write_text(json.dumps([dict(className='OwnedTest',name='first',started=1,completed=2,result='SUCCESS')]),encoding='utf8')
            completed.write_text(json.dumps(dict(tests=1,failures=0,skipped=0)),encoding='utf8')
            self.assertEqual('incomplete', collect_tests(folder, {'client':['OwnedTest']})['status'])
            xml.write_text(valid,encoding='utf8'); methods.write_bytes(full_methods); completed.write_bytes(full_completed)
            discovery = leaf/'expected-methods.json'
            discovery.rename(leaf/'missing-expected.json')
            self.assertEqual('incomplete', collect_tests(folder, {'client':['OwnedTest']})['status'])
            (leaf/'missing-expected.json').rename(discovery)
            effective = folder/'effective.json'
            original_config = effective.read_bytes()
            for field in ('excludePatterns','commandLineIncludePatterns','fileIncludes','fileExcludes'):
                config = json.loads(original_config); config['tests'][0][field] = ['partial']
                effective.write_text(json.dumps(config),encoding='utf8')
                self.assertEqual('incomplete', collect_tests(folder, {'client':['OwnedTest']})['status'], field)
            for field in ('includeTags','excludeTags','includeEngines','excludeEngines'):
                config = json.loads(original_config); config['tests'][0]['junitOptions'][field] = ['partial']
                effective.write_text(json.dumps(config),encoding='utf8')
                self.assertEqual('incomplete', collect_tests(folder, {'client':['OwnedTest']})['status'], field)
            effective.write_bytes(original_config)
            (leaf/'methods.json').rename(leaf/'old-methods.json')
            self.assertEqual('incomplete', collect_tests(folder, {'client':['OwnedTest']})['status'])
            (leaf/'old-methods.json').rename(leaf/'methods.json')
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
            executions.write_text(executions.read_text(encoding='utf8').replace('"upToDate": true', '"upToDate": false'), encoding='utf8')
            effective = folder/'effective.json'
            effective.write_text(effective.read_text(encoding='utf8').replace('"maxWorkers": 2', '"maxWorkers": 1'), encoding='utf8')
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
