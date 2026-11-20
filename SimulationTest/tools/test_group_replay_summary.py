import unittest
from group_replay_summary import movement

class ReplayMovementTest(unittest.TestCase):
    sample = dict(unit='a',command='click',origin='click-a',beforeX='0',beforeY='0',beforeHeading='90',ownEndX='0',ownEndY='0',ownEndHeading='92',steering='true',aimX='20',aimY='0',pushX='0',pushY='0',x='0',y='0')

    def test_turn_in_place_is_not_forward(self):
        self.assertEqual(movement(self.sample),(True, False, False))

    def test_push_along_route_is_not_propulsion(self):
        self.assertEqual(movement(dict(self.sample,ownEndHeading='90',pushX='2',x='2')),(False, False, True))

    def test_own_forward_and_later_push_can_coexist(self):
        self.assertEqual(movement(dict(self.sample,ownEndHeading='90',ownEndX='1',pushY='2',x='1',y='2')),(False,True,True))

    def test_sideways_displacement_is_not_forward(self):
        self.assertFalse(movement(dict(self.sample,ownEndY='1'))[1])

    def test_unrelated_order_is_not_forward(self):
        self.assertFalse(movement(dict(self.sample,ownEndX='1',origin='older-a'))[1])

    def test_reversing_toward_aim_is_not_forward(self):
        self.assertFalse(movement(dict(self.sample,ownEndX='1',ownEndHeading='270',beforeHeading='270'))[1])


class ReplayWindowTest(unittest.TestCase):
    def setUp(self):
        import tempfile
        import json
        from pathlib import Path
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.out = Path(self.tmp.name)
        scene=self.out/'scene.json'
        scene.write_text(json.dumps({'units':[{'owner':'p1','unitId':'a','type':'test'}]}))
        (self.out/'result.json').write_text(json.dumps({'passed':True,'argv':[str(scene),str(self.out),'click','10','0','4']}))
        (self.out/'steps.tsv').write_text('step\tadvanceThreadCpuNs\tadvanceWallNs\twork\tbudget\n'+''.join(f'{i}\t1\t2\t0\t512\n' for i in range(4)))
        (self.out/'planning.tsv').write_text('step\tplanningCpuNs\tplanningWallNs\n'+''.join(f'{i}\t0\t1\n' for i in range(4)))
        self.frames([0,0,0,0])

    def frames(self, xs, driving=()):
        import csv,gzip
        row=dict(ReplayMovementTest.sample,type='test',step=0,speed=0,angular=0,activeTargetX='',published='false',arrived='false',heading=90)
        with gzip.open(self.out/'trace.tsv.gz','wt',newline='') as stream:
            writer=csv.DictWriter(stream,fieldnames=row,delimiter='\t')
            writer.writeheader()
            for i,x in enumerate(xs): writer.writerow(dict(row,step=i,x=x,ownEndX='1' if i in driving else '0',arrived='true' if i==0 else 'false'))

    def report(self):
        from group_replay_summary import summarize
        return summarize(self.out,1,2)

    def test_complete_window_excludes_observation_after_proof(self):
        self.assertEqual(self.report()['completedWindow']['steps'],3)

    def test_subsequent_displacement_revokes_completion(self):
        self.frames([0,0,0,1]); self.assertIsNone(self.report()['completedWindow'])

    def test_truncated_trace_rejected(self):
        self.frames([0,0,0]); self.assertRaises(ValueError,self.report)

    def test_missing_planning_clock_rejected(self):
        (self.out/'planning.tsv').unlink(); self.assertRaises(FileNotFoundError,self.report)

    def test_duplicate_simulation_step_rejected(self):
        p=self.out/'steps.tsv';p.write_text(p.read_text().replace('3\t1','2\t1'));self.assertRaises(ValueError,self.report)

    def test_stable_again_uses_later_window(self):
        import json
        p=self.out/'result.json';r=json.loads(p.read_text());r['argv'][-1]='6';p.write_text(json.dumps(r))
        for name in ('steps.tsv','planning.tsv'):
            p=self.out/name;lines=p.read_text().splitlines();last=lines[-1].split('\t')[1:]
            p.write_text('\n'.join(lines+['\t'.join([str(i),*last]) for i in (4,5)])+'\n')
        self.frames([0,0,0,1,1,1]);report=self.report()
        self.assertEqual((report['firstStableThrough'],report['poseStableThrough']),(2,5))

    def test_wrong_command_rejected(self):
        import json
        p=self.out/'result.json';r=json.loads(p.read_text());r['argv'][-4]='other';p.write_text(json.dumps(r))
        self.assertRaises(ValueError,self.report)

    def test_first_forward_step_after_each_admission_wait(self):
        for waits, driving, expected in [({0, 1}, {2, 3}, [2]), ({0, 2}, {1, 3}, [1, 3]),
                                         ({0}, {3}, [3]), ({0, 2}, set(), [])]:
            with self.subTest(waits=waits, driving=driving):
                self.frames([0, 0, 0, 0], driving)
                (self.out/'planning.tsv').write_text('step\tplanningCpuNs\tplanningWallNs\tadmissionWaitIds\n' + ''.join(
                    f"{i}\t0\t1\t{'a' if i in waits else ''}\n" for i in range(4)))
                self.assertEqual(self.report()['releasedAfterWait'].get('a', []), expected)
