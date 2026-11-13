"""Owned finite bunker declarations and procedural art; never reads original resources."""
from pathlib import Path
import json
import shutil
from aa_beam_fixture import png

ROOT = Path(__file__).resolve().parents[2]
DEST = ROOT / 'SimulationTest/public/inputs/bunker-production'


def render(sections, fmt, parent=None):
    if fmt == 'ini':
        def literal(v):
            return str(v).lower() if isinstance(v, bool) else str(v)
        return '\n'.join('['+s+']\n'+(f'copyFrom: {parent}\n' if s == 'core' and parent else '')+
            '\n'.join(f'{k}: {literal(v)}' for k, v in fields.items()) for s, fields in sections.items())+'\n'
    result = dict(format='rw-reforge.unit.v1')
    if parent:
        result['copyFrom'] = [parent]
    for s, fields in sections.items():
        if s.startswith(('turret_', 'projectile_', 'action_')):
            prefix, name = s.split('_', 1)
            result.setdefault(dict(turret='weapons', projectile='projectiles', action='actions')[prefix], []).append(dict(name=name, **fields))
        else:
            result[s] = fields
    return json.dumps(result, indent=2)+'\n'


def generate():
    for fmt in ('ini', 'json', 'hjson'):
        pack = DEST / fmt / 'package'
        pack.mkdir(parents=True, exist_ok=True)
        leg = dict(x=-22, y=15, attach_x=-5, attach_y=10, moveSpeed=1.9, rotateSpeed=7,
            heightSpeed=.3, moveWarmUp='0.1s', resetAngle=25, image_leg='leg.png', image_foot='foot.png', dust_effect=False,
            holdDisMin=5, holdDisMax=19, favourOppositeSideNeighbours=True, estimatingPositionMultiplier=.7)
        base = dict(core=dict(name='mechBunker', **{'class':'CustomUnitMetadata'}, price=4500, maxHp=800,
                radius=29, fogOfWarSightRange=20, buildSpeed=.0006),
            graphics=dict(total_frames=1, image='body.png', image_wreak='NONE', image_turret='NONE',
                image_shadow='shadow.png', lock_body_rotation_with_main_turret=False, lock_leg_rotation_with_main_turret=False),
            attack=dict(canAttack=True, canAttackFlyingUnits=False, canAttackLandUnits=True,
                canAttackUnderwaterUnits=False, turretSize=20, turretTurnSpeed=2, maxAttackRange=200,
                shootDelay=70, turretRotateWithBody=True),
            turret_1=dict(x=0, y=0, idleDir=0, turnSpeed=2.9, turnSpeedAcceleration=.07, canShoot=False, projectile='1'),
            turret_2=dict(x=0, y=0, image='turret.png', projectile='1', turnSpeed=3.9,
                turnSpeedAcceleration=.2, canShoot=True, size=25, shoot_sound='large_gun_fire2', shoot_sound_vol=.3,
                recoilOffset=-3, recoilOutTime=6, recoilReturnTime=7, idleSweepAngle=20, idleSweepSpeed=.2,
                idleSweepDelay=210, idleSweepAddRandomDelay=50, idleSweepCondition='if not self.isMoving()'),
            projectile_1=dict(life=150, speed=4, targetGround=True, areaDamage=110, areaRadius=45),
            movement=dict(movementType='OVER_CLIFF', moveSpeed=.4, moveAccelerationSpeed=.07,
                moveDecelerationSpeed=.17, maxTurnSpeed=1, turnAcceleration=.5, targetHeight=3,
                moveSlidingMode=True, moveIgnoringBody=True, moveSlidingDir=181, reverseSpeedPercentage=1),
            action_deploy=dict(isVisible=True, convertTo='mechBunkerDeployed', price=0, buildSpeed=.006,
                whenBuilding_cannotMove=True, whenBuilding_rotateTo=0, whenBuilding_rotateTo_orBackwards=True,
                canPlayerCancel=False, allowMultipleInQueue=False), leg_1=leg)
        for i, (x, y, ax, ay) in enumerate([(22,15,5,10),(-22,-15,-5,-10),(22,-15,5,-10)], 2):
            base[f'leg_{i}'] = dict(copyFromSections=['leg_1'], x=x, y=y, attach_x=ax, attach_y=ay) if fmt != 'ini' else {'@copyFromSection': 'leg_1', 'x': x, 'y': y, 'attach_x': ax, 'attach_y': ay}
        deployed = dict(core=dict(name='mechBunkerDeployed', radius=23, maxShield=500, shieldRegen=.15,
                startShieldAtZero=True, isBuilding=True, footprint='-1,-1,1,1', constructionFootprint='-1,-1,1,1'),
            graphics=dict(image_shield='shield.png'), attack=dict(maxAttackRange=240, shootDelay=50),
            turret_2=dict(shouldResetTurret=False),
            movement=dict(movementType='NONE', moveSpeed=0, targetHeight=0, moveSlidingMode=False, moveIgnoringBody=False),
            action_deploy=dict(isVisible=False), action_undeploy=dict(isVisible=True, convertTo='mechBunker', price=0,
                buildSpeed=.010, canPlayerCancel=False, allowMultipleInQueue=False),
            leg_1=dict(x=-3, y=3, moveSpeed=1, heightSpeed=.4, holdDisMax=15),
            leg_2=dict(x=3, y=3), leg_3=dict(x=-3, y=-3), leg_4=dict(x=3, y=-3))
        for name, sections, parent in [('bunker', base, None), ('deployed', deployed, f'bunker.{fmt}')]:
            (pack/f'{name}.{fmt}').write_text(render(sections, fmt, parent), encoding='utf8')
        for name, kind, w, h in [('body','body',39,49),('turret','turret',14,57),('leg','turret',16,68),
                ('foot','body',17,36),('shadow','shadow',39,49),('shield','body',92,92)]:
            (pack/f'{name}.png').write_bytes(png(kind,w,h))
        (pack/'pack.json').write_text(json.dumps(dict(format='rw-reforge.content-pack.v1', id='owned-bunker-'+fmt,
            name='Owned normal bunker forms', units=[dict(type='mech-bunker', file=f'{n}.{fmt}') for n in ('bunker','deployed')]), indent=2)+'\n', encoding='utf8')
        production = DEST/fmt/'production-package'
        shutil.copytree(ROOT/f'SimulationTest/public/inputs/heavy-mech-production/{fmt}/production-package', production, dirs_exist_ok=True)
        for directory in ('maps', 'assets'):
            shutil.copytree(ROOT/f'SimulationTest/public/inputs/heavy-mech-production/{fmt}/{directory}', DEST/fmt/directory, dirs_exist_ok=True)
        (DEST/fmt/'combat.properties').write_text('format=rw-reforge.combat-sandbox.v1\n', encoding='utf8')
        shutil.copytree(pack, production/'units/bunker', dirs_exist_ok=True)
        shutil.copytree(ROOT/f'SimulationTest/public/inputs/aa-beam-production/{fmt}/production-package/units/commandCenter', production/'units/commandCenter', dirs_exist_ok=True)
        manifest = json.loads((production/'pack.json').read_text(encoding='utf8'))
        manifest.update(id='owned-bunker-production-'+fmt, name='Owned normal T1 bunker production')
        manifest['units'] += [dict(type='mech-bunker', file=f'units/bunker/{n}.{fmt}') for n in ('bunker','deployed')]
        manifest['units'].append(dict(type='command-center', file=f'units/commandCenter/commandCenter.{fmt}'))
        (production/'pack.json').write_text(json.dumps(manifest, indent=2)+'\n', encoding='utf8')
        for factory in ('mechFactory', 'mechFactoryT2'):
            path = production/f'units/{factory}.{fmt}'
            text = path.read_text(encoding='utf8')
            text = text.replace('builder,mechGun,mechHeavyMissile', 'builder,mechGun,mechHeavyMissile,mechBunker') if factory.endswith('T2') else text.replace('builder,mechGun', 'builder,mechGun,mechBunker')
            path.write_text(text, encoding='utf8')


if __name__ == '__main__':
    generate()
