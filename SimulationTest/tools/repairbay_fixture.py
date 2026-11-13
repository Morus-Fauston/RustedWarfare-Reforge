"""Owned repair-bay declarations and geometric art; no original pixels are read."""
from pathlib import Path
import json
import shutil
from aa_beam_fixture import png

ROOT = Path(__file__).resolve().parents[2]
DEST = ROOT / 'SimulationTest/public/inputs/repairbay-production'


def generate():
    for fmt in ('ini', 'json', 'hjson'):
        pack = DEST / fmt / 'package'
        pack.mkdir(parents=True, exist_ok=True)
        fields = dict(format='rw-reforge.native-repairbay.v1', name='repairbay', maxHp=1000,
            radius=30, footprint='-1,-1,1,1', movementType='NONE', price=1500, buildSpeed=.001,
            image='body.png', image_dead='dead.png')
        text = '\n'.join(f'{k}={v}' for k, v in fields.items())+'\n' if fmt == 'ini' else json.dumps(fields, indent=2)+'\n'
        (pack / f'repairbay.{fmt}').write_text(text, encoding='utf8')
        (pack / 'body.png').write_bytes(png('body', 62, 76))
        (pack / 'dead.png').write_bytes(png('body', 60, 76))
        (pack / 'pack.json').write_text(json.dumps(dict(format='rw-reforge.content-pack.v1',
            id='owned-repairbay-'+fmt, name='Owned repair bay',
            units=[dict(type='repair-bay', file=f'repairbay.{fmt}')]), indent=2)+'\n', encoding='utf8')
        production = DEST / fmt / 'production-package'
        shutil.copytree(pack, production, dirs_exist_ok=True)
        builder = dict(format='rw-reforge.native-builder.v7', name='builder', maxHp=170,
            radius=10, displayRadius=12, movementType='LAND', moveSpeed=.8,
            moveAccelerationSpeed=.04, moveDecelerationSpeed=.1, maxTurnSpeed=3.8,
            turnAcceleration=.35, price=500, buildSpeed=.002, fogOfWarSightRange=15,
            image='builder.png', image_dead='builder_dead.png', constructionTypes='repairbay')
        text = '\n'.join(f'{k}={v}' for k, v in builder.items())+'\n' if fmt == 'ini' else json.dumps(builder, indent=2)+'\n'
        (production / f'builder.{fmt}').write_text(text, encoding='utf8')
        (production / 'builder.png').write_bytes(png('body', 20, 20))
        (production / 'builder_dead.png').write_bytes(png('body', 20, 20))
        (production / 'pack.json').write_text(json.dumps(dict(format='rw-reforge.content-pack.v1',
            id='owned-repairbay-production-'+fmt, name='Owned repair bay construction', units=[
                dict(type='repair-bay', file=f'repairbay.{fmt}'),
                dict(type='builder', file=f'builder.{fmt}')]), indent=2)+'\n', encoding='utf8')
        zero = DEST / fmt / 'reclaim-zero-package'
        shutil.copytree(ROOT / f'SimulationTest/public/inputs/phase-five-sandbox/{fmt}/package/units/c_tank',
            zero, dirs_exist_ok=True)
        tank = zero / f'tank.{fmt}'
        text = tank.read_text(encoding='utf8')
        text = text.replace('price: 350', 'price: 350\nreclaimPrice: 0') if fmt == 'ini' else text.replace(
            '"price": 350,', '"price": 350,\n    "reclaimPrice": 0,')
        assert 'reclaimPrice' in text
        tank.write_text(text, encoding='utf8')
        (zero / 'pack.json').write_text(json.dumps(dict(format='rw-reforge.content-pack.v1',
            id='owned-reclaim-zero-'+fmt, name='Owned explicit zero reclaim target',
            units=[dict(type='tank', file=f'tank.{fmt}')]), indent=2)+'\n', encoding='utf8')
        normal = DEST / fmt / 'normal-package'
        shutil.copytree(production, normal, dirs_exist_ok=True)
        manifest = json.loads((normal / 'pack.json').read_text(encoding='utf8'))
        manifest.update(id='owned-repairbay-normal-'+fmt, name='Owned normal repair bay production')
        for name, kind, stem in [('commandCenter', 'command-center', 'commandCenter'),
                ('landFactory', 'land-factory', 'landFactory'), ('c_tank', 'tank', 'tank')]:
            shutil.copytree(ROOT / f'SimulationTest/public/inputs/phase-five-sandbox/{fmt}/package/units/{name}',
                normal / 'units' / name, dirs_exist_ok=True)
            manifest['units'].append(dict(type=kind, file=f'units/{name}/{stem}.{fmt}'))
        builder_path = normal / f'builder.{fmt}'
        builder_path.write_text(builder_path.read_text(encoding='utf8').replace('repairbay', 'landFactory,repairbay'), encoding='utf8')
        (normal / 'pack.json').write_text(json.dumps(manifest, indent=2)+'\n', encoding='utf8')
        for directory in ('maps', 'assets'):
            shutil.copytree(ROOT / f'SimulationTest/public/inputs/production-combat/combat/{directory}',
                DEST / fmt / directory, dirs_exist_ok=True)
        (DEST / fmt / 'combat.properties').write_text('format=rw-reforge.combat-sandbox.v1\n', encoding='utf8')


if __name__ == '__main__':
    generate()
