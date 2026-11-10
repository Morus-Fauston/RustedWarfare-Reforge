"""Independent target-bound terminal refund archive checks."""
import copy
import unittest
from platform_scenario import compare_history, PlatformError


class RepairBayPlatformTest(unittest.TestCase):
    def test_native_terminal_refund_requires_the_owned_target_action_removal_and_single_event(self):
        bay = dict(unitId='bay', ownerPlayerId='p1', typeId='repairbay', alive=True, health=1000, maxHealth=1000)
        tank = dict(unitId='tank', ownerPlayerId='p1', typeId='c_tank', alive=True, health=1, maxHealth=210,
                    position=dict(x=170, y=210))
        def sample(step, removed=False):
            return dict(worldId='w', stepIndex=step, simulationTime=max(0, step+1),
                balance=380 if removed else 100, units=copy.deepcopy([bay] if removed else [bay, tank]),
                events=[dict(kind='building-reclaimed', unitId='tank')] if removed else [],
                weapons=[], projectiles=[], projectileEvents=[])
        before=sample(-1);after=sample(0,True)
        rows=[dict(kind='observation',phase='initial',sample=before),dict(kind='intent-start',intentId='reclaim'),
              dict(kind='observation',phase='before-action',sample=before),
              dict(kind='action',type='ReclaimUnitAction',unitId='bay',actorPlayerId='p1',targetUnitId='tank',append=False,
                   result=dict(actionId='click-1',accepted=True)),
              dict(kind='observation',phase='after-action',sample=before),
              dict(kind='observation',phase='step',sample=after),dict(kind='intent-end',intentId='reclaim'),
              dict(kind='observation',phase='before-close',sample=after),dict(kind='closed')]
        rows=[dict(sequence=i,**row) for i,row in enumerate(rows)]
        # 350 * Float(.8), promoted before multiplication, truncates to 280 only at the balance view.
        # Precise mode is separately covered by actual income-bearing production archives.
        rows[5]['sample']['balance']=380.00000417232513
        rows[7]['sample']['balance']=380.00000417232513
        plan=dict(scenarioId='repairbay-example',intents=[dict(intentId='reclaim',kind='reclaim',targetUnitId='tank',position=dict(x=170,y=210),waitSteps=1)])
        policy=dict(initialBalance=100,reclaimRefunds=[dict(intentId='reclaim',typeId='c_tank',price=350)])
        self.assertEqual(0,compare_history(rows,plan,policy)['exitCode'])
        for fault in ('event','duplicate','retained','foreign','wrong-target','wrong-price','combat-death'):
            changed=copy.deepcopy(rows)
            if fault=='event':changed[5]['sample']['events']=[]
            elif fault=='duplicate':changed[5]['sample']['events']*=2
            elif fault=='retained':changed[5]['sample']['units'].append(tank)
            elif fault=='foreign':changed[2]['sample']['units'][1]['ownerPlayerId']='p2'
            elif fault=='wrong-target':changed[3]['targetUnitId']='bay'
            elif fault=='wrong-price':changed[5]['sample']['balance']+=1
            else:changed[5]['sample']['events'].append(dict(kind='unit-died',unitId='tank'))
            with self.subTest(fault=fault),self.assertRaises(PlatformError):compare_history(changed,plan,policy)


if __name__ == '__main__':
    unittest.main()
