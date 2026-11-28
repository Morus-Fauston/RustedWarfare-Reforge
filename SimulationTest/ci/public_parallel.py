"""Freeze and check one public CI plan across independent workspaces."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
import math
import os
from pathlib import Path
import platform
import re
import shutil
from statistics import median
import subprocess
import tarfile
import tempfile
import time
from urllib.error import HTTPError
from urllib.parse import urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener, urlopen
import zipfile

from development_check import plan_mechanisms, resources, run_development
from public_batch import plan_layer, verify_mechanism_receipt
from public_check import (
    REPO, build_shared_distribution, execute_command, identities, verify_scenario_sources,
)
from public_distribution import digest, package_files, toolchain_identity
from public_scenarios import run_scenarios


MECHANISMS = ('mechanisms-1', 'mechanisms-2', 'mechanisms-3', 'mechanisms-4')
PRODUCTION = ('production-1', 'production-2', 'production-3')
SCENARIOS = (*PRODUCTION, 'other-short', 'content-three-formats')
LONG_SHARDS = ('long-1', 'long-2', 'long-3')
COLD_BUILD_TIMEOUT = 900
COMMAND_TIMEOUT = 600
LONG_COMMAND_TIMEOUT = 900
FORKS = 4
WORKERS = 4


def is_mechanism_shard(shard):
    return shard == 'mechanisms' or shard in MECHANISMS


def mechanism_plan_for_shard(bundle, shard):
    if shard in MECHANISMS and 'mechanismShards' in bundle:
        return bundle['mechanismShards'][shard]
    return bundle['mechanisms']


def allocate_mechanisms(mechanisms):
    selectors = {
        'mechanisms-1': lambda mod, classes: classes if mod == 'core' else [],
        'mechanisms-2': lambda mod, classes: classes if mod in ('client', 'pc') else [],
        'mechanisms-3': lambda mod, classes: classes[0::2] if mod == 'tools' else [],
        'mechanisms-4': lambda mod, classes: classes[1::2] if mod == 'tools' else [],
    }
    known_modules = {'core', 'client', 'pc', 'tools'}
    for group in mechanisms['groups']:
        unknown = set(group['tests']) - known_modules
        if unknown:
            raise ValueError('Unassigned mechanism module(s): ' + ', '.join(sorted(unknown)))
    shards = {}
    assigned_pairs = []
    for shard in MECHANISMS:
        select = selectors[shard]
        groups = []
        for group in mechanisms['groups']:
            sub_tests = {}
            sub_owners = {}
            claimed_suites = set()
            for mod, classes in group['tests'].items():
                picked = list(select(mod, classes))
                if picked:
                    sub_tests[mod] = picked
                    sub_owners[mod] = {cls: list(group['owners'][mod][cls]) for cls in picked}
                    for cls in picked:
                        claimed_suites.update(group['owners'][mod][cls])
                        assigned_pairs.append((group['fixture'], mod, cls))
            if sub_tests:
                groups.append(dict(
                    fixture=group['fixture'],
                    tests=sub_tests,
                    suites=[s for s in group['suites'] if s in claimed_suites],
                    owners=sub_owners,
                ))
        if not groups:
            raise ValueError(f'Public mechanism shard is empty: {shard}')
        py_tests = list(mechanisms['pythonTests']) if shard == 'mechanisms-1' else []
        py_owners = dict(mechanisms['pythonOwners']) if shard == 'mechanisms-1' else {}
        dedup = [
            row for row in mechanisms.get('deduplicated', [])
            if any(g['fixture'] == row['fixture'] and row['testClass'] in g['tests'].get(row['module'], ())
                   for g in groups)
        ]
        shards[shard] = dict(groups=groups, pythonTests=py_tests, pythonOwners=py_owners, deduplicated=dedup)
    expected_pairs = [(g['fixture'], mod, cls)
                      for g in mechanisms['groups'] for mod, classes in g['tests'].items() for cls in classes]
    if len(assigned_pairs) != len(expected_pairs) or set(assigned_pairs) != set(expected_pairs):
        raise ValueError('Mechanism shard partition lost or duplicated test classes')
    return shards


def write_json(path, value):
    with path.open('x', encoding='utf8', newline='\n') as stream:
        json.dump(value, stream, ensure_ascii=True, separators=(',', ':'))
        stream.write('\n')


def read_json(path):
    return json.loads(path.read_bytes())


def checked_json(path, expected):
    data = path.read_bytes()
    if hashlib.sha256(data).hexdigest() != expected:
        raise ValueError(f'JSON digest differs: {path}')
    return json.loads(data)


def git(repo, *args):
    return subprocess.check_output(['git', *args], cwd=repo, text=True, timeout=30).strip()


def candidate(repo):
    checkout, tree = git(repo, 'rev-parse', 'HEAD', 'HEAD^{tree}').splitlines()
    head, base = checkout, None
    cloud = os.environ.get('GITHUB_ACTIONS') == 'true'
    event = os.environ.get('GITHUB_EVENT_NAME') if cloud else 'local'
    if cloud:
        if checkout != os.environ['GITHUB_SHA'] or git(repo, 'status', '--porcelain', '--untracked-files=no'):
            raise ValueError('Cloud checkout differs from the designated clean commit')
        if event == 'pull_request':
            pr = read_json(Path(os.environ['GITHUB_EVENT_PATH']))['pull_request']
            head, base = pr['head']['sha'], pr['base']['sha']
            parents = [line.split()[1] for line in git(repo, 'cat-file', 'commit', checkout).split('\n\n', 1)[0].splitlines()
                       if line.startswith('parent ')]
            if parents != [base, head]:
                raise ValueError('Checkout merge parents differ from the PR base and head')
    return dict(head=head, base=base, checkout=checkout, tree=tree, event=event,
                run=os.environ['GITHUB_RUN_ID'] if cloud else 'local',
                attempt=os.environ['GITHUB_RUN_ATTEMPT'] if cloud else '1')


def toolchain(repo):
    java = toolchain_identity()
    wrapper = repo/'SimulationTest/gradle/wrapper'
    return dict(java={key: java[key]['sha256'] for key in ('java', 'javac', 'release')},
                javaVersion=(Path(java['release']['path']).read_text(encoding='utf8').splitlines()),
                python=platform.python_version(), runnerOS=platform.system(), architecture=platform.machine(),
                gradleWrapper=digest(wrapper/'gradle-wrapper.jar'),
                gradleProperties=digest(wrapper/'gradle-wrapper.properties'),
                gradleDistribution=next(line.split('=', 1)[1] for line in
                    (wrapper/'gradle-wrapper.properties').read_text(encoding='utf8').splitlines()
                    if line.startswith('distributionUrl=')),
                gradleUserConfig=[row['sha256'] for row in java['gradleUserConfig']])


def gradle_version(text):
    header = re.findall(r'^Gradle (\S+)\s*$', text, re.MULTILINE)
    fields = dict(re.findall(r'^(Build time|Revision|Kotlin|Groovy|Ant):\s+([^\r\n]+)', text, re.MULTILINE))
    if len(header) != 1 or len(fields) != 5 or not re.fullmatch(r'[0-9a-f]{40}', fields['Revision']):
        raise ValueError('Incomplete actual Gradle version receipt')
    return dict(version=header[0], **fields)


def measure_gradle(repo, output):
    project = repo/'SimulationTest'
    wrapper = [str(project/'gradlew.bat')] if os.name == 'nt' else ['sh', str(project/'gradlew')]
    process = execute_command(output, 'gradle-version', wrapper+['--version'], project, COLD_BUILD_TIMEOUT)
    if not process['passed']:
        raise ValueError('Could not resolve the actual Gradle distribution')
    return gradle_version((output/'gradle-version-process/stdout.log').read_text(encoding='utf8'))


def allocate(cases, timing):
    rows = {name: [] for name in SCENARIOS}
    weights = {name: 0 for name in PRODUCTION}
    for values in timing.values():
        if not values or any(not math.isfinite(seconds) or seconds <= 0 for seconds in values.values()):
            raise ValueError('Scenario timing values must be positive and finite')
    fallback = {backend: median(values.values()) for backend, values in timing.items()}
    production = []
    for case in cases:
        suite = case['suite']
        if suite == 'production-combat':
            backend, key = case.get('backend', 'core'), case['id']
            production.append((timing[backend].get(key, fallback[backend]), key, case))
        else:
            rows[suite if suite == 'content-three-formats' else 'other-short'].append(case)
    for seconds, _, case in sorted(production, key=lambda row: (-row[0], row[1])):
        shard = min(PRODUCTION, key=lambda name: (weights[name], name))
        rows[shard].append(case)
        weights[shard] += seconds
    return rows


def copy_distribution(package, archive, files):
    with tarfile.open(archive, 'x') as output:
        for row in files:
            output.add(package/row['path'], arcname=row['path'], recursive=False)
    if package_files(package) != files:
        raise ValueError('Package members changed during transfer creation')


def verify_distribution(archive, expected_sha, destination, files):
    if digest(archive) != expected_sha:
        raise ValueError('Transferred distribution archive digest differs')
    with tarfile.open(archive, 'r') as incoming:
        members = incoming.getmembers()
        expected_names = [row['path'] for row in files]
        if [member.name for member in members] != expected_names:
            raise ValueError('Transferred distribution member names differ')
        if any(not member.isfile() for member in members):
            raise ValueError('Transferred distribution must contain only ordinary files')
        destination.mkdir()
        incoming.extractall(destination, members=members, filter='data')
    if package_files(destination) != files:
        raise ValueError('Relocated distribution member bytes differ')
    return destination


def allocate_long(cases):
    rows = {name: [] for name in LONG_SHARDS}
    session_cases = [case for case in cases if case.get('backend') == 'session']
    core_cases = [case for case in cases if case.get('backend') != 'session']
    ordered = [*session_cases, *core_cases]
    for index, case in enumerate(ordered):
        rows[LONG_SHARDS[index % len(LONG_SHARDS)]].append(case)
    if any(not rows[name] for name in LONG_SHARDS):
        raise ValueError('A public long scenario shard is empty')
    return rows


def new_plan(repo, layer_kind='pr'):
    if layer_kind not in ('pr', 'long'):
        raise ValueError(f'Unsupported parallel layer: {layer_kind}')
    layer = plan_layer(repo, layer_kind)
    suites = [(row['name'], row['plan']) for row in layer['suites']]
    cases = [dict(case, suite=suite) for suite, selection in suites for case in selection['scenarios']]
    keys = [(case['suite'], case['id']) for case in cases]
    if len(set(keys)) != len(keys) or len({case['id'] for case in cases}) != len(cases):
        raise ValueError('Scenario identities must be unique across the public matrix')
    if layer_kind == 'long':
        if any(selection['tests'] or selection.get('pythonTests') for _, selection in suites):
            raise ValueError('Continuous long layer cannot skip selected mechanism tests')
        shards = allocate_long(cases)
        return dict(layer=layer, mechanisms=None, shards=shards, timing='balanced-backend-round-robin-v1')
    timing = read_json(repo/'SimulationTest/ci/public_scenario_timings.json')
    mechanisms = plan_mechanisms(suites)
    mechanism_shards = allocate_mechanisms(mechanisms)
    shards = {**{name: [] for name in MECHANISMS}, **allocate(cases, timing['seconds'])}
    if any(not shards[shard] for shard in SCENARIOS):
        raise ValueError('A public scenario shard is empty')
    return dict(layer=layer, mechanisms=mechanisms, mechanismShards=mechanism_shards,
                shards=shards, timing=timing['source'])


def prepare(repo, output, layer_kind='pr'):
    output.mkdir(parents=True)
    transfer = output/'transfer'
    transfer.mkdir()
    started = time.monotonic()
    identity, sources, tools = candidate(repo), identities(repo), toolchain(repo)
    plan = new_plan(repo, layer_kind=layer_kind)
    with resources(['build:'+str(repo/'Engine'), 'build:'+str(repo/'SimulationTest')]):
        gradle = measure_gradle(repo, output)
        built = build_shared_distribution(repo, output/'build', sources, COLD_BUILD_TIMEOUT)
    if built['status'] != 'passed':
        raise ValueError(built.get('error', 'Distribution preparation failed'))
    package = output/'build/package'
    files = package_files(package)
    archive = transfer/'distribution.tar'
    packaged = time.monotonic()
    copy_distribution(package, archive, files)
    archive_sha = digest(archive)
    if candidate(repo) != identity or identities(repo) != sources or toolchain(repo) != tools:
        raise ValueError('Identity changed during preparation')
    bundle = dict(plan, schemaVersion=1, identity=identity, source=sources, toolchain=tools, gradle=gradle, files=files,
                  archiveSha256=archive_sha)
    write_json(transfer/'plan.json', bundle)
    write_json(output/'preparation.json', dict(identity=identity, toolchain=tools, gradle=gradle, build=built,
        archiveBytes=archive.stat().st_size, packingSeconds=time.monotonic()-packaged,
        elapsedSeconds=time.monotonic()-started))
    return digest(transfer/'plan.json')


def scenario_coverage(cases, execution):
    if (execution['status'] != 'passed' or execution['firstError'] is not None
            or execution['requestedWorkers'] != WORKERS or execution['peakRunning'] > WORKERS
            or [row['id'] for row in execution['items']] != [case['id'] for case in cases]):
        raise ValueError('Current scenario execution is incomplete')
    current = []
    for case, row in zip(cases, execution['items']):
        name = case['id']
        commands = scenario_commands(case)
        if (row['status'] != 'passed' or row['archive']['id'] != name or row['kind'] != case['kind']
                or not row['endedUnix'] >= row['startedUnix']
                or [command['name'] for command in row['commands']] != commands
                or any(command['exitCode'] != 0 or command['firstError'] is not None for command in row['commands'])):
            raise ValueError(f'Incomplete scenario receipt: {name}')
        current.append(dict(suite=case['suite'], id=row['id'], kind=row['kind'], status=row['status'],
                            elapsedSeconds=row['endedUnix']-row['startedUnix'], commands=commands,
                            archive=row['archive']))
    return dict(scenarios=current)


def scenario_commands(case):
    name = case['id']
    commands = [name]
    if case.get('backend') == 'session' or case.get('recompare'):
        commands.append(name+'-recompare')
    if case.get('archiveAssertions'):
        commands.append(name+'-assertions')
    return commands


def verify_scenario_coverage(coverage, cases):
    current = coverage['scenarios']
    if len(current) != len(cases):
        raise ValueError('Scenario coverage differs')
    for row, case in zip(current, cases):
        if (any(row[key] != case[key] for key in ('suite', 'id', 'kind'))
                or row['status'] != 'passed' or row['commands'] != scenario_commands(case)
                or row['archive']['id'] != case['id'] or row['archive']['kind'] != case['kind']
                or not math.isfinite(row['elapsedSeconds']) or row['elapsedSeconds'] < 0):
            raise ValueError(f'Scenario receipt differs: {case["id"]}')


def mechanism_coverage(measured):
    return dict(groups=[dict(fixture=group['selection']['fixture'], methods=group['methodOwners'])
                        for group in measured['groups']],
                python=[dict(path=row['path'], suites=row['suites'], tests=row['result']['tests'])
                        for row in measured['pythonCoverage']])


def verify_mechanism_coverage(coverage, plan):
    groups = coverage['groups']
    if len(groups) != len(plan['groups']) or [row['path'] for row in coverage['python']] != plan['pythonTests']:
        raise ValueError('Mechanism group or Python script coverage differs')
    for row in coverage['python']:
        if row['suites'] != plan['pythonOwners'][row['path']] or row['tests'] < 1:
            raise ValueError('Python script execution or owners differ')
    for actual, expected in zip(groups, plan['groups']):
        methods = actual['methods']
        classes = {(module, name) for module, names in expected['tests'].items() for name in names}
        counts = {(row['module'], row['className'], row['method']) for row in methods}
        if (actual['fixture'] != expected['fixture'] or len(counts) != len(methods)
                or {(module, name) for module, name, _ in counts} != classes
                or any(not row['method'] or row['suites'] != expected['owners'][row['module']][row['className']]
                       for row in methods)):
            raise ValueError('JVM fixture, class, method or suite owner coverage differs')


def verify_sharded_mechanism_conservation(receipts, full_plan):
    expected_classes = {(g['fixture'], mod, cls): g['owners'][mod][cls]
                        for g in full_plan['groups'] for mod, classes in g['tests'].items() for cls in classes}
    seen_methods = set()
    seen_classes = {}
    seen_python = []
    for shard in MECHANISMS:
        coverage = receipts[shard]['coverage']
        for row in coverage['python']:
            if row['suites'] != full_plan['pythonOwners'].get(row['path']) or row['tests'] < 1:
                raise ValueError('Sharded Python script execution or owners differ')
            seen_python.append(row['path'])
        for group in coverage['groups']:
            fixture = group['fixture']
            shard_group_classes = set()
            for method in group['methods']:
                key = (fixture, method['module'], method['className'], method['method'])
                if not method['method'] or key in seen_methods:
                    raise ValueError('Duplicate or empty JVM method across mechanism shards')
                seen_methods.add(key)
                cls_key = (fixture, method['module'], method['className'])
                shard_group_classes.add(cls_key)
                if method['suites'] != expected_classes.get(cls_key):
                    raise ValueError('Sharded JVM suite owner coverage differs')
            for cls_key in shard_group_classes:
                if cls_key in seen_classes:
                    raise ValueError('Duplicate JVM test class across mechanism shards')
                seen_classes[cls_key] = shard
    if set(seen_classes) != set(expected_classes) or seen_python != full_plan['pythonTests']:
        raise ValueError('Total sharded mechanism coverage does not conserve planned JVM/Python selection')


def run_shard(repo, bundle, plan_sha, shard, archive, output):
    output.mkdir(parents=True)
    started = time.monotonic()
    receipt = dict(shard=shard, planSha256=plan_sha, status='execution-failed', archiveSha256=None, gradle=None)
    try:
        receipt.update(identity=candidate(repo), toolchain=toolchain(repo))
        sources = identities(repo)
        if receipt['identity'] != bundle['identity'] or sources != bundle['source']:
            raise ValueError('Shard checkout, attempt or authored input differs')
        if receipt['toolchain'] != bundle['toolchain']:
            raise ValueError('Shard toolchain differs')
        cases = bundle['shards'][shard]
        distribution = verify_distribution(archive, bundle['archiveSha256'], output/'package', bundle['files'])
        receipt['archiveSha256'] = bundle['archiveSha256']

        def admit_distribution():
            if package_files(distribution) != bundle['files']:
                raise ValueError('Relocated distribution changed during verification')

        if is_mechanism_shard(shard):
            folder = output/'mechanisms'
            plan = mechanism_plan_for_shard(bundle, shard)
            receipt['gradle'] = measure_gradle(repo, output)
            if receipt['gradle'] != bundle['gradle']:
                raise ValueError('Actual Gradle distribution differs')
            def admit_mechanisms():
                admit_distribution()
                if identities(repo) != sources or candidate(repo) != bundle['identity'] or toolchain(repo) != receipt['toolchain']:
                    raise ValueError('Authored input, checkout or toolchain changed during mechanisms')
            if run_development(repo, folder, plan, False, FORKS, COLD_BUILD_TIMEOUT,
                               admit=admit_mechanisms, session_distribution=distribution):
                raise ValueError('Fresh mechanisms failed')
            receipt['coverage'] = mechanism_coverage(verify_mechanism_receipt(repo, folder, plan, sources))
        else:
            expected = {row['path']: row['sha256'] for row in sources}
            def admit(case):
                verify_scenario_sources(repo, case, expected)
                admit_distribution()
            folder = output/'scenarios'
            folder.mkdir()
            timeout = LONG_COMMAND_TIMEOUT if shard in LONG_SHARDS else COMMAND_TIMEOUT
            execution = run_scenarios(repo, folder, cases, distribution, WORKERS, timeout, admit, execute_command)
            if execution['status'] != 'passed':
                raise ValueError(f'Current scenarios failed: {execution["firstError"]}')
            receipt['coverage'] = scenario_coverage(cases, execution)
        if candidate(repo) != bundle['identity'] or identities(repo) != sources or toolchain(repo) != receipt['toolchain']:
            raise ValueError('Shard identity changed during execution')
        receipt['status'] = 'passed'
    except Exception as error:
        receipt['error'] = str(error)
    receipt['elapsedSeconds'] = time.monotonic()-started
    write_json(output/'receipt.json', receipt)
    return receipt


def inspect_raw_evidence(path, expected, receipt, bundle):
    if digest(path) != expected:
        raise ValueError('Raw artifact bytes differ from upload digest')
    mechanism = is_mechanism_shard(receipt['shard'])
    member = 'mechanisms/result.json' if mechanism else 'scenario-execution.json'
    with zipfile.ZipFile(path) as archive:
        if archive.namelist().count(member) != 1:
            raise ValueError(f'Raw artifact must contain a unique {member}')
        actual = json.loads(archive.read(member))
    if actual['status'] != 'passed':
        raise ValueError('Raw artifact reported a failed execution')
    if mechanism:
        expected_plan = mechanism_plan_for_shard(bundle, receipt['shard'])
        if (actual['selection'] != expected_plan or actual['source'] != bundle['source']
                or mechanism_coverage(actual) != receipt['coverage']):
            raise ValueError('Raw JVM/Python evidence does not match the shard receipt')
    elif scenario_coverage(bundle['shards'][receipt['shard']], actual) != receipt['coverage']:
        raise ValueError('Raw scenario evidence does not match the shard receipt')


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def download_raw(artifact_id, output):
    api = os.environ['GITHUB_API_URL']
    repo = os.environ['GITHUB_REPOSITORY']
    url = f'{api}/repos/{repo}/actions/artifacts/{artifact_id}/zip'
    request = Request(url, headers={'Authorization': 'Bearer '+os.environ['GH_TOKEN'],
                                    'Accept': 'application/vnd.github+json'})
    try:
        with build_opener(NoRedirect()).open(request, timeout=30):
            raise ValueError('Raw artifact API did not return an archive redirect')
    except HTTPError as response:
        with response:
            redirect = response.headers.get('Location')
            if response.code != 302 or not redirect or urlsplit(redirect).scheme != 'https':
                raise ValueError(f'Raw artifact unavailable (HTTP {response.code})') from None
    try:
        with urlopen(redirect, timeout=60) as archive, output.open('xb') as stream:
            shutil.copyfileobj(archive, stream, length=1024*1024)
    except OSError:
        raise ValueError('Could not download the raw artifact archive') from None


def verify_raw_artifacts(bundle, receipts, artifacts, folder, *, raw_prefix='public-raw'):
    if not receipts:
        return {}, []
    attempt = bundle['identity']['attempt']
    api = os.environ['GITHUB_API_URL']
    repo = os.environ['GITHUB_REPOSITORY']
    run = bundle['identity']['run']
    items = {}
    page = 1
    while True:
        url = f'{api}/repos/{repo}/actions/runs/{run}/artifacts?per_page=100&page={page}'
        headers = {'Authorization': 'Bearer '+os.environ['GH_TOKEN'], 'Accept': 'application/vnd.github+json'}
        with urlopen(Request(url, headers=headers), timeout=30) as response:
            batch = json.load(response)['artifacts']
        for artifact in batch:
            name = artifact['name']
            if name.startswith(f'{raw_prefix}-{attempt}-'):
                if name in items:
                    raise ValueError('Duplicate raw evidence artifact')
                items[name] = artifact
        if len(batch) < 100:
            break
        page += 1

    def verify(shard):
        artifact = items[f'{raw_prefix}-{attempt}-{shard}']
        expected = artifacts[shard]
        if artifact['expired'] or artifact['digest'] != 'sha256:'+expected:
            raise ValueError('Raw artifact missing, expired or disagrees with the upload digest')
        path = folder/(shard+'.zip')
        download_raw(artifact['id'], path)
        inspect_raw_evidence(path, expected, receipts[shard], bundle)
        return dict(id=artifact['id'], bytes=path.stat().st_size, sha256=expected)

    results, errors = {}, []
    with ThreadPoolExecutor(max_workers=6) as pool:
        futures = {shard: pool.submit(verify, shard) for shard in receipts}
        for shard, future in futures.items():
            try:
                results[shard] = future.result()
            except (OSError, ValueError, KeyError, TypeError, zipfile.BadZipFile) as error:
                errors.append(f'{shard} raw evidence: {error}')
    unexpected = set(items) - {f'{raw_prefix}-{attempt}-{shard}' for shard in bundle['shards']}
    if unexpected:
        errors.append('Unexpected raw artifacts: '+', '.join(sorted(unexpected)))
    return results, errors


def aggregate(bundle, plan_sha, folder, pins, jobs, artifacts, admit_raw, *, receipt_prefix='public-receipt', layer_kind=None):
    receipts, errors = {}, []
    attempt = bundle['identity']['attempt']
    expected = bundle['shards']
    effective_layer = layer_kind or bundle.get('layer', {}).get('kind', 'pr')
    required_jobs = ('prepare', 'scenarios') if effective_layer == 'long' else ('prepare', 'mechanism', 'scenarios')
    inputs = {}
    for path in sorted(folder.glob('*/receipt.json')):
        try:
            receipt = read_json(path)
            shard = receipt['shard']
            if shard not in expected or shard in inputs:
                raise ValueError('Unexpected or duplicate shard')
            inputs[shard] = path
            receipts[shard] = receipt
        except (OSError, ValueError, KeyError, TypeError) as error:
            errors.append(f'{path.parent.name}: {error}')
    for job in required_jobs:
        if jobs.get(job) != 'success':
            errors.append(f'{job} job conclusion: {jobs.get(job)}')
    for shard, cases in expected.items():
        try:
            path = inputs[shard]
            if not re.fullmatch(r'[0-9a-f]{64}', artifacts.get(shard, '')):
                raise ValueError('Raw evidence artifact has no platform digest')
            if path.parent.name != f'{receipt_prefix}-{attempt}-{shard}':
                raise ValueError('Unexpected artifact name')
            row = checked_json(path, pins[shard])
            if row != receipts[shard]:
                raise ValueError('Receipt changed during summary')
            package = bundle['archiveSha256']
            if (row['identity'] != bundle['identity'] or row['toolchain'] != bundle['toolchain']
                    or row['planSha256'] != plan_sha or row['archiveSha256'] != package or row['status'] != 'passed'
                    or row['gradle'] != (bundle['gradle'] if is_mechanism_shard(shard) else None)):
                raise ValueError('Failed shard or receipt identity differs')
            if is_mechanism_shard(shard):
                verify_mechanism_coverage(row['coverage'], mechanism_plan_for_shard(bundle, shard))
            else:
                verify_scenario_coverage(row['coverage'], cases)
            admit_raw(shard, row)
        except (OSError, ValueError, KeyError, TypeError, zipfile.BadZipFile) as error:
            errors.append(f'{shard}: {error}')
    if 'mechanismShards' in bundle and all(shard in receipts for shard in MECHANISMS):
        try:
            verify_sharded_mechanism_conservation(receipts, bundle['mechanisms'])
        except (ValueError, KeyError, TypeError) as error:
            errors.append(f'mechanisms-conservation: {error}')
    return dict(identity=bundle['identity'], planSha256=plan_sha, jobs=jobs, receipts=receipts, evidenceArtifacts=artifacts, errors=errors,
                status='execution-failed' if errors else 'passed')


def output_pin(name, sha):
    if path := os.environ.get('GITHUB_OUTPUT'):
        with Path(path).open('a', encoding='utf8') as stream:
            stream.write(f'{name}={sha}\n')
    print(f'{name}={sha}')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['prepare', 'shard', 'summary'])
    parser.add_argument('--layer', choices=['pr', 'long'], default='pr')
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--transfer', type=Path)
    parser.add_argument('--plan-sha', default=os.environ.get('PUBLIC_PLAN_SHA'))
    parser.add_argument('--shard', choices=('mechanisms', *MECHANISMS, *SCENARIOS, *LONG_SHARDS))
    parser.add_argument('--receipts', type=Path)
    args = parser.parse_args()
    output = args.output.resolve()
    if args.action == 'prepare':
        output_pin('plan_sha', prepare(REPO, output, layer_kind=args.layer))
        return 0
    if args.action == 'shard':
        try:
            bundle = checked_json(args.transfer/'plan.json', args.plan_sha)
        except (OSError, ValueError, TypeError) as error:
            output.mkdir(parents=True)
            write_json(output/'receipt.json', dict(shard=args.shard, planSha256=args.plan_sha,
                                                  status='execution-failed', error=str(error)))
            print(str(error))
            return 1
        receipt = run_shard(REPO, bundle, args.plan_sha, args.shard, args.transfer/'distribution.tar', output)
        output_pin(args.shard.replace('-', '_'), digest(output/'receipt.json'))
        if receipt['status'] != 'passed':
            print(receipt['error'])
        return int(receipt['status'] != 'passed')
    output.mkdir(parents=True)
    jobs = json.loads(os.environ['PUBLIC_JOBS'])
    receipt_prefix = 'public-long-receipt' if args.layer == 'long' else 'public-receipt'
    raw_prefix = 'public-long-raw' if args.layer == 'long' else 'public-raw'
    try:
        bundle = checked_json(args.transfer/'plan.json', args.plan_sha)
        if (bundle['identity'] != candidate(REPO) or bundle['source'] != identities(REPO)
                or bundle.get('layer', {}).get('kind') != args.layer
                or any(bundle[key] != value for key, value in new_plan(REPO, layer_kind=args.layer).items())):
            raise ValueError('Summary checkout, attempt or planned coverage differs')
        admitted = {}
        artifacts = json.loads(os.environ['PUBLIC_ARTIFACTS'])
        result = aggregate(bundle, args.plan_sha, args.receipts, json.loads(os.environ['PUBLIC_PINS']), jobs,
                           artifacts, admitted.__setitem__, receipt_prefix=receipt_prefix, layer_kind=args.layer)
        try:
            with tempfile.TemporaryDirectory(prefix='public-raw-evidence-') as raw:
                verified, errors = verify_raw_artifacts(bundle, admitted, artifacts, Path(raw), raw_prefix=raw_prefix)
        except (OSError, ValueError, KeyError, TypeError) as error:
            verified, errors = {}, [f'Raw artifact enumeration failed: {error}']
        result['verifiedRawEvidence'] = verified
        result['errors'].extend(errors)
        if set(verified) != set(bundle['shards']):
            result['errors'].append('Raw evidence did not verify for all shards')
        if result['errors']:
            result['status'] = 'execution-failed'
    except (OSError, ValueError, KeyError, TypeError) as error:
        result = dict(status='execution-failed', jobs=jobs, errors=[str(error)])
    write_json(output/'result.json', result)
    print(json.dumps(dict(status=result['status'], errors=result['errors'])))
    return int(result['status'] != 'passed')


if __name__ == '__main__':
    raise SystemExit(main())
