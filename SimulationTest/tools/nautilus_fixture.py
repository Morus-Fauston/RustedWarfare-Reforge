"""Owned Nautilus production fixtures; geometric art, no original asset reads."""
import json
from pathlib import Path
import shutil
from aa_beam_fixture import png
from bunker_fixture import render

ROOT = Path(__file__).resolve().parents[2]
DEST = ROOT / 'SimulationTest/public/inputs/nautilus-production'


def weapons(sub):
    result = dict(attack=dict(canAttack=True, canAttackFlyingUnits=not sub, canAttackLandUnits=True,
        canAttackUnderwaterUnits=sub, canAttackNotTouchingWaterUnits=not sub, turretSize=20,
        turretTurnSpeed=1.8, maxAttackRange=240, shootDelay=300),
        turret_1=dict(x=0, y=27, idleDir=0, invisible=True, canShoot=False))
    for index, (x, y) in enumerate([(10, 22), (-10, 22), (-10, 14), (10, 14), (10, 4), (-10, 4)]):
        result[f'turret_{index+2}'] = dict(x=x, y=y, idleDir=0, invisible=True,
            canAttackFlyingUnits=not sub, canAttackLandUnits=True, canAttackUnderwaterUnits=sub,
            canAttackNotTouchingWaterUnits=not sub, projectile='2' if sub else '3', size=1, turnSpeed=40,
            energyUsage=1, warmup=index*20, **({'linkDelayWithTurret': '2'} if index else {}))
    result['projectile_2'] = dict(directDamage=50, life=250, speed=.15, targetSpeed=1.2,
        ballistic=True, ballistic_delaymove_height=40, ballistic_height=-2, autoTargetingOnDeadTarget=True)
    result['projectile_3'] = dict(directDamage=35, life=240, speed=.3, targetSpeed=6,
        ballistic=True, ballistic_delaymove_height=40, ballistic_height=50, autoTargetingOnDeadTarget=True,
        areaDamage=180, areaRadius=55)
    return result


