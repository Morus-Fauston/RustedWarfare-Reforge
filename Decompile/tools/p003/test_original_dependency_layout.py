import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from original_dependency_layout import LayoutError, audit_layout


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


class LayoutAuditTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='rw-layout-test-')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source = self.root / 'source'
        self.derived = self.root / 'derived'
        original_map = (b'<map width="2" height="1"><tileset source="../tilesets/a.tsx"/>'
                        b'<tileset source="../tilesets/b.tsx"/><layer name="Ground" width="2" height="1">'
                        b'<data encoding="csv">1,62</data></layer></map>')
        self.originals = {
            'maps/owned.tmx': original_map,
            'tilesets/a.tsx': b'<tileset><image source="../images/grass.png"/></tileset>',
            'tilesets/b.tsx': b'<tileset><image source="../images/rock.png"/></tileset>',
            'units/owned.ini': b'[core]\nname: owned\n[graphics]\nimage: ../images/unit.png\n',
            'images/grass.png': b'owned-grass', 'images/rock.png': b'owned-rock',
            'images/unit.png': b'owned-unit',
        }
        for name, raw in self.originals.items():
            path = self.source / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(raw)
        self.outputs = {name: raw for name, raw in self.originals.items() if name.endswith(('.tmx', '.tsx', '.ini'))}
        self.outputs['maps/owned.tmx'] = original_map.replace(
            b'<data encoding="csv">1,62</data>', b'<data encoding="base64">AQAAAD4AAAA=</data>')
        self.outputs['maps/owned.tmx'] = self.outputs['maps/owned.tmx'].replace(
            b'</map>', b'<layer name="Items" width="2" height="1"><data encoding="base64">AAAAAAAAAAA=</data></layer></map>')
        self.outputs['units/owned.ini'] += b'\n[attack]\ncanAttack: false\n'
        self.identity = {'schemaVersion': 1, 'map': 'maps/owned.tmx', 'unit': 'units/owned.ini',
                         'copy': ['tilesets/a.tsx', 'tilesets/b.tsx'],
                         'files': [{'path': n, 'sha256': sha(b), 'bytes': len(b),
                                    'role': 'dependency' if n.startswith('images/') else 'content'}
                                   for n, b in self.originals.items()]}
        self.manifest = {'schemaVersion': 1, 'kind': 'original-only-derived-inputs',
                         'map': {'width': 2, 'height': 1, 'cells': 2, 'gids': [1, 62]}}
        self.identity_path = self.root / 'identity.json'
        self.identity_path.write_text(json.dumps(self.identity), encoding='utf-8')
        self.write_outputs()

    def write_outputs(self):
        for name, raw in self.outputs.items():
            path = self.derived / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(raw)
        self.manifest['outputs'] = [{'path': n, 'sha256': sha(b), 'bytes': len(b)} for n, b in self.outputs.items()]
        self.manifest_path = self.root / 'manifest.json'
        self.manifest_path.write_text(json.dumps(self.manifest), encoding='utf-8')

    def audit(self):
        return audit_layout(self.source, self.derived, self.identity_path, sha(self.identity_path.read_bytes()),
                            self.manifest_path, sha(self.manifest_path.read_bytes()))

    def test_reports_raw_loader_keys_and_never_promises_ready(self):
        before = {p: p.read_bytes() for p in self.root.rglob('*') if p.is_file()}
        result = self.audit()
        self.assertFalse(result['layoutReady'])
        self.assertFalse(result['runtimeVerified'])
        self.assertFalse(result['installAllowed'])
        self.assertEqual([1, 62], [c['derivedGid'] for c in result['cells']])
        self.assertEqual(['tilesets/../tilesets/a.tsx', 'tilesets/../tilesets/b.tsx'],
                         [r['rawKey'] for r in result['references'] if r['kind'] == 'tsx'])
        self.assertEqual(3, len([r for r in result['references'] if r['guardCode'] == 'invalid-path']))
        self.assertEqual(['tilesets/bitmaps/grass.png', 'tilesets/bitmaps/rock.png'],
                         [r['rawKey'] for r in result['references'] if r['kind'] == 'terrain-image'])
        self.assertEqual(before, {p: p.read_bytes() for p in self.root.rglob('*') if p.is_file()})

    def test_rejects_unapproved_map_node_even_when_manifest_is_repinned(self):
        self.outputs['maps/owned.tmx'] = self.outputs['maps/owned.tmx'].replace(
            b'</map>', b'<objectgroup name="unauthorized"/></map>')
        self.write_outputs()
        with self.assertRaisesRegex(LayoutError, '^unapproved-content-change$'):
            self.audit()

    def test_missing_dependency_is_not_an_empty_or_ready_layout(self):
        (self.source / 'images/unit.png').unlink()
        with self.assertRaisesRegex(LayoutError, '^missing-dependency$'):
            self.audit()

    def test_wrong_input_identity_and_manifest_identity_fail(self):
        (self.source / 'images/grass.png').write_bytes(b'changed')
        with self.assertRaisesRegex(LayoutError, '^identity-mismatch$'):
            self.audit()
        with self.assertRaisesRegex(LayoutError, '^manifest-identity-mismatch$'):
            audit_layout(self.source, self.derived, self.identity_path, '0' * 64,
                         self.manifest_path, sha(self.manifest_path.read_bytes()))

    def test_wrong_gid_with_repinned_carrier_is_rejected(self):
        self.outputs['maps/owned.tmx'] = self.outputs['maps/owned.tmx'].replace(
            b'AQAAAD4AAAA=', b'AQAAAAEAAAA=')
        self.write_outputs()
        with self.assertRaisesRegex(LayoutError, '^carrier-map-mismatch$'):
            self.audit()

    def test_cli_is_readonly_and_reports_diagnostic_not_ready(self):
        command = [sys.executable, '-B', str(Path(__file__).with_name('original_dependency_layout.py')),
                   '--source-root', str(self.source), '--derived-root', str(self.derived),
                   '--identity', str(self.identity_path), '--identity-sha256', sha(self.identity_path.read_bytes()),
                   '--manifest', str(self.manifest_path), '--manifest-sha256', sha(self.manifest_path.read_bytes())]
        before = {p: p.read_bytes() for p in self.root.rglob('*') if p.is_file()}
        result = subprocess.run(command, capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertFalse(json.loads(result.stdout)['layoutReady'])
        self.assertEqual(before, {p: p.read_bytes() for p in self.root.rglob('*') if p.is_file()})


if __name__ == '__main__':
    unittest.main()
