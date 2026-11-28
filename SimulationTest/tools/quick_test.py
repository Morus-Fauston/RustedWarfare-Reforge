"""Fast warm-daemon L1/L2 local test runner for Engine and SimulationTest.

Keeps all headless checks on the SimulationTest Gradle root so :core/:client/:pc
incremental compilation and the Gradle daemon stay warm across targeted unit
tests, named public mechanism suites, and dev-ab micro-probes. Never stops the
Gradle daemon and never invokes public-long or full public_batch PR sweeps.
"""
import argparse
from datetime import datetime
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import time


DEFAULT_REPO = Path(__file__).resolve().parents[2]
PUBLIC_P001_RELATIVE = Path('SimulationTest/public/inputs/content-movement-knowledge/P001-移动沙盒输入')
SUITE_NAME_RE = re.compile(r'^[a-z0-9][a-z0-9-]*$')
MODULE_TASKS = (
    ('core', ':core:test'),
    ('client', ':client:test'),
    ('pc', ':pc:test'),
    ('tools', ':test'),
)


def default_test_forks(cpu_count=None):
    """Map available CPUs to the bounded public CI fork set {1, 2, 4}."""
    cores = os.cpu_count() if cpu_count is None else cpu_count
    half = (cores or 2) // 2
    if half >= 4:
        return 4
    if half >= 2:
        return 2
    return 1


def parse_test_forks(value):
    if str(value) not in ('1', '2', '3', '4', '8'):
        raise argparse.ArgumentTypeError('Test forks must be 1, 2, 3, 4 or 8')
    return int(value)


def parse_gradle_workers(value):
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


