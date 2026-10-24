"""Additional read-only P-local-clear hooks; the qualified v2 transform stays unchanged."""
import hashlib
import importlib.util
from pathlib import Path

_path=Path(__file__).resolve().with_name('motion_detail_patch_v2.py')
if hashlib.sha256(_path.read_bytes()).hexdigest()!='f44e6287627fb752130961f88371a9c874c89bb09a1fa9c5d798b6d8d7572b15':
    raise ValueError('qualified v2 patch source differs')
_spec=importlib.util.spec_from_file_location('eng017_local_clear_base',_path)
base=importlib.util.module_from_spec(_spec);_spec.loader.exec_module(base)

SOURCE_SHA256=base.SOURCE_SHA256
OWNER=base.OWNER

def transform(source):
    patched=base.transform(source)
    newline=b'\r\n' if b'\r\n' in source else b'\n'
    start=patched.index(base.PROPULSION);end=patched.index(b'.end method',start)
    body=patched[start:end]
    anchor=b'    invoke-direct/range {p0 .. p0}, Lcom/corrodinggames/rts/game/units/bp;->cV()V'+newline
    if body.count(anchor)!=1:raise ValueError('P local-clear call count differs')
    def call(name):
        return b'    invoke-static/range {p0 .. p0}, '+OWNER+b'->'+name+b'(Ljava/lang/Object;)V'+newline+newline
    before=call(b'beforeLocalPathClear');after=call(b'afterLocalPathClear')
    replacement=body.replace(anchor,before+anchor+newline+after,1)
    result=patched[:start]+replacement+patched[end:]
    restored=result.replace(before,b'').replace(after,b'')
    if [line for line in restored.splitlines()if line.strip()]!=[line for line in patched.splitlines()if line.strip()]:
        raise ValueError('P local-clear hook round-trip differs')
    return result
