"""Verify same-PR timing evidence and run the fixed technology and long layers."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile
from datetime import datetime

from development_check import plan_development, resources, run_development
from public_batch import plan_layer, run_layer, verify_mechanism_receipt
from public_check import REPO, identities
from public_distribution import digest
from public_parallel import SCENARIOS, download_raw, git, measure_gradle, new_plan, toolchain, write_json


PR_WORKFLOW = '.github/workflows/public-pr.yml'
TECHNOLOGY_SUITE = 'final-candidate-technology'
TECHNOLOGY_METHOD = 'the_default_technology_chain_uses_ordinary_construction_upgrades_and_production()'
SHARDS = {'mechanisms', *SCENARIOS}
JOBS = {'public-prepare', 'public-mechanisms', 'public-coverage',
        *(f'public-scenarios ({name})' for name in SCENARIOS)}
MAX_SECONDS = 1200


def api(path):
    result = subprocess.check_output(['gh', 'api', path], timeout=30)
    return json.loads(result)


def pull_identity(pull):
    if (pull['state'] != 'open' or pull['base']['ref'] != 'main' or pull['mergeable'] is not True
            or not pull['merge_commit_sha']):
        raise ValueError('PR is not open and mergeable into main')
    return dict(head=pull['head']['sha'], base=pull['base']['sha'], checkout=pull['merge_commit_sha'])


def checkout_identity(repo, pull, repository):
    expected = pull_identity(pull)
    if pull['head']['repo']['full_name'] != repository or pull['base']['repo']['full_name'] != repository:
        raise ValueError('Final candidate must be a PR from this repository')
    checkout, tree = git(repo, 'rev-parse', 'HEAD', 'HEAD^{tree}').splitlines()
    parents = git(repo, 'show', '-s', '--format=%P', checkout).split()
    if (checkout != expected['checkout'] or parents != [expected['base'], expected['head']]
            or git(repo, 'status', '--porcelain', '--untracked-files=no')):
        raise ValueError('Checked-out candidate does not match the current PR merge')
    return dict(expected, tree=tree, pr=pull['number'])


def require_current_candidate(prefix, pr_number, expected):
    pull = api(f'{prefix}/pulls/{pr_number}')
    if pull_identity(pull) != {key: expected[key] for key in ('head', 'base', 'checkout')}:
        raise ValueError('PR candidate changed')
    if api(f'{prefix}/git/ref/heads/main')['object']['sha'] != expected['base']:
        raise ValueError('Current main does not match the verified merge base')


def verify_sample(expected, run, jobs, coverage, plan, plan_sha):
    attempt = run['run_attempt']
    if (run['event'] != 'pull_request' or run['path'] != PR_WORKFLOW
            or run['head_sha'] != expected['head'] or run['conclusion'] != 'success'):
        raise ValueError('Sample is not a successful public PR run for this head')
    identity = {key: expected[key] for key in ('head', 'base', 'checkout', 'tree')}
    identity.update(event='pull_request', run=str(run['id']), attempt=str(attempt))
    if (coverage['status'] != 'passed' or coverage['identity'] != identity
            or plan['identity'] != identity or coverage['planSha256'] != plan_sha
            or set(plan['shards']) != SHARDS or set(coverage['verifiedRawEvidence']) != SHARDS
            or set(coverage['receipts']) != SHARDS or coverage['errors']
            or any(row['status'] != 'passed' for row in coverage['receipts'].values())):
        raise ValueError('Sample coverage is incomplete or has a different candidate identity')
    runners = [job for job in jobs if job['conclusion'] != 'skipped']
    if ({job['name'] for job in runners} != JOBS or len(runners) != len(JOBS)
            or any(job['conclusion'] != 'success' or job['labels'] != ['ubuntu-latest']
                   or job['runner_group_name'] != 'GitHub Actions' for job in runners)):
        raise ValueError('Sample does not have the complete standard-runner job set')
    end = next(job['completed_at'] for job in runners if job['name'] == 'public-coverage')
    start = run['created_at'] if attempt == 1 else run['run_started_at']
    elapsed = (datetime.fromisoformat(end)-datetime.fromisoformat(start)).total_seconds()
    if not 0 < elapsed < MAX_SECONDS:
        raise ValueError(f'PR attempt {attempt} took {elapsed:.0f} seconds')
    return dict(run=run['id'], attempt=attempt, elapsedSeconds=elapsed, planSha256=plan_sha)


def load_artifact(artifacts, name, member, output):
    matches = [row for row in artifacts if row['name'] == name and not row['expired']]
    if len(matches) != 1:
        raise ValueError(f'Did not find a unique current artifact: {name}')
    artifact = matches[0]
    path = output/(name+'.zip')
    download_raw(artifact['id'], path)
    if artifact['digest'] != 'sha256:'+digest(path):
        raise ValueError(f'Artifact transport digest differs: {name}')
    with zipfile.ZipFile(path) as archive:
        if archive.namelist().count(member) != 1:
            raise ValueError(f'Did not find a unique artifact member: {member}')
        data = archive.read(member)
    return json.loads(data), hashlib.sha256(data).hexdigest()


def require_toolchain(plan, expected):
    if any(plan[key] != expected[key] for key in ('toolchain', 'gradle')):
        raise ValueError('Qualification and final candidate toolchain identities differ')


def qualification_runs(index, pr_number, latest_run):
    indexed = index['workflow_runs']
    if index['total_count'] != len(indexed):
        raise ValueError('PR run index is incomplete')
    runs = sorted(indexed, key=lambda row: row['run_number'], reverse=True)[:3]
    if (len(runs) != 3 or runs[0]['id'] != latest_run
            or len({row['id'] for row in runs}) != 3
            or len({row['run_number'] for row in runs}) != 3
            or any([pr['number'] for pr in row['pull_requests']] != [pr_number]
                   or row['run_attempt'] != 1 or row['status'] != 'completed' for row in runs)):
        raise ValueError('Qualification requires the latest three independent completed runs of this PR head')
    return tuple(row['id'] for row in reversed(runs))


def qualification_path(prefix, head):
    return f'{prefix}/actions/workflows/public-pr.yml/runs?event=pull_request&head_sha={head}&per_page=100'


def require_merge_tree(commit, expected):
    if (commit['tree']['sha'] != expected['tree']
            or [parent['sha'] for parent in commit['parents']] != [expected['base'], expected['head']]):
        raise ValueError('Sample merge checkout does not have the same candidate tree and parents')


def qualify(prefix, pr_number, latest_run, expected, output, source, selection, tools):
    path = qualification_path(prefix, expected['head'])
    run_ids = qualification_runs(api(path), pr_number, latest_run)
    samples = []
    for index, run_id in enumerate(run_ids, 1):
        run_path = f'{prefix}/actions/runs/{run_id}'
        run = api(f'{run_path}/attempts/1')
        jobs = api(f'{run_path}/attempts/1/jobs?per_page=100')
        if run['id'] != run_id or run['run_attempt'] != 1 or jobs['total_count'] != len(jobs['jobs']):
            raise ValueError('Run identity, attempt or job list differs')
        metadata = api(f'{run_path}/artifacts?per_page=100')
        if metadata['total_count'] != len(metadata['artifacts']):
            raise ValueError('Artifact index is incomplete')
        with tempfile.TemporaryDirectory(prefix='public-pr-evidence-') as tmp:
            folder = Path(tmp)
            artifacts = metadata['artifacts']
            coverage, _ = load_artifact(artifacts, 'public-coverage-1', 'result.json', folder)
            plan, plan_sha = load_artifact(artifacts, 'public-plan-1', 'plan.json', folder)
        if plan['source'] != source or any(plan[key] != data for key, data in selection.items()):
            raise ValueError('Sample plan differs from current executable selection or source')
        require_toolchain(plan, tools)
        checkout = plan['identity']['checkout']
        if checkout != expected['checkout']:
            commit = api(f'{prefix}/git/commits/{checkout}')
            if commit['sha'] != checkout:
                raise ValueError('Sample Git checkout lookup returned a different commit')
            require_merge_tree(commit, expected)
        sample = verify_sample(dict(expected, checkout=checkout), run, jobs['jobs'], coverage, plan, plan_sha)
        sample['checkout'] = checkout
        samples.append(sample)
        write_json(output/f'pr-{index}.json', dict(sample=sample, coverage=coverage, plan=plan))
    require_current_candidate(prefix, pr_number, expected)
    if qualification_runs(api(path), pr_number, latest_run) != run_ids:
        raise ValueError('PR qualification runs changed during verification')
    return samples


def run_candidate(repo, output, pr_number, run_id):
    output.mkdir(parents=True)
    result = dict(status='execution-failed', pr=pr_number, qualificationRun=run_id)
    try:
        repository = os.environ['GITHUB_REPOSITORY']
        prefix = f'repos/{repository}'
        expected = checkout_identity(repo, api(f'{prefix}/pulls/{pr_number}'), repository)
        if expected['head'] != os.environ['GITHUB_SHA']:
            raise ValueError('Dispatch ref does not point to the qualified PR head')
        source = identities(repo)
        result.update(identity=expected, finalRun=os.environ['GITHUB_RUN_ID'],
                      finalAttempt=os.environ['GITHUB_RUN_ATTEMPT'])
        with resources(['build:'+str(repo/'Engine'), 'build:'+str(repo/'SimulationTest')]):
            tools = dict(toolchain=toolchain(repo), gradle=measure_gradle(repo, output))
        result.update(tools)
        result['samples'] = qualify(prefix, pr_number, run_id, expected, output, source, new_plan(repo), tools)
        technology = plan_development(repo, [TECHNOLOGY_SUITE])
        folder = output/'technology'
        if run_development(repo, folder, technology, False, 2, 900):
            raise ValueError('Final technology chain failed')
        checked = verify_mechanism_receipt(repo, folder, technology, source)
        methods = [method['method'] for group in checked['groups'] for method in group['methodOwners']]
        if methods != [TECHNOLOGY_METHOD]:
            raise ValueError('Complete technology-chain method is missing')
        result['technology'] = dict(status='passed', result='technology/result.json', method=methods[0])
        if run_layer(repo, output/'long', plan_layer(repo, 'long'), False, 900, 1, 2):
            raise ValueError('Final continuous layer failed')
        result['long'] = dict(status='passed', result='long/result.json')
        if identities(repo) != source or toolchain(repo) != tools['toolchain']:
            raise ValueError('Candidate inputs or toolchain changed during final verification')
        require_current_candidate(prefix, pr_number, expected)
        accepted = tuple(sample['run'] for sample in result['samples'])
        if qualification_runs(api(qualification_path(prefix, expected['head'])), pr_number, run_id) != accepted:
            raise ValueError('PR qualification runs changed during verification')
        result['status'] = 'passed'
    except Exception as error:
        result['error'] = str(error)
    write_json(output/'result.json', result)
    print(json.dumps(dict(status=result['status'], error=result.get('error'))))
    return int(result['status'] != 'passed')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--pr', type=int, required=True)
    parser.add_argument('--run', type=int, required=True)
    parser.add_argument('--output', type=Path, required=True)
    options = parser.parse_args()
    if min(options.pr, options.run) < 1:
        parser.error('PR and run IDs must be positive')
    raise SystemExit(run_candidate(REPO, options.output.resolve(), options.pr, options.run))
