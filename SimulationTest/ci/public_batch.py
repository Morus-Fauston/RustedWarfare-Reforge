"""Run a fixed complete public test layer locally or in CI; keep every child receipt."""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time

from public_check import REPO, identities, plan_suite, source_files


SUITES = ('content-movement-knowledge', 'production-combat', 'build-economy',
          'maintenance-defense', 'platform-basics', 'tooling')
LAYERS = {
    'mechanism': SUITES,
    'short': SUITES[:5],
    'pr': SUITES,
    'long': SUITES[:4],
}


def plan_layer(repo, kind):
    rows = []
    for name in LAYERS[kind]:
        selected_kind = 'mechanism' if name == 'tooling' and kind == 'pr' else kind
        plan = plan_suite(repo, name, selected_kind)
        if plan['inputScope'] != 'public':
            raise ValueError('Public layer contains private suite: ' + name)
        rows.append(dict(name=name, plan=plan))
    return dict(schemaVersion=1, kind=kind, suites=rows,
                exclusions=['original-runtime', 'device', 'real-platform-input', 'formal-performance'])


def run_layer(repo, output, plan, isolated):
    output.mkdir(parents=True, exist_ok=False)
    started = time.monotonic()
    source = identities(repo)
    result = dict(schemaVersion=1, status='execution-failed', selection=plan, source=source,
                  isolated=isolated, suites=[], cloudRun=os.environ.get('GITHUB_ACTIONS') == 'true')
    try:
        for row in plan['suites']:
            name = row['name']
            child = output / name
            argv = [sys.executable, '-B', str(repo/'SimulationTest/ci/public_check.py'),
                    '--suite', name, '--kind', row['plan']['kind'], '--output', str(child)]
            record = dict(name=name, argv=argv, result=name+'/result.json', exitCode=None)
            result['suites'].append(record)
            with (output/(name+'.log')).open('w', encoding='utf8') as log:
                # The existing runner bounds each actual command. Do not kill only the
                # intermediate Python process and leave its Gradle/JVM child running.
                process = subprocess.run(argv, cwd=repo, stdout=log, stderr=subprocess.STDOUT)
                record['exitCode'] = process.returncode
            if record['exitCode'] != 0:
                raise RuntimeError(name + ': child failed; see ' + name + '.log')
            measured = json.loads((child/'result.json').read_text(encoding='utf8'))
            if (measured['status'] != 'passed' or measured['selection'] != row['plan'] or
                    measured['source'] != source):
                raise RuntimeError(name + ': incomplete, mismatched or changed-source receipt')
            record.update(status='passed', elapsedSeconds=measured['elapsedSeconds'])
        if identities(repo) != source:
            raise RuntimeError('Public source changed during batch')
        result['status'] = 'passed'
    except Exception as error:
        result['error'] = str(error)
    result['elapsedSeconds'] = time.monotonic() - started
    (output/'result.json').write_text(json.dumps(result, ensure_ascii=False, indent=2)+'\n', encoding='utf8')
    print(json.dumps(dict(status=result['status'], output=str(output), error=result.get('error'))))
    return 0 if result['status'] == 'passed' else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--kind', required=True, choices=tuple(LAYERS))
    parser.add_argument('--output', type=Path, help='New immutable batch directory')
    parser.add_argument('--list', action='store_true', help='List fixed complete layer without execution')
    parser.add_argument('--isolated', action='store_true', help='Copy only authored public source closure')
    args = parser.parse_args()
    try:
        plan = plan_layer(REPO, args.kind)
    except (ValueError, KeyError, TypeError, OSError) as error:
        parser.error(str(error))
    if args.list:
        print(json.dumps(plan))
        return 0
    if args.output is None:
        parser.error('--output is required for execution')
    output = args.output.resolve()
    if output.exists():
        parser.error('Output must be new: ' + str(output))
    repo = REPO
    if args.isolated:
        repo = Path(tempfile.mkdtemp(prefix='reforge-public-layer-'))
        for source in source_files(REPO):
            target = repo/source.relative_to(REPO)
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, target)
    return run_layer(repo, output, plan, args.isolated)


if __name__ == '__main__':
    raise SystemExit(main())
