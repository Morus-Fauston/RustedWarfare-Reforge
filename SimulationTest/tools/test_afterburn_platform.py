"""Finite normal action plans and independently declared platform form boundaries."""
import copy
import unittest

from platform_windows import validate_windows_plan
from platform_scenario import PlatformError, compare_history
from test_aa_beam_archive import unit


class AfterburnPlatformTest(unittest.TestCase):
    def test_every_step_checks_the_integer_cooldown_completion_event_and_automatic_timer(self):
        initial=unit()
        queued=copy.deepcopy(initial)
        queued['unitActions']['actions']['afterBurn'].update(queueIndex=0,progress=0,available=False)
        converted=copy.deepcopy(initial)
        converted['typeId']='aaBeamGunship_afterburn';converted['energy']['current']=6
        converted['unitActions']['actions']['afterBurn'].update(cooldownRemaining=25000,available=False)
        converted['unitActions']['actions']['cancelBurn']=dict(queueIndex=None,progress=None,cooldownRemaining=0,available=True)
        def sample(value,step=-1,events=None):
            return dict(worldId='w',stepIndex=step,simulationTime=step+1,balance=0,units=[copy.deepcopy(value)],events=events or [])
        rows=[dict(sequence=0,kind='observation',phase='initial',sample=sample(initial)),
              dict(sequence=1,kind='intent-start',intentId='burn'),
              dict(sequence=2,kind='observation',phase='before-action',sample=sample(initial)),
              dict(sequence=3,kind='action',type='UnitAction',unitId='aa',actorPlayerId='p1',actionName='afterBurn',cancelQueued=False,
                   result=dict(actionId='burn',accepted=True)),
              dict(sequence=4,kind='observation',phase='after-action',sample=sample(queued)),
              dict(sequence=5,kind='observation',phase='step',sample=sample(converted,0,[dict(kind='unit-action-completed',unitId='aa')])),
              dict(sequence=6,kind='intent-end',intentId='burn')]
        plan=dict(scenarioId='afterburn',intents=[dict(intentId='burn',kind='unit-action',actionName='afterBurn',waitSteps=1)])
        policy=dict(initialBalance=0,unitActionStages=[dict(intentId='burn',typeId='aaBeamGunship_afterburn',cooldown='active')])
        self.assertEqual('passed',compare_history(rows,plan,policy)['status'])
        for fault in ('clock','auto-timer','missing-event','wrong-name','queue-cancel','synchronous-conversion'):
            changed=copy.deepcopy(rows)
            if fault=='clock':changed[5]['sample']['units'][0]['unitActions']['actions']['afterBurn']['cooldownRemaining']=24999
            elif fault=='auto-timer':changed[5]['sample']['units'][0]['unitActions']['automaticCooldown']=60
            elif fault=='missing-event':changed[5]['sample']['events']=[]
            elif fault=='wrong-name':changed[3]['actionName']='cancelBurn'
            elif fault=='queue-cancel':changed[3]['cancelQueued']=True
            else:changed[4]['sample']['units'][0]['typeId']='aaBeamGunship_afterburn'
            with self.subTest(fault=fault),self.assertRaises(PlatformError):compare_history(changed,plan,policy)

    def test_windows_accepts_only_the_two_normal_action_names_and_no_queue_cancellation(self):
        plan=dict(scenarioId='afterburn',contentPack=dict(directory='package',unitId='commandCenter'),
                  intents=[dict(intentId='burn',kind='unit-action',actionName='afterBurn',waitSteps=1)])
        contract=dict(scenarioId='afterburn',history=dict(initialBalance=0),rules=[dict(intentId='burn')])
        self.assertEqual('UnitAction',validate_windows_plan(plan,contract)[0]['actionType'])
        for field,value in [('actionName','autoSwitchBack'),('actionName',True),('actionName',[]),
                            ('cancelQueued',True),('cancelQueued',False),('unknown',0)]:
            changed=copy.deepcopy(plan);changed['intents'][0][field]=value
            with self.subTest(field=field,value=value),self.assertRaises(PlatformError):
                validate_windows_plan(changed,contract)

    def test_form_and_cooldown_stage_is_consumed_and_malformed_or_forged_stages_fail(self):
        value=unit()
        sample=dict(worldId='w',stepIndex=-1,simulationTime=0,balance=0,units=[value],events=[])
        rows=[dict(sequence=0,kind='observation',phase='initial',sample=copy.deepcopy(sample)),
              dict(sequence=1,kind='intent-start',intentId='select'),
              dict(sequence=2,kind='observation',phase='before-action',sample=copy.deepcopy(sample)),
              dict(sequence=3,kind='action',type='SelectAction',unitId='aa',actorPlayerId='p1',
                   result=dict(actionId='select',accepted=True)),
              dict(sequence=4,kind='observation',phase='after-action',sample=copy.deepcopy(sample)),
              dict(sequence=5,kind='intent-end',intentId='select')]
        plan=dict(scenarioId='afterburn',intents=[dict(intentId='select',kind='select')])
        policy=dict(initialBalance=0,unitActionStages=[dict(intentId='select',typeId='aaBeamGunship',cooldown='ready')])
        self.assertEqual('passed',compare_history(rows,plan,policy)['status'])
        for field,value in [('typeId','aaBeamGunship_afterburn'),('typeId','builder'),('typeId',[]),
                            ('cooldown','active'),('cooldown',False),('intentId','missing'),('unknown',0)]:
            changed=copy.deepcopy(policy);changed['unitActionStages'][0][field]=value
            with self.subTest(field=field,value=value),self.assertRaises(PlatformError):
                compare_history(rows,plan,changed)
        for stages in (None,{},[policy['unitActionStages'][0]]*2):
            changed=copy.deepcopy(policy);changed['unitActionStages']=stages
            with self.subTest(stages=stages),self.assertRaises(PlatformError):compare_history(rows,plan,changed)


if __name__=='__main__':unittest.main()
