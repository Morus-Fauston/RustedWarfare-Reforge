"""Fixed-Dex O.v2 read-only branch/candidate hooks. Original instructions round-trip."""
import hashlib

SOURCE_SHA256='08da6419b760ecacc4477b7fad161da847154a714ab7d3d365955bd53226672f'
OWNER=b'Lcom/rustedwarfare/reforge/reference/motion/MinimalMotionObservation;'
UPDATE=b'.method public strictfp a(F)V'
COLLISION=b'.method private strictfp a(FLcom/corrodinggames/rts/gameFramework/k;FF)V'
PROPULSION=b'.method private strictfp a(FLcom/corrodinggames/rts/game/units/bv;Lcom/corrodinggames/rts/game/units/en;Z)V'

def transform(source):
    if type(source)is not bytes or hashlib.sha256(source).hexdigest()!=SOURCE_SHA256:
        raise ValueError('fixed O.v2 unit owner differs')
    newline=b'\r\n' if b'\r\n' in source else b'\n'
    insertions=[]
    def call(registers,method,descriptor):
        result=b'    invoke-static/range {'+registers+b'}, '+OWNER+b'->'+method+b'('+descriptor+b')V'+newline+newline
        insertions.append(result);return result
    def method(signature):
        if source.count(signature)!=1:raise ValueError('method count differs')
        start=source.index(signature);end=source.index(b'.end method',start)+len(b'.end method')
        return source[start:end]
    original=method(UPDATE)
    # APKTool labels this :cond_26; raw-Dex MethodProbe names the same code-unit @0401.
    anchor=b'    :cond_26'+newline+b'    invoke-direct/range {p0 .. p0}, Lcom/corrodinggames/rts/game/units/bp;->cV()V'+newline
    if original.count(anchor)!=1:raise ValueError('normal-completion branch differs')
    before=call(b'p0 .. p0',b'beforeNormalClear',b'Ljava/lang/Object;')
    after=call(b'p0 .. p0',b'afterNormalClear',b'Ljava/lang/Object;')
    replaced=anchor.split(newline,1)[0]+newline+before+anchor.split(newline,1)[1]+newline+after
    patched=source.replace(original,original.replace(anchor,replaced),1)
    for signature,name,descriptor in [(COLLISION,b'collisionBefore',b'Ljava/lang/Object;FLjava/lang/Object;FF'),
                                      (PROPULSION,b'propulsion',b'Ljava/lang/Object;FLjava/lang/Object;Ljava/lang/Object;Z')]:
        original=method(signature);anchor=b'    .prologue'+newline
        if original.count(anchor)!=1:raise ValueError('entry differs')
        updated=original.replace(anchor,anchor+call(b'p0 .. p4',name,descriptor),1)
        if signature==COLLISION:
            if updated.count(b'    return-void')!=1:raise ValueError('collision return count differs')
            updated=updated.replace(b'    return-void',call(b'p0 .. p0',b'collisionAfter',b'Ljava/lang/Object;')+b'    return-void',1)
        patched=patched.replace(original,updated,1)
    restored=patched
    for insertion in insertions:restored=restored.replace(insertion,b'')
    # The normal anchor adds one blank line, which is formatting only; compare instructions.
    def meaningful(raw):return [line for line in raw.splitlines()if line.strip()]
    if meaningful(restored)!=meaningful(source):raise ValueError('original instruction round-trip differs')
    return patched
