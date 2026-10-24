"""Real JAR controls: independent reads use two workers, then shared drift runs serially.
Derived from frozen own test_original_preparation_v1.py; historical bytes stay untouched.
All six controls, 60-second commands and the caller's 210-second limit remain.
"""
from pathlib import Path
import json,sys,tempfile,uuid,traceback,subprocess,os,threading
from concurrent.futures import ThreadPoolExecutor
E=Path(sys.argv[2]).absolute()
sys.path.insert(0,str(E))
import source_launch_reserve_v1 as host
import original_host_io_v1 as io
def save(path,value):
 raw=value if type(value)is bytes else json.dumps(value,ensure_ascii=False,allow_nan=False,indent=2).encode('utf8')
 with path.open('xb')as stream:stream.write(raw)
 return io.reference(path)
def run_control(label,argv,base,commands,command_lock):
 row=dict(label=label,argv=argv,timeoutSeconds=60,exitCode=None,timedOut=False,firstError=None,preservationErrors=[])
 with command_lock:commands.append(row)
 stdout=stderr=b'';child=None
 def failure(phase,error):
  detail=dict(phase=phase,type=type(error).__name__,message=str(error))
  if row['firstError']is None:row['firstError']=detail
  else:row['preservationErrors'].append(detail)
 try:
  save(base/(label+'-start.json'),row)
  child=subprocess.Popen(argv,stdin=subprocess.DEVNULL,stdout=subprocess.PIPE,stderr=subprocess.PIPE,shell=False)
  row['pid']=child.pid
  stdout,stderr=child.communicate(timeout=60)
 except subprocess.TimeoutExpired as first:
  row['timedOut']=True;failure('process-timeout',first);stdout=first.output or b'';stderr=first.stderr or b''
 except Exception as error:failure('process-start-or-communicate',error)
 finally:
  if child is not None:
   if child.poll()is None:
    try:child.kill()
    except Exception as error:row['cleanupError']=str(error);failure('kill',error)
    try:stdout,stderr=child.communicate(timeout=5)
    except Exception as error:
     row['cleanupError']=str(error);failure('cleanup-communicate',error)
     if isinstance(error,subprocess.TimeoutExpired):stdout=error.output or stdout;stderr=error.stderr or stderr
   row['exitCode']=child.poll()
  for name,raw in (('stdout',stdout),('stderr',stderr)):
   try:row[name]=save(base/(label+'.'+name),raw)
   except Exception as error:failure('preserve-'+name,error)
 return row

def require_finished(row):
 io.require(not row['timedOut']and not row.get('cleanupError')and row['exitCode']is not None
  and row['firstError']is None and not row['preservationErrors'],'control did not complete cleanly: '+row['label'])

