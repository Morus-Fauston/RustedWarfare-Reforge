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
import threading
import time
import uuid
import xml.etree.ElementTree as ET

REPO = Path(__file__).resolve().parents[2]


def command_timeout(value):
    seconds=float(value)
    if not math.isfinite(seconds) or not 0<seconds<=3600:
        raise argparse.ArgumentTypeError('Command timeout must be positive and at most 3600 seconds')
    return seconds


def lock_wait_seconds(value):
    seconds = float(value)
    if not math.isfinite(seconds) or not 0 <= seconds <= 86400:
        raise argparse.ArgumentTypeError('Lock wait timeout must be between 0 and 86400 seconds')
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


_SOURCE_TREE_ROOTS = (
    'Engine/core/src', 'Engine/client/src', 'Engine/pc/src', 'Engine/android/src', 'Engine/android/tools',
    'SimulationTest/src/main', 'SimulationTest/src/test', '.github/workflows',
    'SimulationTest/public', 'SimulationTest/fixtures/record-chain',
    'SimulationTest/ci', 'SimulationTest/gradle', 'Engine/gradle',
)
_EXPLICIT_BUILD_FILES = (
    'Engine/core/build.gradle.kts', 'Engine/client/build.gradle.kts',
    'Engine/pc/build.gradle.kts', 'SimulationTest/build.gradle.kts',
    'Engine/build.gradle.kts', 'Engine/settings.gradle.kts', 'Engine/gradle.properties',
    'Engine/gradlew', 'Engine/gradlew.bat', 'Engine/android/build.gradle.kts',
    'SimulationTest/settings.gradle.kts', 'SimulationTest/gradle.properties',
    'SimulationTest/gradlew', 'SimulationTest/gradlew.bat',
)
_EXPLICIT_TOOL_NAMES = (
    'nuclear_silo_fixture.py', 'nuclear_silo_scenarios.py', 'nuclear_sound_fixture.py', 'nuclear_platform.py', 'test_nuclear_platform.py',
    'nautilus_fixture.py', 'nautilus_scenarios.py', 'test_nautilus_platform.py',
    'mech_engineer_fixture.py', 'mech_engineer_scenarios.py', 'test_mech_engineer_platform.py',
    'repairbay_fixture.py', 'repairbay_scenarios.py', 'test_repairbay_platform.py',
    'bunker_fixture.py', 'bunker_scenarios.py', 'test_bunker_platform.py',
    'afterburn_scenarios.py', 'test_afterburn_platform.py',
    'aa_beam_fixture.py', 'aa_beam_scenarios.py', 'test_aa_beam_archive.py',
    'air_transport_fixture.py', 'air_transport_scenarios.py',
    'platform_scenario.py', 'test_platform_scenario.py', 'platform_wait.py', 'test_platform_wait.py',
    'test_platform_session_cli.py', 'test_named_weapon_platform.py', 'test_submarine_platform.py',
    'test_amphibious_platform.py', 'test_transport_platform.py',
    'bomber_fixture.py', 'bomber_scenarios.py', 'workflow_scenarios.py', 'test_workflow_scenarios.py',
    'spider_platform.py', 'modular_spider_fixture.py', 'modular_spider_scenarios.py',
    'production_session_assertions.py', 'phase_six_a_fixture.py',
    'diagnostic_reader.py', 'test_diagnostic_reader.py',
    'action_layout.py', 'test_action_layout.py',
    'platform_windows.py', 'owned_window.py', 'test_owned_window.py',
    'workflow.py', 'workflow_inputs.py', 'workflow_device.py', 'workflow_windows.py',
    'evidence_compact.py', 'test_evidence_compact.py',
    'windows_client_mcp.py', 'test_windows_client_mcp.py',
    'workflow_reuse.py', 'perf_report.py', 'platform_perf.py', 'test_workflow.py',
    'test_workflow_device.py', 'test_workflow_windows.py', 'test_workflow_reuse.py', 'test_perf_analysis.py',
    'specialist_check.py', 'test_specialist_check.py',
    'live_hotspot_stress.py', 'test_live_hotspot_stress.py',
    'quick_test.py', 'test_quick_test.py',
)
_EXPLICIT_DEC_TOOL_NAMES = ('verify_dec_evidence.py', 'test_verify_dec_evidence.py')


