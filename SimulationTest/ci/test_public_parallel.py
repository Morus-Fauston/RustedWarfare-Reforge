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
                       commands=[dict(name='probe', exitCode=0, firstError=None)])
            payload = dict(status='passed', firstError=None, requestedWorkers=4, peakRunning=1, items=[raw])
            with zipfile.ZipFile(archive, 'w') as output:
                output.writestr('scenario-execution.json', json.dumps(payload))
            bundle = dict(shards={'production-1': [case]})
            receipt['coverage'] = dict(scenarios=[dict(id='probe', kind='short', suite='production-combat',
                status='passed', elapsedSeconds=1, archive=raw['archive'], commands=['probe'])])
            inspect_raw_evidence(archive, digest(archive), receipt, bundle)
            raw['commands'].append(dict(name='probe-recompare', exitCode=0, firstError=None))
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

    def test_long_layer_balances_all_scenarios_across_three_shards_and_enforces_conservation(self):
        from public_check import REPO
        from public_batch import plan_layer
        from public_parallel import LONG_SHARDS, new_plan

        expected_layer = plan_layer(REPO, 'long')
        expected_ids = [case['id'] for row in expected_layer['suites'] for case in row['plan']['scenarios']]
        plan = new_plan(REPO, layer_kind='long')
        self.assertIsNone(plan['mechanisms'])
        self.assertEqual(set(LONG_SHARDS), set(plan['shards']))
        sharded_ids = [case['id'] for shard in LONG_SHARDS for case in plan['shards'][shard]]
        self.assertEqual(len(expected_ids), len(sharded_ids))
        self.assertEqual(set(expected_ids), set(sharded_ids))
        counts = [len(plan['shards'][shard]) for shard in LONG_SHARDS]
        self.assertLessEqual(max(counts) - min(counts), 1)

        identity = dict(run='200', attempt='1', head='a'*40, checkout='b'*40, tree='c'*40)
        bundle = dict(plan, identity=identity, toolchain={'jdk': '25'}, gradle={'version': '9.3.1'}, archiveSha256='d'*64)
        plan_sha = 'f'*64
        with tempfile.TemporaryDirectory() as tmp:
            folder = Path(tmp)
            pins = {}
            for shard, cases in bundle['shards'].items():
                scenarios = [dict(suite=c['suite'], id=c['id'], kind=c['kind'], status='passed',
                                  elapsedSeconds=1.0,
                                  commands=[c['id']] + ([c['id']+'-recompare'] if c.get('recompare') else []),
                                  archive=dict(id=c['id'], kind=c['kind']))
                             for c in cases]
                row = dict(identity=identity, planSha256=plan_sha, toolchain=bundle['toolchain'],
                           archiveSha256=bundle['archiveSha256'], status='passed', gradle=None,
                           shard=shard, coverage=dict(scenarios=scenarios))
                path = folder/f'public-long-receipt-1-{shard}'/'receipt.json'
                path.parent.mkdir()
                write_json(path, row)
                pins[shard] = digest(path)
            artifacts = {shard: '2'*64 for shard in LONG_SHARDS}
            ok = aggregate(bundle, plan_sha, folder, pins, dict(prepare='success', scenarios='success'),
                           artifacts, lambda k, r: None, receipt_prefix='public-long-receipt', layer_kind='long')
            self.assertEqual('passed', ok['status'], ok['errors'])
            bad_job = aggregate(bundle, plan_sha, folder, pins, dict(prepare='success', scenarios='cancelled'),
                                artifacts, lambda k, r: None, receipt_prefix='public-long-receipt', layer_kind='long')
            self.assertEqual('execution-failed', bad_job['status'])
            (folder/'public-long-receipt-1-long-3'/'receipt.json').unlink()
            missing = aggregate(bundle, plan_sha, folder, pins, dict(prepare='success', scenarios='success'),
                                artifacts, lambda k, r: None, receipt_prefix='public-long-receipt', layer_kind='long')
            self.assertEqual('execution-failed', missing['status'])

    def test_workflows_enable_repo_wide_parallel_pr_and_sharded_long_with_fallbacks(self):
        import re
        from public_check import REPO
        from public_parallel import MECHANISMS
        pr_yml = (REPO/'.github/workflows/public-pr.yml').read_text(encoding='utf8')
        self.assertNotIn('codex/phase-six-asp-test', pr_yml)
        self.assertIn("vars.PUBLIC_PR_PARALLEL == 'false'", pr_yml)
        self.assertIn("inputs.execution == 'serial'", pr_yml)
        for shard in MECHANISMS:
            self.assertIn(shard, pr_yml)
            self.assertIn(f'{shard.replace("-", "_")}_artifact', pr_yml)

        long_yml = (REPO/'.github/workflows/public-long.yml').read_text(encoding='utf8')
        for token in ('public-long-prepare:', 'public-long-scenarios:', 'public-long-coverage:',
                      'public-final-candidate:', 'long-1', 'long-2', 'long-3',
                      "vars.PUBLIC_LONG_PARALLEL == 'false'"):
            self.assertIn(token, long_yml)

        draft_yml = (REPO/'SimulationTest/ci/public-pr.yml').read_text(encoding='utf8')
        versions = []
        for path_label, content in (('.github/workflows/public-pr.yml', pr_yml),
                                    ('.github/workflows/public-long.yml', long_yml),
                                    ('SimulationTest/ci/public-pr.yml', draft_yml)):
            blocks = re.findall(r'uses:\s*actions/setup-java@[^\r\n]+\r?\n\s+with:\r?\n\s+distribution:\s*(\S+)\r?\n\s+java-version:\s*[\'"]?([^\'"\r\n]+)[\'"]?', content)
            self.assertGreaterEqual(len(blocks), 1, path_label)
            for dist, ver in blocks:
                self.assertEqual('temurin', dist, path_label)
                self.assertRegex(ver, r'^25\.\d+\.\d+(?:\.\d+)?\+\d+$', f'{path_label} uses floating JDK version: {ver}')
                versions.append(ver)
        self.assertEqual(1, len(set(versions)), f'Workflows disagree on pinned Temurin version: {set(versions)}')

    def test_toolchain_mismatch_is_rejected_and_mechanisms_shard_with_strict_conservation(self):
        from public_check import REPO
        from public_parallel import MECHANISMS, SCENARIOS, aggregate, new_plan, run_shard, write_json

        plan = new_plan(REPO, layer_kind='pr')
        self.assertEqual(4, len(MECHANISMS))
        self.assertEqual({*MECHANISMS, *SCENARIOS}, set(plan['shards']))
        self.assertEqual(set(MECHANISMS), set(plan['mechanismShards']))

        full_mech = plan['mechanisms']
        expected_pairs = {(g['fixture'], mod, cls): tuple(g['owners'][mod][cls])
                          for g in full_mech['groups'] for mod, classes in g['tests'].items() for cls in classes}
        sharded_pairs = []
        for shard in MECHANISMS:
            sub = plan['mechanismShards'][shard]
            shard_class_count = sum(len(classes) for g in sub['groups'] for classes in g['tests'].values())
            self.assertGreater(shard_class_count, 0, shard)
            self.assertLessEqual(shard_class_count, 400, f'{shard} exceeds safe single-job class budget: {shard_class_count}')
            for g in sub['groups']:
                for mod, classes in g['tests'].items():
                    for cls in classes:
                        sharded_pairs.append(((g['fixture'], mod, cls), tuple(g['owners'][mod][cls])))
        self.assertEqual(len(expected_pairs), len(sharded_pairs))
        self.assertEqual(expected_pairs, dict(sharded_pairs))

        sharded_py = [p for shard in MECHANISMS for p in plan['mechanismShards'][shard]['pythonTests']]
        self.assertEqual(full_mech['pythonTests'], sharded_py)

        # Toolchain drift check in run_shard (e.g., 25.0.5+8 vs 25.0.5.1+2)
        identity = dict(run='300', attempt='1', head='a'*40, checkout='b'*40, tree='c'*40)
        base_tools = {'javaVersion': ['SEMANTIC_VERSION="25.0.5+8"']}
        drifted_tools = {'javaVersion': ['SEMANTIC_VERSION="25.0.5.1+2"']}
        bundle = dict(plan, identity=identity, source=[{'path': 'in', 'sha256': '1'}],
                      toolchain=base_tools, gradle={'version': '9.3.1'}, archiveSha256='d'*64, files=[])
        with (tempfile.TemporaryDirectory() as tmp,
              patch('public_parallel.candidate', return_value=identity),
              patch('public_parallel.identities', return_value=bundle['source']),
              patch('public_parallel.toolchain', return_value=drifted_tools)):
            mismatch = run_shard(Path(tmp), bundle, '0'*64, MECHANISMS[0], None, Path(tmp)/'mismatch')
            self.assertEqual('execution-failed', mismatch['status'])
            self.assertEqual('Shard toolchain differs', mismatch['error'])

        # Conservation check in aggregate across all 4 mechanism shards + scenario shards
        plan_sha = '9'*64
        with tempfile.TemporaryDirectory() as tmp:
            folder = Path(tmp)
            pins = {}
            for shard in bundle['shards']:
                if shard in MECHANISMS:
                    sub = bundle['mechanismShards'][shard]
                    cov = dict(
                        groups=[dict(fixture=g['fixture'], methods=[
                            dict(module=mod, className=cls, method='test_case()', suites=g['owners'][mod][cls])
                            for mod, classes in g['tests'].items() for cls in classes
                        ]) for g in sub['groups']],
                        python=[dict(path=p, suites=sub['pythonOwners'][p], tests=1) for p in sub['pythonTests']]
                    )
                    gradle_val = bundle['gradle']
                else:
                    cases = bundle['shards'][shard]
                    cov = dict(scenarios=[dict(suite=c['suite'], id=c['id'], kind=c['kind'], status='passed',
                                               elapsedSeconds=1.0,
                                               commands=[c['id']] + ([c['id']+'-recompare'] if c.get('recompare') else []) + ([c['id']+'-assertions'] if c.get('archiveAssertions') else []),
                                               archive=dict(id=c['id'], kind=c['kind'])) for c in cases])
                    gradle_val = None
                row = dict(identity=identity, planSha256=plan_sha, toolchain=base_tools,
                           archiveSha256=bundle['archiveSha256'], status='passed', gradle=gradle_val,
                           shard=shard, coverage=cov)
                p = folder/f'public-receipt-1-{shard}'/'receipt.json'
                p.parent.mkdir()
                write_json(p, row)
                pins[shard] = digest(p)
            artifacts = {shard: '3'*64 for shard in bundle['shards']}
            jobs = dict(prepare='success', mechanism='success', scenarios='success')
            ok = aggregate(bundle, plan_sha, folder, pins, jobs, artifacts, lambda k, r: None)
            self.assertEqual('passed', ok['status'], ok['errors'])

            # Tamper: duplicate a class from mechanisms-1 into mechanisms-2 -> must fail conservation
            m2_path = folder/f'public-receipt-1-{MECHANISMS[1]}'/'receipt.json'
            m2_orig = m2_path.read_bytes()
            m1_row = json.loads((folder/f'public-receipt-1-{MECHANISMS[0]}'/'receipt.json').read_bytes())
            m2_bad = json.loads(m2_orig)
            m2_bad['coverage']['groups'][0]['methods'].append(m1_row['coverage']['groups'][0]['methods'][0])
            m2_path.write_text(json.dumps(m2_bad), encoding='utf8')
            pins[MECHANISMS[1]] = digest(m2_path)
            dup_res = aggregate(bundle, plan_sha, folder, pins, jobs, artifacts, lambda k, r: None)
            self.assertEqual('execution-failed', dup_res['status'])
            m2_path.write_bytes(m2_orig)
            pins[MECHANISMS[1]] = digest(m2_path)


if __name__ == '__main__':
    unittest.main()
