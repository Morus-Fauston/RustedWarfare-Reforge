"""Build, test and smoke-check current source; see workflow.md."""
import json
import os
from pathlib import Path
import signal
import subprocess
import time
import hashlib
import tempfile
import argparse
import sys
import shutil
import uuid
import xml.etree.ElementTree as ET
from contextlib import contextmanager, ExitStack
from concurrent.futures import ThreadPoolExecutor
import threading

from workflow_inputs import sha, verify, prepare, freeze


def save_json(path, value):
    with Path(path).open('x', encoding='utf-8') as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2)


def preserve_result(path, result):
    """Never replace a process/behavior first error with a receipt failure."""
    try:
        save_json(path,result)
    except OSError as error:
        result.setdefault('preservationErrors',[]).append(str(error))
        result['firstError']=result.get('firstError') or dict(kind='receipt-write',message=str(error))
        result['passed']=False
        try:save_json(Path(path).with_name('receipt-fallback.json'),result)
        except OSError as secondary:
            result['preservationErrors'].append(str(secondary))
            print(json.dumps(result,ensure_ascii=True),file=sys.stderr)
    return result


def canonical(path):
    value = Path(path)
    if not value.is_absolute() or '..' in value.parts or value.resolve() != value or any(part.rstrip(' .')!=part for part in value.parts):
        raise ValueError(f'Non-canonical absolute path: {path}')
    return value.resolve()


def execute_step(argv, cwd, output, timeout=600, expected=0, env=None, cwd_is_output=False):
    """Run one process and return its preserved receipt."""
    output = canonical(output)
    if timeout <= 0:
        raise ValueError('Timeout must be positive')
    output.mkdir()  # A previous attempt is never overwritten.
    argv = [str(a) for a in argv]
    result = dict(argv=argv, cwd=str(Path(cwd).resolve()), expectedExitCode=expected,
                  timeoutSeconds=timeout, startedUnix=time.time(), exitCode=None,
                  firstError=None, preservationErrors=[])
    proc = None
    try:
        with ExitStack() as stack:
            out = stack.enter_context((output/'stdout.log').open('xb'))
            err = stack.enter_context((output/'stderr.log').open('xb'))
            save_json(output/'start.json',result)
            options = {'creationflags': subprocess.CREATE_NEW_PROCESS_GROUP | subprocess.CREATE_NO_WINDOW} if os.name == 'nt' else {'start_new_session': True}
            proc = subprocess.Popen(argv, cwd=cwd, env=env, stdout=out, stderr=err,
                                    shell=False, **options)
            result['pid'] = proc.pid
            try:
                result['exitCode'] = proc.wait(timeout=timeout)
            except subprocess.TimeoutExpired:
                result.update(exitCode=124, timedOut=True, firstError={'kind':'timeout','exitCode':124})
                if proc.poll() is None:
                    try:
                        if os.name == 'nt':
                            killed = subprocess.run(
                                [str(Path(os.environ['SystemRoot'])/'System32/taskkill.exe'),
                                 '/PID', str(proc.pid), '/T', '/F'], capture_output=True, timeout=30)
                            result['terminationExitCode'] = killed.returncode
                            if killed.returncode:
                                result['preservationErrors'].append('Owned process termination returned '+str(killed.returncode))
                        else:
                            os.killpg(proc.pid, signal.SIGKILL)
                    except (OSError, subprocess.SubprocessError) as cleanup:
                        result['preservationErrors'].append('Owned process termination: '+str(cleanup))
                try:
                    result['observedProcessExitCode'] = proc.wait(timeout=30)
                except subprocess.TimeoutExpired:
                    result['preservationErrors'].append('Owned process did not terminate')
    except (OSError, subprocess.SubprocessError) as error:
        result.update(exitCode=125, error=f'{type(error).__name__}: {error}')
    result.update(endedUnix=time.time(), elapsedSeconds=time.time()-result['startedUnix'],
                  passed=result['exitCode'] == expected and not result.get('timedOut', False) and not result['preservationErrors'])
    if not result['passed']:
        result['firstError'] = result['firstError'] or {'kind': 'timeout' if result.get('timedOut') else 'process-exit',
                                'exitCode': result['exitCode'], 'message': result.get('error')}
    try:
        save_json(output/'result.json', result)
    except OSError as error:
        result['preservationErrors'].append(str(error))
        result['firstError'] = result['firstError'] or {'kind': 'receipt-write', 'message': str(error)}
        result['passed'] = False
        try:
            save_json(output/'receipt-fallback.json', result)
        except OSError as secondary:
            result['preservationErrors'].append(str(secondary))
    return result


