"""The runner must preserve actual unittest outcomes, including empty/skipped suites."""
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


class PythonCheckTest(unittest.TestCase):
    def test_actual_pass_failure_skip_and_empty_are_distinct_and_retained(self):
        runner = Path(__file__).with_name('python_check.py')
        with tempfile.TemporaryDirectory(prefix='python-check-') as folder:
            root = Path(folder)
            cases = [('pass', 'self.assertEqual(3, 1+2)', 0, 'passed'),
                     ('fail', 'self.fail("literal failure")', 1, 'failed'),
                     ('skip', 'self.skipTest("missing environment")', 1, 'incomplete'),
                     ('empty', None, 1, 'incomplete')]
            for name, body, code, status in cases:
                source=root/('test_'+name+'.py')
                source.write_text('import unittest\n'+('class Example(unittest.TestCase):\n def test_example(self):\n  '+body+'\n' if body else ''),encoding='utf8')
                output=root/(name+'.json')
                result=subprocess.run([sys.executable,'-B',str(runner),'--test',str(source),'--output',str(output)],capture_output=True)
                self.assertEqual(code,result.returncode,result.stderr)
                record=json.loads(output.read_text(encoding='utf8'))
                self.assertEqual(status,record['status'])
                self.assertEqual(0 if body is None else 1,record['tests'])
                original=output.read_bytes()
                again=subprocess.run([sys.executable,'-B',str(runner),'--test',str(source),'--output',str(output)],capture_output=True)
                self.assertNotEqual(0,again.returncode)
                self.assertEqual(original,output.read_bytes())


if __name__ == '__main__':
    unittest.main()
