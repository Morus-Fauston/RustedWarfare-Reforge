"""Local/CI named mechanism, short and continuous scenario suites (standard library)."""
import argparse
import hashlib
import math
import json
import os
import re
from pathlib import Path
import shutil
import sys
import tempfile
import time
import uuid
import xml.etree.ElementTree as ET

REPO = Path(__file__).resolve().parents[2]


def command_timeout(value):
    seconds=float(value)
    if not math.isfinite(seconds) or not 0<seconds<=3600:
        raise argparse.ArgumentTypeError('Command timeout must be positive and at most 3600 seconds')
    return seconds


def execute_command(output, name, argv, cwd, timeout):
    """Keep the existing process-tree executor and its unmodified first-error receipt."""
    sys.path.insert(0,str(REPO/'SimulationTest/tools'))
    from workflow import execute_step
    folder=output/(name+'-process')
    measured=execute_step(argv,cwd,folder.resolve(),timeout=timeout)
    # Compatibility summary only; separately captured raw streams remain authoritative.
    with (output/(name+'.log')).open('xb') as log:
        for stream in ['stdout.log','stderr.log']:
            path=folder/stream
            if path.exists():log.write(path.read_bytes())
    return measured


def source_files(repo):
    """Explicit public source closure; never copy TestLab, Docs, evidence or original inputs."""
    roots = ['Engine/core/src', 'Engine/client/src', 'Engine/pc/src', 'Engine/android/src', 'Engine/android/tools',
             'SimulationTest/src/main', 'SimulationTest/src/test', '.github/workflows',
             'SimulationTest/public', 'SimulationTest/fixtures/record-chain',
             'SimulationTest/ci', 'SimulationTest/gradle', 'Engine/gradle']
    paths = [p for root in roots for p in (repo / root).rglob('*')
             if p.is_file() and '__pycache__' not in p.parts]
    paths += [repo / name for name in ['Engine/core/build.gradle.kts', 'Engine/client/build.gradle.kts',
              'Engine/pc/build.gradle.kts', 'SimulationTest/build.gradle.kts',
              'Engine/build.gradle.kts', 'Engine/settings.gradle.kts', 'Engine/gradle.properties',
              'Engine/gradlew', 'Engine/gradlew.bat', 'Engine/android/build.gradle.kts',
              'SimulationTest/settings.gradle.kts', 'SimulationTest/gradle.properties',
              'SimulationTest/gradlew', 'SimulationTest/gradlew.bat']]
    paths += [repo / 'SimulationTest/tools' / name for name in
              ['nuclear_silo_fixture.py', 'nuclear_silo_scenarios.py', 'nuclear_sound_fixture.py', 'nuclear_platform.py', 'test_nuclear_platform.py', 'nautilus_fixture.py', 'nautilus_scenarios.py', 'test_nautilus_platform.py', 'mech_engineer_fixture.py', 'mech_engineer_scenarios.py', 'test_mech_engineer_platform.py', 'repairbay_fixture.py', 'repairbay_scenarios.py', 'test_repairbay_platform.py', 'bunker_fixture.py', 'bunker_scenarios.py', 'test_bunker_platform.py', 'afterburn_scenarios.py', 'test_afterburn_platform.py', 'aa_beam_fixture.py', 'aa_beam_scenarios.py', 'test_aa_beam_archive.py', 'air_transport_fixture.py', 'air_transport_scenarios.py', 'platform_scenario.py', 'test_platform_scenario.py', 'platform_wait.py', 'test_platform_wait.py', 'test_platform_session_cli.py', 'test_named_weapon_platform.py', 'test_submarine_platform.py', 'test_amphibious_platform.py', 'test_transport_platform.py',
               'bomber_fixture.py', 'bomber_scenarios.py', 'workflow_scenarios.py', 'test_workflow_scenarios.py',
               'spider_platform.py', 'modular_spider_fixture.py', 'modular_spider_scenarios.py',
               'phase_six_a_fixture.py',
               'diagnostic_reader.py', 'test_diagnostic_reader.py',
               'action_layout.py', 'test_action_layout.py',
               'platform_windows.py', 'owned_window.py', 'test_owned_window.py',
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


def test_forks(value):
    if str(value) not in ('1', '2', '4'):
        raise argparse.ArgumentTypeError('Test forks must be 1, 2 or 4')
    return int(value)


def test_concurrency(tests, forks):
    forks = test_forks(forks)
    return dict(requested=forks, maxWorkers=forks, modules={
        module: dict(maxParallelForks=1 if module == 'pc' else forks,
                     reason='PC tests remain serial' if module == 'pc' else 'Independent class JVMs')
        for module in tests})


def scenario_workers(value):
    if str(value) not in ('1', '2', '4'):
        raise argparse.ArgumentTypeError('Scenario workers must be 1, 2 or 4')
    return int(value)


def scenario_concurrency(workers):
    return dict(requested=workers, maxWorkers=workers, phase='after-build-and-tests')


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


def build_shared_distribution(repo, output, source, command_timeout_seconds=600, preparation_plan=None, forks=1):
    """Pin one batch's package and, for PR, its compiled test inputs; never test results."""
    from public_distribution import seal, verify, toolchain_identity, artifact_identities
    output.mkdir(parents=True, exist_ok=False)
    started = time.monotonic()
    project = repo/'SimulationTest'
    wrapper = [str(project/'gradlew.bat')] if os.name == 'nt' else ['sh', str(project/'gradlew')]
    argv = wrapper + [':installDist', '--no-daemon', '--console=plain', '--max-workers=1', '--rerun-tasks']
    preparation = None
    kind = 'pr' if preparation_plan is not None else 'long'
    if preparation_plan is not None:
        modules = sorted({m for row in preparation_plan['suites'] for m in row['plan']['tests']})
        tasks = [(':testClasses' if m == 'tools' else ':'+m+':testClasses') for m in modules] + [':installDist']
        control = dict(mode='prepare', tasks={}, modules=modules, preparationTasks=tasks,
                       repo=str(repo.resolve()), output=str(output.resolve()), testForks=forks)
        (output/'selection.json').write_text(json.dumps(control), encoding='utf8')
        argv = wrapper + [':publicPreparation', '--daemon', '--console=plain', '--max-workers='+str(forks),
            '--rerun-tasks', '--no-build-cache', '--init-script', str(project/'ci/development_tests.gradle'),
            '-Ddevelopment.selection='+str(output/'selection.json')]
    result = dict(status='execution-failed', argv=argv, exitCode=None)
    try:
        initial_toolchain = toolchain_identity()
        result['toolchainBefore'] = initial_toolchain
        measured=execute_command(output,'build',argv,project,command_timeout_seconds)
        result.update(exitCode=measured['exitCode'],command=measured)
        if not measured['passed']:
            raise RuntimeError('Shared distribution build failed: exit '+str(result['exitCode']))
        if identities(repo) != source:
            raise RuntimeError('Public source changed during shared distribution build')
        final_toolchain = toolchain_identity()
        result['toolchainAfter'] = final_toolchain
        if final_toolchain != initial_toolchain:
            raise RuntimeError('JDK or Gradle configuration changed during shared distribution build')
        if preparation_plan is not None:
            effective = json.loads((output/'effective.json').read_text(encoding='utf8'))
            executions = json.loads((output/'task-executions.json').read_text(encoding='utf8'))
            if (effective['tests'] or effective['mode'] != 'prepare' or effective['preparationTasks'] != tasks or
                    any(r['failure'] for r in executions) or
                    not set(tasks).issubset(r['task'] for r in executions)):
                raise RuntimeError('Incomplete shared preparation task execution')
            preparation = dict(repo=str(repo.resolve()), selection=[r['plan'] for r in preparation_plan['suites']],
                tasks=tasks, executions=executions, effective=effective,
                artifacts=artifact_identities(json.loads((output/'artifact-roots.json').read_text(encoding='utf8'))))
        package = output/'package'
        shutil.copytree(project/'build/install/simulation-test', package)
        receipt = output/'distribution.json'
        pin = seal(package, receipt, source, initial_toolchain, kind=kind, preparation=preparation)
        for selected in preparation['selection'] if preparation else [None]:
            verify(receipt, pin, identities(repo), kind=kind, plan=selected, repo=repo)
        result.update(status='passed', receipt=str(receipt), sha256=pin, kind=kind, preparation=preparation)
    except Exception as error:
        result['error'] = str(error)
    result['elapsedSeconds'] = time.monotonic()-started
    (output/'result.json').write_text(json.dumps(result, ensure_ascii=False, indent=2)+'\n', encoding='utf8')
    return result


def run(repo, output, plan, prepared_distribution=None, command_timeout_seconds=600, forks=1, workers=2):
    output.mkdir(parents=True, exist_ok=False)
    initial = identities(repo)
    result = dict(schemaVersion=1, suite=plan['suiteId'], selection=plan, status='execution-failed',
                  cloudRun=False, source=initial, commands=[], coverage=[],
                  exclusions=['original-compatibility', 'platform-input', 'historical-snapshot-reexecution'],
                  commandTimeoutSeconds=command_timeout_seconds,
                  testConcurrency=test_concurrency(plan['tests'], forks), scenarioConcurrency=scenario_concurrency(workers))
    if not any(case['kind'] == 'long' for case in plan['scenarios']):
        result['exclusions'].append('continuous-long')
    started = time.monotonic()
    def admit():
        from public_distribution import verify
        return verify(prepared_distribution[0], prepared_distribution[1], identities(repo),
            kind='long' if plan['kind'] == 'long' else 'pr', plan=plan, repo=repo)
    def command(name, args, cwd):
        if prepared_distribution is not None:
            admit()
        measured=execute_command(output,name,args,cwd,command_timeout_seconds)
        code=measured['exitCode']
        result['commands'].append(dict(name=name, argv=[str(a) for a in args], exitCode=code,
                                       elapsedSeconds=measured['elapsedSeconds'],
                                       receipt=name+'-process/result.json',firstError=measured['firstError']))
        if prepared_distribution is not None:
            admit()
        if not measured['passed']:
            raise RuntimeError(f'{name}: exit {code}; see {name}.log')
    try:
        sys.path.insert(0, str(REPO/'SimulationTest/tools'))
        from workflow import resources
        with resources(['build:'+str(repo/'Engine'), 'build:'+str(repo/'SimulationTest')]):
            project = repo / 'SimulationTest'
            if prepared_distribution is not None:
                if plan['kind'] not in ('pr', 'mechanism', 'long') or plan['inputScope'] != 'public':
                    raise ValueError('A prepared package requires its exact public PR or long selection')
                distribution = admit()
                result['preparedDistribution'] = dict(receipt=str(prepared_distribution[0]), sha256=prepared_distribution[1])
            wrapper = [str(project / 'gradlew.bat')] if os.name == 'nt' else ['sh', str(project / 'gradlew')]
            common = ['--no-daemon', '--console=plain', '--max-workers='+str(forks), '--rerun-tasks']
            if plan['fixture']:
                common += ['-Dpublic.inputs.dir=' + str(project / 'public' / plan['fixture'])]
            if plan['tests']:
                if prepared_distribution is not None:
                    admit()
                from development_check import execute_test_group
                executed = execute_test_group(repo, output/'test-execution',
                    dict(fixture=plan['fixture'], tests=plan['tests'], suites=[plan['suiteId']]),
                    prepared_distribution is None, forks, package=prepared_distribution is None, timeout=command_timeout_seconds)
                result['testExecution'] = executed
                process = executed['command']
                result['commands'].append(dict(name='tests-and-package' if prepared_distribution is None else 'current-tests',
                    argv=process['argv'], exitCode=process['exitCode'], elapsedSeconds=process['elapsedSeconds'],
                    receipt='test-execution/command/result.json', firstError=process['firstError']))
                result['coverage'] = executed['tests'].get('coverage', [])
                if not process['passed'] or executed['tests']['status'] != 'passed':
                    raise RuntimeError('Current test execution or packaging did not pass')
                if prepared_distribution is not None:
                    admit()
                    test_tasks = {MODULES[m][0] for m in plan['tests']}
                    if any(r.get('didWork') for r in executed['tests']['tasks'] if r['task'] not in test_tasks):
                        raise RuntimeError('Prepared compilation or packaging executed again')
                    result['preparationReused'] = True
            elif prepared_distribution is None:
                command('tests-and-package', wrapper + [':installDist'] + common, project)
            if prepared_distribution is None:
                distribution = project/'build/install/simulation-test'
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
        from public_distribution import package_files
        from public_scenarios import run_scenarios
        fixed_files = package_files(distribution) if plan['scenarios'] else None
        def scenario_admit():
            if prepared_distribution is not None:
                admit()
            elif package_files(distribution) != fixed_files:
                raise ValueError('Scenario distribution changed during verification')
        with resources(['build:'+str(repo/'Engine'), 'build:'+str(repo/'SimulationTest')]):
            scenarios = run_scenarios(repo, output, plan['scenarios'], distribution, workers,
                                      command_timeout_seconds, scenario_admit, execute_command)
        result['scenarioExecution'] = scenarios
        result['commands'].extend(c for item in scenarios['items'] for c in item['commands'])
        archives = [item['archive'] for item in scenarios['items'] if item['status'] == 'passed']
        result['archives'] = archives
        if scenarios['status'] != 'passed':
            raise RuntimeError('Scenario execution failed: '+str(scenarios['firstError']))
        if initial != identities(repo):
            raise RuntimeError('Public source changed during verification')
        if prepared_distribution is not None:
            admit()
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
    parser.add_argument('--test-forks', type=test_forks, default=1, help='Bound independent test JVMs: 1, 2 or 4; PC stays serial')
    parser.add_argument('--scenario-workers', type=scenario_workers, default=2, help='Bound independent headless scenarios after tests: 1, 2 or 4')
    parser.add_argument('--command-timeout',type=command_timeout,default=600,
                        help='Explicit per-command seconds, at most 3600; default 600. Selection is unchanged.')
    parser.add_argument('--distribution-receipt', type=Path, help='Same-batch fixed public PR preparation or long package receipt')
    parser.add_argument('--distribution-sha256', help='Receipt digest pinned by the parent batch')
    args = parser.parse_args()
    try:
        plan = plan_suite(REPO, args.suite, args.kind)
    except (ValueError, KeyError, TypeError) as error:
        parser.error(str(error))
    if args.list:
        print(json.dumps(dict(plan, testConcurrency=test_concurrency(plan['tests'], args.test_forks),
                             scenarioConcurrency=scenario_concurrency(args.scenario_workers))))
        return 0
    if bool(args.distribution_receipt) != bool(args.distribution_sha256):
        parser.error('The fixed distribution receipt and its pinned digest are required together')
    if args.distribution_receipt and args.isolated:
        parser.error('The parent already fixes the isolated source closure for a prepared package')
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
    prepared = (args.distribution_receipt, args.distribution_sha256) if args.distribution_receipt else None
    return run(repo, output, plan, prepared, args.command_timeout, args.test_forks, args.scenario_workers)


if __name__ == '__main__':
    raise SystemExit(main())
