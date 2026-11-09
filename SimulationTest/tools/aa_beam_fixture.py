"""Generate owned AA beam content without reading original assets or code."""
from pathlib import Path
import json
import shutil
import struct
import zlib

ROOT = Path(__file__).resolve().parents[2]
DEST = ROOT / 'SimulationTest/public/inputs/aa-beam-production'


def png(kind, width, height):
    def chunk(name, data):
        return struct.pack('>I', len(data)) + name + data + struct.pack('>I', zlib.crc32(name + data) & 0xffffffff)
    rows = []
    for y in range(height):
        row = bytearray([0])
        for x in range(width):
            if kind.startswith('beam'):
                visible = abs(x - width // 2) <= (2 if kind == 'beam' else 5)
                color = (94, 224, 226, 230) if abs(x - width // 2) > 1 else (224, 255, 250, 255)
            else:
                visible = width // 3 <= x <= width * 2 // 3 and 2 <= y < height - 2 or height // 3 <= y <= height * 2 // 3 and 2 <= x < width - 2
                color = dict(body=(58, 126, 112, 255), dead=(69, 64, 58, 255), shadow=(30, 35, 39, 100), turret=(161, 190, 175, 255))[kind]
            row.extend(color if visible else (0, 0, 0, 0))
        rows.append(row)
    return (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', width, height, 8, 6, 0, 0, 0)) +
            chunk(b'IDAT', zlib.compress(b''.join(rows))) + chunk(b'IEND', b''))


def generate():
    assert not DEST.exists(), 'Never overwrite an existing input batch'
    for fmt in ('ini', 'json', 'hjson'):
        root = DEST / fmt
        shutil.copytree(ROOT / f'SimulationTest/public/inputs/air-transport-production/{fmt}', root)
        pack = root / 'production-package'
        t2 = pack / 'units' / f'airFactoryT2.{fmt}'
        text = t2.read_text(encoding='utf8')
        assert text.count('helicopter,amphibiousJet,dropship') == 1
        t2.write_text(text.replace('helicopter,amphibiousJet,dropship', 'helicopter,amphibiousJet,dropship,aaBeamGunship'), encoding='utf8')
        fields = dict(format='rw-reforge.aa-beam-gunship.v1', name='aaBeamGunship', maxHp=2500, radius=22,
            price=6000, buildSpeed=1 / 2100, image='aa.png', image_dead='aa_dead.png',
            image_shadow='aa_shadow.png', image_turret='aa_turret.png', beam_image='aa_beam.png',
            beam_image_start='aa_beam_start.png', beam_image_end='aa_beam_end.png')
        unit = pack / 'units' / f'aaBeamGunship.{fmt}'
        unit.write_text(('\n'.join(f'{key}={value}' for key, value in fields.items()) + '\n') if fmt == 'ini'
                        else json.dumps(fields, indent=2) + '\n', encoding='utf8')
        for kind, name, width, height in [('body','aa.png',33,52), ('dead','aa_dead.png',33,52),
            ('shadow','aa_shadow.png',33,52), ('turret','aa_turret.png',16,26), ('beam','aa_beam.png',13,20),
            ('beam_start','aa_beam_start.png',20,20), ('beam_end','aa_beam_end.png',20,20)]:
            (pack / 'units' / name).write_bytes(png(kind, width, height))
        manifest_path = pack / 'pack.json'
        manifest = json.loads(manifest_path.read_text(encoding='utf8'))
        manifest.update(id=f'p009-aa-beam-production-{fmt}', name='Owned AA beam production')
        manifest['units'].append(dict(type='aa-beam-gunship', file=f'units/aaBeamGunship.{fmt}'))
        manifest_path.write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf8')
    (DEST / 'README.md').write_text('# Owned AA beam inputs\n\n'
        'Generated once by SimulationTest/tools/aa_beam_fixture.py. Existing owned air transport content, '
        'map and art are reused. Seven AA images use the rectangle recipe in that generator. No original '
        'images, audio, bytecode or derived pixels are included. The original core is not a runtime dependency.\n', encoding='utf8')


if __name__ == '__main__':
    generate()
