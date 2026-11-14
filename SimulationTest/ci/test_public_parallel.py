"""Admission failures for the public CI matrix."""
from contextlib import nullcontext
from copy import deepcopy
from unittest.mock import patch
import zipfile
import json
from pathlib import Path
import tempfile
import unittest

from public_parallel import (
    allocate, aggregate, copy_distribution, verify_distribution, write_json,
    inspect_raw_evidence, gradle_version, run_shard,
)
from public_distribution import digest, package_files


class PublicParallelTest(unittest.TestCase):
    def test_production_uses_largest_first_and_backend_medians(self):
        cases = [dict(suite='production-combat', id=name, backend=backend)
                 for name, backend in [('a', 'core'), ('b', 'core'), ('c', 'session'),
                                       ('d', 'core'), ('e', 'session'), ('f', 'core')]]
        cases += [dict(suite='content-three-formats', id='format'), dict(suite='platform-basics', id='platform')]
        timing = {'core': {'a': 12, 'b': 5, 'd': 3}, 'session': {'c': 8}}
        jobs = allocate(cases, timing)
        self.assertEqual(['a', 'd'], [row['id'] for row in jobs['production-1']])
        self.assertEqual(['c', 'b'], [row['id'] for row in jobs['production-2']])
        self.assertEqual(['e', 'f'], [row['id'] for row in jobs['production-3']])
        self.assertEqual(['format'], [row['id'] for row in jobs['content-three-formats']])
        self.assertEqual(['platform'], [row['id'] for row in jobs['other-short']])

    def test_package_relocation_verifies_relative_names_and_bytes(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp)
            package = base/'first'
            (package/'lib').mkdir(parents=True)
            (package/'lib/probe.jar').write_bytes(b'original')
            files = package_files(package)
            archive = base/'transfer.tar'
            copy_distribution(package, archive, files)
            relocated = base/'second'
            self.assertEqual(relocated, verify_distribution(archive, digest(archive), relocated, files))
            self.assertEqual(files, package_files(relocated))
            with self.assertRaises(ValueError):
                verify_distribution(archive, '0'*64, base/'rejected', files)
            with self.assertRaises(ValueError):
                verify_distribution(archive, digest(archive), base/'tampered',
                                    [dict(files[0], path='../escape')])

    def test_actual_gradle_version_uses_stable_distribution_fields(self):
        text = '''
------------------------------------------------------------
Gradle 9.3.1
------------------------------------------------------------

Build time:    2026-01-29 14:15:42 UTC
Revision:      26a13fa2aede8c42a9a389d8d5c1f73292c1b1e9

Kotlin:        2.2.21
Groovy:        4.0.29
Ant:           Apache Ant(TM) version 1.10.15
Launcher JVM: 25
Daemon JVM: /different/worktree/jdk
OS: Linux
'''
        result = gradle_version(text)
        self.assertEqual('9.3.1', result['version'])
        self.assertEqual(40, len(result['Revision']))
        self.assertNotEqual(result, gradle_version(text.replace('9.3.1', '9.3.2')))
        with self.assertRaises(ValueError):
            gradle_version(text.replace('Revision:', ''))

    def test_mechanism_input_drift_stops_before_the_next_operation(self):
        identity, tools = {'event':'local'}, {'python': '3.13'}
        plan = dict(identity=identity, source=[{'path':'input','sha256':'a'}],
                    toolchain=tools, gradle={'version':'9.3.1'}, archiveSha256='d'*64, files=[],
                    shards={'mechanisms':[]}, mechanisms={})
        source = plan['source']
        group = dict(fixture=None, tests={'core':['Probe']},
                     owners={'core':{'Probe':['production-combat']}})
        plan['mechanisms'] = dict(groups=[group, group], pythonTests=[], pythonOwners={})
        result = dict(command={'passed':True}, tests=dict(status='passed', coverage=[],
            tasks=[dict(task=':core:compileTestKotlin', didWork=True), dict(task=':core:test', didWork=True)]))
        process = dict(pythonCoverage=[], groups=[dict(selection=group, methodOwners=[])]*2)
        with (tempfile.TemporaryDirectory() as tmp,
              patch('public_parallel.candidate', return_value=identity),
              patch('public_parallel.toolchain', return_value=tools),
              patch('public_parallel.identities', return_value=source) as identity_check,
              patch('development_check.identities', return_value=source),
              patch('public_parallel.measure_gradle', return_value=plan['gradle']),
              patch('public_parallel.verify_distribution', return_value=Path(tmp)/'portable'),
              patch('public_parallel.package_files', return_value=[]),
              patch('public_parallel.verify_mechanism_receipt', return_value=process),
              patch('development_check.resources', side_effect=lambda names: nullcontext()),
              patch('development_check.execute_test_group', side_effect=lambda *args, **kwargs: deepcopy(result)) as execute):
            repo = Path(tmp)
            success = run_shard(repo, plan, '0'*64, 'mechanisms', None, repo/'success')
            self.assertEqual('passed', success['status'], success.get('error'))
            self.assertEqual(2, execute.call_count)
            from development_check import run_development
            self.assertEqual(1, run_development(repo, repo/'prepared', plan['mechanisms'], False,
                                               admit=lambda: None, prepared=True))
            self.assertIn('Prepared compilation', json.loads((repo/'prepared/result.json').read_bytes())['error'])
            execute.reset_mock()
            identity_check.side_effect = [source, source, [{'path':'input','sha256':'b'}]]
            drift = run_shard(repo, plan, '0'*64, 'mechanisms', None, repo/'drift')
            self.assertEqual('execution-failed', drift['status'])
            self.assertEqual(1, execute.call_count)

    def test_raw_archive_must_exist_match_digest_and_contain_one_current_result(self):
        receipt = dict(shard='production-1')
        with tempfile.TemporaryDirectory() as tmp:
            archive = Path(tmp)/'raw.zip'
            with self.assertRaises(FileNotFoundError):
                inspect_raw_evidence(archive, '0'*64, receipt, {})
            with zipfile.ZipFile(archive, 'w') as output:
                output.writestr('other.json','{}')
            with self.assertRaises(ValueError):
                inspect_raw_evidence(archive, '0'*64, receipt, {})
            with self.assertRaises(ValueError):
                inspect_raw_evidence(archive, digest(archive), receipt, {})
            case = dict(id='probe', kind='short', suite='production-combat', backend='session')
            raw = dict(id='probe', kind='short', status='passed', startedUnix=1, endedUnix=2,
                       archive=dict(id='probe', kind='short', path='/actual/archive'),
                       commands=[dict(name=name, exitCode=0, firstError=None)
                                 for name in ('probe', 'probe-recompare')])
            payload = dict(status='passed', firstError=None, requestedWorkers=4, peakRunning=1, items=[raw])
            with zipfile.ZipFile(archive, 'w') as output:
                output.writestr('scenario-execution.json', json.dumps(payload))
            bundle = dict(shards={'production-1': [case]})
            receipt['coverage'] = dict(scenarios=[dict(id='probe', kind='short', suite='production-combat',
                status='passed', elapsedSeconds=1, archive=raw['archive'], commands=['probe','probe-recompare'])])
            inspect_raw_evidence(archive, digest(archive), receipt, bundle)
            raw['commands'].pop()
            with zipfile.ZipFile(archive, 'w') as output:
                output.writestr('scenario-execution.json', json.dumps(payload))
            with self.assertRaises(ValueError):
                inspect_raw_evidence(archive, digest(archive), receipt, bundle)

    def test_aggregation_failures_are_closed_and_keep_other_receipts(self):
        identity = dict(run='100', attempt='2', head='a'*40, checkout='b'*40, tree='c'*40)
        bundle = dict(identity=identity, toolchain={'jdk': '25'}, gradle={'version': '9.3.1'}, archiveSha256='d'*64,
                      shards={'mechanisms': [], 'production-1': [dict(suite='production-combat', id='a', kind='short')]},
                      mechanisms=dict(groups=[], pythonTests=[], pythonOwners={}))
        plan_sha = 'e'*64
        mechanism = dict(groups=[], python=[])
        scenario = dict(suite='production-combat', id='a', kind='short', status='passed',
                        elapsedSeconds=10, commands=['a'], archive=dict(id='a', kind='short'))
        good = {shard: dict(identity=identity, planSha256=plan_sha, toolchain=bundle['toolchain'],
                            archiveSha256=bundle['archiveSha256'], status='passed',
                            gradle=None if cases else bundle['gradle'],
                            shard=shard, coverage=dict(scenarios=[scenario]) if cases else mechanism)
                for shard, cases in bundle['shards'].items()}
        with tempfile.TemporaryDirectory() as tmp:
            folder = Path(tmp)
            pins = {}
            for shard, row in good.items():
                path = folder/f'public-receipt-2-{shard}'/'receipt.json'
                path.parent.mkdir()
                write_json(path, row)
                pins[shard] = digest(path)
            jobs = dict(prepare='success', mechanism='success', scenarios='success')
            def result(results=None):
                verified = {key: '1'*64 for key in good}
                return aggregate(bundle, plan_sha, folder, pins, results or jobs, verified,
                                 lambda key, row: self.assertEqual(key, row['shard']))

            self.assertEqual('passed', result()['status'])
            for bad in ('cancelled', 'failure', 'skipped'):
                self.assertEqual('execution-failed', result(dict(jobs, scenarios=bad))['status'])
            artifacts = {key: '1'*64 for key in good if key != 'production-1'}
            self.assertEqual('execution-failed', aggregate(bundle, plan_sha, folder, pins, jobs, artifacts, lambda key, row: None)['status'])
            path = folder/'public-receipt-2-production-1'/'receipt.json'
            clean = path.read_bytes()
            for key, value in [('identity', dict(identity, head='f'*40)),
                               ('identity', dict(identity, attempt='1')),
                               ('status', 'execution-failed'),
                               ('archiveSha256', '0'*64),
                               ('coverage', dict(scenarios=[])),
                               ('coverage', dict(scenarios=[scenario, scenario]))]:
                with self.subTest(key=key, value=value):
                    bad = deepcopy(good['production-1'])
                    bad[key] = value
                    path.write_text(json.dumps(bad), encoding='utf8')
                    self.assertEqual('execution-failed', result()['status'])
                    pins['production-1'] = digest(path)
                    self.assertEqual('execution-failed', result()['status'])
            path.write_bytes(clean)
            pins['production-1'] = digest(path)
            duplicate = folder/'public-receipt-2-copy'
            duplicate.mkdir()
            (duplicate/'receipt.json').write_bytes(clean)
            self.assertEqual('execution-failed', result()['status'])
            (duplicate/'receipt.json').unlink()
            duplicate.rmdir()
            path.unlink()
            failed = result()
            self.assertEqual('execution-failed', failed['status'])
            self.assertIn('mechanisms', failed['receipts'])


if __name__ == '__main__':
    unittest.main()