@contextmanager
def resources(names,formal=False):
    """Machine-wide managed locks. Never take over an existing lock."""
    directory = Path(tempfile.gettempdir())/'rw-reforge-workflow-locks'
    directory.mkdir(exist_ok=True)
    owned = []
    try:
        # Dual registration prevents a formal/functional start race without a
        # queue: formal claims its gate before checking leases; functional
        # creates its lease before checking the formal gate. At worst both refuse.
        gate=directory/'formal-performance.lock'
        lease=gate if formal else directory/('functional-'+uuid.uuid4().hex+'.lease')
        try:
            with lease.open('x',encoding='utf8') as stream:
                json.dump(dict(pid=os.getpid(),formal=formal,startedUnix=time.time()),stream)
        except FileExistsError:
            raise ValueError('Resource occupied: formal performance window') from None
        owned.append(lease)
        if formal and any(directory.glob('functional-*.lease')):
            raise ValueError('Resource occupied: managed functional task; formal performance never queues')
        if not formal and gate.exists():
            raise ValueError('Resource occupied: formal performance window')
        for name in sorted(set(os.path.normcase(n) for n in names)):
            path = directory/(hashlib.sha256(name.encode()).hexdigest()+'.lock')
            try:
                with path.open('x', encoding='utf-8') as stream:
                    json.dump({'resource': name, 'pid': os.getpid(), 'startedUnix': time.time()}, stream)
            except FileExistsError:
                raise ValueError(f'Resource occupied: {name}') from None
            owned.append(path)
        yield
    finally:
        for path in reversed(owned):
            path.unlink()


def run_jobs(jobs, output, workers=4):
    """Bounded host jobs; caller supplies only registered adapter commands.

    A JVM/Gradle adapter must declare its internal workers, not just one process.
    Same-plan resources serialize; cross-plan resources refuse immediately.
    """
    if not 1 <= workers <= 4 or any(not 1 <= j['workers'] <= workers for j in jobs):
        raise ValueError('Functional worker budget must be in 1..4')
    names = [j['name'] for j in jobs]
    if len(set(names)) != len(names) or any(Path(n).name != n or n in ('.','..') for n in names):
        raise ValueError('Job output names conflict or are non-canonical')
    locks = {n: threading.Lock() for job in jobs for n in job['resources']}
    condition = threading.Condition()
    available = workers
    def run(job):
        nonlocal available
        acquired = []
        try:
            for name in sorted(set(job['resources'])):
                locks[name].acquire(); acquired.append(locks[name])
            with condition:
                condition.wait_for(lambda: available >= job['workers'])
                available -= job['workers']
            try:
                with resources(job['resources']):
                    return execute_step(job['argv'], job['cwd'], Path(output)/job['name'],
                                        timeout=job.get('timeout', 600), env=job.get('env'))
            finally:
                with condition:
                    available += job['workers']; condition.notify_all()
        finally:
            for lock in reversed(acquired):
                lock.release()
    with ThreadPoolExecutor(max_workers=workers) as pool:
        return list(pool.map(run, jobs))


def test_evidence(folder, required_tasks=None):
    folder = Path(folder)
    rows = []
    config = folder/'effective-config.json'
    expected = {r['task'] for r in json.loads(config.read_text(encoding='utf-8'))['tests']} if config.is_file() else set()
    expected |= set(required_tasks or [])
    for started in folder.glob('*/started.json'):
        done = started.parent/'completed.json'
        if not done.is_file():
            rows.append({'task': started.parent.name, 'status': 'incomplete', 'tests': 0})
            continue
        row = json.loads(done.read_text(encoding='utf-8'))
        xml = sorted((started.parent/'xml').glob('TEST-*.xml'))
        counts = dict(tests=0, failures=0, errors=0, skipped=0)
        errors = []
        for file in xml:
            suite = ET.parse(file).getroot()
            for key in counts:
                counts[key] += int(suite.get(key, '0'))
            errors += [{'type':e.get('type',''), 'message':e.get('message','')} for e in suite.iter() if e.tag in ('failure','error')]
        valid = bool(xml) and counts['tests'] > 0 and counts['tests'] == row['tests'] and row['skipped'] == 0
        row.update(status='passed' if valid and counts['failures'] == counts['errors'] == 0 else 'failed',
                   xml=[{'path': str(p), 'sha256': sha(p)} for p in xml], xmlCounts=counts, failuresDetail=errors)
        rows.append(row)
    observed = {r['task'] for r in rows}
    rows += [{'task': name, 'status': 'not-executed', 'tests': 0} for name in sorted(expected-observed)]
    return {'status': 'not-executed' if not rows else ('passed' if all(r['status'] == 'passed' for r in rows) else 'failed'),
            'tests': sum(r['tests'] for r in rows), 'tasks': rows}


