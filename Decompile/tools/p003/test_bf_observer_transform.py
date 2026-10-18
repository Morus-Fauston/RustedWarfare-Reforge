"""BO-v0.2 public seam tests using authored synthetic CRLF fixtures only."""
import hashlib
import unittest

from bf_observer_transform import TransformError, transform_bf_observer

CRLF = "\r\n"
HELPER = "Lcom/rustedwarfare/reforge/reference/probe/BoundaryStepProbe;"
METHOD = ".method private strictfp b(F)V"
AC = "    invoke-virtual {p0}, Lcom/corrodinggames/rts/game/i;->ac()Z"
ENTER = "    invoke-static {p1}, " + HELPER + "->onBfEnter(F)V"
NORMAL = "    invoke-static {}, " + HELPER + "->onBfNormalExit()V"
PREFIX = CRLF.join([
    METHOD, "    .locals 14", "", "    .prologue",
    "    const/16 v13, 0x46", "", "    const v12, 0x3dcccccd    # 0.1f", "",
    "    const/4 v3, 0x0", "", "    .line 3170",
]) + CRLF
BODY = CRLF.join([
    AC, "    move-result v0", AC, "    move-result v1", AC,
    "    move-result v2", AC, "    move-result v3", "    :authored_exit",
    "    return-void", ".end method",
])
OTHER = CRLF.join([
    ".method private authoredOther()V", "    .locals 1", "    :authored_start",
    "    const/4 v0, 0x0", "    :authored_end", "    return-void",
    "    .catchall {:authored_start .. :authored_end} :authored_handler",
    "    :authored_handler", "    move-exception v0", "    throw v0", ".end method",
]) + CRLF
TARGET = PREFIX + BODY
SOURCE = OTHER + TARGET + CRLF + OTHER


def digest(text):
    """Test-side input identity, calculated before calling the transform."""
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


UNSET = object()


