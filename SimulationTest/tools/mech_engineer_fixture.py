"""Owned finite engineer content and geometric art. Never reads original pixels."""
from pathlib import Path
import json
import shutil
from aa_beam_fixture import png
from bunker_fixture import render

ROOT = Path(__file__).resolve().parents[2]
DEST = ROOT / 'SimulationTest/public/inputs/mech-engineer-production'


def generate():
    for fmt in ('ini', 'json', 'hjson'):
        pack = DEST / fmt / 'package'
        shutil.copytree(ROOT / f'SimulationTest/public/inputs/repairbay-production/{fmt}/package',
                        pack / 'repairbay', dirs_exist_ok=True)
        fields = dict(name='mechEngineer', maxHp=2000, radius=19, price=5000, buildSpeed=.0008, techLevel=2,
            fogOfWarSightRange=16, transportSlotsNeeded=3, selfRegenRate=.08,
            canRepairBuildings=True, canRepairUnits=True, autoRepair=True, canReclaimResources=True,
            nanoRange=140, nanoRepairSpeed=.3, nanoBuildSpeed=2, generation_credits=1, generation_delay=40,
            constructionTypes='repairbay', image='blank.png', image_dead='dead.png',
            image_body='body.png', image_turret='cannon.png', image_leg='leg.png', image_foot='foot.png')
        movement = dict(movementType='OVER_CLIFF', moveSpeed=.85, moveAccelerationSpeed=.02,
            moveDecelerationSpeed=.2, maxTurnSpeed=1.5, turnAcceleration=1,
            moveSlidingMode=True, moveIgnoringBody=True, moveSlidingDir=180, reverseSpeedPercentage=.2)
        pack.mkdir(parents=True, exist_ok=True)
        (pack/f'engineer.{fmt}').write_text(render(dict(core=fields, movement=movement), fmt), encoding='utf8')
        for name, kind, w, h in [('blank', 'body', 1, 1), ('body', 'body', 40, 44), ('dead', 'dead', 38, 44),
                ('cannon', 'turret', 28, 35), ('leg', 'turret', 19, 82), ('foot', 'body', 26, 33)]:
            (pack/f'{name}.png').write_bytes(png(kind, w, h))
        manifest = dict(format='rw-reforge.content-pack.v1', id='owned-mech-engineer-'+fmt,
            name='Owned mechanic engineer', units=[dict(type='mech-engineer', file=f'engineer.{fmt}'),
                dict(type='repair-bay', file=f'repairbay/repairbay.{fmt}')])
        (pack/'pack.json').write_text(json.dumps(manifest, indent=2)+'\n', encoding='utf8')
        production = DEST/fmt/'production-package'
        shutil.copytree(ROOT/f'SimulationTest/public/inputs/heavy-mech-production/{fmt}/production-package',
                        production, dirs_exist_ok=True)
        shutil.copytree(pack, production/'units/engineer', dirs_exist_ok=True)
        production_engineer = production/f'units/engineer/engineer.{fmt}'
        production_engineer.write_text(production_engineer.read_text(encoding='utf8').replace(
            'repairbay', 'repairbay,mechFactory,mechFactoryT2,mechGun'), encoding='utf8')
        normal = json.loads((production/'pack.json').read_text(encoding='utf8'))
        normal.update(id='owned-engineer-production-'+fmt, name='Owned normal T2 engineer production')
        normal['units'] += [dict(type=entry['type'], file='units/engineer/'+entry['file']) for entry in manifest['units']]
        shutil.copytree(ROOT/f'SimulationTest/public/inputs/repairbay-production/{fmt}/normal-package/units/commandCenter',
                        production/'units/commandCenter', dirs_exist_ok=True)
        normal['units'].append(dict(type='command-center', file=f'units/commandCenter/commandCenter.{fmt}'))
        (production/'pack.json').write_text(json.dumps(normal, indent=2)+'\n', encoding='utf8')
        factory = production/f'units/mechFactoryT2.{fmt}'
        text = factory.read_text(encoding='utf8')
        assert 'builder,mechGun,mechHeavyMissile' in text
        factory.write_text(text.replace('builder,mechGun,mechHeavyMissile',
                                       'builder,mechGun,mechHeavyMissile,mechEngineer'), encoding='utf8')
        for directory in ('maps', 'assets'):
            shutil.copytree(ROOT/f'SimulationTest/public/inputs/heavy-mech-production/{fmt}/{directory}',
                            DEST/fmt/directory, dirs_exist_ok=True)
        (DEST/fmt/'combat.properties').write_text('format=rw-reforge.combat-sandbox.v1\n', encoding='utf8')


if __name__ == '__main__':
    generate()
