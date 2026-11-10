"""Fixed public repair-bay contracts from qualified coefficients, never from run output."""
from pathlib import Path
import hashlib
import json

PUBLIC = Path(__file__).resolve().parents[1] / 'public'


def save(path, value):
    path.write_text(json.dumps(value, indent=2) + '\n', encoding='utf8')


def generate():
    for fmt in ('ini', 'json', 'hjson'):
        root = PUBLIC / f'inputs/repairbay-production/{fmt}'
        scene = json.loads((PUBLIC / f'scenarios/heavy-mech-{fmt}-short.json').read_text(encoding='utf8'))
        def ref(path, identity, **extra):
            return dict(id=identity, path='../' + path.relative_to(PUBLIC).as_posix(),
                        sha256=hashlib.sha256(path.read_bytes()).hexdigest(), **extra)
        refs = [ref(root / f'production-package/{stem}.{fmt}', identity, loader=kind)
                for stem, identity, kind in [('repairbay', 'repairbay', 'repair-bay'), ('builder', 'builder', 'builder')]]
        used = {root / f'production-package/{stem}.{fmt}' for stem in ('repairbay', 'builder')}
        scene.update(scenarioId='p009-repairbay-work')
        scene.pop('testScene', None)
        scene['content'] = dict(map=ref(root / 'maps/p001-controlled.tmx', 'map'), unitVariants=refs,
            dependencies=[ref(p, f'dep-{i}') for i, p in enumerate(sorted(
                p for directory in ('production-package', 'assets') for p in (root / directory).rglob('*')
                if p.is_file() and p not in used))])
        scene['automaticVision']['grid'] = dict(width=30, height=20)
        scene['units'] = [dict(unitId=identity, owner='p1', type=kind, position=dict(x=x, y=y), heading=270)
                          for identity, kind, x, y in [('bay', 'repairbay', 110, 210),
                              ('worker', 'builder', 170, 210), ('worker2', 'builder', 110, 270)]]
        scene['combatContract'] = dict(worldSeed=12345, initialFrameCounter=-1,
            unitObjectIds=dict(bay=1, worker=2, worker2=3), nextObjectId=4)
        scene['economyContract'] = dict(initialBalances=dict(p1=16000, p2=0))
        scene['maintenanceContract'] = dict(basePrices=dict(repairbay=1500))
        scene['actions'] = [dict(actionId=identity, atStep=step, kind=kind, actor='p1', unitId='bay',
            **(dict(targetUnitId=target, append=False) if target else {}))
            for identity, step, kind, target in [('terminal', 0, 'reclaim', 'worker'),
                ('after-terminal', 182, 'stop', None), ('weaken', 183, 'reclaim', 'worker2'),
                ('pause', 184, 'stop', None), ('manual', 185, 'repair', 'worker2'), ('stop-repair', 186, 'stop', None)]]
        scene['termination']['steps'] = [dict(stepIndex=t, delta=1, deltaUnit='sim-tick') for t in range(230)]
        def rule(identity, step, fields):
            return dict(ruleId=identity, when=dict(phase='after-advance', stepIndex=step),
                assertions=[dict(path=key, operator='equals', value=value) for key, value in fields.items()])
        contract = dict(schemaVersion=1, kind='behavior-contract', contractId=f'public.repairbay-{fmt}.short.v1',
            scope='self-behavior', scenarioIds=[scene['scenarioId']],
            comparison=dict(numberMode='declared-per-field', tolerances={}),
            approval=dict(status='tool-fixture-only', source='ENG-P009-012 qualified DEC007 native repair and reclaim coefficients; owned self behavior'),
            exclusions=['original-compatibility', 'platform-input', 'performance'],
            reclaimedUnits=[dict(unitId='worker', reclaimActionId='terminal', workerId='bay', owner='p1', removalStepIndex=181)],
            rules=[rule('first-reclaim', 0, {'units.worker.health': 168.97999572753906}),
                rule('native-terminal-refund', 181, {'economy.preciseBalances.p1': 16400.000005960464}),
                rule('manual-repair', 185, {'units.worker2.health': 169.17999267578125}),
                rule('stopped-hp', 222, {'units.worker2.health': 169.17999267578125}),
                rule('automatic-selection-only', 223, {'units.worker2.health': 169.17999267578125,
                    'units.bay.tasks.automatic-repair:bay.automaticRepair': True}),
                rule('automatic-work', 224, {'units.worker2.health': 169.37998962402344})])
        save(PUBLIC / f'scenarios/repairbay-{fmt}-short.json', scene)
        save(PUBLIC / f'contracts/repairbay-{fmt}-short.json', contract)


