"""D-082 fixed method transform and local first-diagnostic evidence decoding."""
import base64
import hashlib
import re
import struct

SOURCE_SHA256 = "479cd8802dff3b5ba4ab1ab43715e95f1d2e9a8ae61d84ad85e309235d64e946"
METHOD_SHA256 = "6f21e670c4a1ecaaacdac2db0cf3b2a4d674e771179339858ec172c8896e42df"
SIGNATURE = b".method public static a(Ljava/lang/String;Ljava/lang/String;)V"


def transform(source):
    if not isinstance(source, bytes) or hashlib.sha256(source).hexdigest() != SOURCE_SHA256:
        raise ValueError("fixed original diagnostic owner identity differs")
    start = source.index(SIGNATURE)
    end = source.index(b".end method", start) + len(b".end method")
    if hashlib.sha256(source[start:end]).hexdigest() != METHOD_SHA256:
        raise ValueError("fixed diagnostic method differs")
    body = b"\r\n".join([
        SIGNATURE,
        b"    .locals 1",
        b"    invoke-static {p0, p1}, Lcom/rustedwarfare/reforge/reference/boot/DiagnosticReportBlock;->reject(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/RuntimeException;",
        b"    move-result-object v0",
        b"    throw v0",
        b".end method",
    ])
    return source[:start] + body + source[end:]


def parse_messages(messages):
    """Decode one process's extracted diagnostic messages; never repair evidence."""
    lines = list(messages)
    if len(lines) < 3:
        raise ValueError("diagnostic evidence missing or truncated")
    first = re.fullmatch(r"RWR_NA_BEGIN ([0-9]+) ([0-9a-f]{64})", lines[0])
    last = re.fullmatch(r"RWR_NA_END ([0-9]+) ([0-9a-f]{64})", lines[-1])
    if not first or not last or first[2] != last[2] or int(last[1]) != len(lines) - 2:
        raise ValueError("diagnostic completion framing differs")
    chunks = []
    for index, line in enumerate(lines[1:-1]):
        part = re.fullmatch(r"RWR_NA_CHUNK ([0-9]+) ([A-Za-z0-9+/=]{1,1024})", line)
        if not part or part[1] != str(index):
            raise ValueError("diagnostic chunk missing, duplicate or unordered")
        try:
            chunk = base64.b64decode(part[2], validate=True)
        except ValueError as error:
            raise ValueError("diagnostic base64 invalid") from error
        if base64.b64encode(chunk).decode("ascii") != part[2]:
            raise ValueError("diagnostic base64 not canonical")
        chunks.append(chunk)
    raw = b"".join(chunks)
    if len(raw) != int(first[1]) or hashlib.sha256(raw).hexdigest() != first[2]:
        raise ValueError("diagnostic length or digest mismatch")
    values = []
    cursor = 0
    for _ in range(2):
        if cursor + 4 > len(raw):
            raise ValueError("diagnostic string length missing")
        length = struct.unpack_from(">i", raw, cursor)[0]
        cursor += 4
        if length == -1:
            values.append(None)
        elif length < 0 or cursor + length * 2 > len(raw):
            raise ValueError("diagnostic string bounds invalid")
        else:
            values.append(raw[cursor:cursor + length * 2].decode("utf-16-be", errors="surrogatepass"))
            cursor += length * 2
    if cursor != len(raw):
        raise ValueError("diagnostic trailing bytes")
    return dict(category=values[0], detail=values[1], bytes=len(raw), sha256=first[2])
