"""Owned flame-mech declarations and sprites. Uses only owned inputs."""
from pathlib import Path
import json
import shutil
import struct
import zlib

from aa_beam_fixture import png
from bunker_fixture import render

ROOT = Path(__file__).resolve().parents[2]
INPUTS = ROOT / 'SimulationTest/public/inputs'
DESTINATION = INPUTS / 'flame-mech-production'


def body_png(shadow=False):
    width, height = 45, 87

    def chunk(kind, data):
        return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data))

    pixels = bytearray()
    for y in range(height):
        pixels.append(0)
        for x in range(width):
            lateral = abs(x - 22)
            pixel = (0, 0, 0, 0)
            if 19 <= y <= 77 and lateral < min(15, y - 13, 84 - y):
                pixel = (81, 103, 96, 255) if lateral > 8 else (131, 153, 143, 255)
            if 12 <= y <= 44 and 11 <= lateral <= 18:
                pixel = (170, 123, 68, 255) if y > 17 else (235, 183, 96, 255)
            if 38 <= y <= 65 and 5 <= lateral <= 9:
                pixel = (187, 129, 72, 255)
            if shadow and pixel[3]:
                pixel = (19, 24, 27, 102)
            pixels.extend(pixel)
    header = struct.pack('>IIBBBBB', width, height, 8, 6, 0, 0, 0)
    return b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', header) + chunk(b'IDAT', zlib.compress(pixels)) + chunk(b'IEND', b'')


def inherited(section, fmt, **fields):
    return dict(**({'@copyFromSection': section} if fmt == 'ini' else {'copyFromSections': [section]}), **fields)


def generate():
    assert not DESTINATION.exists(), 'Never overwrite a source fixture'
    for fmt in ('ini', 'json', 'hjson'):
        root = DESTINATION / fmt
        template = INPUTS / 'heavy-mech-production' / fmt
        for folder in ('production-package', 'maps', 'assets'):
            shutil.copytree(template / folder, root / folder)
        shutil.copyfile(template / 'combat.properties', root / 'combat.properties')
        pack = root / 'production-package'
        units = pack / 'units'
        factory = units / f'mechFactoryT2.{fmt}'
        text = factory.read_text(encoding='utf8')
        assert text.count('builder,mechGun,mechHeavyMissile') == 1
        factory.write_text(text.replace('builder,mechGun,mechHeavyMissile',
            'builder,mechGun,mechHeavyMissile,mechFlame'), encoding='utf8')
        flame = units / 'flame'
        flame.mkdir()
        leg = dict(x=-20, y=0, attach_x=-13, attach_y=0, moveSpeed=1.9, moveWarmUp='0.1s',
            rotateSpeed=7, heightSpeed=.7, resetAngle=25, holdDisMin=22, holdDisMax=42,
            image_leg='leg.png', image_foot='foot.png')
        common = dict(core=dict(dont_load=True),
            graphics=dict(total_frames=1, lock_body_rotation_with_main_turret=True),
            leg_1=leg, leg_2=inherited('leg_1', fmt, x=20, attach_x=13),
            movement=dict(movementType='OVER_CLIFF', moveSlidingMode=False, moveIgnoringBody=False,
                reverseSpeedPercentage=1))
        fields = dict(core=dict(name='mechFlame', **{'class': 'CustomUnitMetadata'}, price=12000, maxHp=4500,
                mass=6000, radius=25, buildSpeed=.0005, techLevel=2, transportSlotsNeeded=2, selfRegenRate=.06),
            graphics=dict(image='body.png', image_shadow='shadow.png', image_wreak='NONE', image_turret='NONE',
                shadowOffsetX=2, shadowOffsetY=2),
            attack=dict(canAttack=True, canAttackLandUnits=True, canAttackFlyingUnits=False,
                canAttackUnderwaterUnits=False, maxAttackRange=170, shootDelay=5, turretRotateWithBody=False),
            turret_1=dict(x=0, y=0, idleDir=0, turnSpeed=2.9, turnSpeedAcceleration=.17, turnSpeedDeceleration=.35, canShoot=False),
            turret_2=dict(x=15, y=30, attachedTo='1', slave=True, invisible=True, projectile='fireProjectile1',
                turnSpeed=8, turnSpeedAcceleration=1, size=0, canShoot=True, limitingAngle=45, shoot_flame='CUSTOM:smoke'),
            turret_3=inherited('turret_2', fmt, x=-15, warmup=1),
            projectile_fireProjectile1=dict(life=70, speed=7, targetSpeed=2.5, targetSpeedAcceleration=.3,
                speedSpread=1, drawSize=.3, flameWeapon=True, hitSound=False, targetGround=True,
                targetGroundSpread=25, deflectionPower=-1, areaDamage=12, areaRadius=60,
                explodeEffect='CUSTOM:hitFlame', effectOnCreate='CUSTOM:attachedFlame, CUSTOM:attachedLight'),
            movement=dict(moveSpeed=.6, moveAccelerationSpeed=.07, moveDecelerationSpeed=.17, maxTurnSpeed=5,
                turnAcceleration=.5))
        (flame / f'mech-large-common.{fmt}').write_text(render(common, fmt), encoding='utf8')
        (flame / f'flame.{fmt}').write_text(render(fields, fmt, f'mech-large-common.{fmt}'), encoding='utf8')
        (flame / 'body.png').write_bytes(body_png())
        (flame / 'shadow.png').write_bytes(body_png(shadow=True))
        shutil.copyfile(units / 'heavy/leg.png', flame / 'leg.png')
        (flame / 'foot.png').write_bytes(png('body', 17, 36))
        manifest = pack / 'pack.json'
        data = json.loads(manifest.read_text(encoding='utf8'))
        data.update(id=f'owned-flame-mech-production-{fmt}', name='Owned normal T2 flame-mech production')
        data['units'].append(dict(type='mech-flame', file=f'units/flame/flame.{fmt}'))
        manifest.write_text(json.dumps(data, indent=2) + '\n', encoding='utf8')
    (DESTINATION / 'README.md').write_text('# Owned flame-mech production inputs\n\n'
        'Generated by SimulationTest/tools/mech_flame_fixture.py using the existing owned T2 chain, '
        'controlled terrain, shared owned leg image and geometric sprites. Contains no original images.\n', encoding='utf8')


if __name__ == '__main__':
    generate()
