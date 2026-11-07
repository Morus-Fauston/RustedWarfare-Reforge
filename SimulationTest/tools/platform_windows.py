"""Real owned Windows input backend for the fixed shared client-intent plans."""
import ctypes
from ctypes import wintypes as W
from contextlib import ExitStack
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import time

from platform_scenario import PlatformError, compare_archive, read, save, sha, tree, validate_intent_contract
from diagnostic_reader import DiagnosticReader
from action_layout import wait_layout

TITLE = 'RustedWarfare-Reforge | P002 Windows sandbox'


def validate_windows_plan(plan,contract):
    intents,rules=plan.get('intents'),contract.get('rules')
    if ('history' in contract or not isinstance(intents,list) or not intents or
            not isinstance(rules,list) or len(intents)!=len(rules) or
            plan.get('scenarioId')!=contract.get('scenarioId')):
        raise PlatformError('input-contract','Windows requires matching immediate short-intent contracts; explicit-step history uses Core/Session',1)
    if len({i.get('intentId') for i in intents})!=len(intents):
        raise PlatformError('input-contract','Duplicate Windows intention identity',1)
    for intent,rule in zip(intents,rules):validate_intent_contract(intent,rule)


def run_windows(scenario, contract, distribution, output, size, java='java'):
    from owned_window import Guard, Win32
    from workflow import resources
    if os.name != 'nt' or size not in ((1200, 800), (960, 640)):
        raise PlatformError('platform-input', 'Real Windows and a registered client size are required', 1)
    scenario, contract, distribution, output = map(lambda p: Path(p).resolve(), (scenario, contract, distribution, output))
    java = str(Path(shutil.which(java) or java).resolve())
    plan, contract_data = read(scenario), read(contract)
    validate_windows_plan(plan,contract_data)
    rules=contract_data['rules']
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
    reader=DiagnosticReader(diagnostic)
    current_generation=None
    def logs():
        wait('current live diagnostic prefix',lambda rows:True)
        return [r for r in reader.view() if current_generation is None or
                'generation' not in r or r['generation']==current_generation]
    def wait(label,predicate,deadline=15,after_sequence=-1,after_generation=None):
        return reader.wait(label,predicate,proc,after_sequence=after_sequence,
            generation=current_generation if after_generation is None else None,
            after_generation=after_generation,timeout=deadline,check=guard.check if guard is not None else None)
    def layout():
        return wait_layout(wait,current_generation,size)
    def click_action(action_id):
        drawn=layout();target=drawn.action(action_id)
        record(dict(kind='drawn-action-target',actionId=action_id,layout=drawn.frame,target=target))
        guard.click(*target)
    def world(position):
        drawn=layout();target=drawn.world(position['x'],position['y'])
        record(dict(kind='drawn-world-target',position=position,layoutRevision=drawn.frame['layoutRevision'],target=target))
        return target
    def stroke(vk,modifiers=()):
        try:
            for modifier in modifiers:guard.down('key',modifier)
            guard.down('key',vk);guard.settle(.012);guard.up('key',vk)
            for modifier in reversed(modifiers):guard.up('key',modifier)
            guard.settle(.012)
        finally:guard.release_owned()
    def choose_content_pack():
        choice=plan.get('contentPack')
        if choice is None:return
        source=(output/'inputSnapshot'/choice['directory']).resolve()
        if not source.is_relative_to(output/'inputSnapshot') or not source.is_dir():
            raise PlatformError('input-identity','Content package must be inside the frozen input snapshot',1)
        # Ordinary keyboard entry uses an owned short ASCII copy, retaining its full identities.
        short=Path(tempfile.mkdtemp(prefix='rwre-window-pack-')).resolve()/'pack'
        shutil.copytree(source,short);expected=tree(source)
        if tree(short)!=expected:raise PlatformError('input-identity','Short content copy differs')
        save(output/'content-copy.json',dict(original=str(source),typedPath=str(short),files=expected))
        click_action('content-tab');click_action('content-directory')
        text=short.as_posix()
        if not text.isascii() or len(text)>160 or any(c not in 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789:/._-' for c in text):
            raise PlatformError('input-contract','Content input requires a bounded owned ASCII path',1)
        api.u.VkKeyScanW.argtypes=[ctypes.c_wchar];api.u.VkKeyScanW.restype=ctypes.c_short
        stroke(65,(17,))
        for character in text:
            mapping=api.u.VkKeyScanW(character)
            if mapping<0 or mapping>>8 not in (0,1):raise PlatformError('input-contract','Unqualified path key mapping')
            stroke(mapping&255,(16,) if mapping>>8 else ())
        def menu(rows):return next((r for r in reversed(rows) if r['kind']=='content-pack-menu'),{})
        wait('typed content directory',lambda rows:menu(rows).get('input')==str(short))
        click_action('content-check')
        wait('checked package',lambda rows:menu(rows).get('canEnable') is True and menu(rows).get('busy') is False,deadline=60)
        if tree(short)!=expected:raise PlatformError('input-identity','Content changed during menu check')
        click_action('content-enable')
        rows=wait('enabled package',lambda rows:menu(rows).get('enabled') is True)
        actual=menu(rows);units=actual.get('availableUnits',[]);selected=choice['unitId']
        if selected not in units:raise PlatformError('input-contract','Requested start unit is absent from checked selector')
        if actual.get('unitId')!=selected:
            click_action('content-unit');click_action('content-unit:'+selected)
            wait('selected package unit',lambda rows:menu(rows).get('unitId')==selected)
        def start_drawn(rows):
            return any(a['actionId']=='start-match' and a['enabled'] is True
                       for a in layout().frame['actions'])
        wait('package previews and enabled Start',start_drawn,deadline=60)
        guard.capture(output/'content-package-enabled.png')
    u = ctypes.WinDLL('user32', use_last_error=True)
    u.GetForegroundWindow.restype = W.HWND
    u.GetWindowThreadProcessId.argtypes = [W.HWND, ctypes.POINTER(W.DWORD)]
    u.IsWindow.argtypes = [W.HWND]
    u.SetForegroundWindow.argtypes = [W.HWND]
    previous = int(u.GetForegroundWindow() or 0)
    previous_pid = W.DWORD()
    previous_thread = int(u.GetWindowThreadProcessId(previous, ctypes.byref(previous_pid))) if previous else 0
    lease=ExitStack()
    try:lease.enter_context(resources(['windows:interactive:rustedwarfare-reforge']))
    except Exception as error:
        result.update(category='resource-unavailable',message=str(error),productStarted=False,endedUnix=time.time())
        save(output/'result.json',result)
        return result
    with lease:
        try:
            shutil.copyfile(scenario, output/'scenario.json')
            shutil.copyfile(contract, output/'contract.json')
            shutil.copytree(inputs, output/'inputSnapshot')
            if tree(output/'inputSnapshot') != input_before or tree(inputs) != input_before:
                raise PlatformError('input-identity', 'Input snapshot changed while freezing')
            command = [java, '--enable-native-access=ALL-UNNAMED', '-cp', os.pathsep.join(map(str, jars)),
                       'com.rustedwarfare.reforge.pc.WindowsLauncherKt', '--mode', 'sandbox',
                       '--inputs', str(output/'inputSnapshot'), '--evidence', str(output/'runtime'), '--visuals','placeholder']
            save(output/'manifest.json', dict(backend='windows-input', command=command, size=list(size),
                scenarioSha256=sha(output/'scenario.json'), contractSha256=sha(output/'contract.json'),
                inputFiles=input_before, packageFiles=package_before, javaSha256=sha(java),
                comparatorSha256=sha(Path(__file__).with_name('platform_scenario.py')),
                driverSha256=sha(__file__), guardSha256=sha(Path(__file__).with_name('owned_window.py')),
                readerSha256=sha(Path(__file__).with_name('diagnostic_reader.py')),
                layoutSha256=sha(Path(__file__).with_name('action_layout.py')),
                childLocalAppData=str(output/'user-data'),visuals='placeholder',
                previousForeground=dict(hwnd=previous, pid=previous_pid.value, thread=previous_thread)))
            with (output/'stdout.log').open('xb') as stdout, (output/'stderr.log').open('xb') as stderr:
                proc = subprocess.Popen(command, stdout=stdout, stderr=stderr, stdin=subprocess.DEVNULL,
                                        creationflags=subprocess.CREATE_NO_WINDOW, shell=False,
                                        env=dict(os.environ,LOCALAPPDATA=str(output/'user-data')))
            result['pid'] = proc.pid
            api = Win32(proc, java, TITLE)
            end = time.monotonic()+15
            while not api.candidates():
                if proc.poll() is not None or time.monotonic() > end:
                    raise PlatformError('owned-window', 'Own PC window did not appear')
                time.sleep(.05)
            api.bind()
            api.prepare_english_once(True, record)
            api.place_for_input(record)
            api.initial_focus_once(True,record)
            guard = Guard(api, api.input_state, record)
            guard.resize(*size)
            wait('viewport', lambda rows: any(row.get('kind') == 'resize' and
                 (row['width'], row['height']) == size for row in rows))
            layout();guard.capture(output/'startup-menu.png')
            choose_content_pack()
            load_boundary=reader.cursor
            previous_generation=max((r['generation'] for r in reader.view() if r['kind']=='load-request'),default=0)
            click_action('start-match')
            request=wait('load-request',lambda rows:any(r['kind']=='load-request' for r in rows),
                         after_sequence=load_boundary,after_generation=previous_generation)
            current_generation=next(r['generation'] for r in request if r['kind']=='load-request')
            rows=wait('load-ready',lambda rows:any(r['kind']=='load-ready' for r in rows),
                      deadline=180,after_sequence=load_boundary)
            wait('first interactive frame',lambda rows:any(r['kind']=='load-first-frame-drawn' for r in rows),
                 after_sequence=load_boundary)
            guard.capture(output/'first-world.png')
            for intent, rule in zip(plan['intents'], rules):
                guard.check()
                if anchors:
                    # The comparator requires distinct dispatch boundaries. A quick capture may
                    # finish before another diagnostic is emitted; wait for a real record.
                    wait('next intent boundary', lambda rows: rows[-1]['sequence'] > anchors[-1]['endSequence'])
                logs();start=reader.cursor
                record(dict(kind='intent-dispatch', intentId=intent['intentId'], afterSequence=start))
                kind = intent['kind']
                if kind == 'select':
                    guard.click(*world(intent['position']))
                elif kind in ('move', 'attack'):
                    guard.click(*world(intent['position']), button='right')
                elif kind == 'stop':
                    click_action('stop')
                elif kind == 'produce':
                    click_action('produce:'+intent['typeId'])
                elif kind == 'cancelProduction':
                    click_action('cancel-production:'+intent['typeId'])
                elif kind == 'upgrade':
                    click_action('upgrade:'+intent['upgradeId'])
                elif kind == 'cancelUpgrade':
                    click_action('cancel-upgrade:'+intent['upgradeId'])
                elif kind == 'reclaim':
                    click_action('choose-reclaim')
                    guard.click(*world(intent['position']))
                elif kind == 'build':
                    click_action('choose-construction:'+intent['typeId'])
                    guard.move(*world(intent['position']))
                    guard.settle(.12)
                    guard.capture(output/(intent['intentId']+'-preview.png'))
                    guard.click(*world(intent['position']))
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
                anchors.append(dict(intentId=intent['intentId'], afterSequence=start, endSequence=reader.cursor))
                guard.capture(output/(intent['intentId']+'.png'))
            guard.close_owned()
            result['processExitCode'] = proc.wait(timeout=20)
            result['stream']=reader.finish(result['processExitCode'])
            if tree(distribution) != package_before or tree(output/'inputSnapshot') != input_before:
                raise PlatformError('input-identity', 'Fixed package or input snapshot changed during PC execution')
            save(output/'intents.json', anchors)
            (output/'session').mkdir()
            shutil.copyfile(diagnostic, output/'session/client-diagnostic.jsonl')
            result['complete'] = True
        except Exception as error:
            result.update(category=getattr(error, 'category', 'windows-exception'), message=str(error),
                          exitCode=getattr(error, 'exit_code', 3))
            if getattr(error,'record',None) is not None:result['diagnosticFailure']=error.record
            if getattr(error,'secondary',None):result['diagnosticSecondary']=error.secondary
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
            except Exception as error:
                result.update(exitCode=3,category='archive-comparison',message=type(error).__name__+': '+str(error),status='incomplete')
        result['endedUnix'] = time.time()
        save(output/'result.json', result)
    return result
