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

from platform_scenario import PlatformError, compare_archive, read, save, sha, tree, validate_intent_contract, validate_unit_action_intent
from diagnostic_reader import DiagnosticReader
from platform_wait import WaitSegment
from action_layout import wait_layout

TITLE = 'RustedWarfare-Reforge | P002 Windows sandbox'
DEFAULT_STARTS = {
    'public.phase-six-a-command-json.v1': ('phase-six-a-json.json', False),
    'public.phase-six-a-spider-json.v1': ('phase-six-a-spider-json.json', True),
}


def startup_arguments(plan,snapshot,evidence):
    default=plan.get('windowsDefaultStart',False)
    if type(default) is not bool or default and 'testScene' not in plan:
        raise PlatformError('input-contract','Default Windows startup requires an explicit paired scene',1)
    if default:
        selection = DEFAULT_STARTS.get(plan.get('scenarioId'))
        if selection is None or plan.get('windowsSpiderStart', False) is not selection[1]:
            raise PlatformError('input-contract', 'Installed Windows Start supports the two Phase VI A JSON seeds', 1)
    tail=['--evidence',str(evidence),'--visuals','placeholder']
    if default:
        return tail,'RustedWarfare-Reforge | Phase VI A sandbox'
    return ['--mode','sandbox','--inputs',str(snapshot)]+tail,TITLE


def validate_windows_plan(plan,contract):
    intents,rules=plan.get('intents'),contract.get('rules')
    history='history' in contract
    if ((history and 'testScene' not in plan and 'contentPack' not in plan) or not isinstance(intents,list) or not intents or
            not isinstance(rules,list) or len(intents)!=len(rules) or
            plan.get('scenarioId')!=contract.get('scenarioId')):
        raise PlatformError('input-contract','Windows requires matching intents and a paired scene or normal content menu for history',1)
    if len({i.get('intentId') for i in intents})!=len(intents):
        raise PlatformError('input-contract','Duplicate Windows intention identity',1)
    paused=plan.get('windowsPausedIntents',[])
    if (not isinstance(paused,list) or any(not isinstance(identity,str) for identity in paused) or
            len(paused)!=len(set(paused)) or paused and not history or
            any(not any(i.get('intentId')==identity and type(i.get('waitSteps')) is int and
                        i['waitSteps']==0 for i in intents) for identity in paused)):
        raise PlatformError('input-contract','Paused Windows intentions must be unique known zero-wait history intentions',1)
    if not history:
        for intent,rule in zip(intents,rules):validate_intent_contract(intent,rule)
    else:
        if [i['intentId'] for i in intents]!=[r['intentId'] for r in rules]:
            raise PlatformError('input-contract','History intention order differs',1)
        for identity,segment in plan.get('windowsWaits',{}).items():
            intent=next((i for i in intents if i['intentId']==identity),None)
            progress=segment.get('progressOnly',False)
            event_valid=(type(progress) is bool and
                (not any(k in segment for k in ('eventKind','unitType')) if progress else
                 isinstance(segment.get('eventKind'),str) and isinstance(segment.get('unitType'),str)))
            if (not intent or not intent.get('waitSteps') or segment.get('rate')!=4 or
                    not event_valid or
                    not intent['waitSteps']<=segment.get('maximumSimulationSteps',0)<=1200 or
                    not 0<segment.get('stallSeconds',0)<=segment.get('maximumWallSeconds',0)<=60):
                raise PlatformError('input-contract','Invalid declared waiting segment',1)
    action_types={'select':'SelectAction','move':'MoveAction','build':'BuildAction','upgrade':'UpgradeAction',
                  'produce':'ProduceAction','attack':'AttackAction','repair':'RepairUnitAction','reclaim':'ReclaimUnitAction','stop':'StopAction','submarine-depth':'SubmarineDepthAction',
                  'amphibious-flight':'AmphibiousFlightAction','load':'LoadAction','unload':'UnloadAction','unit-action':'UnitAction'}
    for intent in intents:
        if intent.get('kind')=='unit-action':validate_unit_action_intent(intent, paired='testScene' in plan)
    if any(i.get('kind')=='load' and i.get('direction') not in ('INTO','UP') for i in intents):
        raise PlatformError('input-contract','Load intention requires INTO or UP',1)
    if any(i.get('kind')=='unload' and type(i.get('unloading')) is not bool for i in intents):
        raise PlatformError('input-contract','Unload intention requires a strict boolean',1)
    if any(i.get('kind')=='amphibious-flight' and type(i.get('flying')) is not bool for i in intents):
        raise PlatformError('input-contract','Amphibious intention requires a strict flying boolean',1)
    if any(i.get('kind')=='submarine-depth' and type(i.get('surface')) is not bool for i in intents):
        raise PlatformError('input-contract','Submarine intention requires a strict surface boolean',1)
    if history:
        if any(i.get('kind') not in action_types for i in intents):
            raise PlatformError('input-contract','Unsupported Windows history intention',1)
        no_actions=contract['history'].get('noActionIntents',[])
        if (not isinstance(no_actions,list) or any(not isinstance(x,str) for x in no_actions) or
                len(no_actions)!=len(set(no_actions)) or
                any(not any(i['intentId']==x and i['kind']=='select' for i in intents) for x in no_actions)):
            raise PlatformError('input-contract','Only declared empty selections may omit a formal action',1)
        return [dict(r,actionType=action_types[i['kind']],**({'noFormalAction':True} if i['intentId'] in no_actions else {}))
                for i,r in zip(intents,rules)]
    return rules


