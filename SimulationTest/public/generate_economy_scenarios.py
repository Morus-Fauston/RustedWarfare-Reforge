"""Author only the continuous command schedule; expected values live in contracts."""
from pathlib import Path
import hashlib
import json

ROOT = Path(__file__).parent


def generate():
    source = json.loads((ROOT / 'scenarios/build-economy-short.json').read_text(encoding='utf8'))
    source['scenarioId'] = 'public.build-economy-chain.v1'
    source['content']['map']['path'] = '../inputs/build-economy/upgrades/maps/operating-chain.tmx'
    for ref in [source['content']['map']] + source['content']['unitVariants'] + source['content']['dependencies']:
        ref['sha256'] = hashlib.sha256((ROOT / 'scenarios' / ref['path']).read_bytes()).hexdigest()
    source['automaticVision'].update(observers=['p1'], grid=dict(width=10, height=14))
    source['units'] = [dict(unitId='h', owner='p1', type='commandCenter', position=dict(x=110, y=50), heading=0)]
    source['combatContract'].update(unitObjectIds=dict(h=10), nextObjectId=11)
    source['economyContract']['initialBalances']['p1'] = 1750
    worker = 'produced:p1:' + '0' * 32
    extractor = 'constructed:p1:' + 'a' * 32
    source['actions'] = [
        dict(actionId='produce-worker', atStep=0, actor='p1', kind='produce', unitId='h', productionTypeId='builder', unitNonce='0' * 32),
        dict(actionId='stop-exit', atStep=501, actor='p1', kind='stop', unitId=worker),
        dict(actionId='build-extractor', atStep=501, actor='p1', kind='build', unitId=worker,
             buildingTypeId='extractorT1', target=dict(x=110, y=110), buildingNonce='a' * 32, append=False),
        dict(actionId='move-worker', atStep=1600, actor='p1', kind='move', unitId=worker, target=dict(x=110, y=90)),
        dict(actionId='stop-worker', atStep=1700, actor='p1', kind='stop', unitId=worker),
        dict(actionId='build-factory', atStep=1701, actor='p1', kind='build', unitId=worker,
             buildingTypeId='landFactory', target=dict(x=110, y=170), buildingNonce='b' * 32, append=False),
        dict(actionId='upgrade-extractor', atStep=3400, actor='p1', kind='upgrade', unitId=extractor, upgradeId='upgrade_t2'),
    ]
    source['termination']['steps'] = [dict(stepIndex=i, delta=1, deltaUnit='sim-tick') for i in range(5100)]
    (ROOT / 'scenarios/build-economy-chain.json').write_bytes((json.dumps(source, indent=2) + '\n').encode())


if __name__ == '__main__':
    generate()
