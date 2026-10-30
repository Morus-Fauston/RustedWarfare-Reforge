"""Owned synthetic fixtures, reconstructed from public test requirements, never original files.

Only input files are generated. Expected observations/checkpoints are separate fixed contracts.
"""
from pathlib import Path
import hashlib
import json
import struct
import zlib

ROOT = Path(__file__).parent / 'inputs/content-movement-knowledge'


def write(root, name, text):
    path = root / name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(text.encode('utf-8'))


def png(path, width, height, rgb):
    def chunk(kind, data):
        return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data))
    scan = (b'\0' + bytes(rgb) * width) * height
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>2I5B', width, height, 8, 2, 0, 0, 0)) +
                     chunk(b'IDAT', zlib.compress(scan)) + chunk(b'IEND', b''))


def generate():
    unit = '''[core]
name: p001_weaponless_tank
class: CustomUnitMetadata
radius: 11
displayRadius: 11
fogOfWarSightRange: 3
price: 17
maxHp: 42
mass: 1
techLevel: 1
buildSpeed: 0.01
isBio: false
softCollisionOnAll: 0
[graphics]
total_frames: 3
image: ../assets/unit.png
imageScale: 0.8
image_shadow: AUTO
shadowOffsetX: 1
shadowOffsetY: 1
animation_moving_start: 0
animation_moving_end: 2
animation_moving_speed: 1
dustEffect: false
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
    def tileset(name, width, height, count, columns, ids):
        return (f'<tileset name="{name}" tilewidth="20" tileheight="20" tilecount="{count}" columns="{columns}">'
                f'<image source="../assets/{name}.png" width="{width}" height="{height}"/>' +
                ''.join(f'<tile id="{i}"><properties><property name="trees" value=""/></properties></tile>' for i in ids) +
                '</tileset>\n')
    gids = [62 if (col == 14 and row not in [9, 10, 11]) or
            (20 <= col <= 24 and 3 <= row <= 7 and (col in [20, 24] or row in [3, 7])) else 1
            for row in range(20) for col in range(30)]
    assert gids.count(62) == 33
    tmx = ('<map orientation="orthogonal" renderorder="right-down" width="30" height="20" tilewidth="20" tileheight="20">'
           '<tileset firstgid="1" source="../tilesets/p001-ground.tsx"/>'
           '<tileset firstgid="41" source="../tilesets/p001-rock.tsx"/>'
           '<layer name="Ground" width="30" height="20"><data encoding="csv">' + ','.join(map(str, gids)) +
           '</data></layer></map>\n')
    roots = [ROOT / 'P001-移动沙盒输入', ROOT / 'P002-知识沙盒输入']
    for root in roots:
        for name, width, height, rgb in [('ground', 100, 160, (62, 104, 53)),
                                          ('rock', 140, 100, (108, 103, 95)), ('unit', 60, 20, (40, 91, 173))]:
            png(root / f'assets/{name}.png', width, height, rgb)
        write(root, 'units/weaponless_tank.ini', unit)
        write(root, 'maps/p001-controlled.tmx', tmx)
        write(root, 'tilesets/p001-ground.tsx', tileset('ground', 100, 160, 40, 5, [16]))
        write(root, 'tilesets/p001-rock.tsx', tileset('rock', 140, 100, 35, 7, [7, 8, 21, 22, 28, 29]))
        write(root, 'negative/L01.ini', unit.replace('radius: 11\n', '', 1))
        write(root, 'negative/L02b.ini', unit.replace('../assets/unit.png', 'missing.png'))
        write(root, 'negative/L03a.ini', unit + '\n[attack]\ncanAttack: true\n')
        write(root, 'negative/L02a.tmx', tmx.replace('../tilesets/p001-ground.tsx', 'missing.tsx'))
        write(root, 'negative/L03b.tmx', tmx.replace('encoding="csv"', 'encoding="base64"'))
        write(root, 'negative/L03c.tmx', tmx.replace('../tilesets/p001-ground.tsx', 'L03c.tsx'))
        write(root, 'negative/L03c.tsx', tileset('ground', 100, 160, 40, 5, [16]).replace('name="trees"', 'name="water"'))
    write(roots[1], 'sandbox.properties', '''format=rw-reforge.sandbox.v1
p1.team=blue
p1.x=110
p1.y=110
p1.heading=90
p2.team=blue
p2.x=510
p2.y=310
p2.heading=0
p3.team=red
p3.x=550
p3.y=310
p3.heading=0
''')
    files = [dict(path=p.relative_to(ROOT).as_posix(), sha256=hashlib.sha256(p.read_bytes()).hexdigest())
             for p in sorted(ROOT.rglob('*')) if p.is_file() and p.name != 'OWNERSHIP.json']
    write(ROOT, 'OWNERSHIP.json', json.dumps(dict(
        source='New authored functional fixtures from public MapLoadTest/UnitVariantLoadTest/LoadFailureTest requirements',
        pixels='New solid RGB rasters encoded with Python standard library; no original art',
        semantics='Movement values and geometry preserve existing assertions; unrelated retained values are synthetic',
        files=files), indent=2) + '\n')


if __name__ == '__main__':
    generate()
