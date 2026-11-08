"""Bound independent headless scenarios; preserve every started item's terminal state."""
from concurrent.futures import ThreadPoolExecutor, wait, FIRST_COMPLETED
import json
import os
from pathlib import Path
import re
import sys
import threading
import time


def run_scenarios(repo, output, cases, distribution, workers, timeout, admit, execute_command):
    rows = [dict(id=c['id'], kind=c['kind'], status='not-started', commands=[]) for c in cases]
    result = dict(schemaVersion=1, status='execution-failed', requestedWorkers=workers,
                  peakRunning=0, items=rows, firstError=None)
    lock, stopped = threading.Lock(), threading.Event()
    active = 0

    def fail(index, error, first_error=None):
        with lock:
            rows[index]['error'] = str(error)
            if result['firstError'] is None:
                result['firstError'] = dict(id=cases[index]['id'], error=str(error),
                    cause=first_error or dict(kind='scenario-validation', message=str(error)))
            stopped.set()

    try:
        if workers not in (1, 2, 4):
            raise ValueError('Scenario workers must be 1, 2 or 4')
        names = []
        for case in cases:
            name = case['id']
            if not re.fullmatch(r'[a-z0-9][a-z0-9-]*', name):
                raise ValueError('Invalid scenario output name')
            commands = [name] + ([name+'-recompare'] if case.get('backend') == 'session' else [])
            names += [name] + [n+suffix for n in commands for suffix in ('.log', '-process')]
        if len(set(n.casefold() for n in names)) != len(names) or any((output/n).exists() for n in names):
            raise ValueError('Scenario outputs conflict or already exist')
    except Exception as error:
        result['firstError'] = dict(error=str(error), cause=dict(kind='scenario-preflight', message=str(error)))
        (output/'scenario-execution.json').write_text(json.dumps(result, ensure_ascii=True, indent=2), encoding='utf8')
        return result

    project = repo/'SimulationTest'
    launcher = distribution/'bin'/('simulation-test.bat' if os.name == 'nt' else 'simulation-test')
    cli = [str(launcher)] if os.name == 'nt' else ['sh', str(launcher)]

    def work(index):
        nonlocal active
        case, row = cases[index], rows[index]
        with lock:
            if stopped.is_set():
                return
            active += 1
            result['peakRunning'] = max(result['peakRunning'], active)
            row.update(status='running', startedUnix=time.time())
        cause = None
        def command(name, argv):
            nonlocal cause
            admit()
            measured = execute_command(output, name, argv, project, timeout)
            row['commands'].append(dict(name=name, argv=measured['argv'], exitCode=measured['exitCode'],
                elapsedSeconds=measured['elapsedSeconds'], receipt=name+'-process/result.json',
                startedUnix=measured['startedUnix'], endedUnix=measured['endedUnix'], firstError=measured['firstError']))
            if not measured['passed']:
                cause = measured['firstError']
                raise RuntimeError(name+': scenario command failed')
            admit()
        try:
            folder = output/case['id']
            if case.get('backend') == 'session':
                command(case['id'], [sys.executable, '-B', str(project/'tools/platform_scenario.py'),
                    'session', '--scenario', str(project/'public'/case['scenario']),
                    '--contract', str(project/'public'/case['contract']), '--distribution', str(distribution), '--output', str(folder)])
                judgment = json.loads((folder/'result.json').read_text(encoding='utf8'))
                if judgment['exitCode'] != 0 or judgment['status'] != 'passed':
                    raise RuntimeError('Session evidence is not complete/pass: '+case['id'])
                command(case['id']+'-recompare', [sys.executable, '-B', str(project/'tools/platform_scenario.py'),
                    'compare', '--run', str(folder)])
                archive = dict(id=case['id'], kind=case['kind'], backend='session', path=str(folder))
            else:
                command(case['id'], cli+['run', '--scenario', str(project/'public'/case['scenario']),
                    '--contract', str(project/'public'/case['contract']), '--output', str(folder), '--mode', 'self'])
                batches = list(folder.glob('run-*'))
                if len(batches) != 1:
                    raise RuntimeError('Expected exactly one scenario-run archive')
                judgment = json.loads((batches[0]/'comparison/result.json').read_text(encoding='utf8'))
                manifest = json.loads((batches[0]/'manifest.json').read_text(encoding='utf8'))
                if judgment['exitCode'] != 0 or not manifest['complete']:
                    raise RuntimeError('Scenario evidence is not complete/pass: '+case['id'])
                archive = dict(id=case['id'], kind=case['kind'], path=str(batches[0]))
            row.update(status='passed', archive=archive)
        except Exception as error:
            row['status'] = 'failed'
            fail(index, error, cause)
        finally:
            with lock:
                row['endedUnix'] = time.time()
                active -= 1

    next_index, running = 0, {}
    with ThreadPoolExecutor(max_workers=workers) as pool:
        while running or (next_index < len(cases) and not stopped.is_set()):
            while next_index < len(cases) and len(running) < workers and not stopped.is_set():
                try:
                    admit()
                except Exception as error:
                    fail(next_index, error)
                    break
                with lock:
                    if stopped.is_set():
                        break
                    running[pool.submit(work, next_index)] = next_index
                    next_index += 1
            if running:
                done, _ = wait(running, return_when=FIRST_COMPLETED)
                for future in done:
                    future.result()
                    del running[future]
    result['status'] = 'passed' if all(r['status'] == 'passed' for r in rows) else 'execution-failed'
    (output/'scenario-execution.json').write_text(json.dumps(result, ensure_ascii=True, indent=2), encoding='utf8')
    return result
