"""Independent player-view boundaries for submerged mobile production."""
import copy
import unittest
from platform_scenario import validate_history_poses, PlatformError


class NautilusPlatformTest(unittest.TestCase):
    def test_owned_submerged_manifest_allows_the_fourth_internal_single_slot_product_but_no_unload(self):
        mother = dict(unitId='mother', ownerPlayerId='p1', typeId='nautilusSubmarine', alive=True,
            height=-8, energy=dict(current=6, maximum=6, recharging=False),
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


if __name__ == '__main__':
    unittest.main()