def generate_platform():
    for fmt in ('ini', 'json', 'hjson'):
        def intent(identity, kind, wait=0, point=None, **extra):
            return dict(intentId=identity, kind=kind, waitSteps=wait,
                **(dict(position=dict(x=point[0], y=point[1])) if point else {}), **extra)
        intents = [intent('select-center', 'select', point=(130, 130)),
            intent('produce-worker', 'produce', 600, typeId='builder'),
            intent('wait-worker-exit', 'select', 100, (130, 130)),
            intent('select-worker', 'select', point=(129, 220)),
            intent('place-worker', 'move', 300, (230, 200)), intent('stop-worker', 'stop'),
            intent('build-bay', 'build', 600, (270, 150), typeId='repairbay'),
            intent('finish-bay', 'select', 600, (270, 150)),
            intent('select-worker-again', 'select', point=(230, 200)),
            intent('build-factory', 'build', 600, (270, 250), typeId='landFactory'),
            intent('finish-factory', 'select', 600, (270, 250)),
            intent('produce-tank', 'produce', 600, typeId='c_tank'),
            intent('wait-tank-exit', 'select', 100, (270, 250)),
            intent('select-tank', 'select', point=(269, 337)),
            intent('approach-enemy', 'move', 160, (400, 300)),
            intent('return-to-bay', 'move', 250, (190, 290)), intent('stop-tank', 'stop', 40),
            intent('select-bay', 'select', point=(270, 150)),
            intent('reclaim-partial', 'reclaim', 10, (190, 290)), intent('stop-reclaim', 'stop'),
            intent('repair-mobile', 'repair', 1, (190, 290)), intent('stop-repair', 'stop'),
            intent('automatic-repair', 'select', 60, (270, 150)),
            intent('reclaim-terminal', 'reclaim', 600, (190, 290))]
        plan = dict(schemaVersion=1, scenarioId=f'repairbay-{fmt}-production-chain',
            inputs=f'../../inputs/repairbay-production/{fmt}',
            contentPack=dict(directory='normal-package', unitId='commandCenter'), intents=intents,
            waitPlan={i['intentId']: dict(minimumSteps=i['waitSteps'], timeoutSeconds=240)
                      for i in intents if i['waitSteps']})
        contract = dict(schemaVersion=1, scenarioId=plan['scenarioId'], rules=[dict(intentId=i['intentId']) for i in intents],
            history=dict(initialBalance=16000, ownerPlayerId='p1', initialOwnedTypes=['commandCenter'],
                initialIncome=dict(unitId='a', typeId='commandCenter', period=10, credits=4.5),
                productionOrder=['builder', 'c_tank'], projectileBirthsRequired=True,
                spend=[dict(intentId=i, amount=price, at=at) for i, price, at in [
                    ('produce-worker', 500, 'action'), ('build-bay', 1500, 'construction-created'),
                    ('build-factory', 700, 'construction-created'), ('produce-tank', 350, 'action')]],
                reclaimRefunds=[dict(intentId='reclaim-terminal', typeId='c_tank', price=350)],
                finalUnits=[dict(ownerPlayerId='p1', typeId=t, fields=dict(alive=True))
                            for t in ['commandCenter', 'builder', 'repairbay', 'landFactory']]))
        save(PUBLIC / f'platform/scenarios/repairbay-{fmt}-production-chain.json', plan)
        save(PUBLIC / f'platform/contracts/repairbay-{fmt}-production-chain.json', contract)


if __name__ == '__main__':
    generate()
    generate_platform()
