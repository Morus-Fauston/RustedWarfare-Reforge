"""Run named public mechanisms with warm compilation and fresh test evidence."""
import argparse
import json
import os
from pathlib import Path
import shutil
import sys
import tempfile
import time
import xml.etree.ElementTree as ET

from public_check import REPO, MODULES, identities, plan_suite, source_files
from public_distribution import toolchain_identity

sys.path.insert(0, str(REPO/'SimulationTest/tools'))
from workflow import execute_step, resources


def plan_development(repo, names):
    groups, deduplicated, python_tests = [], [], []
    for name in names:
        selected = plan_suite(repo, name, 'mechanism')
        if selected['inputScope'] != 'public':
            raise ValueError('Developer checks require public inputs')
        group = next((g for g in groups if g['fixture'] == selected['fixture']), None)
        if selected['tests'] and group is None:
            group = dict(fixture=selected['fixture'], tests={}, suites=[])
            groups.append(group)
        if selected['tests']:
            group['suites'].append(name)
        for module, classes in selected['tests'].items():
            target = group['tests'].setdefault(module, [])
            for cls in classes:
                if cls in target:
                    deduplicated.append(dict(suite=name, module=module, testClass=cls,
                                             fixture=selected['fixture']))
                else:
                    target.append(cls)
        for path in selected['pythonTests']:
            if path not in python_tests:
                python_tests.append(path)
    return dict(schemaVersion=1, suites=names, groups=groups,
                pythonTests=python_tests, deduplicated=deduplicated)


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
        valid = (set(r['name'] for r in rows) == set(expected) and len(rows) == len(expected)
                 and all(r['tests'] > 0 and r['tests'] == len(r['methods']) and
                         not any(r[k] for k in ('failures', 'errors', 'skipped')) and
                         len({m['name'] for m in r['methods']}) == len(r['methods']) and
                         all(m['name'] and m['className'] == r['name'] and not m['skipped'] and not m['failed'] for m in r['methods'])
                         for r in rows)
                 and started and completed and completed['tests'] == sum(r['tests'] for r in rows)
                 and completed['failures'] == 0 and completed['skipped'] == 0
                 and execution and not execution['skipped'] and not execution['upToDate']
                 and not execution['noSource'] and not execution['failure']
                 and config and set(config['classes']) == set(expected))
        coverage.append(dict(module=module, task=task, status='passed' if valid else 'incomplete',
                             classes=rows, started=started, completed=completed, execution=execution))
    return dict(status='passed' if coverage and all(r['status']=='passed' for r in coverage) else 'incomplete',
                coverage=coverage, effective=effective, tasks=executions)


def run_development(repo, output, plan, cold):
    output.mkdir(parents=True, exist_ok=False)
    started = time.monotonic()
    initial = identities(repo)
    result = dict(schemaVersion=1, status='execution-failed', selection=plan, cold=cold,
                  source=initial, groups=[], pythonCoverage=[], exclusions=['scenario-execution',
                  'distribution-packaging', 'platform-input', 'formal-performance'])
    try:
        toolchain = toolchain_identity()
        result['toolchainBefore'] = toolchain
        project = repo/'SimulationTest'
        for index, group in enumerate(plan['groups']):
            folder = output/('group-'+str(index)); folder.mkdir()
            control = dict(group, output=str(folder), repo=str(repo),
                           tasks={MODULES[m][0]:dict(module=m, classes=classes)
                                  for m, classes in group['tests'].items()})
            (folder/'selection.json').write_text(json.dumps(control, ensure_ascii=True, indent=2), encoding='utf8')
            record = dict(selection=group, path=folder.name,
                ignoredPreviousXml=[str(p.relative_to(project)) for m in group['tests']
                                    for p in sorted((project/MODULES[m][1]).glob('TEST-*.xml'))])
            result['groups'].append(record)
            wrapper = [str(project/'gradlew.bat')] if os.name=='nt' else ['sh', str(project/'gradlew')]
            argv = wrapper + list(control['tasks']) + ['--console=plain', '--max-workers=1',
                '--no-build-cache', '--init-script', str(repo/'SimulationTest/ci/development_tests.gradle'),
                '-Ddevelopment.selection='+str(folder/'selection.json')]
            argv += ['--no-daemon', '--rerun-tasks'] if cold else ['--daemon']
            # The command and its subprocess tree retain the workflow's bounded execution/failure receipts.
            record['command'] = execute_step(argv, project, folder/'command')
            record['tests'] = collect_tests(folder, group['tests'])
            if not record['command']['passed'] or record['tests']['status'] != 'passed':
                raise RuntimeError('Current test execution did not pass: '+folder.name)
        for index, path in enumerate(plan['pythonTests']):
            summary = output/('python-'+str(index)+'.json')
            command = execute_step([sys.executable, '-B', str(repo/'SimulationTest/ci/python_check.py'),
                '--test', str(repo/path), '--output', str(summary)], repo, output/('python-'+str(index)))
            measured = json.loads(summary.read_text(encoding='utf8'))
            result['pythonCoverage'].append(dict(path=path, command=command, result=measured))
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
    args = parser.parse_args()
    try:
        plan = plan_development(REPO, args.suite)
    except (ValueError, KeyError, TypeError, OSError) as error:
        parser.error(str(error))
    if args.list:
        print(json.dumps(plan, ensure_ascii=True))
        return 0
    if args.output is None or args.output.exists():
        parser.error('--output must name a new result directory')
    if args.isolated and not args.cold:
        parser.error('--isolated requires --cold; warm checks reuse the current project build')
    repo = REPO
    with resources(['build:'+str(REPO/'Engine'), 'build:'+str(REPO/'SimulationTest')]):
        if args.isolated:
            repo = Path(tempfile.mkdtemp(prefix='reforge-development-cold-'))
            for path in source_files(REPO):
                target = repo/path.relative_to(REPO); target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(path, target)
        return run_development(repo, args.output.resolve(), plan, args.cold)


if __name__ == '__main__':
    raise SystemExit(main())
