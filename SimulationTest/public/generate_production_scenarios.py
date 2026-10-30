"""Author fixed scenarios; contracts are hand-written independent expectations, never traces."""
from pathlib import Path
import hashlib
import json

ROOT = Path(__file__).parent


def generate():
    def identity(name, filename, loader=None):
        path = ROOT / 'inputs/production-combat' / filename
        item = dict(id=name, path='../inputs/production-combat/' + filename,
                    sha256=hashlib.sha256(path.read_bytes()).hexdigest())
        if loader:
            item['loader'] = loader
        return item
    dependencies = [identity('terrain', 'production/assets/terrain.png')]
    for folder, names in [('production', ['tank.png', 'tank_dead.png', 'tank_turret.png',
                                          'factory.png', 'factory_back.png', 'factory_dead.png']),
                          ('center', ['builder.png', 'builder_dead.png'])]:
        dependencies += [identity(folder + '-' + name, folder + '/units/' + name) for name in names]
    nonce = '0' * 32
    product = 'produced:p1:' + nonce
    for name, count in [('production-combat-short', 505), ('production-combat-chain', 1201)]:
        scenario = dict(schemaVersion=1, kind='scenario', scenarioId='public.' + name + '.v1',
            content=dict(map=identity('map', 'production/maps/production-chain.tmx'),
                unitVariants=[identity('c_tank', 'production/units/tank.ini', 'combat'),
                              identity('landFactory', 'production/units/landFactory.properties', 'factory'),
                              identity('builder', 'center/units/builder.properties', 'builder')], dependencies=dependencies),
            coordinateModel=dict(id='public.coordinate.v1', space='2d', xAxis='x-right', yAxis='y-down',
                origin='top-left', unit='world-unit', valueType='double', precision='ieee754-binary64'),
            timeModel=dict(id='public.time.v1', deltaUnit='sim-tick', stepRule='fixed-explicit-delta', precision='ieee754-binary64'),
            angleUnit='degree-clockwise-zero-up', movementContract=dict(pathPointRadius=16, targetRadius=4),
            automaticVision=dict(retention='MEMORY', pathSource='PLAYER_KNOWLEDGE', shareAlliedVision=False,
                                 observers=['p1', 'p2'], grid=dict(width=10, height=5)),
            combatContract=dict(worldSeed=12345, initialFrameCounter=-1, unitObjectIds=dict(f=10, enemy=11), nextObjectId=12),
            economyContract=dict(initialBalances=dict(p1=1750, p2=0)),
            players=[dict(playerId='p1', control='local', teamId='one'), dict(playerId='p2', control='local', teamId='two')],
            units=[dict(unitId='f', owner='p1', type='landFactory', position=dict(x=50, y=50), heading=0),
                   dict(unitId='enemy', owner='p2', type='builder', position=dict(x=170, y=50), heading=270)],
            actions=[dict(actionId='produce', atStep=0, actor='p1', kind='produce', unitId='f', productionTypeId='c_tank', unitNonce=nonce),
                dict(actionId='produce-tail', atStep=0, actor='p1', kind='produce', unitId='f', productionTypeId='c_tank', unitNonce='1'*32),
                dict(actionId='cancel-tail', atStep=0, actor='p1', kind='cancel-production', unitId='f', productionTypeId='c_tank'),
                dict(actionId='foreign-produce', atStep=1, actor='p2', kind='produce', unitId='f', productionTypeId='c_tank', unitNonce='2'*32),
                dict(actionId='select-product', atStep=501, actor='p1', kind='select', unitId=product),
                dict(actionId='stop-product', atStep=501, actor='p1', kind='stop', unitId=product),
                dict(actionId='foreign-stop', atStep=502, actor='p2', kind='stop', unitId=product),
                dict(actionId='attack', atStep=502, actor='p1', kind='attack', unitId=product, targetUnitId='enemy')],
            termination=dict(kind='explicit-steps', required=True,
                             steps=[dict(stepIndex=i, delta=1, deltaUnit='sim-tick') for i in range(count)]))
        (ROOT / 'scenarios' / (name + '.json')).write_bytes((json.dumps(scenario, indent=2) + '\n').encode('utf8'))


