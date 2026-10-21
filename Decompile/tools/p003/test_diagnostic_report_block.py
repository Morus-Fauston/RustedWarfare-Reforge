"""Real byte fixtures at the narrow transform/evidence boundary; no device."""
import base64
import hashlib
import struct
import unittest
from pathlib import Path
import diagnostic_report_block as subject


class DiagnosticReportTest(unittest.TestCase):
    def test_fixed_entry_rejects_before_thread_and_keeps_other_bytes(self):
        source = Path("Decompile/reference-bridge/build/r10-io98-c7a87a9bf7f348b0ac41575887ea3dd5/decoded/smali/com/corrodinggames/rts/gameFramework/j/m.smali").read_bytes()
        signature = b".method public static a(Ljava/lang/String;Ljava/lang/String;)V"
        start = source.index(signature)
        end = source.index(b".end method", start) + len(b".end method")
        actual = subject.transform(source)
        after = actual.index(b".end method", start) + len(b".end method")
        self.assertEqual(source[:start], actual[:start])
        self.assertEqual(source[end:], actual[after:])
        self.assertIn(b"DiagnosticReportBlock;->reject", actual[start:after])
        self.assertIn(b"throw v0", actual[start:after])
        self.assertNotIn(b"new-instance", actual[start:after])
        with self.assertRaises(ValueError):
            subject.transform(actual)
        with self.assertRaises(ValueError):
            subject.transform(source.replace(b".locals 2", b".locals 3", 1))

    def test_complete_original_diagnostic_code_units(self):
        raw = struct.pack(">i", -1) + struct.pack(">i", 3) + b"\x00A\xd8\x00\x00\n"
        sha = hashlib.sha256(raw).hexdigest()
        messages = [f"RWR_NA_BEGIN {len(raw)} {sha}",
                    "RWR_NA_CHUNK 0 " + base64.b64encode(raw).decode(),
                    f"RWR_NA_END 1 {sha}"]
        result = subject.parse_messages(messages)
        self.assertIsNone(result["category"])
        self.assertEqual("A\ud800\n", result["detail"])
        self.assertEqual(sha, result["sha256"])
        for bad in (
            messages[:-1],
            messages[:1] + messages[2:],
            messages[:2] + messages[1:],
            [messages[0], messages[1].replace("CHUNK 0", "CHUNK 1"), messages[2]],
            [messages[0].replace(str(len(raw)), str(len(raw) + 1), 1)] + messages[1:],
            [messages[0], messages[1].replace("AAAA", "BAAA", 1), messages[2]],
            [messages[0], messages[1], messages[2][:-1] + ("1" if sha[-1] != "1" else "2")],
        ):
            with self.subTest(bad=bad), self.assertRaises(ValueError):
                subject.parse_messages(bad)


if __name__ == "__main__":
    unittest.main()
