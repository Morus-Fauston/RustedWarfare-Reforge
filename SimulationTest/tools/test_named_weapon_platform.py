"""Independent comparator examples; real three-format Session runs are separate."""
import copy
import unittest
from platform_scenario import compare_intent, value_at, PlatformError


class NamedWeaponPlatformTest(unittest.TestCase):
    def test_projectile_observation_paths_resolve_index_or_unique_token(self):
        sample = dict(projectiles=[dict(token='shot-1', multiplicity=1,
                                       position=dict(x=3.0, y=4.0))])
        self.assertEqual(3.0, value_at(sample, 'projectiles.0.position.x'))
        self.assertEqual(4.0, value_at(sample, 'projectiles.shot-1.position.y'))
        for path in ('projectiles.1.position.x', 'projectiles.-1.position.x',
                     'projectiles.00.position.x', 'projectiles.missing.position.x'):
            with self.subTest(path=path), self.assertRaises(PlatformError):
                value_at(sample, path)
        duplicate = dict(projectiles=sample['projectiles']*2)
        with self.assertRaises(PlatformError):
            value_at(duplicate, 'projectiles.shot-1.position.x')

    def case(self):
        before = dict(worldId='w', stepIndex=-1, simulationTime=0, units=[
            dict(unitId='a', ownerPlayerId='p1', typeId='named_tank', health=100000, attackTargetId=None),
            dict(unitId='b', ownerPlayerId='p2', typeId='named_tank', health=100000),
            dict(unitId='c', ownerPlayerId='p2', typeId='named_tank', health=100000)])
        after = copy.deepcopy(before); after['units'][0]['attackTargetId'] = 'b'
        step = copy.deepcopy(after); step.update(stepIndex=0, simulationTime=1)
        step['units'][1]['health'] = 99975; step['units'][2]['health'] = 99975
        step['weapons'] = [dict(unitId='a', weaponName='near', targetUnitId='b'),
                           dict(unitId='a', weaponName='far', targetUnitId='c')]
        step['projectileEvents'] = [dict(kind='born', sourceUnitId='a', sourceWeaponName=name,
            targetUnitId=target, occurrences=1) for name, target in [('near', 'b'), ('far', 'c')]]
        rows = [dict(sequence=0, kind='observation', phase='before-action', sample=before),
                dict(sequence=1, kind='action', type='AttackAction', unitId='a', actorPlayerId='p1',
                     targetUnitId='b', result=dict(actionId='attack', accepted=True)),
                dict(sequence=2, kind='observation', phase='after-action', sample=after),
                dict(sequence=3, kind='observation', phase='step', sample=step)]
        intent = dict(intentId='dual', kind='attack', unitId='a', targetUnitId='b')
        rule = dict(intentId='dual', actionType='AttackAction', unitId='a', accepted=True,
            unitIdentity=dict(ownerPlayerId='p1', typeId='named_tank'),
            targetIdentity=dict(ownerPlayerId='p2', typeId='named_tank'),
            assertions=[dict(path='units.a.attackTargetId', operator='equals', value='b')],
            tail=dict(samples=1, assertions=[
                dict(path='weapons.a.near.targetUnitId', operator='equals', value='b'),
                dict(path='weapons.a.far.targetUnitId', operator='equals', value='c')]),
            weaponBirths=[dict(unitId='a', weaponName=name, targetUnitId=target, minimumOccurrences=1)
                          for name, target in [('near', 'b'), ('far', 'c')]])
        return rows, intent, rule

    def test_two_level_weapon_identity_resolves_without_accepting_a_missing_duplicate_or_wrong_secondary(self):
        rows, intent, rule = self.case()
        self.assertEqual(0, compare_intent(rows, intent, rule, -1)['exitCode'])
        for change in ('missing', 'duplicate', 'wrong'):
            changed = copy.deepcopy(rows); weapons = changed[-1]['sample']['weapons']
            if change == 'missing': weapons.pop()
            elif change == 'duplicate': weapons.append(copy.deepcopy(weapons[-1]))
            else: weapons[-1]['targetUnitId'] = 'b'
            with self.subTest(change=change), self.assertRaises(PlatformError):
                compare_intent(changed, intent, rule, -1)

    def test_wrong_firing_weapon_is_rejected_even_when_all_state_paths_are_omitted(self):
        rows, intent, rule = self.case(); del rule['tail']
        rows[-1]['sample']['projectileEvents'][-1]['sourceWeaponName'] = 'near'
        with self.assertRaises(PlatformError): compare_intent(rows, intent, rule, -1)

    def test_less_than_damage_assertion_is_strict_finite_and_numeric(self):
        rows, intent, rule = self.case(); del rule['weaponBirths']
        rule['tail']['assertions'] = [dict(path='units.b.health', operator='lessThan', value=100000)]
        self.assertEqual(0, compare_intent(rows, intent, rule, -1)['exitCode'])
        for value in (100000, True, float('nan'), float('inf'), '99975'):
            changed = copy.deepcopy(rows); changed[-1]['sample']['units'][1]['health'] = value
            with self.subTest(value=value), self.assertRaises(PlatformError): compare_intent(changed, intent, rule, -1)

    def test_birth_occurrences_cannot_be_zero_negative_boolean_or_obtained_from_another_phase(self):
        rows, intent, rule = self.case(); del rule['tail']
        for value in (0, -1, True, 1.5):
            changed = copy.deepcopy(rows); changed[-1]['sample']['projectileEvents'][0]['occurrences'] = value
            with self.subTest(value=value), self.assertRaises(PlatformError): compare_intent(changed, intent, rule, -1)
        rows[-1]['phase'] = 'after-action'
        with self.assertRaises(PlatformError): compare_intent(rows, intent, rule, -1)


if __name__ == '__main__':
    unittest.main()