def generate():
    core = dict(name='nautilusSubmarine', maxHp=4000, radius=36, price=18000, buildSpeed=.0003,
        techLevel=2, fogOfWarSightRange=24, selfRegenRate=.1, nanoFactorySpeed=2,
        productionUnits='hoverTank', energyMax=6, energyRegen=.01, energyNeedsToRechargeToFull=True,
        maxTransportingUnits=3, transportUnitsRequireMovementType='WATER,HOVER,OVER_CLIFF_WATER',
        transportUnitsCanUnloadUnits=False, transportUnitsRequireTag='nonexistent',
        transportUnitsKeepBuiltUnits=True, exit_x=0, exit_y=30, exit_dirOffset=0,
        exit_moveAwayAmount=110, exit_heightOffset=0, image='body.png', image_dead='dead.png')
    movement = dict(movementType='WATER', moveSpeed=.45, moveAccelerationSpeed=.015,
        moveDecelerationSpeed=.05, reverseSpeedPercentage=.6, maxTurnSpeed=.8, turnAcceleration=.05,
        targetHeight=-8, targetHeightDrift=.1, heightChangeRate=.005)
    for fmt in ('ini', 'json', 'hjson'):
        root = DEST / fmt
        pack = root / 'package'
        pack.mkdir(parents=True, exist_ok=True)
        actions = dict(action_surface=dict(convertTo='nautilusSubmarineSurface', price=0, buildSpeed=1,
                whenBuilding_cannotMove=True, highPriorityQueue=True, isVisible=True),
            action_dive=dict(convertTo='nautilusSubmarine', price=0, buildSpeed='4.5s',
                whenBuilding_cannotMove=True, highPriorityQueue=True, isVisible=False, isActive='if self.isOverwater'),
            hiddenAction_trigger_onLand=dict(autoTrigger='if not self.overWater()', convertTo='nautilusSubmarineLand'))
        (pack / f'nautilus.{fmt}').write_text(render(dict(core=core, movement=movement, **actions, **weapons(True)), fmt), encoding='utf8', newline='\n')
        surface_core = dict(core, name='nautilusSubmarineSurface', transportUnitsCanUnloadUnits='if self.isOverLiquid()',
                            transportUnitsRequireTag='', transportUnitsKeepBuiltUnits=False)
        surface_motion = dict(movement, movementType='HOVER', moveSpeed=.6, targetHeight=.1)
        land_core = dict(core, name='nautilusSubmarineLand')
        land_motion = dict(movement, movementType='OVER_CLIFF_WATER', moveSpeed=.35,
            moveAccelerationSpeed=.05, maxTurnSpeed=.5, turnAcceleration=.2,
            targetHeight=2, targetHeightDrift=0, moveSlidingDir=45, slowDeathFall=True)
        surface_actions = {s: dict(fields, **({'isVisible': False} if s == 'action_surface' else
            {'isVisible': True} if s == 'action_dive' else {})) for s, fields in actions.items()}
        land_actions = {s: dict(fields, **({'isVisible': False} if s in ('action_surface', 'action_dive') else {}))
                        for s, fields in actions.items()}
        land_actions['action_trigger_onWater'] = dict(autoTrigger='if self.overWater()',
            convertTo='nautilusSubmarineSurface', buildSpeed=0, isVisible=False)
        def legs(land):
            feet = [(-20, 0), (20, 0), (-20, 34), (20, 34), (-14, -25), (14, -25)] if land else [
                (-6, 0), (6, 0), (-6, 34), (6, 34), (-4, -25), (4, -25)]
            attachments = [(-6, 6), (6, 6), (-6, 26), (6, 26), (-2, -20), (2, -20)]
            return {f'leg_{i+1}': dict(x=x, y=y, attach_x=ax, attach_y=ay, moveSpeed=1.5 if land else .3,
                resetAngle=20, holdDisMin=10, holdDisMax=20, image_leg='leg.png', image_foot='foot.png',
                lockMovement=not land) for i, ((x, y), (ax, ay)) in enumerate(zip(feet, attachments))}
        (pack / f'nautilusSurface.{fmt}').write_text(render(dict(core=surface_core, movement=surface_motion, **surface_actions, **legs(False), **weapons(False)), fmt), encoding='utf8', newline='\n')
        (pack / f'nautilusLand.{fmt}').write_text(render(dict(core=land_core, movement=land_motion, **land_actions, **legs(True), **weapons(False)), fmt), encoding='utf8', newline='\n')
        for name in ('body', 'dead'):
            (pack / f'{name}.png').write_bytes(png(name, 42, 90))
        (pack / 'leg.png').write_bytes(png('body', 16, 68))
        (pack / 'foot.png').write_bytes(png('turret', 17, 36))
        shutil.copytree(ROOT / f'SimulationTest/public/inputs/hover-production/{fmt}/package/units/hoverTank',
                        pack / 'hover', dirs_exist_ok=True)
        manifest = dict(format='rw-reforge.content-pack.v1', id='owned-nautilus-'+fmt,
            name='Owned Nautilus mobile production', units=[dict(type='nautilus', file=f'nautilus.{fmt}'),
                dict(type='hover-tank', file=f'hover/hover.{fmt}'),
                dict(type='nautilus', file=f'nautilusSurface.{fmt}'),
                dict(type='nautilus', file=f'nautilusLand.{fmt}')])
        (pack / 'pack.json').write_text(json.dumps(manifest, indent=2)+'\n', encoding='utf8', newline='\n')
        production = root / 'production-package'
        shutil.copytree(ROOT / f'SimulationTest/public/inputs/naval-production/{fmt}/production-package',
                        production, dirs_exist_ok=True)
        shutil.copytree(pack, production / 'nautilus', dirs_exist_ok=True)
        normal = json.loads((production / 'pack.json').read_text(encoding='utf8'))
        normal.update(id='owned-nautilus-production-'+fmt, name='Owned normal sea-factory Nautilus chain')
        normal['units'] += [dict(type=entry['type'], file='nautilus/'+entry['file']) for entry in manifest['units']]
        shutil.copytree(ROOT / f'SimulationTest/public/inputs/repairbay-production/{fmt}/normal-package/units/commandCenter',
                        production / 'units/commandCenter', dirs_exist_ok=True)
        normal['units'].append(dict(type='command-center', file=f'units/commandCenter/commandCenter.{fmt}'))
        (production / 'pack.json').write_text(json.dumps(normal, indent=2)+'\n', encoding='utf8', newline='\n')
        factory = production / f'units/seaFactoryT2.{fmt}'
        text = factory.read_text(encoding='utf8')
        assert 'heavyBattleship,attackSubmarine' in text
        factory.write_text(text.replace('heavyBattleship,attackSubmarine',
            'heavyBattleship,attackSubmarine,nautilusSubmarine'), encoding='utf8', newline='\n')
        experimental = dict(format='rw-reforge.native-experimental-factory.v1', name='experimentalLandFactory',
            techLevel=2, maxHp=3200, radius=55, footprint='-2,-2,2,2', placementFootprint='-2,-2,2,4',
            movementType='NONE', productionUnits='nautilusSubmarineLand', price=11000, buildSpeed=.00035,
            fogOfWarSightRange=15, image='experimental_front.png', image_back='experimental_back.png',
            image_dead='experimental_dead.png')
        experimental_text = '\n'.join(f'{k}={v}' for k, v in experimental.items())+'\n' if fmt == 'ini' else json.dumps(experimental, indent=2)+'\n'
        (production / f'units/experimentalFactory.{fmt}').write_text(experimental_text, encoding='utf8', newline='\n')
        for name in ('front', 'back', 'dead'):
            (production / f'units/experimental_{name}.png').write_bytes(png('dead' if name == 'dead' else 'body', 100, 115))
        normal['units'].append(dict(type='experimental-factory', file=f'units/experimentalFactory.{fmt}'))
        (production / 'pack.json').write_text(json.dumps(normal, indent=2)+'\n', encoding='utf8', newline='\n')
        builder = production / f'units/builder.{fmt}'
        assert 'seaFactory' in builder.read_text(encoding='utf8')
        builder.write_text(builder.read_text(encoding='utf8').replace('seaFactory', 'seaFactory,experimentalLandFactory'), encoding='utf8', newline='\n')
        for directory in ('maps', 'assets'):
            shutil.copytree(ROOT / f'SimulationTest/public/inputs/naval-production/{fmt}/{directory}',
                            root / directory, dirs_exist_ok=True)


if __name__ == '__main__':
    generate()
