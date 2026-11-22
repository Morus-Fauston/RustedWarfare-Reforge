"""Generate paired, standalone group-move scenes from one verified sandbox scenario."""
from pathlib import Path
from copy import deepcopy
import hashlib
import json
import shutil
import sys
import xml.etree.ElementTree as ET


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def save(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def generate(source, out):
    template = json.loads(source.read_text(encoding='utf-8-sig'))
    inputs = (source.parent / template['inputs']).resolve()
    if not inputs.is_dir() or source.parent.resolve().is_relative_to(inputs):
        raise ValueError('scenario inputs must be a separate directory')
    for name, expected in template['testScene']['files'].items():
        if not (inputs / name).resolve().is_relative_to(inputs):
            raise ValueError(f'input path outside scene: {name}')
        if digest(inputs / name) != expected:
            raise ValueError(f'input digest mismatch: {name}')
    if out == inputs or out.is_relative_to(inputs):
        raise ValueError('output must be outside the source input directory')
    out.mkdir(exist_ok=False)
    cases = []
    for rocks in (False, True):
        root = out / ('rocks' if rocks else 'open')
        scenarios = root / 'scenarios'
        scenarios.mkdir(parents=True)
        dest = (scenarios / template['inputs']).resolve()
        if not dest.is_relative_to(root.resolve()):
            raise ValueError('inputs must stay inside the fixture directory')
        shutil.copytree(inputs, dest)
        scene = deepcopy(template)
        files = scene['testScene']['files']
        def input_path(ref):
            path = (scenarios / ref['path']).resolve()
            if not path.is_relative_to(dest) or path.relative_to(dest).as_posix() not in files:
                raise ValueError('content reference must name a verified file in the copied input')
            return path
        def replace_hash(ref):
            path = (scenarios / ref['path']).resolve()
            ref['sha256'] = files[path.relative_to(dest).as_posix()] = digest(path)
        small = next(u for u in scene['content']['unitVariants'] if u['id'] == 'c_tank')
        spath = input_path(small)
        profile = json.loads(spath.read_text(encoding='utf-8-sig'))
        profile['core']['radius'] = profile['core']['displayRadius'] = 6
        profile['movement'].update(maxTurnSpeed=4.1, turnAcceleration=0.25)
        save(spath, profile)
        replace_hash(small)
        mref = scene['content']['map']
        mpath = input_path(mref)
        tree = ET.parse(mpath)
        w, h = (74, 40) if rocks else (42, 29)
        dims = {'width': str(w), 'height': str(h)}
        tree.getroot().attrib.update(dims)
        tile = ET.SubElement(tree.find('tileset'), 'tile', id='2')
        ET.SubElement(ET.SubElement(tile,'properties'),'property', name='large-rock', value='')
        layer = tree.find('layer'); layer.attrib.update(dims)
        def blocked(x, y):
            if x in (0,w-1) or y in (0,h-1): return True
            if not rocks: return False
            # Each column of stone reaches both boundary walls. Gate widths are 1 or 6 tiles.
            for start, narrow, wide in ((24,12,23),(34,15,20),(44,11,25)):
                if start <= x < start+2:
                    return y != narrow and not wide <= y < wide+6
            return False
        layer.find('data').text = ','.join('3' if blocked(x,y) else '1' for y in range(h) for x in range(w))
        tree.write(mpath,encoding='utf-8'); replace_hash(mref)
        variants = [('small100',100,90),('mixed100',100,90)] if rocks else [
            (f'open{n}-{angle}',n,angle) for n in (1,2,10,50) for angle in (90,270)]
        for name,count,heading in variants:
            units = []
            for index in range(count):
                if rocks: x,y = 420-36*(index//10), 180+36*(index%10)
                elif index == 0: x,y=392,272
                else: x,y=356-36*((index-1)//7), 272+36*((0,-1,1,-2,2,-3,3)[(index-1)%7])
                units.append(dict(unitId=f'tank-{index:02}',owner='p1',type='mechGun' if name=='mixed100' and index%4==3 else 'c_tank',position=dict(x=x,y=y),heading=heading,height=0))
            click = [1260,400] if rocks else [680,272]
            scene.update(scenarioId=name, units=units,actions=[],intents=[dict(intentId="select-leader",kind="select",unitId="tank-00",waitSteps=1)],termination=dict(kind="explicit-steps",steps=[dict(stepIndex=0,delta=1,deltaUnit="sim-tick")]))
            scene.pop('offlineCamera',None)
            scene['testScene']['displayName']=name
            scene['combatContract'].update(unitObjectIds={u['unitId']:8+i for i,u in enumerate(units)}, nextObjectId=count+8)
            path = scenarios/(name+'.json'); save(path,scene)
            # Geometry accompanies the input, independently of what the planner assigns.
            geometry = dict(tileSize=20, width=w, height=h,
                blocked=[[x,y] for y in range(h) for x in range(w) if blocked(x,y)],
                command=name+'-0', click=click, members={u['unitId']:u for u in units},
                bodies={k:dict(shape='circle', radius=r) for k,r in [('c_tank',6),('mechGun',13)]},
                gateColumns=([24,34,44] if rocks else []),
                entryPlaneX=(480 if rocks else None),exitPlaneX=(920 if rocks else None),
                capacityWitness=([[1060+36*i,220+36*j] for i in range(10) for j in range(10)] if rocks else []))
            save(scenarios/(name+'-geometry.json'),geometry)
            cases.append(dict(name=name,scene=path.relative_to(out).as_posix(),sha256=digest(path),click=click,count=count,heading=heading,seed=scene['combatContract']['worldSeed']))
    save(out/'cases.json',cases)

if __name__=='__main__':
    generate(Path(sys.argv[1]).resolve(), Path(sys.argv[2]).resolve())
