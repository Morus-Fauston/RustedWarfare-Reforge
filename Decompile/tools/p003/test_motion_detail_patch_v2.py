import hashlib
from pathlib import Path
import unittest
import motion_detail_patch_v2 as patch

ROOT=Path(__file__).resolve().parents[3]
SOURCE=ROOT/'Decompile/reference-bridge/build/s05-settings-decode-9f59b8cac44f43a4be215af093f612dc/smali/com/corrodinggames/rts/game/units/bp.smali'
class MotionDetailPatchV2Test(unittest.TestCase):
    def test_only_five_read_only_hooks_and_all_original_instructions_survive(self):
        raw=SOURCE.read_bytes();result=patch.transform(raw)
        self.assertEqual(hashlib.sha256(raw).hexdigest(),patch.SOURCE_SHA256)
        self.assertEqual(result.count(patch.OWNER),5)
        filtered=[line for line in result.splitlines()if line.strip() and patch.OWNER not in line]
        self.assertEqual(filtered,[line for line in raw.splitlines()if line.strip()])
        self.assertEqual(result.count(b'    .locals'),raw.count(b'    .locals'))
        self.assertIn(b'invoke-static/range {p0 .. p4}',result)
    def test_changed_owner_and_repatch_are_refused(self):
        raw=SOURCE.read_bytes()
        for changed in [raw+b'\n',raw.replace(b':cond_26',b':cond_27',1),patch.transform(raw),None]:
            with self.assertRaises(ValueError):patch.transform(changed)
if __name__=='__main__':unittest.main()
