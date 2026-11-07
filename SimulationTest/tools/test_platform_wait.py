"""Event identity and bounded progress of a real waiting segment."""
import unittest
from platform_wait import WaitSegment, WaitFailure

def row(sequence, step, event=None, generation=2, world='world:2'):
    return dict(sequence=sequence,kind='observation',phase='step',generation=generation,
        sample=dict(worldId=world,stepIndex=step,events=[dict(kind=event,unitId='dock')] if event else [],
                    units=[dict(unitId='dock',typeId='spdock')]))

class WaitingSegmentTest(unittest.TestCase):
    def gate(self):
        return WaitSegment(generation=2,world='world:2',after_sequence=10,initial_step=100,
            minimum_steps=20,maximum_steps=100,wall_seconds=10,stall_seconds=2,started=0,
            event_kind='construction-completed',unit_type='spdock')

    def test_only_fresh_matching_event_and_enough_progress_release_the_segment(self):
        gate=self.gate()
        self.assertFalse(gate.observe([row(9,120,'construction-completed')],.5))
        self.assertFalse(gate.observe([row(11,110,'construction-completed')],1))
        self.assertTrue(gate.observe([row(12,120)],1.5))
        self.assertEqual(20,gate.receipt(1.5)['simulationSteps'])

    def test_wrong_world_generation_or_event_unit_never_completes(self):
        for values in [dict(generation=1),dict(world='world:1')]:
            with self.subTest(values=values),self.assertRaises(WaitFailure):
                self.gate().observe([row(11,120,'construction-completed',**values)],1)
        changed=row(11,120,'construction-completed');changed['sample']['units'][0]['typeId']='other'
        self.assertFalse(self.gate().observe([changed],1))

    def test_a_repeated_after_action_sample_cannot_supply_an_old_step_event(self):
        old=row(11,100,'construction-completed');old['phase']='after-action'
        gate=self.gate()
        self.assertFalse(gate.observe([old,row(12,120)],1))

    def test_stalled_wall_and_simulation_limits_preserve_the_first_failure(self):
        for records,now,reason in [([],2.1,'stalled'),([row(11,120)],10.1,'wall-timeout'),
                                   ([row(11,201)],1,'simulation-limit')]:
            gate=self.gate()
            with self.subTest(reason=reason),self.assertRaises(WaitFailure) as caught:gate.observe(records,now)
            self.assertEqual(reason,caught.exception.reason)
            with self.assertRaises(WaitFailure) as again:gate.observe([row(12,130,'construction-completed')],now+.1)
            self.assertIs(caught.exception,again.exception)

    def test_speed_ack_must_be_fresh_same_generation_unpaused_and_requested(self):
        good=dict(kind='simulation-control',sequence=11,generation=2,rate=4,paused=False)
        self.assertTrue(WaitSegment.speed_ack([good],2,10,4))
        for changed in [dict(good,sequence=10),dict(good,generation=1),dict(good,rate=1),dict(good,paused=True)]:
            self.assertFalse(WaitSegment.speed_ack([changed],2,10,4))

if __name__=='__main__':unittest.main()