def source_identity(root):
    names = subprocess.check_output(['git', 'ls-files', '-z'], cwd=root).decode('utf-8').split('\0')
    names += [p.relative_to(root).as_posix() for p in (root/'SimulationTest/tools').glob('workflow*') if p.is_file()]
    names += ['SimulationTest/tools/test_workflow.py']
    names += ['Decompile/tools/workspace_layout.py']
    excluded = {'build','.gradle','.kotlin','bin','__pycache__','.git'}
    for folder in ('Engine','SimulationTest'):
        for directory, dirs, files in os.walk(root/folder):
            dirs[:] = [d for d in dirs if d not in excluded]
            for file in files:
                path = Path(directory)/file
                if 'src' in path.relative_to(root).parts or path.suffix in ('.kts','.gradle','.properties') or ('tools' in path.relative_to(root).parts and path.suffix in ('.py','.ps1')):
                    names.append(path.relative_to(root).as_posix())
    return {name: sha(root/name) for name in sorted(set(names)) if name and (root/name).is_file()}


def tree_identity(root):
    return [{'path':p.relative_to(root).as_posix(),'sha256':sha(p),'size':p.stat().st_size}
            for p in sorted(Path(root).rglob('*')) if p.is_file()]


def evidence_index(root):
    return [r for r in tree_identity(root) if r['path']!='result.json' and not r['path'].endswith('.md')]


def freeze_package(source, target):
    before=tree_identity(source)
    if not before:
        raise ValueError('Empty runtime distribution')
    shutil.copytree(source,target)
    if tree_identity(source)!=before or tree_identity(target)!=before:
        raise ValueError('Runtime copy SHA mismatch or source drift')
    return before


def output_domain(output, root, evidence_root=None):
    output = canonical(output)
    root = canonical(root)
    if output == root:
        raise ValueError('Workspace root cannot be an output batch')
    allowed = [root/'Engine/build', root/'SimulationTest/build', Path(tempfile.gettempdir()).resolve()]
    suffix = Path('.scratch/开发票据/测试系统/P003-工作流整合改进/evidence/TEST-P003-001')
    allowed.append(root/suffix)
    if evidence_root:
        evidence_root = canonical(evidence_root)
        if evidence_root.parts[-len(suffix.parts):] != suffix.parts:
            raise ValueError('External evidence root must be this ticket')
        if not (evidence_root.parents[len(suffix.parts)-1]/'AGENTS.md').is_file():
            raise ValueError('External evidence workspace has no project contract')
        allowed.append(evidence_root)
    if not any(output.is_relative_to(p) and output != p for p in allowed):
        raise ValueError('Output outside registered ticket evidence/build/Temp domain')
    return output


def cli_output_domain(plan,output):
    """External calls claim a new batch; only internal adapters write children."""
    value=output_domain(output,plan['root'],plan.get('evidenceRoot'))
    bases=[Path(plan['root'])/'.scratch/开发票据/测试系统/P003-工作流整合改进/evidence/TEST-P003-001']
    if plan.get('evidenceRoot'):bases.append(Path(plan['evidenceRoot']))
    for base in bases:
        if value.is_relative_to(base) and value.parent!=base:
            raise ValueError('External output must be a new direct batch, never a child of an existing batch')
    return value


def gradle_command(plan, project, tasks, output, clean=False):
    root = Path(plan['root'])
    command = [plan['java'], '-cp', str(root/project/'gradle/wrapper/gradle-wrapper.jar'),
               'org.gradle.wrapper.GradleWrapperMain', *tasks, '--offline', '--no-daemon', '--console=plain',
               '--max-workers=1', '--continue', '-Pkotlin.compiler.execution.strategy=in-process', '-Pkotlin.incremental=false',
               '--init-script', str(root/'SimulationTest/tools/workflow_gradle.init.gradle'),
               '-Dworkflow.root='+str(root), '-Dworkflow.cache='+plan['gradleUserHome'],
               '-Dworkflow.run.dir='+str(output/'tasks')]
    if plan.get('referenceRoot'):
        command.append('-Dworkflow.reference.root='+plan['referenceRoot'])
    if clean:
        command.append('-Dworkflow.clean.id='+output.parent.name)
    return command


