"""Keep only the fixed original update popup for owned main-thread teardown."""
import hashlib
SOURCE_SHA256 = 'ff7a25c04d4a31a500983729b1c2810e83126d694f06a77cd8417a2f714b82dd'
METHOD_SHA256 = '7c833889974df62fb1536ff313df4c965d70a820b740dc70e79c9b531d0dd09d'
SIGNATURE = b'.method public static showUpdateMessagePopup(Landroid/content/Context;Z)Z'
OLD = b'    invoke-virtual {v0}, Landroid/app/AlertDialog$Builder;->show()Landroid/app/AlertDialog;'
NEW = b'    invoke-virtual {v0}, Landroid/app/AlertDialog$Builder;->show()Landroid/app/AlertDialog;\r\n\r\n    move-result-object v0\r\n\r\n    invoke-static {p0, v0}, Lcom/rustedwarfare/reforge/reference/boot/FixedUpdateDialogCleanup;->track(Landroid/content/Context;Landroid/app/Dialog;)V'


def transform(source):
    if type(source) is not bytes or hashlib.sha256(source).hexdigest() != SOURCE_SHA256:
        raise ValueError('fixed popup owner differs')
    start = source.index(SIGNATURE); end = source.index(b'.end method', start) + len(b'.end method')
    body = source[start:end]
    if hashlib.sha256(body).hexdigest() != METHOD_SHA256 or body.count(OLD) != 1:
        raise ValueError('fixed popup method differs')
    return source[:start] + body.replace(OLD, NEW) + source[end:]
