"""Owned Nautilus production fixtures; geometric art, no original asset reads."""
import json
from pathlib import Path
import shutil
from aa_beam_fixture import png
from bunker_fixture import render

ROOT = Path(__file__).resolve().parents[2]
DEST = ROOT / 'SimulationTest/public/inputs/nautilus-production'


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
        (pack / f'nautilus.{fmt}').write_text(render(dict(core=core, movement=movement), fmt), encoding='utf8')
        for name in ('body', 'dead'):
            (pack / f'{name}.png').write_bytes(png(name, 42, 90))
        shutil.copytree(ROOT / f'SimulationTest/public/inputs/hover-production/{fmt}/package/units/hoverTank',
                        pack / 'hover', dirs_exist_ok=True)
        manifest = dict(format='rw-reforge.content-pack.v1', id='owned-nautilus-'+fmt,
            name='Owned Nautilus mobile production', units=[dict(type='nautilus', file=f'nautilus.{fmt}'),
                dict(type='hover-tank', file=f'hover/hover.{fmt}')])
        (pack / 'pack.json').write_text(json.dumps(manifest, indent=2)+'\n', encoding='utf8')
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
        (production / 'pack.json').write_text(json.dumps(normal, indent=2)+'\n', encoding='utf8')
        factory = production / f'units/seaFactoryT2.{fmt}'
        text = factory.read_text(encoding='utf8')
        assert 'heavyBattleship,attackSubmarine' in text
        factory.write_text(text.replace('heavyBattleship,attackSubmarine',
            'heavyBattleship,attackSubmarine,nautilusSubmarine'), encoding='utf8')
        for directory in ('maps', 'assets'):
            shutil.copytree(ROOT / f'SimulationTest/public/inputs/naval-production/{fmt}/{directory}',
                            root / directory, dirs_exist_ok=True)


if __name__ == '__main__':
    generate()
