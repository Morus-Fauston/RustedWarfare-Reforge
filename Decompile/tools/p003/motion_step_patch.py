"""Two observer calls at the fixed original simulation method's entry/normal return."""
import hashlib
SOURCE_SHA256='f348f71c6c341d55932756b9826a385df07003bab61f7fe51620d944dd4867c8'
METHOD_SHA256='a675055facd29e463c2c05461808d759ff167e7092681d43e71644d970b929a8'
SIGNATURE=b'.method private strictfp b(F)V'
BEFORE=b'    invoke-static {p0, p1}, Lcom/rustedwarfare/reforge/reference/motion/NativeMotionTrace;->before(Ljava/lang/Object;F)V\r\n\r\n'
AFTER=b'    invoke-static {p0, p1}, Lcom/rustedwarfare/reforge/reference/motion/NativeMotionTrace;->after(Ljava/lang/Object;F)V\r\n\r\n'
def transform(source):
    if type(source) is not bytes or hashlib.sha256(source).hexdigest()!=SOURCE_SHA256:raise ValueError('fixed motion owner differs')
    start=source.index(SIGNATURE);end=source.index(b'.end method',start)+len(b'.end method');body=source[start:end]
    anchor=b'    .prologue\r\n'
    if hashlib.sha256(body).hexdigest()!=METHOD_SHA256 or body.count(anchor)!=1 or body.count(b'    return-void')!=1:
        raise ValueError('fixed motion method differs')
    patched=body.replace(anchor,anchor+BEFORE).replace(b'    return-void',AFTER+b'    return-void')
    assert patched.replace(BEFORE,b'').replace(AFTER,b'')==body
    return source[:start]+patched+source[end:]
