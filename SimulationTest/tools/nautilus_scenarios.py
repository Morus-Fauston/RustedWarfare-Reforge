"""Owned submerged-production cases with explicit independent completion obligations."""
import hashlib
import json
from pathlib import Path

PUBLIC = Path(__file__).resolve().parents[1] / 'public'


def save(path, value):
    path.write_text(json.dumps(value, indent=2)+'\n', encoding='utf8')


def generate():
    for fmt in ('ini', 'json', 'hjson'):
        root = PUBLIC / f'inputs/nautilus-production/{fmt}'
        pack = root / 'package'
        def ref(path, identity, **extra):
            return dict(id=identity, path='../'+path.relative_to(PUBLIC).as_posix(),
                sha256=hashlib.sha256(path.read_bytes()).hexdigest(), **extra)
        scene = json.loads((PUBLIC / f'scenarios/engineer-{fmt}-short.json').read_text(encoding='utf8'))
        paths = [(pack / f'nautilus.{fmt}', 'nautilusSubmarine', 'nautilus'),
                 (pack / f'hover/hover.{fmt}', 'hoverTank', 'hover-tank')]
        used = {p for p, _, _ in paths}
        scene.update(scenarioId='p009-nautilus-mobile-production')
        scene['content'] = dict(map=ref(root / 'maps/naval-shore.tmx', 'map'),
            unitVariants=[ref(p, identity, loader=kind) for p, identity, kind in paths],
            dependencies=[ref(p, f'dep-{i}') for i, p in enumerate(sorted(
                p for directory in ('package', 'assets') for p in (root / directory).rglob('*') if p.is_file() and p not in used))])
        scene['units'] = [dict(unitId='mother', owner='p1', type='nautilusSubmarine', position=dict(x=500, y=180), heading=90)]
        scene['combatContract'] = dict(worldSeed=12345, initialFrameCounter=-1, unitObjectIds=dict(mother=8), nextObjectId=9)
        scene['economyContract'] = dict(initialBalances=dict(p1=5000, p2=0))
        scene.pop('automaticVision', None)
        scene.pop('maintenanceContract', None)
        scene['actions'] = [dict(actionId=f'queue-{i}', atStep=0, kind='produce', actor='p1', unitId='mother',
            productionTypeId='hoverTank', unitNonce=f'{i:032x}') for i in range(1, 6)]
        scene['actions'].append(dict(actionId='cancel-waiting', atStep=1260, kind='cancel-production', actor='p1', unitId='mother', productionTypeId='hoverTank'))
        scene['termination']['steps'] = [dict(stepIndex=t, delta=1, deltaUnit='sim-tick') for t in range(1261)]
        def rule(identity, step, fields):
            return dict(ruleId=identity, when=dict(phase='after-advance', stepIndex=step),
                assertions=[dict(path=key, operator='equals', value=value) for key, value in fields.items()])
        contract = dict(schemaVersion=1, kind='behavior-contract', contractId=f'public.nautilus-{fmt}.short.v1',
            scope='self-behavior', scenarioIds=[scene['scenarioId']], comparison=dict(numberMode='declared-per-field', tolerances={}),
            approval=dict(status='tool-fixture-only', source='ENG-P009-014 qualified DEC005: .002 base x2, strict used>3 completion gate; owned self behavior'),
            exclusions=['original-compatibility', 'platform-input', 'performance'],
            producedUnits=[dict(unitId=f'produced:p1:{i:032x}', productionActionId=f'queue-{i}',
                producerId='mother', owner='p1', typeId='hoverTank', unitNonce=f'{i:032x}', birthStepIndex=250*i-1) for i in range(1, 5)],
            rules=[
                rule('first-internal-completion', 249, {'units.mother.transport.passengerCount': 1,
                    'units.mother.transport.capacity': 3, 'units.mother.transport.canUnload': False}),
                rule('exactly-three', 749, {'units.mother.transport.occupiedSlots': 3}),
                rule('fourth-completion', 999, {'units.mother.transport.occupiedSlots': 4}),
                rule('head-waits-without-new-identity', 1259, {'units.mother.transport.passengerCount': 4,
                    'production.lastBirthSerial': 4, 'economy.balances.p1': 2750,
                    'economy.queues.mother': [dict(typeId='hoverTank', unitNonce=f'{5:032x}', progress=1,
                        cost=450, rate=.002)]}),
                rule('full-price-cancel', 1260, {'economy.balances.p1': 3200,
                    'production.lastBirthSerial': 4, 'units.mother.transport.passengerCount': 4})])
        save(PUBLIC / f'scenarios/nautilus-{fmt}-short.json', scene)
        save(PUBLIC / f'contracts/nautilus-{fmt}-short.json', contract)