SUPPORT = {
    'core-pc-headless': {'adapter': 'gradle+fixed-dist+p002', 'layer': 'Windows/JDK25 real core'},
    'core-pc-ui': {'adapter': 'fixed-owned-window', 'layer': 'Windows real load/Select/Move/Stop/screenshots/normal close'},
    'core-mumu-art': {'adapter': 'fixed-motion-instrumentation', 'layer': 'MuMu ART; 12 cases / 7 planner checks'},
    'core-mumu-ui': {'adapter': 'fixed-visibility-instrumentation', 'layer': 'MuMu real Activity/input; Memory visibility only'},
    'original-mumu': {'adapter': 'existing-om08-diagnostic', 'layer': 'MuMu existing OM08 raw capture+normalization; formal O.v2 not evaluated'},
}


def read_plan(path):
    plan = json.loads(Path(path).read_text(encoding='utf-8-sig'))
    allowed = {'schemaVersion', 'root', 'inputSource', 'inputs', 'java', 'gradleUserHome', 'owner', 'targets', 'timeoutSeconds','referenceRoot','evidenceRoot','device','windows','reuseFrom','performance'}
    if set(plan)-allowed or plan.get('schemaVersion') != 1:
        raise ValueError('Unknown plan fields/schema; arbitrary command configuration is unsupported')
    for name in ('root', 'inputSource', 'java', 'gradleUserHome'):
        canonical(plan[name])
    if not Path(plan['java']).is_file():
        raise ValueError('Missing Java executable')
    if not plan.get('owner'):
        raise ValueError('Missing resource owner')
    if not plan.get('targets') or any(t not in SUPPORT for t in plan['targets']):
        raise ValueError('Unsupported targets')
    if not 1 <= plan.get('timeoutSeconds', 600) <= 1800:
        raise ValueError('Invalid bounded command timeout')
    return plan


def suite_cli_outcome(receipt):
    """Only P001/P002 suite CLI exit contracts; raw process receipt stays intact."""
    code=receipt.get('exitCode')
    status,kind={0:('passed','passed'),2:('failed','behavior-failure'),
        3:('evidence-insufficient','evidence-insufficient'),4:('execution-error','execution-error'),
        64:('blocked','input-refused')}.get(code,('execution-error','unexpected-exit'))
    if receipt.get('timedOut') or receipt.get('preservationErrors'):
        status,kind='execution-error','termination-or-preservation-error'
    return dict(status=status,kind=kind,exitCode=code)


def target_summary(targets):
    statuses=('passed','failed','evidence-insufficient','execution-error','blocked','not-executed','prepared')
    return {status:sum(r['status']==status for r in targets.values()) for status in statuses}