def _is_link(path_or_entry):
    if path_or_entry.is_symlink():
        return True
    is_junction = getattr(path_or_entry, 'is_junction', None)
    return bool(is_junction and is_junction())


def _walk_regular_files(root_dir, *, with_stat=False):
    root_dir = Path(root_dir)
    if _is_link(root_dir):
        raise ValueError('Public source tree cannot contain symlinks: ' + str(root_dir))
    if not root_dir.exists():
        return []
    if not root_dir.is_dir():
        raise ValueError('Public source root must be a directory: ' + str(root_dir))
    found = []
    stack = [root_dir]
    while stack:
        current = stack.pop()
        with os.scandir(current) as entries:
            ordered = sorted(entries, key=lambda entry: entry.name)
        subdirs = []
        for entry in ordered:
            if _is_link(entry):
                raise ValueError('Public source tree cannot contain symlinks: ' + entry.path)
            if entry.name == '__pycache__':
                continue
            if entry.is_dir(follow_symlinks=False):
                subdirs.append(Path(entry.path))
            elif entry.is_file(follow_symlinks=False):
                if with_stat:
                    st = entry.stat(follow_symlinks=False)
                    found.append((Path(entry.path), st.st_size, st.st_mtime_ns))
                else:
                    found.append(Path(entry.path))
            else:
                raise ValueError('Unsupported public source entry: ' + entry.path)
        for subdir in reversed(subdirs):
            stack.append(subdir)
    return found


def _p003_python_files(repo, *, with_stat=False):
    p003_dir = Path(repo) / 'Decompile/tools/p003'
    if _is_link(p003_dir):
        raise ValueError('Public source tree cannot contain symlinks: ' + str(p003_dir))
    if not p003_dir.exists():
        return []
    if not p003_dir.is_dir():
        raise ValueError('Public source root must be a directory: ' + str(p003_dir))
    rows = []
    with os.scandir(p003_dir) as entries:
        ordered = sorted(entries, key=lambda entry: entry.name)
    for entry in ordered:
        if _is_link(entry):
            raise ValueError('Public source tree cannot contain symlinks: ' + entry.path)
        if entry.name == '__pycache__':
            continue
        if entry.is_file(follow_symlinks=False) and entry.name.endswith('.py'):
            if with_stat:
                st = entry.stat(follow_symlinks=False)
                rows.append((Path(entry.path), st.st_size, st.st_mtime_ns))
            else:
                rows.append(Path(entry.path))
        elif not entry.is_dir(follow_symlinks=False) and not entry.is_file(follow_symlinks=False):
            raise ValueError('Unsupported public source entry: ' + entry.path)
    return rows


def _explicit_public_files(repo, *, require_exists=False):
    repo = Path(repo)
    explicit = [repo / name for name in _EXPLICIT_BUILD_FILES]
    explicit += [repo / 'SimulationTest/tools' / name for name in _EXPLICIT_TOOL_NAMES]
    explicit += [repo / 'Decompile/tools' / name for name in _EXPLICIT_DEC_TOOL_NAMES]
    for path in explicit:
        if _is_link(path):
            raise ValueError('Public source tree cannot contain symlinks: ' + str(path))
        if require_exists:
            if not path.is_file():
                raise FileNotFoundError('Missing public source file: ' + str(path))
        elif path.exists() and not path.is_file():
            raise ValueError('Public source file must be a regular file: ' + str(path))
    return explicit


def source_files(repo):
    """Explicit public source closure; never copy TestLab, Docs, evidence or original inputs."""
    repo = Path(repo)
    paths = [p for root in _SOURCE_TREE_ROOTS for p in _walk_regular_files(repo / root)]
    paths += _explicit_public_files(repo, require_exists=False)
    paths += _p003_python_files(repo)
    return sorted(paths)


def repo_metadata_snapshot(repo):
    """Fast (path, size, mtime_ns) snapshot over the exact public source closure."""
    repo = Path(repo)
    rows = []
    for root in _SOURCE_TREE_ROOTS:
        for path, size, mtime_ns in _walk_regular_files(repo / root, with_stat=True):
            rows.append((path.relative_to(repo).as_posix(), size, mtime_ns))
    for path in _explicit_public_files(repo, require_exists=True):
        st = path.stat()
        rows.append((path.relative_to(repo).as_posix(), st.st_size, st.st_mtime_ns))
    for path, size, mtime_ns in _p003_python_files(repo, with_stat=True):
        rows.append((path.relative_to(repo).as_posix(), size, mtime_ns))
    rows.sort(key=lambda item: item[0])
    return rows


