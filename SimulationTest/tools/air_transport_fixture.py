"""Create owned air transport inputs; never reads original images or game bytecode."""
from pathlib import Path
import json
import shutil
import struct
import zlib

ROOT = Path(__file__).resolve().parents[2]
DEST = ROOT / 'SimulationTest/public/inputs/air-transport-production'


def png(kind):
    def chunk(name, body):
        return struct.pack('>I', len(body)) + name + body + struct.pack('>I', zlib.crc32(name + body) & 0xffffffff)
    rows = []
    for y in range(47):
        row = bytearray([0])
        for x in range(45):
            body = 17 <= x <= 27 and 3 <= y <= 43
            wings = 3 <= x <= 41 and 18 <= y <= 28
            color = ((66, 64, 59, 255) if kind == 'dead' else
                     (35, 39, 42, 100) if kind == 'shadow' else (64, 129, 109, 255))
            if kind == 'body' and body and 6 <= y <= 12:
                color = (174, 207, 211, 255)
            row.extend(color if body or wings else (0, 0, 0, 0))
        rows.append(row)
    return (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', 45, 47, 8, 6, 0, 0, 0)) +
            chunk(b'IDAT', zlib.compress(b''.join(rows))) + chunk(b'IEND', b''))


def write_native(path, fields):
    path.write_text(('\n'.join(f'{key}={value}' for key, value in fields.items()) + '\n')
                    if path.suffix == '.ini' else json.dumps(fields, indent=2) + '\n', encoding='utf8')


def generate():
    assert not DEST.exists(), 'Never overwrite a fixture batch'
    for fmt in ('ini', 'json', 'hjson'):
        root = DEST / fmt
        shutil.copytree(ROOT / f'SimulationTest/public/inputs/amphibious-production/{fmt}', root)
        pack = root / 'production-package'
        unit_path = pack / 'units' / f'airFactoryT2.{fmt}'
        text = unit_path.read_text(encoding='utf8')
        assert text.count('helicopter,amphibiousJet') == 1
        unit_path.write_text(text.replace('helicopter,amphibiousJet', 'helicopter,amphibiousJet,dropship'), encoding='utf8')
        fields = dict(format='rw-reforge.native-dropship.v1', name='dropship', maxHp=500, radius=20,
                      price=800, buildSpeed=.001, image='dropship.png', image_dead='dropship_dead.png',
                      image_shadow='dropship_shadow.png')
        write_native(pack / 'units' / f'dropship.{fmt}', fields)
        for kind, name in [('body', 'dropship.png'), ('dead', 'dropship_dead.png'), ('shadow', 'dropship_shadow.png')]:
            (pack / 'units' / name).write_bytes(png(kind))
        manifest_path = pack / 'pack.json'
        manifest = json.loads(manifest_path.read_text(encoding='utf8'))
        manifest.update(id=f'p009-air-transport-production-{fmt}', name='Owned air transport production')
        manifest['units'].append(dict(type='dropship', file=f'units/dropship.{fmt}'))
        manifest_path.write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf8')
    (DEST / 'README.md').write_text('# Owned air transport inputs\n\n'
        'Generated once with SimulationTest/tools/air_transport_fixture.py. Existing owned amphibious-production '
        'definitions, map and art are reused. The three 45x47 transport images use the new rectangle recipe in '
        'that generator. No original images, audio, bytecode or derived pixels are included. Native behavior '
        'is independently implemented; the original core is not a runtime dependency.\n', encoding='utf8')


if __name__ == '__main__':
    generate()
