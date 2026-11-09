"""Independent platform archive protocol examples, separate from core simulation."""
import copy
import unittest

from platform_scenario import PlatformError, validate_history_poses, validate_launch_appearance


def unit():
    return dict(unitId='aa', typeId='aaBeamGunship', ownerPlayerId='p1', alive=True, height=35,
        energy=dict(current=5.51, maximum=6, recharging=False),
        flightPose=dict(velocity=dict(x=0,y=0),height=35,heightPhase=90,rotorPhase=0,
            rotorHeading=0,rotorHeightOffset=0,fall=dict(speed=0,landed=False,landedInWater=False,settled=False)))


class AaBeamArchiveTest(unittest.TestCase):
    def test_platform_samples_require_complete_energy_and_rotorless_fall_state(self):
        validate_history_poses([unit()], {})
        for fault in ('missing-energy','over-energy','string-lock','full-lock','missing-fall','bad-fall','height-mismatch'):
            value=unit()
            if fault=='missing-energy':value.pop('energy')
            elif fault=='over-energy':value['energy']['current']=7
            elif fault=='string-lock':value['energy']['recharging']='false'
            elif fault=='full-lock':value['energy'].update(current=6,recharging=True)
            elif fault=='missing-fall':value['flightPose'].pop('fall')
            elif fault=='bad-fall':value['flightPose']['fall']['landedInWater']=True
            else:value['flightPose']['height']=34
            with self.subTest(fault=fault), self.assertRaises(PlatformError):
                validate_history_poses([value], {})

    def test_platform_beams_require_authorized_geometry_and_only_the_aa_source(self):
        shot=dict(appearance='AA_BEAM',sourceUnitId='aa',sourceWeaponName='1',kind='born',
                  beam=dict(end=dict(x=300,y=150),endHeight=35,textureOffset=2))
        self.assertEqual('AA_BEAM',validate_launch_appearance(shot,{'aa':unit()}))
        for fault in ('missing','scroll','extra','incompatible','foreign-source'):
            value=copy.deepcopy(shot);source=unit()
            if fault=='missing':value.pop('beam')
            elif fault=='scroll':value['beam']['textureOffset']=-1
            elif fault=='extra':value['beam']['targetUnitId']='hidden'
            elif fault=='incompatible':value['lightning']=dict(end=dict(x=300,y=150),endHeight=35)
            else:source['typeId']='builder'
            with self.subTest(fault=fault), self.assertRaises(PlatformError):
                validate_launch_appearance(value,{'aa':source})


if __name__ == '__main__':
    unittest.main()
