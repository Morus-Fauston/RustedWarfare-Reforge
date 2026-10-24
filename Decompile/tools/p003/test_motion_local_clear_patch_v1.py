import hashlib
from pathlib import Path
import unittest
import motion_detail_patch_v2 as old
import motion_local_clear_patch_v1 as patch

ROOT=Path(__file__).resolve().parents[3]
SOURCE=ROOT/'Decompile/reference-bridge/build/s05-settings-decode-9f59b8cac44f43a4be215af093f612dc/smali/com/corrodinggames/rts/game/units/bp.smali'

class MotionLocalClearPatchTest(unittest.TestCase):
    def test_adds_only_the_p_specific_pair_preserving_old_five_hooks_and_all_original_instructions(self):
        raw=SOURCE.read_bytes();result=patch.transform(raw)
        self.assertEqual(hashlib.sha256(raw).hexdigest(),patch.SOURCE_SHA256)
        self.assertEqual(result.count(patch.OWNER),7)
        start=result.index(old.PROPULSION);end=result.index(b'.end method',start)
        body=result[start:end]
        before=body.index(b'->beforeLocalPathClear(Ljava/lang/Object;)V')
        clear=body.index(b'->cV()V')
        after=body.index(b'->afterLocalPathClear(Ljava/lang/Object;)V')
        self.assertLess(before,clear);self.assertLess(clear,after)
        self.assertNotIn(b'->beforeNormalClear',body)
        filtered=[line for line in result.splitlines() if line.strip() and patch.OWNER not in line]
        self.assertEqual(filtered,[line for line in raw.splitlines() if line.strip()])
        self.assertEqual(result.count(b'    .locals'),raw.count(b'    .locals'))
        for name in [b'beforeNormalClear',b'afterNormalClear',b'collisionBefore',b'collisionAfter',b'propulsion']:
            self.assertEqual(result.count(b'->'+name+b'('),1)
    def test_changed_owner_repatch_and_old_patched_input_are_refused(self):
        raw=SOURCE.read_bytes()
        for changed in [raw+b'\n',None,old.transform(raw),patch.transform(raw)]:
            with self.assertRaises(ValueError):patch.transform(changed)

if __name__=='__main__':unittest.main()
