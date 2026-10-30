"""Owned maintenance/defense fixtures. Reads only earlier owned public inputs.

Legacy mutation spellings and image names preserve the existing reader tests.
No trace is consumed and no expected behavior is generated here.
"""
from pathlib import Path
import hashlib
import json
import shutil
from generate_combat_inputs import png
from generate_content_inputs import write

PUBLIC = Path(__file__).parent
ROOT = PUBLIC / 'inputs/maintenance-defense'


def copy_owned(source, target):
    for file in source.rglob('*'):
        if file.is_file():
            destination = target / file.relative_to(source)
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(file, destination)


def generate():
    maintenance = ROOT / 'maintenance'
    copy_owned(PUBLIC / 'inputs/build-economy/queue', maintenance)
    text = (maintenance / 'maps/p001-controlled.tmx').read_text(encoding='utf8')
    prefix, cells = text.split('<data encoding="csv">')
    gids = cells.split('</data>')[0].split(',')
    gids[8 * 30 + 16] = '3'
    write(maintenance, 'maps/p001-controlled.tmx', prefix + '<data encoding="csv">' + ','.join(gids) + '</data></layer></map>\n')
    defense = ROOT / 'construction'
    copy_owned(maintenance, defense)
    # The siege's second defensive site is open; the blocked-click fixture is
    # specific to the old maintenance/queue map.
    shutil.copyfile(PUBLIC / 'inputs/build-economy/queue/maps/p001-controlled.tmx', defense / 'maps/p001-controlled.tmx')
    for file in (PUBLIC / 'inputs/build-economy/turret-upgrades/units').iterdir():
        if file.is_file() and file.name.startswith('turret'):
            shutil.copyfile(file, defense / 'units' / file.name)
    write(defense, 'construction.properties', 'format=rw-reforge.construction-sandbox.v1\nvariant=defense-construction\n')
    builder = (defense / 'units/builder.properties').read_text(encoding='utf8')
    write(defense, 'units/builder.properties', builder.replace('native-builder.v3', 'native-builder.v4').replace(
        'constructionTypes=extractorT1,landFactory,fabricatorT1',
        'constructionTypes=extractorT1,landFactory,fabricatorT1,c_turret_t1,laserDefence'))
    tower = (defense / 'units/turret_t1.ini').read_text(encoding='utf8')
    write(defense, 'units/turret_t1.ini', tower.replace('[core]\n', '[core]\nprice: 500\nbuildSpeed: 0.0008\n'))
    # A separate legacy point-defense scene keeps the native two-frame names.
    point = ROOT / 'point-defense'
    copy_owned(PUBLIC / 'inputs/production-combat/production', point)
    write(point, 'point-defense.properties', 'format=rw-reforge.point-defense-sandbox.v1\nvariant=level-one\n')
    native = (PUBLIC / 'inputs/production-combat/defense/units/laserDefence.properties').read_text(encoding='utf8')
    native = native.replace('laser.png', 'laser_defence.png').replace('laser_dead.png', 'laser_defence_dead.png')
    for root in [point, defense]:
        write(root, 'units/laserDefence.properties', native)
        png(root / 'units/laser_defence.png', 72, 36, (70, 100, 120))
        png(root / 'units/laser_defence_dead.png', 36, 36, (90, 80, 70))

    legacy = ROOT / 'turret'
    copy_owned(PUBLIC / 'inputs/production-combat/production', legacy)
    write(legacy, 'turret.properties', 'format=rw-reforge.turret-sandbox.v1\nvariant=basic-turret\n')
    common = (PUBLIC / 'inputs/production-combat/upgrade/units/turret_common_land.ini').read_text(encoding='utf8')
    common = common.replace('maxHp: 700\n', '').replace('dont_load: true', 'dont_load:true').replace(
        'constructionFootprint: 0,0,1,1', 'constructionFootprint:0,0,1,1').replace(
        'buildingToFootprintOffsetX: 20', 'buildingToFootprintOffsetX:20')
    common = '\n'.join(line for line in common.splitlines() if not (
        line.startswith('recoil') or line.startswith('idleSweep') or line in ['x: 0', 'y: 6'])) + '\n'
    common = common.replace('image: turret.png', 'image: turret_base.png').replace(
        'image_wreak: turret_dead.png', 'image_wreak: turret_base_dead.png')
    write(legacy, 'units/turret_common_land.ini', common)
    from generate_economy_inputs import ini
    child = ini(dict(core=dict(name='c_turret_t1', overrideAndReplace='turret', copyFrom='turret_common_land.ini',
        maxHp=700, price=500, buildSpeed=.0008, mass=9000, techLevel=1, displayLocaleKey='turret', buildingSelectionOffset=0),
        graphics=dict(image_shadow='NONE', shadowOffsetX=0, shadowOffsetY=0),
        turret_1=dict(x=0, y=6, recoilOffset=-.5, recoilOutTime=6, recoilReturnTime=12,
            idleSweepAngle=20, idleSweepDelay=210, idleSweepSpeed=.2, shoot_sound='owned', shoot_sound_vol=.1,
            shoot_flame='NONE', shoot_light='#FFFFFFFF'),
        projectile_1=dict(frame=1), ai=dict(upgradedFrom='c_turret_t1', buildPriority=.1, noneInBaseExtraPriority=.1, maxEachBase=5)))
    child = child.replace('idleSweepSpeed: 0.2', 'idleSweepSpeed:0.2')
    for name, target, price, speed in [('gun', 'c_turret_t2_gun', 1000, .001),
            ('artillery', 'c_turret_t1_artillery', 1600, .0004),
            ('flame', 'owned_flame', 700, .001), ('lightning', 'owned_lightning', 800, .001)]:
        child += ini({'action_upgrade_' + name: dict(convertTo=target, price=price, text='owned ' + name,
            description='owned contract', descriptionAddUnitStats=target, displayType='upgrade', buildSpeed=speed,
            iconExtraImage='SHARED:icon_upgrade_top.png', iconExtraColor='#AAFFFFFF')})
    write(legacy, 'units/turret_t1.ini', child)
    for filename in ['turret_base.png', 'turret_base_dead.png', 'turret_top.png']:
        png(legacy / 'units' / filename, 40, 40, (110, 90, 70))
    files = [dict(path=file.relative_to(ROOT).as_posix(), sha256=hashlib.sha256(file.read_bytes()).hexdigest())
        for file in sorted(ROOT.rglob('*')) if file.is_file() and file.name != 'OWNERSHIP.json']
    write(ROOT, 'OWNERSHIP.json', json.dumps(dict(
        source='Reused owned public fixtures plus newly authored metadata and solid RGBA pixels; no private files read',
        semantics='Existing assertions and mutation spelling preserved; not original-content equivalence', files=files), indent=2) + '\n')


if __name__ == '__main__':
    generate()