class TransformTest(unittest.TestCase):
    def transform(self, source=SOURCE, method=TARGET, source_sha=UNSET, method_sha=UNSET, helper=HELPER):
        return transform_bf_observer(source, source_sha if source_sha is not UNSET else digest(source),
                                     method_sha if method_sha is not UNSET else digest(method), helper)

    def rejects(self, code, source=SOURCE, method=TARGET, **kwargs):
        before = source.encode("utf-8") if isinstance(source, str) and not any(0xd800 <= ord(c) <= 0xdfff for c in source) else source
        with self.assertRaises(TransformError) as caught:
            self.transform(source, method, **kwargs)
        self.assertEqual(code, caught.exception.code)
        if isinstance(before, bytes):
            self.assertEqual(before, source.encode("utf-8"))

    def test_p01_exact_two_lines_with_four_ac_calls_and_other_methods(self):
        output, manifest = self.transform()
        expected_body = BODY.replace(AC, ENTER + CRLF + AC, 1).replace(
            "    return-void", NORMAL + CRLF + "    return-void", 1)
        expected = OTHER + PREFIX + expected_body + CRLF + OTHER
        self.assertEqual(expected.encode("utf-8"), output.encode("utf-8"))
        self.assertEqual(1, output.count(ENTER))
        self.assertEqual(1, output.count(NORMAL))
        self.assertEqual(SOURCE, output.replace(ENTER + CRLF, "", 1).replace(NORMAL + CRLF, "", 1))
        self.assertEqual(14, manifest["locals"])
        self.assertEqual(16, manifest["logicalRegisters"])
        self.assertFalse(manifest["tryTableChanged"])

    def test_p02_wrong_source_hash_rejects_without_output_or_mutation(self):
        self.rejects("source-hash-mismatch", source_sha="0" * 64)

    def test_p03_wrong_method_hash_has_priority_over_anchor(self):
        changed = TARGET.replace(".locals 14", ".locals 13")
        self.rejects("method-hash-mismatch", OTHER + changed, changed, method_sha="0" * 64)

    def test_p04_no_or_duplicate_method(self):
        self.rejects("method-not-unique", OTHER, "", method_sha="0" * 64)
        self.rejects("method-not-unique", SOURCE + TARGET, TARGET)

    def test_p04_missing_end_method(self):
        changed = TARGET.removesuffix(".end method")
        self.rejects("missing-window", OTHER + changed, changed)

    def test_p04_missing_end_must_not_consume_the_next_method(self):
        changed = TARGET.removesuffix(".end method")
        self.rejects("missing-window", changed + CRLF + OTHER, changed)

    def test_p04_locals_missing_duplicate_wrong(self):
        for locals_line in ["    .locals 13", "", "    .locals 14" + CRLF + "    .locals 14"]:
            with self.subTest(locals=locals_line):
                changed = TARGET.replace("    .locals 14", locals_line)
                self.rejects("locals-mismatch", OTHER + changed, changed)

    def test_p04_entry_requires_the_whole_prefix_not_any_of_four_ac(self):
        changed = TARGET.replace("    .line 3170", "    .line 1234", 1)
        self.assertEqual(4, changed.count(AC))
        self.rejects("entry-anchor-mismatch", OTHER + changed, changed)
        changed = TARGET.replace("    const/4 v3, 0x0", "    const/4 v3, 0x1", 1)
        self.rejects("entry-anchor-mismatch", OTHER + changed, changed)

    def test_p04_duplicate_complete_entry_is_rejected_before_method_hash(self):
        duplicate = PREFIX + BODY
        self.rejects("method-not-unique", SOURCE + duplicate, TARGET, method_sha="0" * 64)

    def test_p04_normal_missing_multiple_and_error_precedence(self):
        for normal in ["    throw v0", "    return-void" + CRLF + "    return-void"]:
            with self.subTest(normal=normal):
                changed = TARGET.replace("    return-void", normal)
                self.rejects("normal-anchor-mismatch", OTHER + changed, changed)
        changed = TARGET.replace("    .line 3170", "    .line 1234").replace("    return-void", "    throw v0")
        self.rejects("entry-anchor-mismatch", OTHER + changed, changed)

    def test_p05_repeat_and_partial_patch_precede_stale_source_hash(self):
        output, _ = self.transform()
        self.rejects("already-patched", output, TARGET, source_sha=digest(SOURCE))
        for call in [ENTER, NORMAL]:
            self.rejects("already-patched", SOURCE + call, TARGET, source_sha="0" * 64)

    def test_p06_bad_types_hashes_helper_and_input_priority(self):
        for bad in [None, b"bytes", 1, []]:
            with self.subTest(bad=repr(bad)):
                self.rejects("invalid-transform-input", helper=bad, source_sha="0" * 64)
                self.rejects("invalid-transform-input", source_sha=bad)
                self.rejects("invalid-transform-input", method_sha=bad)
        for bad in ["", "x" * 64, "a" * 63, "a" * 65]:
            with self.subTest(hash=bad):
                self.rejects("invalid-transform-input", source_sha=bad, helper="Lbad;")
        self.rejects("invalid-helper", helper="Lbad;", source_sha="0" * 64)
        for bad in [None, b"bytes", 1, []]:
            with self.subTest(source=repr(bad)), self.assertRaises(TransformError) as caught:
                transform_bf_observer(bad, "0" * 64, "0" * 64, HELPER)
            self.assertEqual("invalid-transform-input", caught.exception.code)

    def test_p06_unpaired_surrogate_precedes_helper_and_hash(self):
        for bad in ["\ud800", "\udfff", "prefix\ud800suffix"]:
            with self.subTest(source=repr(bad)), self.assertRaises(TransformError) as caught:
                transform_bf_observer(bad, "0" * 64, "0" * 64, "Lbad;")
            self.assertEqual("invalid-transform-input", caught.exception.code)

    def test_p07_preserves_exception_table_labels_and_non_target_bytes(self):
        changed = TARGET.replace("    :authored_exit", CRLF.join([
            "    :test_try_end", "    .catchall {:test_try_start .. :test_try_end} :authored_exit",
            "    :authored_exit",
        ])).replace("    move-result v0", "    move-result v0" + CRLF + "    :test_try_start", 1)
        source = OTHER + changed + CRLF + OTHER
        output, _ = self.transform(source, changed)
        restored = output.replace(ENTER + CRLF, "", 1).replace(NORMAL + CRLF, "", 1)
        self.assertEqual(source.encode(), restored.encode())
        self.assertEqual(2, output.count(OTHER))
        self.assertIn(".catchall {:test_try_start .. :test_try_end} :authored_exit", output)

    def test_lf_fixture_is_rejected_at_entry_after_prior_checks(self):
        source = SOURCE.replace(CRLF, "\n")
        method = TARGET.replace(CRLF, "\n")
        self.rejects("entry-anchor-mismatch", source, method)
        self.rejects("method-hash-mismatch", source, method, method_sha="0" * 64)
        self.rejects("method-not-unique", OTHER.replace(CRLF, "\n"), "", method_sha="0" * 64)

    def test_manifest_contains_before_after_hashes_and_install_is_forbidden(self):
        output, manifest = self.transform()
        after_method = output[len(OTHER):-len(CRLF + OTHER)]
        self.assertEqual(digest(SOURCE), manifest["sourceSha256Actual"])
        self.assertEqual(digest(TARGET), manifest["methodSha256Actual"])
        self.assertEqual(digest(output), manifest["sourceSha256After"])
        self.assertEqual(digest(after_method), manifest["methodSha256After"])
        self.assertFalse(manifest["installAllowed"])
        self.assertEqual(HELPER, manifest["helperDescriptor"])
        self.assertEqual({"enter": 1, "normal": 1}, manifest["insertCounts"])
        manifest["helperDescriptor"] = "caller-mutation"
        _, again = self.transform()
        self.assertEqual(HELPER, again["helperDescriptor"])


if __name__ == "__main__":
    unittest.main()