def generate_platform():
    for fmt in ('ini', 'json', 'hjson'):
        base = json.loads((PUBLIC / f'platform/scenarios/transport-{fmt}-production-chain.json').read_text(encoding='utf8'))
        intents = base['intents'][:10]
        def intent(identity, kind, wait=0, point=None, **extra):
            return dict(intentId=identity, kind=kind, waitSteps=wait,
                **(dict(position=dict(x=point[0], y=point[1])) if point else {}), **extra)
        intents.append(intent('upgrade-t2', 'upgrade', 600, upgradeId='upgrade_t2'))
        intents += [intent(f'wait-upgrade-{i}', 'select', 600, (450, 250)) for i in range(3)]
        intents += [intent('finish-upgrade', 'select', 200, (450, 250)),
            intent('produce-mother', 'produce', 600, typeId='nautilusSubmarine')]
        intents += [intent(f'wait-mother-{i}', 'select', 600, (450, 250)) for i in range(5)]
        intents += [intent('leave-shipyard', 'select', 600, (450, 250)),
            intent('select-mother', 'select', point=(449, 385))]
        intents += [intent(f'queue-hover-{i}', 'produce', typeId='hoverTank') for i in range(1, 6)]
        intents += [intent('mobile-production', 'move', 600, (600, 385)),
            intent('stop-mother', 'stop', 600), intent('wait-full-head', 'stop', 100)]
        plan = dict(schemaVersion=1, scenarioId=f'nautilus-{fmt}-production-chain',
            inputs=f'../../inputs/nautilus-production/{fmt}',
            contentPack=dict(directory='production-package', unitId='commandCenter'), intents=intents,
            waitPlan={i['intentId']:dict(minimumSteps=i['waitSteps'],timeoutSeconds=240) for i in intents if i['waitSteps']})
        charges = [('produce-worker',500,'action'),('build-factory',1000,'construction-created'),
            ('upgrade-t2',2000,'action'),('produce-mother',18000,'action')]
        charges += [(f'queue-hover-{i}',450,'action') for i in range(1,6)]
        contract = dict(schemaVersion=1,scenarioId=plan['scenarioId'],rules=[dict(intentId=i['intentId']) for i in intents],
            history=dict(initialBalance=30000,ownerPlayerId='p1',initialOwnedTypes=['commandCenter'],
                initialIncome=dict(unitId='a',typeId='commandCenter',period=10,credits=4.5),
                productionOrder=['builder','nautilusSubmarine'],completedUpgradeTypes=['seaFactoryT2'],
                spend=[dict(intentId=identity,amount=price,at=at) for identity,price,at in charges],
                transportStages=[dict(intentId=identity,typeId='nautilusSubmarine',passengerCount=count,
                    unloading=False,minimumX=500) for identity,count in [('mobile-production',2),('wait-full-head',4)]],
                finalUnits=[dict(ownerPlayerId='p1',typeId=t,fields=dict(alive=True)) for t in
                    ['commandCenter','builder','seaFactoryT2','nautilusSubmarine']]))
        save(PUBLIC / f'platform/scenarios/nautilus-{fmt}-production-chain.json',plan)
        save(PUBLIC / f'platform/contracts/nautilus-{fmt}-production-chain.json',contract)


if __name__ == '__main__':
    generate()
    generate_platform()
