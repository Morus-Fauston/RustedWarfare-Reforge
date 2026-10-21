import hashlib
from pathlib import Path
import unittest

import asset_metadata_patch as patch


class AssetMetadataPatchTest(unittest.TestCase):
    def source(self):
        return (Path(__file__).resolve().parents[2] /
                "reference-bridge/build/s05-settings-decode-9f59b8cac44f43a4be215af093f612dc/smali/com/corrodinggames/rts/gameFramework/e/a.smali").read_bytes()

    def test_fixed_whole_owner_changes_only_one_call_and_returns_original_wide_value(self):
        before = self.source()
        after = patch.transform(before)
        self.assertEqual(after.count(patch.NEW), 1)
        self.assertEqual(after.replace(patch.NEW, patch.OLD), before)
        self.assertIn(b"move-result-wide v0\r\n\r\n    return-wide v0", after)
        self.assertEqual(hashlib.sha256(before).hexdigest(), patch.SOURCE_SHA256)

    def test_changed_input_and_repeat_are_rejected(self):
        before = self.source()
        for bad in (before+b"\n", before.replace(b".locals 2", b".locals 3", 1), patch.transform(before)):
            with self.assertRaises(ValueError):
                patch.transform(bad)


if __name__ == "__main__":
    unittest.main()
