"""Fixed map read wrapper only; public conversion and mutation callers stay private."""
import hashlib
SOURCE_SHA256 = '08198761533334dcc6f87ba41364efc2d291e4e58ff24ea16deca2baedcce78e'
METHOD_SHA256 = '6e64dd0ce7f0d7d484be51b9ff964a6d72a8261b6d719b8cd766c9f66935959f'
SIGNATURE = b'.method private static strictfp c(Ljava/lang/String;)Ljava/lang/String;'
OLD = b'Lcom/corrodinggames/rts/gameFramework/e/a;->e(Ljava/lang/String;)Ljava/lang/String;'
NEW = b'Lcom/rustedwarfare/reforge/reference/boot/CheckedReadRoute;->normalize(Ljava/lang/String;)Ljava/lang/String;'

def transform(source):
    if not isinstance(source,bytes) or hashlib.sha256(source).hexdigest()!=SOURCE_SHA256:
        raise ValueError("fixed map owner differs")
    start=source.index(SIGNATURE);end=source.index(b".end method",start)+len(b".end method")
    body=source[start:end]
    if hashlib.sha256(body).hexdigest()!=METHOD_SHA256 or body.count(OLD)!=1:
        raise ValueError("fixed map read method differs")
    return source[:start]+body.replace(OLD,NEW)+source[end:]
