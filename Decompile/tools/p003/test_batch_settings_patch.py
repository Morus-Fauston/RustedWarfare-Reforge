from pathlib import Path
import unittest
import batch_settings_patch as subject


class SettingsPatchTest(unittest.TestCase):
    def test_only_three_reviewed_instructions_change(self):
        source = Path("Decompile/reference-bridge/build/s05-settings-decode-9f59b8cac44f43a4be215af093f612dc/smali_classes2/com/rustedwarfare/reforge/reference/boot/GbootBootstrap.smali").read_bytes()
        output = subject.transform(source)
        self.assertNotEqual(source, output)
        newline = b"\r\n" if b"\r\n" in source else b"\n"
        restored = output
        for old, method, register in subject.CHANGES:
            new = (b"    invoke-static {}, Lcom/rustedwarfare/reforge/reference/boot/BatchSettings;->"
                   + method + b"()Ljava/lang/String;" + newline + b"    move-result-object " + register)
            self.assertEqual(1, restored.count(new))
            restored = restored.replace(new, old)
        self.assertEqual(source, restored)
        self.assertIn(b"First settings open requires missing XML and backup", output)
        for bad in (output, source+b"\n", source.replace(b'"rts_settings"', b'"other_settings"', 1)):
            with self.assertRaisesRegex(ValueError, "fixed Gboot smali identity"):
                subject.transform(bad)


if __name__ == "__main__": unittest.main()
