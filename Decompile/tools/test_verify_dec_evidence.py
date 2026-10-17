"""合成夹具验证：正常迁移通过；正文、底稿、链接、越界异常必须拒绝。"""
from pathlib import Path
import hashlib
import json
import shutil
import uuid
import unittest
import verify_dec_evidence as verifier


def hash_bytes(data):
    return hashlib.sha256(data).hexdigest()


class VerificationTest(unittest.TestCase):
    def setUp(self):
        workspace = Path(__file__).resolve().parents[2]
        parent = workspace / '.scratch/研究缓存/dec-verifier-fixtures'
        parent.mkdir(parents=True, exist_ok=True)
        self.root = (parent / ('run-' + uuid.uuid4().hex)).resolve()
        self.root.mkdir()
        self.addCleanup(self.cleanup_fixture, parent.resolve(), self.root)
        self.put('evidence/artifact.txt', b'original bytecode\n')
        self.put('evidence/manifest.json', json.dumps({'artifacts': [{'path': 'artifact.txt', 'sha256': hash_bytes(b'original bytecode\n')}]}).encode())
        self.put('backup.bin', b'# original report\n\nbody\n')
        self.current = b'# original report\n\nbody\n'
        self.put('Docs/Research-DEC/DEC-P001-001-输出报告.md', self.current)
        self.mapping = {'files': [{'old': 'evidence/review.md', 'new': 'Docs/Research-DEC/DEC-P001-001-输出报告.md',
                                 'originalBytes': 'backup.bin', 'originalSha256': hash_bytes(self.current),
                                 'migratedSha256': hash_bytes(self.current)}]}
        self.put('mapping.json', json.dumps(self.mapping).encode())
        self.put('repairs.json', b'{"reports": []}')

    @staticmethod
    def cleanup_fixture(parent, path):
        # 删除前确认绝对目标仅为本次创建的唯一夹具，不触碰归档或其他目录。
        if path.parent != parent or not path.name.startswith('run-'):
            raise ValueError('Unexpected fixture cleanup target')
        shutil.rmtree(path)

    def put(self, name, data):
        p = self.root / name
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_bytes(data)

    def result(self):
        return verifier.verify(self.root, 'mapping.json', 'repairs.json')

    def test_original_migration_passes(self):
        self.assertTrue(self.result()['passed'])

    def test_tampered_artifact_rejected(self):
        self.put('evidence/artifact.txt', b'changed\n')
        self.assertFalse(self.result()['passed'])

    def test_tampered_body_rejected(self):
        self.put('Docs/Research-DEC/DEC-P001-001-输出报告.md', b'new summary\n')
        self.assertFalse(self.result()['passed'])

    def test_approved_block_keeps_original_body(self):
        first, rest = self.current.split(b'\n\n', 1)
        new = first + b'\n\n' + verifier.START.encode() + b'\nmaintenance\n' + verifier.END.encode() + b'\n\n' + rest
        self.put('Docs/Research-DEC/DEC-P001-001-输出报告.md', new)
        self.put('repairs.json', json.dumps({'reports': [{'path': 'Docs/Research-DEC/DEC-P001-001-输出报告.md', 'sha256': hash_bytes(new)}]}).encode())
        self.assertTrue(self.result()['passed'])
        # 即使新哈希已登记，删除技术正文也必须失败。
        bad = new.replace(b'body', b'deleted')
        self.put('Docs/Research-DEC/DEC-P001-001-输出报告.md', bad)
        self.put('repairs.json', json.dumps({'reports': [{'path': 'Docs/Research-DEC/DEC-P001-001-输出报告.md', 'sha256': hash_bytes(bad)}]}).encode())
        self.assertFalse(self.result()['passed'])

    def test_broken_link_rejected(self):
        self.put('Docs/Research-DEC/README.md', b'[missing](missing.txt)\n')
        self.assertFalse(self.result()['passed'])

    def test_line_anchor_rejected(self):
        self.put('Docs/Research-DEC/README.md', b'[bad](../../evidence/artifact.txt#L1-L99)\n')
        self.assertFalse(self.result()['passed'])

    def test_empty_inventory_rejected(self):
        self.put('mapping.json', b'{"files": []}')
        self.assertFalse(self.result()['passed'])

    def test_old_report_path_reappeared_rejected(self):
        self.put('evidence/review.md', self.current)
        self.assertFalse(self.result()['passed'])

    def test_verification_does_not_write_files(self):
        before = {str(p.relative_to(self.root)): p.read_bytes() for p in self.root.rglob('*') if p.is_file()}
        self.assertTrue(self.result()['passed'])
        after = {str(p.relative_to(self.root)): p.read_bytes() for p in self.root.rglob('*') if p.is_file()}
        self.assertEqual(before, after)

    def test_outside_workspace_rejected(self):
        with self.assertRaises(ValueError):
            verifier.inside(self.root, '../outside')


if __name__ == '__main__':
    unittest.main()
