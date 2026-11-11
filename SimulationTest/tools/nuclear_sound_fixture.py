"""Deterministic owned synthesis; reads no original recording or pixels."""
from pathlib import Path
import hashlib, json, math, struct, wave

ROOT = Path(__file__).resolve().parents[2]
DEST = ROOT/'Engine/client/src/main/resources/sounds'

def generate():
    for name, duration in [('nuke-launch', .8), ('nuke-explode', 1.8)]:
        values=[]
        state=137
        for i in range(int(22050*duration)):
            t=i/22050
            state=(state*1664525+1013904223)&0xffffffff
            noise=(state/0xffffffff*2-1)
            if name=='nuke-launch':
                value=(math.sin(2*math.pi*(90*t+180*t*t))*.5+noise*.18)*min(1,t*30)*math.exp(-t*3)
            else:
                value=(math.sin(2*math.pi*42*t)*.45+noise*.45)*min(1,t*100)*math.exp(-t*2.8)
            values.append(struct.pack('<h',round(max(-1,min(1,value))*24000)))
        with wave.open(str(DEST/f'{name}.wav'),'wb') as out:
            out.setnchannels(1);out.setsampwidth(2);out.setframerate(22050);out.writeframes(b''.join(values))
        print(name, hashlib.sha256((DEST/f'{name}.wav').read_bytes()).hexdigest())
    path=DEST/'sounds.json'
    doc=json.loads(path.read_text(encoding='utf8'))
    for name in ('nuke-launch','nuke-explode'):
        doc['sounds']['reforge:sounds/'+name]=dict(file=name+'.wav')
        doc['events']['reforge:events/'+name]=dict(sound='reforge:sounds/'+name)
    path.write_text(json.dumps(doc,indent=2)+'\n',encoding='utf8')

if __name__=='__main__':generate()
