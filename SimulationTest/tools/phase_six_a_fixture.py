"""Compose the six-A default sandbox from previously reviewed, owned content."""
from collections import Counter
import hashlib
import json
from pathlib import Path
import re
import shutil
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1] / 'public'
INPUTS = ROOT / 'inputs'
FORMATS = ('ini', 'json', 'hjson')
MENU_FIELDS = {
    'units/mech-factory/builder': {
        'format': 'rw-reforge.native-builder.v7',
        'constructionTypes': ('extractorT1,fabricatorT1,landFactory,mechFactory,airFactory,seaFactory,c_turret_t1,'
                              'laserDefence,repairbay,experimentalLandFactory,nukeLauncherC,antiNukeLauncherC'),
    },
    'units/economy/landFactory': {'productionUnits': 'builder,c_tank,hoverTank'},
    'units/mech-factory/mechFactory': {'productionUnits': 'builder,mechGun,mechBunker'},
    'units/mech-factory/mechFactoryT2': {
        'productionUnits': 'builder,mechGun,mechHeavyMissile,mechBunker,mechEngineer,mechFlame',
    },
    'units/air-factory/units/airFactoryT2': {
        'productionUnits': 'helicopter,amphibiousJet,dropship,aaBeamGunship,bomber',
    },
    'units/sea-factory/seaFactoryT2': {
        'productionUnits': 'heavyBattleship,attackSubmarine,hovercraft,nautilusSubmarine',
    },
}
ADDITIONS = (
    ('phase-five-sandbox', 'package', 'command-center', {
        'units/commandCenter/commandCenter': 'command-center',
    }),
    ('aa-beam-production', 'production-package', 'air-products', {
        'units/amphibiousJet': 'amphibious-jet', 'units/dropship': 'dropship',
        'units/aaBeamGunship': 'aa-beam-gunship', 'units/aaBeamGunship_afterburn': 'aa-beam-gunship',
    }),
    ('bunker-production', 'production-package', 'bunker', {
        'units/bunker/bunker': 'mech-bunker', 'units/bunker/deployed': 'mech-bunker',
    }),
    ('flame-mech-production', 'production-package', 'flame', {'units/flame/flame': 'mech-flame'}),
    ('nautilus-production', 'production-package', 'nautilus', {
        'nautilus/nautilus': 'nautilus', 'nautilus/nautilusSurface': 'nautilus', 'nautilus/nautilusLand': 'nautilus',
        'nautilus/hover/hover': 'hover-tank', 'nautilus/scout/robotCrab': 'scout-bot',
        'nautilus/scout/robotCrabWater': 'scout-bot', 'units/experimentalFactory': 'experimental-factory',
    }),
    ('nuclear-silo', 'interception-package', 'silos', {
        'nukeLauncherC': 'nuclear-silo', 'antiNukeLauncherC': 'nuclear-silo',
    }),
)


def write_json(path, document):
    path.write_text(json.dumps(document, ensure_ascii=False, indent=2) + '\n', encoding='utf8', newline='\n')


def fixed_text(data):
    return data.replace(b'\r\n', b'\n')


def copy_closure(source, destination, visited):
    if destination in visited:
        assert visited[destination] == source
        return
    visited[destination] = source
    text = fixed_text(source.read_bytes())
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_bytes(text)
    for name in set(re.findall(rb'[A-Za-z0-9_-]+\.(?:png|ini|json|hjson)\b', text)):
        name = name.decode('ascii')
        child = source.with_name(name)
        if child.suffix == '.png':
            shutil.copyfile(child, destination.with_name(name))
        else:
            copy_closure(child, destination.with_name(name), visited)


def build_inputs(destination, form):
    template = INPUTS / 'modular-spider-start' / form / 'opening-package'
    pack = destination / 'package'
    shutil.copytree(template, pack)
    for item in pack.rglob('*.' + form):
        item.write_bytes(fixed_text(item.read_bytes()))
    entries = json.loads((template / 'pack.json').read_text(encoding='utf8'))['units']
    for stem, replacements in MENU_FIELDS.items():
        unit = pack / (stem + '.' + form)
        if form == 'ini':
            text = unit.read_text(encoding='utf8')
            for key, value in replacements.items():
                text, matches = re.subn(rf'(?m)^{key}\s*[:=][^\n]*$', f'{key}={value}', text)
                assert matches == 1, (unit, key)
            unit.write_text(text, encoding='utf8', newline='\n')
        else:
            text = unit.read_text(encoding='utf8')
            for key, value in replacements.items():
                text, matches = re.subn(rf'"{key}"\s*:\s*"[^"]*"', f'"{key}": "{value}"', text)
                assert matches == 1, (unit, key)
            unit.write_text(text, encoding='utf8', newline='\n')
    visited = {}
    for group, package, subdirectory, additions in ADDITIONS:
        source = INPUTS / group / form / package
        for stem, loader in additions.items():
            relative = Path('extra') / subdirectory / (stem + '.' + form)
            copy_closure(source / (stem + '.' + form), pack / relative, visited)
            entries.append(dict(type=loader, file=relative.as_posix()))
    write_json(pack / 'pack.json', dict(format='rw-reforge.content-pack.v1', id=f'phase-six-a.{form}',
               name='Owned Phase VI A sandbox', units=entries))
    build_map(destination)
    return entries


