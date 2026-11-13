"""Independent platform archive checks for native amphibious physical state."""
import copy
import unittest
from platform_scenario import PlatformError, validate_history_poses, validate_history_action, compare_history
from platform_windows import validate_windows_plan


class AmphibiousPlatformTest(unittest.TestCase):
    def test_a_declared_unreachable_move_requires_its_cancellation_receipt_and_empty_route(self):
        rows,_=self.case();rows[1].update(type='MoveAction',target=dict(x=250,y=300))
        old=rows[0]['sample']['units'][0];old['moveTarget']=None;old['tasks']=[]
        new=copy.deepcopy(old);rows[2]['sample']['units']=[new]
        rows[1]['result']['events']=[dict(kind=k,unitId='jet') for k in ('movement-cancelled','path-unreachable')]
        intent=dict(intentId='shore',kind='move',position=dict(x=250,y=300),pathUnreachable=True)
        validate_history_action(rows,rows[1],intent)
        for fault in ('missing-event','wrong-unit','route','tasks'):
            bad=copy.deepcopy(rows)
            if fault=='missing-event':bad[1]['result']['events'].pop()
            elif fault=='wrong-unit':bad[1]['result']['events'][1]['unitId']='other'
            elif fault=='route':bad[2]['sample']['units'][0]['moveTarget']=dict(x=250,y=300)
            else:bad[2]['sample']['units'][0]['tasks']=[dict(kind='MOVE')]
            with self.subTest(fault=fault),self.assertRaises(PlatformError):validate_history_action(bad,bad[1],intent)

    def case(self):
        unit=dict(unitId='jet',ownerPlayerId='p1',typeId='amphibiousJet',alive=True,
            position=dict(x=500,y=300),height=-8,moveTarget=dict(x=550,y=300),tasks=[dict(kind='MOVE')],
            amphibiousJet=dict(flyingIntent=False,verticalSpeed=.1,underwater=True,surfacePresentation=False,
                movementType='WATER',maximumSpeed=.4,attackRange=100,wingFold=1,phase=20,sinkSpeed=0,settled=False,canDive=False))
        before=dict(worldId='w',stepIndex=4,simulationTime=5,units=[unit])
        after=copy.deepcopy(before);after['units'][0]['amphibiousJet']['flyingIntent']=True
        row=dict(sequence=1,kind='action',type='AmphibiousFlightAction',unitId='jet',actorPlayerId='p1',
            flying=True,result=dict(actionId='fly',accepted=True))
        rows=[dict(sequence=0,kind='observation',phase='before-action',sample=before),row,
              dict(sequence=2,kind='observation',phase='after-action',sample=after)]
        return rows,dict(intentId='fly',kind='amphibious-flight',flying=True,unitId='jet')

    def test_flight_action_only_changes_the_exact_owned_intention(self):
        rows,intent=self.case()
        validate_history_action(rows,rows[1],intent)
        for value in (False,'true',1,None):
            bad=copy.deepcopy(rows);bad[1]['flying']=value
            with self.subTest(value=value),self.assertRaises(PlatformError):validate_history_action(bad,bad[1],intent)
        for field,value in [('height',0),('moveTarget',None),('tasks',[])]:
            bad=copy.deepcopy(rows);bad[2]['sample']['units'][0][field]=value
            with self.subTest(field=field),self.assertRaises(PlatformError):validate_history_action(bad,bad[1],intent)
        bad=copy.deepcopy(rows);bad[2]['sample']['units'][0]['amphibiousJet']['wingFold']=0
        with self.assertRaises(PlatformError):validate_history_action(bad,bad[1],intent)

    def test_windows_planner_binds_a_strict_flying_boolean_to_the_action(self):
        _,intent=self.case()
        plan=dict(scenarioId='jet',contentPack=dict(directory='package',unitId='amphibiousJet'),intents=[intent])
        contract=dict(scenarioId='jet',history=dict(initialBalance=0),rules=[dict(intentId='fly')])
        self.assertEqual('AmphibiousFlightAction',validate_windows_plan(plan,contract)[0]['actionType'])
        for value in (None,'true',1,0):
            bad=copy.deepcopy(plan);bad['intents'][0]['flying']=value
            with self.subTest(value=value),self.assertRaises(PlatformError):validate_windows_plan(bad,contract)

    def test_history_accepts_actual_lightning_and_rejects_missing_or_forged_geometry(self):
        rows,_=self.case();sample=copy.deepcopy(rows[0]['sample'])
        sample.update(stepIndex=-1,simulationTime=0,balance=0)
        sample['units'][0]['constructionProgress']=1
        shot=dict(token='shot',sourceUnitId='jet',sourceWeaponName='left',appearance='AMPHIBIOUS_LIGHTNING',
            lightning=dict(end=dict(x=550,y=200),endHeight=-8))
        sample['projectiles']=[{k:v for k,v in shot.items() if k not in ('sourceUnitId','sourceWeaponName')}]
        sample['projectileEvents']=[dict(kind='born',**shot)]
        history=[dict(sequence=0,kind='observation',phase='initial',sample=sample)]
        plan=dict(scenarioId='jet-shot',intents=[]);policy=dict(initialBalance=0,projectileBirthsRequired=True)
        self.assertEqual('passed',compare_history(history,plan,policy)['status'])
        for field in ('lightning','appearance'):
            for channel in ('projectiles','projectileEvents'):
                bad=copy.deepcopy(history);del bad[0]['sample'][channel][0][field]
                with self.subTest(channel=channel,field=field),self.assertRaises(PlatformError):compare_history(bad,plan,policy)
        for value in (None,dict(end=dict(x=1,y=2)),dict(end=dict(x=True,y=2),endHeight=0),
                      dict(end=dict(x=1,y=2),endHeight=float('inf'))):
            bad=copy.deepcopy(history);bad[0]['sample']['projectiles'][0]['lightning']=value
            with self.subTest(value=value),self.assertRaises(PlatformError):compare_history(bad,plan,policy)

    def test_complete_physical_state_and_owner_controls_are_required_and_enemy_controls_redacted(self):
        unit = dict(unitId='jet', ownerPlayerId='p1', typeId='amphibiousJet', alive=True,
                    constructionProgress=1, height=-8,
                    amphibiousJet=dict(flyingIntent=False, verticalSpeed=.1, underwater=True,
                        surfacePresentation=False, movementType='WATER', maximumSpeed=.4, attackRange=100,
                        wingFold=1, phase=0, sinkSpeed=0, settled=False, canDive=False))
        validate_history_poses([unit], {})
        for field, value in [('underwater',False), ('maximumSpeed',1.4), ('attackRange',170),
                ('movementType','AIR'), ('wingFold',1.1), ('phase',361), ('sinkSpeed',-1),
                ('settled',0), ('verticalSpeed',float('inf')), ('flyingIntent',None), ('canDive',True)]:
            bad=copy.deepcopy(unit); bad['amphibiousJet'][field]=value
            with self.subTest(field=field), self.assertRaises(PlatformError):
                validate_history_poses([bad], {})
        for field in ('amphibiousJet','height'):
            bad=copy.deepcopy(unit);del bad[field]
            with self.subTest(field=field),self.assertRaises(PlatformError):validate_history_poses([bad],{})
        enemy=copy.deepcopy(unit);enemy['ownerPlayerId']='p2'
        for key in ('flyingIntent','verticalSpeed','canDive'):enemy['amphibiousJet'][key]=None
        validate_history_poses([enemy],{})
        for key,value in [('flyingIntent',False),('verticalSpeed',.1),('canDive',False)]:
            bad=copy.deepcopy(enemy);bad['amphibiousJet'][key]=value
            with self.subTest(key=key),self.assertRaises(PlatformError):validate_history_poses([bad],{})


if __name__=='__main__':unittest.main()