def input_receipt(rows,rule,start,input_events=None):
    """Bind the dispatched input to its real formal call or explicit empty-selection release."""
    current=[r for r in rows if r['sequence']>start]
    if rule.get('noFormalAction'):
        if any(r.get('kind')=='action' for r in current):
            raise PlatformError('input-action','An empty selection unexpectedly submitted a formal action',2)
        selection=next((r for r in current if r.get('kind')=='selection'),None)
        if selection is None:return None
        if selection.get('selectedId') is not None or selection.get('selectedIds')!=[]:
            raise PlatformError('input-selection','Expected the declared empty selection',2)
        releases=current if input_events is None else input_events
        release=next((r for r in releases if r.get('kind')=='map-release' and
            r['sequence']==selection['sequence']+1 and r.get('button')==0 and
            r.get('intent')=='Click' and r.get('selectedIds')==[] and
            r.get('generation',selection.get('generation'))==selection.get('generation')),None)
        if release is None:return None
        after=next((r for r in current if r.get('kind')=='observation' and
            r.get('phase')=='step' and r['sequence']>release['sequence']),None)
        return (release,after['sample']) if after is not None else None
    action=next((r for r in current if r.get('kind')=='action' and r.get('type')==rule['actionType']),None)
    if action is None:return None
    after=next((r for r in current if r.get('kind')=='observation' and
        r.get('phase')=='after-action' and r['sequence']==action['sequence']+1),None)
    return (action,after['sample']) if after is not None else None


def wait_for_input(wait,reader,label,rule,start):
    # map-release is an unscoped UI receipt. Bind it only by the immediate sequence
    # after the generation-qualified selection; observations remain generation filtered.
    rows=wait(label,lambda rows:input_receipt(rows,rule,start,reader.view()) is not None)
    return input_receipt(rows,rule,start,reader.view())