def build_map(destination):
    source = INPUTS / 'phase-five-sandbox/json'
    assets = destination / 'assets'
    assets.mkdir()
    shutil.copyfile(source / 'assets/naval-ground.png', assets / 'naval-ground.png')
    foundations = INPUTS / 'three-format-economy/construction/json/assets/terrain.png'
    shutil.copyfile(foundations, assets / 'foundation.png')
    document = ET.parse(source / 'maps/phase-five.tmx')
    root = document.getroot()
    layer = root.find('layer')
    overlay = ET.Element('tileset', firstgid='41', name='owned-foundations', tilewidth='20', tileheight='20')
    ET.SubElement(overlay, 'image', source='../assets/foundation.png', width='100', height='20')
    for index, key in ((1, 'trees'), (2, 'cliff'), (4, 'res_pool')):
        properties = ET.SubElement(ET.SubElement(overlay, 'tile', id=str(index)), 'properties')
        ET.SubElement(properties, 'property', name=key, value='')
    root.insert(list(root).index(layer), overlay)
    cells = layer.find('data')
    tiles = cells.text.strip().split(',')
    width = int(root.get('width'))
    for x, y in ((4, 22), (11, 24), (19, 20)):
        assert tiles[y * width + x] == '1'
        tiles[y * width + x] = '45'
    for x, y in ((15, 17), (16, 17), (17, 17), (18, 17)):
        assert tiles[y * width + x] == '1'
        tiles[y * width + x] = '43'
    cells.text = ','.join(tiles)
    (destination / 'maps').mkdir()
    document.write(destination / 'maps/phase-six-a.tmx', encoding='utf-8')


def unit_id(file):
    text = file.read_text(encoding='utf8')
    if file.suffix == '.ini':
        match = re.search(r'(?im)^name\s*[:=]\s*([^\r\n]+)', text)
    else:
        match = re.search(r'"name"\s*:\s*"([^"]+)"', text)
    assert match, file
    return match[1].strip()


def seed(destination, entries, form, spider):
    template = json.loads((ROOT / 'scenarios/phase-five-json.json').read_text(encoding='utf8'))
    keys = ('schemaVersion', 'kind', 'coordinateModel', 'timeModel', 'angleUnit', 'movementContract', 'players')
    name = 'spider' if spider else 'command'
    data = {key: template[key] for key in keys}
    files = {file.relative_to(destination).as_posix(): hashlib.sha256(file.read_bytes()).hexdigest()
             for file in sorted(destination.rglob('*')) if file.is_file()}
    prefix = f'../inputs/phase-six-a-sandbox/{form}'
    def ref(identity, relative):
        return dict(id=identity, path=f'{prefix}/{relative}', sha256=files[relative])
    variants = [ref(unit_id(destination / 'package' / row['file']), f"package/{row['file']}")
                for row in entries]
    assert all(count == 1 for count in Counter(row['id'] for row in variants).values())
    map_ref = ref('map', 'maps/phase-six-a.tmx')
    refs = {row['path'].removeprefix(prefix + '/') for row in [*variants, map_ref]}
    units = template['units']
    first = 'modularSpider' if spider else 'commandCenter'
    if spider:
        units[0] = dict(unitId='center', owner='p1', type=first, position=dict(x=110, y=150), heading=90)
    intents, actions, policy = opening_plan(template, spider)
    steps = sum(intent.get('waitSteps', 0) for intent in intents)
    data.update(
        scenarioId=f'public.phase-six-a-{name}-{form}.v1',
        content=dict(map=map_ref, unitVariants=variants,
                     dependencies=[ref(f'dep{index}', path) for index, path in enumerate(files) if path not in refs]),
        units=units, actions=actions,
        termination=dict(kind='explicit-steps', steps=[dict(stepIndex=i, delta=1, deltaUnit='sim-tick') for i in range(steps)]),
        combatContract=template['combatContract'],
        economyContract=dict(initialBalances=dict(p1=5000 if spider else 30000, p2=0)),
        maintenanceContract=dict(basePrices={
            'commandCenter': 3000, 'landFactory': 700, 'mechFactory': 1000, 'mechFactoryT2': 5000,
            'airFactory': 1000, 'airFactoryT2': 2500, 'seaFactory': 1000, 'seaFactoryT2': 3000,
            'experimentalLandFactory': 11000, 'laserDefence': 1200, 'c_turret_t1': 500, 'c_turret_t2_gun': 1500,
            'c_turret_t1_artillery': 2100, 'extractorT1': 700, 'extractorT2': 2100, 'extractorT3': 6100,
            'fabricatorT1': 2200, 'fabricatorT2': 6600, 'fabricatorT3': 14100, 'repairbay': 1500,
            'nukeLauncherC': 45000, 'antiNukeLauncherC': 15000,
        }),
        inputs=prefix, contentPack=dict(directory='package', unitId=first),
        intents=intents, windowsDefaultStart=True,
        testScene=dict(formatVersion=1, displayName='Modular Spider' if spider else 'Command center', files=files),
    )
    if spider:
        data['windowsSpiderStart'] = True
    contract = dict(schemaVersion=1, scenarioId=data['scenarioId'],
                    rules=[dict(intentId=intent['intentId']) for intent in intents], history=policy)
    return data, contract


