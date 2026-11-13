"""Owned finite nuclear silo declarations and geometric art. No original input is read."""
from pathlib import Path
import json
import shutil
from aa_beam_fixture import png

ROOT = Path(__file__).resolve().parents[2]
DEST = ROOT / 'SimulationTest/public/inputs/nuclear-silo'

def generate_interception():
    for fmt in ('ini', 'json', 'hjson'):
        root = DEST / fmt
        package = root / 'interception-package'
        shutil.copytree(root / 'normal-package', package, dirs_exist_ok=True)
        fields = dict(format='rw-reforge.nuclear-silo.v1', name='antiNukeLauncherC', maxHp=2800,
            radius=35, footprint='-1,-1,1,1', constructionFootprint='-1,-1,1,1',
            movementType='NONE', price=15000, buildSpeed=.001,
            image='anti-body.png', image_back='anti-back.png', image_dead='anti-dead.png',
            image_arm1='anti-arm1.png', image_arm2='anti-arm2.png')
        text = '\n'.join(f'{k}={v}' for k, v in fields.items())+'\n' if fmt == 'ini' else json.dumps(fields, indent=2)+'\n'
        (package / f'antiNukeLauncherC.{fmt}').write_text(text, encoding='utf8', newline='\n')
        for name, width, height in [('body', 40, 48), ('back', 40, 48), ('dead', 40, 48), ('arm1', 13, 11), ('arm2', 12, 11)]:
            (package / f'anti-{name}.png').write_bytes(png('body', width, height))
        builder = package / f'builder.{fmt}'
        declaration = builder.read_text(encoding='utf8').replace('nukeLauncherC', 'nukeLauncherC,antiNukeLauncherC')
        builder.write_text(declaration, encoding='utf8', newline='\n')
        manifest = json.loads((package/'pack.json').read_text(encoding='utf8'))
        manifest.update(id='owned-nuclear-interception-'+fmt, name='Owned ground nuclear interception')
        manifest['units'].append(dict(type='nuclear-silo', file=f'antiNukeLauncherC.{fmt}'))
        (package/'pack.json').write_text(json.dumps(manifest, indent=2)+'\n', encoding='utf8', newline='\n')

def generate():
    for fmt in ('ini', 'json', 'hjson'):
        pack = DEST / fmt / 'package'
        pack.mkdir(parents=True, exist_ok=True)
        fields = dict(format='rw-reforge.nuclear-silo.v1', name='nukeLauncherC', maxHp=1500,
            radius=35, footprint='-1,-1,1,1', constructionFootprint='-2,-1,2,2',
            movementType='NONE', price=45000, buildSpeed=.001,
            image='body.png', image_back='back.png', image_dead='dead.png',
            image_arm1='arm1.png', image_arm2='arm2.png')
        text = '\n'.join(f'{k}={v}' for k, v in fields.items())+'\n' if fmt == 'ini' else json.dumps(fields, indent=2)+'\n'
        (pack / f'nukeLauncherC.{fmt}').write_text(text, encoding='utf8', newline='\n')
        for name, width, height in [('body', 84, 70), ('back', 84, 70), ('dead', 84, 70), ('arm1', 38, 29), ('arm2', 42, 29)]:
            (pack / f'{name}.png').write_bytes(png('body', width, height))
        (pack/'pack.json').write_text(json.dumps(dict(format='rw-reforge.content-pack.v1',
            id='owned-nuclear-silo-'+fmt, name='Owned nuclear silo',
            units=[dict(type='nuclear-silo', file=f'nukeLauncherC.{fmt}')]), indent=2)+'\n', encoding='utf8', newline='\n')
        production = DEST / fmt / 'production-package'
        shutil.copytree(pack, production, dirs_exist_ok=True)
        builder = dict(format='rw-reforge.native-builder.v7', name='builder', maxHp=170,
            radius=10, displayRadius=12, movementType='LAND', moveSpeed=.8,
            moveAccelerationSpeed=.04, moveDecelerationSpeed=.1, maxTurnSpeed=3.8,
            turnAcceleration=.35, price=500, buildSpeed=.002, fogOfWarSightRange=15,
            image='builder.png', image_dead='builder_dead.png', constructionTypes='nukeLauncherC')
        text = '\n'.join(f'{k}={v}' for k, v in builder.items())+'\n' if fmt == 'ini' else json.dumps(builder, indent=2)+'\n'
        (production/f'builder.{fmt}').write_text(text, encoding='utf8', newline='\n')
        for name in ('builder', 'builder_dead'):
            (production/f'{name}.png').write_bytes(png('body', 20, 20))
        manifest = json.loads((pack/'pack.json').read_text(encoding='utf8'))
        manifest.update(id='owned-nuclear-production-'+fmt, name='Owned nuclear silo construction')
        manifest['units'].append(dict(type='builder', file=f'builder.{fmt}'))
        (production/'pack.json').write_text(json.dumps(manifest, indent=2)+'\n', encoding='utf8', newline='\n')
        normal = DEST / fmt / 'normal-package'
        shutil.copytree(production, normal, dirs_exist_ok=True)
        shutil.copytree(ROOT / f'SimulationTest/public/inputs/repairbay-production/{fmt}/normal-package/units/commandCenter',
                        normal / 'commandCenter', dirs_exist_ok=True)
        manifest.update(id='owned-nuclear-normal-'+fmt, name='Owned normal command-center nuclear chain')
        manifest['units'].append(dict(type='command-center', file=f'commandCenter/commandCenter.{fmt}'))
        (normal/'pack.json').write_text(json.dumps(manifest, indent=2)+'\n', encoding='utf8', newline='\n')
        for directory in ('maps', 'assets'):
            shutil.copytree(ROOT / f'SimulationTest/public/inputs/repairbay-production/{fmt}/{directory}',
                            DEST / fmt / directory, dirs_exist_ok=True)

if __name__ == '__main__':
    generate()
    generate_interception()