def python_source_closure(repo):
    """Public closure restricted to Python mechanism directories (avoids scanning 7,110+ input assets)."""
    repo = Path(repo)
    names = {p.relative_to(repo).as_posix() for p in _walk_regular_files(repo / 'SimulationTest/ci')}
    for path in [repo / 'SimulationTest/tools' / name for name in _EXPLICIT_TOOL_NAMES] + [
        repo / 'Decompile/tools' / name for name in _EXPLICIT_DEC_TOOL_NAMES
    ]:
        if _is_link(path):
            raise ValueError('Public source tree cannot contain symlinks: ' + str(path))
        names.add(path.relative_to(repo).as_posix())
    names.update(p.relative_to(repo).as_posix() for p in _p003_python_files(repo))
    return names


def identities(repo):
    from public_distribution import digest
    return [dict(path=p.relative_to(repo).as_posix(), sha256=digest(p)) for p in source_files(repo)]


def verify_scenario_sources(repo, case, expected):
    from public_distribution import digest

    public = (repo/'SimulationTest/public').resolve()
    scenario = public/case['scenario']

    def relative(path):
        resolved = path.resolve(strict=True)
        if not resolved.is_relative_to(public) or resolved != Path(os.path.abspath(path)):
            raise ValueError('Scenario input escapes or aliases the public tree: '+str(path))
        return resolved

    def check(path):
        path = relative(path)
        if digest(path) != expected.get(path.relative_to(repo).as_posix()):
            raise ValueError('Public scenario input changed: '+str(path))

    scenario = relative(scenario)
    data = scenario.read_bytes()
    if hashlib.sha256(data).hexdigest() != expected.get(scenario.relative_to(repo).as_posix()):
        raise ValueError('Public scenario input changed: '+str(scenario))
    check(public/case['contract'])
    document = json.loads(data)
    if case.get('backend') == 'session':
        for name in ('platform_scenario.py', 'spider_platform.py', 'nuclear_platform.py',
                     'production_session_assertions.py'):
            script = repo/'SimulationTest/tools'/name
            if digest(script) != expected.get(script.relative_to(repo).as_posix()):
                raise ValueError('Public Session comparator changed: '+name)
    if case.get('backend') == 'session' and 'inputs' in document:
        inputs = relative(scenario.parent/document['inputs'])
        current = {path.relative_to(repo).as_posix() for path in inputs.rglob('*') if path.is_file()}
        prefix = inputs.relative_to(repo).as_posix()+'/'
        if current != {name for name in expected if name.startswith(prefix)}:
            raise ValueError('Public scenario input closure changed: '+str(inputs))
        for name in sorted(current):
            check(repo/name)
    elif 'content' in document:
        content = document['content']
        units = content.get('unitVariants', [content.get('unitVariant')])
        for row in [content['map'], *units, *content['dependencies']]:
            check(scenario.parent/row['path'])


MODULES = {'tools': (':test', 'build/test-results/test'),
           'core': (':core:test', 'build/core/test-results/test'),
           'client': (':client:test', 'build/client/test-results/test'),
           'pc': (':pc:test', 'build/pc/test-results/test')}


def test_forks(value):
    if str(value) not in ('1', '2', '3', '4', '8'):
        raise argparse.ArgumentTypeError('Test forks must be 1, 2, 3, 4 or 8')
    return int(value)


def gradle_workers(value):
    text = str(value).strip()
    if not text.isdigit():
        raise argparse.ArgumentTypeError('Gradle workers must be an integer between 1 and 16')
    parsed = int(text)
    if not 1 <= parsed <= 16:
        raise argparse.ArgumentTypeError('Gradle workers must be an integer between 1 and 16')
    return parsed


MAX_LOCAL_TEST_JVMS = 8
_MODULE_FORK_PRIORITY = ('core', 'client', 'tools')


