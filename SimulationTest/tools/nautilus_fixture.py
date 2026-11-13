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
    result['turret_botTurret'] = dict(x=10, y=22, idleDir=0, invisible=True, canShoot=False,
        projectile='scoutBotProjectile', size=1, turnSpeed=40, limitingRange=500)
    result['turret_antiNuke'] = dict(x=7, y=20, idleDir=0, invisible=True, canShoot=False,
        projectile='antiNukeMissile', size=1, turnSpeed=40, interceptProjectiles_withTags='nuke',
        interceptProjectiles_andTargetingGroundUnderDistance=500, interceptProjectiles_andUnderDistance=1100,
        interceptProjectiles_andOverHeight=50, resourceUsage='ammo=1', shoot_sound='nuke_launch', shoot_sound_vol=.15)
    result['projectile_antiNukeMissile'] = dict(directDamage=600, life=99999, speed=.2, targetSpeed=6.5,
        targetSpeedAcceleration=.05, ballistic=True, ballistic_delaymove_height=80, ballistic_height=110,
        targetGround=True, areaDamage=100, areaRadius=60, areaExpandTime=75, deflectionPower=-1)
    result['projectile_2'] = dict(directDamage=50, life=250, speed=.15, targetSpeed=1.2,
        ballistic=True, ballistic_delaymove_height=40, ballistic_height=-2, autoTargetingOnDeadTarget=True)
    result['projectile_3'] = dict(directDamage=35, life=240, speed=.3, targetSpeed=6,
        ballistic=True, ballistic_delaymove_height=40, ballistic_height=50, autoTargetingOnDeadTarget=True,
        areaDamage=180, areaRadius=55)
    split = 'scoutBotProjectileSplit'
    fast = 'scoutBotProjectileSplitFastTurn'
    result['projectile_scoutBotProjectile'] = dict(life=0, directDamage=0,
        spawnProjectilesOnEndOfLife=f'{split}(offsetDir=15), {split}(offsetDir=-180)')
    result[f'projectile_{split}'] = dict(life=90, speed=.3, turnSpeed=4, directDamage=123,
        spawnUnit='robotCrab', targetSpeed=6, targetSpeedAcceleration=.05, ballistic=True,
        ballistic_delaymove_height=20, ballistic_height=50, targetGround=True, deflectionPower=-1,
        wobbleAmplitude=1, wobbleFrequency=60, targetGroundSpread=5, spawnProjectilesOnEndOfLife=fast)
    result[f'projectile_{fast}'] = dict(result[f'projectile_{split}'], life=150, speed=2, turnSpeed=9,
        spawnProjectilesOnEndOfLife='NONE')
    return result


def scout_forms(pack, fmt):
    scout = pack / 'scout'
    scout.mkdir(exist_ok=True)
    entries = []
    for name, water in (('robotCrab', False), ('robotCrabWater', True)):
        unit = dict(core=dict(name=name, radius=11, displayRadius=13, maxHp=650, price=700, buildSpeed=.002,
                reclaimPrice=0, fogOfWarSightRange=22, energyMax=1, energyRegen=-.0005,
                energyStartingPercentage=1, dieOnZeroEnergy=True, nanoRange=110, nanoRepairSpeed=.4, nanoBuildSpeed=.4,
                canRepairBuildings=True, canRepairUnits=True, canReclaimResources=True, image='base.png',
                image_dead='dead.png', image_turret='turret.png', image_leg='leg.png', image_foot='foot.png'),
            movement=dict(movementType='OVER_CLIFF_WATER', moveSpeed=.6 if water else 1,
                moveAccelerationSpeed=1, moveDecelerationSpeed=1, maxTurnSpeed=4,
                turnAcceleration=.2 if water else .5, moveSlidingDir=45 if water else 35, targetHeight=.1),
            attack=dict(canAttackLandUnits=True, canAttackFlyingUnits=True, canAttackUnderwaterUnits=False,
                maxAttackRange=110, shootDelay=50),
            projectile_1=dict(directDamage=17, life=70, speed=6))
        trigger = 'notOverWater' if water else 'overWater'
        unit[f'hiddenAction_{trigger}'] = dict(autoTrigger='if not self.overWater()' if water else 'if self.overWater()',
            convertTo='robotCrab' if water else 'robotCrabWater')
        path = scout / f'{name}.{fmt}'
        path.write_text(render(unit, fmt), encoding='utf8', newline='\n')
        entries.append(dict(type='scout-bot', file=f'scout/{path.name}'))
    for name, kind, width, height in [('base', 'body', 24, 21), ('dead', 'dead', 24, 21), ('turret', 'turret', 8, 15),
                                       ('leg', 'body', 24, 102), ('foot', 'empty', 8, 8)]:
        (scout / f'{name}.png').write_bytes(png(kind, width, height))
    return entries


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
            action_launchScoutBot=dict(id='launchScoutBot', price=250, buildSpeed='3.5s',
                fireTurretXAtGround='botTurret', fireTurretXAtGround_onlyOverPassableTileOf='HOVER',
                addActionCooldownTime='30s', allowMultipleInQueue=False,
                whenBuilding_cannotMove=True, isVisible=False),
            action_buildAntiNuke=dict(id='buildAntiNuke', price=6000, buildSpeed=.00025,
                isActive='if self.ammoIncludingQueued(lessThan=4)', addResources='ammo=1',
                isLocked='if not game.nukesEnabled()'),
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
            {'isVisible': True} if s in ('action_dive', 'action_launchScoutBot') else {})) for s, fields in actions.items()}
        land_actions = {s: dict(fields, **({'isVisible': False} if s in ('action_surface', 'action_dive') else
            {'isVisible': True} if s == 'action_launchScoutBot' else {}))
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
        manifest['units'] += scout_forms(pack, fmt)
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
