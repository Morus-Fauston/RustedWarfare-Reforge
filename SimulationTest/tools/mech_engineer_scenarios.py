"""Owned engineer scenarios with independent DEC007 coefficient obligations."""
from pathlib import Path
import hashlib
import json

PUBLIC = Path(__file__).resolve().parents[1] / 'public'


def save(path, value):
    path.write_text(json.dumps(value, indent=2) + '\n', encoding='utf8')


def generate():
    for fmt in ('ini', 'json', 'hjson'):
        root = PUBLIC / f'inputs/mech-engineer-production/{fmt}'
        pack = root / 'production-package'
        manifest = json.loads((pack / 'pack.json').read_text(encoding='utf8'))
        scene = json.loads((PUBLIC / f'scenarios/repairbay-{fmt}-short.json').read_text(encoding='utf8'))

        def ref(path, identity, **extra):
            return dict(id=identity, path='../' + path.relative_to(PUBLIC).as_posix(),
                        sha256=hashlib.sha256(path.read_bytes()).hexdigest(), **extra)

        names = dict(mech='mechGun', heavy='mechHeavyMissile', engineer='mechEngineer')
        paths = [(pack / entry['file'], 'factory' if entry['type'] == 'mech-factory' else entry['type']) for entry in manifest['units']]
        used = {p for p, _ in paths}
        scene.update(scenarioId='p009-engineer-work')
        scene['content'] = dict(map=ref(root / 'maps/p001-controlled.tmx', 'map'),
            unitVariants=[ref(p, names.get(p.stem, p.stem), loader=kind) for p, kind in paths],
            dependencies=[ref(p, f'dep-{i}') for i, p in enumerate(sorted(
                p for directory in ('production-package', 'assets') for p in (root / directory).rglob('*')
                if p.is_file() and p not in used))])
        scene['units'] = [dict(unitId=identity, owner='p1', type=kind, position=dict(x=x, y=210), heading=90)
                          for identity, kind, x in [('engineer', 'mechEngineer', 110),
                              ('worker', 'builder', 170), ('worker2', 'builder', 210)]]
        scene['combatContract'] = dict(worldSeed=12345, initialFrameCounter=-1,
            unitObjectIds=dict(engineer=1, worker=2, worker2=3), nextObjectId=4)
        scene.pop('maintenanceContract', None)
        scene['actions'] = [dict(actionId=identity, atStep=step, kind=kind, actor='p1', unitId='engineer',
            **(dict(targetUnitId=target, append=False) if target else {}))
            for identity, step, kind, target in [('terminal', 0, 'reclaim', 'worker'),
                ('after-terminal', 155, 'stop', None), ('weaken', 157, 'reclaim', 'worker2'),
                ('pause', 158, 'stop', None), ('manual', 159, 'repair', 'worker2'), ('stop-repair', 160, 'stop', None)]]
        scene['termination']['steps'] = [dict(stepIndex=t, delta=1, deltaUnit='sim-tick') for t in range(200)]

        def rule(identity, step, fields):
            return dict(ruleId=identity, when=dict(phase='after-advance', stepIndex=step),
                assertions=[dict(path=key, operator='equals', value=value) for key, value in fields.items()])

        contract = dict(schemaVersion=1, kind='behavior-contract', contractId=f'public.engineer-{fmt}.short.v1',
            scope='self-behavior', scenarioIds=[scene['scenarioId']],
            comparison=dict(numberMode='declared-per-field', tolerances={}),
            approval=dict(status='tool-fixture-only', source='ENG-P009-013 DEC007 qualified engineer .3 repair, 5.1 reclaim, 1/40 income; owned self behavior'),
            exclusions=['original-compatibility', 'platform-input', 'performance'],
            reclaimedUnits=[dict(unitId='worker', reclaimActionId='terminal', workerId='engineer', owner='p1', removalStepIndex=154)],
            rules=[rule('first-reclaim', 0, {'units.worker.health': 168.47000122070312}),
                rule('terminal-and-three-income-payments', 154, {'economy.preciseBalances.p1': 16403.000005960464}),
                rule('manual-repair', 159, {'units.worker2.health': 168.77000427246094}),
                rule('stopped-hp', 194, {'units.worker2.health': 168.77000427246094}),
                rule('automatic-selection-only', 195, {'units.worker2.health': 168.77000427246094,
                    'units.engineer.tasks.automatic-repair:engineer.automaticRepair': True}),
                rule('automatic-work', 196, {'units.worker2.health': 169.07000732421875})])
        save(PUBLIC / f'scenarios/engineer-{fmt}-short.json', scene)
        save(PUBLIC / f'contracts/engineer-{fmt}-short.json', contract)


