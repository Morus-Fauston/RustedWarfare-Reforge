"""Fixed maintenance action schedules and public content identities, no expectations."""
from pathlib import Path
import hashlib
import json

ROOT = Path(__file__).parent


def generate():
    scenario = json.loads((ROOT / 'scenarios/build-economy-short.json').read_text(encoding='utf8'))
    scenario['scenarioId'] = 'public.maintenance-repair-short.v1'
    content = scenario['content']
    refs = [content['map']] + content['unitVariants'] + content['dependencies']
    for ref in refs:
        ref['path'] = ref['path'].replace('build-economy/upgrades', 'maintenance-defense/maintenance').replace('maps/economy-chain.tmx', 'maps/p001-controlled.tmx')
        ref['sha256'] = hashlib.sha256((ROOT / 'scenarios' / ref['path']).read_bytes()).hexdigest()
    scenario['automaticVision']['grid'] = dict(width=30, height=20)
    scenario['units'] = [dict(unitId=id, owner='p1', type=type, position=dict(x=x, y=y), heading=heading)
        for id, type, x, y, heading in [('a', 'builder', 120, 170, 90), ('b', 'builder', 120, 210, 90), ('f', 'landFactory', 190, 170, 0)]]
    scenario['combatContract'].update(worldSeed=1, unitObjectIds=dict(a=1, b=2, f=3), nextObjectId=4)
    scenario['economyContract']['initialBalances'].update(p1=1750, p2=0)
    scenario['maintenanceContract'] = dict(basePrices=dict(commandCenter=3000, landFactory=700,
        extractorT1=700, extractorT2=2100, extractorT3=6100, fabricatorT1=2200, fabricatorT2=6600, fabricatorT3=14100))
    scenario['actions'] = []
    for worker in ['a', 'b']:
        scenario['actions'].append(dict(actionId='reclaim-' + worker, atStep=0, actor='p1', kind='reclaim', unitId=worker, targetUnitId='f', append=False))
    for worker in ['a', 'b']:
        scenario['actions'].append(dict(actionId='stop-' + worker, atStep=80, actor='p1', kind='stop', unitId=worker))
        scenario['actions'].append(dict(actionId='repair-' + worker, atStep=80, actor='p1', kind='repair', unitId=worker, targetUnitId='f', append=False))
    scenario['termination']['steps'] = [dict(stepIndex=i, delta=1, deltaUnit='sim-tick') for i in range(380)]
    (ROOT / 'scenarios/maintenance-repair-short.json').write_bytes((json.dumps(scenario, indent=2) + '\n').encode())
    import copy
    hidden = copy.deepcopy(scenario)
    hidden['scenarioId'] = 'public.maintenance-privacy-short.v1'
    hidden['units'].append(dict(unitId='hidden', owner='p2', type='landFactory', position=dict(x=510, y=330), heading=0))
    hidden['combatContract'].update(unitObjectIds=dict(a=1, b=2, f=3, hidden=4), nextObjectId=5)
    hidden['actions'] = [dict(actionId=id, atStep=step, actor=actor, kind=kind, unitId='a', targetUnitId=target, append=False)
        for id, step, actor, kind, target in [('hidden-repair', 1, 'p1', 'repair', 'hidden'),
            ('missing-repair', 2, 'p1', 'repair', 'missing'), ('foreign-worker', 3, 'p2', 'reclaim', 'f')]]
    hidden['termination']['steps'] = hidden['termination']['steps'][:10]
    (ROOT / 'scenarios/maintenance-privacy-short.json').write_bytes((json.dumps(hidden, indent=2) + '\n').encode())
    scenario['scenarioId'] = 'public.maintenance-queue-short.v1'
    scenario['units'] = scenario['units'][:1]
    scenario['combatContract'].update(unitObjectIds=dict(a=1), nextObjectId=2)
    target = 'constructed:p1:' + 'a' * 32
    second = 'constructed:p1:' + 'b' * 32
    def build(id, step, nonce, append):
        return dict(actionId=id, atStep=step, actor='p1', kind='build', unitId='a',
            buildingTypeId='landFactory', target=dict(x=190, y=170), buildingNonce=nonce * 32, append=append)
    scenario['actions'] = [build('build', 0, 'a', False),
        dict(actionId='stop', atStep=1, actor='p1', kind='stop', unitId='a'),
        dict(actionId='reclaim', atStep=1, actor='p1', kind='reclaim', unitId='a', targetUnitId=target, append=False),
        build('rebuild', 1, 'b', True),
        dict(actionId='tail', atStep=1, actor='p1', kind='patrol', unitId='a', target=dict(x=450, y=290), append=True),
        dict(actionId='pause-rebuild', atStep=102, actor='p1', kind='stop', unitId='a'),
        dict(actionId='resume', atStep=102, actor='p1', kind='resume-construction', unitId='a', targetUnitId=second, append=False)]
    scenario['termination']['steps'] = [dict(stepIndex=i, delta=1, deltaUnit='sim-tick') for i in range(150)]
    (ROOT / 'scenarios/maintenance-queue-short.json').write_bytes((json.dumps(scenario, indent=2) + '\n').encode())

    # A single finite center opens this entire run. Later actions consume exactly
    # the declared survivors and buildings; there are no host-side state changes.
    scenario['scenarioId'] = 'public.maintenance-defense-chain.v1'
    scenario.pop('automaticVision')  # Mechanism/short suites cover player redaction; this long capture is host-only.
    root = ROOT / 'inputs/maintenance-defense/construction'
    refs = content['unitVariants']
    for ref in refs:
        ref['path'] = ref['path'].replace('maintenance-defense/maintenance', 'maintenance-defense/construction')
    for id, filename, loader in [('c_turret_t1', 'turret_t1.ini', 'turret-construction'),
            ('c_turret_t2_gun', 'turret_t2_gun.ini', 'turret'),
            ('c_turret_t1_artillery', 'turret_t1_artillery.ini', 'turret'),
            ('laserDefence', 'laserDefence.properties', 'point-defense-construction')]:
        refs.append(dict(id=id, path='../inputs/maintenance-defense/construction/units/' + filename, loader=loader))
    content['map']['path'] = '../inputs/maintenance-defense/construction/maps/p001-controlled.tmx'
    declared = {Path(ref['path']).as_posix() for ref in [content['map']] + refs}
    content['dependencies'] = [dict(id='dep-' + file.relative_to(root).as_posix().replace('/', '-'),
        path='../inputs/maintenance-defense/construction/' + file.relative_to(root).as_posix())
        for file in sorted(root.rglob('*')) if file.is_file() and
        '../inputs/maintenance-defense/construction/' + file.relative_to(root).as_posix() not in declared]
    for ref in [content['map']] + refs + content['dependencies']:
        ref['sha256'] = hashlib.sha256((ROOT / 'scenarios' / ref['path']).read_bytes()).hexdigest()
    scenario['players'] = [dict(playerId='p1', control='local'), dict(playerId='p2', control='local')]
    scenario['units'] = [dict(unitId='h', owner='p1', type='commandCenter', position=dict(x=110, y=50), heading=0)] + [
        dict(unitId='z' + str(i + 1).zfill(2), owner='p2', type='c_tank',
            position=dict(x=510 + i // 6 * 22, y=30 + i % 6 * 10), heading=270) for i in range(12)]
    scenario['combatContract'].update(worldSeed=12345, unitObjectIds=dict(h=10, **{'z'+str(i+1).zfill(2): i+11 for i in range(12)}), nextObjectId=23)
    scenario['maintenanceContract']['basePrices'].update(c_turret_t1=500, c_turret_t2_gun=1500, c_turret_t1_artillery=2100, laserDefence=1200)
    worker = 'produced:p1:' + '0' * 32
    helper = 'produced:p1:' + '1' * 32
    factory = 'constructed:p1:' + 'b' * 32
    def move(id, step, unit, x, y):
        return dict(actionId=id, atStep=step, actor='p1', kind='move', unitId=unit, target=dict(x=x, y=y))
    def stop(id, step, unit):
        return dict(actionId=id, atStep=step, actor='p1', kind='stop', unitId=unit)
    def produce(id, step, unit, type, nonce):
        return dict(actionId=id, atStep=step, actor='p1', kind='produce', unitId=unit, productionTypeId=type, unitNonce=nonce * 32)
    def construct(id, step, unit, type, x, y, nonce):
        return dict(actionId=id, atStep=step, actor='p1', kind='build', unitId=unit, buildingTypeId=type,
            target=dict(x=x, y=y), buildingNonce=nonce * 32, append=False)
    scenario['actions'] = [produce('produce-worker', 0, 'h', 'builder', '0'),
        move('to-resource', 501, worker, 110, 290), move('face-resource', 1000, worker, 190, 290), stop('hold-resource', 1100, worker),
        construct('extractor', 1300, worker, 'extractorT1', 190, 290, 'a'),
        move('to-factory', 2500, worker, 110, 330), move('face-factory', 2800, worker, 190, 330), stop('hold-factory', 2900, worker),
        construct('factory', 3200, worker, 'landFactory', 190, 330, 'b'),
        produce('factory-worker', 4500, factory, 'builder', '1'),
        move('helper-away', 5001, helper, 350, 330), produce('factory-tank', 5100, factory, 'c_tank', '2'),
        move('tank-away', 5601, 'produced:p1:' + '2' * 32, 450, 350),
        move('helper-face-site', 6000, helper, 270, 330), stop('helper-hold', 6100, helper)]
    scenario['actions'] += [dict(actionId='siege-' + str(i+1), atStep=6500, actor='p2', kind='attack',
        unitId='z'+str(i+1).zfill(2), targetUnitId='h') for i in range(12)]
    scenario['actions'] += [move('to-defense-lane', 10500, worker, 410, 330),
        move('to-defense', 11000, worker, 410, 190), move('face-defense', 11400, worker, 330, 190), stop('hold-defense', 11500, worker),
        construct('turret', 11600, worker, 'c_turret_t1', 330, 190, 'c'),
        produce('surviving-factory-worker', 13000, factory, 'builder', '3'),
        construct('point-defense', 14000, helper, 'laserDefence', 270, 330, 'd')]
    scenario['actions'].sort(key=lambda action: action['atStep'])
    scenario['termination']['steps'] = [dict(stepIndex=i, delta=1, deltaUnit='sim-tick') for i in range(16000)]
    (ROOT / 'scenarios/maintenance-defense-chain.json').write_bytes((json.dumps(scenario, indent=2) + '\n').encode())


if __name__ == '__main__':
    generate()