def main():
 config_ref=io.reference(Path(sys.argv[1]));config=io.document(io.checked(config_ref));out=Path(sys.argv[3]);io.require(out.is_absolute()and out==out.resolve(),'new canonical absolute output required');out.mkdir()
 result=dict(kind='synthetic-original-preparation-controls',formalCompatibility=False,controlWorkers=2,timeoutSeconds=60,tests=[],commands=[],error=None,archiveErrors=[],postErrors=[],sourceCopies=[],preservedTempFiles=[],driftRuntimeBefore=[],driftRuntimeAfter=[])
 sources=[io.reference(Path(__file__).absolute())]+[io.reference(E/n)for n in ('Eng017OriginalPreparationDriftControl.java','source_launch_reserve_v1.py','motion_v2_attempt_ledger_v1.py','original_host_io_v1.py')]
 command_lock=threading.Lock()
 result['origin']=io.reference(E/'test_original_preparation_v1.py')
 result['helperRoot']=str(E)
 runtime=[config['java'],*config['jars']];result['sourcesBefore']=sources;result['runtimeBefore']=runtime
 copies=out/'sources';copies.mkdir()
 for r in sources:result['sourceCopies'].append(dict(original=r,preserved=save(copies/Path(r['path']).name,io.checked(r))))
 # Collect fixture paths before intentional drift; preserve all actual versions.
 originals={}
 def remember(r):
  raw=io.checked(r)
  if Path(r['path']).is_relative_to(Path(tempfile.gettempdir())):originals[(r['path'],r['sha256'])]=(r,raw)
 try:
  with tempfile.TemporaryDirectory(prefix='eng017-original-preparation-controls-')as temporary:
   base=Path(temporary)
   try:
    remember(config_ref);remember(config['graph'])
    graph=io.document(io.checked(config['graph']))
    for r in graph['sources']['allowedCode']+[v for v in graph['sources'].values()if type(v)is dict]+[graph['cli']]:remember(r)
    cp=os.pathsep.join(r['path']for r in config['jars'])
    def process(label,argv):return run_control(label,argv,base,result['commands'],command_lock)
    def reservation(label,role='original'):
     folder=base/label;folder.mkdir();root=folder/'ledger';root.mkdir();output=folder/'output';output.mkdir()
     history=save(folder/'history.json',dict(kind='motion-v2-protected-core-index',schemaVersion=1,entries=[]))
     q=io.document(io.checked(config['request']));q['role']=role;q['hostAttemptId']=str(uuid.uuid4())
     campaign=io.document(io.checked(config['campaign']));slot=next(x for x in campaign['runs']if x['caseId']=='OM08'and x['repeat']==2)
     q['runId']=slot['originalRunId'if role=='original'else'coreRunId'];request=save(folder/'request.json',q)
     launch_folder=folder/'launch';launch_folder.mkdir()
     launch=host.admit_and_reserve(wrapper=config['wrapper'],campaign=config['campaign'],request=request,case_id='OM08',repeat=2,role=role,java=config['java'],jars=config['jars'],protected_sources=sources,ledger_root=root,history=history,output=launch_folder)
     io.require(launch['status']=='RESERVED_NO_HOST_STARTED','control reservation failed')
     for r in launch['boundBefore']:remember(r)
     command=dict(kind='motion-v2-original-preparation-command',schemaVersion=1,wrapper=config['wrapper'],campaign=config['campaign'],request=request,caseId='OM08',repeat=2,role=role,launch=launch['resultRef'],ledgerRoot=str(root),knownCoreIndex=history,sourceGraph=config['graph'],output=str(output))
     return folder,output,command,launch
    cases=[]
    for label in ('success','core-role','unqualified-graph','alias','collision'):
     folder,output,command,launch=reservation(label,'core'if label=='core-role'else'original')
     if label=='unqualified-graph':command['sourceGraph']=save(folder/'unlisted-graph.json',io.checked(config['graph']))
     if label=='alias':(output/'sub').mkdir();command['output']=str(output/'sub'/'..')
     if label=='collision':save(output/'read-slot.json',b'preserve collision')
     command_ref=save(folder/'command.json',command)
     argv=[config['java']['path'],'-cp',cp,'com.rustedwarfare.reforge.testing.cross.MotionV2OriginalReadPreparationMain',command_ref['path'],command_ref['sha256']]
     cases.append((label,output,command,launch,command_ref,argv))
    # All reservations are independent. Join every read before intentional shared graph drift.
    with ThreadPoolExecutor(max_workers=2)as pool:
     futures=[pool.submit(process,case[0],case[5])for case in cases]
     rows=[]
     for case,future in zip(cases,futures):
      try:rows.append(future.result())
      except Exception as error:
       row=next((r for r in result['commands']if r['label']==case[0]),None)
       if row is None:
        row=dict(label=case[0],argv=case[5],timeoutSeconds=60,exitCode=None,timedOut=False,firstError=None,preservationErrors=[])
        result['commands'].append(row)
       row['firstError']=row['firstError']or dict(phase='future',type=type(error).__name__,message=str(error));rows.append(row)
    order={case[0]:i for i,case in enumerate(cases)}
    result['commands'].sort(key=lambda row:order[row['label']])
    for (label,output,command,launch,command_ref,_),row in zip(cases,rows):
     result['tests'].append(dict(name=label,command=command_ref,launch=launch['resultRef'],process=row,validated=False))
    for (label,output,command,launch,command_ref,_),row,test in zip(cases,rows,result['tests']):
     require_finished(row)
     if label=='success':
      io.require(row['exitCode']==0,io.checked(row['stderr']).decode('utf8',errors='replace'))
      prepared_ref=io.document(io.checked(row['stdout']));prepared=io.document(io.checked(prepared_ref));dto=io.document(io.checked(prepared['slot']))
      entry=io.document(io.checked(launch['admissionExport']))['originalEntry']
      io.require(dto==dict(kind='motion-v2-original-read-slot',schemaVersion=1,**{k:entry[k]for k in ('caseId','repeat','originalRunId','originalRequest','capture','rawRefs')}),'DTO differs from same admitted entry')
      q=io.document(io.checked(command['request']));io.require(prepared['hostAttemptId']==q['hostAttemptId']and prepared['requestId']not in(q['hostAttemptId'],q['runId']),'fresh prepared IDs')
      io.require(prepared['runtimeRequest']==command['request']and prepared['sourceGraph']==config['graph']and prepared['campaign']==config['campaign'],'same actual prepared identities')
      test['prepared']=prepared_ref
     else:
      io.require(row['exitCode']not in(None,0),'expected file preparation rejection')
      if label=='collision':io.require((output/'read-slot.json').read_bytes()==b'preserve collision','collision overwritten')
      else:io.require(not(output/'read-slot.json').exists(),'rejection wrote DTO')
      io.require(not(output/'read-preparation.json').exists(),'rejection wrote preparation')
     test['validated']=True
    folder,output,command,launch=reservation('drift')
    command_ref=save(folder/'command.json',command);classes=folder/'classes';classes.mkdir();javac=Path(config['java']['path']).with_name('javac.exe');result['driftRuntimeBefore'].append(io.reference(javac))
    compiled=process('drift-compile',[str(javac),'-encoding','UTF-8','-cp',cp,'-d',str(classes),str(E/'Eng017OriginalPreparationDriftControl.java')])
    require_finished(compiled)
    io.require(compiled['exitCode']==0,io.checked(compiled['stderr']).decode('utf8',errors='replace'))
    result['driftRuntimeBefore'] += [io.reference(p)for p in classes.glob('*.class')]
    drift=process('dual-drift',[config['java']['path'],'-cp',str(classes)+os.pathsep+cp,'Eng017OriginalPreparationDriftControl',command_ref['path'],str(folder)])
    require_finished(drift)
    io.require(drift['exitCode']==0,io.checked(drift['stderr']).decode('utf8',errors='replace'))
    observed=io.document(io.checked(io.document(io.checked(drift['stdout']))));result['driftObserved']=observed;result['tests'].append(dict(name='dual-drift',command=command_ref,process=drift,validated=True))
    for old,copy in (('graphOriginal','graphCopy'),('slotOriginal','slotCopy')):originals[(observed[old]['path'],observed[old]['sha256'])]=(observed[old],io.checked(observed[copy]))
   except Exception as error:result['error']=dict(type=type(error).__name__,message=str(error),traceback=traceback.format_exc())
   finally:
    for r in result['driftRuntimeBefore']:
     try:result['driftRuntimeAfter'].append(io.reference(Path(r['path'])))
     except Exception as error:result['postErrors'].append(dict(reference=r,error=str(error)))
    paths=[Path(r['path'])for r,_ in originals.values()]
    try:paths=list(base.rglob('*'))+paths
    except Exception as error:result['archiveErrors'].append(dict(path=str(base),phase='enumerate',error=str(error)))
    for path in paths:
     try:
      if path.is_file():
       r=io.reference(path);originals[(r['path'],r['sha256'])]=(r,io.checked(r))
     except Exception as error:result['archiveErrors'].append(dict(path=str(path),phase='read',error=str(error)))
    preserved=out/'synthetic-preserved'
    try:preserved.mkdir()
    except Exception as error:result['archiveErrors'].append(dict(path=str(preserved),phase='mkdir',error=str(error)))
    for index,(r,raw)in enumerate(originals.values()):
     target=preserved/(str(index)+'-'+Path(r['path']).name)
     try:result['preservedTempFiles'].append(dict(original=r,preserved=save(target,raw)))
     except Exception as error:result['archiveErrors'].append(dict(path=str(target),original=r,phase='save',error=str(error)))
 except Exception as error:
  if result['error']is None:result['error']=dict(type=type(error).__name__,message=str(error),traceback=traceback.format_exc())
  else:result['archiveErrors'].append(dict(phase='temporary-cleanup',error=str(error)))
 finally:
  for key,refs in (('sourcesAfter',sources),('runtimeAfter',runtime)):
   result[key]=[]
   for r in refs:
    try:result[key].append(io.reference(Path(r['path'])))
    except Exception as error:result['postErrors'].append(dict(reference=r,error=str(error)))
  result['originAfter']=None
  try:result['originAfter']=io.reference(E/'test_original_preparation_v1.py')
  except Exception as error:result['postErrors'].append(dict(reference=result['origin'],error=str(error)))
  result['notExecuted']=[name for name in ('success','core-role','unqualified-graph','alias','collision','dual-drift')if not any(test['name']==name for test in result['tests'])]
  result['passed']=result['error']is None and result['origin']==result['originAfter']and not result['archiveErrors']and not result['postErrors']and result['sourcesBefore']==result['sourcesAfter']and result['runtimeBefore']==result['runtimeAfter']and result['driftRuntimeBefore']==result['driftRuntimeAfter']and len(result['tests'])==6 and all(test['validated']for test in result['tests'])
  print(json.dumps(save(out/'result.json',result),ensure_ascii=True))
 return 0 if result['passed']else 1
if __name__=='__main__':sys.exit(main())
