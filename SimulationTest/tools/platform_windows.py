"""Real owned Windows input backend for the fixed shared client-intent plans."""
import ctypes
from ctypes import wintypes as W
import json
import os
from pathlib import Path
import shutil
import subprocess
import time
import xml.etree.ElementTree as ET

from platform_scenario import PlatformError, compare_archive, read, save, sha, tree

TITLE = 'RustedWarfare-Reforge | P002 Windows sandbox'


def run_windows(scenario, contract, distribution, output, size, java='java'):
    from owned_window import Guard, Win32
    from workflow import resources
    if os.name != 'nt' or size not in ((1200, 800), (960, 640)):
        raise PlatformError('platform-input', 'Real Windows and a registered client size are required', 1)
    scenario, contract, distribution, output = map(lambda p: Path(p).resolve(), (scenario, contract, distribution, output))
    java = str(Path(shutil.which(java) or java).resolve())
    plan, rules = read(scenario), read(contract)['rules']
    package_before = tree(distribution)
    inputs = (scenario.parent / plan['inputs']).resolve()
    input_before = tree(inputs)
    jars = sorted((distribution/'lib').glob('*.jar'))
    if not jars or not input_before:
        raise PlatformError('input-identity', 'Fixed PC package and public inputs required', 1)
    output.mkdir()
    result = dict(exitCode=3, backend='windows-input', status='incomplete', size=list(size), startedUnix=time.time())
    proc = api = guard = None
    anchors = []
    def record(row):
        with (output/'input.jsonl').open('a', encoding='utf8') as stream:
            stream.write(json.dumps(dict(unix=time.time(), **row), ensure_ascii=True)+'\n')
    diagnostic = output/'runtime/reforge/client-diagnostic.jsonl'
    def logs():
        if not diagnostic.is_file():
            return []
        lines = diagnostic.read_bytes().split(b'\n')
        return [json.loads(line) for line in lines[:-1] if line]
    def wait(label, predicate, deadline=15):
        end = time.monotonic()+deadline
        while time.monotonic() < end:
            if proc.poll() is not None:
                raise PlatformError('adapter-process', 'Own PC process exited during '+label)
            if guard is not None:
                guard.check()
            rows = logs()
            if predicate(rows):
                return rows
            time.sleep(.05)
        raise PlatformError('input-timeout', 'Missing actual diagnostic event: '+label)
    def ui(x, y):
        resize = next(row for row in reversed(logs()) if row['kind'] == 'resize')
        return (round(resize['screenX']+x*resize['screenWidth']/960),
                round(resize['height']-resize['screenY']-y*resize['screenHeight']/640))
    def click(x, y, button='left'):
        guard.click(*ui(x, y), button)
    u = ctypes.WinDLL('user32', use_last_error=True)
    u.GetForegroundWindow.restype = W.HWND
    u.GetWindowThreadProcessId.argtypes = [W.HWND, ctypes.POINTER(W.DWORD)]
    u.IsWindow.argtypes = [W.HWND]
    u.SetForegroundWindow.argtypes = [W.HWND]
    previous = int(u.GetForegroundWindow() or 0)
    previous_pid = W.DWORD()
    previous_thread = int(u.GetWindowThreadProcessId(previous, ctypes.byref(previous_pid))) if previous else 0
    with resources(['windows:interactive:rustedwarfare-reforge']):
        try:
            shutil.copyfile(scenario, output/'scenario.json')
            shutil.copyfile(contract, output/'contract.json')
            shutil.copytree(inputs, output/'inputSnapshot')
            if tree(output/'inputSnapshot') != input_before or tree(inputs) != input_before:
                raise PlatformError('input-identity', 'Input snapshot changed while freezing')
            command = [java, '--enable-native-access=ALL-UNNAMED', '-cp', os.pathsep.join(map(str, jars)),
                       'com.rustedwarfare.reforge.pc.WindowsLauncherKt', '--mode', 'sandbox',
                       '--inputs', str(output/'inputSnapshot'), '--evidence', str(output/'runtime')]
            save(output/'manifest.json', dict(backend='windows-input', command=command, size=list(size),
                scenarioSha256=sha(output/'scenario.json'), contractSha256=sha(output/'contract.json'),
                inputFiles=input_before, packageFiles=package_before, javaSha256=sha(java),
                comparatorSha256=sha(Path(__file__).with_name('platform_scenario.py')),
                driverSha256=sha(__file__), guardSha256=sha(Path(__file__).with_name('owned_window.py')),
                previousForeground=dict(hwnd=previous, pid=previous_pid.value, thread=previous_thread)))
            with (output/'stdout.log').open('xb') as stdout, (output/'stderr.log').open('xb') as stderr:
                proc = subprocess.Popen(command, stdout=stdout, stderr=stderr, stdin=subprocess.DEVNULL,
                                        creationflags=subprocess.CREATE_NO_WINDOW, shell=False)
            result['pid'] = proc.pid
            api = Win32(proc, java, TITLE)
            end = time.monotonic()+15
            while not api.candidates():
                if proc.poll() is not None or time.monotonic() > end:
                    raise PlatformError('owned-window', 'Own PC window did not appear')
                time.sleep(.05)
            api.bind()
            api.prepare_english_once(True, record)
            api.initial_focus_once(True)
            guard = Guard(api, api.input_state, record)
            guard.resize(*size)
            wait('viewport', lambda rows: any(row.get('kind') == 'resize' and
                 (row['width'], row['height']) == size for row in rows))
            click(830, 140)
            rows = wait('load-ready', lambda rows: any(row['kind'] == 'load-ready' for row in rows))
            if any(row['kind'] == 'load-error' for row in rows):
                raise PlatformError('input-load', 'Actual PC content load failed')
            map_root = ET.parse(output/'inputSnapshot/maps/p001-controlled.tmx').getroot()
            width = int(map_root.get('width'))*int(map_root.get('tilewidth'))
            height = int(map_root.get('height'))*int(map_root.get('tileheight'))
            scale = min(704/width, 512/height)
            def world(position):
                return (16+(704-width*scale)/2+position['x']*scale,
                        64+(512-height*scale)/2+(height-position['y'])*scale)
            for intent, rule in zip(plan['intents'], rules):
                guard.check()
                start = logs()[-1]['sequence']
                record(dict(kind='intent-dispatch', intentId=intent['intentId'], afterSequence=start))
                kind = intent['kind']
                if kind == 'select':
                    click(*world(intent['position']))
                elif kind in ('move', 'attack'):
                    click(*world(intent['position']), button='right')
                elif kind == 'stop':
                    guard.key(83)
                elif kind == 'produce':
                    click(830, 284)
                elif kind == 'cancelProduction':
                    click(830, 236)
                elif kind == 'upgrade':
                    click(790, 284)
                elif kind == 'cancelUpgrade':
                    click(884, 284)
                elif kind == 'build':
                    click(884, 307)
                    guard.move(*ui(*world(intent['position'])))
                    guard.settle(.12)
                    guard.capture(output/(intent['intentId']+'-preview.png'))
                    click(*world(intent['position']))
                else:
                    raise PlatformError('input-contract', 'Unsupported PC intention: '+kind, 1)
                rows = wait(intent['intentId'], lambda rows: any(row.get('kind') == 'action' and
                            row['sequence'] > start and row.get('type') == rule['actionType'] for row in rows))
                action = next(row for row in rows if row.get('kind') == 'action' and row['sequence'] > start and row.get('type') == rule['actionType'])
                rows = wait('after-action', lambda rows: any(row.get('phase') == 'after-action' and row['sequence'] == action['sequence']+1 for row in rows))
                immediate = next(row for row in rows if row['sequence'] == action['sequence']+1)['sample']
                steps = intent.get('waitSteps', 0)
                if steps:
                    rows = wait('bounded post-command steps', lambda rows: any(row.get('phase') == 'step' and
                                row['sequence'] > action['sequence'] and row['sample']['stepIndex'] >= immediate['stepIndex']+steps for row in rows))
                anchors.append(dict(intentId=intent['intentId'], afterSequence=start, endSequence=rows[-1]['sequence']))
                guard.capture(output/(intent['intentId']+'.png'))
            guard.close_owned()
            result['processExitCode'] = proc.wait(timeout=20)
            if result['processExitCode'] != 0 or not any(row['kind'] == 'application-disposed' for row in logs()):
                raise PlatformError('closing-tail', 'PC application did not close normally')
            if tree(distribution) != package_before or tree(output/'inputSnapshot') != input_before:
                raise PlatformError('input-identity', 'Fixed package or input snapshot changed during PC execution')
            save(output/'intents.json', anchors)
            (output/'session').mkdir()
            shutil.copyfile(diagnostic, output/'session/client-diagnostic.jsonl')
            result['complete'] = True
        except Exception as error:
            result.update(category=getattr(error, 'category', 'windows-exception'), message=str(error),
                          exitCode=getattr(error, 'exit_code', 3))
        finally:
            if proc is not None and proc.poll() is None and api is not None and api.bound_state is not None:
                try:
                    if guard is not None:
                        guard.release_owned()
                        guard.close_owned()
                    else:
                        from owned_window import validate_identity
                        validate_identity(api.bound_state, api.snapshot(), foreground=False)
                        api.close_message()
                    result['cleanupExitCode'] = proc.wait(timeout=20)
                except Exception as error:
                    result.setdefault('cleanupErrors', []).append(str(error))
            if api is not None:
                api.release_handle()
            observed_pid = W.DWORD()
            observed_thread = int(u.GetWindowThreadProcessId(previous, ctypes.byref(observed_pid))) if previous and u.IsWindow(previous) else 0
            if previous and previous_thread == observed_thread and previous_pid.value == observed_pid.value:
                restored = bool(u.SetForegroundWindow(previous))
                time.sleep(.1)
                result['foregroundRestored'] = restored and int(u.GetForegroundWindow() or 0) == previous
            else:
                result['foregroundRestored'] = None
                result['foregroundRestoreReason'] = 'Previous window no longer has its recorded identity'
            record(dict(kind='foreground-restoration', restored=result['foregroundRestored']))
        if result.get('complete') and not result.get('cleanupErrors') and result['foregroundRestored'] is not False:
            save(output/'archive.json', dict(complete=True, backend='windows-input', files=tree(output)))
            try:
                result.update(compare_archive(output))
            except PlatformError as error:
                result.update(exitCode=error.exit_code, category=error.category, message=str(error), status='failed' if error.exit_code == 2 else 'incomplete')
        result['endedUnix'] = time.time()
        save(output/'result.json', result)
    return result
