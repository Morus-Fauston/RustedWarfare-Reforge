"""Fixed consumer qualifications and scenario archives for the shared workflow."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import sys
import os
import shutil
import subprocess
import tempfile

from platform_scenario import compare_run, session_anchors, tree, PlatformError
from workflow import execute_step, resources

ROOT=Path(__file__).resolve().parents[2]


def source_store_root(run):
    """Share across batches of one evidence ticket; portable runs use their parent."""
    run=Path(run)
    for parent in run.parents:
        if parent.parent.name=='evidence' and re.fullmatch(r'(ENG|TEST)-P[0-9]{3}-[0-9]{3}',parent.name):
            return parent/'source-snapshots'
    return run.parent/'source-snapshots'


def read_run_plan(path):
    path=Path(path).resolve(strict=True)
    plan=json.loads(path.read_text(encoding='utf8'))
    allowed={'schemaVersion','root','suite','kind','cases','qualifications'}
    if not isinstance(plan,dict) or set(plan)!=allowed or plan['schemaVersion']!=1:
        raise ValueError('Expected a complete fixed public scenario plan')
    if Path(plan['root']).resolve(strict=True)!=ROOT or plan['kind'] not in ('short','long'):
        raise ValueError('Scenario plan must select this source root and a short/long layer')
    indexes=plan['qualifications']
    if not isinstance(indexes,list) or not indexes or any(not isinstance(q,dict) or set(q)!={'path','sha256'} for q in indexes):
        raise ValueError('Scenario plan needs pinned consumer qualifications')
    qualified=[]
    for record in indexes:
        source=Path(record['path'])
        if not source.is_absolute():source=path.parent/source
        source=source.resolve(strict=True)
        if digest(source)!=record['sha256']:
            raise ValueError('Scenario qualification digest differs from fixed plan')
        qualified.append(source)
    sys.path.insert(0,str(ROOT/'SimulationTest/ci'))
    from public_check import plan_suite
    suite=plan_suite(ROOT,plan['suite'],plan['kind'])
    ids=plan['cases']
    if (suite['inputScope']!='public' or not isinstance(ids,list) or not ids or
            any(not isinstance(i,str) for i in ids) or len(set(ids))!=len(ids)):
        raise ValueError('Expected unique public scenario identifiers')
    selected=[c for c in suite['scenarios'] if c['id'] in ids]
    if {c['id'] for c in selected}!=set(ids):
        raise ValueError('Scenario plan contains an unregistered case')
    backends={json.loads(p.read_text(encoding='utf8')).get('backend','core') for p in qualified}
    if not {c.get('backend','core') for c in selected} <= backends:
        raise ValueError('Every selected backend needs its own fixed consumer qualification')
    return plan,selected,qualified


def save(path,value):
    with Path(path).open('x',encoding='utf8') as stream:
        json.dump(value,stream,ensure_ascii=True,indent=2);stream.write('\n')


def scenario_output(path):
    path=Path(path).resolve()
    if path.exists():raise ValueError('Scenario output must be a new batch')
    if path.is_relative_to(ROOT):
        if any(path.is_relative_to(ROOT/p) and path!=ROOT/p for p in ('Engine/build','SimulationTest/build')):
            return path
        base=ROOT/'.scratch/开发票据'
        if path.is_relative_to(base):
            parts=path.relative_to(base).parts
            if ('evidence' in parts and len(parts)>parts.index('evidence')+2 and
                    re.fullmatch(r'(ENG|TEST)-P[0-9]{3}-[0-9]{3}',parts[parts.index('evidence')+1])):
                return path
        raise ValueError('Scenario output cannot enter source or unrelated workspace directories')
    if not path.is_relative_to(Path(tempfile.gettempdir()).resolve()):
        raise ValueError('External scenario output must be in system Temp')
    return path


def copy_qualification(index_path,target):
    """Capture only declared replay inputs, never an arbitrary parent directory."""
    document=json.loads(index_path.read_text(encoding='utf8'));root=index_path.parent
    paths=[index_path,member(root,document['scenario']),member(root,document['contract'])]
    backend=document.get('backend','core')
    for case in document['cases']:
        if backend=='session':paths.append(member(root,case['path']))
        else:paths += [member(root,case['path']+'/'+n) for n in
            ('manifest.json','inputs/scenario.json','inputs/contract.json','reforge/raw.jsonl','reforge/normalized.jsonl')]
    for source in sorted(set(paths)):
        destination=target/source.relative_to(root)
        destination.parent.mkdir(parents=True,exist_ok=True)
        shutil.copy2(source,destination)
        if digest(source)!=digest(destination):raise ValueError('Qualification changed while capturing')
    return target/index_path.name


def run_scenarios(plan_path,output):
    with resources(['build:'+str(ROOT/'Engine'),'build:'+str(ROOT/'SimulationTest')]):
        return _run_scenarios(plan_path,output)


def _run_scenarios(plan_path,output):
    plan,cases,indexes=read_run_plan(plan_path)
    output=scenario_output(output)
    guard=ROOT/'Decompile/tools/workspace_layout.py'
    if not guard.is_file():raise ValueError('Private workspace layout guard is required for a producer run')
    checked=subprocess.run([sys.executable,'-B',str(guard)],cwd=ROOT,capture_output=True,timeout=30)
    if checked.returncode:raise ValueError('Workspace layout refused scenario output')
    sys.path.insert(0,str(ROOT/'SimulationTest/ci'))
    from public_check import source_files,identities
    from public_distribution import toolchain_identity
    from workflow_inputs import freeze
    initial=identities(ROOT);toolchain=toolchain_identity()
    output.mkdir(parents=True,exist_ok=False)
    save(output/'intent.json',dict(plan=plan,selection=cases,source=initial,toolchain=toolchain,
        sourceCommit=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT).decode().strip(),
        layout=json.loads(checked.stdout),scope='Fixed selected public Core/Session scenarios; not phase or platform acceptance'))
    result=dict(schemaVersion=1,passed=False,firstError=None,postErrors=[],selection=cases,
        build=dict(status='not-run'),qualification=dict(status='not-run'),execution=[],consumption=[])
    candidate=output/'candidate';snapshot=candidate/'source';package=candidate/'package'
    stage='source-capture'
    try:
        from source_snapshots import SnapshotStore
        reference=candidate/'source-reference.json'
        store=SnapshotStore(source_store_root(output))
        result['sourceCapture']=store.capture(ROOT,[p.relative_to(ROOT).as_posix() for p in source_files(ROOT)],reference)
        result['sourceStorage']=dict(kind='shared-tree-zip-v1',reference='candidate/source-reference.json',
            store=Path(os.path.relpath(store.root,output)).as_posix(),recovery='Keep the referenced objects; scenario-restore transfers them')
        # The immutable snapshot is shared. Only a mutable build/worker tree
        # is materialized in Temp, never another archived source tree.
        snapshot=Path(tempfile.mkdtemp(prefix='rwre-scenario-source-'))/'source'
        store.restore(reference,snapshot)
        if identities(snapshot)!=initial:raise ValueError('Public source changed during capture')
        public=snapshot/'SimulationTest/public'
        for case in cases:
            if case.get('backend','core')=='core':freeze(public,[case['scenario'],case['contract']])
            else:
                scene=json.loads((public/case['scenario']).read_text(encoding='utf8'))
                inputs=(public/case['scenario']).parent/scene['inputs']
                if not inputs.resolve().is_relative_to(public) or not tree(inputs):
                    raise ValueError('Session inputs must be a nonempty public closure')
        fixed_indexes=[copy_qualification(p,output/'qualification-inputs'/str(i)) for i,p in enumerate(indexes)]
        if any(digest(p)!=record['sha256'] for p,record in zip(fixed_indexes,plan['qualifications'])):
            raise ValueError('Pinned qualification changed during capture')
        stage='build'
        build_root=snapshot
        wrapper=[str(build_root/'SimulationTest/gradlew.bat')] if os.name=='nt' else ['sh',str(build_root/'SimulationTest/gradlew')]
        built=execute_step(wrapper+[':installDist','--no-daemon','--console=plain','--max-workers=1','--rerun-tasks'],
            build_root/'SimulationTest',output/'build-command')
        result['build']=dict(status='passed' if built['passed'] else 'failed',command=built,temporarySource=str(build_root))
        if not built['passed']:raise ValueError('Fixed scenario distribution build failed')
        shutil.copytree(build_root/'SimulationTest/build/install/simulation-test',package)
        save(candidate/'identity.json',dict(source=initial,packageFiles=tree(package),toolchain=toolchain))
        stage='qualification';qualified=[]
        for i,index in enumerate(fixed_indexes):
            leaf=output/'qualification-results'/str(i);leaf.mkdir(parents=True)
            # The frozen worker has its own source ROOT. Give it an allowed
            # Temp destination, then preserve its complete bytes in this batch.
            worker_output=Path(tempfile.mkdtemp(prefix='rwre-scenario-qualification-'))/'actual'
            command=execute_step([sys.executable,'-B',str(snapshot/'SimulationTest/tools/workflow.py'),
                'scenario-qualify','--index',str(index),'--distribution',str(package),'--output',str(worker_output)],
                snapshot,leaf/'command')
            copied=worker_output.is_dir()
            worker_files=tree(worker_output) if copied else []
            if copied:
                shutil.copytree(worker_output,leaf/'actual')
                if tree(worker_output)!=worker_files or tree(leaf/'actual')!=worker_files:
                    raise ValueError('Qualification bytes changed while preserving worker output')
            save(leaf/'relocation.json',dict(original=str(worker_output),
                captured='actual' if copied else None,files=worker_files,
                scope='Original worker output preserved, including failures; inputs unchanged'))
            qualified.append(dict(path=str(leaf.relative_to(output)),command=command))
            result['qualification']=dict(status='running',cases=qualified)
            if not command['passed'] or not json.loads((leaf/'actual/result.json').read_text(encoding='utf8'))['passed']:
                raise ValueError('Consumer qualification failed; no producer scenario starts')
        result['qualification']['status']='passed'
        for case in cases:
            if tree(package)!=json.loads((candidate/'identity.json').read_text(encoding='utf8'))['packageFiles']:
                raise ValueError('Fixed package changed before scenario execution')
            leaf=output/'runs'/case['id'];leaf.mkdir(parents=True)
            scenario=public/case['scenario'];contract=public/case['contract'];backend=case.get('backend','core')
            stage='execution:'+case['id']
            if backend=='core':
                launcher=package/'bin'/('simulation-test.bat' if os.name=='nt' else 'simulation-test')
                argv=[str(launcher)] if os.name=='nt' else ['sh',str(launcher)]
                command=execute_step(argv+['run','--scenario',str(scenario),'--contract',str(contract),
                    '--output',str(leaf/'archive'),'--mode','self'],snapshot,leaf/'execution')
                archives=list((leaf/'archive').glob('run-*'))
                actual=archives[0] if len(archives)==1 else None
                collected=bool(actual and json.loads((actual/'manifest.json').read_text(encoding='utf8')).get('complete'))
                compare=argv+['compare','--scenario',str(scenario),'--contract',str(contract),'--actual',str(actual),
                    '--output',str(leaf/'recomparison'),'--mode','self']
            else:
                actual=leaf/'archive'
                adapter=[sys.executable,'-B',str(snapshot/'SimulationTest/tools/platform_scenario.py')]
                command=execute_step(adapter+['session','--scenario',str(scenario),'--contract',str(contract),
                    '--distribution',str(package),'--output',str(actual),'--timeout','180'],snapshot,leaf/'execution')
                collected=(actual/'archive.json').is_file() and json.loads((actual/'archive.json').read_text(encoding='utf8')).get('complete') is True
                compare=adapter+['compare','--run',str(actual)]
            result['execution'].append(dict(case=case['id'],backend=backend,command=command,
                collected=collected,archive=actual.relative_to(output).as_posix() if actual else None))
            stage='consumption:'+case['id']
            if collected:
                consumed=execute_step(compare,snapshot,leaf/'consumption')
                result['consumption'].append(dict(case=case['id'],command=consumed))
            else:consumed=dict(passed=False)
            if not command['passed']:
                stage='execution:'+case['id']
                raise ValueError('Scenario process failed; captured evidence preserved without rerun')
            if not consumed['passed']:
                raise ValueError('Independent consumption failed; captured evidence preserved without rerun')
        stage='identity-verification'
        if identities(ROOT)!=initial or identities(snapshot)!=initial or toolchain_identity()!=toolchain:
            raise ValueError('Original source/input/toolchain changed after freezing')
        if tree(package)!=json.loads((candidate/'identity.json').read_text(encoding='utf8'))['packageFiles']:
            raise ValueError('Fixed scenario package changed during execution')
        result['passed']=True
    except Exception as error:
        if stage=='qualification' and result['qualification']['status']!='passed':
            result['qualification']['status']='failed'
        result['firstError']=dict(stage=stage,message=type(error).__name__+': '+str(error))
    save(output/'execution.json',result['execution']);save(output/'consumption.json',result['consumption'])
    save(output/'build.json',result['build']);save(output/'qualification.json',result['qualification'])
    save(output/'summary.json',result)
    complete=(len(result['execution'])==len(cases) and all(r['collected'] for r in result['execution']) and package.is_dir())
    payload=dict(schemaVersion=2,kind='scenario-archive-manifest',complete=complete,files=tree(output))
    save(output/'payload.json',payload)
    result['sealing']=dict(status='sealed-complete' if complete else 'preserved-incomplete',payloadSha256=digest(output/'payload.json'))
    save(output/'seal.json',result['sealing'])
    return result


def verify_payload(run):
    run=Path(run).resolve(strict=True)
    payload=json.loads((run/'payload.json').read_text(encoding='utf8'))
    seal=json.loads((run/'seal.json').read_text(encoding='utf8'))
    if type(payload.get('schemaVersion')) is not int or type(payload.get('complete')) is not bool:
        raise ValueError('Invalid scenario archive version or completion type')
    if (payload.get('schemaVersion') not in (1,2) or payload.get('kind')!='scenario-archive-manifest' or
            digest(run/'payload.json')!=seal.get('payloadSha256')):
        raise ValueError('Missing or changed scenario payload identity')
    files=payload.get('files')
    if (not isinstance(files,list) or not files or any(not isinstance(f,dict) for f in files) or
            len({f['path'] for f in files})!=len(files)):
        raise ValueError('Invalid or duplicate scenario payload file set')
    for item in files:
        if digest(member(run,item['path']))!=item['sha256']:
            raise ValueError('Scenario payload bytes changed: '+item['path'])
    metadata=set()
    if (run/'restore.json').is_file():
        restored=json.loads((run/'restore.json').read_text(encoding='utf8'))
        if restored.get('payloadSha256')!=seal['payloadSha256']:
            raise ValueError('Restore mapping differs from original payload')
        for item in restored.get('metadata',[]):
            if item['path'] not in ('origin-result.json','origin-terminal.json') or digest(member(run,item['path']))!=item['sha256']:
                raise ValueError('Restored original metadata changed')
            metadata.add(item['path'])
    actual={f['path'] for f in tree(run)}-{'payload.json','seal.json','result.json','terminal.json','restore.json'}
    if actual!={f['path'] for f in files}|metadata:raise ValueError('Scenario payload closure changed')
    reference=run/'candidate/source-reference.json'
    if payload['schemaVersion']==2 and payload.get('complete') is True and not reference.is_file():
        raise ValueError('Complete source-reference archive requires its source reference')
    if reference.is_file():
        sys.path.insert(0,str(ROOT/'SimulationTest/ci'))
        from source_snapshots import SnapshotStore
        SnapshotStore(source_store_root(run)).verify(reference)
    return run,payload,seal


def restore_scenarios(run,output):
    with resources(['build:'+str(ROOT/'Engine'),'build:'+str(ROOT/'SimulationTest')]):
        return _restore_scenarios(run,output)


def _restore_scenarios(run,output):
    run,payload,seal=verify_payload(run)
    output=scenario_output(output)
    if output.is_relative_to(run):raise ValueError('Restore output must stay outside the captured run')
    reference=run/'candidate/source-reference.json'
    if reference.is_file():
        sys.path.insert(0,str(ROOT/'SimulationTest/ci'))
        from source_snapshots import SnapshotStore
        SnapshotStore(source_store_root(run)).copy_to(reference,source_store_root(output))
    output.mkdir(parents=True,exist_ok=False)
    for name in [f['path'] for f in payload['files']]+['payload.json','seal.json']:
        source=member(run,name);target=member(output,name)
        target.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(source,target)
    metadata=[]
    for name in ('result.json','terminal.json'):
        source=run/('origin-'+name) if (run/('origin-'+name)).is_file() else run/name
        if source.is_file():
            target=output/('origin-'+name);shutil.copy2(source,target)
            metadata.append(dict(path=target.name,sha256=digest(target)))
    save(output/'restore.json',dict(schemaVersion=1,origin=str(run),payloadSha256=seal['payloadSha256'],
        locatorPolicy='Captured payload paths remain relative; original documents and hashes are unchanged',metadata=metadata))
    verify_payload(output);verify_payload(run)
    restoration=dict(status='verified',payloadSha256=seal['payloadSha256'])
    if reference.is_file():
        restored_source=Path(tempfile.mkdtemp(prefix='rwre-restored-candidate-'))/'source'
        SnapshotStore(source_store_root(output)).restore(output/'candidate/source-reference.json',restored_source)
        restoration.update(sourceRoot=str(restored_source),sourceStore=str(source_store_root(output)),
            sourceReference='candidate/source-reference.json')
    return dict(schemaVersion=1,passed=payload['complete'],firstError=None if payload['complete'] else
        dict(stage='restore',message='Bytes restored, but original capture was incomplete'),postErrors=[],
        execution=dict(status='not-run'),restoration=restoration,
        scope='Restored candidate source/package/input/raw bytes only; no simulation or build')


def compare_scenarios(run,output):
    with resources(['build:'+str(ROOT/'Engine'),'build:'+str(ROOT/'SimulationTest')]):
        return _compare_scenarios(run,output)


def _compare_scenarios(run,output):
    run,payload,seal=verify_payload(run)
    output=scenario_output(output)
    if output.is_relative_to(run):raise ValueError('Replay output must stay outside the captured run')
    output.mkdir(parents=True,exist_ok=False)
    intent=json.loads((run/'intent.json').read_text(encoding='utf8'))
    snapshot=run/'candidate/source';package=run/'candidate/package'
    selection=intent.get('selection')
    if selection is None:
        sys.path.insert(0,str(ROOT/'SimulationTest/ci'))
        from public_check import plan_suite
        plan=intent['plan'];selection=[c for c in plan_suite(snapshot,plan['suite'],plan['kind'])['scenarios'] if c['id'] in plan['cases']]
    execution=json.loads((run/'execution.json').read_text(encoding='utf8'))
    result=dict(schemaVersion=1,passed=False,firstError=None,postErrors=[],execution=dict(status='not-run',
        reason='Independent replay only; no simulation or build'),consumption=[],originalPayloadSha256=seal['payloadSha256'],
        originalRun=str(run),scope='Captured candidate consumers and inputs only; not current private workspace acceptance')
    try:
        reference=run/'candidate/source-reference.json'
        if reference.is_file():
            sys.path.insert(0,str(ROOT/'SimulationTest/ci'))
            from source_snapshots import SnapshotStore
            snapshot=Path(tempfile.mkdtemp(prefix='rwre-scenario-replay-'))/'source'
            SnapshotStore(source_store_root(run)).restore(reference,snapshot)
        for case in selection:
            capture=next((e for e in execution if e['case']==case['id']),None)
            if not capture or not capture['collected'] or not capture.get('archive'):
                raise ValueError('Selected scenario lacks a complete capture: '+case['id'])
            # Early schema-1 Windows captures stored native relative separators.
            # Resolve that locator explicitly; never rewrite the sealed original.
            locator=capture['archive'].replace('\\','/')
            actual=member(run,locator);leaf=output/case['id'];leaf.mkdir()
            if case.get('backend','core')=='core':
                launcher=package/'bin'/('simulation-test.bat' if os.name=='nt' else 'simulation-test')
                argv=[str(launcher)] if os.name=='nt' else ['sh',str(launcher)]
                public=snapshot/'SimulationTest/public'
                argv+=['compare','--scenario',str(public/case['scenario']),'--contract',str(public/case['contract']),
                    '--actual',str(actual),'--output',str(leaf/'comparison-output'),'--mode','self']
            else:
                argv=[sys.executable,'-B',str(snapshot/'SimulationTest/tools/platform_scenario.py'),'compare','--run',str(actual)]
            command=execute_step(argv,snapshot,leaf/'command')
            result['consumption'].append(dict(case=case['id'],command=command))
            if not command['passed']:raise ValueError('Independent consumption rejected '+case['id'])
        verify_payload(run)
        result['passed']=True
    except Exception as error:
        result['firstError']=dict(stage='consumption',message=type(error).__name__+': '+str(error))
    save(output/'consumption.json',result['consumption'])
    return result


def finish_result(result,output):
    terminal=dict(status='passed')
    try:
        print(json.dumps(dict(passed=result['passed'],output=str(output)),ensure_ascii=True),flush=True)
    except (OSError,UnicodeError) as error:
        terminal=dict(status='failed',error=type(error).__name__+': '+str(error));result['passed']=False
        if result['firstError'] is None:result['firstError']=dict(stage='printing',message=terminal['error'])
        else:result['postErrors'].append(dict(stage='printing',message=terminal['error']))
    result['printing']=terminal
    save(output/'terminal.json',terminal);save(output/'result.json',result)
    return 0 if result['passed'] else 4


def digest(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def member(root, name):
    if (not isinstance(name, str) or not name or Path(name).is_absolute() or
            any(p in ('.','..') for p in Path(name).parts) or Path(name).as_posix() != name):
        raise ValueError('Expected canonical relative qualification member')
    path = root/name
    if path.resolve() != path or not path.resolve().is_relative_to(root):
        raise ValueError('Qualification member escapes its fixed root')
    return path


def qualify(index_path, output, distribution=None):
    output=scenario_output(output)
    index_path = Path(index_path).resolve(strict=True)
    root = index_path.parent
    captured = index_path.read_bytes()
    index = json.loads(captured)
    if not isinstance(index,dict) or index.get('schemaVersion') != 1:
        raise ValueError('Expected a fixed qualification index')
    backend=index.get('backend','core')
    if (backend,index.get('fixtureKind','core-archive')) not in (('session','legacy-raw'),('core','core-archive')):
        raise ValueError('Unsupported fixed qualification format')
    if backend=='core':
        if distribution is None:
            raise ValueError('Core qualification requires an explicitly fixed comparison distribution')
        distribution=Path(distribution).resolve(strict=True)
        launcher=distribution/'bin'/('simulation-test.bat' if os.name=='nt' else 'simulation-test')
        if not launcher.is_file():
            raise ValueError('Missing installed Core comparison entry')
    cases = index.get('cases')
    if (not isinstance(cases, list) or not cases or any(not isinstance(c,dict) for c in cases) or
            not any(c.get('expectedExitCode') == 0 for c in cases) or
            not any(c.get('expectedExitCode') in (2,3) for c in cases)):
        raise ValueError('Qualification needs known good and semantic bad cases')
    names = [c.get('case') for c in cases]
    if (any(not isinstance(n,str) or not re.fullmatch(r'[A-Za-z0-9_-]+',n) for n in names) or
            len(set(names)) != len(names)):
        raise ValueError('Qualification case names must be unique fixed identifiers')
    scenario = member(root,index['scenario']); contract_path = member(root,index['contract'])
    files = [scenario,contract_path]
    for case in cases:
        code = case.get('expectedExitCode')
        category=case.get('expectedCategory',case.get('category'))
        if (type(code) is not int or code not in (0,2,3) or
                code != 0 and not isinstance(category,str)):
            raise ValueError('Bad cases need an explicit evidence/semantic category; execution errors cannot qualify')
        path = member(root,case['path'])
        if backend=='session':
            if digest(path) != case.get('sha256'):
                raise ValueError('Qualification raw bytes changed: '+case['case'])
            files.append(path)
        else:
            files += [member(root,case['path']+'/'+name) for name in
                ('manifest.json','inputs/scenario.json','inputs/contract.json','reforge/raw.jsonl','reforge/normalized.jsonl')]
    before = {p.relative_to(root).as_posix():digest(p) for p in files}
    plan = json.loads(scenario.read_text(encoding='utf8'))
    contract = json.loads(contract_path.read_text(encoding='utf8'))
    output.mkdir(parents=True,exist_ok=False)
    package_files=tree(distribution) if backend=='core' else None
    result = dict(schemaVersion=1,passed=False,backend=backend,indexSha256=hashlib.sha256(captured).hexdigest(),
                  consumerSha256=digest(Path(__file__).with_name('platform_scenario.py')),
                  qualifierSha256=digest(__file__),comparisonPackageFiles=package_files,
                  inputFiles=before,cases=[],scope='Consumer replay only; no producer, build or original package claim')
    for case in cases:
        try:
            path=member(root,case['path'])
            if backend=='session':
                with path.open(encoding='utf8') as stream:
                    rows=[json.loads(line) for line in stream]
                measured=compare_run(rows,plan,contract,session_anchors(rows))
            else:
                leaf=output/case['case'];leaf.mkdir()
                argv=[str(launcher)] if os.name=='nt' else ['sh',str(launcher)]
                command=execute_step(argv+['compare','--scenario',str(scenario),'--contract',str(contract_path),
                    '--actual',str(path),'--output',str(leaf/'comparison-output'),'--mode','self'],
                    distribution,leaf/'command',expected=case['expectedExitCode'])
                archives=list((leaf/'comparison-output').glob('run-*'))
                if len(archives)!=1:
                    raise ValueError('Core consumer did not preserve one comparison result')
                measured=json.loads((archives[0]/'comparison/result.json').read_text(encoding='utf8'))
                if measured['exitCode']!=command['exitCode']:
                    raise ValueError('Core process and comparison result disagree')
                measured=dict(measured,category=measured.get('comparison',{}).get('category'),command=command)
        except PlatformError as error:
            measured=dict(exitCode=error.exit_code,category=error.category,message=str(error))
        except (OSError,ValueError,KeyError,TypeError) as error:
            measured=dict(exitCode=4,category='qualification-exception',message=str(error))
        passed=measured['exitCode']==case['expectedExitCode'] and (
            case['expectedExitCode']==0 or measured.get('category')==case.get('expectedCategory',case.get('category')))
        result['cases'].append(dict(case=case['case'],expectedExitCode=case['expectedExitCode'],
            actualExitCode=measured['exitCode'],passed=passed,judgment=measured))
    result['sourceUnchanged'] = (before=={p.relative_to(root).as_posix():digest(p) for p in files}
                                 and index_path.read_bytes()==captured and
                                 (backend!='core' or tree(distribution)==package_files))
    result['passed'] = result['sourceUnchanged'] and all(c['passed'] for c in result['cases'])
    (output/'result.json').write_text(json.dumps(result,ensure_ascii=True,indent=2)+'\n',encoding='utf8')
    return result


def main(argv=None):
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('operation',choices=['scenario-qualify','scenario-run','scenario-compare','scenario-restore'])
    parser.add_argument('--index',type=Path)
    parser.add_argument('--plan',type=Path)
    parser.add_argument('--run',type=Path)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--distribution',type=Path,help='Fixed installed Core comparison package')
    args=parser.parse_args(argv)
    try:
        if args.operation=='scenario-run':
            if args.plan is None:raise ValueError('Scenario run requires --plan')
            result=run_scenarios(args.plan,args.output)
            return finish_result(result,args.output)
        if args.operation=='scenario-compare':
            if args.run is None:raise ValueError('Independent replay requires --run')
            return finish_result(compare_scenarios(args.run,args.output),args.output)
        if args.operation=='scenario-restore':
            if args.run is None:raise ValueError('Candidate restore requires --run')
            return finish_result(restore_scenarios(args.run,args.output),args.output)
        if args.index is None:raise ValueError('Qualification requires --index')
        with resources(['build:'+str(ROOT/'Engine'),'build:'+str(ROOT/'SimulationTest')]):
            result=qualify(args.index,args.output,args.distribution)
        print(json.dumps(dict(passed=result['passed'],output=str(args.output)),ensure_ascii=True),flush=True)
        return 0 if result['passed'] else 4
    except (OSError,ValueError,KeyError,TypeError) as error:
        print(str(error),file=sys.stderr)
        return 64


if __name__=='__main__':
    raise SystemExit(main())
