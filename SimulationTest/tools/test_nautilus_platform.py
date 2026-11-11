"""Independent player-view boundaries for submerged mobile production."""
import copy
import unittest
from platform_scenario import validate_history_poses, PlatformError
import platform_scenario


class NautilusPlatformTest(unittest.TestCase):
    def test_three_form_history_rejects_a_forged_intermediate_form_clock_queue_or_event(self):
        terrain=dict(width=2,height=1,tileWidth=20,tileHeight=20,waterCells=[1])
        def unit(form='nautilusSubmarineLand',timer=0,x=10):
            return dict(unitId='mother',typeId=form,ownerPlayerId='p1',alive=True,constructionProgress=1,
                position=dict(x=x,y=10),unitActions=dict(automaticCooldown=timer,actions={} if form.endswith('Land') else
                    dict(dive=dict(queueIndex=None,progress=None,cooldownRemaining=0,available=True))))
        rows=[dict(kind='observation',phase='initial',sample=dict(units=[unit()],events=[])),
              dict(kind='observation',phase='step',sample=dict(units=[unit(timer=60)],
                   events=[dict(unitId='mother',kind='unit-action-automatic')])),
              dict(kind='observation',phase='step',sample=dict(units=[unit(timer=59,x=30)],events=[]))]
        platform_scenario.validate_nautilus_history(rows,dict(nautilusTerrain=terrain))
        for fault in ('form','clock','event','queue'):
            changed=copy.deepcopy(rows)
            if fault=='form': changed[-1]['sample']['units'][0]=unit('nautilusSubmarineSurface',59,30)
            elif fault=='clock':changed[-1]['sample']['units'][0]['unitActions']['automaticCooldown']=0
            elif fault=='event':changed[1]['sample']['events']=[]
            else:changed[-1]['sample']['units'][0]['unitActions']['actions']={'dive':dict(queueIndex=0,progress=.5,cooldownRemaining=0,available=False)}
            with self.subTest(fault=fault),self.assertRaises(PlatformError):
                platform_scenario.validate_nautilus_history(changed,dict(nautilusTerrain=terrain))

    def test_owned_submerged_manifest_allows_the_fourth_internal_single_slot_product_but_no_unload(self):
        mother = dict(unitId='mother', ownerPlayerId='p1', typeId='nautilusSubmarine', alive=True,
            height=-8, energy=dict(current=6, maximum=6, recharging=False),
            constructionProgress=1, unitActions=dict(automaticCooldown=0, actions=dict(surface=dict(
                queueIndex=None, progress=None, cooldownRemaining=0, available=True))),
            transport=dict(passengerIds=['one','two','three','four'], passengerCount=4,
                occupiedSlots=4, capacity=3, unloading=False, unloadRemaining=0, canUnload=False))
        validate_history_poses([mother], {})
        for fault in ('capacity', 'ordinary-unload', 'unloading', 'slots', 'energy', 'missing-energy',
                      'duplicate', 'visible-child', 'foreign-manifest', 'flight'):
            changed = copy.deepcopy(mother)
            units = [changed]
            if fault == 'capacity': changed['transport']['capacity'] = 4
            elif fault == 'ordinary-unload': changed['transport']['canUnload'] = True
            elif fault == 'unloading': changed['transport']['unloading'] = True
            elif fault == 'slots': changed['transport']['occupiedSlots'] = 3
            elif fault == 'energy': changed['energy']['current'] = 0
            elif fault == 'missing-energy': del changed['energy']
            elif fault == 'duplicate': changed['transport']['passengerIds'][1] = 'one'
            elif fault == 'visible-child': units.append(dict(unitId='one', typeId='hoverTank', ownerPlayerId='p1'))
            elif fault == 'foreign-manifest': changed['ownerPlayerId'] = 'p2'
            else: changed['flightPose'] = dict(height=20)
            with self.subTest(fault=fault), self.assertRaises(PlatformError):
                validate_history_poses(units, {})

    def test_surface_accepts_real_weapon_exhaustion_and_unload_while_land_rejects_unload_and_foreign_actions(self):
        legs = [dict(index=i, anchor=dict(x=0,y=0),foot=dict(x=0,y=0),height=0,heading=0,phase='PLANTED') for i in range(6)]
        mother = dict(unitId='mother', ownerPlayerId='p1', typeId='nautilusSubmarineSurface', alive=True,
            constructionProgress=1, height=.1, energy=dict(current=.4,maximum=6,recharging=True),
            unitActions=dict(automaticCooldown=0,actions=dict(dive=dict(queueIndex=None,progress=None,cooldownRemaining=0,available=True))),
            transport=dict(passengerIds=['one'],passengerCount=1,occupiedSlots=1,capacity=3,unloading=False,unloadRemaining=0,canUnload=True),
            mechPose=dict(forwardHeading=0,angularVelocity=0,reversing=False,legs=legs))
        validate_history_poses([mother],dict(mechLegs=dict(nautilusSubmarineSurface=6)))
        for fault in ('unlocked-low-energy','locked-full-energy','missing-action','land-unload','foreign-actions'):
            changed=copy.deepcopy(mother)
            if fault=='unlocked-low-energy': changed['energy']['recharging']=False
            elif fault=='locked-full-energy': changed['energy']['current']=6
            elif fault=='missing-action': changed['unitActions']['actions']={}
            elif fault=='land-unload': changed['typeId']='nautilusSubmarineLand'; changed['unitActions']['actions']={}
            else: changed['ownerPlayerId']='p2'
            with self.subTest(fault=fault),self.assertRaises(PlatformError):
                validate_history_poses([changed],dict(mechLegs=dict(nautilusSubmarineSurface=6,nautilusSubmarineLand=6)))


if __name__ == '__main__':
    unittest.main()
