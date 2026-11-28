"""Bound independent headless scenarios; preserve every started item's terminal state."""
from collections import deque
from concurrent.futures import ThreadPoolExecutor, wait, FIRST_COMPLETED
import hashlib
import heapq
import itertools
import json
import math
import os
from pathlib import Path
import queue
import re
import signal
import subprocess
import sys
import threading
import time
import uuid


MAX_TASKS_PER_WORKER = 25


def session_timeout_seconds(case, timeout):
    cap = 300 if case.get('kind') == 'long' else 180
    headroom = int(timeout) - 30 if timeout > 60 else int(math.ceil(timeout))
    return max(1, min(cap, headroom))


def core_long_timeout_ms(case, timeout):
    if case.get('kind') != 'long' or timeout <= 120:
        return None
    headroom = int(timeout) - 30 if timeout > 150 else int(math.ceil(timeout))
    return max(120, min(600, headroom)) * 1000


def _resolve_java_executable():
    java_home = os.environ.get('JAVA_HOME')
    if java_home:
        candidate = Path(java_home.strip('"')) / 'bin' / ('java.exe' if os.name == 'nt' else 'java')
        if candidate.is_file():
            return str(candidate)
    return 'java'


def _sha256_file(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def _save_json_exclusive(path, payload):
    with Path(path).open('x', encoding='utf-8') as stream:
        json.dump(payload, stream, ensure_ascii=False, indent=2)


def _kill_process_tree(proc):
    errors = []
    term_code = None
    if proc.poll() is None:
        try:
            if os.name == 'nt':
                killed = subprocess.run(
                    [str(Path(os.environ.get('SystemRoot', r'C:\Windows')) / 'System32/taskkill.exe'),
                     '/PID', str(proc.pid), '/T', '/F'],
                    capture_output=True, timeout=30,
                )
                term_code = killed.returncode
                if killed.returncode and proc.poll() is None:
                    errors.append('Owned process termination returned ' + str(killed.returncode))
            else:
                os.killpg(proc.pid, signal.SIGKILL)
        except (OSError, subprocess.SubprocessError) as cleanup:
            errors.append('Owned process termination: ' + str(cleanup))
    try:
        proc.wait(timeout=30)
    except subprocess.TimeoutExpired:
        errors.append('Owned process did not terminate')
    return term_code, errors


def _load_lpt_weights(repo):
    timings_file = Path(repo) / 'SimulationTest/ci/public_scenario_timings.json'
    if not timings_file.is_file():
        return {}, {}
    try:
        doc = json.loads(timings_file.read_text(encoding='utf-8'))
        secs = doc.get('seconds') or {}
        return dict(secs.get('core') or {}), dict(secs.get('session') or {})
    except (OSError, ValueError, TypeError):
        return {}, {}


class _JsonLineSubprocessHandle:
    def __init__(self, argv, cwd, generation):
        options = (
            {'creationflags': subprocess.CREATE_NEW_PROCESS_GROUP | subprocess.CREATE_NO_WINDOW}
            if os.name == 'nt' else {'start_new_session': True}
        )
        self.generation = generation
        self.tasks_completed = 0
        self.responses = queue.Queue()
        self.stderr_tail = deque(maxlen=64)
        self.proc = subprocess.Popen(
            argv, cwd=cwd, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            text=True, encoding='utf-8', errors='replace', bufsize=1, shell=False, **options,
        )
        self.pid = self.proc.pid
        self._stdout_thread = threading.Thread(target=self._read_stdout, daemon=True)
        self._stderr_thread = threading.Thread(target=self._read_stderr, daemon=True)
        self._stdout_thread.start()
        self._stderr_thread.start()

    def _read_stdout(self):
        try:
            for line in self.proc.stdout:
                stripped = line.strip()
                if stripped:
                    self.responses.put(stripped)
        except (OSError, ValueError):
            pass
        finally:
            self.responses.put(None)

    def _read_stderr(self):
        try:
            for line in self.proc.stderr:
                self.stderr_tail.append(line.rstrip('\r\n'))
        except (OSError, ValueError):
            pass

    def close_gracefully(self):
        if self.proc.poll() is None:
            try:
                if self.proc.stdin and not self.proc.stdin.closed:
                    self.proc.stdin.close()
            except OSError:
                pass
            try:
                self.proc.wait(timeout=10)
            except subprocess.TimeoutExpired:
                _kill_process_tree(self.proc)
        for pipe in (self.proc.stdin, self.proc.stdout, self.proc.stderr):
            if pipe is not None:
                try:
                    pipe.close()
                except OSError:
                    pass

    def kill_now(self):
        term_code, errors = _kill_process_tree(self.proc)
        for pipe in (self.proc.stdin, self.proc.stdout, self.proc.stderr):
            if pipe is not None:
                try:
                    pipe.close()
                except OSError:
                    pass
        return term_code, errors


class _WorkerSlot:
    def __init__(self, slot_id, jvm_argv, py_eval_argv, cwd, max_tasks_per_worker):
        self.slot_id = slot_id
        self.jvm_argv = jvm_argv
        self.py_eval_argv = py_eval_argv
        self.cwd = cwd
        self.max_tasks_per_worker = max_tasks_per_worker
        self.generation = 0
        self.handle = None
        self.py_handle = None
        self.py_generation = 0

    def ensure_handle(self):
        if self.handle is not None and self.handle.proc.poll() is not None:
            self.handle.close_gracefully()
            self.handle = None
        if self.handle is None:
            self.generation += 1
            self.handle = _JsonLineSubprocessHandle(self.jvm_argv, self.cwd, self.generation)
        return self.handle

    def ensure_py_handle(self):
        if self.py_handle is not None and self.py_handle.proc.poll() is not None:
            self.py_handle.close_gracefully()
            self.py_handle = None
        if self.py_handle is None:
            self.py_generation += 1
            self.py_handle = _JsonLineSubprocessHandle(self.py_eval_argv, self.cwd, self.py_generation)
        return self.py_handle

    def rotate_if_needed(self):
        if self.handle is not None and self.handle.tasks_completed >= self.max_tasks_per_worker:
            self.handle.close_gracefully()
            self.handle = None
        if self.py_handle is not None and self.py_handle.tasks_completed >= self.max_tasks_per_worker:
            self.py_handle.close_gracefully()
            self.py_handle = None

    def close(self):
        if self.handle is not None:
            self.handle.close_gracefully()
            self.handle = None
        if self.py_handle is not None:
            self.py_handle.close_gracefully()
            self.py_handle = None


def _exchange_json_line(handle, req, timeout):
    req_id = req['id']
    try:
        handle.proc.stdin.write(json.dumps(req, ensure_ascii=True) + '\n')
        handle.proc.stdin.flush()
    except OSError as write_err:
        handle.kill_now()
        return 'broken', dict(error=f'{type(write_err).__name__}: {write_err}', preservationErrors=[])
    deadline = time.monotonic() + timeout
    while True:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            term_code, kill_errors = handle.kill_now()
            return 'timeout', dict(terminationExitCode=term_code, preservationErrors=kill_errors)
        try:
            raw = handle.responses.get(timeout=remaining)
        except queue.Empty:
            continue
        if raw is None:
            rc = handle.proc.poll()
            if rc is None:
                try:
                    rc = handle.proc.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    handle.kill_now()
                    rc = 125
            tail = '\n'.join(handle.stderr_tail)
            handle.close_gracefully()
            return 'eof', dict(exitCode=rc if rc not in (None, 0) else 125,
                               error=tail or 'Batch worker terminated unexpectedly', preservationErrors=[])
        resp = json.loads(raw)
        if resp.get('id') != req_id:
            continue
        handle.tasks_completed += 1
        return 'ok', resp


class PersistentScenarioWorkerPool:
    """Bounded pool of persistent JVM batch workers + GIL-isolated Python session evaluators."""

    def __init__(self, repo, distribution, workers, max_tasks_per_worker=MAX_TASKS_PER_WORKER):
        if max_tasks_per_worker < 1:
            raise ValueError('max_tasks_per_worker must be positive')
        self.repo = Path(repo).resolve()
        self.project = self.repo / 'SimulationTest'
        self.distribution = Path(distribution).resolve()
        self.workers = workers
        self.max_tasks_per_worker = max_tasks_per_worker
        self.java_bin = _resolve_java_executable()
        lib_wildcard = str(self.distribution / 'lib' / '*')
        if os.name == 'nt' and not lib_wildcard.startswith('\\\\?\\'):
            lib_wildcard = '\\\\?\\' + lib_wildcard
        self.classpath = lib_wildcard
        self.jvm_argv = [
            self.java_bin,
            '-Xmx512m',
            '-XX:MaxMetaspaceSize=128m',
            '-XX:ReservedCodeCacheSize=64m',
            '-XX:MaxDirectMemorySize=32m',
            '-XX:MaxHeapFreeRatio=35',
            '-XX:MinHeapFreeRatio=15',
            '-Dfile.encoding=UTF-8',
            '-Dstdout.encoding=UTF-8',
            '-Dstderr.encoding=UTF-8',
            '-Drepo.root=' + str(self.repo),
            '-cp', self.classpath,
            'com.rustedwarfare.reforge.testing.MainKt',
            'batch-worker',
        ]
        self.py_eval_argv = [
            sys.executable, '-B', str(self.project / 'tools/platform_scenario.py'),
            '--session-eval-worker',
        ]
        jars = sorted((self.distribution / 'lib').glob('*.jar'))
        self.precomputed_package_files = [dict(path=p.name, sha256=_sha256_file(p)) for p in jars]
        comp_file = self.project / 'tools/platform_scenario.py'
        self.precomputed_comparator_sha256 = _sha256_file(comp_file) if comp_file.is_file() else None
        self._slots = [
            _WorkerSlot(i, self.jvm_argv, self.py_eval_argv, self.project, max_tasks_per_worker)
            for i in range(workers)
        ]
        self._slot_lock = threading.Lock()
        self._free_slots = deque(self._slots)
        self._waiters = []
        self._waiter_seq = itertools.count()
        self._closed = False
        self._close_lock = threading.Lock()

    def _acquire_slot(self, priority=0.0):
        with self._slot_lock:
            if self._free_slots and not self._waiters:
                return self._free_slots.popleft()
            evt = threading.Event()
            holder = [None]
            heapq.heappush(self._waiters, (-float(priority), next(self._waiter_seq), evt, holder))
        evt.wait()
        return holder[0]

    def _release_slot(self, slot):
        with self._slot_lock:
            if self._waiters:
                _, _, evt, holder = heapq.heappop(self._waiters)
                holder[0] = slot
                evt.set()
            else:
                self._free_slots.append(slot)

    def __enter__(self):
        return self

    def __exit__(self, *args):
        self.close()

    def close(self):
        with self._close_lock:
            if self._closed:
                return
            self._closed = True
            for slot in self._slots:
                slot.close()

    def _dispatch_jvm_on_slot(self, slot, payload, timeout):
        req_id = uuid.uuid4().hex
        req = dict(payload, id=req_id)
        handle = slot.ensure_handle()
        pid = handle.pid
        gen = handle.generation
        status, info = _exchange_json_line(handle, req, timeout)
        if status == 'broken':
            slot.handle = None
            return dict(
                exitCode=125, timedOut=False, pid=pid, workerGeneration=gen, workerSlot=slot.slot_id,
                error=info['error'], preservationErrors=info['preservationErrors'],
            )
        if status == 'timeout':
            slot.handle = None
            out = dict(
                exitCode=124, timedOut=True, pid=pid, workerGeneration=gen, workerSlot=slot.slot_id,
                firstError={'kind': 'timeout', 'exitCode': 124},
                preservationErrors=info['preservationErrors'],
            )
            if info.get('terminationExitCode') is not None:
                out['terminationExitCode'] = info['terminationExitCode']
            return out
        if status == 'eof':
            slot.handle = None
            return dict(
                exitCode=info['exitCode'], timedOut=False, pid=pid, workerGeneration=gen, workerSlot=slot.slot_id,
                error=info['error'], preservationErrors=info['preservationErrors'],
            )
        slot.rotate_if_needed()
        return dict(
            exitCode=int(info.get('exitCode', 1)),
            timedOut=False,
            pid=pid,
            workerGeneration=gen,
            workerSlot=slot.slot_id,
            response=info,
            error=info.get('message') if not info.get('ok', False) else None,
            preservationErrors=[],
        )

    def dispatch_raw(self, payload, timeout, *, priority=0.0):
        if timeout <= 0:
            raise ValueError('Timeout must be positive')
        slot = self._acquire_slot(priority)
        try:
            return self._dispatch_jvm_on_slot(slot, payload, timeout)
        finally:
            self._release_slot(slot)

    def execute_core_step(self, output, name, logical_argv, payload, timeout, *, priority=0.0):
        folder = (Path(output) / (name + '-process')).resolve()
        folder.mkdir()
        stdout_path = folder / 'stdout.log'
        stderr_path = folder / 'stderr.log'
        started_unix = time.time()
        argv = [str(a) for a in logical_argv]
        result = dict(
            argv=argv, cwd=str(self.project), expectedExitCode=0, timeoutSeconds=timeout,
            startedUnix=started_unix, exitCode=None, firstError=None, preservationErrors=[],
            batchWorker=True,
        )
        _save_json_exclusive(folder / 'start.json', result)
        outcome = self.dispatch_raw(
            dict(payload, stdoutLog=str(stdout_path), stderrLog=str(stderr_path)),
            timeout=timeout,
            priority=priority,
        )
        for stream_path in (stdout_path, stderr_path):
            if not stream_path.exists():
                stream_path.write_bytes(b'')
        if outcome.get('error') and stderr_path.stat().st_size == 0:
            stderr_path.write_text(str(outcome['error']) + '\n', encoding='utf-8')
        result['pid'] = outcome.get('pid')
        result['workerGeneration'] = outcome.get('workerGeneration')
        result['workerSlot'] = outcome.get('workerSlot')
        result['exitCode'] = outcome['exitCode']
        if outcome.get('timedOut'):
            result['timedOut'] = True
        if 'terminationExitCode' in outcome:
            result['terminationExitCode'] = outcome['terminationExitCode']
        result['preservationErrors'].extend(outcome.get('preservationErrors', []))
        ended_unix = time.time()
        passed = (result['exitCode'] == 0 and not result.get('timedOut', False) and not result['preservationErrors'])
        result.update(endedUnix=ended_unix, elapsedSeconds=ended_unix - started_unix, passed=passed)
        if not passed:
            result['firstError'] = outcome.get('firstError') or {
                'kind': 'timeout' if result.get('timedOut') else 'process-exit',
                'exitCode': result['exitCode'],
                'message': outcome.get('error'),
            }
        _save_json_exclusive(folder / 'result.json', result)
        with (Path(output) / (name + '.log')).open('xb') as combined:
            for stream_path in (stdout_path, stderr_path):
                combined.write(stream_path.read_bytes())
        return result

    def execute_session_step(self, output, name, logical_argv, scenario_path, contract_path, sess_timeout, outer_timeout,
                             *, priority=0.0):
        sys.path.insert(0, str(self.project / 'tools'))
        import platform_scenario

        folder = (Path(output) / (name + '-process')).resolve()
        folder.mkdir()
        stdout_path = folder / 'stdout.log'
        stderr_path = folder / 'stderr.log'
        started_unix = time.time()
        argv = [str(a) for a in logical_argv]
        result = dict(
            argv=argv, cwd=str(self.project), expectedExitCode=0, timeoutSeconds=outer_timeout,
            startedUnix=started_unix, exitCode=None, firstError=None, preservationErrors=[],
            batchWorker=True,
        )
        _save_json_exclusive(folder / 'start.json', result)
        worker_meta = {}

        slot = self._acquire_slot(priority)
        try:
            run_dir = Path(output) / name
            ctx = platform_scenario.prepare_session_workspace(
                scenario_path, contract_path, self.distribution, run_dir,
                java=self.java_bin, timeout=sess_timeout, read_only_inputs=True,
                precomputed_package_files=self.precomputed_package_files,
                precomputed_comparator_sha256=self.precomputed_comparator_sha256,
            )
            inner_stdout = run_dir / 'stdout.log'
            inner_stderr = run_dir / 'stderr.log'
            exec_started = time.time()
            jvm_budget = min(float(sess_timeout), float(outer_timeout))
            outcome = self._dispatch_jvm_on_slot(
                slot,
                dict(
                    cmd='session-run',
                    inputs=ctx['runnerInputRoot'],
                    plan=ctx['scenarioJson'],
                    output=ctx['sessionDir'],
                    loadTimeoutSeconds=int(sess_timeout),
                    stdoutLog=str(inner_stdout),
                    stderrLog=str(inner_stderr),
                ),
                timeout=jvm_budget,
            )
            exec_ended = time.time()
            worker_meta.update(outcome)
            if outcome.get('error') and (not inner_stderr.exists() or inner_stderr.stat().st_size == 0):
                inner_stderr.write_text(str(outcome['error']) + '\n', encoding='utf-8')

            # Finalize & compare inside this slot's dedicated Python subprocess (true multi-core, zero GIL contention)
            remaining_outer = max(5.0, float(outer_timeout) - (time.time() - started_unix))
            py_handle = slot.ensure_py_handle()
            py_status, py_info = _exchange_json_line(
                py_handle,
                dict(
                    id=uuid.uuid4().hex,
                    ctx=ctx,
                    execStartedUnix=exec_started,
                    execEndedUnix=exec_ended,
                    execRc=outcome['exitCode'],
                    timedOut=bool(outcome.get('timedOut', False)),
                ),
                timeout=remaining_outer,
            )
            if py_status == 'ok' and py_info.get('ok'):
                slot.rotate_if_needed()
                sess_result = py_info['result']
                stdout_path.write_text(json.dumps(sess_result, ensure_ascii=True, allow_nan=False) + '\n', encoding='utf-8')
                stderr_path.write_bytes(b'')
                if outcome.get('timedOut'):
                    exit_code = 124
                    result['timedOut'] = True
                else:
                    exit_code = int(sess_result.get('exitCode', 3))
                err_msg = sess_result.get('message')
            elif py_status == 'timeout':
                slot.py_handle = None
                result['timedOut'] = True
                result['preservationErrors'].extend(py_info.get('preservationErrors', []))
                stdout_path.write_bytes(b'')
                stderr_path.write_text('Session archive evaluation timed out\n', encoding='utf-8')
                exit_code = 124
                err_msg = 'Session archive evaluation timed out'
            else:
                slot.py_handle = None
                err_msg = py_info.get('error') or 'Session archive evaluation failed'
                stdout_path.write_bytes(b'')
                stderr_path.write_text(str(err_msg) + '\n', encoding='utf-8')
                exit_code = 3
        except platform_scenario.PlatformError as error:
            err_payload = dict(exitCode=error.exit_code, category=error.category, message=str(error))
            stdout_path.write_text(json.dumps(err_payload, ensure_ascii=True, allow_nan=False) + '\n', encoding='utf-8')
            stderr_path.write_bytes(b'')
            exit_code = error.exit_code
            err_msg = str(error)
        except Exception as error:
            stdout_path.write_bytes(b'')
            stderr_path.write_text(f'{type(error).__name__}: {error}\n', encoding='utf-8')
            exit_code = 3
            err_msg = f'{type(error).__name__}: {error}'
        finally:
            self._release_slot(slot)

        if 'pid' in worker_meta:
            result['pid'] = worker_meta['pid']
            result['workerGeneration'] = worker_meta.get('workerGeneration')
            result['workerSlot'] = worker_meta.get('workerSlot')
        if 'terminationExitCode' in worker_meta:
            result['terminationExitCode'] = worker_meta['terminationExitCode']
        result['preservationErrors'].extend(worker_meta.get('preservationErrors', []))
        result['exitCode'] = exit_code
        ended_unix = time.time()
        passed = (exit_code == 0 and not result.get('timedOut', False) and not result['preservationErrors'])
        result.update(endedUnix=ended_unix, elapsedSeconds=ended_unix - started_unix, passed=passed)
        if not passed:
            result['firstError'] = worker_meta.get('firstError') or {
                'kind': 'timeout' if result.get('timedOut') else 'process-exit',
                'exitCode': exit_code,
                'message': err_msg,
            }
        _save_json_exclusive(folder / 'result.json', result)
        with (Path(output) / (name + '.log')).open('xb') as combined:
            for stream_path in (stdout_path, stderr_path):
                combined.write(stream_path.read_bytes())
        return result


def _can_use_persistent_pool(distribution, execute_command):
    if distribution is None:
        return False
    jar = Path(distribution) / 'lib' / 'simulation-test.jar'
    if not jar.is_file():
        return False
    return (
        getattr(execute_command, '__name__', '') == 'execute_command'
        and getattr(execute_command, '__module__', '') == 'public_check'
    )


def _estimate_case_weight(case, core_w, sess_w):
    cid = case['id']
    bk = case.get('backend', 'core')
    if bk == 'session':
        if case.get('kind') == 'long':
            return 48.0
        if cid.startswith('phase-six-'):
            return 28.0
        if 'land-forms' in cid:
            return 20.0
        if cid in sess_w:
            return max(4.0, float(sess_w[cid]) - 31.0)
        return 8.0
    if case.get('kind') == 'long':
        if cid.startswith('phase-five-'):
            return 75.0
        if cid == 'maintenance-defense-chain':
            return 62.0
        return 15.0
    base = max(1.0, float(core_w[cid]) - 15.0) if cid in core_w else 2.5
    if case.get('recompare'):
        base += 2.0
    if case.get('archiveAssertions'):
        base += 18.0
    return base


def _compute_dispatch_order(repo, cases, workers, use_lpt):
    indices = list(range(len(cases)))
    core_w, sess_w = _load_lpt_weights(repo)
    weights = [_estimate_case_weight(c, core_w, sess_w) for c in cases]
    if not use_lpt or len(cases) <= workers:
        return indices, weights
    indices.sort(key=lambda idx: (-weights[idx], idx))
    return indices, weights


def run_scenarios(repo, output, cases, distribution, workers, timeout, admit, execute_command, *,
                  use_worker_pool=None, max_tasks_per_worker=MAX_TASKS_PER_WORKER,
                  shared_worker_pool=None):
    started = time.monotonic()
    rows = [dict(id=c['id'], kind=c['kind'], status='not-started', commands=[]) for c in cases]
    result = dict(schemaVersion=1, status='execution-failed', requestedWorkers=workers,
                  peakRunning=0, items=rows, firstError=None)
    lock, stopped = threading.Lock(), threading.Event()
    active = 0

    def finish():
        result['elapsedSeconds'] = time.monotonic() - started
        (output/'scenario-execution.json').write_text(json.dumps(result, ensure_ascii=True, indent=2), encoding='utf8')
        return result

    def fail(index, error, first_error=None):
        with lock:
            rows[index]['error'] = str(error)
            if result['firstError'] is None:
                result['firstError'] = dict(id=cases[index]['id'], error=str(error),
                    cause=first_error or dict(kind='scenario-validation', message=str(error)))
            stopped.set()

    try:
        if workers not in (1, 2, 4, 6, 8):
            raise ValueError('Scenario workers must be 1, 2, 4, 6 or 8')
        names = []
        for case in cases:
            name = case['id']
            if not re.fullmatch(r'[a-z0-9][a-z0-9-]*', name):
                raise ValueError('Invalid scenario output name')
            commands = [name] + ([name+'-recompare'] if case.get('recompare') else [])
            names += [name] + [n+suffix for n in commands for suffix in ('.log', '-process')]
            if case.get('recompare'):
                names.append(name+'-comparison')
            if case.get('archiveAssertions'):
                names += [name+'-assertions', name+'-assertions.log', name+'-assertions-process']
        if len(set(n.casefold() for n in names)) != len(names) or any((output/n).exists() for n in names):
            raise ValueError('Scenario outputs conflict or already exist')
    except Exception as error:
        result['firstError'] = dict(error=str(error), cause=dict(kind='scenario-preflight', message=str(error)))
        return finish()

    project = repo/'SimulationTest'
    launcher = distribution/'bin'/('simulation-test.bat' if os.name == 'nt' else 'simulation-test')
    cli = [str(launcher)] if os.name == 'nt' else ['sh', str(launcher)]
    owns_pool = False
    if shared_worker_pool is not None:
        worker_pool = shared_worker_pool
    else:
        enable_pool = _can_use_persistent_pool(distribution, execute_command) if use_worker_pool is None else bool(use_worker_pool)
        if enable_pool and cases:
            worker_pool = PersistentScenarioWorkerPool(repo, distribution, workers, max_tasks_per_worker=max_tasks_per_worker)
            owns_pool = True
        else:
            worker_pool = None
    dispatch_order, case_weights = _compute_dispatch_order(repo, cases, workers, use_lpt=(worker_pool is not None))

    def work(index):
        nonlocal active
        case, row = cases[index], rows[index]
        prio = case_weights[index]
        with lock:
            if stopped.is_set():
                return
            active += 1
            result['peakRunning'] = max(result['peakRunning'], active)
            row.update(status='running', startedUnix=time.time())
        cause = None
        archive_seconds = 0

        def record_measured(name, measured):
            nonlocal cause
            row['commands'].append(dict(
                name=name, argv=measured['argv'], exitCode=measured['exitCode'],
                elapsedSeconds=measured['elapsedSeconds'], receipt=name+'-process/result.json',
                startedUnix=measured['startedUnix'], endedUnix=measured['endedUnix'], firstError=measured['firstError'],
            ))
            if not measured['passed']:
                cause = measured['firstError']
                raise RuntimeError(name+': scenario command failed')

        def command(name, argv, *, batch_payload=None, session_spec=None):
            admit(case)
            if worker_pool is not None and session_spec is not None:
                measured = worker_pool.execute_session_step(
                    output, name, argv, session_spec['scenario'], session_spec['contract'],
                    session_spec['sessionTimeout'], timeout, priority=prio,
                )
            elif worker_pool is not None and batch_payload is not None:
                measured = worker_pool.execute_core_step(output, name, argv, batch_payload, timeout, priority=prio)
            else:
                measured = execute_command(output, name, argv, project, timeout)
            record_measured(name, measured)
            admit(case)

        try:
            folder = output/case['id']
            scenario_file = project/'public'/case['scenario']
            contract_file = project/'public'/case['contract']
            if case.get('backend') == 'session':
                sess_timeout = session_timeout_seconds(case, timeout)
                session_argv = [
                    sys.executable, '-B', str(project/'tools/platform_scenario.py'),
                    'session', '--scenario', str(scenario_file),
                    '--contract', str(contract_file), '--distribution', str(distribution),
                    '--timeout', str(sess_timeout), '--read-only-inputs', '--output', str(folder),
                ]
                command(
                    case['id'], session_argv,
                    session_spec=dict(scenario=scenario_file, contract=contract_file, sessionTimeout=sess_timeout),
                )
                archive_started = time.monotonic()
                judgment = json.loads((folder/'result.json').read_text(encoding='utf8'))
                archive_seconds = time.monotonic() - archive_started
                if judgment['exitCode'] != 0 or judgment['status'] != 'passed':
                    raise RuntimeError('Session evidence is not complete/pass: '+case['id'])
                archive = dict(id=case['id'], kind=case['kind'], backend='session', path=str(folder))
            else:
                core_timeout = core_long_timeout_ms(case, timeout)
                run_argv = cli + ['run', '--scenario', str(scenario_file),
                                  '--contract', str(contract_file), '--output', str(folder), '--mode', 'self']
                batch_req = dict(
                    cmd='core-run', scenario=str(scenario_file), contract=str(contract_file),
                    output=str(folder),
                )
                if core_timeout is not None:
                    run_argv += ['--timeout-ms', str(core_timeout)]
                    batch_req['timeoutMs'] = core_timeout
                command(
                    case['id'], run_argv,
                    batch_payload=batch_req,
                )
                archive_started = time.monotonic()
                batches = list(folder.glob('run-*'))
                if len(batches) != 1:
                    raise RuntimeError('Expected exactly one scenario-run archive')
                judgment = json.loads((batches[0]/'comparison/result.json').read_text(encoding='utf8'))
                manifest = json.loads((batches[0]/'manifest.json').read_text(encoding='utf8'))
                archive_seconds = time.monotonic() - archive_started
                if judgment['exitCode'] != 0 or not manifest['complete']:
                    raise RuntimeError('Scenario evidence is not complete/pass: '+case['id'])
                if case.get('recompare'):
                    comp_out = output/(case['id']+'-comparison')
                    comp_argv = cli + ['compare', '--scenario', str(scenario_file),
                                       '--contract', str(contract_file), '--actual', str(batches[0]),
                                       '--output', str(comp_out), '--mode', 'self']
                    command(
                        case['id']+'-recompare', comp_argv,
                        batch_payload=dict(
                            cmd='core-compare', scenario=str(scenario_file), contract=str(contract_file),
                            actual=str(batches[0]), output=str(comp_out),
                        ),
                    )
                if case.get('archiveAssertions'):
                    classpath = str(distribution/'lib'/'*')
                    if os.name == 'nt':
                        classpath = '\\\\?\\'+classpath
                    java = _resolve_java_executable()
                    assertions_out = output/(case['id']+'-assertions')
                    assertions_argv = [
                        java, '-Xmx512m', '-cp', classpath,
                        'com.rustedwarfare.reforge.testing.PublicArchiveAssertionsKt', case['archiveAssertions'],
                        str(scenario_file), str(contract_file), str(batches[0]), str(assertions_out),
                    ]
                    command(
                        case['id']+'-assertions', assertions_argv,
                        batch_payload=dict(
                            cmd='core-assertions', family=case['archiveAssertions'],
                            scenario=str(scenario_file), contract=str(contract_file),
                            archive=str(batches[0]), output=str(assertions_out),
                        ),
                    )
                archive = dict(id=case['id'], kind=case['kind'], path=str(batches[0]))
            row.update(status='passed', archive=archive)
        except Exception as error:
            row['status'] = 'failed'
            fail(index, error, cause)
        finally:
            with lock:
                row['endedUnix'] = time.time()
                row['archiveSeconds'] = archive_seconds
                active -= 1

    try:
        cursor, running = 0, {}
        with ThreadPoolExecutor(max_workers=workers) as pool:
            while running or (cursor < len(dispatch_order) and not stopped.is_set()):
                while cursor < len(dispatch_order) and len(running) < workers and not stopped.is_set():
                    target_index = dispatch_order[cursor]
                    try:
                        admit(cases[target_index])
                    except Exception as error:
                        fail(target_index, error)
                        break
                    with lock:
                        if stopped.is_set():
                            break
                        running[pool.submit(work, target_index)] = target_index
                        cursor += 1
                if running:
                    done, _ = wait(running, return_when=FIRST_COMPLETED)
                    for future in done:
                        future.result()
                        del running[future]
    finally:
        if owns_pool and worker_pool is not None:
            worker_pool.close()
    result['status'] = 'passed' if all(r['status'] == 'passed' for r in rows) else 'execution-failed'
    return finish()
