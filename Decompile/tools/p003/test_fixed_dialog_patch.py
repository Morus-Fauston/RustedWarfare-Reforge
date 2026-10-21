import hashlib
from pathlib import Path
import unittest

import fixed_dialog_patch as patch


class FixedDialogPatchTest(unittest.TestCase):
    def source(self):
        return (Path(__file__).resolve().parents[2] / 'reference-bridge/build/s05-settings-decode-9f59b8cac44f43a4be215af093f612dc/smali/com/corrodinggames/rts/appFramework/MainMenuActivity.smali').read_bytes()

    def test_only_single_update_popup_return_value_is_retained(self):
        before = self.source(); after = patch.transform(before)
        self.assertEqual(after.count(patch.NEW), 1)
        self.assertEqual(after.replace(patch.NEW, patch.OLD), before)
        self.assertEqual(hashlib.sha256(before).hexdigest(), patch.SOURCE_SHA256)
        self.assertEqual(before.count(b'AlertDialog$Builder;->show()'), after.count(b'AlertDialog$Builder;->show()'))

    def test_wrong_owner_method_register_count_or_repeated_patch_are_rejected(self):
        before = self.source()
        for bad in (before+b'\n', before.replace(patch.SIGNATURE, b'.method public other()V'),
                before.replace(b'.locals 9', b'.locals 10', 1), patch.transform(before),
                before.replace(patch.OLD, b'', 1)):
            with self.assertRaises(ValueError): patch.transform(bad)


if __name__ == '__main__': unittest.main()