def allocate_module_test_forks(modules, per_module_cap, *, is_cloud=False, max_resident_jvms=MAX_LOCAL_TEST_JVMS):
    """Bound per-module test JVMs and, on local hosts, total resident JVMs across modules (<= 8)."""
    active = list(modules)
    if not active:
        return {}
    per_cap = max(1, int(per_module_cap))
    alloc = {}
    if 'pc' in active:
        alloc['pc'] = 1
    non_pc = [m for m in _MODULE_FORK_PRIORITY if m in active] + [
        m for m in active if m != 'pc' and m not in _MODULE_FORK_PRIORITY
    ]
    if not non_pc:
        return alloc
    if is_cloud:
        for mod in non_pc:
            alloc[mod] = per_cap
        return alloc
    budget = max(len(non_pc), max_resident_jvms - alloc.get('pc', 0))
    for mod in non_pc:
        alloc[mod] = 1
    remaining = budget - len(non_pc)
    while remaining > 0:
        progressed = False
        for mod in non_pc:
            if remaining <= 0:
                break
            if alloc[mod] < per_cap:
                alloc[mod] += 1
                remaining -= 1
                progressed = True
        if not progressed:
            break
    return alloc


def default_test_forks(*, cloud=None, cpu_count=None):
    is_cloud = (os.environ.get('GITHUB_ACTIONS') == 'true') if cloud is None else bool(cloud)
    cores = (os.cpu_count() or 2) if cpu_count is None else cpu_count
    if is_cloud or cores <= 4:
        return 1
    if cores >= 12:
        return 3
    if cores >= 6:
        return 2
    return 1


