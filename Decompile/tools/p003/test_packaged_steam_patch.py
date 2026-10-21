from pathlib import Path
import unittest
import packaged_steam_patch as patch


class PackagedSteamPatchTest(unittest.TestCase):
    def source(self):
        return (Path(__file__).resolve().parents[2] /
                "reference-bridge/build/s05-settings-decode-9f59b8cac44f43a4be215af093f612dc/smali/com/corrodinggames/rts/gameFramework/i/b.smali").read_bytes()

    def test_branch_is_the_only_change_and_preserves_existing_private_segment(self):
        before = self.source()
        after = patch.transform(before)
        self.assertEqual(after.replace(patch.BRANCH, patch.ANCHOR), before)
        self.assertEqual(after.count(b"->skipSteamProbe(Ljava/lang/String;)Z"), 1)
        self.assertIn(b"move-result v0\r\n    if-nez v0, :cond_0\r\n    new-instance", after)
        for original in (b"    .locals 9", b"    iput-boolean v0, p0, Lcom/corrodinggames/rts/gameFramework/i/b;->p:Z",
                         b"->privateExists(Ljava/lang/String;)Z", b"->privateIsDirectory(Ljava/lang/String;)Z"):
            self.assertIn(original, after)

    def test_wrong_input_and_repeated_application_are_rejected(self):
        before = self.source()
        for bad in (before+b"\n", before.replace(b":cond_5", b":cond_9", 1), patch.transform(before)):
            with self.assertRaises(ValueError):
                patch.transform(bad)


if __name__ == "__main__":
    unittest.main()
