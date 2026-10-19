import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from original_approved_layout import audit_approved_layout, write_approved_layout
from original_dependency_layout import LayoutError


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


class ApprovedLayoutTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='rw-approved-layout-')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source = self.root / 'source'
        self.derived = self.root / 'di' / 'derived'
        self.allowed = self.root / 'outputs'
        self.allowed.mkdir()
        self.output = self.allowed / 'new-layout'
        self.map_name = 'maps/p001-controlled.tmx'
        self.unit_name = 'units/weaponless_tank.ini'
        self.map_original = (
            b'<map width="2" height="1"><tileset firstgid="1" source="../tilesets/p001-ground.tsx"/>'
            b'<tileset firstgid="41" source="../tilesets/p001-rock.tsx"/>'
            b'<layer name="Ground" width="2" height="1"><data encoding="csv">1,62</data></layer></map>')
        self.unit_original = b'[core]\nname: owned\n[graphics]\nimage: ../../../../Rusted Warfare/assets/units/tanks/tank.png\n'
        self.originals = {
            self.map_name: self.map_original,
            self.unit_name: self.unit_original,
            'tilesets/p001-ground.tsx': b'<tileset><image source="../../owned/longgrass.png"/></tileset>',
            'tilesets/p001-rock.tsx': b'<tileset><image source="../../owned/mountain.png"/></tileset>',
            'dependencies/longgrass.png': b'owned-grass',
            'dependencies/mountain.png': b'owned-rock',
            'dependencies/tank.png': b'owned-unit',
        }
        for name, raw in self.originals.items():
            path = self.source / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(raw)
        self.carrier = {n: b for n, b in self.originals.items() if n.endswith(('.tmx', '.tsx', '.ini'))}
        self.carrier[self.map_name] = self.map_original.replace(
            b'<data encoding="csv">1,62</data>', b'<data encoding="base64">AQAAAD4AAAA=</data>').replace(
            b'</map>', b'\n <layer name="Items" width="2" height="1"><data encoding="base64">AAAAAAAAAAA=</data></layer>\n</map>')
        self.carrier[self.unit_name] += b'\n[attack]\ncanAttack: false\n'
        self.profile = {'schemaVersion': 1, 'map': self.map_name, 'unit': self.unit_name,
                        'copy': ['tilesets/p001-ground.tsx', 'tilesets/p001-rock.tsx'], 'allowedGids': [1, 62],
                        'files': [{'path': n, 'bytes': len(b), 'sha256': sha(b),
                                   'role': 'dependency' if n.startswith('dependencies/') else 'content'}
                                  for n, b in self.originals.items()]}
        self.identity_path = self.root / 'identity.json'
        self.identity_path.write_text(json.dumps(self.profile), encoding='utf-8')
        self.di_manifest = {'schemaVersion': 1, 'kind': 'original-only-derived-inputs',
                            'map': {'width': 2, 'height': 1, 'gids': [1, 62]}}
        self.di_manifest_path = self.root / 'di' / 'manifest.json'
        self.save_carrier()

    def save_carrier(self):
        for name, raw in self.carrier.items():
            path = self.derived / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(raw)
        self.di_manifest['outputs'] = [{'path': n, 'bytes': len(b), 'sha256': sha(b)} for n, b in self.carrier.items()]
        self.di_manifest_path.write_text(json.dumps(self.di_manifest), encoding='utf-8')

    def args(self):
        return (self.source, self.derived, self.identity_path, sha(self.identity_path.read_bytes()),
                self.di_manifest_path, sha(self.di_manifest_path.read_bytes()))

    def write(self, **kwargs):
        return write_approved_layout(*self.args(), kwargs.pop('output', self.output),
                                     allowed_output_root=self.allowed, **kwargs)

    def audit(self):
        manifest = self.output / 'manifest.json'
        return audit_approved_layout(*self.args(), self.output, manifest, sha(manifest.read_bytes()))

    def test_writes_only_seven_payloads_and_three_value_changes(self):
        before = {p: p.read_bytes() for p in self.source.rglob('*') if p.is_file()}
        result = self.write()
        self.assertTrue(result['hostLayoutReady'])
        for key in ('layoutReady', 'runtimeVerified', 'installAllowed'):
            self.assertFalse(result[key])
        self.assertEqual('host-payload-only', result['layoutScope'])
        self.assertEqual({
            'private/maps/p001-controlled.tmx', 'private/units/weaponless_tank.ini', 'private/units/tank.png',
            'assets/tilesets/p001-ground.tsx', 'assets/tilesets/p001-rock.tsx',
            'assets/tilesets/bitmaps/longgrass.png', 'assets/tilesets/bitmaps/mountain.png', 'manifest.json'},
            {p.relative_to(self.output).as_posix() for p in self.output.rglob('*') if p.is_file()})
        self.assertEqual(self.carrier[self.map_name].replace(b'../tilesets/', b''),
                         (self.output / 'private/maps/p001-controlled.tmx').read_bytes())
        self.assertEqual(b'[core]\nname: owned\n[graphics]\nimage: tank.png\n\n[attack]\ncanAttack: false\n',
                         (self.output / 'private/units/weaponless_tank.ini').read_bytes())
        self.assertEqual(b'owned-unit', (self.output / 'private/units/tank.png').read_bytes())
        self.assertEqual(self.originals['tilesets/p001-ground.tsx'],
                         (self.output / 'assets/tilesets/p001-ground.tsx').read_bytes())
        self.assertEqual(3, len(result['transforms']))
        self.assertEqual(['tilesets/p001-ground.tsx', 'tilesets/p001-rock.tsx',
                          'tilesets/bitmaps/longgrass.png', 'tilesets/bitmaps/mountain.png',
                          'units/tank.png'], [r['rawKey'] for r in result['references']])
        self.assertEqual(before, {p: p.read_bytes() for p in self.source.rglob('*') if p.is_file()})

    def test_auditor_checks_complete_payload_and_rejects_repinned_change(self):
        report = self.write()
        before = {p: p.read_bytes() for p in self.root.rglob('*') if p.is_file()}
        self.assertEqual(report, self.audit())
        self.assertEqual(before, {p: p.read_bytes() for p in self.root.rglob('*') if p.is_file()})
        for source_name in (self.map_name, self.unit_name):
            target_name = 'private/' + source_name
            restored = (self.output / target_name).read_bytes()
            for change in sorted((c for c in report['transforms'] if c['input'] == source_name),
                                 key=lambda c: c['outputStart'], reverse=True):
                restored = (restored[:change['outputStart']] + change['before'].encode()
                            + restored[change['outputEnd']:])
            self.assertEqual(self.carrier[source_name], restored)
        payload = self.output / 'private/units/weaponless_tank.ini'
        payload.write_bytes(payload.read_bytes().replace(b'name: owned', b'name: changed'))
        entry = next(e for e in report['outputs'] if e['path'] == 'private/units/weaponless_tank.ini')
        entry.update(bytes=payload.stat().st_size, sha256=sha(payload.read_bytes()))
        (self.output / 'manifest.json').write_text(json.dumps(report), encoding='utf-8')
        with self.assertRaisesRegex(LayoutError, '^layout-payload-mismatch$'):
            self.audit()

    def test_repinned_di_cannot_add_even_unrelated_comment_or_whitespace(self):
        self.carrier[self.map_name] = self.carrier[self.map_name].replace(b'</map>', b'<!-- extra -->\n</map>')
        self.save_carrier()
        with self.assertRaisesRegex(LayoutError, '^unapproved-content-change$'):
            self.write()
        self.assertFalse(self.output.exists())

    def test_missing_or_changed_dependency_never_creates_output(self):
        image = self.source / 'dependencies/tank.png'
        image.unlink()
        with self.assertRaisesRegex(LayoutError, '^missing-dependency$'):
            self.write()
        self.assertFalse(self.output.exists())
        image.write_bytes(b'wrong-image')
        with self.assertRaisesRegex(LayoutError, '^identity-mismatch$'):
            self.write()
        self.assertFalse(self.output.exists())

    def test_manifest_and_input_identity_pins_are_mandatory(self):
        args = list(self.args())
        for index in (3, 5):
            changed = args.copy()
            changed[index] = '0' * 64
            with self.subTest(index=index):
                with self.assertRaisesRegex(LayoutError, '^manifest-identity-mismatch$'):
                    write_approved_layout(*changed, self.output, allowed_output_root=self.allowed)
                self.assertFalse(self.output.exists())

    def test_repinned_carrier_cannot_change_cells_structure_or_unit_fields(self):
        initial = dict(self.carrier)
        cases = [
            (self.map_name, b'AQAAAD4AAAA=', b'AQAAAAEAAAA='),
            (self.map_name, b'AAAAAAAAAAA=', b'AQAAAAAAAAA='),
            (self.map_name, b'firstgid="41"', b'firstgid="42"'),
            (self.map_name, b'</map>', b'<objectgroup name="extra"/></map>'),
            (self.map_name, b'../tilesets/p001-ground.tsx', b'p001-ground.tsx'),
            (self.unit_name, b'name: owned', b'name: modified'),
            (self.unit_name, b'[graphics]', b'[graphics]\nimage: extra.png'),
            (self.unit_name, b'canAttack: false', b'canAttack: true'),
        ]
        for name, before, after in cases:
            with self.subTest(name=name, before=before):
                self.carrier = dict(initial)
                self.carrier[name] = self.carrier[name].replace(before, after)
                self.save_carrier()
                with self.assertRaises(LayoutError):
                    self.write()
                self.assertFalse(self.output.exists())

    def test_forbidden_or_overlapping_roots_and_existing_output_preserve_files(self):
        for target in (self.source, self.derived, self.allowed, self.root):
            with self.subTest(target=target):
                with self.assertRaisesRegex(LayoutError, '^unsafe-output$'):
                    self.write(output=target)
        banned = self.allowed / 'banned'
        banned.mkdir()
        with self.assertRaisesRegex(LayoutError, '^unsafe-output$'):
            self.write(output=banned / 'new', forbidden_roots=[banned])
        self.output.mkdir()
        marker = self.output / 'keep'
        marker.write_bytes(b'keep')
        with self.assertRaisesRegex(LayoutError, '^output-exists$'):
            self.write()
        self.assertEqual(b'keep', marker.read_bytes())
        self.assertEqual([marker], list(self.output.iterdir()))

    def test_raw_ambiguous_output_paths_are_rejected(self):
        for value in (str(self.allowed) + '/./new', str(self.allowed) + '/../new',
                      str(self.allowed) + '/new. ', str(self.allowed) + '//new'):
            with self.subTest(value=value):
                with self.assertRaisesRegex(LayoutError, '^unsafe-output$'):
                    self.write(output=value)
        self.assertEqual([], list(self.allowed.iterdir()))

    def test_real_symlink_input_output_and_audited_payload_are_rejected(self):
        link = self.allowed / 'redirect'
        link.symlink_to(self.source, target_is_directory=True)
        with self.assertRaisesRegex(LayoutError, '^redirected-input$'):
            self.write(output=link / 'new')
        source_link = self.root / 'source-link'
        source_link.symlink_to(self.source, target_is_directory=True)
        args = list(self.args())
        args[0] = source_link
        with self.assertRaisesRegex(LayoutError, '^redirected-input$'):
            write_approved_layout(*args, self.output, allowed_output_root=self.allowed)
        self.write()
        extra = self.output / 'link'
        extra.symlink_to(self.source, target_is_directory=True)
        with self.assertRaisesRegex(LayoutError, '^redirected-input$'):
            self.audit()

    def test_auditor_rejects_missing_extra_or_changed_payload_and_manifest_pin(self):
        self.write()
        manifest = self.output / 'manifest.json'
        with self.assertRaisesRegex(LayoutError, '^manifest-identity-mismatch$'):
            audit_approved_layout(*self.args(), self.output, manifest, '0' * 64)
        extra = self.output / 'extra'
        extra.mkdir()
        with self.assertRaisesRegex(LayoutError, '^layout-payload-mismatch$'):
            self.audit()
        extra.rmdir()
        extra.write_bytes(b'extra')
        with self.assertRaisesRegex(LayoutError, '^layout-payload-mismatch$'):
            self.audit()
        extra.unlink()
        image = self.output / 'private/units/tank.png'
        image.write_bytes(b'changed')
        with self.assertRaisesRegex(LayoutError, '^layout-payload-mismatch$'):
            self.audit()
        image.unlink()
        with self.assertRaisesRegex(LayoutError, '^layout-payload-mismatch$'):
            self.audit()

    def test_cli_produces_manifest_hash_and_rejects_existing_output(self):
        args = self.args()
        command = [sys.executable, '-B', str(Path(__file__).with_name('original_approved_layout.py')),
                   '--source-root', str(args[0]), '--derived-root', str(args[1]),
                   '--identity', str(args[2]), '--identity-sha256', args[3],
                   '--di-manifest', str(args[4]), '--di-manifest-sha256', args[5],
                   '--output', str(self.output), '--allowed-output-root', str(self.allowed)]
        result = subprocess.run(command, capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)
        response = json.loads(result.stdout)
        self.assertEqual(sha((self.output / 'manifest.json').read_bytes()), response['sha256'])
        self.assertFalse(response['installAllowed'])
        self.assertEqual(self.audit()['outputs'], json.loads((self.output / 'manifest.json').read_bytes())['outputs'])
        again = subprocess.run(command, capture_output=True, text=True)
        self.assertEqual(2, again.returncode)
        self.assertEqual('output-exists', again.stderr.strip())


if __name__ == '__main__':
    unittest.main()
