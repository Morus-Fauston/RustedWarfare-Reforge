"""Reproduce the original, synthetic select/move/stop input; no third-party assets."""
from pathlib import Path
import hashlib
import json
import struct
import zlib


def generate():
    root = Path(__file__).resolve().parent
    assets = root / 'inputs' / 'select-move-stop'
    assets.mkdir(parents=True, exist_ok=True)
    def chunk(kind, payload):
        return struct.pack('!I', len(payload)) + kind + payload + struct.pack('!I', zlib.crc32(kind + payload))
    png = (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('!2I5B', 20, 20, 8, 2, 0, 0, 0))
           + chunk(b'IDAT', zlib.compress((b'\0' + bytes([64, 128, 64]) * 20) * 20)) + chunk(b'IEND', b''))
    (assets / 'owned.png').write_bytes(png)
    (assets / 'open-ground.tmx').write_text('''<map orientation="orthogonal" renderorder="right-down" width="12" height="12" tilewidth="20" tileheight="20">
<tileset firstgid="1" name="owned" tilewidth="20" tileheight="20"><image source="owned.png" width="20" height="20"/></tileset>
<layer name="Ground" width="12" height="12"><data encoding="csv">''' + ','.join(['1'] * 144) + '</data></layer>\n</map>\n', encoding='utf-8', newline='\n')
    (assets / 'walker.ini').write_text('''[core]
name: public_walker
class: CustomUnitMetadata
radius: 1
displayRadius: 1
[graphics]
total_frames: 1
image: owned.png
[movement]
movementType: LAND
moveSpeed: 1
moveAccelerationSpeed: 1
moveDecelerationSpeed: 1
maxTurnSpeed: 1
turnAcceleration: 1
moveSlidingMode: false
moveIgnoringBody: false
''', encoding='utf-8', newline='\n')
    def identity(name, filename):
        return dict(id=name, path='../inputs/select-move-stop/' + filename,
                    sha256=hashlib.sha256((assets / filename).read_bytes()).hexdigest())
    scenario = dict(schemaVersion=1, kind='scenario', scenarioId='public.select-move-stop.v1',
        content=dict(map=identity('public.open-ground', 'open-ground.tmx'),
                     unitVariant=identity('public.walker', 'walker.ini'),
                     dependencies=[identity('public.owned-image', 'owned.png')]),
        coordinateModel=dict(id='public.coordinate.v1', space='2d', xAxis='x-right', yAxis='y-down',
                             origin='top-left', unit='world-unit', valueType='double', precision='ieee754-binary64'),
        timeModel=dict(id='public.time.v1', deltaUnit='sim-tick', stepRule='fixed-explicit-delta', precision='ieee754-binary64'),
        angleUnit='degree-clockwise-zero-up', movementContract=dict(pathPointRadius=16, targetRadius=4),
        players=[dict(playerId='p1', control='local'), dict(playerId='p2', control='local')],
        units=[dict(unitId='u1', owner='p1', type='public.walker', position=dict(x=70, y=70), heading=90)],
        actions=[dict(actionId='select-1', atStep=0, actor='p1', kind='select', unitId='u1'),
                 dict(actionId='move-1', atStep=1, actor='p1', kind='move', unitId='u1', target=dict(x=170, y=70)),
                 dict(actionId='foreign-stop', atStep=2, actor='p2', kind='stop', unitId='u1'),
                 dict(actionId='stop-1', atStep=3, actor='p1', kind='stop', unitId='u1')],
        termination=dict(kind='explicit-steps', required=True,
                         steps=[dict(stepIndex=i, delta=1, deltaUnit='sim-tick') for i in range(6)]))
    (root / 'scenarios').mkdir(exist_ok=True)
    (root / 'scenarios' / 'select-move-stop.json').write_text(json.dumps(scenario, indent=2) + '\n', encoding='utf-8', newline='\n')


if __name__ == '__main__':
    generate()
