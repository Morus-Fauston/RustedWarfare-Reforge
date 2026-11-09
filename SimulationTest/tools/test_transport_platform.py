"""Transport intentions and authorized cargo at the public PC history boundary."""
import copy
import unittest
from platform_windows import validate_windows_plan
from platform_scenario import PlatformError, compare_history, validate_history_action, validate_history_poses


class TransportPlatformTest(unittest.TestCase):
    def test_windows_pause_is_explicit_known_zero_wait_intents_only(self):
        plan=dict(scenarioId='transport',contentPack=dict(directory='package',unitId='commandCenter'),
            intents=[dict(intentId='start',kind='unload',unloading=True,waitSteps=0)],windowsPausedIntents=['start'])
        contract=dict(scenarioId='transport',history=dict(initialBalance=0),rules=[dict(intentId='start')])
        self.assertEqual('UnloadAction',validate_windows_plan(plan,contract)[0]['actionType'])
        for paused in ('start',['missing'],['start','start'],[True],None):
            bad=copy.deepcopy(plan);bad['windowsPausedIntents']=paused
            with self.subTest(paused=paused),self.assertRaises(PlatformError):validate_windows_plan(bad,contract)
        bad=copy.deepcopy(plan);bad['intents'][0]['waitSteps']=1
        with self.assertRaises(PlatformError):validate_windows_plan(bad,contract)

    def test_history_checks_declared_transport_stage_before_accepting_a_complete_run(self):
        unit=self.unit()
        sample=dict(worldId='w',stepIndex=-1,simulationTime=0,balance=0,units=[unit],events=[])
        action=dict(sequence=3,kind='action',type='SelectAction',unitId='craft',actorPlayerId='p1',result=dict(actionId='select',accepted=True))
        rows=[dict(sequence=0,kind='observation',phase='initial',sample=copy.deepcopy(sample)),
            dict(sequence=1,kind='intent-start',intentId='select'),
            dict(sequence=2,kind='observation',phase='before-action',sample=copy.deepcopy(sample)),action,
            dict(sequence=4,kind='observation',phase='after-action',sample=copy.deepcopy(sample)),
            dict(sequence=5,kind='intent-end',intentId='select')]
        plan=dict(scenarioId='transport-stage',intents=[dict(intentId='select',kind='select')])
        policy=dict(initialBalance=0,transportStages=[dict(intentId='select',passengerCount=1,unloading=False,minimumX=290,maximumX=310)])
        self.assertEqual('passed',compare_history(rows,plan,policy)['status'])
        for key,value in [('passengerCount',0),('unloading',True),('minimumX',301),('maximumX',299)]:
            bad=copy.deepcopy(policy);bad['transportStages'][0][key]=value
            with self.subTest(key=key),self.assertRaises(PlatformError):compare_history(rows,plan,bad)

    def unit(self):
        return dict(unitId='craft', ownerPlayerId='p1', typeId='hovercraft', alive=True,
            constructionProgress=1, height=3, position=dict(x=300,y=200),
            transport=dict(passengerIds=['worker'], passengerCount=1, occupiedSlots=1,
                capacity=4, unloading=False, unloadRemaining=0, canUnload=True))

    def test_windows_plan_requires_actual_load_direction_and_unload_boolean(self):
        for intent, expected in [(dict(intentId='board',kind='load',direction='UP',position=dict(x=300,y=200)), 'LoadAction'),
                                 (dict(intentId='release',kind='unload',unloading=True), 'UnloadAction')]:
            plan=dict(scenarioId='transport',contentPack=dict(directory='package',unitId='commandCenter'),intents=[intent])
            contract=dict(scenarioId='transport',history=dict(initialBalance=0),rules=[dict(intentId=intent['intentId'])])
            self.assertEqual(expected,validate_windows_plan(plan,contract)[0]['actionType'])
            key='direction' if intent['kind']=='load' else 'unloading'
            for value in (None,1,'true','INVALID'):
                bad=copy.deepcopy(plan);bad['intents'][0][key]=value
                with self.subTest(kind=intent['kind'],value=value),self.assertRaises(PlatformError):
                    validate_windows_plan(bad,contract)

    def test_owner_cargo_is_complete_and_passengers_are_absent_from_world_samples(self):
        unit=self.unit()
        validate_history_poses([unit],{})
        for key,value in [('passengerIds',['worker','worker']),('passengerCount',0),('occupiedSlots',5),
                          ('capacity',5),('unloadRemaining',31),('unloading','true'),('canUnload',1)]:
            bad=copy.deepcopy(unit);bad['transport'][key]=value
            with self.subTest(key=key),self.assertRaises(PlatformError):validate_history_poses([bad],{})
        for key in ('transport','height'):
            bad=copy.deepcopy(unit);del bad[key]
            with self.subTest(key=key),self.assertRaises(PlatformError):validate_history_poses([bad],{})
        enemy=copy.deepcopy(unit);enemy['ownerPlayerId']='p2'
        with self.assertRaises(PlatformError):validate_history_poses([enemy],{})
        del enemy['transport'];validate_history_poses([enemy],{})
        worker=dict(unitId='worker',ownerPlayerId='p1',typeId='builder',carriedBy='craft')
        with self.assertRaises(PlatformError):validate_history_poses([unit,worker],{})

    def test_formal_unload_action_is_bound_to_strict_boolean_and_load_to_direction(self):
        for kind,key,wanted,action_type in [('load','direction','UP','LoadAction'),('unload','unloading',True,'UnloadAction')]:
            unit=self.unit()
            sample=dict(worldId='w',stepIndex=0,simulationTime=1,units=[unit])
            action=dict(sequence=1,kind='action',type=action_type,unitId='craft',actorPlayerId='p1',
                        result=dict(actionId='a',accepted=True),**{key:wanted})
            rows=[dict(sequence=0,kind='observation',phase='before-action',sample=sample),action,
                  dict(sequence=2,kind='observation',phase='after-action',sample=copy.deepcopy(sample))]
            intent=dict(intentId='a',kind=kind,**{key:wanted})
            validate_history_action(rows,action,intent)
            for value in (None,1,'true','INVALID'):
                bad=copy.deepcopy(rows);bad[1][key]=value
                with self.subTest(kind=kind,value=value),self.assertRaises(PlatformError):validate_history_action(bad,bad[1],intent)


if __name__=='__main__':
    unittest.main()
