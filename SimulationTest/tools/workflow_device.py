"""Thin adapters for the accepted MuMu OwnRuns and ART/UI instrumentation.

No installation or restart. Fresh manager/ADB identity is rechecked every call.
Only the fixed existing instrumentation entries can run; there is no shell plan.
"""
from pathlib import Path
import importlib.util
import json
import re
import shlex
import stat
import struct
import sys
import uuid

from workflow_inputs import sha

MAIN='com.rustedwarfare.reforge.android'
TEST=MAIN+'.test'
READER='com.rustedwarfare.reforge.android.ownruns.OwnRunsProtectionInstrumentation'


def require(condition,message):
    if not condition:raise ValueError(message)


def reference(path):
    path=Path(path).resolve()
    return dict(path=str(path),sha256=sha(path),bytes=path.stat().st_size)


def checked(ref):
    path=Path(ref['path'])
    require(path.is_absolute() and '..' not in path.parts and path.resolve()==path,'Non-canonical fixed reference')
    require(reference(path)==ref,'Fixed input changed: '+str(path))
    return path


def load_module(ref,name):
    path=checked(ref)
    spec=importlib.util.spec_from_file_location(name,path)
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
    return module


def server_owners(value):
    rows=[]
    for listener in value['listeners']:
        found=[p for p in value['adb'] if p['Id']==listener['OwningProcess']]
        require(len(found)==1 and found[0].get('Path'),'Listener has no unique executable owner')
        rows.append(dict(listener=listener,process=found[0]))
    return sorted(rows,key=lambda r:r['listener']['LocalPort'])


def baseline(ref,receiver):
    value=json.loads(checked(ref).read_bytes())
    if value.get('kind')=='d099-ui-log-backup-v2':
        require(value['status']=='UI_BACKUP_COMPLETE_AWAITING_REVIEW' and value['oldRunsProtected'] and value['inputsStable'] and not value['postErrors'],'Legacy backup is not complete')
        binding={k:v for k,v in value['backupRequest'].items() if k!='manifestSha256'}
        binding.update(mode='inventory',manifest='-')
        return dict(binding=binding,inventory=value['inventoryAfter'],preservedLeaves=value['preservedLeaves'],
                    expectedPackages=value['expectedPackages'],protection=value['protectionAfter'],profile=value['profileAfter'])
    require(value.get('kind')=='test-p003-mumu-chain-v1' and value.get('dataProtected') is True and not value['postErrors'], 'Previous adapter has no valid complete data baseline')
    return value['nextBaseline']