def run_plan(plan, output, profile='quick', clean=False,prepare_only=False):
    root = canonical(plan['root'])
    output = output_domain(output, root, plan.get('evidenceRoot'))
    guard_roots = {root}
    if plan.get('evidenceRoot'):
        guard_roots.add(Path(plan['evidenceRoot']).parents[5])
    for guard_root in guard_roots:
        guard_path=guard_root/'Decompile/tools/workspace_layout.py'
        if not guard_path.is_file():raise ValueError('Missing private layout guard: '+str(guard_path))
        guard = subprocess.run([sys.executable,'-B',str(guard_path)],
                               cwd=guard_root,capture_output=True,timeout=30,shell=False)
        if guard.returncode != 0:
            raise ValueError('Workspace layout refused before output creation: '+guard.stdout.decode('utf8',errors='replace'))
    before=source_identity(root)
    commit=subprocess.check_output(['git','rev-parse','HEAD'],cwd=root,text=True).strip()
    tool_identity={'java':{'path':plan['java'],'sha256':sha(plan['java'])},
                   'python':{'path':sys.executable,'sha256':sha(sys.executable)}}
    for guard_root in guard_roots:
        guard_path=guard_root/'Decompile/tools/workspace_layout.py'
        tool_identity['layout:'+str(guard_root)]={'path':str(guard_path),'sha256':sha(guard_path)}
    output.parent.mkdir(parents=True,exist_ok=True)
    output.mkdir()  # claim batch before any subprocess; never overwrite
    result = {'schemaVersion': 1, 'profile': profile, 'clean': clean, 'plan': plan,'prepareOnly':prepare_only,
              'startedUnix': time.time(), 'stages': [], 'firstError': None, 'preservationErrors': [],
              'targets': {name: {**meta, 'status': 'not-executed', 'reason': 'not selected' if name not in plan['targets'] else 'adapter not yet integrated'} for name, meta in SUPPORT.items()}}
    result['sourceBefore'] = before
    result['sourceCommit']=commit
    result['toolIdentity']=tool_identity
    save_json(output/'intent.json',result)
    env = dict(os.environ, GRADLE_USER_HOME=plan['gradleUserHome'])

    def step(name, argv, cwd, timeout=None):
        receipt = execute_step(argv, cwd, output/name, timeout=timeout or plan.get('timeoutSeconds', 600), env=env)
        result['stages'].append({'name': name, **receipt})
        if 'preparation' in result:
            verify(plan['inputs'], root)
        if source_identity(root) != before:
            raise ValueError('Source drift during execution')
        return receipt

    try:
        names = ['build:'+str(root/'Engine'), 'build:'+str(root/'SimulationTest')]
        with resources(names,formal=profile=='performance'):
            guard = step('layout', [sys.executable, '-B', str(root/'Decompile/tools/workspace_layout.py')], root)
            if not guard['passed']:
                raise ValueError('Workspace layout refused')
            result['preparation'] = prepare(plan['inputs'], plan['inputSource'], root)
            verify(plan['inputs'], root)
            if prepare_only:
                for project,task in [('Engine',':core:test'),('SimulationTest',':test')]:
                    name='configuration-'+project.lower()
                    attempt=output/name
                    receipt=step(name,gradle_command(plan,project,[task,'--dry-run'],attempt),root/project)
                    if not receipt['passed']:raise ValueError('Gradle effective configuration refused: '+project)
                    configured=json.loads((attempt/'tasks/effective-config.json').read_bytes())
                    if not configured['tests']:raise ValueError('Expected test configuration missing: '+project)
                for target in plan['targets']:
                    result['targets'][target].update(status='prepared',reason='input/tool/cache/effective Gradle paths checked; no test or product executed')
                return result
            unsupported = [t for t in plan['targets'] if not SUPPORT[t]['adapter']]
            for target in unsupported:
                result['targets'][target].update(status='blocked', reason='adapter not yet integrated; no target operations executed')
            if profile == 'performance':
                from workflow_device import checked,reference,require
                config=plan['performance']
                require(plan['targets']==['core-pc-headless'],'Performance cannot share a plan with platform/functional targets')
                mode=config['mode'];require(mode in ('qualify','measure'),'Unsupported performance mode')
                require(mode!='measure' or config.get('maintainerApproved') is True,'Formal measurement requires an explicit maintainer-selected idle window')
                expected=json.loads(checked(config['packageManifest']).read_bytes())
                package=Path(config['packageRoot'])
                require(tree_identity(package)==expected,'Fixed performance package identity differs')
                built=json.loads(checked(config['buildReceipt']).read_bytes())
                require(built['passed'] and built['package']==expected and built['packageAfter']==expected,'No successful fixed-package build receipt')
                result['package']=freeze_package(package,output/'package')
                protocol=checked(config['protocol'])
                require(config['protocol']['sha256']=='325039d085d02ddf9f0b89b6b5d885c7423cbe73d058ae2725bb799763863d4f','Unapproved S08 protocol')
                raw=root/'SimulationTest/build'/('workflow-perf-'+uuid.uuid4().hex)
                receipt=step('performance-command',[plan['java'],'-Xmx2g','-cp',str(output/'package/lib/*'),
                    'com.rustedwarfare.reforge.testing.perf.WholeChainPerfMain','--repo-root',str(root),
                    '--protocol',str(protocol),'--protocol-sha',config['protocol']['sha256'],'--output',str(raw),'--mode',mode],root,
                    timeout=3600 if mode=='measure' else 600)
                require(receipt['passed'],'Performance executor failed; preserve partial raw output')
                completion=json.loads((raw/'completion.json').read_bytes())
                require(completion['complete'] and completion['mode']==mode and completion['batches']==(576 if mode=='measure' else 37),'Incomplete performance coverage')
                result['rawSource']=str(raw);result['rawFiles']=freeze_package(raw,output/'perf-raw')
                report=step('performance-recompute',[sys.executable,'-B',str(root/'SimulationTest/tools/perf_report.py'),
                    str(output/'perf-raw'),str(output/'recomputed.json')],root)
                require(report['passed'],'Raw performance recomputation failed')
                require(tree_identity(output/'package')==result['package'],'Performance runtime drift')
                result['packageAfter']=tree_identity(output/'package')
                result['performance']=dict(mode=mode,batches=completion['batches'],finalPerformanceVerdict='not-evaluated',
                    qualificationOnly=mode=='qualify',recomputed=reference(output/'recomputed.json'))
                result['targets']['core-pc-headless'].update(status='passed',reason='explicit performance '+mode+'; numeric acceptance not evaluated')
            if 'core-pc-headless' in plan['targets'] and profile!='performance':
                stages = [('engine', 'Engine', [':core:test', ':client:test', ':pc:test', ':pc:installDist']),
                          ('simulation', 'SimulationTest', [':test', ':installDist'])]
                if profile == 'quick':
                    stages[0] = ('engine', 'Engine', [':core:test', '--tests', '*content.*', '--tests', '*RevisedMovementTest',
                                                     '--tests', '*ProportionalMovementTest', ':pc:installDist'])
                    stages[1] = ('simulation', 'SimulationTest', [':test', '--tests', '*DevelopmentKnowledgeSuiteTest', ':installDist'])
                for name, project, tasks in stages:
                    attempt = output/name
                    attempt.mkdir()
                    # Per-attempt task paths are allocated before Gradle configures.
                    command = gradle_command(plan, project, tasks, attempt, clean)
                    receipt = step(name+'-command', command, root/project)
                    required = [':core:test'] if name == 'engine' and profile == 'quick' else [':core:test',':client:test',':pc:test'] if name == 'engine' else [':test']
                    evidence = test_evidence(attempt/'tasks', required)
                    receipt['testEvidence'] = evidence
                    save_json(attempt/'test-evidence.json', evidence)
                    execution_file = attempt/'tasks/task-executions.json'
                    executions = json.loads(execution_file.read_text(encoding='utf-8')) if execution_file.is_file() else []
                    producer=':pc:installDist' if name=='engine' else ':installDist'
                    completed_producer=any(r['task']==producer and r['executed'] and not r['failure'] and
                        (not r['skipped'] or r['upToDate']) for r in executions)
                    if receipt.get('timedOut') or receipt['preservationErrors'] or not completed_producer:
                        raise ValueError(f'{name} producer/termination receipt incomplete; stop package chain')
                    infrastructure_failure = any(r['failure'] and r['task'] not in required for r in executions)
                    unexpected = [e for r in evidence['tasks'] for e in r.get('failuresDetail',[]) if not any(
                        marker in e['type'] for marker in ('AssertionError','AssertionFailedError','ComparisonFailure'))]
                    if not receipt['passed'] or evidence['status'] != 'passed':
                        actual_assertions=any(r.get('failuresDetail') for r in evidence['tasks'])
                        if executions and actual_assertions and not infrastructure_failure and not unexpected and evidence['tasks'] and all(r.get('endedMillis') for r in evidence['tasks']):
                            result['firstError'] = result['firstError'] or {'kind': 'behavior-failure', 'stage': name}
                        else:
                            raise ValueError(f'{name} build/test failed; downstream package/smoke not executed')
                config = json.loads((output/'simulation/tasks/effective-config.json').read_text(encoding='utf-8'))
                dist = Path(config['buildDirectories'][':'])/'install/simulation-test'
                frozen = output/'package'
                result['package'] = freeze_package(dist,frozen)
                save_json(output/'package-manifest.json', result['package'])
                engine_config=json.loads((output/'engine/tasks/effective-config.json').read_text(encoding='utf-8'))
                result['pcPackage']=freeze_package(Path(engine_config['buildDirectories'][':pc'])/'install/pc',output/'pc-package')
                save_json(output/'pc-package-manifest.json',result['pcPackage'])
                jobs = []
                for name, suite in [('host', 'P002-knowledge-host.development.center.v2.json'),
                                    ('automatic', 'P002-automatic-vision.development.center.v2.json')]:
                    jobs.append(dict(name='smoke-'+name, argv=[plan['java'], '-cp', str(frozen/'lib/*'),
                        'com.rustedwarfare.reforge.testing.p002.P002KnowledgeSuiteMainKt', '--repo-root', str(root),
                        '--suite', 'TestLab/suites/'+suite, '--output', str(output/('behavior-'+name))],
                        cwd=root, workers=1, resources=[], env=env))
                if profile=='full':
                    topic='SimulationTest/build/workflow-p001-'+uuid.uuid4().hex
                    result['p001ArchiveRoot']=str(root/topic)
                    jobs.append(dict(name='smoke-p001',argv=[plan['java'],'-cp',str(frozen/'lib/*'),
                        'com.rustedwarfare.reforge.testing.p001.P001SuiteMainKt','--repo-root',str(root),
                        '--suite','TestLab/suites/P001-self-behavior.natural.v2.json','--output',topic],
                        cwd=root,workers=1,resources=[],env=env))
                for job, receipt in zip(jobs, run_jobs(jobs, output, workers=4)):
                    result['stages'].append({'name': job['name'], **receipt, 'suiteOutcome':suite_cli_outcome(receipt)})
                    if not receipt['passed']:
                        result['firstError'] = result['firstError'] or receipt['firstError']
                verify(plan['inputs'], root)
                if source_identity(root) != before:
                    raise ValueError('Source drift during fixed-package smoke')
                if tree_identity(frozen)!=result['package'] or tree_identity(output/'pc-package')!=result['pcPackage']:
                    raise ValueError('Fixed runtime bytes drifted during smoke')
                result['packageAfter']=tree_identity(frozen)
                result['pcPackageAfter']=tree_identity(output/'pc-package')
                outcomes=[s['suiteOutcome'] for s in result['stages'] if 'suiteOutcome' in s]
                priority=('execution-error','blocked','evidence-insufficient','failed','passed')
                status=next(s for s in priority if any(r['status']==s for r in outcomes))
                if status=='passed' and result['firstError']:status='failed'
                result['targets']['core-pc-headless'].update(
                    status=status, suiteOutcomes=outcomes,reason='fresh tasks and fixed-package behavior executed')
            if 'core-pc-ui' in plan['targets']:
                from workflow_windows import run_windows
                windows_result=run_windows(plan,plan['windows'],output/'core-pc-ui')
                result['targets']['core-pc-ui'].update(status='passed' if windows_result['passed'] else 'failed',
                    reason='actual own-window input and logs; raw receipt: core-pc-ui/result.json')
                result['stages'].append(dict(name='core-pc-ui',receipt=str(output/'core-pc-ui/result.json')))
                if not windows_result['passed']:
                    result['firstError']=result['firstError'] or windows_result['firstError'] or dict(kind='window-postcheck',errors=windows_result['postErrors'])
                verify(plan['inputs'],root)
                if source_identity(root)!=before:raise ValueError('Source drift during window adapter')
            if any(t in plan['targets'] for t in ('core-mumu-art','core-mumu-ui','original-mumu')):
                from workflow_device import run_device,reference
                config=dict(plan['device'])
                for target in ('core-mumu-art','core-mumu-ui','original-mumu'):
                    if target not in plan['targets']:continue
                    device_result=run_device(plan,config,output/target,target)
                    result['targets'][target].update(status='passed' if device_result['passed'] else 'failed',
                        reason='actual current-instance instrumentation; raw receipt: '+target+'/result.json')
                    result['stages'].append(dict(name=target,result=reference(output/target/'result.json')))
                    if not device_result['passed']:
                        result['firstError']=result['firstError'] or device_result['firstError'] or dict(kind='device-postcheck',errors=device_result['postErrors'])
                    if not device_result['dataProtected'] or device_result['postErrors']:
                        raise ValueError('Device data/resource protection unresolved; affected dependent targets stopped')
                    config['baseline']=reference(output/target/'result.json')
                    result['nextDeviceBaseline']=config['baseline']
                    verify(plan['inputs'],root)
                    if source_identity(root)!=before:raise ValueError('Source drift during device adapter')
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        prior = next((s['firstError'] for s in result['stages'] if s.get('firstError')), None)
        result['firstError'] = result['firstError'] or prior or {'kind': 'precondition-or-execution', 'message': str(error)}
        result['failureContext'] = str(error)
        for target in plan['targets']:
            if result['targets'][target]['status'] == 'not-executed':
                result['targets'][target].update(status='blocked', reason=str(error))
    finally:
        try:
            result['sourceAfter']=source_identity(root)
            result['toolIdentityAfter']={name:{'path':row['path'],'sha256':sha(row['path'])} for name,row in tool_identity.items()}
            if result['sourceAfter']!=before or result['toolIdentityAfter']!=tool_identity:
                result['firstError']=result['firstError'] or dict(kind='source-or-tool-drift',message='Final source/tool identity differs')
        except OSError as error:
            result['preservationErrors'].append(str(error))
            result['firstError']=result['firstError'] or dict(kind='final-identity-read',message=str(error))
        result['endedUnix'] = time.time()
        result['passed'] = not result['firstError'] and all(result['targets'][t]['status'] == ('prepared' if prepare_only else 'passed') for t in plan['targets'])
        result['summary']=target_summary(result['targets'])
        try:
            if result.get('nextDeviceBaseline') and result['passed']:
                next_plan=dict(plan,device=dict(plan['device'],baseline=result['nextDeviceBaseline']))
                save_json(output/'next-plan.json',next_plan)
            result['artifactIndex']=evidence_index(output)
            if result.get('p001ArchiveRoot'):
                result['externalArtifactIndices']={result['p001ArchiveRoot']:tree_identity(Path(result['p001ArchiveRoot']))}
            lines = ['# Workflow result', '', f"Profile: {profile}; passed: {result['passed']}", '',
                     '| Target | Status | Reason |', '|---|---|---|']
            lines += [f"| {t} | {r['status']} | {r['reason']} |" for t, r in result['targets'].items()]
            lines += ['', 'Outcome counts: '+json.dumps(result['summary']), '', '| Suite stage | Outcome | Exit |', '|---|---|---|']
            lines += [f"| {s['name']} | {s['suiteOutcome']['kind']} | {s['suiteOutcome']['exitCode']} |" for s in result['stages'] if 'suiteOutcome' in s]
            lines += ['', 'Raw commands/logs: one directory per stage.', 'Source, input and package identities: result.json / package-manifest.json.']
            with (output/'report.md').open('x',encoding='utf-8') as stream:stream.write('\n'.join(lines)+'\n')
        except OSError as error:
            result['preservationErrors'].append(str(error)); result['passed'] = False
            result['firstError'] = result['firstError'] or {'kind': 'receipt-write', 'message': str(error)}
        preserve_result(output/'result.json',result)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('operation', choices=['prepare', 'run', 'reuse', 'device-inspect', 'collect','launch'])
    parser.add_argument('--plan', required=True)
    parser.add_argument('--output', help='New batch; omitted generates a unique name in the registered evidence root')
    parser.add_argument('--target',action='append',choices=sorted(SUPPORT),help='Select one or more existing target adapters')
    parser.add_argument('--profile', choices=['quick', 'full', 'performance'], default='quick')
    parser.add_argument('--clean', action='store_true', help='Use fresh build trees; preserve all old outputs')
    args = parser.parse_args()
    try:
        plan = read_plan(args.plan)
        if args.target:plan=dict(plan,targets=args.target)
        if not args.output:
            parent=Path(plan.get('evidenceRoot',str(Path(plan['root'])/'SimulationTest/build')))
            args.output=str(parent/('workflow-'+args.operation+'-'+uuid.uuid4().hex+('.json' if args.operation=='reuse' else '')))
        args.output=str(cli_output_domain(plan,args.output))
        if args.operation in ('run','collect','launch','prepare'):
            result = run_plan(plan, args.output, args.profile, args.clean,prepare_only=args.operation=='prepare')
            print(json.dumps({'passed': result['passed'], 'prepareOnly':result['prepareOnly'],'sourceCommit':result['sourceCommit'],'firstError': result['firstError'], 'output': args.output}))
            return 0 if result['passed'] else 4
        if args.operation=='device-inspect':
            for guard_root in {Path(plan['root']),Path(plan['evidenceRoot']).parents[5] if plan.get('evidenceRoot') else Path(plan['root'])}:
                guard=subprocess.run([sys.executable,'-B',str(guard_root/'Decompile/tools/workspace_layout.py')],cwd=guard_root,capture_output=True,timeout=30,shell=False)
                if guard.returncode:raise ValueError('Layout refused before device inspect output')
            from workflow_device import run_device
            value=run_device(plan,plan['device'],args.output,'device-inspect')
            print(json.dumps({'passed':value['passed'],'firstError':value['firstError'],'postErrors':value['postErrors']}))
            return 0 if value['passed'] else 4
        output = output_domain(args.output,plan['root'],plan.get('evidenceRoot'))
        if output.exists():
            raise ValueError('Output conflict')
        for guard_root in {Path(plan['root']),Path(plan['evidenceRoot']).parents[5] if plan.get('evidenceRoot') else Path(plan['root'])}:
            guard=subprocess.run([sys.executable,'-B',str(guard_root/'Decompile/tools/workspace_layout.py')],cwd=guard_root,capture_output=True,timeout=30,shell=False)
            if guard.returncode:raise ValueError('Layout refused before prepare/reuse output')
        if args.operation == 'prepare':
            value = prepare(plan['inputs'], plan['inputSource'], plan['root'])
        else:
            from workflow_reuse import explain_reuse
            value=explain_reuse(plan,plan['reuseFrom'],args.profile)
        save_json(output, value)
        print(json.dumps(value, ensure_ascii=True))
        return 0
    except (OSError, ValueError, KeyError) as error:
        print(f'{type(error).__name__}: {error}', file=sys.stderr)
        return 64


if __name__ == '__main__':
    raise SystemExit(main())
