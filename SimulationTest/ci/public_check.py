"""Local/CI named mechanism, short and continuous scenario suites (standard library)."""
import argparse
import hashlib
import json
import os
import re
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time
import uuid
import xml.etree.ElementTree as ET

REPO = Path(__file__).resolve().parents[2]


def source_files(repo):
    """Explicit public source closure; never copy TestLab, Docs, evidence or original inputs."""
    roots = ['Engine/core/src', 'Engine/client/src', 'Engine/pc/src',
             'SimulationTest/src/main', 'SimulationTest/src/test', '.github/workflows',
             'SimulationTest/public', 'SimulationTest/ci', 'SimulationTest/gradle']
    paths = [p for root in roots for p in (repo / root).rglob('*')
             if p.is_file() and '__pycache__' not in p.parts]
    paths += [repo / name for name in ['Engine/core/build.gradle.kts', 'Engine/client/build.gradle.kts',
              'Engine/pc/build.gradle.kts', 'SimulationTest/build.gradle.kts',
              'SimulationTest/settings.gradle.kts', 'SimulationTest/gradle.properties',
              'SimulationTest/gradlew', 'SimulationTest/gradlew.bat']]
    paths += [repo / 'SimulationTest/tools' / name for name in
              ['platform_scenario.py', 'test_platform_scenario.py', 'test_platform_session_cli.py',
               'workflow.py', 'workflow_inputs.py', 'workflow_device.py', 'workflow_windows.py',
               'workflow_reuse.py', 'perf_report.py', 'platform_perf.py', 'test_workflow.py',
               'test_workflow_device.py', 'test_workflow_windows.py', 'test_workflow_reuse.py', 'test_perf_analysis.py',
               'specialist_check.py', 'test_specialist_check.py']]
    paths += list((repo / 'Decompile/tools/p003').glob('*.py'))
    paths += [repo / 'Decompile/tools' / name for name in ['verify_dec_evidence.py', 'test_verify_dec_evidence.py']]
    return sorted(paths)


def identities(repo):
    return [dict(path=p.relative_to(repo).as_posix(), sha256=hashlib.sha256(p.read_bytes()).hexdigest())
            for p in source_files(repo)]


MODULES = {'tools': (':test', 'build/test-results/test'),
           'core': (':core:test', 'build/core/test-results/test'),
           'client': (':client:test', 'build/client/test-results/test'),
           'pc': (':pc:test', 'build/pc/test-results/test')}


def plan_suite(repo, name, kind):
    if not re.fullmatch(r'[a-z0-9][a-z0-9-]*', name):
        raise ValueError('Suite name must be a public registry name, not a path')
    path = repo / 'SimulationTest/public/suites' / (name + '.json')
    if not path.is_file():
        raise ValueError('Unknown suite: ' + name)
    suite = json.loads(path.read_text(encoding='utf-8'))
    if suite['schemaVersion'] != 1:
        raise ValueError('Unsupported public suite schema')
    tests = {}
    if kind in ['mechanism', 'pr', 'all']:
        tests = suite.get('tests', {'tools': suite.get('toolTests', []),
                                    'core': suite.get('mechanismTests', [])})
        tests = {module: classes for module, classes in tests.items() if classes}
    if any(module not in MODULES or not isinstance(classes, list) or
           len(classes) != len(set(classes)) or any(not isinstance(c, str) or
           not re.fullmatch(r'[A-Za-z_][A-Za-z0-9_.]*', c) for c in classes)
           for module, classes in tests.items()):
        raise ValueError('Invalid module or fixed JUnit class selection')
    python_tests = suite.get('pythonTests', []) if kind in ['mechanism', 'pr', 'all'] else []
    public_sources = {p.relative_to(repo).as_posix() for p in source_files(repo)}
    if (not isinstance(python_tests, list) or any(not isinstance(p, str) or p not in public_sources or
            not re.fullmatch(r'(SimulationTest/(tools|ci)|Decompile/tools(/p003)?)/test_[a-z0-9_]+\.py', p)
            for p in python_tests) or len(python_tests) != len(set(python_tests))):
        raise ValueError('Python test must name a unique public mechanism file')
    scenarios = []
    for category in ['short', 'long']:
        if kind not in [category, 'all'] and not (kind == 'pr' and category == 'short'):
            continue
        cases = suite.get(category, [])
        if isinstance(cases, dict):
            cases = [dict(id='short', **cases)]
        for case in cases:
            if case.get('backend', 'core') not in ('core', 'session'):
                raise ValueError('Public CI supports only core or headless session scenarios')
            if not re.fullmatch(r'[a-z0-9][a-z0-9-]*', case['id']):
                raise ValueError('Invalid scenario ID')
            for field in ['scenario', 'contract']:
                root = (repo / 'SimulationTest/public').resolve()
                if (not (root / case[field]).resolve().is_relative_to(root) or
                        not (root / case[field]).is_file()):
                    raise ValueError('Scenario/contract must exist inside public input directory')
            scenarios.append(dict(case, kind=category))
    if not tests and not scenarios and not python_tests:
        raise ValueError('Selected suite/kind is empty')
    if len({case['id'] for case in scenarios}) != len(scenarios):
        raise ValueError('Duplicate scenario ID')
    fixture = suite.get('fixture')
    if fixture is not None:
        root = (repo / 'SimulationTest/public').resolve()
        if not isinstance(fixture, str) or not (root / fixture).resolve().is_relative_to(root) or not (root / fixture).is_dir():
            raise ValueError('Fixture must exist inside public input directory')
    return dict(suiteId=suite['suiteId'], kind=kind, tests=tests, scenarios=scenarios,
                pythonTests=python_tests, inputScope=suite.get('inputScope', 'public'), fixture=suite.get('fixture'))


