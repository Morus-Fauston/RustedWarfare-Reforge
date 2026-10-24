"""Run the current fixed PC package through the existing owned-window helper."""
import json
from pathlib import Path
import subprocess
import time
import xml.etree.ElementTree as ET

from workflow_device import checked,reference,require


def ui_to_client(x,y,resize):
    return (round(resize['screenX']+x*resize['screenWidth']/960),
            round(resize['height']-resize['screenY']-y*resize['screenHeight']/640))


def world_to_ui(x,y,width,height):
    scale=min(600/width,400/height)
    return (24+(600-width*scale)/2+x*scale,100+(400-height*scale)/2+(height-y)*scale)


def _run_windows(plan,config,output):
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
    proc=None;binding=None
    def step(name,argv):
        r=execute_step(list(map(str,argv)),plan['root'],output/name,timeout=30);result['steps'].append(dict(name=name,**r))
        require(r['passed'],'Window command failed: '+name)
        return (output/name/'stdout.log').read_bytes()
    def logs():
        path=output/'runtime/reforge/client-diagnostic.jsonl'
        if not path.is_file():return []
        raw=path.read_bytes();lines=raw.split(b'\n')
        return [json.loads(line) for line in lines[:-1] if line]
    def wait(label,predicate,timeout=15):
        end=time.monotonic()+timeout
        while time.monotonic()<end:
            require(proc.poll() is None,'Own window exited while waiting for '+label)
            rows=logs()
            if predicate(rows):return rows
            time.sleep(.1)
        raise ValueError('Window event deadline: '+label)
    action_number=0
    def action(kind,*args):
        nonlocal action_number
        action_number+=1
        argv=[pwsh,'-NoProfile','-ExecutionPolicy','Bypass','-File',str(Path(__file__).with_suffix('.ps1')),
              '-Binding',str(output/'binding.json'),'-Action',kind]
        if kind=='click':argv+=['-X',str(args[0]),'-Y',str(args[1]),'-Button',args[2]]
        if kind=='capture':argv+=['-Label',args[0]]
        return json.loads(step('action-'+str(action_number)+'-'+kind,argv))
    with resources(['windows:interactive:rustedwarfare-reforge']):
        try:
            query="@(Get-Process -Name java -ErrorAction SilentlyContinue | Where-Object {$_.MainWindowTitle -eq 'RustedWarfare-Reforge | P002 Windows sandbox'} | Select-Object Id,MainWindowTitle)|ConvertTo-Json -Compress"
            existing=step('existing-windows',[pwsh,'-NoProfile','-Command',query]).strip()
            require(existing in (b'',b'[]'),'Existing game window belongs to another run; preserved')
            inputs=Path(plan['root'])/'TestLab/local/P002-知识沙盒输入'
            jars=sorted((package/'lib').glob('*.jar'))
            import os
            argv=[str(java),'--enable-native-access=ALL-UNNAMED','-cp',os.pathsep.join(map(str,jars)),
                'com.rustedwarfare.reforge.pc.WindowsLauncherKt','--mode','sandbox','--inputs',str(inputs),'--evidence',str(output/'runtime')]
            result['argv']=argv;result['cwd']=plan['root'];result['packageBuild']=config['buildReceipt']
            with (output/'stdout.log').open('xb') as stdout,(output/'stderr.log').open('xb') as stderr:
                proc=subprocess.Popen(argv,cwd=plan['root'],stdin=subprocess.DEVNULL,stdout=stdout,stderr=stderr,
                    shell=False,creationflags=subprocess.CREATE_NO_WINDOW)
            result['pid']=proc.pid
            rows=wait('resize',lambda rows:any(r['kind']=='resize' for r in rows))
            q='$p=Get-Process -Id '+str(proc.pid)+';[ordered]@{pid=$p.Id;startUtcTicks=$p.StartTime.ToUniversalTime().Ticks;title=$p.MainWindowTitle;path=$p.Path}|ConvertTo-Json -Compress'
            facts=json.loads(step('own-process',[pwsh,'-NoProfile','-Command',q]))
            require(facts['pid']==proc.pid and facts['title']=='RustedWarfare-Reforge | P002 Windows sandbox','Own title not ready')
            result['process']=facts
            binding=dict(process=facts,java=reference(java),legacy=reference(legacy),output=str(output))
            save_json(output/'binding.json',binding)
            def click_ui(x,y,button='left'):
                resize=next(r for r in reversed(logs()) if r['kind']=='resize')
                return action('click',*ui_to_client(x,y,resize),button)
            click_ui(790,80)
            rows=wait('load-ready',lambda rows:any(r['kind']=='load-ready' for r in rows))
            require(not any(r['kind']=='load-error' for r in rows),'Actual content load failed')
            map_xml=ET.parse(inputs/'maps/p001-controlled.tmx').getroot()
            width=int(map_xml.get('width'))*int(map_xml.get('tilewidth'));height=int(map_xml.get('height'))*int(map_xml.get('tileheight'))
            initial=next(r['sample'] for r in rows if r['kind']=='observation' and r['phase']=='initial')
            unit=next(u for u in initial['units'] if u['unitId']=='u1');start=unit['position']
            click_ui(*world_to_ui(start['x'],start['y'],width,height))
            wait('select',lambda rows:any(r['kind']=='action' and r['type']=='SelectAction' and r['result']['accepted'] for r in rows))
            action('capture','selected')
            click_ui(*world_to_ui(550,110,width,height),button='right')
            move_rows=wait('move',lambda rows:any(r['kind']=='action' and r['type']=='MoveAction' and r['result']['accepted'] for r in rows))
            move_sequence=next(r['sequence'] for r in reversed(move_rows) if r['kind']=='action' and r['type']=='MoveAction' and r['result']['accepted'])
            def moved(rows):
                return any(u['unitId']=='u1' and u['position']!=start for r in rows if r['kind']=='observation' and r['sequence']>move_sequence for u in r['sample']['units'])
            wait('actual movement',moved)
            before_stop=logs()
            latest=next(next(u for u in r['sample']['units'] if u['unitId']=='u1') for r in reversed(before_stop) if r['kind']=='observation')
            require(latest['moveTarget'] is not None and latest['speed']>0,'Unit no longer moving before Stop; cannot claim a moving Stop')
            stop_boundary=before_stop[-1]['sequence']
            click_ui(715,312)
            stop_rows=wait('stop',lambda rows:any(r['kind']=='action' and r['type']=='StopAction' and r['result']['accepted'] and r['sequence']>stop_boundary for r in rows))
            stop_sequence=next(r['sequence'] for r in reversed(stop_rows) if r['kind']=='action' and r['type']=='StopAction' and r['result']['accepted'])
            def stopped(rows):
                samples=[next(u for u in r['sample']['units'] if u['unitId']=='u1') for r in rows if r['kind']=='observation' and r['phase']=='step' and r['sequence']>stop_sequence]
                return len(samples)>=9 and all(u['moveTarget'] is None and u['speed']==0 and u['angularVelocity']==0 and u['position']==samples[-1]['position'] for u in samples[-9:])
            wait('stable Stop tail',stopped)
            action('capture','stopped')
            action('close');result['exitCode']=proc.wait(timeout=20)
            result['closingObserved']=result['exitCode']==0
            rows=logs();require(result['closingObserved'] and any(r['kind']=='application-disposed' for r in rows),'Normal closing tail missing')
            require([r['sequence'] for r in rows]==list(range(len(rows))),'Window diagnostic sequence incomplete')
            result['diagnostic']=reference(output/'runtime/reforge/client-diagnostic.jsonl')
            result['recordCount']=len(rows);result['checks']=['real-content-load','select','MoveAction','actual-displacement','StopAction','9-step-stable-tail','native-screenshots','normal-close']
        except Exception as error:
            result['firstError']=dict(type=type(error).__name__,message=str(error))
        finally:
            if proc is not None and proc.poll() is None and binding is not None:
                try:action('close');result['closingObserved']=proc.wait(timeout=20)==0
                except Exception as error:result['postErrors'].append(dict(message='Owned window closing failed: '+str(error)))
            if proc is not None and proc.poll() is None:
                result['postErrors'].append(dict(message='Own process remains; preserved without killing an unbound window',pid=proc.pid))
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
