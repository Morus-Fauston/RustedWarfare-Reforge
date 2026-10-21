import unittest
import io
import struct
import zipfile
from pathlib import Path
import n_package_metadata as subject

ROOT = Path("Decompile/reference-bridge/build")
BASE = ROOT / "r12-composition-53ba9acb1306418bb5523b9ec13731df/r12-composed-unsigned.apk"
BAD = ROOT / "s05-n-45b98eccfb1c4c199ec00857db73cea5/unsigned.apk"


class PackageMetadataTest(unittest.TestCase):
    def test_real_writer_metadata_drift_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "external_attr"):
            subject.verify(BASE.read_bytes(), BAD.read_bytes())

    def test_repair_preserves_every_non_attribute_byte_and_rejects_other_drift(self):
        original, candidate = BASE.read_bytes(), BAD.read_bytes()
        repaired = subject.repair_zero_attributes(original, candidate)
        result = subject.verify(original, repaired)
        self.assertEqual(1142, result["originalEntries"])
        with zipfile.ZipFile(io.BytesIO(candidate)) as archive:
            cursor = archive.start_dir
            allowed = set()
            for info in archive.infolist():
                if info.filename != "classes3.dex":
                    allowed.update(range(cursor + 38, cursor + 42))
                n, e, c = struct.unpack_from("<3H", candidate, cursor + 28)
                cursor += 46 + n + e + c
            broken = bytearray(candidate)
            # DOS date: not part of the writer attribute defect.
            broken[archive.start_dir + 14] ^= 1
        changed = {i for i, (a, b) in enumerate(zip(candidate, repaired)) if a != b}
        self.assertEqual(len(candidate), len(repaired))
        self.assertTrue(changed)
        self.assertTrue(changed.issubset(allowed))
        with self.assertRaisesRegex(ValueError, "date_time"):
            subject.repair_zero_attributes(original, bytes(broken))
        with self.assertRaisesRegex(ValueError, "known zero-attribute"):
            subject.repair_zero_attributes(original, repaired)


if __name__ == "__main__":
    unittest.main()