def run(repo, output, plan):
    output.mkdir(parents=True, exist_ok=False)
    initial = identities(repo)
    result = dict(schemaVersion=1, suite=plan['suiteId'], selection=plan, status='execution-failed',
                  cloudRun=False, source=initial, commands=[], coverage=[],
                  exclusions=['original-compatibility', 'platform-input', 'historical-snapshot-reexecution'])
    if not any(case['kind'] == 'long' for case in plan['scenarios']):
        result['exclusions'].append('continuous-long')
    started = time.monotonic()
    def command(name, args, cwd):
        begin = time.monotonic()
        with (output / (name + '.log')).open('w', encoding='utf-8') as stream:
            try:
                completed = subprocess.run(args, cwd=cwd, stdout=stream, stderr=subprocess.STDOUT,
                                           timeout=600, check=False)
                code = completed.returncode
            except subprocess.TimeoutExpired:
                code = 124
        result['commands'].append(dict(name=name, argv=[str(a) for a in args], exitCode=code,
                                       elapsedSeconds=time.monotonic() - begin))
        if code:
            raise RuntimeError(f'{name}: exit {code}; see {name}.log')
    def junit(folder, name, expected):
        rows = []
        for path in sorted(folder.glob('TEST-*.xml')):
            suite = ET.parse(path).getroot()
            rows.append({key: suite.attrib[key] for key in ['name', 'tests', 'failures', 'errors', 'skipped']})
        if {r['name'] for r in rows} != set(expected) or any(
                int(r['tests']) < 1 or any(int(r[k]) for k in ['failures', 'errors', 'skipped']) for r in rows):
            raise RuntimeError(f'{name}: missing, unexpected, empty, failed or skipped test class: {rows}')
        shutil.copytree(folder, output / name)
        result['coverage'].append(dict(name=name, classes=rows))
    try:
        project = repo / 'SimulationTest'
        wrapper = [str(project / 'gradlew.bat')] if os.name == 'nt' else ['sh', str(project / 'gradlew')]
        common = ['--no-daemon', '--console=plain', '--max-workers=1', '--rerun-tasks']
        if plan['fixture']:
            common += ['-Dpublic.inputs.dir=' + str(project / 'public' / plan['fixture'])]
        tasks = []
        for module, classes in plan['tests'].items():
            tasks += [MODULES[module][0]] + [v for name in classes for v in ['--tests', name]]
        command('tests-and-package', wrapper + tasks + [':installDist'] + common, project)
        for module, classes in plan['tests'].items():
            junit(project / MODULES[module][1], module + '-tests', classes)
        result['pythonCoverage'] = []
        for index, relative in enumerate(plan.get('pythonTests', [])):
            name = 'python-' + str(index) + '-' + Path(relative).stem
            summary = output / (name + '.json')
            command(name, [sys.executable, '-B', str(project/'ci/python_check.py'),
                          '--test', str(repo/relative), '--output', str(summary)], repo)
            measured = json.loads(summary.read_text(encoding='utf8'))
            if (measured['status'] != 'passed' or measured['tests'] < 1 or
                    any(measured[k] for k in ['failures', 'errors', 'skipped']) or not measured['sourceUnchanged']):
                raise RuntimeError('Python mechanism evidence is incomplete: ' + relative)
            result['pythonCoverage'].append(dict(path=relative, result=name+'.json', **measured))
        launcher = project / 'build/install/simulation-test/bin' / ('simulation-test.bat' if os.name == 'nt' else 'simulation-test')
        cli = [str(launcher)] if os.name == 'nt' else ['sh', str(launcher)]
        archives = []
        for case in plan['scenarios']:
            folder = output / case['id']
            if case.get('backend') == 'session':
                command(case['id'], [sys.executable, '-B', str(project / 'tools/platform_scenario.py'),
                    'session', '--scenario', str(project / 'public' / case['scenario']),
                    '--contract', str(project / 'public' / case['contract']),
                    '--distribution', str(project / 'build/install/simulation-test'), '--output', str(folder)], project)
                judgment = json.loads((folder / 'result.json').read_text(encoding='utf8'))
                if judgment['exitCode'] != 0 or judgment['status'] != 'passed':
                    raise RuntimeError('Session evidence is not complete/pass: ' + case['id'])
                command(case['id']+'-recompare', [sys.executable, '-B', str(project / 'tools/platform_scenario.py'),
                    'compare', '--run', str(folder)], project)
                archives.append(dict(id=case['id'], kind=case['kind'], backend='session', path=str(folder)))
                continue
            command(case['id'], cli + ['run', '--scenario', str(project / 'public' / case['scenario']),
                    '--contract', str(project / 'public' / case['contract']),
                    '--output', str(folder), '--mode', 'self'], project)
            batches = list(folder.glob('run-*'))
            if len(batches) != 1:
                raise RuntimeError('Expected exactly one scenario-run archive')
            judgment = json.loads((batches[0] / 'comparison/result.json').read_text(encoding='utf-8'))
            manifest = json.loads((batches[0] / 'manifest.json').read_text(encoding='utf-8'))
            if judgment['exitCode'] != 0 or not manifest['complete']:
                raise RuntimeError('Scenario evidence is not complete/pass: ' + case['id'])
            archives.append(dict(id=case['id'], kind=case['kind'], path=str(batches[0])))
        if initial != identities(repo):
            raise RuntimeError('Public source changed during verification')
        result.update(status='passed', archives=archives)
        if plan['suiteId'] == 'public-pr-first-loop' and archives:
            result['shortArchive'] = archives[0]['path']
    except Exception as error:
        result['error'] = str(error)
    result['elapsedSeconds'] = time.monotonic() - started
    (output / 'result.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(dict(status=result['status'], output=str(output), error=result.get('error')), ensure_ascii=False))
    return 0 if result['status'] == 'passed' else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, help='New immutable output directory')
    parser.add_argument('--isolated', action='store_true', help='Copy only public source closure to a new system temporary directory')
    parser.add_argument('--suite', default='pr-first-loop', help='Named registry under public/suites')
    parser.add_argument('--kind', choices=['mechanism', 'short', 'long', 'pr', 'all'], default='pr')
    parser.add_argument('--list', action='store_true', help='Print fixed plan without building or creating evidence')
    args = parser.parse_args()
    try:
        plan = plan_suite(REPO, args.suite, args.kind)
    except (ValueError, KeyError, TypeError) as error:
        parser.error(str(error))
    if args.list:
        print(json.dumps(plan))
        return 0
    if plan['inputScope'] != 'public' and (args.isolated or os.environ.get('GITHUB_ACTIONS') == 'true'):
        parser.error('Private reference suite cannot run in the public closure or CI')
    output = (args.output or REPO / 'SimulationTest/build/public-check' / str(uuid.uuid4())).resolve()
    repo = REPO
    if args.isolated:
        repo = Path(tempfile.mkdtemp(prefix='reforge-public-'))
        for source in source_files(REPO):
            target = repo / source.relative_to(REPO)
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, target)
        print('Isolated public source:', repo)
    return run(repo, output, plan)


if __name__ == '__main__':
    raise SystemExit(main())
