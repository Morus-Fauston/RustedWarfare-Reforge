"""Owned construction/economic fixtures, authored from public loader contracts.

Reuses only the already-owned production fixture; no private/original files read.
Behavior contracts are maintained separately and are never generated from traces.
"""
from pathlib import Path
import hashlib
import json
import shutil
from generate_combat_inputs import png
from generate_content_inputs import write

PUBLIC = Path(__file__).parent
ROOT = PUBLIC / 'inputs/build-economy'


def ini(sections):
    return '\n'.join('[' + name + ']\n' + '\n'.join(
        key + (':' if key == '@copyFrom_skipThisSection' else ': ') + str(value)
        for key, value in entries.items()) for name, entries in sections.items()) + '\n'


def action(target, price, rate, text, description):
    return dict(convertTo=target, price=price, buildSpeed=rate, text=text, description=description,
                displayType='upgrade', iconExtraImage='SHARED:icon_upgrade_top.png', iconExtraColor='#AAFFFFFF')


def economic_units(root):
    motion = dict(movementType='NONE', moveSpeed=0, maxTurnSpeed=0, moveAccelerationSpeed=.01,
                  moveDecelerationSpeed=.01, turnAcceleration=.1)
    attack = dict(canAttack='false', canAttackFlyingUnits='false', canAttackLandUnits='false',
                  canAttackUnderwaterUnits='false', maxAttackRange=0)
    for family in ['extractor', 'fabricator']:
        extractor = family == 'extractor'
        common = dict(displayLocaleKey=family, mass=9000, radius=18 if extractor else 20, displayRadius=20,
                      isBuilding='true', footprint='0,0,0,0' if extractor else '-1,-1,1,1',
                      constructionFootprint='0,0,0,0' if extractor else '-1,-1,1,1')
        if extractor:
            common.update(dont_load='true', placeOnlyOnResPool='true', buildingSelectionOffset=4,
                          numBitsOnDeath=16, hideScorchMark='true', effectOnDeath='resourcePoolSmoke',
                          explodeTypeOnDeath='buildingNoShockwaveOrSmoke')
            write(root, 'units/extractor_common.ini', ini(dict(core=common, movement=motion, attack=attack)))
        for tier in [1, 2, 3]:
            unit_id = family + 'T' + str(tier)
            core = dict(common) if not extractor else dict(copyFrom='extractor_common.ini')
            core.update(name=unit_id, techLevel=tier if extractor else min(tier, 2),
                        maxHp=([800, 1000, 2000] if extractor else [500, 1000, 2200])[tier - 1],
                        price=([700, 2100, 6100] if extractor else [2200, 6600, 14100])[tier - 1],
                        buildSpeed=.001 if extractor else [.0005, .0004, .0003][tier - 1],
                        generation_resources='credits=' + str(([8, 12, 20] if extractor else [2, 7, 14])[tier - 1]))
            if tier == 1 or not extractor:
                core['overrideAndReplace'] = family if tier == 1 else 'NONE'
            if not extractor:
                core['fireTurretXAtSelfOnDeath'] = 'explode'
                if tier > 1:
                    core['copyFrom'] = 'fabricatorT1.ini'
            graphic = dict(total_frames=4 if extractor else 3,
                           image=f'extractor_t{tier}.png' if extractor else f'fabricatorT{tier}.png')
            if extractor:
                graphic.update(image_back='extractor_back.png', image_back_always_use_full_image='true')
            else:
                graphic['image_wreak'] = 'fabricator_dead.png'
            animation = dict(onActions='idle', body_0s='{frame:0}', **{'body_0.8s': '{frame:3}',
                              'body_1.0s': '{frame:3}'}, pingPong='true') if extractor else dict(
                onActions='idle', body_0s='{frame:2}', **{
                    ['body_0.4s', 'body_0.3s', 'body_0.2s'][tier - 1]: '{frame:2}',
                    ['body_0.6s', 'body_0.5s', 'body_0.4s'][tier - 1]: '{frame:0}',
                    ['body_0.8s', 'body_0.6s', 'body_0.5s'][tier - 1]: '{frame:0}',
                    ['body_1.3s', 'body_1.0s', 'body_0.7s'][tier - 1]: '{frame:2}'})
            if extractor and tier > 1:
                animation['KeyframeTimeScale'] = '0.5' if tier == 2 else '0.33'
            if not extractor and tier > 1:
                animation = {'@copyFrom_skipThisSection': 'true', **animation}
            ai = dict(buildPriority=.4, recommendedInEachBasePriorityIfUnmet=.55, recommendedInEachBaseNum=2,
                      noneInBaseExtraPriority=.43, maxEachBase=99) if extractor else dict(
                buildPriority=.03, noneInBaseExtraPriority=.04, noneGlobalExtraPriority=.1, maxEachBase=1, maxGlobal=5)
            if extractor or tier > 1:
                ai['upgradedFrom'] = family + 'T1'
            sections = dict(core=core, graphics=graphic, movement=motion, attack=attack, animation_idle=animation, ai=ai)
            if not extractor:
                sections['turret_explode'] = dict(x=0, y=0, canShoot='false', projectile='explode')
                sections['projectile_explode'] = dict(life=99, speed=1, frame=0, shadowFrame=1, drawType=1,
                    areaDamage=[350, 450, 850][tier - 1], areaRadius=90, areaExpandTime=35, deflectionPower=-1,
                    lightSize=1, instant='true', largeHitEffect='true', targetGround='true', friendlyFire='true', lightColor='#FFEEEE00')
                if tier > 1:
                    sections['action_upgradeT2'] = {'@copyFrom_skipThisSection': 'true'}
            if tier < 3:
                sections['action_upgradeT' + str(tier + 1)] = action(family + 'T' + str(tier + 1),
                    ([1400, 4000] if extractor else [4400, 7500])[tier - 1],
                    (['0.0006', '0.0003'] if extractor else ['65.6s', '93.3s'])[tier - 1],
                    'i:gui.actions.upgradeT' + str(tier + 1), 'i:units.' + family + '.upgrade.description' + ('T3' if tier == 2 else ''))
            elif extractor:
                for key, target, price, rate in [('overclock', 'overclocked', 8000, '0.0002'), ('reinforce', 'reinforced', 3000, '0.0007')]:
                    sections['action_' + key] = action('extractorT3_' + target, price, rate,
                        'i:units.extractor.upgrade_' + key + '.text', 'i:units.extractor.upgrade_' + key + '.description')
            filename = 'extractor.ini' if extractor and tier == 1 else unit_id + '.ini'
            write(root, 'units/' + filename, ini(sections))
            png(root / 'units' / graphic['image'], 148 if extractor else 141, 59 if extractor else 60, (50 + tier * 20, 100, 80))
    png(root / 'units/extractor_back.png', 51, 44, (60, 80, 60))
    png(root / 'units/fabricator_dead.png', 47, 60, (90, 70, 60))