def _run_device(plan,config,output,target):
    # Imported lazily to keep the shared process boundary in one place.
    from workflow import execute_step,resources,save_json,canonical,output_domain,preserve_result
    root=canonical(plan['root']);output=output_domain(output,root,plan.get('evidenceRoot'))
    require(target in ('core-mumu-art','core-mumu-ui','original-mumu','device-inspect'),'Unimplemented device target')
    require(config['released'] is True and config['owner']==plan['owner'],'Device not explicitly released to this owner')
    require(config['index']==1 and config['creation']==1792546680141873,'Only the approved MuMu instance is supported')
    output.mkdir()
    result=dict(kind='test-p003-mumu-chain-v1',target=target,status='preflight',passed=False,
        dataProtected=False,steps=[],firstError=None,postErrors=[],startedUnix=__import__('time').time(),
        productStarted=False,installationAttempted=False,newRunNames=[])
    references=[config[k] for k in ('adb','manager','powershell','receiver','pm','baseline','hostEpoch')]
    device=None;info=None;server_before=None;before=None;after=None;runtime_released=False
    def run(name,argv,allowed=(0,),timeout=45):
        r=execute_step(list(map(str,argv)),root,output/name,timeout=timeout,expected=allowed[0])
        r['allowedExitCodes']=list(allowed)
        result['steps'].append(dict(name=name,**r))
        require(r['exitCode'] in allowed and not r['preservationErrors'] and not r.get('timedOut'),'Command failed: '+name)
        return (output/name/'stdout.log').read_bytes()
    def shell(name,tokens,allowed=(0,),timeout=45):
        return run(name,device+['shell',shlex.join(tokens)],allowed,timeout)
    def resources_now(label):
        script="$p=@(Get-Process -Name adb -ErrorAction SilentlyContinue|Select-Object Id,Path);$l=@(Get-NetTCPConnection -LocalPort 15037,5037 -State Listen -ErrorAction SilentlyContinue|Select-Object LocalAddress,LocalPort,OwningProcess);[pscustomobject]@{adb=$p;listeners=$l}|ConvertTo-Json -Depth 5 -Compress"
        servers=json.loads(run(label+'-servers',[pwsh,'-NoProfile','-Command',script]))
        manager=json.loads(run(label+'-manager',[mm,'info','-v',str(config['index'])]))
        keys=('index','created_timestamp','pid','adb_host_ip','adb_port','is_process_started','is_android_started','player_state','error_code','launch_err_code')
        require(manager['index']=='1' and manager['created_timestamp']==config['creation'] and manager['is_android_started'] and manager['is_process_started'],'Approved instance not running')
        require(manager['player_state']=='start_finished' and manager['error_code']==manager['launch_err_code']==0,'Instance not ready')
        require(all(manager[k]==epoch['manager-final'][k] for k in keys),'Host epoch changed; rebind required')
        require(server_owners(servers)==server_owners(epoch['servers-final']),'Host ADB owner changed; rebind required')
        return manager,servers
    def packages(label):
        facts={}
        for i,(package,wanted) in enumerate(base['expectedPackages'].items()):
            prefix=label+'-'+str(i)
            require(shell(prefix+'-pid',['pidof',package],(1,))==b'','Package already active: '+package)
            path=shell(prefix+'-path',['pm','path','--user','0',package]).decode('ascii').strip()
            require(path=='package:'+wanted['basePath'],'Installed path differs: '+package)
            digest=shell(prefix+'-sha',['sha256sum',wanted['basePath']]).decode('ascii').split()
            require(digest==[wanted['apk']['sha256'],wanted['basePath']],'Installed package SHA differs: '+package)
            protection=pm.protection(shell(prefix+'-pm',['dumpsys','package',package]),package,wanted['uid'])
            require(protection==base['protection'][package],'Package permission/protection differs: '+package)
            facts[package]=dict(path=path,sha256=digest[0],protection=protection)
        return facts
    def instrument(label,binding,entry,timeout=45):
        for ref in references:checked(ref)
        tokens=['am','instrument','--user','0','-w','-r']
        for k,v in binding.items():tokens+=['-e',k,v]
        tokens+=[TEST+'/'+entry]
        return shell(label,tokens,timeout=timeout)
    def profile(label):
        def node(suffix,path,directory=True):
            fields=shell(label+suffix,['run-as',MAIN,'/system/bin/toybox','stat','-c','%f|%s|%d|%i|%Y|%Z|%u|%g|%n',path]).decode('ascii').strip().split('|')
            require(len(fields)==9 and fields[-1]==path,'Profile stat framing differs')
            value=dict(mode=int(fields[0],16),values=[int(v) for v in fields[1:-1]],path=path)
            require(value['values'][5:7]==[10051,10051] and (stat.S_ISDIR(value['mode']) if directory else stat.S_ISREG(value['mode'])),'Profile ownership/type differs')
            return value
        data='/data/user/0/'+MAIN
        parents=[node('-root',data),node('-files',data+'/files')];leaves=[]
        for i,name in enumerate(('profileInstalled','profileinstaller_profileWrittenFor_lastUpdateTime.dat')):
            path=data+'/files/'+name;suffix='-'+str(i)
            names=shell(label+suffix+'-name',['run-as',MAIN,'/system/bin/toybox','find',data+'/files','-mindepth','1','-maxdepth','1','-name',name,'-print0'])
            require(names in (b'',path.encode('ascii')+b'\0'),'Unknown profile name result')
            leaf=dict(path=path,present=bool(names));leaves.append(leaf)
            if not names:continue
            before=node(suffix+'-stat',path,False)
            require(before['values'][0]==(24 if i==0 else 8),'Profile size differs')
            raw=run(label+suffix+'-original',device+['exec-out','run-as',MAIN,'/system/bin/toybox','cat',path])
            import hashlib
            require(len(raw)==before['values'][0],'Profile read length differs')
            digest=hashlib.sha256(raw).hexdigest()
            read=shell(label+suffix+'-sha',['run-as',MAIN,'/system/bin/toybox','sha256sum',path]).decode('ascii').split()
            require(read==[digest,path] and node(suffix+'-after',path,False)==before,'Profile changed during read')
            if i==0:
                version,code,updated,size=struct.unpack('>iiqq',raw)
                require(version==1 and updated>=0 and size>=0,'Profile schema differs')
                leaf['originalHex']=raw.hex()
            leaf.update(attrs=before,sha256=digest)
        require([node('-root-after',data),node('-files-after',data+'/files')]==parents,'Profile parent changed during read')
        return dict(parents=parents,leaves=leaves,privateTreeVerified=False)
    def inventory(label):
        binding=dict(base['binding'],schema='e9-ownruns-request-v1',mode='inventory',manifest='-',bindingId='p003-inv-'+uuid.uuid4().hex)
        host.request(binding)
        raw=instrument(label,binding,READER)
        host.receive(raw,binding,output/(label+'-decoded'))
        ref=reference(output/(label+'-decoded')/'summary.json')
        summary=json.loads(checked(ref).read_bytes())
        return host.manifest(summary['manifest']),ref,summary['manifest']
    with resources(['mumu:1:1792546680141873','adb-server:15037']):
        try:
            for ref in references:checked(ref)
            adb=checked(config['adb']);mm=checked(config['manager']);pwsh=checked(config['powershell'])
            require(sha(adb)=='9c3a7b68458565d669926134e9cc257cbdd01ffd89939938f578e87f57b0737c','Unqualified ADB')
            require(sha(mm)=='cae3455106d50e576bebea1c59cd482ff41e9e2b02ffbadfd0b098638891f1c4','Unqualified manager')
            require(config['receiver']['sha256']=='e6e360dec669f8e19c0048cd638af35e4bf8429da0dcdbf4f7ef4905081e8fb8','Unqualified OwnRuns receiver')
            require(config['pm']['sha256']=='5846e2c8e94508473813418e23d7e90b0fc18ed62da0112deb4f912f303b83ca','Unqualified permission decoder')
            epoch=json.loads(checked(config['hostEpoch']).read_bytes())
            require(epoch['status']=='NEW_EPOCH_HOST_READY_NO_APP_ADMISSION' and not epoch['postErrors'],'Invalid host epoch')
            host=load_module(config['receiver'],'workflow_ownruns');pm=load_module(config['pm'],'workflow_pm')
            base=baseline(config['baseline'],host)
            for ref in base['preservedLeaves']:checked(ref)
            prior=host.manifest(json.loads(checked(base['inventory']).read_bytes())['manifest'])
            info,server_before=resources_now('before')
            device=[str(adb),'-H','localhost','-P','15037','-s',info['adb_host_ip']+':'+str(info['adb_port'])]
            run('connect',[adb,'-H','localhost','-P','15037','connect',info['adb_host_ip']+':'+str(info['adb_port'])])
            require(shell('shell-id',['id']).startswith(b'uid=2000(shell)'),'Unexpected shell identity')
            require(shell('user',['am','get-current-user']).strip()==b'0','Unexpected device user')
            result['packagesBefore']=packages('package-before')
            result['profileBefore']=profile('profile-before')
            require(result['profileBefore']==base['profile'],'Profile baseline differs; preserve for explicit qualification')
            before,ref,_=inventory('inventory-before');result['inventoryBefore']=ref
            require(before==prior,'Existing run set/content/metadata differs; preserve and requalify')
            result['baselineAdmitted']=True
            if target=='device-inspect':
                runtime_released=True
            elif target=='core-mumu-art':
                result['productStarted']=True
                raw=instrument('art',{},'com.rustedwarfare.reforge.android.MotionMechanismInstrumentation',60)
                text=raw.decode('utf8');matches=re.findall(r'^INSTRUMENTATION_RESULT: motionResult=(.*)\r?$',text,re.M)
                codes=re.findall(r'^INSTRUMENTATION_CODE: (-?[0-9]+)\s*$',text,re.M)
                require(len(matches)==1 and codes==['-1'] and 'INSTRUMENTATION_FAILED' not in text,'Incomplete ART result')
                motion=json.loads(matches[0]);save_json(output/'art-result.json',motion)
                # This instrumentation does not open an Activity or create run files.
                runtime_released=True
                require(motion['kind']=='eng017-art-motion-development-v1' and motion['success'] and not motion['formalCompatibility'] and not motion['realPhone'],'ART behavior failed')
                require(len(motion['cases'])==12 and len({r['case'] for r in motion['cases']})==12 and all(r['success'] for r in motion['cases']),'ART case coverage differs')
                identity=motion['identity'];require(identity['uid']==10051 and identity['sdk']==35,'ART platform identity differs')
                for role,pkg in [('main',MAIN),('test',TEST)]:
                    wanted=base['expectedPackages'][pkg]
                    require(identity[role]['sha256']==wanted['apk']['sha256'] and identity[role]['path']==wanted['basePath'] and identity[role]['bytes']==wanted['apk']['bytes'],'ART actual code identity differs')
                names={r['name'] for r in identity['classes']}
                expected={'com.rustedwarfare.reforge.core.sim.'+n for n in ('SimulationContext','MotionStepSample','Position')}|{'com.rustedwarfare.reforge.core.content.MovementProfile'}|{'com.rustedwarfare.reforge.core.path.'+n for n in ('PathPlanner','PlanningTerrain')}
                require(len(identity['classes'])==6 and names==expected,'ART loaded class coverage differs')
                captured=next(r for r in motion['cases'] if r['case']=='captured-knowledge-reachability')
                require(len(captured['checks'])==7 and all(r['success'] and r['allLegsDirect'] for r in captured['checks']),'ART planner checks differ')
            elif target=='original-mumu':
                original=config['original']
                for key in ('collector','template','pairHelper','validator'):
                    checked(original[key]);references.append(original[key])
                require(original['collector']['sha256']=='a43fc17b3a188ccf117b6bd7860dedf1e715e2c947989dd3f5f65cdbc25b0be6','Unqualified original collector')
                require(original['validator']['sha256']=='487bdfe5f6ae0f2784f69bf4530779c75dcf7b485a6f535a24f826f04fc97faa','Unqualified raw normalizer')
                require(original['pairHelper']['sha256']=='c67d5a1f0a783816f672caa78c2e68c0d94a61f0f0044b5911b605d0524a7d13','Unqualified installed-pair helper')
                template=json.loads(checked(original['template']).read_bytes())
                require(template['purpose']=='minimal-technical-diagnostics' and template['formalCompatibility'] is False and [r['caseId'] for r in template['runs']]==['OM08'],'Only the existing OM08 diagnostic is supported')
                for ref in template['fixedInputs']:checked(ref)
                collector_root=output/'original-capture';collector_root.mkdir()
                helper=collector_root/'installed_pair_shape_v1.py'
                with helper.open('xb') as stream:stream.write(checked(original['pairHelper']).read_bytes())
                plan_value=json.loads(json.dumps(template));tag=str(uuid.uuid4())
                plan_value['runs'][0]['request'].update(runId=tag,requestId='p003_'+tag.replace('-',''))
                plan_value['fixedInputs']=[reference(helper) if r['path']==original['pairHelper']['path'] else r for r in plan_value['fixedInputs']]
                require(reference(helper) in plan_value['fixedInputs'],'Installed-pair helper not in explicit closure')
                save_json(collector_root/'capture-plan.json',plan_value)
                result['originalPlan']=reference(collector_root/'capture-plan.json')
                script="import importlib.util,pathlib,sys;s=importlib.util.spec_from_file_location('approved_original',sys.argv[1]);m=importlib.util.module_from_spec(s);s.loader.exec_module(m);m.HERE=pathlib.Path(sys.argv[2]);sys.argv=[sys.argv[1],'--plan',sys.argv[3],'--plan-sha256',sys.argv[4],'--index','0'];m.main()"
                result['productStarted']=True
                run('original-collector',[sys.executable,'-B','-c',script,str(checked(original['collector'])),str(collector_root),
                    result['originalPlan']['path'],result['originalPlan']['sha256']],timeout=180)
                capture=collector_root/('scene-run-'+tag)/'capture.json'
                captured=json.loads(capture.read_bytes());result['originalCapture']=reference(capture)
                require(captured['status']=='RAW_CAPTURED_AWAITING_VALIDATION' and captured['primaryError'] is None and not captured['postErrors'] and captured['inputsStable'],'Original collector did not close cleanly')
                runtime_released=True
                script="import importlib.util,pathlib,json,sys;s=importlib.util.spec_from_file_location('approved_normalizer',sys.argv[1]);m=importlib.util.module_from_spec(s);s.loader.exec_module(m);v=m.validate(pathlib.Path(sys.argv[2]));p=pathlib.Path(sys.argv[3]);f=p.open('x',encoding='utf8');json.dump(v,f,ensure_ascii=False);f.close()"
                run('original-normalizer',[sys.executable,'-B','-c',script,str(checked(original['validator'])),str(capture),str(output/'original-evidence.json')])
                normalized=json.loads((output/'original-evidence.json').read_bytes())
                require(normalized['caseId']=='OM08' and normalized['complete'] and normalized['generalDiagnosticOnly'] and normalized.get('originalCompatibility') is False,'Original raw integrity/coverage differs')
                result['originalEvidence']=reference(output/'original-evidence.json')
                result['compatibilityVerdict']='not-evaluated; diagnostic source only; complete O.v2 remains deferred'
            else:
                for key,digest in [('functionalDecoder','525b2331495ff170750e8fba36f8f445299805ea1535d75b81413d9146403383'),
                                   ('imageDecoder','6421faede0b570bcd61d829f4085c8c530708f43d31b6ebff04b12b64e89cfd2')]:
                    require(config[key]['sha256']==digest,'Unqualified UI decoder');references.append(config[key])
                decoder=load_module(config['functionalDecoder'],'workflow_functional');images=load_module(config['imageDecoder'],'workflow_images')
                result['productStarted']=True
                raw=instrument('ui',dict(mode='MEMORY',shared='false',exercise='visibility'),
                    'com.rustedwarfare.reforge.android.SandboxFunctionalInstrumentation',90)
                values,codes=decoder.decode_result(raw,images=True);save_json(output/'ui-result.json',dict(values=values,codes=codes))
                runtime_released=values.get('normalReleaseObserved')=='true' and values.get('imageCallbackUnresolved')=='false' and values.get('recoveryReferenceUnresolved')=='false'
                require(re.fullmatch(r'run-[0-9]+-[0-9a-f]{8}',values.get('runName','')),'UI has no canonical run name')
                result['newRunNames']=[values['runName']]
                require(runtime_released,'UI closing fence unresolved; do not admit data reader')
                require(codes==['-1'] and values['schema']=='e9-sandbox-functional-result-v1' and values['primaryError']=='null' and json.loads(values['secondaryErrors'])==[] and values['result'].startswith('PASS:'),'UI behavior failed')
                require(values['mode']=='MEMORY' and values['shared']=='false' and values['exercise']=='visibility' and values['applicationStartAttempts']=='1','UI declared coverage differs')
                actions=json.loads(values['actions']);require(any(r.get('kind')=='action-confirmed' and r.get('type')=='MoveAction' for r in actions),'Move never consumed')
                pngs,image_ids=images.decode(raw,values['runName']);require(set(pngs)=={'visible','hidden'},'UI image coverage incomplete')
                (output/'images').mkdir()
                for name,data in pngs.items():
                    with (output/'images'/ (name+'.png')).open('xb') as stream:stream.write(data)
                save_json(output/'images/identity.json',image_ids)
            result['status']='behavior-complete'
        except Exception as error:
            result['firstError']=dict(kind='device-precondition-or-behavior',type=type(error).__name__,message=str(error))
        finally:
            post_gate=False
            if device is not None:
                try:
                    resources_now('always-post')
                    result['packagesAfter']=packages('always-package-after')
                    post_gate=True
                except Exception as error:
                    result['postErrors'].append(dict(stage='single-ordered-resource-package-postcheck',message=str(error)))
                    result['postcheckSkips']=['No stale endpoint package query after resource failure; no reader/backup admission after any post-gate failure']
            # Data readers are admitted only after an established baseline and a
            # confirmed business closing fence. Package/resource guards precede them.
            if result.get('baselineAdmitted') and runtime_released and post_gate:
                try:
                    result['profileAfterRuntime']=profile('profile-post-runtime')
                    result['profileChangedByInstrumentation']=result['profileAfterRuntime']!=result['profileBefore']
                    after,ref,text=inventory('inventory-after');result['inventoryAfter']=ref
                    require(all(after.get(n)==row for n,row in before.items()),'Old record changed after runtime')
                    require(set(after)==set(before)|set(result['newRunNames']),'Unexpected new/missing run')
                    leaves=list(base['preservedLeaves'])
                    if result['newRunNames']:
                        new=set(result['newRunNames'])
                        accepted=text.splitlines()[0]+'\n'+''.join((line if len(line.split('\t'))==2 else line.rsplit('\t',1)[0]+'\t'+('1' if line.split('\t')[0] in new else '0'))+'\n' for line in text.splitlines()[1:])
                        import hashlib
                        binding=dict(base['binding'],schema='e9-ownruns-request-v1',mode='backup',bindingId='p003-backup-'+uuid.uuid4().hex,
                            manifest=accepted,manifestSha256=hashlib.sha256(accepted.encode('ascii')).hexdigest())
                        host.request(binding);raw=instrument('backup',binding,READER)
                        receipt=host.receive(raw,binding,output/'backup-decoded')
                        require(receipt['backupTransferComplete'] and {r['run'] for r in receipt['copied']}==new,'Backup incomplete')
                        for name in sorted(new):
                            leaf=reference(output/'backup-decoded'/name/'client-diagnostic.jsonl')
                            require(leaf['sha256']==after[name]['sha256'] and leaf['bytes']==after[name]['fields'][5],'Copied bytes differ')
                            leaves.append(leaf)
                    final,ref,_=inventory('inventory-final');require(final==after,'Inventory changed during backup')
                    final_profile=profile('profile-final')
                    require(final_profile==result['profileAfterRuntime'],'Profile changed during inventory/backup')
                    for leaf in leaves:checked(leaf)
                    resources_now('final');packages('package-final')
                    result['nextBaseline']=dict(base,inventory=ref,preservedLeaves=leaves,profile=final_profile)
                    result['dataProtected']=True
                except Exception as error:
                    result['postErrors'].append(dict(type=type(error).__name__,message=str(error)))
            elif result.get('baselineAdmitted'):
                result['postErrors'].append(dict(message='Business closing fence or resource/package post-gate unresolved; data reader not admitted'))
            for ref in references:
                try:checked(ref)
                except Exception as error:result['postErrors'].append(dict(message=str(error)))
            result['passed']=result['firstError'] is None and not result['postErrors'] and result['dataProtected']
            result['status']='passed' if result['passed'] else 'failed-preserved'
            result['endedUnix']=__import__('time').time()
            preserve_result(output/'result.json',result)
    return result


def run_device(plan,config,output,target):
    from workflow import preserve_result
    try:
        return _run_device(plan,config,output,target)
    except ValueError as error:
        # Resource refusal occurs after exclusively claiming the batch, before
        # any device command. Preserve this refusal as a completed attempt.
        if not str(error).startswith('Resource occupied:'):
            raise
        result=dict(kind='test-p003-mumu-chain-v1',target=target,passed=False,status='blocked',
            firstError=dict(kind='resource-occupied',message=str(error)),postErrors=[],steps=[],
            productStarted=False,dataProtected=False,installationAttempted=False,endedUnix=__import__('time').time())
        return preserve_result(Path(output)/'result.json',result)
