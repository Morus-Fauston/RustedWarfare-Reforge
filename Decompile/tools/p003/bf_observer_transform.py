import hashlib
import re
from typing import Dict, Tuple

HELPER_DESCRIPTOR = "Lcom/rustedwarfare/reforge/reference/probe/BoundaryStepProbe;"
METHOD_HEADER = ".method private strictfp b(F)V"
ENTRY_WINDOW = (
    ".method private strictfp b(F)V\r\n"
    "    .locals 14\r\n"
    "\r\n"
    "    .prologue\r\n"
    "    const/16 v13, 0x46\r\n"
    "\r\n"
    "    const v12, 0x3dcccccd    # 0.1f\r\n"
    "\r\n"
    "    const/4 v3, 0x0\r\n"
    "\r\n"
    "    .line 3170\r\n"
    "    invoke-virtual {p0}, Lcom/corrodinggames/rts/game/i;->ac()Z"
)
ENTER_PATCH = "    invoke-static {p1}, " + HELPER_DESCRIPTOR + "->onBfEnter(F)V"
NORMAL_PATCH = "    invoke-static {}, " + HELPER_DESCRIPTOR + "->onBfNormalExit()V"


class TransformError(ValueError):
    def __init__(self, code: str):
        super().__init__(code)
        self.code = code


def _valid_sha(value: str) -> bool:
    return isinstance(value, str) and re.fullmatch(r"[0-9a-fA-F]{64}", value) is not None


def _method_bounds(source: str) -> Tuple[int, int]:
    starts = [m.start() for m in re.finditer(r"(?m)^" + re.escape(METHOD_HEADER) + r"\r?$", source)]
    if len(starts) != 1:
        raise TransformError("method-not-unique")
    start = starts[0]
    end_line = re.search(r"(?m)^\.end method\r?$", source[start:])
    if end_line is None:
        raise TransformError("missing-window")
    end = start + end_line.start() + len(".end method")
    next_method = re.search(r"(?m)^\.method ", source[start + len(METHOD_HEADER):end])
    if next_method is not None:
        raise TransformError("missing-window")
    return start, end


def transform_bf_observer(source_text: str, expected_source_sha256: str,
                           expected_method_sha256: str,
                           helper_descriptor: str) -> Tuple[str, dict]:
    if (not isinstance(source_text, str) or not isinstance(helper_descriptor, str)
            or not _valid_sha(expected_source_sha256) or not _valid_sha(expected_method_sha256)):
        raise TransformError("invalid-transform-input")
    try:
        source_bytes = source_text.encode("utf-8")
    except UnicodeEncodeError as error:
        raise TransformError("invalid-transform-input") from error
    if helper_descriptor != HELPER_DESCRIPTOR:
        raise TransformError("invalid-helper")
    if "->onBfEnter(F)V" in source_text or "->onBfNormalExit()V" in source_text:
        raise TransformError("already-patched")
    actual_source_sha = hashlib.sha256(source_bytes).hexdigest()
    if actual_source_sha.lower() != expected_source_sha256.lower():
        raise TransformError("source-hash-mismatch")
    start, end = _method_bounds(source_text)
    method = source_text[start:end]
    actual_method_sha = hashlib.sha256(method.encode("utf-8")).hexdigest()
    if actual_method_sha.lower() != expected_method_sha256.lower():
        raise TransformError("method-hash-mismatch")
    if len(re.findall(r"(?m)^    \.locals 14\r?$", method)) != 1:
        raise TransformError("locals-mismatch")
    if method.count(ENTRY_WINDOW) != 1:
        raise TransformError("entry-anchor-mismatch")
    normal = "    return-void"
    if method.count(normal) != 1:
        raise TransformError("normal-anchor-mismatch")
    ac_line = "    invoke-virtual {p0}, Lcom/corrodinggames/rts/game/i;->ac()Z"
    entry = ENTRY_WINDOW
    transformed_method = method.replace(entry, entry.replace(ac_line, ENTER_PATCH + "\r\n" + ac_line), 1)
    transformed_method = transformed_method.replace(normal, NORMAL_PATCH + "\r\n" + normal, 1)
    transformed = source_text[:start] + transformed_method + source_text[end:]
    if transformed.count("->onBfEnter(F)V") != 1 or transformed.count("->onBfNormalExit()V") != 1:
        raise TransformError("verification-failed")
    restored = transformed.replace(ENTER_PATCH + "\r\n", "", 1).replace(NORMAL_PATCH + "\r\n", "", 1)
    restored_method = transformed_method.replace(ENTER_PATCH + "\r\n", "", 1).replace(NORMAL_PATCH + "\r\n", "", 1)
    if restored.encode("utf-8") != source_bytes or restored_method.encode("utf-8") != method.encode("utf-8"):
        raise TransformError("verification-failed")
    manifest: Dict[str, object] = {
        "schemaVersion": 1,
        "transformKind": "bf-observer-two-point",
        "sourceSha256Expected": expected_source_sha256.lower(),
        "sourceSha256Actual": actual_source_sha,
        "methodSha256Expected": expected_method_sha256.lower(),
        "methodSha256Actual": actual_method_sha,
        "sourceSha256After": hashlib.sha256(transformed.encode("utf-8")).hexdigest(),
        "methodSha256After": hashlib.sha256(transformed_method.encode("utf-8")).hexdigest(),
        "helperDescriptor": helper_descriptor,
        "installAllowed": False,
        "insertCounts": {"enter": 1, "normal": 1},
        "locals": 14,
        "logicalRegisters": 16,
        "tryTableChanged": False,
        "status": "transformed",
    }
    return transformed, manifest