def opening_plan(template, spider):
    if spider:
        intents = [
            dict(intentId='settle-spider', kind='select', position=dict(x=20, y=20), waitSteps=100),
            dict(intentId='select-spider', kind='select', position=dict(x=110, y=150), waitSteps=0),
            dict(intentId='select-slot', kind='unit-action', coreActionId='select-slot', actionName='selectSlot1', waitSteps=0),
            dict(intentId='buy-gun', kind='unit-action', coreActionId='buy-gun', actionName='buildGun', waitSteps=600),
            dict(intentId='finish-gun', kind='stop', coreActionId='finish-gun', waitSteps=450),
        ]
        actions = [dict(actionId=name, actor='p1', unitId='center', atStep=100, kind='unit-action',
                        actionName=command, cancelQueued=False) for name, command in
                   [('select-slot', 'selectSlot1'), ('buy-gun', 'buildGun')]]
        actions.append(dict(actionId='finish-gun', actor='p1', unitId='center', atStep=700, kind='stop'))
        policy = dict(initialBalance=5000, initialOwnedTypes=['modularSpider', *(['modularSpider_emptySlot'] * 6)],
            mechLegs={'modularSpider': 8},
            noActionIntents=['settle-spider'],
            initialIncome=dict(unitId='center', typeId='modularSpider', period=40, credits=30),
            spend=[dict(intentId='buy-gun', amount=2000, at='action')],
            finalUnits=[dict(typeId='modularSpider_gunturret', ownerPlayerId='p1',
                             fields=dict(alive=True, constructionProgress=1))])
        return intents, actions, policy
    intents = template['intents'][:11]
    base = {intent.get('coreActionId') for intent in intents}
    actions = [action for action in template['actions'] if action['actionId'] in base]
    intents[-1]['waitSteps'] = 550
    intents.append(dict(intentId='produce-hover', kind='produce', coreActionId='produce-hover', typeId='hoverTank', waitSteps=550))
    actions.append(dict(actionId='produce-hover', actor='p1', unitId='constructed:p1:' + '2' * 32, atStep=2850,
                        kind='produce', productionTypeId='hoverTank', unitNonce='4' * 32))
    policy = dict(initialBalance=30000, initialOwnedTypes=['commandCenter'],
        initialIncome=dict(unitId='center', typeId='commandCenter', period=10, credits=4.5),
        spend=[dict(intentId=name, amount=price, at=when) for name, price, when in (
            ('produce-builder', 500, 'action'), ('build-land', 700, 'construction-created'),
            ('produce-tank', 350, 'action'), ('produce-hover', 450, 'action'))],
        productionOrder=['builder', 'c_tank', 'hoverTank'],
        finalUnits=[dict(typeId=type_id, ownerPlayerId='p1', fields=dict(alive=True, constructionProgress=1))
                    for type_id in ('commandCenter', 'landFactory', 'c_tank', 'hoverTank')])
    return intents, actions, policy


def generate(output=ROOT):
    base = output / 'inputs/phase-six-a-sandbox'
    assert not base.exists()
    for form in FORMATS:
        destination = base / form
        entries = build_inputs(destination, form)
        for spider in (False, True):
            name = f"phase-six-a-{'spider-' if spider else ''}{form}"
            scenario, contract = seed(destination, entries, form, spider)
            for group, value in (('scenarios', scenario), ('contracts', contract)):
                path = output / group / (name + '.json')
                assert not path.exists()
                write_json(path, value)


if __name__ == '__main__':
    generate()
