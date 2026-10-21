"""One fixed sourced TSX alias; no general traversal normalization."""
import hashlib
SOURCE_SHA256 = 'c0688bd3e5126e6f4b8e3149a1fad240f36e8303e4b5e1e2fd077655d8f9bbb5'
METHOD_SHA256 = '422dd14e69f0a8691e1cecb6cb229836a578c916176518ba8c7fb70dbcd990ec'
SIGNATURE = b'.method private static strictfp a(Ljava/lang/String;)Lorg/w3c/dom/Element;'
OLD = b'    invoke-static {v0, p0}, Lcom/corrodinggames/rts/game/b/b;->b(Ljava/lang/String;Ljava/lang/String;)Ljava/io/InputStream;'
NEW = b'    invoke-static {p0}, Lcom/rustedwarfare/reforge/reference/boot/PackagedTilesetReference;->name(Ljava/lang/String;)Ljava/lang/String;\r\n    move-result-object v1\r\n    invoke-static {v0, v1}, Lcom/corrodinggames/rts/game/b/b;->b(Ljava/lang/String;Ljava/lang/String;)Ljava/io/InputStream;'

def transform(source):
    if not isinstance(source,bytes) or hashlib.sha256(source).hexdigest()!=SOURCE_SHA256:
        raise ValueError("fixed map owner differs")
    start=source.index(SIGNATURE);end=source.index(b".end method",start)+len(b".end method")
    body=source[start:end]
    if hashlib.sha256(body).hexdigest()!=METHOD_SHA256 or body.count(OLD)!=1:
        raise ValueError("fixed map read method differs")
    return source[:start]+body.replace(OLD,NEW)+source[end:]