def generate():
    # Reuse own inputs, explicitly recorded below; these are not private evidence.
    base = PUBLIC / 'inputs/production-combat/center'
    variants = {'start': 'command-center-start', 'factory': 'factory-construction',
                'economy': 'economy-construction', 'upgrades': 'economy-upgrades', 'queue': 'building-maintenance'}
    for name, variant in variants.items():
        root = ROOT / name
        for source in base.rglob('*'):
            if source.is_file():
                target = root / source.relative_to(base)
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(source, target)
        write(root, 'construction.properties', 'format=rw-reforge.construction-sandbox.v1\nvariant=' + variant + '\n')
        if name != 'start':
            file = root / 'units/builder.properties'
            text = file.read_text(encoding='utf8').replace('native-builder.v1', 'native-builder.v2' if name == 'factory' else 'native-builder.v3')
            text += 'constructionTypes=' + ('landFactory' if name == 'factory' else 'extractorT1,landFactory,fabricatorT1') + '\n'
            write(root, 'units/builder.properties', text)
            factory = (root / 'units/landFactory.properties').read_text(encoding='utf8').replace('native-land-factory.v1', 'native-land-factory.v2')
            factory = factory.replace('productionUnit=c_tank', 'productionUnits=builder,c_tank\nplacementFootprint=-1,-1,1,3\nprice=700\nbuildSpeed=0.001')
            write(root, 'units/landFactory.properties', factory)
            for file in ['factory.png', 'factory_back.png', 'factory_dead.png']:
                png(root / 'units' / file, 50, 69 if file == 'factory_dead.png' else 74, (70, 90, 110))
        if name in ['economy', 'upgrades', 'queue']:
            economic_units(root)
            if name == 'economy':
                text = (root / 'units/extractor.ini').read_text(encoding='utf8').replace('extractor_t1.png', 'extractor.png')
                write(root, 'units/extractor.ini', text)
                png(root / 'units/extractor.png', 148, 59, (70, 100, 80))
            # A new open map with the independently fixed extractor test location.
            text = (root / 'maps/p001-controlled.tmx').read_text(encoding='utf8')
            text = text.replace('</tileset>', '<tile id="2"><properties><property name="cliff" value=""/></properties></tile><tile id="4"><properties><property name="res_pool" value=""/></properties></tile></tileset>')
            text = text.replace('width="40" height="20"', 'width="100" height="20"')
            png(root / 'assets/terrain.png', 100, 20, (60, 110, 60))
            gids = ['1'] * 600
            gids[14 * 30 + 9] = '5'
            text = text[:text.index('<data encoding="csv">') + len('<data encoding="csv">')] + ','.join(gids) + '</data></layer></map>\n'
            write(root, 'maps/p001-controlled.tmx', text)
            compact = text[:text.index('<data encoding="csv">') + len('<data encoding="csv">')]
            compact = compact.replace('width="30" height="20"', 'width="10" height="10"')
            small = ['1'] * 100
            small[8 * 10 + 8] = '5'
            write(root, 'maps/economy-chain.tmx', compact + ','.join(small) + '</data></layer></map>\n')
            operating = compact.replace('height="10"', 'height="14"')
            cells = ['1'] * 140
            cells[5 * 10 + 5] = '5'
            write(root, 'maps/operating-chain.tmx', operating + ','.join(cells) + '</data></layer></map>\n')
    # Keep the prior suite's immutable fixture unchanged. These extra loader tests
    # also require original mutation spelling and an artillery barrel with no sweep.
    root = ROOT / 'turret-upgrades'
    for source in (PUBLIC / 'inputs/production-combat/upgrade').rglob('*'):
        if source.is_file() and source.name not in ['open.tmx', 'production-chain.tmx']:
            target = root / source.relative_to(PUBLIC / 'inputs/production-combat/upgrade')
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, target)
    write(root, 'upgrade.properties', 'format=rw-reforge.upgrade-sandbox.v1\nvariant=two-branches\n')
    # Preserve the loader/evidence test's 25-file closure, including independently
    # drawn graphics for both completed forms and a real external tileset.
    text = (root / 'maps/p001-controlled.tmx').read_text(encoding='utf8')
    start = text.index('<tileset ')
    end = text.index('</tileset>', start) + len('</tileset>')
    tileset = text[start:end].replace(' firstgid="1"', '').replace('source="../assets/', 'source="../assets/')
    write(root, 'tilesets/p005-terrain.tsx', tileset + '\n')
    text = text[:start] + '<tileset firstgid="1" source="../tilesets/p005-terrain.tsx"/>' + text[end:]
    write(root, 'maps/p001-controlled.tmx', text)
    write(root, 'maps/p005-corridor.tmx', text)
    for filename in ['turret_base2.png', 'turret_top_l2.png', 'turret_top_artillery.png']:
        png(root / 'units' / filename, 40, 40, (90, 110, 70))
    for name, base_image, top_image in [('turret_t2_gun.ini', 'turret_base2.png', 'turret_top_l2.png'),
                                       ('turret_t1_artillery.ini', 'turret_base2.png', 'turret_top_artillery.png')]:
        text = (root / 'units' / name).read_text(encoding='utf8')
        write(root, 'units/' + name, text + '\n[graphics]\nimage: ' + base_image + '\nimage_turret: ' + top_image + '\n')
    file = root / 'units/turret_t1.ini'
    write(root, 'units/turret_t1.ini', file.read_text(encoding='utf8').replace('price: 1000', 'price:  1000').replace('price: 1600', 'price:  1600'))
    file = root / 'units/turret_t1_artillery.ini'
    common = (root / 'units/turret_common_land.ini').read_text(encoding='utf8')
    sweep = '\n'.join(line for line in common.splitlines() if line.startswith('idleSweep')) + '\n'
    write(root, 'units/turret_common_land.ini', '\n'.join(line for line in common.splitlines() if not line.startswith('idleSweep')) + '\n')
    for name in ['turret_t1.ini', 'turret_t2_gun.ini']:
        text = (root / 'units' / name).read_text(encoding='utf8')
        text = text.replace('[turret_1]\n', '[turret_1]\n' + sweep) if '[turret_1]' in text else text + '[turret_1]\n' + sweep
        write(root, 'units/' + name, text)
    text = file.read_text(encoding='utf8').replace('areaRadius: 55', 'areaRadius:55').replace('gravity: 0.18', 'gravity:0.18')
    write(root, 'units/turret_t1_artillery.ini', text)
    files = [dict(path=p.relative_to(ROOT).as_posix(), sha256=hashlib.sha256(p.read_bytes()).hexdigest())
             for p in sorted(ROOT.rglob('*')) if p.is_file() and p.name != 'OWNERSHIP.json']
    write(ROOT, 'OWNERSHIP.json', json.dumps(dict(source='New authored economic INI/maps and solid RGBA pixels from public loader/test contracts; own production-combat/center reused',
        semantics='Fixed loader values and existing assertions retained; not original equivalence', files=files), indent=2) + '\n')


if __name__ == '__main__':
    generate()
