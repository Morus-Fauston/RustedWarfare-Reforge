"""Owned, synthetic production/combat fixtures from public loader/test contracts.

No original INI, map, or pixels are copied. Fixed expectations live elsewhere.
"""
from pathlib import Path
import hashlib
import json
import struct
import zlib
from generate_content_inputs import write

ROOT = Path(__file__).parent / 'inputs/production-combat'


def png(path, width, height, rgb):
    def chunk(kind, data):
        return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data))
    pixels = (b'\0' + bytes([*rgb, 255]) * width) * height
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>2I5B', width, height, 8, 6, 0, 0, 0)) +
                     chunk(b'IDAT', zlib.compress(pixels)) + chunk(b'IEND', b''))


def generate():
    tank = '''[core]
name: c_tank
overrideAndReplace: tank
class: CustomUnitMetadata
price: 350
buildSpeed: 0.002
maxHp: 210
radius: 11
displayRadius: 11
isBio: false
softCollisionOnAll: 0
fogOfWarSightRange: 15
[graphics]
total_frames: 3
image: tank.png
image_wreak: tank_dead.png
image_turret: tank_turret.png
[attack]
canAttack: true
canAttackFlyingUnits: false
canAttackLandUnits: true
canAttackUnderwaterUnits: false
turretSize: 20
turretTurnSpeed: 4
maxAttackRange: 130
shootDelay: 75
[turret_1]
x: 0
y: 0
canShoot: true
projectile: 1
turnSpeedAcceleration: 1
[projectile_1]
directDamage: 25
life: 60
speed: 5
[movement]
movementType: LAND
moveSpeed: 1.1
moveAccelerationSpeed: 0.07
moveDecelerationSpeed: 0.17
maxTurnSpeed: 4.1
turnAcceleration: 0.25
moveSlidingMode: false
moveIgnoringBody: false
'''
    factory = '''format=rw-reforge.native-land-factory.v1
name=landFactory
maxHp=1200
radius=30
footprint=-1,-1,1,1
movementType=NONE
productionUnit=c_tank
fogOfWarSightRange=15
image=factory.png
image_back=factory_back.png
image_dead=factory_dead.png
'''
    # Combat fixtures require free factory footprints; unreachable cases use a separate full wall.
    gids = [1] * 600
    def map_text(cells):
        return ('<map orientation="orthogonal" renderorder="right-down" width="30" height="20" tilewidth="20" tileheight="20">'
                '<tileset firstgid="1" name="owned" tilewidth="20" tileheight="20">'
                '<image source="../assets/terrain.png" width="40" height="20"/>'
                '<tile id="1"><properties><property name="trees" value=""/></properties></tile></tileset>'
                '<layer name="Ground" width="30" height="20"><data encoding="csv">' +
                ','.join(map(str, cells)) + '</data></layer></map>\n')
    for name in ['combat', 'production', 'patrol', 'patrol-return', 'upgrade', 'defense', 'center']:
        root = ROOT / name
        write(root, 'units/tank.ini', tank)
        write(root, 'units/landFactory.properties', factory)
        for filename, w, h, color in [('tank.png', 60, 20, (40, 90, 170)),
                                     ('tank_dead.png', 20, 20, (90, 90, 90)),
                                     ('tank_turret.png', 20, 20, (80, 110, 160)),
                                     ('factory.png', 60, 60, (60, 90, 150)),
                                     ('factory_back.png', 60, 60, (50, 60, 70)),
                                     ('factory_dead.png', 60, 60, (90, 80, 70))]:
            png(root / 'units' / filename, w, h, color)
        png(root / 'assets/terrain.png', 40, 20, (60, 110, 60))
        write(root, 'maps/p001-controlled.tmx', map_text(gids))
        write(root, 'maps/open.tmx', map_text([1] * 600))
        write(root, 'maps/p005-sealed.tmx', map_text([2 if c == 14 else 1 for r in range(20) for c in range(30)]))
        compact = map_text([1] * 50).replace('width="30" height="20"', 'width="10" height="5"')
        write(root, 'maps/production-chain.tmx', compact)
        if name == 'combat':
            write(root, 'combat.properties', 'format=rw-reforge.combat-sandbox.v1\n')
        elif name in ['production', 'patrol', 'patrol-return', 'upgrade', 'defense']:
            write(root, 'production.properties', 'format=rw-reforge.production-sandbox.v1\nvariant=production\n')
        if name == 'patrol-return':
            write(root, 'patrol.properties', 'format=rw-reforge.patrol-sandbox.v1\nvariant=patrol-return\n')
    turret = '''[core]
dont_load: true
class: CustomUnitMetadata
isBuilding: true
isBio: false
maxHp: 700
radius: 16
displayRadius: 16
footprint: 0,0,1,1
constructionFootprint: 0,0,1,1
buildingToFootprintOffsetX: 20
buildingToFootprintOffsetY: 20
fogOfWarSightRange: 15
[graphics]
total_frames: 1
image: turret.png
image_wreak: turret_dead.png
image_turret: turret_top.png
[movement]
movementType: NONE
moveSpeed: 0
maxTurnSpeed: 0
moveAccelerationSpeed: 0
moveDecelerationSpeed: 0
turnAcceleration: 0
[attack]
canAttack: true
canAttackFlyingUnits: false
canAttackLandUnits: true
canAttackUnderwaterUnits: false
turretTurnSpeed: 6
turretSize: 22
maxAttackRange: 165
shootDelay: 30
[turret_1]
x: 0
y: 6
shouldResetTurret: false
recoilOffset: -0.5
recoilOutTime: 6
recoilReturnTime: 12
idleSweepAngle: 20
idleSweepDelay: 210
idleSweepSpeed: 0.2
[projectile_1]
directDamage: 41
life: 240
speed: 6
'''
    tower = '''[core]
name: c_turret_t1
overrideAndReplace: turret
copyFrom: turret_common_land.ini
dont_load: false
[action_upgrade_gun]
convertTo: c_turret_t2_gun
price: 1000
buildSpeed: 0.001
[action_upgrade_artillery]
convertTo: c_turret_t1_artillery
price: 1600
buildSpeed: 0.0004
'''
    gun = '''[core]
name: c_turret_t2_gun
overrideAndReplace: turretT2
copyFrom: turret_common_land.ini
dont_load: false
maxHp: 1100
[attack]
maxAttackRange: 185
shootDelay: 20
[turret_1]
barrelX: -3
barrelOffsetX_onOddShots: 6
[projectile_1]
directDamage: 44
'''
    artillery = '''[core]
name: c_turret_t1_artillery
overrideAndReplace: turret_artillery
copyFrom: turret_common_land.ini
dont_load: false
maxHp: 1000
[attack]
maxAttackRange: 350
shootDelay: 3.67s
turretTurnSpeed: 1.7
turretSize: 27
[projectile_1]
directDamage: 0
speed: 4
targetGround: true
areaDamage: 80
areaRadius: 55
targetGroundSpread: 10
initialUnguidedSpeedHeight: 1.2
gravity: 0.18
'''
    upgrade = ROOT / 'upgrade'
    for filename, content in [('turret_common_land.ini', turret), ('turret_t1.ini', tower),
                              ('turret_t2_gun.ini', gun), ('turret_t1_artillery.ini', artillery)]:
        write(upgrade, 'units/' + filename, content)
    for filename in ['turret.png', 'turret_dead.png', 'turret_top.png']:
        png(upgrade / 'units' / filename, 40, 40, (110, 90, 70))
    defense = ROOT / 'defense'
    write(defense, 'units/laserDefence.properties', '''format=rw-reforge.native-laser-defence.v1
name=laserDefence
maxHp=500
radius=19
footprint=0,0,1,1
movementType=NONE
fogOfWarSightRange=15
image=laser.png
image_dead=laser_dead.png
''')
    for filename in ['laser.png', 'laser_dead.png']:
        png(defense / 'units' / filename, 40, 40, (80, 110, 100))
    center = ROOT / 'center'
    write(center, 'construction.properties', 'format=rw-reforge.construction-sandbox.v1\nvariant=command-center-start\n')
    write(center, 'units/commandCenter.properties', '''format=rw-reforge.native-command-center.v1
name=commandCenter
maxHp=4000
radius=30
footprint=-1,-1,1,1
movementType=NONE
productionUnit=builder
fogOfWarSightRange=20
incomePeriod=10
incomeCredits=4.5
image=base.png
image_back=base_back.png
image_dead=base_dead.png
''')
    write(center, 'units/builder.properties', '''format=rw-reforge.native-builder.v1
name=builder
maxHp=170
radius=10
displayRadius=12
movementType=LAND
moveSpeed=0.8
moveAccelerationSpeed=0.04
moveDecelerationSpeed=0.1
maxTurnSpeed=3.8
turnAcceleration=0.35
price=500
buildSpeed=0.002
fogOfWarSightRange=15
image=builder.png
image_dead=builder_dead.png
''')
    for filename, w, h in [('base.png', 212, 68), ('base_back.png', 53, 68),
                            ('base_dead.png', 53, 74), ('builder.png', 20, 20), ('builder_dead.png', 20, 20)]:
        png(center / 'units' / filename, w, h, (60, 100, 130))
    files = [dict(path=p.relative_to(ROOT).as_posix(), sha256=hashlib.sha256(p.read_bytes()).hexdigest())
             for p in sorted(ROOT.rglob('*')) if p.is_file() and p.name != 'OWNERSHIP.json']
    write(ROOT, 'OWNERSHIP.json', json.dumps(dict(
        source='New authored fixtures from public loaders and existing public test assertions',
        pixels='New solid RGBA rasters; no original graphics',
        semantics='Preserves consumed test values; does not claim original-content equivalence', files=files), indent=2) + '\n')


if __name__ == '__main__':
    generate()
