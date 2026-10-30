"""Local/CI entry for the first public mechanism + short suite (Python standard library)."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import time
import uuid
import xml.etree.ElementTree as ET

REPO = Path(__file__).resolve().parents[2]


def source_files(repo):
    """Explicit public source closure; never copy TestLab, Docs, evidence or original inputs."""
    roots = ['Engine/core/src', 'SimulationTest/src/main', 'SimulationTest/src/test',
             'SimulationTest/public', 'SimulationTest/ci', 'SimulationTest/gradle']
    paths = [p for root in roots for p in (repo / root).rglob('*')
             if p.is_file() and '__pycache__' not in p.parts]
    paths += [repo / name for name in ['Engine/core/build.gradle.kts', 'SimulationTest/build.gradle.kts',
              'SimulationTest/settings.gradle.kts', 'SimulationTest/gradle.properties',
              'SimulationTest/gradlew', 'SimulationTest/gradlew.bat']]
    return sorted(paths)


def identities(repo):
    return [dict(path=p.relative_to(repo).as_posix(), sha256=hashlib.sha256(p.read_bytes()).hexdigest())
            for p in source_files(repo)]


def run(repo, output):
    output.mkdir(parents=True, exist_ok=False)
    initial = identities(repo)
    result = dict(schemaVersion=1, suite='public-pr-first-loop', status='execution-failed',
                  cloudRun=False, source=initial, commands=[], coverage=[],
                  exclusions=['original-compatibility', 'platform-input', 'full-historical-migration', 'continuous-long'])
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
        suite = json.loads((project / 'public/suites/pr-first-loop.json').read_text(encoding='utf-8'))
        if suite['schemaVersion'] != 1 or suite['suiteId'] != result['suite']:
            raise RuntimeError('Unsupported public suite identity')
        wrapper = [str(project / 'gradlew.bat')] if os.name == 'nt' else ['sh', str(project / 'gradlew')]
        common = ['--no-daemon', '--console=plain', '--max-workers=1', '--rerun-tasks']
        tool_classes = suite['toolTests']
        core_classes = suite['mechanismTests']
        command('tools-and-package', wrapper + [':test'] +
                [v for name in tool_classes for v in ['--tests', name]] + [':installDist'] + common, project)
        junit(project / 'build/test-results/test', 'tool-tests', tool_classes)
        command('mechanisms', wrapper + [':core:test'] +
                [v for name in core_classes for v in ['--tests', name]] + common, project)
        junit(project / 'build/core/test-results/test', 'mechanism-tests', core_classes)
        launcher = project / 'build/install/simulation-test/bin' / ('simulation-test.bat' if os.name == 'nt' else 'simulation-test')
        cli = [str(launcher)] if os.name == 'nt' else ['sh', str(launcher)]
        command('short', cli + ['run', '--scenario', str(project / 'public' / suite['short']['scenario']),
                '--contract', str(project / 'public' / suite['short']['contract']),
                '--output', str(output / 'short'), '--mode', 'self'], project)
        batches = list((output / 'short').glob('run-*'))
        if len(batches) != 1:
            raise RuntimeError('Expected exactly one short-run archive')
        judgment = json.loads((batches[0] / 'comparison/result.json').read_text(encoding='utf-8'))
        manifest = json.loads((batches[0] / 'manifest.json').read_text(encoding='utf-8'))
        if judgment['exitCode'] != 0 or not manifest['complete']:
            raise RuntimeError('Short evidence is not complete/pass')
        if initial != identities(repo):
            raise RuntimeError('Public source changed during verification')
        result.update(status='passed', shortArchive=str(batches[0]))
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
    args = parser.parse_args()
    output = (args.output or REPO / 'SimulationTest/build/public-check' / str(uuid.uuid4())).resolve()
    repo = REPO
    if args.isolated:
        repo = Path(tempfile.mkdtemp(prefix='reforge-public-'))
        for source in source_files(REPO):
            target = repo / source.relative_to(REPO)
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, target)
        print('Isolated public source:', repo)
    return run(repo, output)


if __name__ == '__main__':
    raise SystemExit(main())
