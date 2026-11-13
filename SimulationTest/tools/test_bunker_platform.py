"""Independent finite bunker state and action schedule archive checks."""
import copy
import unittest
import struct
from platform_scenario import PlatformError, compare_history, validate_history_poses, validate_unit_action_intent


def bunker():
    return dict(unitId='b', typeId='mechBunker', ownerPlayerId='p1', alive=True, height=3,
        constructionProgress=1, carriedBy=None,
        unitActions=dict(automaticCooldown=0, actions=dict(deploy=dict(
            queueIndex=None, progress=None, cooldownRemaining=0, available=True))))


class BunkerPlatformTest(unittest.TestCase):
    def test_independent_history_rejects_premature_conversion_missing_completion_and_forged_queue(self):
        def sample(value, tick=-1, events=None):
            return dict(worldId='w', stepIndex=tick, simulationTime=tick+1, balance=0, units=[copy.deepcopy(value)], events=events or [])
        rows=[]
        def add(**fields):rows.append(dict(sequence=len(rows), **fields))
        initial=bunker();queued=bunker()
        queued['unitActions']['actions']['deploy'].update(queueIndex=0, progress=0, available=False)
        add(kind='observation', phase='initial', sample=sample(initial))
        add(kind='intent-start', intentId='deploy')
        add(kind='observation', phase='before-action', sample=sample(initial))
        add(kind='action', type='UnitAction', unitId='b', actorPlayerId='p1', actionName='deploy', cancelQueued=False,
            result=dict(actionId='deploy', accepted=True))
        add(kind='observation', phase='after-action', sample=sample(queued))
        progress=0
        f32=lambda v:struct.unpack('<f',struct.pack('<f',v))[0]
        for tick in range(167):
            value=copy.deepcopy(queued);progress=f32(progress+f32(.006))
            value['unitActions']['actions']['deploy']['progress']=progress
            events=[]
            if tick==166:
                value['typeId']='mechBunkerDeployed'
                value['unitActions']['actions']=dict(undeploy=dict(queueIndex=None, progress=None, cooldownRemaining=0, available=True))
                events=[dict(kind='unit-action-completed',unitId='b')]
            add(kind='observation',phase='step',sample=sample(value,tick,events))
        add(kind='intent-end',intentId='deploy')
        plan=dict(scenarioId='bunker',intents=[dict(intentId='deploy',kind='unit-action',actionName='deploy',waitSteps=167)])
        policy=dict(initialBalance=0,bunkerStages=[dict(intentId='deploy',typeId='mechBunkerDeployed')])
        self.assertEqual('passed',compare_history(rows,plan,policy)['status'])
        for fault in ('progress','premature','event','wrong-name'):
            altered=copy.deepcopy(rows)
            if fault=='progress':altered[6]['sample']['units'][0]['unitActions']['actions']['deploy']['progress']=.5
            elif fault=='premature':altered[5]['sample']['units'][0]['typeId']='mechBunkerDeployed'
            elif fault=='event':altered[-2]['sample']['events']=[]
            else:altered[3]['actionName']='undeploy'
            with self.subTest(fault=fault),self.assertRaises(PlatformError):compare_history(altered,plan,policy)

    def test_finite_owner_actions_reject_hidden_control_bad_progress_cooldown_and_foreign_energy(self):
        validate_history_poses([bunker()], {})
        validate_unit_action_intent(dict(intentId='deploy', kind='unit-action', actionName='deploy', waitSteps=167))
        for fault in ('hidden', 'cooldown', 'progress', 'automatic', 'energy', 'enemy'):
            value = bunker()
            action = value['unitActions']['actions']['deploy']
            if fault == 'hidden':
                value['typeId'] = 'mechBunkerDeployed'
            elif fault == 'cooldown':
                action['cooldownRemaining'] = 1
            elif fault == 'progress':
                action.update(queueIndex=0, progress=1, available=False)
            elif fault == 'automatic':
                value['unitActions']['automaticCooldown'] = 60
            elif fault == 'energy':
                value['energy'] = dict(current=1, maximum=1, recharging=False)
            else:
                value['ownerPlayerId'] = 'p2'
            with self.subTest(fault=fault), self.assertRaises(PlatformError):
                validate_history_poses([value], {})


if __name__ == '__main__':
    unittest.main()
