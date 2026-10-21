"""Approved S05 packaged-asset timestamp routing at one fixed original method."""
import hashlib

SOURCE_SHA256 = "c8efe9d7afbca702e2bad2d15474309e10c4afb7628b387a5833e36cad47e03f"
METHOD_SHA256 = "4765d04173dc7e56377723cf513cee52e11dc8ee90a02c094c34c259208696a1"
SIGNATURE = b".method public static n(Ljava/lang/String;)J"
OLD = b"Lcom/rustedwarfare/reforge/reference/boot/GbootBootstrap;->privateLastModified(Ljava/lang/String;)J"
NEW = b"Lcom/rustedwarfare/reforge/reference/boot/AssetMetadata;->lastModified(Ljava/lang/String;)J"


def transform(source):
    if not isinstance(source, bytes) or hashlib.sha256(source).hexdigest() != SOURCE_SHA256:
        raise ValueError("fixed metadata owner differs")
    start = source.index(SIGNATURE)
    end = source.index(b".end method", start) + len(b".end method")
    body = source[start:end]
    if hashlib.sha256(body).hexdigest() != METHOD_SHA256 or body.count(OLD) != 1:
        raise ValueError("fixed metadata method differs")
    return source[:start] + body.replace(OLD, NEW) + source[end:]
