"""Run the fixed PC package through guarded native input and current drawn targets."""
import ctypes
from ctypes import wintypes as W
import json
import os
from pathlib import Path
import subprocess
import time

from workflow_device import checked,reference,require
from diagnostic_reader import DiagnosticReader
from action_layout import wait_layout


def ui_to_client(x,y,resize):
    return (round(resize['screenX']+x*resize['screenWidth']/960),
            round(resize['height']-resize['screenY']-y*resize['screenHeight']/640))


def world_to_ui(x,y,width,height):
    scale=min(600/width,400/height)
    return (24+(600-width*scale)/2+x*scale,100+(400-height*scale)/2+(height-y)*scale)


def stable_stop(rows,unit_id,after_sequence):
    samples=[next(u for u in r['sample']['units'] if u['unitId']==unit_id) for r in rows
             if r['kind']=='observation' and r['phase']=='step' and r['sequence']>after_sequence]
    return len(samples)>=9 and all(u['moveTarget'] is None and u['speed']==0 and
        u['position']==samples[-1]['position'] and u['heading']==samples[-1]['heading'] for u in samples[-9:])


def _run_windows(plan,config,output):
    from owned_window import Guard,Win32,validate_identity
    from workflow import output_domain,resources,execute_step,save_json,tree_identity,preserve_result
    output=output_domain(output,plan['root'],plan.get('evidenceRoot'))
    require(config['released'] is True and config['owner']==plan['owner'],'Window resource not released to owner')
    java=checked(config['java']);pwsh=checked(config['powershell']);legacy=checked(config['legacyHelper'])
    require(config['java']['path']==str(Path(plan['java'])),'Launch Java differs from plan')
    package=Path(config['packageRoot']);require(package.is_absolute() and package.resolve()==package,'Non-canonical package')
    expected=json.loads(checked(config['packageManifest']).read_bytes())
    require(tree_identity(package)==expected,'Fixed PC package SHA/set mismatch')
    build=json.loads(checked(config['buildReceipt']).read_bytes())
    require(build['passed'] and build['pcPackage']==expected and build['pcPackageAfter']==expected,'Package has no successful bound build')
    result=dict(kind='test-p003-owned-windows-v1',startedUnix=time.time(),passed=False,firstError=None,postErrors=[],steps=[],
                packageBefore=expected,process=None,argv=None,closingObserved=False)
    require(os.name=='nt','Real Windows required')
    proc=api=guard=None
    size=(1200,800)
    u=ctypes.WinDLL('user32',use_last_error=True)
    u.GetForegroundWindow.restype=W.HWND
    u.GetWindowThreadProcessId.argtypes=[W.HWND,ctypes.POINTER(W.DWORD)]
    u.IsWindow.argtypes=[W.HWND];u.SetForegroundWindow.argtypes=[W.HWND]
    previous=int(u.GetForegroundWindow() or 0);previous_pid=W.DWORD()
    previous_thread=int(u.GetWindowThreadProcessId(previous,ctypes.byref(previous_pid))) if previous else 0
    reader=DiagnosticReader(output/'runtime/reforge/client-diagnostic.jsonl')
    current_generation=None
    def step(name,argv):
        r=execute_step(list(map(str,argv)),plan['root'],output/name,timeout=30);result['steps'].append(dict(name=name,**r))
        require(r['passed'],'Window command failed: '+name)
        return (output/name/'stdout.log').read_bytes()
    def logs():
        wait('current live diagnostic prefix',lambda rows:True)
        return [r for r in reader.view() if current_generation is None or
                'generation' not in r or r['generation']==current_generation]
    def wait(label,predicate,timeout=15,after_sequence=-1,after_generation=None):
        return reader.wait(label,predicate,proc,after_sequence=after_sequence,
            generation=current_generation if after_generation is None else None,
            after_generation=after_generation,timeout=timeout,check=guard.check if guard is not None else None)
    def record(row):
        with (output/'input.jsonl').open('a',encoding='utf8') as stream:
            stream.write(json.dumps(dict(unix=time.time(),**row),ensure_ascii=True)+'\n')
    def layout(enabled_action=None):return wait_layout(wait,current_generation,size,enabled_action=enabled_action)
    def click_action(action_id):
        drawn=layout('start-match') if action_id=='start-match' else layout()
        target=drawn.action(action_id)
        record(dict(kind='drawn-action-target',actionId=action_id,layout=drawn.frame,target=target))
        guard.click(*target)
    def world(x,y):return layout().world(x,y)
    with resources(['windows:interactive:rustedwarfare-reforge']):
        try:
            query="@(Get-Process -Name java -ErrorAction SilentlyContinue | Where-Object {$_.MainWindowTitle -eq 'RustedWarfare-Reforge | P002 Windows sandbox'} | Select-Object Id,MainWindowTitle)|ConvertTo-Json -Compress"
            existing=step('existing-windows',[pwsh,'-NoProfile','-Command',query]).strip()
            require(existing in (b'',b'[]'),'Existing game window belongs to another run; preserved')
            inputs=Path(plan['root'])/'TestLab/local/P002-知识沙盒输入'
            jars=sorted((package/'lib').glob('*.jar'))
            argv=[str(java),'--enable-native-access=ALL-UNNAMED','-cp',os.pathsep.join(map(str,jars)),
                'com.rustedwarfare.reforge.pc.WindowsLauncherKt','--mode','sandbox','--inputs',str(inputs),'--evidence',str(output/'runtime'),
                '--visuals','placeholder']
            result['argv']=argv;result['cwd']=plan['root'];result['packageBuild']=config['buildReceipt']
            result['childLocalAppData']=str(output/'user-data');result['visuals']='placeholder'
            result['driver']=reference(Path(__file__));result['guard']=reference(Path(__file__).with_name('owned_window.py'))
            result['reader']=reference(Path(__file__).with_name('diagnostic_reader.py'))
            result['layout']=reference(Path(__file__).with_name('action_layout.py'))
            with (output/'stdout.log').open('xb') as stdout,(output/'stderr.log').open('xb') as stderr:
                proc=subprocess.Popen(argv,cwd=plan['root'],stdin=subprocess.DEVNULL,stdout=stdout,stderr=stderr,
                    shell=False,creationflags=subprocess.CREATE_NO_WINDOW,env=dict(os.environ,LOCALAPPDATA=str(output/'user-data')))
            result['pid']=proc.pid
            api=Win32(proc,str(java),'RustedWarfare-Reforge | P002 Windows sandbox')
            end=time.monotonic()+15
            while not api.candidates():
                require(proc.poll() is None and time.monotonic()<end,'Own window did not appear')
                time.sleep(.05)
            facts=api.bind()
            result['process']=facts
            save_json(output/'binding.json',dict(process=facts,java=reference(java),legacy=reference(legacy),output=str(output)))
            api.prepare_english_once(True,record);api.place_for_input(record);api.initial_focus_once(True,record)
            guard=Guard(api,api.input_state,record);guard.resize(*size)
            wait('viewport',lambda rows:any(r['kind']=='resize' and (r['width'],r['height'])==size for r in rows))
            load_boundary=reader.cursor
            previous_generation=max((r['generation'] for r in reader.view() if r['kind']=='load-request'),default=0)
            click_action('start-match')
            request=wait('load-request',lambda rows:any(r['kind']=='load-request' for r in rows),
                         after_sequence=load_boundary,after_generation=previous_generation)
            current_generation=next(r['generation'] for r in request if r['kind']=='load-request')
            rows=wait('load-ready',lambda rows:any(r['kind']=='load-ready' for r in rows),
                      timeout=180,after_sequence=load_boundary)
            wait('first interactive frame',lambda rows:any(r['kind']=='load-first-frame-drawn' for r in rows),
                 after_sequence=load_boundary)
            initial=next(r['sample'] for r in rows if r['kind']=='observation' and r['phase']=='initial')
            unit=next(u for u in initial['units'] if u['unitId']=='u1');start=unit['position']
            guard.click(*world(start['x'],start['y']))
            wait('select',lambda rows:any(r['kind']=='action' and r['type']=='SelectAction' and r['result']['accepted'] for r in rows))
            guard.capture(output/'selected.png')
            guard.click(*world(550,110),button='right')
            move_rows=wait('move',lambda rows:any(r['kind']=='action' and r['type']=='MoveAction' and r['result']['accepted'] for r in rows))
            move_sequence=next(r['sequence'] for r in reversed(move_rows) if r['kind']=='action' and r['type']=='MoveAction' and r['result']['accepted'])
            def moved(rows):
                return any(u['unitId']=='u1' and u['position']!=start for r in rows if r['kind']=='observation' and r['sequence']>move_sequence for u in r['sample']['units'])
            wait('actual movement',moved)
            before_stop=logs()
            latest=next(next(u for u in r['sample']['units'] if u['unitId']=='u1') for r in reversed(before_stop) if r['kind']=='observation')
            require(latest['moveTarget'] is not None and latest['speed']>0,'Unit no longer moving before Stop; cannot claim a moving Stop')
            stop_boundary=before_stop[-1]['sequence']
            click_action('stop')
            stop_rows=wait('stop',lambda rows:any(r['kind']=='action' and r['type']=='StopAction' and r['result']['accepted'] and r['sequence']>stop_boundary for r in rows))
            stop_sequence=next(r['sequence'] for r in reversed(stop_rows) if r['kind']=='action' and r['type']=='StopAction' and r['result']['accepted'])
            def stopped(rows):
                return stable_stop(rows,'u1',stop_sequence)
            wait('stable Stop tail',stopped)
            guard.capture(output/'stopped.png')
            guard.close_owned();result['exitCode']=proc.wait(timeout=20)
            result['closingObserved']=result['exitCode']==0
            result['stream']=reader.finish(result['exitCode'])
            result['diagnostic']=reference(output/'runtime/reforge/client-diagnostic.jsonl')
            result['recordCount']=reader.record_count;result['checks']=['real-content-load','select','MoveAction','actual-displacement','StopAction','9-step-stable-tail','native-screenshots','normal-close']
        except Exception as error:
            result['firstError']=dict(type=type(error).__name__,message=str(error))
            if getattr(error,'record',None) is not None:result['firstError']['diagnosticRecord']=error.record
            if getattr(error,'secondary',None):result['firstError']['secondaryDiagnostics']=error.secondary
        finally:
            if proc is not None and proc.poll() is None and api is not None and api.bound_state is not None:
                try:
                    if guard is not None:guard.release_owned();guard.close_owned()
                    else:
                        validate_identity(api.bound_state,api.snapshot(),foreground=False);api.close_message()
                    result['closingObserved']=proc.wait(timeout=20)==0
                except Exception as error:result['postErrors'].append(dict(message='Owned window closing failed: '+str(error)))
            if proc is not None and proc.poll() is None:
                result['postErrors'].append(dict(message='Own process remains; preserved without killing an unbound window',pid=proc.pid))
            if api is not None:api.release_handle()
            observed_pid=W.DWORD()
            observed_thread=int(u.GetWindowThreadProcessId(previous,ctypes.byref(observed_pid))) if previous and u.IsWindow(previous) else 0
            if previous and observed_thread==previous_thread and observed_pid.value==previous_pid.value:
                restored=bool(u.SetForegroundWindow(previous));time.sleep(.1)
                result['foregroundRestored']=restored and int(u.GetForegroundWindow() or 0)==previous
                if not result['foregroundRestored']:result['postErrors'].append(dict(message='Previous foreground restoration failed'))
            else:result['foregroundRestored']=None
            record(dict(kind='foreground-restoration',restored=result['foregroundRestored']))
            try:
                result['packageAfter']=tree_identity(package)
                if result['packageAfter']!=expected:result['postErrors'].append(dict(message='Fixed PC package changed'))
            except OSError as error:result['postErrors'].append(dict(message='Fixed package readback failed: '+str(error)))
            result['passed']=result['firstError'] is None and not result['postErrors'] and result['closingObserved']
            result['endedUnix']=time.time();preserve_result(output/'result.json',result)
    return result


def run_windows(plan,config,output):
    from workflow import output_domain,preserve_result
    output=output_domain(output,plan['root'],plan.get('evidenceRoot'))
    require(config['released'] is True and config['owner']==plan['owner'],'Window resource not released to owner')
    output.mkdir()  # Only this exclusive creator may preserve into the batch.
    started=time.time()
    try:return _run_windows(plan,config,output)
    except Exception as error:
        result=dict(kind='test-p003-owned-windows-v1',passed=False,startedUnix=started,endedUnix=time.time(),
            firstError=dict(kind='precondition-or-resource',type=type(error).__name__,message=str(error)),postErrors=[],
            productStarted=False,steps=[],process=None,closingObserved=False)
        return preserve_result(output/'result.json',result)