def default_gradle_workers(forks, *, cloud=None, cpu_count=None):
    is_cloud = (os.environ.get('GITHUB_ACTIONS') == 'true') if cloud is None else bool(cloud)
    cores = (os.cpu_count() or 2) if cpu_count is None else cpu_count
    if is_cloud or cores <= 4 or forks == 1:
        return forks
    return max(forks, min(8, cores // 2))


def gradle_wrapper(project_dir, is_windows=None):
    windows = (os.name == 'nt') if is_windows is None else is_windows
    if windows:
        return [str(project_dir / 'gradlew.bat')]
    return ['sh', str(project_dir / 'gradlew')]


def validate_filter(pattern):
    cleaned = pattern.strip()
    if not cleaned or cleaned.startswith('-') or any(ch.isspace() for ch in cleaned):
        raise ValueError('Test filter must be non-empty, not start with "-", and contain no whitespace: ' + repr(pattern))
    return cleaned


def validate_suite_name(repo, name):
    if not isinstance(name, str) or not name.strip() or name != name.strip():
        raise ValueError('Suite name must be non-empty without surrounding whitespace: ' + repr(name))
    if not SUITE_NAME_RE.fullmatch(name):
        raise ValueError('Suite name must be a public registry name: ' + repr(name))
    suite_file = repo / 'SimulationTest/public/suites' / f'{name}.json'
    if not suite_file.is_file():
        raise ValueError('Unknown public suite: ' + name)
    return name


def quote_gradle_arg(value, *, is_windows=None):
    """Quote a value safely for Gradle JavaExec --args (Ant Commandline.translateCommandline)."""
    text = str(value)
    if any(ch in text for ch in ('\r', '\n', '\0')):
        raise ValueError('Gradle --args value must not contain control characters: ' + repr(text))
    windows = (os.name == 'nt') if is_windows is None else is_windows
    if windows and any(ch in text for ch in ('"', "'")):
        raise ValueError('Windows gradlew.bat --args path must not contain quotes: ' + repr(text))
    if not any(ch.isspace() or ch in ('"', "'") for ch in text):
        return text
    if "'" not in text:
        return f"'{text}'"
    # POSIX: Ant Commandline concatenates adjacent quoted tokens without backslash escaping.
    parts = text.split("'")
    quoted = []
    for index, segment in enumerate(parts):
        if segment:
            quoted.append(f"'{segment}'")
        if index < len(parts) - 1:
            quoted.append('"\'"')
    return ''.join(quoted)


def new_temp_dir(prefix, base_dir=None):
    root = (Path(base_dir) if base_dir is not None else Path(tempfile.gettempdir()) / 'reforge-quick-test').resolve()
    stamp = datetime.now().strftime('%Y%m%d-%H%M%S')
    candidate = (root / f'{prefix}-{stamp}-{os.getpid()}').resolve()
    index = 1
    while candidate.exists():
        candidate = (root / f'{prefix}-{stamp}-{os.getpid()}-{index}').resolve()
        index += 1
    return candidate


def build_plan(repo, *, core=(), client=(), pc=(), tools=(), suites=(), dev_ab=False,
               forks=None, gradle_workers=None, rerun=False, temp_root=None, is_windows=None, cloud=None):
    repo = Path(repo).resolve()
    sim_dir = repo / 'SimulationTest'
    public_p001 = repo / PUBLIC_P001_RELATIVE
    if not (sim_dir / 'build.gradle.kts').is_file():
        raise ValueError('Repository root does not contain SimulationTest/build.gradle.kts: ' + str(repo))
    if not public_p001.is_dir():
        raise ValueError('Missing public P001 fixture directory: ' + str(public_p001))

    is_cloud = (os.environ.get('GITHUB_ACTIONS') == 'true') if cloud is None else bool(cloud)
    effective_forks = default_test_forks() if forks is None else parse_test_forks(forks)
    effective_gradle_workers = (
        default_gradle_workers(effective_forks, cloud=is_cloud)
        if gradle_workers is None else parse_gradle_workers(gradle_workers)
    )
    wrapper = gradle_wrapper(sim_dir, is_windows=is_windows)
    steps = []

    selected_modules = {'core': core, 'client': client, 'pc': pc, 'tools': tools}
    test_tasks = []
    active_modules = []
    for module_name, task_path in MODULE_TASKS:
        patterns = [validate_filter(p) for p in selected_modules[module_name]]
        if not patterns:
            continue
        active_modules.append(module_name)
        test_tasks.append(task_path)
        for pattern in patterns:
            test_tasks.extend(['--tests', pattern])
        if rerun:
            test_tasks.append('--rerun')

    if test_tasks:
        per_module_cap = max(1, min(effective_forks, effective_gradle_workers))
        allocated = allocate_module_test_forks(active_modules, per_module_cap, is_cloud=is_cloud)
        fork_props = [f'-P{mod}.test.maxParallelForks={allocated.get(mod, 1)}' for mod in ('core', 'client', 'tools')]
        argv = (
            wrapper
            + test_tasks
            + [
                '--daemon',
                '--console=plain',
                f'--max-workers={effective_gradle_workers}',
                *fork_props,
                f'-Dpublic.inputs.dir={public_p001}',
            ]
        )
        steps.append(dict(
            name='targeted-tests',
            layer='L1',
            cwd=str(sim_dir),
            argv=argv,
        ))

    if suites:
        suite_names = [validate_suite_name(repo, name) for name in suites]
        suite_out = new_temp_dir('dev-check', base_dir=temp_root)
        argv = [sys.executable, '-B', str(sim_dir / 'ci' / 'development_check.py')]
        for name in suite_names:
            argv.extend(['--suite', name])
        argv.extend([
            '--test-forks', str(effective_forks),
            '--gradle-workers', str(effective_gradle_workers),
            '--output', str(suite_out),
        ])
        steps.append(dict(
            name='mechanism-suites',
            layer='L1',
            cwd=str(repo),
            output=str(suite_out),
            argv=argv,
        ))

    if dev_ab:
        probe_out = new_temp_dir('dev-ab', base_dir=temp_root)
        quoted_out = quote_gradle_arg(probe_out, is_windows=is_windows)
        argv = (
            wrapper
            + [
                ':movementProbe',
                '--daemon',
                '--console=plain',
                f'--max-workers={effective_gradle_workers}',
                f'--args=--scene dev-ab --assert true --output {quoted_out}',
            ]
        )
        steps.append(dict(
            name='dev-ab',
            layer='L2',
            cwd=str(sim_dir),
            output=str(probe_out),
            argv=argv,
        ))

    if not steps:
        raise ValueError('Select at least one target: --core, --client, --pc, --tools, --suite or --dev-ab')

    return dict(
        schemaVersion=1,
        repo=str(repo),
        testForks=effective_forks,
        gradleWorkers=effective_gradle_workers,
        steps=steps,
    )


def run_plan(plan, runner=subprocess.run):
    results = []
    started_all = time.monotonic()
    for step in plan['steps']:
        output = step.get('output')
        if output and step['name'] != 'mechanism-suites':
            Path(output).mkdir(parents=True, exist_ok=True)
        elif output:
            Path(output).parent.mkdir(parents=True, exist_ok=True)
        started = time.monotonic()
        completed = runner(step['argv'], cwd=step['cwd'])
        elapsed = round(time.monotonic() - started, 3)
        row = dict(
            name=step['name'],
            layer=step['layer'],
            exitCode=completed.returncode,
            elapsedSeconds=elapsed,
            output=output,
        )
        results.append(row)
        if completed.returncode != 0:
            return dict(
                status='failed',
                failedStep=step['name'],
                elapsedSeconds=round(time.monotonic() - started_all, 3),
                results=results,
            )
    return dict(
        status='passed',
        elapsedSeconds=round(time.monotonic() - started_all, 3),
        results=results,
    )


def build_parser():
    parser = argparse.ArgumentParser(
        description=(
            'Run warm-daemon L1/L2 local checks on the SimulationTest Gradle root '
            '(never runs --stop, public-long, or full public_batch --kind pr).'
        )
    )
    parser.add_argument('--repo', type=Path, default=DEFAULT_REPO,
                        help='Repository or git worktree root (defaults to this script worktree)')
    parser.add_argument('--core', nargs='+', default=[], metavar='FILTER',
                        help='Targeted :core:test class or Class.method filter(s)')
    parser.add_argument('--client', nargs='+', default=[], metavar='FILTER',
                        help='Targeted :client:test class or Class.method filter(s)')
    parser.add_argument('--pc', nargs='+', default=[], metavar='FILTER',
                        help='Targeted :pc:test class or Class.method filter(s)')
    parser.add_argument('--tools', nargs='+', default=[], metavar='FILTER',
                        help='Targeted SimulationTest :test class or Class.method filter(s)')
    parser.add_argument('--suite', action='append', default=[], metavar='SUITE',
                        help='Warm public mechanism suite forwarded to development_check.py (repeatable)')
    parser.add_argument('--dev-ab', action='store_true',
                        help='Run SimulationTest :movementProbe --scene dev-ab --assert true on the same warm daemon')
    parser.add_argument('--test-forks', type=parse_test_forks, default=None,
                        help='Bounded module test forks: 1, 2, 3, 4 or 8; PC stays serial')
    parser.add_argument('--gradle-workers', type=parse_gradle_workers, default=None,
                        help='Bounded Gradle max workers (1..16, defaults independently from CPU count)')
    parser.add_argument('--rerun', action='store_true',
                        help='Force re-execution of targeted test task(s) via --rerun without recompiling upstream')
    parser.add_argument('--temp-root', type=Path, default=None,
                        help='Override temporary output root (defaults to system temp directory)')
    parser.add_argument('--dry-run', '--list', dest='dry_run', action='store_true',
                        help='Print planned commands as JSON without executing')
    return parser


def main(argv=None):
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        plan = build_plan(
            args.repo,
            core=args.core,
            client=args.client,
            pc=args.pc,
            tools=args.tools,
            suites=args.suite,
            dev_ab=args.dev_ab,
            forks=args.test_forks,
            gradle_workers=args.gradle_workers,
            rerun=args.rerun,
            temp_root=args.temp_root,
        )
    except (ValueError, argparse.ArgumentTypeError) as error:
        parser.error(str(error))
    if args.dry_run:
        print(json.dumps(plan, ensure_ascii=False, indent=2))
        return 0
    summary = run_plan(plan)
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0 if summary['status'] == 'passed' else 1


if __name__ == '__main__':
    raise SystemExit(main())
