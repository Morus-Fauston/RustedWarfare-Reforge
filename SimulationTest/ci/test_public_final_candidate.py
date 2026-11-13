"""Fixed PR verification rejects stale, partial and over-budget samples."""
from copy import deepcopy
import unittest

from public_final_candidate import JOBS, PR_WORKFLOW, verify_sample, pull_identity, require_toolchain


class PublicFinalCandidateTest(unittest.TestCase):
    def test_carries_one_toolchain_into_final(self):
        expected = {'toolchain': {'python': '3.13.18'}, 'gradle': {'version': '9.3.1', 'Revision': 'one'}}
        require_toolchain(expected, expected)
        with self.assertRaises(ValueError):
            require_toolchain(dict(expected, toolchain={'python': '3.13.19'}), expected)
        with self.assertRaises(ValueError):
            require_toolchain(dict(expected, gradle={'version': '9.3.1', 'Revision': 'two'}), expected)

    def test_current_candidate_uses_open_mergeable_main(self):
        pull = dict(state='open', base=dict(ref='main', sha='b'), head=dict(sha='h'),
                    mergeable=True, merge_commit_sha='c')
        self.assertEqual(dict(head='h', base='b', checkout='c'), pull_identity(pull))
        for replacement in (dict(state='closed'), dict(mergeable=None), dict(merge_commit_sha=None),
                            dict(base=dict(ref='release', sha='b'))):
            with self.assertRaises(ValueError):
                pull_identity(dict(pull, **replacement))

    def test_sample_rejects_failed_missing_stale_or_too_slow_evidence(self):
        candidate = dict(head='h', base='b', checkout='m', tree='t', pr=4)
        identity = dict(head='h', base='b', checkout='m', tree='t', event='pull_request',
                        run='1', attempt='2')
        run = dict(id=1, event='pull_request', path=PR_WORKFLOW, head_sha='h', conclusion='success',
                   run_attempt=2, created_at='2026-11-12T00:00:00Z', run_started_at='2026-11-13T10:00:00Z')
        jobs = [dict(name=name, conclusion='success', labels=['ubuntu-latest'], runner_group_name='GitHub Actions',
                     completed_at='2026-11-13T10:19:59Z') for name in JOBS]
        from public_parallel import SCENARIOS
        shards = ('mechanisms', *SCENARIOS)
        plan = dict(identity=identity, shards={key: [] for key in shards})
        coverage = dict(status='passed', identity=identity, planSha256='c'*64, errors=[],
                        verifiedRawEvidence={key: {} for key in shards},
                        receipts={key: dict(status='passed') for key in shards})
        args = candidate, run, jobs, coverage, plan, 'c'*64
        self.assertEqual(1199, verify_sample(*args)['elapsedSeconds'])
        for key, value in [('base', 'other'), ('checkout', 'other'), ('tree', 'other'), ('attempt', '1')]:
            changed = dict(coverage, identity=dict(identity, **{key: value}))
            with self.assertRaises(ValueError):
                verify_sample(candidate, run, jobs, changed, plan, 'c'*64)
        for field in ('verifiedRawEvidence', 'receipts'):
            changed = deepcopy(coverage)
            changed[field].pop('mechanisms')
            with self.assertRaises(ValueError):
                verify_sample(candidate, run, jobs, changed, plan, 'c'*64)
        for changed in (dict(run, event='workflow_dispatch'), dict(run, conclusion='failure')):
            with self.assertRaises(ValueError):
                verify_sample(candidate, changed, jobs, coverage, plan, 'c'*64)
        jobs_without = jobs[:-1]
        with self.assertRaises(ValueError):
            verify_sample(candidate, run, jobs_without, coverage, plan, 'c'*64)
        jobs_changed = [dict(job, conclusion='cancelled') if job['name']=='public-mechanisms' else job
                        for job in jobs]
        with self.assertRaises(ValueError):
            verify_sample(candidate, run, jobs_changed, coverage, plan, 'c'*64)
        jobs_slow = [dict(job, completed_at='2026-11-13T10:20:00Z') for job in jobs]
        with self.assertRaises(ValueError):
            verify_sample(candidate, run, jobs_slow, coverage, plan, 'c'*64)


if __name__ == '__main__':
    unittest.main()
