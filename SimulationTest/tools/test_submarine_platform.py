"""Finite depth intentions at the real Windows planner and immutable history seams."""
import copy
import unittest
from platform_windows import validate_windows_plan
from platform_scenario import PlatformError, compare_history, validate_history_action, validate_history_poses


class SubmarinePlatformTest(unittest.TestCase):
    def test_history_retains_launch_appearance_after_depth_change_and_rejects_tampering(self):
        rows,_=self.case()
        initial=copy.deepcopy(rows[0]['sample'])
        initial.update(stepIndex=-1,simulationTime=0,balance=0)
        initial['projectileEvents']=[dict(kind='born',token='shot',sourceUnitId='sub',
            appearance='SUBMARINE_TORPEDO')]
        initial['projectiles']=[dict(token='shot',appearance='SUBMARINE_TORPEDO')]
        later=copy.deepcopy(initial)
        later['projectileEvents']=[]
        unit=later['units'][0];unit['height']=1
        unit['submarine'].update(underwater=False,surfacePresentation=True,maximumSpeed=.8,attackRange=250)
        history=[dict(sequence=0,kind='observation',phase='initial',sample=initial),
                 dict(sequence=1,kind='observation',phase='before-close',sample=later)]
        plan=dict(scenarioId='launch-retention',intents=[])
        policy=dict(initialBalance=0,projectileBirthsRequired=True)
        self.assertEqual('passed',compare_history(history,plan,policy)['status'])
        for style in (None,'STANDARD','SUBMARINE_MISSILE','unknown'):
            changed=copy.deepcopy(history)
            if style is None:del changed[1]['sample']['projectiles'][0]['appearance']
            else:changed[1]['sample']['projectiles'][0]['appearance']=style
            with self.subTest(style=style),self.assertRaises(PlatformError):compare_history(changed,plan,policy)
        changed=copy.deepcopy(history);del changed[0]['sample']['projectileEvents'][0]['appearance']
        with self.assertRaises(PlatformError):compare_history(changed,plan,policy)

    def test_independent_history_requires_complete_consistent_submarine_state(self):
        rows,_=self.case()
        unit=rows[0]['sample']['units'][0]
        validate_history_poses([unit],{})
        for field,value in [('underwater',False),('underwater','true'),('maximumSpeed',.8),('attackRange',250),
                            ('surfaceIntent',None),('verticalSpeed',-.003),('verticalSpeed',float('inf'))]:
            bad=copy.deepcopy(unit);bad['submarine'][field]=value
            with self.subTest(field=field,value=value),self.assertRaises(PlatformError):validate_history_poses([bad],{})
        for field in ('submarine','height'):
            bad=copy.deepcopy(unit);del bad[field]
            with self.subTest(field=field),self.assertRaises(PlatformError):validate_history_poses([bad],{})

    def case(self):
        unit = dict(unitId='sub', ownerPlayerId='p1', typeId='attackSubmarine', alive=True,
                    position=dict(x=500, y=300), height=-8, moveTarget=dict(x=600,y=300), tasks=[dict(kind='MOVE')],
                    submarine=dict(surfaceIntent=False, verticalSpeed=0, underwater=True,
                                   surfacePresentation=False, maximumSpeed=.45, attackRange=180))
        before = dict(worldId='w', stepIndex=4, simulationTime=5, units=[unit])
        after = copy.deepcopy(before); after['units'][0]['submarine']['surfaceIntent'] = True
        action = dict(sequence=1, kind='action', type='SubmarineDepthAction', unitId='sub', actorPlayerId='p1',
                      surface=True, result=dict(actionId='raise', accepted=True))
        rows = [dict(sequence=0,kind='observation',phase='before-action',sample=before), action,
                dict(sequence=2,kind='observation',phase='after-action',sample=after)]
        intent = dict(intentId='raise',kind='submarine-depth',surface=True,unitId='sub')
        return rows,intent

    def test_windows_planner_binds_each_finite_depth_intent_to_the_formal_action(self):
        _,intent=self.case()
        plan=dict(scenarioId='submarine',contentPack=dict(directory='package',unitId='attackSubmarine'),intents=[intent])
        contract=dict(scenarioId='submarine',history=dict(initialBalance=0),rules=[dict(intentId='raise')])
        self.assertEqual('SubmarineDepthAction',validate_windows_plan(plan,contract)[0]['actionType'])
        for value in (None,'true',1,0):
            bad=copy.deepcopy(plan);bad['intents'][0]['surface']=value
            with self.subTest(value=value),self.assertRaises(PlatformError):validate_windows_plan(bad,contract)

    def test_independent_history_checks_the_exact_boolean_and_preserves_the_existing_route_and_physical_state(self):
        rows,intent=self.case()
        validate_history_action(rows,rows[1],intent)
        for value in (False,'true',1,None):
            bad=copy.deepcopy(rows);bad[1]['surface']=value
            with self.subTest(value=value),self.assertRaises(PlatformError):validate_history_action(bad,bad[1],intent)
        for field,value in [('height',1),('moveTarget',None),('tasks',[])]:
            bad=copy.deepcopy(rows);bad[2]['sample']['units'][0][field]=value
            with self.subTest(field=field),self.assertRaises(PlatformError):validate_history_action(bad,bad[1],intent)
        bad=copy.deepcopy(rows);bad[2]['sample']['units'][0]['submarine']['surfaceIntent']=False
        with self.assertRaises(PlatformError):validate_history_action(bad,bad[1],intent)


if __name__=='__main__':unittest.main()