def generate_platform():
    for fmt in ('ini', 'json', 'hjson'):
        base = json.loads((PUBLIC / f'platform/scenarios/bunker-{fmt}-production-chain.json').read_text(encoding='utf8'))
        intents = base['intents'][:8]

        def intent(identity, kind, wait=0, point=None, **extra):
            return dict(intentId=identity, kind=kind, waitSteps=wait,
                **(dict(position=dict(x=point[0], y=point[1])) if point else {}), **extra)

        intents += [intent('upgrade-t2', 'upgrade', 600, upgradeId='upgrade_t2')]
        intents += [intent(f'wait-upgrade-{i}', 'select', 600, (270, 150)) for i in range(3)]
        intents += [intent('finish-upgrade', 'select', 200, (270, 150)),
            intent('produce-engineer', 'produce', 600, typeId='mechEngineer'),
            intent('wait-engineer', 'select', 600, (270, 150)),
            intent('leave-factory', 'select', 600, (270, 150)),
            intent('select-engineer', 'select', point=(270, 265)),
            intent('cross-cliff-and-fight', 'move', 400, (410, 270)),
            intent('stop-engineer', 'stop', 40),
            intent('build-mobile', 'build', 600, (430, 310), typeId='mechGun'),
            intent('reselect-after-build', 'select', point=(410, 270)),
            intent('clear-engineer-target', 'move', 200, (300, 270)),
            intent('return-to-mobile', 'move', 200, (410, 270)), intent('stop-at-mobile', 'stop'),
            intent('select-mobile', 'select', point=(430, 310)),
            intent('mobile-under-fire', 'move', 60, (450, 310)),
            intent('return-mobile', 'move', 60, (430, 310)), intent('stop-mobile', 'stop', 10),
            intent('secure-worksite', 'select', 200, (430, 310)),
            intent('select-engineer-again', 'select', point=(410, 270)),
            intent('reclaim-partial', 'reclaim', 60, (430, 310)), intent('stop-reclaim', 'stop'),
            intent('manual-repair', 'repair', 1, (430, 310)), intent('stop-repair', 'stop'),
            intent('automatic-repair', 'select', 60, (410, 270)),
            intent('reclaim-terminal', 'reclaim', 600, (430, 310)),
            intent('return-cliff', 'move', 400, (300, 270)), intent('stop-return', 'stop', 40),
            intent('build-bay', 'build', 600, (270, 350), typeId='repairbay')]
        plan = dict(schemaVersion=1, scenarioId=f'engineer-{fmt}-production-chain',
            inputs=f'../../inputs/mech-engineer-production/{fmt}',
            contentPack=dict(directory='production-package', unitId='commandCenter'), intents=intents,
            waitPlan={i['intentId']: dict(minimumSteps=i['waitSteps'], timeoutSeconds=240)
                      for i in intents if i.get('waitSteps', 0)})
        contract = dict(schemaVersion=1, scenarioId=plan['scenarioId'], rules=[dict(intentId=i['intentId']) for i in intents],
            history=dict(initialBalance=16000, ownerPlayerId='p1', initialOwnedTypes=['commandCenter'],
                initialIncome=dict(unitId='a', typeId='commandCenter', period=10, credits=4.5),
                producedIncome=[dict(typeId='mechEngineer', period=40, credits=1)],
                productionOrder=['builder', 'mechEngineer'], completedUpgradeTypes=['mechFactoryT2'],
                projectileBirthsRequired=True, mechLegs=dict(mechGun=2, mechEngineer=4),
                minimumBirths=[dict(ownerPlayerId='p1', typeId='mechEngineer', weaponNames=['body'], targetUnitId='c')],
                spend=[dict(intentId=i, amount=price, at=at) for i, price, at in [
                    ('produce-worker', 500, 'action'), ('build-t1', 1000, 'construction-created'),
                    ('upgrade-t2', 4000, 'action'), ('produce-engineer', 5000, 'action'),
                    ('build-mobile', 600, 'construction-created'), ('build-bay', 1500, 'construction-created')]],
                reclaimRefunds=[dict(intentId='reclaim-terminal', typeId='mechGun', price=600)],
                finalUnits=[dict(ownerPlayerId='p1', typeId=t, fields=dict(alive=True))
                            for t in ['commandCenter', 'builder', 'mechFactoryT2', 'mechEngineer', 'repairbay']]))
        save(PUBLIC / f'platform/scenarios/engineer-{fmt}-production-chain.json', plan)
        save(PUBLIC / f'platform/contracts/engineer-{fmt}-production-chain.json', contract)


if __name__ == '__main__':
    generate()
    generate_platform()
