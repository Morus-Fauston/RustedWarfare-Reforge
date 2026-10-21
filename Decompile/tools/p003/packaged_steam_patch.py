"""One fixed optional-Steam branch for the approved immutable APK mod set."""
import hashlib

SOURCE_SHA256 = "19272e131cb8f2f6a92f3d2fb6cbf67851dea87367275cc6a5d04ff2afd5b0ba"
METHOD_SHA256 = "83c495118c75f0697dd8a8aaedd28fd204946d336ea2fbe32b104d17708b7d04"
SIGNATURE = b".method public final j()V"
ANCHOR = b"    :cond_5\r\n    new-instance v0, Ljava/lang/StringBuilder;"
BRANCH = b"\r\n".join([
    b"    :cond_5",
    b"    iget-object v0, p0, Lcom/corrodinggames/rts/gameFramework/i/b;->o:Ljava/lang/String;",
    b"    invoke-static {v0}, Lcom/rustedwarfare/reforge/reference/boot/PackagedModMetadata;->skipSteamProbe(Ljava/lang/String;)Z",
    b"    move-result v0",
    b"    if-nez v0, :cond_0",
    b"    new-instance v0, Ljava/lang/StringBuilder;",
])


def transform(source):
    if not isinstance(source, bytes) or hashlib.sha256(source).hexdigest() != SOURCE_SHA256:
        raise ValueError("fixed mod metadata owner differs")
    start = source.index(SIGNATURE)
    end = source.index(b".end method", start) + len(b".end method")
    body = source[start:end]
    if hashlib.sha256(body).hexdigest() != METHOD_SHA256 or body.count(ANCHOR) != 1:
        raise ValueError("fixed mod metadata method differs")
    return source[:start] + body.replace(ANCHOR, BRANCH) + source[end:]
