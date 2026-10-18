import hashlib
import unittest

from gboot_transform import PatchError, patch_class


# Entirely self-authored public fixtures; no original game method bodies.
OLD = '.method public static value()I\n    .locals 1\n    const/4 v0, 0x1\n    return v0\n.end method'
NEW = '.method public static value()I\n    .locals 1\n    const/4 v0, 0x2\n    return v0\n.end method'
OTHER = '.method public static other()V\n    .locals 0\n    return-void\n.end method'
PREFIX = '.class public Lfixture/Example;\n.super Ljava/lang/Object;\n# 保留调试文本\n'


def sha(data):
    return hashlib.sha256(data).hexdigest()


def fixture(newline='\n', bom=b''):
    return bom + (PREFIX + OLD + '\n\n' + OTHER + '\n').replace('\n', newline).encode('utf-8')


def plan():
    return [{'descriptor': 'Lfixture/Example;->value()I',
             'beforeSha256': sha((OLD + '\n').encode()), 'afterSmali': NEW}]


class PatchPublicTest(unittest.TestCase):
    def test_p01_exact_replacement_leaves_other_bytes(self):
        source = fixture()
        result, report = patch_class(source, sha(source), plan())
        expected = (PREFIX + NEW + '\n\n' + OTHER + '\n').encode()
        self.assertEqual(expected, result)
        self.assertEqual(1, len(report['methods']))
        self.assertTrue(report['nonTargetBytesUnchanged'])
        self.assertEqual(source, fixture())

    def reject(self, code, source, class_sha, patches):
        with self.assertRaises(PatchError) as caught:
            patch_class(source, class_sha, patches)
        self.assertEqual(code, caught.exception.code)

    def test_p02_input_priority_and_class_identity(self):
        source = fixture()
        self.reject('invalid-input', source, '0' * 64, None)
        self.reject('invalid-input', b'\xff', sha(b'\xff'), plan())
        self.reject('class-hash-mismatch', source, '0' * 64, plan())

    def test_p03_missing_and_duplicate_method(self):
        source = (PREFIX + OTHER + '\n').encode()
        self.reject('method-not-unique', source, sha(source), plan())
        source = (PREFIX + OLD + '\n' + OLD + '\n').encode()
        self.reject('method-not-unique', source, sha(source), plan())

    def test_p04_method_identity_and_error_priority(self):
        source = fixture()
        patch = plan()
        patch[0]['beforeSha256'] = '0' * 64
        patch[0]['afterSmali'] = 'broken'
        self.reject('method-hash-mismatch', source, sha(source), patch)

    def test_p06_second_patch_failure_has_no_output(self):
        source = fixture()
        patches = plan() + [{'descriptor': 'Lfixture/Example;->other()V',
                             'beforeSha256': '0' * 64, 'afterSmali': OTHER}]
        self.reject('method-hash-mismatch', source, sha(source), patches)
        self.assertEqual(fixture(), source)

    def test_p07_repeat_is_not_implicitly_idempotent(self):
        source = fixture()
        result, _ = patch_class(source, sha(source), plan())
        self.reject('class-hash-mismatch', result, sha(source), plan())

    def test_p05_invalid_replacement_structure_and_descriptors(self):
        invalid = [
            NEW.replace('value()I', 'elsewhere()I'),
            NEW.replace('.end method', ''),
            NEW.replace('const/4 v0, 0x2', '...'),
            NEW.replace('const/4 v0, 0x2', 'const/4 vX, 0x2'),
            NEW.replace('const/4 v0, 0x2', 'move-result-object v0'),
            NEW.replace('const/4 v0, 0x2', 'invoke-static {}, Lfixture/Helper;->get()Ljava/lang/String;\n    move-result v0'),
            NEW.replace('return v0', 'return-object v0'),
            NEW.replace('.locals 1', '.locals 0'),
            NEW + '\n' + OTHER,
        ]
        source = fixture()
        for replacement in invalid:
            with self.subTest(replacement=replacement):
                patch = plan()
                patch[0]['afterSmali'] = replacement
                self.reject('invalid-replacement', source, sha(source), patch)

    def test_p08_crlf_bom_unicode_debug_and_final_newline(self):
        source = fixture('\r\n', b'\xef\xbb\xbf')
        result, _ = patch_class(source, sha(source), plan())
        expected = b'\xef\xbb\xbf' + (PREFIX + NEW + '\n\n' + OTHER + '\n').replace('\n', '\r\n').encode()
        self.assertEqual(expected, result)
        source = (PREFIX + OTHER + '\n\n' + OLD).encode()
        expected = (PREFIX + OTHER + '\n\n' + NEW).encode()
        result, _ = patch_class(source, sha(source), plan())
        self.assertEqual(expected, result)

    def test_full_descriptor_owner_must_match(self):
        source = fixture()
        patch = plan()
        patch[0]['descriptor'] = 'Lfixture/OtherClass;->value()I'
        self.reject('method-not-unique', source, sha(source), patch)

    def test_two_methods_are_both_prevalidated_and_replaced(self):
        source = fixture()
        new_other = OTHER.replace('return-void', 'nop\n    return-void')
        patches = plan() + [{'descriptor': 'Lfixture/Example;->other()V',
                             'beforeSha256': sha((OTHER + '\n').encode()),
                             'afterSmali': new_other}]
        result, report = patch_class(source, sha(source), patches)
        expected = (PREFIX + NEW + '\n\n' + new_other + '\n').encode()
        self.assertEqual(expected, result)
        self.assertEqual(2, len(report['methods']))
        self.assertTrue(report['nonTargetBytesUnchanged'])

    def test_input_plan_duplicates_and_invalid_types(self):
        source = fixture()
        self.reject('invalid-input', source, sha(source), plan() + plan())
        self.reject('invalid-input', source, sha(source), [None])
        self.reject('invalid-input', 'text', '0' * 64, plan())
        self.reject('invalid-input', source, 'bad', plan())
        patch = plan()
        patch[0]['afterSmali'] = '\ud800'
        self.reject('invalid-input', source, sha(source), patch)

    def test_nonvoid_invoke_may_discard_result(self):
        source = fixture()
        replacement = NEW.replace('const/4 v0, 0x2',
            'invoke-static {}, Lfixture/Helper;->get()Ljava/lang/String;\n    const/4 v0, 0x2')
        patches = plan()
        patches[0]['afterSmali'] = replacement
        result, _ = patch_class(source, sha(source), patches)
        self.assertEqual((PREFIX + replacement + '\n\n' + OTHER + '\n').encode(), result)

    def test_private_label_is_not_a_symbolic_parameter_register(self):
        source = fixture()
        replacement = NEW.replace('const/4 v0, 0x2',
            'const/4 v0, 0x2\n    if-eqz v0, :private_read\n    :private_read')
        patches = plan()
        patches[0]['afterSmali'] = replacement
        result, _ = patch_class(source, sha(source), patches)
        self.assertEqual((PREFIX + replacement + '\n\n' + OTHER + '\n').encode(), result)

    def test_quoted_log_words_are_not_symbolic_registers(self):
        source = fixture()
        replacement = NEW.replace('const/4 v0, 0x2',
            'const-string v0, "parent path vX ..."\n    const/4 v0, 0x2')
        patches = plan()
        patches[0]['afterSmali'] = replacement
        result, _ = patch_class(source, sha(source), patches)
        self.assertEqual((PREFIX + replacement + '\n\n' + OTHER + '\n').encode(), result)

    def test_move_result_still_requires_adjacent_correct_nonvoid_invoke(self):
        source = fixture()
        cases = [
            'invoke-static {}, Lfixture/Helper;->get()Ljava/lang/String;\n    nop\n    move-result-object v0',
            'invoke-static {}, Lfixture/Helper;->get()Ljava/lang/String;\n    move-result v0',
            'invoke-static {}, Lfixture/Helper;->nothing()V\n    move-result-object v0',
            'invoke-static {}, Lfixture/Helper;->number()I\n    move-result-object v0',
        ]
        for instructions in cases:
            with self.subTest(instructions=instructions):
                patches = plan()
                patches[0]['afterSmali'] = NEW.replace('const/4 v0, 0x2', instructions)
                self.reject('invalid-replacement', source, sha(source), patches)


if __name__ == '__main__':
    unittest.main()
