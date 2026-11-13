"""Run named public mechanisms with warm compilation and fresh test evidence."""
import argparse
import json
import os
from pathlib import Path
import shutil
import sys
import tempfile
import time
import uuid
import xml.etree.ElementTree as ET

from public_check import REPO, MODULES, identities, plan_suite, source_files, test_forks, test_concurrency
from public_distribution import toolchain_identity

sys.path.insert(0, str(REPO/'SimulationTest/tools'))
from workflow import execute_step, resources


def plan_mechanisms(selections):
    groups, deduplicated, python_owners = [], [], {}
    for name, selected in selections:
        if selected['inputScope'] != 'public':
            raise ValueError('Developer checks require public inputs')
        group = next((g for g in groups if g['fixture'] == selected['fixture']), None)
        if selected['tests'] and group is None:
            group = dict(fixture=selected['fixture'], tests={}, suites=[], owners={})
            groups.append(group)
        if selected['tests']:
            group['suites'].append(name)
        for module, classes in selected['tests'].items():
            target = group['tests'].setdefault(module, [])
            owners = group['owners'].setdefault(module, {})
            for cls in classes:
                claims = owners.setdefault(cls, [])
                if name not in claims:
                    claims.append(name)
                if cls in target:
                    deduplicated.append(dict(suite=name, module=module, testClass=cls,
                                             fixture=selected['fixture']))
                else:
                    target.append(cls)
        for path in selected['pythonTests']:
            claims = python_owners.setdefault(path, [])
            if name not in claims:
                claims.append(name)
    return dict(groups=groups, pythonTests=list(python_owners),
                pythonOwners=python_owners, deduplicated=deduplicated)


def plan_development(repo, names):
    sources = {p.relative_to(repo).as_posix() for p in source_files(repo)}
    return dict(schemaVersion=1, suites=names, **plan_mechanisms(
        (name, plan_suite(repo, name, 'mechanism', sources)) for name in names))


def collect_tests(folder, selected):
    """Only this invocation's new task/XML receipts can establish execution."""
    coverage = []
    effective = json.loads((folder/'effective.json').read_text(encoding='utf8'))
    executions = json.loads((folder/'task-executions.json').read_text(encoding='utf8'))
    for module, expected in selected.items():
        task = MODULES[module][0]
        leaf = folder/module
        rows = []
        for path in sorted((leaf/'xml').glob('TEST-*.xml')):
            suite = ET.parse(path).getroot()
            methods = [dict(className=c.get('classname'), name=c.get('name'),
                            skipped=c.find('skipped') is not None,
                            failed=c.find('failure') is not None or c.find('error') is not None)
                       for c in suite.findall('testcase')]
            rows.append(dict(name=suite.get('name'), tests=int(suite.get('tests', 0)),
                failures=int(suite.get('failures', 0)), errors=int(suite.get('errors', 0)),
                skipped=int(suite.get('skipped', 0)), methods=methods))
        started = json.loads((leaf/'started.json').read_text(encoding='utf8')) if (leaf/'started.json').exists() else None
        completed = json.loads((leaf/'completed.json').read_text(encoding='utf8')) if (leaf/'completed.json').exists() else None
        execution = next((x for x in executions if x['task'] == task), None)
        config = next((x for x in effective['tests'] if x['task'] == task), None)
        methods = json.loads((leaf/'methods.json').read_text(encoding='utf8')) if (leaf/'methods.json').exists() else []
        expected_methods = json.loads((leaf/'expected-methods.json').read_text(encoding='utf8')) if (leaf/'expected-methods.json').exists() else []
        observed = [(m['className'], m['name']) for r in rows for m in r['methods']]
        executed = [(m['className'], m['name']) for m in methods]
        discovered = [(m['className'], m['name']) for m in expected_methods]
        valid = (set(r['name'] for r in rows) == set(expected) and len(rows) == len(expected)
                 and discovered and len(discovered) == len(set(discovered)) and sorted(discovered) == sorted(executed)
                 and len(executed) == len(set(executed)) and sorted(executed) == sorted(observed)
                 and all(m.get('started') and m.get('completed', 0) >= m['started'] and
                         m.get('result') == 'SUCCESS' for m in methods)
                 and all(r['tests'] > 0 and r['tests'] == len(r['methods']) and
                         not any(r[k] for k in ('failures', 'errors', 'skipped')) and
                         len({m['name'] for m in r['methods']}) == len(r['methods']) and
                         all(m['name'] and m['className'] == r['name'] and not m['skipped'] and not m['failed'] for m in r['methods'])
                         for r in rows)
                 and started and completed and completed['tests'] == sum(r['tests'] for r in rows)
                 and completed['failures'] == 0 and completed['skipped'] == 0
                 and execution and not execution['skipped'] and not execution['upToDate']
                 and not execution['noSource'] and not execution['failure']
                 and config and set(config['classes']) == set(expected)
                 and all(config.get(key) == [] for key in ('excludePatterns','commandLineIncludePatterns','fileIncludes','fileExcludes'))
                 and config.get('junitOptions') == {key:[] for key in ('includeTags','excludeTags','includeEngines','excludeEngines')}
                 and effective.get('testForks') in (1, 2, 4)
                 and effective.get('maxWorkers') == effective['testForks']
                 and config.get('maxParallelForks') == (1 if module == 'pc' else effective['testForks'])
                 and config.get('forkEvery') == 0)
        coverage.append(dict(module=module, task=task, status='passed' if valid else 'incomplete',
                             classes=rows, methods=methods, expectedMethods=expected_methods,
                             started=started, completed=completed, execution=execution))
    return dict(status='passed' if coverage and all(r['status']=='passed' for r in coverage) else 'incomplete',
                coverage=coverage, effective=effective, tasks=executions)


