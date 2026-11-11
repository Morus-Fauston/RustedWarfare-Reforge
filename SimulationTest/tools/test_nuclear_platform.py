"""Adversarial independent nuclear player-history checks."""
import copy
import unittest
from platform_scenario import PlatformError, validate_unit_action_intent
from nuclear_platform import validate_nuclear_history, validate_nuclear_state


def silo(progress=None):
    queued=progress is not None
    return dict(unitId='silo',typeId='nukeLauncherC',ownerPlayerId='p1',alive=True,constructionProgress=1,
        carriedBy=None,nuclearDoors=0,unitActions=dict(automaticCooldown=0,stockpile=0,
            queue=[dict(name='buildNuke',progress=progress,target=None)] if queued else [],actions=dict(
                buildNuke=dict(queueIndex=0 if queued else None,progress=progress,cooldownRemaining=0,available=True),
                launchNuke=dict(queueIndex=None,progress=None,cooldownRemaining=0,available=False))))


class NuclearPlatformTest(unittest.TestCase):
    def test_nuclear_controls_validate_finite_ground_points_and_reject_undeclared_parameters(self):
        good=dict(intentId='launch',kind='unit-action',actionName='launchNuke',waitSteps=0,target=dict(x=540,y=300))
        validate_unit_action_intent(good)
        for changes in [dict(target=dict(x=True,y=0)),dict(target=dict(x=float('inf'),y=0)),dict(target={}),
                        dict(cancelQueued=True),dict(extra=1),dict(actionName='unknown')]:
            with self.subTest(changes=changes),self.assertRaises(PlatformError):
                validate_unit_action_intent(dict(good,**changes))

    def test_intermediate_stock_queue_and_door_forgery_are_rejected_without_using_a_final_snapshot_as_expected_state(self):
        rows=[dict(kind='observation',phase='initial',sample=dict(units=[silo()],events=[])),
            dict(kind='action',type='UnitAction',unitId='silo',actorPlayerId='p1',actionName='buildNuke',cancelQueued=False,
                result=dict(accepted=True)),
            dict(kind='observation',phase='call',sample=dict(units=[silo(0)],events=[])),
            dict(kind='observation',phase='step',sample=dict(units=[silo(.0003000000142492354)],events=[]))]
        validate_nuclear_history(rows,{})
        for fault in ('stock','queue','doors','availability','enemy','unknown-field'):
            changed=copy.deepcopy(rows);unit=changed[-1]['sample']['units'][0]
            if fault=='stock':unit['unitActions']['stockpile']=1
            elif fault=='queue':unit['unitActions']['queue']=[]
            elif fault=='doors':unit['nuclearDoors']=.5
            elif fault=='availability':unit['unitActions']['actions']['buildNuke']['available']=False
            elif fault=='enemy':unit['ownerPlayerId']='p2'
            else:unit['unitActions']['extra']=0
            with self.subTest(fault=fault),self.assertRaises(PlatformError):validate_nuclear_history(changed,{})

    def test_native_ground_click_uses_existing_pixel_footprint_and_keeps_the_formal_queue_point_exact(self):
        from platform_scenario import validate_history_action
        intent=dict(intentId='launch',kind='unit-action',actionName='launchNuke',target=dict(x=540,y=300),waitSteps=0)
        before=silo();before['unitActions']['stockpile']=1
        after=copy.deepcopy(before);after['unitActions']['stockpile']=0
        actual=dict(x=540.4,y=300.2)
        after['unitActions']['queue']=[dict(name='launchNuke',progress=0,target=copy.deepcopy(actual))]
        call=dict(sequence=1,kind='action',type='UnitAction',actorPlayerId='p1',unitId='silo',
            actionName='launchNuke',cancelQueued=False,target=actual,result=dict(accepted=True))
        def sample(unit):return dict(worldId='w',stepIndex=0,simulationTime=1,units=[unit])
        rows=[dict(sequence=0,kind='observation',phase='before-action',sample=sample(before)),call,
            dict(sequence=2,kind='observation',phase='after-action',sample=sample(after))]
        geometry=dict(worldWidth=600,worldHeight=400,viewport=dict(width=960,height=640,screenWidth=960,screenHeight=640))
        validate_history_action(rows,call,intent,geometry)
        with self.assertRaises(PlatformError):validate_history_action(rows,call,intent)
        changed=copy.deepcopy(rows);changed[1]['target']['x']=542
        with self.assertRaises(PlatformError):validate_history_action(changed,changed[1],intent,geometry)
        changed=copy.deepcopy(rows);changed[2]['sample']['units'][0]['unitActions']['queue'][0]['target']['x']=540
        with self.assertRaises(PlatformError):validate_history_action(changed,changed[1],intent,geometry)

    def test_admission_limit_is_not_a_hard_stock_cap(self):
        unit=silo();state=unit['unitActions'];state['stockpile']=5
        state['actions']['buildNuke']['available']=False
        state['actions']['launchNuke']['available']=True
        validate_nuclear_state(unit,'p1')


if __name__=='__main__':unittest.main()