def generate_upgrade():
    source = json.loads((ROOT / 'scenarios/production-combat-short.json').read_text(encoding='utf8'))
    def ref(name, path, loader=None):
        file = ROOT / 'inputs/production-combat' / path
        value = dict(id=name, path='../inputs/production-combat/' + path,
                     sha256=hashlib.sha256(file.read_bytes()).hexdigest())
        if loader:
            value['loader'] = loader
        return value
    source.update(scenarioId='public.upgrade-cancel-short.v1',
        content=dict(map=ref('map', 'production/maps/production-chain.tmx'),
            unitVariants=[ref('c_turret_t1', 'upgrade/units/turret_t1.ini', 'turret-upgrades'),
                ref('c_turret_t2_gun', 'upgrade/units/turret_t2_gun.ini', 'turret'),
                ref('c_turret_t1_artillery', 'upgrade/units/turret_t1_artillery.ini', 'turret')],
            dependencies=[ref('terrain', 'production/assets/terrain.png'),
                ref('template', 'upgrade/units/turret_common_land.ini')] +
                [ref(name, 'upgrade/units/' + name) for name in ['turret.png', 'turret_dead.png', 'turret_top.png']]),
        combatContract=dict(worldSeed=12345, initialFrameCounter=-1, unitObjectIds=dict(t=10), nextObjectId=11),
        economyContract=dict(initialBalances=dict(p1=3000, p2=0)),
        units=[dict(unitId='t', owner='p1', type='c_turret_t1', position=dict(x=50, y=50), heading=90)],
        actions=[dict(actionId='gun', atStep=0, actor='p1', kind='upgrade', unitId='t', upgradeId='upgrade_gun'),
            dict(actionId='foreign', atStep=1, actor='p2', kind='upgrade', unitId='t', upgradeId='upgrade_artillery'),
            dict(actionId='cancel-gun', atStep=1, actor='p1', kind='cancel-upgrade', unitId='t', upgradeId='upgrade_gun'),
            dict(actionId='artillery', atStep=2, actor='p1', kind='upgrade', unitId='t', upgradeId='upgrade_artillery'),
            dict(actionId='cancel-artillery', atStep=2, actor='p1', kind='cancel-upgrade', unitId='t', upgradeId='upgrade_artillery')],
        termination=dict(kind='explicit-steps', required=True,
            steps=[dict(stepIndex=i, delta=1, deltaUnit='sim-tick') for i in range(4)]))
    (ROOT / 'scenarios/upgrade-cancel-short.json').write_text(json.dumps(source, indent=2) + '\n', encoding='utf8')


def generate_defense():
    source = json.loads((ROOT / 'scenarios/production-combat-short.json').read_text(encoding='utf8'))
    def ref(name, path, loader=None):
        file = ROOT / 'inputs/production-combat' / path
        value = dict(id=name, path='../inputs/production-combat/' + path,
                     sha256=hashlib.sha256(file.read_bytes()).hexdigest())
        if loader:
            value['loader'] = loader
        return value
    source.update(scenarioId='public.point-defense-short.v1',
        content=dict(map=ref('map', 'production/maps/production-chain.tmx'),
            unitVariants=[ref('c_tank', 'production/units/tank.ini', 'combat'),
                ref('laserDefence', 'defense/units/laserDefence.properties', 'point-defense')],
            dependencies=[ref('terrain', 'production/assets/terrain.png')] +
                [ref(name, 'production/units/' + name) for name in ['tank.png', 'tank_dead.png', 'tank_turret.png']] +
                [ref(name, 'defense/units/' + name) for name in ['laser.png', 'laser_dead.png']]),
        combatContract=dict(worldSeed=12345, initialFrameCounter=-1, unitObjectIds=dict(d=10, s=11), nextObjectId=12),
        units=[dict(unitId='d', owner='p1', type='laserDefence', position=dict(x=50, y=50), heading=0),
            dict(unitId='s', owner='p2', type='c_tank', position=dict(x=170, y=50), heading=270)],
        actions=[dict(actionId='hostile-attack', atStep=0, actor='p2', kind='attack', unitId='s', targetUnitId='d')],
        termination=dict(kind='explicit-steps', required=True,
            steps=[dict(stepIndex=i, delta=1, deltaUnit='sim-tick') for i in range(12)]))
    (ROOT / 'scenarios/point-defense-short.json').write_text(json.dumps(source, indent=2) + '\n', encoding='utf8')


if __name__ == '__main__':
    generate()
    generate_upgrade()
    generate_defense()