def execute_test_group(repo, folder, group, cold, forks, package=False, timeout=600):
    """One selected Test execution; compilation may stay warm, results never do."""
    folder.mkdir()
    project = repo/'SimulationTest'
    control = dict(group, output=str(folder), repo=str(repo), testForks=forks, archiveRun=uuid.uuid4().hex,
                   tasks={MODULES[m][0]:dict(module=m, classes=classes)
                          for m, classes in group['tests'].items()})
    (folder/'selection.json').write_text(json.dumps(control, ensure_ascii=True, indent=2), encoding='utf8')
    record = dict(selection=group, path=folder.name,
        ignoredPreviousXml=[str(p.relative_to(project)) for m in group['tests']
                            for p in sorted((project/MODULES[m][1]).glob('TEST-*.xml'))])
    wrapper = [str(project/'gradlew.bat')] if os.name=='nt' else ['sh', str(project/'gradlew')]
    argv = wrapper + list(control['tasks']) + ([':installDist'] if package else []) + [
        '--console=plain', '--max-workers='+str(forks), '--no-build-cache', '--init-script',
        str(repo/'SimulationTest/ci/development_tests.gradle'), '-Ddevelopment.selection='+str(folder/'selection.json')]
    argv += ['--no-daemon', '--rerun-tasks'] if cold else ['--daemon']
    record['command'] = execute_step(argv, project, folder/'command', timeout=timeout)
    try:
        record['tests'] = collect_tests(folder, group['tests'])
    except (OSError, ValueError, KeyError, ET.ParseError) as error:
        record['tests'] = dict(status='incomplete', error=str(error))
    return record


def method_owners(group, tests):
    return [
        dict(module=coverage['module'], className=method['className'], method=method['name'],
             suites=group['owners'][coverage['module']][method['className']])
        for coverage in tests['coverage'] for method in coverage['methods']
    ]


