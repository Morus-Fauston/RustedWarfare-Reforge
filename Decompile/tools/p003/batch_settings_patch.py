"""Only three settings-name instructions in the fixed current own helper."""
import hashlib

SOURCE_SHA256 = "0d2c7983b9d11a7de426db7f7812a02afe98d8042d05aea812ebf207251ef6a7"
CHANGES = (
    (b'    const-string p0, "rts_settings.xml"', b"xmlName", b"p0"),
    (b'    const-string v2, "rts_settings.xml.bak"', b"backupName", b"v2"),
    (b'    const-string v4, "rts_settings"', b"name", b"v4"),
)


def transform(raw):
    if type(raw) is not bytes or hashlib.sha256(raw).hexdigest() != SOURCE_SHA256:
        raise ValueError("fixed Gboot smali identity differs")
    newline = b"\r\n" if b"\r\n" in raw else b"\n"
    output = raw
    for old, method, register in CHANGES:
        if output.count(old) != 1:
            raise ValueError("fixed settings instruction differs")
        replacement = (b"    invoke-static {}, Lcom/rustedwarfare/reforge/reference/boot/BatchSettings;->"
                       + method + b"()Ljava/lang/String;" + newline + b"    move-result-object " + register)
        output = output.replace(old, replacement)
    return output