def run_windows(scenario, contract, distribution, output, size, java='java', rate_profile='normal'):
    from owned_window import Guard, Win32
    from workflow import resources
    if os.name != 'nt' or size not in ((1200, 800), (960, 640)):
        raise PlatformError('platform-input', 'Real Windows and a registered client size are required', 1)
    scenario, contract, distribution, output = map(lambda p: Path(p).resolve(), (scenario, contract, distribution, output))
    java = str(Path(shutil.which(java) or java).resolve())
    plan, contract_data = read(scenario), read(contract)
    rules=validate_windows_plan(plan,contract_data)
    arguments,title=startup_arguments(plan,output/'inputSnapshot',output/'runtime')
    if rate_profile not in ('normal','segmented') or rate_profile=='segmented' and not plan.get('windowsWaits'):
        raise PlatformError('input-contract','Segmented profile requires declared waiting segments',1)
    history='history' in contract_data
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
    case_deadline=time.monotonic()+300
    segments=[]
    def save_segments():
        (output/'waiting-segments.json').write_text(json.dumps(segments,indent=2,allow_nan=False)+'\n',encoding='utf8')
    def logs():
        wait('current live diagnostic prefix',lambda rows:True)
        return [r for r in reader.view() if current_generation is None or
                'generation' not in r or r['generation']==current_generation]
    def wait(label,predicate,deadline=15,after_sequence=-1,after_generation=None):
        deadline=min(deadline,max(.01,case_deadline-time.monotonic()))
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
    def set_speed(rate):
        boundary=reader.cursor
        click_action('set-speed:'+str(rate))
        rows=wait('current speed receipt '+str(rate),lambda rows:WaitSegment.speed_ack(rows,current_generation,boundary,rate),deadline=3)
        ack=next(r for r in reversed(rows) if r.get('kind')=='simulation-control' and r['sequence']>boundary)
        record(dict(kind='verified-speed',requestedRate=rate,receipt=ack))
        return ack
    def set_paused(paused):
        boundary=reader.cursor
        click_action('pause-simulation')
        def matching(rows):
            return next((r for r in reversed(rows) if r.get('kind')=='simulation-control' and
                r.get('generation')==current_generation and r['sequence']>boundary and
                r.get('action')=='pause' and r.get('paused') is paused),None)
        rows=wait('current pause receipt '+str(paused),lambda rows:matching(rows) is not None,deadline=3)
        record(dict(kind='verified-pause',requestedPaused=paused,receipt=matching(rows)))
    def wait_segment(intent,action,immediate):
        declaration=plan.get('windowsWaits',{}).get(intent['intentId'])
        rate=4 if declaration and rate_profile=='segmented' else 1
        limits=declaration or dict(maximumSimulationSteps=1200,maximumWallSeconds=35,stallSeconds=4)
        began=time.monotonic()
        gate=WaitSegment(generation=current_generation,world=immediate['worldId'],after_sequence=action['sequence'],
            initial_step=immediate['stepIndex'],minimum_steps=intent.get('waitSteps',0),
            maximum_steps=limits['maximumSimulationSteps'],wall_seconds=limits['maximumWallSeconds'],
            stall_seconds=limits['stallSeconds'],started=began,
            event_kind=declaration.get('eventKind') if declaration else None,
            unit_type=declaration.get('unitType') if declaration else None)
        entry=dict(intentId=intent['intentId'],requestedRate=rate,startedUnix=time.time(),actionSequence=action['sequence'])
        segments.append(entry)
        try:
            entry['speedStart']=set_speed(rate)
            wait('event and bounded progress '+intent['intentId'],lambda rows:gate.observe(rows,time.monotonic()),deadline=limits['maximumWallSeconds']+1)
            entry.update(gate.receipt(time.monotonic()))
            entry['speedRestored']=set_speed(1)
            pacing=[r for r in reader.view() if r.get('kind')=='simulation-pacing' and r.get('generation')==current_generation and
                entry['speedStart']['sequence']<=r['sequence']<=entry['speedRestored']['sequence']]
            entry['actualPacing']=pacing
            entry['measuredMultiplier']=entry['simulationSteps']/60/max(entry['wallSeconds'],.0001)
            entry['endedUnix']=time.time()
        except Exception as error:
            entry.update(gate.receipt(time.monotonic()))
            entry['firstFailure']=entry['firstFailure'] or str(error)
            raise
        finally:save_segments()
    u = ctypes.WinDLL('user32', use_last_error=True)
    u.GetForegroundWindow.restype = W.HWND
    u.GetWindowThreadProcessId.argtypes = [W.HWND, ctypes.POINTER(W.DWORD)]
    u.IsWindow.argtypes = [W.HWND]
    u.SetForegroundWindow.argtypes = [W.HWND]
    lease=ExitStack()
    try:lease.enter_context(resources(['windows:interactive:rustedwarfare-reforge']))
    except Exception as error:
        result.update(category='resource-unavailable',message=str(error),productStarted=False,endedUnix=time.time())
        save(output/'result.json',result)
        return result
    with lease:
        previous = int(u.GetForegroundWindow() or 0)
        previous_pid = W.DWORD()
        previous_thread = int(u.GetWindowThreadProcessId(previous, ctypes.byref(previous_pid))) if previous else 0
        try:
            shutil.copyfile(scenario, output/'scenario.json')
            shutil.copyfile(contract, output/'contract.json')
            shutil.copytree(inputs, output/'inputSnapshot')
            if tree(output/'inputSnapshot') != input_before or tree(inputs) != input_before:
                raise PlatformError('input-identity', 'Input snapshot changed while freezing')
            command = [java, '--enable-native-access=ALL-UNNAMED', '-cp', os.pathsep.join(map(str, jars)),
                       'com.rustedwarfare.reforge.pc.WindowsLauncherKt']+arguments
            save(output/'manifest.json', dict(backend='windows-input', command=command, size=list(size),
                scenarioSha256=sha(output/'scenario.json'), contractSha256=sha(output/'contract.json'),
                inputFiles=input_before, packageFiles=package_before, javaSha256=sha(java),
                comparatorSha256=sha(Path(__file__).with_name('platform_scenario.py')),
                driverSha256=sha(__file__), guardSha256=sha(Path(__file__).with_name('owned_window.py')),
                readerSha256=sha(Path(__file__).with_name('diagnostic_reader.py')),
                waitSha256=sha(Path(__file__).with_name('platform_wait.py')),
                layoutSha256=sha(Path(__file__).with_name('action_layout.py')),
                childLocalAppData=str(output/'user-data'),visuals='placeholder',
                rateProfile=rate_profile,
                previousForeground=dict(hwnd=previous, pid=previous_pid.value, thread=previous_thread)))
            with (output/'stdout.log').open('xb') as stdout, (output/'stderr.log').open('xb') as stderr:
                proc = subprocess.Popen(command, stdout=stdout, stderr=stderr, stdin=subprocess.DEVNULL,
                                        creationflags=subprocess.CREATE_NO_WINDOW, shell=False,
                                        env=dict(os.environ,LOCALAPPDATA=str(output/'user-data')))
            result['pid'] = proc.pid
            api = Win32(proc, java, title)
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
            if 'testScene' in plan:
                default=plan.get('windowsDefaultStart',False)
                if default:
                    seed, spider_start = DEFAULT_STARTS[plan['scenarioId']]
                else:
                    seed = 'dual-ship-production.json'
                packaged=distribution/'test-scenarios/scenarios'/seed
                if sha(packaged)!=sha(scenario):raise PlatformError('input-identity','Packaged and dispatched seed differ',1)
                if default:
                    installed_inputs=(packaged.parent/plan['inputs']).resolve()
                    if (not installed_inputs.is_relative_to(distribution/'test-scenarios/inputs') or
                            tree(installed_inputs)!=input_before):
                        raise PlatformError('input-identity','Default installed input closure differs from the fixed scene',1)
                    record(dict(kind='default-start-ready',seed=str(packaged),inputRoot=str(installed_inputs),
                                menuChoiceClicks=2 if spider_start else 0))
                    if spider_start:
                        click_action('test-scenes-tab')
                        click_action('test-scene-spider')
                        wait('ordinary Modular Spider start selected', lambda rows: any(
                            row.get('kind') == 'test-scenario-choice' and row.get('id') == plan['scenarioId']
                            and row.get('error') is None for row in rows))
                else:
                    click_action('test-scenes-tab');click_action('test-scene-use')
                    wait('paired scene selected',lambda rows:any(r.get('kind')=='test-scenario-choice' and r.get('id')==plan['scenarioId'] and r.get('error') is None for r in rows))
            else:choose_content_pack()
            load_boundary=reader.cursor
            previous_generation=max((r['generation'] for r in reader.view() if r['kind']=='load-request'),default=0)
            click_action('start-match')
            request=wait('load-request',lambda rows:any(r['kind']=='load-request' for r in rows),
                         after_sequence=load_boundary,after_generation=previous_generation)
            current_generation=next(r['generation'] for r in request if r['kind']=='load-request')
            if plan.get('windowsDefaultStart') and not any(r.get('kind')=='load-request' and
                    r.get('testScenarioId')==plan['scenarioId'] for r in request):
                raise PlatformError('default-start','Default Start did not consume its paired scene',2)
            rows=wait('load-ready',lambda rows:any(r['kind']=='load-ready' for r in rows),
                      deadline=180,after_sequence=load_boundary)
            wait('first interactive frame',lambda rows:any(r['kind']=='load-first-frame-drawn' for r in rows),
                 after_sequence=load_boundary)
            guard.capture(output/'first-world.png')
            if history:set_speed(1)
            clock_paused=False
            for intent, rule in zip(plan['intents'], rules):
                guard.check()
                requested_pause=intent['intentId'] in plan.get('windowsPausedIntents',[])
                if requested_pause!=clock_paused:
                    set_paused(requested_pause)
                    clock_paused=requested_pause
                if anchors:
                    # The comparator requires distinct dispatch boundaries. A quick capture may
                    # finish before another diagnostic is emitted; wait for a real record.
                    wait('next intent boundary', lambda rows: rows[-1]['sequence'] > anchors[-1]['endSequence'])
                logs();start=reader.cursor
                record(dict(kind='intent-dispatch', intentId=intent['intentId'], afterSequence=start))
                kind = intent['kind']
                if kind == 'select':
                    guard.click(*world(intent['position']))
                elif kind in ('move', 'attack', 'load'):
                    guard.click(*world(intent['position']), button='right')
                elif kind == 'stop':
                    click_action('stop')
                elif kind == 'unit-action':
                    click_action('unit-action:'+intent['actionName'])
                elif kind == 'submarine-depth':
                    click_action('submarine-surface' if intent['surface'] else 'submarine-dive')
                elif kind == 'amphibious-flight':
                    click_action('amphibious-fly' if intent['flying'] else 'amphibious-dive')
                elif kind == 'unload':
                    click_action('transport-unload' if intent['unloading'] else 'transport-cancel-unload')
                elif kind == 'produce':
                    click_action('produce:'+intent['typeId'])
                elif kind == 'cancelProduction':
                    click_action('cancel-production:'+intent['typeId'])
                elif kind == 'upgrade':
                    click_action('upgrade:'+intent['upgradeId'])
                elif kind == 'cancelUpgrade':
                    click_action('cancel-upgrade:'+intent['upgradeId'])
                elif kind in ('reclaim','repair'):
                    click_action('choose-'+kind)
                    guard.click(*world(intent['position']))
                elif kind == 'build':
                    click_action('choose-construction:'+intent['typeId'])
                    guard.move(*world(intent['position']))
                    guard.settle(.12)
                    guard.capture(output/(intent['intentId']+'-preview.png'))
                    guard.click(*world(intent['position']))
                else:
                    raise PlatformError('input-contract', 'Unsupported PC intention: '+kind, 1)
                action,immediate = wait_for_input(wait,reader,intent['intentId'],rule,start)
                steps = intent.get('waitSteps', 0)
                if steps:
                    if history:wait_segment(intent,action,immediate)
                    else:
                        rows = wait('bounded post-command steps', lambda rows: any(row.get('phase') == 'step' and
                                    row['sequence'] > action['sequence'] and row['sample']['stepIndex'] >= immediate['stepIndex']+steps for row in rows))
                anchors.append(dict(intentId=intent['intentId'], afterSequence=start, endSequence=reader.cursor))
                guard.capture(output/(intent['intentId']+'.png'))
            if clock_paused:set_paused(False)
            if history:save_segments()
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
            if proc is not None and proc.poll() is None:
                if api is not None and api.bound_state is not None:
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
                if proc.poll() is None:
                    # The original Popen handle still identifies this child before any HWND binds.
                    try:
                        proc.terminate()
                    except OSError as error:
                        result.setdefault('cleanupErrors', []).append(str(error))
                    result['cleanupExitCode'] = proc.wait()
            if api is not None:
                api.release_handle()
            observed_pid = W.DWORD()
            observed_thread = int(u.GetWindowThreadProcessId(previous, ctypes.byref(observed_pid))) if previous and u.IsWindow(previous) else 0
            if proc is not None and previous and previous_thread == observed_thread and previous_pid.value == observed_pid.value:
                if int(u.GetForegroundWindow() or 0) != previous:
                    u.SetForegroundWindow(previous)
                    time.sleep(.1)
                result['foregroundRestored'] = int(u.GetForegroundWindow() or 0) == previous
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