def run_development(repo, output, plan, cold, forks=1, timeout=600, *, admit=None, prepared=False):
    output.mkdir(parents=True, exist_ok=False)
    started = time.monotonic()
    initial = identities(repo)
    result = dict(schemaVersion=1, status='execution-failed', selection=plan, cold=cold,
                  source=initial, testConcurrency=test_concurrency(
                      {m: [] for g in plan['groups'] for m in g['tests']}, forks),
                  groups=[], pythonCoverage=[], exclusions=['scenario-execution',
                  'distribution-packaging', 'platform-input', 'formal-performance'])
    try:
        toolchain = toolchain_identity()
        result['toolchainBefore'] = toolchain
        project = repo/'SimulationTest'
        for index, group in enumerate(plan['groups']):
            folder = output/('group-'+str(index))
            with resources(['build:'+str(repo/'Engine'), 'build:'+str(repo/'SimulationTest')]):
                if admit is not None:
                    admit()
                record = execute_test_group(repo, folder, group, cold, forks, timeout=timeout)
                result['groups'].append(record)
                if admit is not None:
                    admit()
            if not record['command']['passed'] or record['tests']['status'] != 'passed':
                raise RuntimeError('Current test execution did not pass: '+folder.name)
            tasks = {MODULES[module][0] for module in group['tests']}
            if prepared and any(row.get('didWork') for row in record['tests']['tasks'] if row['task'] not in tasks):
                raise RuntimeError('Prepared compilation or packaging executed again')
            record['methodOwners'] = method_owners(group, record['tests'])
        for index, path in enumerate(plan['pythonTests']):
            summary = output/('python-'+str(index)+'.json')
            if admit is not None:
                admit()
            command = execute_step([sys.executable, '-B', str(repo/'SimulationTest/ci/python_check.py'),
                '--test', str(repo/path), '--output', str(summary)], repo, output/('python-'+str(index)), timeout=timeout)
            measured = json.loads(summary.read_text(encoding='utf8'))
            result['pythonCoverage'].append(dict(path=path, command=command, result=measured,
                                                 suites=plan['pythonOwners'][path]))
            if admit is not None:
                admit()
            if not command['passed'] or measured['status'] != 'passed':
                raise RuntimeError('Python mechanism did not pass: '+path)
        result['toolchainAfter'] = toolchain_identity()
        if identities(repo) != initial or result['toolchainAfter'] != toolchain:
            raise RuntimeError('Source, input or toolchain changed during development check')
        result['status'] = 'passed'
    except Exception as error:
        result['error'] = str(error)
    result['elapsedSeconds'] = time.monotonic()-started
    (output/'result.json').write_text(json.dumps(result, ensure_ascii=True, indent=2)+'\n', encoding='utf8')
    print(json.dumps(dict(status=result['status'], output=str(output), error=result.get('error')), ensure_ascii=True))
    return 0 if result['status']=='passed' else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--suite', action='append', required=True, help='Public named mechanism suite; repeat to combine')
    parser.add_argument('--list', action='store_true', help='List exact selection without execution')
    parser.add_argument('--output', type=Path, help='New result directory; previous results are never consumed')
    parser.add_argument('--cold', action='store_true', help='Single-use daemon and full task rebuild')
    parser.add_argument('--isolated', action='store_true', help='Cold check in a fresh public source/project copy')
    parser.add_argument('--test-forks', type=test_forks, default=1, help='Bound independent test JVMs: 1, 2 or 4; PC stays serial')
    args = parser.parse_args()
    try:
        plan = plan_development(REPO, args.suite)
    except (ValueError, KeyError, TypeError, OSError) as error:
        parser.error(str(error))
    if args.list:
        print(json.dumps(dict(plan, testConcurrency=test_concurrency(
            {m: [] for g in plan['groups'] for m in g['tests']}, args.test_forks)), ensure_ascii=True))
        return 0
    if args.output is None or args.output.exists():
        parser.error('--output must name a new result directory')
    if args.isolated and not args.cold:
        parser.error('--isolated requires --cold; warm checks reuse the current project build')
    repo = REPO
    if args.isolated:
        repo = Path(tempfile.mkdtemp(prefix='reforge-development-cold-'))
        for path in source_files(REPO):
            target = repo/path.relative_to(REPO); target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(path, target)
    return run_development(repo, args.output.resolve(), plan, args.cold, args.test_forks)


if __name__ == '__main__':
    raise SystemExit(main())
