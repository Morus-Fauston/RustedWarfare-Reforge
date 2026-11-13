"""Independent produced-income clock checks, including forged birth/credit archives."""
import copy
import unittest
from platform_scenario import compare_history, validate_history_action, PlatformError


class MechEngineerPlatformTest(unittest.TestCase):
    def test_maintenance_click_is_bound_to_the_native_mobile_body_radius(self):
        engineer = dict(unitId='engineer', ownerPlayerId='p1', typeId='mechEngineer', alive=True)
        mobile = dict(unitId='mobile', ownerPlayerId='p1', typeId='mechGun', alive=True,
                      position=dict(x=111, y=100))
        sample = dict(worldId='w', stepIndex=0, simulationTime=1, units=[engineer, mobile])
        rows = [dict(sequence=0, kind='observation', phase='before-action', sample=sample),
                dict(sequence=1, kind='action', type='RepairUnitAction', unitId='engineer',
                     actorPlayerId='p1', targetUnitId='mobile'),
                dict(sequence=2, kind='observation', phase='after-action', sample=copy.deepcopy(sample))]
        intent = dict(kind='repair', position=dict(x=100, y=100))
        validate_history_action(rows, rows[1], intent)
        for fault in ('outside', 'unknown-body', 'dead', 'carried', 'self', 'wrong-id'):
            changed = copy.deepcopy(rows)
            target = changed[0]['sample']['units'][1]
            if fault == 'outside': target['position']['x'] = 111.001
            elif fault == 'unknown-body': target['typeId'] = 'unknown'
            elif fault == 'dead': target['alive'] = False
            elif fault == 'carried': target['carriedBy'] = 'carrier'
            elif fault == 'self': changed[1]['targetUnitId'] = 'engineer'
            else: changed[1]['targetUnitId'] = 'absent'
            with self.subTest(fault=fault), self.assertRaises(PlatformError):
                validate_history_action(changed, changed[1], intent)

    def test_visible_produced_income_starts_after_its_fresh_birth(self):
        center = dict(unitId='center', ownerPlayerId='p1', typeId='commandCenter', alive=True,
                      constructionProgress=1, position=dict(x=1, y=2))
        engineer = dict(unitId='engineer', ownerPlayerId='p1', typeId='mechEngineer', alive=True,
                        constructionProgress=1, carriedBy=None, position=dict(x=3, y=2))

        def sample(step, credits):
            return dict(worldId='w', stepIndex=step, simulationTime=step+1, balance=int(credits), credits=credits,
                units=copy.deepcopy([center] if step < 0 else [center, engineer]),
                events=[dict(kind='unit-produced', unitId='engineer')] if step == 0 else [])

        rows = [dict(kind='observation', phase='initial', sample=sample(-1, 100)),
            dict(kind='observation', phase='step', sample=sample(0, 100)),
            dict(kind='observation', phase='before-action', sample=sample(0, 100)),
            dict(kind='action', type='SelectAction', unitId='center', actorPlayerId='p1',
                 result=dict(actionId='select', accepted=True)),
            dict(kind='observation', phase='after-action', sample=sample(0, 100)),
            dict(kind='observation', phase='step', sample=sample(1, 100)),
            dict(kind='observation', phase='step', sample=sample(2, 101)),
            dict(kind='observation', phase='before-close', sample=sample(2, 101))]
        rows = [dict(sequence=i, **row) for i, row in enumerate(rows)]
        plan = dict(scenarioId='income-fixture', intents=[dict(intentId='select', kind='select',
                    position=dict(x=1, y=2), waitSteps=2)])
        policy = dict(initialBalance=100, initialIncome=dict(unitId='center', typeId='commandCenter', period=10, credits=4.5),
                      producedIncome=[dict(typeId='mechEngineer', period=2, credits=1)])
        anchors = [dict(intentId='select', afterSequence=1, endSequence=6)]
        self.assertEqual(0, compare_history(rows, plan, policy, anchors)['exitCode'])
        for fault in ('birth-income', 'early', 'missing', 'double', 'birth-event', 'duplicate-birth', 'foreign', 'imprecise', 'absent'):
            changed = copy.deepcopy(rows)
            if fault == 'birth-income': changed[1]['sample'].update(balance=101, credits=101)
            elif fault == 'early': changed[5]['sample'].update(balance=101, credits=101)
            elif fault == 'missing': changed[6]['sample'].update(balance=100, credits=100)
            elif fault == 'double': changed[6]['sample'].update(balance=102, credits=102)
            elif fault == 'birth-event': changed[1]['sample']['events'] = []
            elif fault == 'duplicate-birth': changed[5]['sample']['events'] = [dict(kind='unit-produced', unitId='engineer')]
            elif fault == 'foreign': changed[6]['sample']['units'][1]['ownerPlayerId'] = 'p2'
            elif fault == 'imprecise': changed[6]['sample']['credits'] = 101.1
            else: changed[6]['sample']['units'].pop()
            with self.subTest(fault=fault), self.assertRaises(PlatformError): compare_history(changed, plan, policy, anchors)
        for invalid in (dict(period=0), dict(period=True), dict(credits=float('nan')), dict(extra=True)):
            changed = copy.deepcopy(policy)
            changed['producedIncome'][0].update(invalid)
            with self.subTest(invalid=invalid), self.assertRaises(PlatformError): compare_history(rows, plan, changed, anchors)


if __name__ == '__main__':
    unittest.main()
