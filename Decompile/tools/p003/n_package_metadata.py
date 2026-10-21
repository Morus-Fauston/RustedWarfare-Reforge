"""S05 original-payload ZIP metadata verification and one known writer repair."""
import io
import struct
import zipfile

STABLE = ("compress_type", "flag_bits", "date_time", "create_system", "create_version",
          "extract_version", "reserved", "internal_attr", "external_attr", "comment", "extra")


def verify(original, candidate):
    with zipfile.ZipFile(io.BytesIO(original)) as old, zipfile.ZipFile(io.BytesIO(candidate)) as new:
        if len(set(old.namelist())) != len(old.namelist()) or new.namelist() != old.namelist() + ["classes3.dex"]:
            raise ValueError("N entry order/set differs")
        if new.comment != old.comment or old.testzip() or new.testzip():
            raise ValueError("N archive comment/CRC differs")
        for before in old.infolist():
            after = new.getinfo(before.filename)
            for field in STABLE:
                if getattr(before, field) != getattr(after, field):
                    raise ValueError("protected " + field + " differs: " + before.filename)
            if before.filename != "classes.dex":
                if (before.compress_size, before.CRC, before.file_size) != (after.compress_size, after.CRC, after.file_size):
                    raise ValueError("protected size/CRC differs: " + before.filename)
                if old.read(before) != new.read(after):
                    raise ValueError("protected payload differs: " + before.filename)
        return dict(originalEntries=len(old.namelist()), unchangedPayloads=len(old.namelist()) - 1)


def repair_zero_attributes(original, candidate):
    """Restore ONLY the observed 0 -> 0600 writer change; preserve every other byte."""
    raw = bytearray(candidate)
    with zipfile.ZipFile(io.BytesIO(original)) as old, zipfile.ZipFile(io.BytesIO(candidate)) as new:
        if new.namelist() != old.namelist() + ["classes3.dex"]:
            raise ValueError("N entry set differs")
        cursor = new.start_dir
        for info in new.infolist():
            if raw[cursor:cursor + 4] != b"PK\x01\x02":
                raise ValueError("central signature differs")
            name_length, extra_length, comment_length = struct.unpack_from("<3H", raw, cursor + 28)
            name = bytes(raw[cursor + 46:cursor + 46 + name_length]).decode(
                "utf-8" if info.flag_bits & 0x800 else "cp437")
            if name != info.filename:
                raise ValueError("central name differs")
            if name in old.namelist():
                if old.getinfo(name).external_attr != 0 or info.external_attr != 0o600 << 16:
                    raise ValueError("not the known zero-attribute writer defect")
                struct.pack_into("<I", raw, cursor + 38, 0)
            cursor += 46 + name_length + extra_length + comment_length
    repaired = bytes(raw)
    verify(original, repaired)
    return repaired