def default_build_workers(explicit_workers=None, *, cloud=None, cpu_count=None):
    if explicit_workers is not None:
        return gradle_workers(explicit_workers)
    is_cloud = (os.environ.get('GITHUB_ACTIONS') == 'true') if cloud is None else bool(cloud)
    cores = (os.cpu_count() or 2) if cpu_count is None else cpu_count
    cap = 4 if is_cloud else 12
    return min(cap, max(1, cores // 2))


def test_concurrency(tests, forks=None, workers=None, *, cloud=None, cpu_count=None):
    is_cloud = (os.environ.get('GITHUB_ACTIONS') == 'true') if cloud is None else bool(cloud)
    cores = (os.cpu_count() or 2) if cpu_count is None else cpu_count
    effective_forks = default_test_forks(cloud=is_cloud, cpu_count=cores) if forks is None else test_forks(forks)
    if workers is not None:
        max_workers = gradle_workers(workers)
    elif is_cloud or cores <= 4 or effective_forks == 1:
        max_workers = effective_forks
    else:
        max_workers = max(effective_forks, min(8, cores // 2))

    per_module_cap = max(1, min(effective_forks, max_workers))
    allocated = allocate_module_test_forks(tests, per_module_cap, is_cloud=is_cloud)
    non_pc = [m for m in tests if m != 'pc']
    module_entries = {}
    for module in tests:
        if module == 'pc':
            module_entries[module] = dict(maxParallelForks=1, reason='PC tests remain serial')
        elif not is_cloud and allocated[module] < per_module_cap:
            module_entries[module] = dict(
                maxParallelForks=allocated[module],
                reason='Bounded cross-module JVM pool',
            )
        else:
            module_entries[module] = dict(
                maxParallelForks=allocated[module],
                reason='Independent class JVMs' if len(non_pc) <= 1 or is_cloud else 'Bounded cross-module JVM pool',
            )
    return dict(requested=effective_forks, gradleWorkers=max_workers, maxWorkers=max_workers, modules=module_entries)


def scenario_workers(value):
    if str(value) not in ('1', '2', '4', '8'):
        raise argparse.ArgumentTypeError('Scenario workers must be 1, 2, 4 or 8')
    return int(value)


def scenario_concurrency(workers):
    return dict(requested=workers, maxWorkers=workers, phase='after-build-and-tests')


def plan_suite(repo, name, kind, public_sources=None):
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
    if python_tests and public_sources is None:
        public_sources = python_source_closure(repo)
    if (not isinstance(python_tests, list) or any(not isinstance(p, str) or p not in (public_sources or set()) or
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
        case_ids = {case['id']: case for case in cases}
        if len(case_ids) != len(cases):
            raise ValueError('Duplicate scenario ID')
        declared_equivalence = {}
        if any('equivalentTo' in case for case in cases):
            manifest = json.loads((repo/'SimulationTest/public/scenario-equivalence.json').read_bytes())
            for family in manifest['families']:
                if family['suite'] == name:
                    representative, *aliases = family['members']
                    for alias in aliases:
                        if alias['id'] in declared_equivalence:
                            raise ValueError('Duplicate scenario equivalence')
                        declared_equivalence[alias['id']] = (alias, representative)
        for case in cases:
            if 'equivalentTo' in case:
                representative = case_ids.get(case['equivalentTo'])
                if (category != 'short' or representative is None or 'equivalentTo' in representative
                        or case.get('backend', 'core') != 'core' or representative.get('backend', 'core') != 'core'
                        or case.get('recompare') or case.get('archiveAssertions')):
                    raise ValueError('Invalid Core short scenario equivalence')
                if declared_equivalence.get(case['id']) != (
                        {key: value for key, value in case.items() if key != 'equivalentTo'}, representative):
                    raise ValueError('Unregistered Core short scenario equivalence')
            if case.get('backend', 'core') not in ('core', 'session'):
                raise ValueError('Public CI supports only core or headless session scenarios')
            if 'recompare' in case and (case.get('backend', 'core') != 'core' or case['recompare'] is not True):
                raise ValueError('Additional archive recompare must be enabled explicitly on a Core scenario')
            if 'archiveAssertions' in case and (case.get('recompare') is not True or
                    case['archiveAssertions'] not in ('aa-beam', 'transport', 'air-transport')):
                raise ValueError('Archive assertions require an admitted Core archive and a known family')
            if not re.fullmatch(r'[a-z0-9][a-z0-9-]*', case['id']):
                raise ValueError('Invalid scenario ID')
            for field in ['scenario', 'contract']:
                root = (repo / 'SimulationTest/public').resolve()
                if (not (root / case[field]).resolve().is_relative_to(root) or
                        not (root / case[field]).is_file()):
                    raise ValueError('Scenario/contract must exist inside public input directory')
            if 'equivalentTo' not in case:
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


def build_shared_distribution(repo, output, source, command_timeout_seconds=600, preparation_plan=None, forks=1, *,
                              kind=None, workers_gradle=None, source_meta=None):
    """Pin one batch's package and, for PR, its compiled test inputs; never test results."""
    from public_distribution import (
        seal, verify_fast, toolchain_identity, artifact_identities,
        artifact_metadata_snapshot, package_metadata_snapshot,
    )
    output.mkdir(parents=True, exist_ok=False)
    started = time.monotonic()
    project = repo/'SimulationTest'
    wrapper = [str(project/'gradlew.bat')] if os.name == 'nt' else ['sh', str(project/'gradlew')]
    effective_forks = default_test_forks() if forks is None else test_forks(forks)
    build_workers = default_build_workers(workers_gradle)
    argv = wrapper + [':installDist', '--no-daemon', '--console=plain', '--max-workers='+str(build_workers), '--rerun-tasks']
    preparation = None
    kind = 'pr' if preparation_plan is not None else (kind or 'long')
    if preparation_plan is not None:
        modules = sorted({m for row in preparation_plan['suites'] for m in row['plan']['tests']})
        tasks = [(':testClasses' if m == 'tools' else ':'+m+':testClasses') for m in modules] + [':installDist']
        control = dict(mode='prepare', tasks={}, modules=modules, preparationTasks=tasks,
                       repo=str(repo.resolve()), output=str(output.resolve()),
                       testForks=effective_forks, gradleWorkers=build_workers)
        (output/'selection.json').write_text(json.dumps(control), encoding='utf8')
        argv = wrapper + [':publicPreparation', '--daemon', '--console=plain', '--max-workers='+str(build_workers),
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
        if source_meta is not None:
            if repo_metadata_snapshot(repo) != source_meta:
                raise RuntimeError('Public source changed during shared distribution build')
        elif identities(repo) != source:
            raise RuntimeError('Public source changed during shared distribution build')
        final_toolchain = toolchain_identity()
        result['toolchainAfter'] = final_toolchain
        if final_toolchain != initial_toolchain:
            raise RuntimeError('JDK or Gradle configuration changed during shared distribution build')
        artifact_meta = None
        if preparation_plan is not None:
            effective = json.loads((output/'effective.json').read_text(encoding='utf8'))
            executions = json.loads((output/'task-executions.json').read_text(encoding='utf8'))
            if (effective['tests'] or effective['mode'] != 'prepare' or effective['preparationTasks'] != tasks or
                    any(r['failure'] for r in executions) or
                    not set(tasks).issubset(r['task'] for r in executions)):
                raise RuntimeError('Incomplete shared preparation task execution')
            artifact_roots = json.loads((output/'artifact-roots.json').read_text(encoding='utf8'))
            preparation = dict(repo=str(repo.resolve()), selection=[r['plan'] for r in preparation_plan['suites']],
                tasks=tasks, executions=executions, effective=effective,
                artifacts=artifact_identities(artifact_roots))
            artifact_meta = artifact_metadata_snapshot(artifact_roots)
        package = output/'package'
        shutil.copytree(project/'build/install/simulation-test', package)
        receipt = output/'distribution.json'
        pin = seal(package, receipt, source, initial_toolchain, kind=kind, preparation=preparation)
        pkg_meta = package_metadata_snapshot(package)
        verify_fast(receipt, pin, source, initial_toolchain, kind=kind,
                    plan=preparation['selection'][0] if preparation else None, repo=repo,
                    expected_artifact_meta=artifact_meta, expected_package_meta=pkg_meta)
        result.update(status='passed', receipt=str(receipt), sha256=pin, kind=kind, preparation=preparation)
    except Exception as error:
        result['error'] = str(error)
    result['elapsedSeconds'] = time.monotonic()-started
    (output/'result.json').write_text(json.dumps(result, ensure_ascii=False, indent=2)+'\n', encoding='utf8')
    return result


def run(repo, output, plan, prepared_distribution=None, command_timeout_seconds=600, forks=None, workers=2,
        *, scenarios_only=False, lock_wait=1800, workers_gradle=None, pinned_source=None):
    output.mkdir(parents=True, exist_ok=False)
    concurrency = test_concurrency(plan['tests'], forks, workers_gradle)
    effective_forks = concurrency['requested']
    effective_gradle_workers = concurrency['maxWorkers']
    if pinned_source is not None:
        initial, initial_meta = pinned_source
        if repo_metadata_snapshot(repo) != initial_meta:
            raise ValueError('Public source changed before suite start')
    else:
        initial = identities(repo)
        try:
            initial_meta = repo_metadata_snapshot(repo)
        except OSError:
            initial_meta = None
    result = dict(schemaVersion=1, suite=plan['suiteId'], selection=plan, status='execution-failed',
                  cloudRun=False, source=initial, commands=[], coverage=[],
                  exclusions=['original-compatibility', 'platform-input', 'historical-snapshot-reexecution'],
                  commandTimeoutSeconds=command_timeout_seconds,
                  testConcurrency=concurrency, scenarioConcurrency=scenario_concurrency(workers))
    if scenarios_only:
        if prepared_distribution is None:
            raise ValueError('Batch scenarios require a prepared distribution')
        result['mechanismsInBatch'] = True
    if not any(case['kind'] == 'long' for case in plan['scenarios']):
        result['exclusions'].append('continuous-long')
    started = time.monotonic()
    measured_checks = {}
    check_lock = threading.Lock()

    def measure_check(phase, check):
        before = time.monotonic()
        try:
            return check()
        finally:
            elapsed = time.monotonic() - before
            with check_lock:
                timing = measured_checks.setdefault(phase, dict(count=0, elapsedSeconds=0))
                timing['count'] += 1
                timing['elapsedSeconds'] += elapsed

    def verify_source_fast():
        if initial_meta is not None:
            if repo_metadata_snapshot(repo) != initial_meta:
                raise ValueError('Public source changed during verification')
        elif identities(repo) != initial:
            raise ValueError('Public source changed during verification')

    def verify_source_full():
        if identities(repo) != initial:
            raise ValueError('Public source changed during verification')

    dist_kind = plan['kind'] if plan['kind'] in ('short', 'long') else 'pr'
    pinned_toolchain = None
    pinned_artifact_meta = None
    pinned_package_meta = None

    def admit_initial():
        nonlocal pinned_toolchain, pinned_artifact_meta, pinned_package_meta
        from public_distribution import (
            read_receipt, verify, verify_fast, toolchain_identity,
            artifact_metadata_snapshot, package_metadata_snapshot,
        )
        def _check():
            nonlocal pinned_toolchain, pinned_artifact_meta, pinned_package_meta
            verify_source_fast()
            pinned_toolchain = toolchain_identity()
            if pinned_source is not None:
                record = read_receipt(prepared_distribution[0], prepared_distribution[1], initial,
                                      pinned_toolchain, kind=dist_kind, plan=plan, repo=repo)
                if record['preparation']:
                    pinned_artifact_meta = artifact_metadata_snapshot(
                        r['path'] for r in record['preparation']['artifacts'])
                dist = verify_fast(prepared_distribution[0], prepared_distribution[1], initial,
                                   pinned_toolchain, kind=dist_kind, plan=plan, repo=repo,
                                   expected_artifact_meta=pinned_artifact_meta)
                pinned_package_meta = package_metadata_snapshot(dist)
                return dist
            dist = verify(prepared_distribution[0], prepared_distribution[1], initial,
                          pinned_toolchain, kind=dist_kind, plan=plan, repo=repo)
            record = read_receipt(prepared_distribution[0], prepared_distribution[1], initial,
                                  pinned_toolchain, kind=dist_kind, plan=plan, repo=repo)
            if record['preparation']:
                pinned_artifact_meta = artifact_metadata_snapshot(
                    r['path'] for r in record['preparation']['artifacts'])
            pinned_package_meta = package_metadata_snapshot(dist)
            return dist
        return measure_check('preparation', _check)

    def admit():
        from public_distribution import verify_fast, toolchain_identity
        def _check():
            verify_source_fast()
            return verify_fast(
                prepared_distribution[0], prepared_distribution[1], initial,
                toolchain_identity(), kind=dist_kind, plan=plan, repo=repo,
                expected_artifact_meta=pinned_artifact_meta, expected_package_meta=pinned_package_meta)
        return measure_check('preparation', _check)

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
        from public_distribution import digest, package_files, package_metadata_snapshot, read_receipt, verify, verify_members
        with resources(['host:heavy-verification', 'build:'+str(repo/'Engine'), 'build:'+str(repo/'SimulationTest')],
                       wait_seconds=lock_wait):
            project = repo / 'SimulationTest'
            if prepared_distribution is not None:
                if plan['kind'] not in ('pr', 'mechanism', 'short', 'long') or plan['inputScope'] != 'public':
                    raise ValueError('A prepared package requires its exact public PR, short or long selection')
                distribution = admit_initial()
                result['preparedDistribution'] = dict(receipt=str(prepared_distribution[0]), sha256=prepared_distribution[1])
            wrapper = [str(project / 'gradlew.bat')] if os.name == 'nt' else ['sh', str(project / 'gradlew')]
            common = ['--no-daemon', '--console=plain', '--max-workers='+str(effective_gradle_workers), '--rerun-tasks']
            if plan['fixture']:
                common += ['-Dpublic.inputs.dir=' + str(project / 'public' / plan['fixture'])]
            if plan['tests'] and not scenarios_only:
                if prepared_distribution is not None:
                    admit()
                from development_check import execute_test_group
                executed = execute_test_group(repo, output/'test-execution',
                    dict(fixture=plan['fixture'], tests=plan['tests'], suites=[plan['suiteId']]),
                    prepared_distribution is None, effective_forks, package=prepared_distribution is None,
                    timeout=command_timeout_seconds, gradle_workers=effective_gradle_workers)
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
                fixed_files = measure_check('package', lambda: package_files(distribution)) if plan['scenarios'] else None
        result['pythonCoverage'] = []
        for index, relative in enumerate([] if scenarios_only else plan.get('pythonTests', [])):
            name = 'python-' + str(index) + '-' + Path(relative).stem
            summary = output / (name + '.json')
            command(name, [sys.executable, '-B', str(project/'ci/python_check.py'),
                          '--test', str(repo/relative), '--output', str(summary)], repo)
            measured = json.loads(summary.read_text(encoding='utf8'))
            if (measured['status'] != 'passed' or measured['tests'] < 1 or
                    any(measured[k] for k in ['failures', 'errors', 'skipped']) or not measured['sourceUnchanged']):
                raise RuntimeError('Python mechanism evidence is incomplete: ' + relative)
            result['pythonCoverage'].append(dict(path=relative, result=name+'.json', **measured))
        from public_scenarios import run_scenarios
        with resources(['host:heavy-verification', 'build:'+str(repo/'Engine'), 'build:'+str(repo/'SimulationTest')],
                       wait_seconds=lock_wait):
            # Verify package member bytes once on the first scenario admission inside run_scenarios
            # (preserving scenario-execution.json pre-dispatch failure receipts), then use fast
            # metadata snapshots + exact per-scenario input SHA-256 across remaining scenarios.
            if prepared_distribution is not None:
                admit()
            scenario_package_state = {'checked_bytes': False, 'package_meta': None}

            expected_source = {row['path']: row['sha256'] for row in initial}

            def verify_scenario_inputs(case):
                verify_scenario_sources(repo, case, expected_source)
                with check_lock:
                    first_package_check = not scenario_package_state['checked_bytes']
                    if first_package_check:
                        if prepared_distribution is not None:
                            scenario_pin = read_receipt(*prepared_distribution, initial,
                                kind=dist_kind, plan=plan, repo=repo)
                            verify_members(prepared_distribution[0], scenario_pin)
                        elif package_files(distribution) != fixed_files:
                            raise ValueError('Scenario distribution changed during verification')
                        scenario_package_state['package_meta'] = package_metadata_snapshot(distribution)
                        scenario_package_state['checked_bytes'] = True
                        return
                    expected_pkg_meta = scenario_package_state['package_meta']
                if prepared_distribution is not None:
                    receipt_file = Path(prepared_distribution[0])
                    if receipt_file.is_symlink() or not receipt_file.is_file() or digest(receipt_file) != prepared_distribution[1]:
                        raise ValueError('Fixed distribution receipt changed')
                    if package_metadata_snapshot(distribution) != expected_pkg_meta:
                        raise ValueError('Fixed distribution members or dependency bytes changed')
                elif package_metadata_snapshot(distribution) != expected_pkg_meta:
                    raise ValueError('Scenario distribution changed during verification')

            scenarios = run_scenarios(repo, output, plan['scenarios'], distribution, workers,
                command_timeout_seconds, lambda case: measure_check('scenario', lambda: verify_scenario_inputs(case)),
                execute_command)
            result['scenarioExecution'] = scenarios
            result['commands'].extend(c for item in scenarios['items'] for c in item['commands'])
            archives = [item['archive'] for item in scenarios['items'] if item['status'] == 'passed']
            result['archives'] = archives
            if scenarios['status'] != 'passed':
                raise RuntimeError('Scenario execution failed: '+str(scenarios['firstError']))
            if pinned_source is not None:
                measure_check('final-source', verify_source_fast)
                if prepared_distribution is not None:
                    admit()
            else:
                measure_check('final-source', verify_source_full)
                if prepared_distribution is not None:
                    measure_check('final-preparation', lambda: verify(
                        prepared_distribution[0], prepared_distribution[1], initial,
                        kind=dist_kind, plan=plan, repo=repo))
                elif fixed_files is not None:
                    def _verify_final_package():
                        if package_files(distribution) != fixed_files:
                            raise ValueError('Scenario distribution changed during verification')
                    measure_check('final-package', _verify_final_package)
        result.update(status='passed', archives=archives)
        if plan['suiteId'] == 'public-pr-first-loop' and archives:
            result['shortArchive'] = archives[0]['path']
    except Exception as error:
        result['error'] = str(error)
    result['identityChecks'] = measured_checks
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
    parser.add_argument('--test-forks', type=test_forks, default=None, help='Bound independent test JVMs: 1, 2, 3, 4 or 8; PC stays serial')
    parser.add_argument('--gradle-workers', type=gradle_workers, default=None, help='Bound Gradle max workers independently (1..16)')
    parser.add_argument('--scenario-workers', type=scenario_workers, default=2, help='Bound independent headless scenarios after tests: 1, 2, 4 or 8')
    parser.add_argument('--command-timeout',type=command_timeout,default=600,
                        help='Explicit per-command seconds, at most 3600; default 600. Selection is unchanged.')
    parser.add_argument('--lock-wait-seconds', type=lock_wait_seconds, default=1800,
                        help='Max seconds to wait on host:heavy-verification queue (0 fails fast); default 1800')
    parser.add_argument('--distribution-receipt', type=Path, help='Same-batch fixed public PR preparation or long package receipt')
    parser.add_argument('--distribution-sha256', help='Receipt digest pinned by the parent batch')
    args = parser.parse_args()
    try:
        plan = plan_suite(REPO, args.suite, args.kind)
    except (ValueError, KeyError, TypeError) as error:
        parser.error(str(error))
    if args.list:
        print(json.dumps(dict(plan, testConcurrency=test_concurrency(plan['tests'], args.test_forks, args.gradle_workers),
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
    return run(repo, output, plan, prepared, args.command_timeout, args.test_forks, args.scenario_workers,
               lock_wait=args.lock_wait_seconds, workers_gradle=args.gradle_workers)


if __name__ == '__main__':
    raise SystemExit(main())
