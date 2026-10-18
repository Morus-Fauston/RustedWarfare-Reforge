"""Self-authored toy fixtures only; all filesystem fixtures are retained, never deleted."""
import unittest
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
from original_input_transform import DeriveError, derive_map, derive_unit, write_derived_inputs

TOY = (b'<?xml version="1.0" encoding="UTF-8"?>\n'
       b'<map width="2" height="1"><!--owned--><layer name="Ground" width="2" height="1">'
       b'<data encoding="csv">1,62</data></layer></map>\n')
EXPECTED = (b'<?xml version="1.0" encoding="UTF-8"?>\n'
            b'<map width="2" height="1"><!--owned--><layer name="Ground" width="2" height="1">'
            b'<data encoding="base64">AQAAAD4AAAA=</data></layer>'
            b'\n <layer name="Items" width="2" height="1"><data encoding="base64">AAAAAAAAAAA=</data></layer>\n'
            b'</map>\n')


class BytesTransformTest(unittest.TestCase):
    def test_t01_literal_little_endian_ground_and_zero_items(self):
        actual, info = derive_map(TOY, frozenset({1, 62}))
        self.assertEqual(EXPECTED, actual)
        self.assertEqual(8, info['groundRawBytes'])
        self.assertEqual(2, info['cells'])
        self.assertEqual([1, 62], info['gids'])

    def test_t02_bom_crlf_comments_and_attribute_order_unchanged(self):
        source = b'\xef\xbb\xbf' + TOY.replace(b'\n', b'\r\n')
        expected = b'\xef\xbb\xbf' + EXPECTED.replace(b'\n', b'\r\n')
        actual, _ = derive_map(source, frozenset({1, 62}))
        self.assertEqual(expected, actual)
        self.assertEqual(source, b'\xef\xbb\xbf' + TOY.replace(b'\n', b'\r\n'))

    def test_t03_ini_complete_original_prefix_and_literal_append(self):
        for newline in (b'\n', b'\r\n'):
            for final in (b'', newline):
                source = newline.join((b'# [attack] is just an owned comment', b'[core]', b'name: toy')) + final
                expected = source + (b'' if final else newline) + newline + b'[attack]' + newline + b'canAttack: false' + newline
                actual, report = derive_unit(source)
                self.assertEqual(expected, actual)
                self.assertTrue(actual.startswith(source))
                self.assertEqual({'section': 'attack', 'field': 'canAttack', 'value': 'false'}, report['addedField'])

    def assert_rejected(self, code, operation):
        with self.assertRaises(DeriveError) as caught:
            operation()
        self.assertEqual(code, caught.exception.code)

    def test_t04_map_exact_error_priority_and_repeated_input_rejected(self):
        cases = [
            (b'', 'invalid-input'), (b'\xff', 'invalid-input'),
            (b'<bad', 'unsupported-input'),
            (TOY.replace(b'width="2"', b'width="0"', 1), 'invalid-dimensions'),
            (TOY.replace(b'</map>', b'<layer name="Ground" width="2" height="1"><data encoding="csv">1,62</data></layer></map>'), 'ground-not-unique'),
            (TOY.replace(b'encoding="csv"', b'encoding="base64"'), 'unsupported-encoding'),
            (TOY.replace(b'>1,62<', b'>1<'), 'cell-count-mismatch'),
            (TOY.replace(b'>1,62<', b'>-1,62<'), 'unsupported-gid'),
            (TOY.replace(b'>1,62<', b'>4294967296,62<'), 'unsupported-gid'),
            (TOY.replace(b'>1,62<', b'>2,62<'), 'unsupported-gid'),
            (TOY.replace(b'>1,62<', b'>1,<'), 'unsupported-gid'),
            (TOY.replace(b'</map>', b'<layer name="Items" width="2" height="1"><data encoding="csv">0,0</data></layer></map>'), 'items-already-present'),
            (TOY.replace(b'</map>', b'<layer name="Objects" width="2" height="1"><data encoding="csv">0,0</data></layer></map>'), 'items-already-present'),
        ]
        for source, code in cases:
            with self.subTest(code=code, source=source):
                self.assert_rejected(code, lambda: derive_map(source, frozenset({1, 62})))
        self.assert_rejected('invalid-input', lambda: derive_map(TOY, {1, 62}))
        derived, _ = derive_map(TOY, frozenset({1, 62}))
        self.assert_rejected('unsupported-encoding', lambda: derive_map(derived, frozenset({1, 62})))

    def test_t04_ambiguous_xml_and_multiple_items_rejected(self):
        bad_data = TOY.replace(b'</data>', b'</data><data encoding="csv">1,62</data>')
        self.assert_rejected('ground-not-unique', lambda: derive_map(bad_data, frozenset({1, 62})))
        dtd = TOY.replace(b'<map ', b'<!DOCTYPE map [<!ENTITY owned "1">]><map ', 1)
        self.assert_rejected('unsupported-input', lambda: derive_map(dtd, frozenset({1, 62})))
        multiple = TOY.replace(b'</map>', b'<layer name="Items"/><layer name="Items"/></map>')
        self.assert_rejected('items-already-present', lambda: derive_map(multiple, frozenset({1, 62})))
        compressed = TOY.replace(b'encoding="csv"', b'encoding="csv" compression="gzip"')
        self.assert_rejected('unsupported-encoding', lambda: derive_map(compressed, frozenset({1, 62})))

    def test_t04_unit_attack_existing_and_invalid_syntax_rejected(self):
        self.assert_rejected('invalid-input', lambda: derive_unit(b''))
        self.assert_rejected('unsupported-input', lambda: derive_unit(b'name: no-section\n'))
        self.assert_rejected('unsupported-input', lambda: derive_unit(b'[core\nname: toy\n'))
        for source in (b'[attack]\ncanAttack: false\n', b'[Attack]\n', b'[core]\nname: toy\n[attack]\n'):
            self.assert_rejected('attack-already-present', lambda: derive_unit(source))
        derived, _ = derive_unit(b'[core]\nname: toy\n')
        self.assert_rejected('attack-already-present', lambda: derive_unit(derived))


class WriterPublicTest(unittest.TestCase):
    def setUp(self):
        parent = os.environ.get('DERIVED_TEST_ROOT')
        if parent:
            Path(parent).mkdir(parents=True, exist_ok=True)
        self.base = Path(tempfile.mkdtemp(prefix='owned-derived-', dir=parent)).resolve()
        self.source = self.base / 'original'
        self.source.mkdir()
        self.old = self.base / 'old-evidence'
        self.old.mkdir()
        self.allowed = self.base / 'new-runs'
        self.allowed.mkdir()
        self.output = self.allowed / 'run-unique'
        content = {
            'maps/toy.tmx': TOY,
            'tilesets/a.tsx': b'<tileset name="owned"/>\n',
            'tilesets/b.tsx': b'<tileset name="owned-second"/>\n',
            'units/toy.ini': b'[core]\nname: toy\n',
            'images/owned.bin': b'owned-image-not-to-be-copied',
        }
        files = []
        for name, value in content.items():
            path = self.source / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(value)
            files.append({'path': name, 'bytes': len(value), 'sha256': hashlib.sha256(value).hexdigest(),
                          'role': 'dependency' if name.startswith('images/') else 'content'})
        self.manifest = {'schemaVersion': 1, 'files': files,
                         'map': 'maps/toy.tmx', 'unit': 'units/toy.ini',
                         'copy': ['tilesets/a.tsx', 'tilesets/b.tsx'],
                         'mapExpected': {'width': 2, 'height': 1, 'gids': {'1': 1, '62': 1}},
                         'allowedGids': [1, 62], 'dependencyMappings': []}
        self.identity = self.base / 'input.json'
        self.identity.write_text(json.dumps(self.manifest), encoding='utf-8')
        self.before = {p: p.read_bytes() for p in self.source.rglob('*') if p.is_file()}

    def call(self, output=None, manifest=None):
        return write_derived_inputs(self.source, output or self.output, manifest or self.manifest,
                                    allowed_output_root=self.allowed, forbidden_roots=[self.old])

    def test_t05_real_writer_copy_only_four_content_inputs(self):
        report = self.call()
        self.assertFalse(report['layoutReady'])
        self.assertEqual(EXPECTED, (self.output / 'derived/maps/toy.tmx').read_bytes())
        self.assertEqual(b'[core]\nname: toy\n\n[attack]\ncanAttack: false\n',
                         (self.output / 'derived/units/toy.ini').read_bytes())
        self.assertEqual(b'<tileset name="owned"/>\n', (self.output / 'derived/tilesets/a.tsx').read_bytes())
        self.assertEqual(4, len([p for p in (self.output / 'derived').rglob('*') if p.is_file()]))
        self.assertFalse((self.output / 'derived/images').exists())
        self.assertEqual(self.before, {p: p.read_bytes() for p in self.before})

    def test_t05_full_plan_failure_does_not_create_output(self):
        path = self.source / 'units/toy.ini'
        path.write_bytes(b'[attack]\ncanAttack: false\n')
        changed = json.loads(json.dumps(self.manifest))
        entry = next(p for p in changed['files'] if p['path'] == 'units/toy.ini')
        entry.update(bytes=path.stat().st_size, sha256=hashlib.sha256(path.read_bytes()).hexdigest())
        with self.assertRaises(DeriveError) as caught:
            self.call(manifest=changed)
        self.assertEqual('attack-already-present', caught.exception.code)
        self.assertFalse(self.output.exists())
        self.assertEqual([], list(self.allowed.iterdir()))

    def test_t05_hash_mismatch_and_existing_target_leave_no_partial_files(self):
        changed = json.loads(json.dumps(self.manifest))
        changed['files'][-1]['sha256'] = '0' * 64
        with self.assertRaises(DeriveError) as caught:
            self.call(manifest=changed)
        self.assertEqual('source-identity-mismatch', caught.exception.code)
        self.assertFalse(self.output.exists())
        self.output.mkdir()
        marker = self.output / 'owned-marker'
        marker.write_bytes(b'keep')
        with self.assertRaises(DeriveError) as caught:
            self.call()
        self.assertEqual('output-exists', caught.exception.code)
        self.assertEqual([marker], list(self.output.iterdir()))
        self.assertEqual(b'keep', marker.read_bytes())

    def test_t05_source_ancestor_and_old_evidence_targets_rejected(self):
        for target in (self.source, self.source / 'child', self.base, self.old / 'new'):
            with self.subTest(target=target):
                with self.assertRaises(DeriveError) as caught:
                    self.call(output=target)
                self.assertEqual('unsafe-output', caught.exception.code)
                self.assertFalse((target / 'derived').exists())

    def test_t05_symlink_redirected_input_or_output_is_rejected(self):
        outside = self.base / 'outside'
        outside.mkdir()
        link = self.allowed / 'redirect'
        # This is a retained owned negative fixture only, never an output link to original inputs.
        try:
            link.symlink_to(outside, target_is_directory=True)
        except (OSError, NotImplementedError) as error:
            self.skipTest('Owned symlink fixture unavailable: ' + type(error).__name__)
        with self.assertRaises(DeriveError) as caught:
            self.call(output=link / 'run')
        self.assertEqual('redirected-path', caught.exception.code)
        self.assertFalse((outside / 'run').exists())
        source_link = self.base / 'input-link'
        source_link.symlink_to(self.source, target_is_directory=True)
        with self.assertRaises(DeriveError) as caught:
            write_derived_inputs(source_link, self.output, self.manifest,
                                 allowed_output_root=self.allowed, forbidden_roots=[self.old])
        self.assertEqual('redirected-path', caught.exception.code)
        self.assertFalse(self.output.exists())

    def test_t05_real_cli_rejection_has_no_partial_output(self):
        changed = json.loads(json.dumps(self.manifest))
        changed['files'][0]['sha256'] = '0' * 64
        self.identity.write_text(json.dumps(changed), encoding='utf-8')
        tool = Path(__file__).with_name('original_input_transform.py')
        argv = [sys.executable, '-B', str(tool), '--source-root', str(self.source),
                '--output', str(self.output), '--identity', str(self.identity),
                '--allowed-output-root', str(self.allowed), '--forbid-root', str(self.old)]
        log = self.base / 'cli-invalid.log'
        with log.open('xb') as stream:
            result = subprocess.run(argv, stdout=stream, stderr=subprocess.STDOUT)
        self.assertEqual(2, result.returncode)
        self.assertIn('source-identity-mismatch', log.read_text('utf-8'))
        self.assertFalse(self.output.exists())
        self.assertEqual([], list(self.allowed.iterdir()))

    def test_t05_external_pinned_dependency_is_verified_but_never_copied(self):
        external = self.base / 'owned-external-image.bin'
        external.write_bytes(b'owned-outside-dependency')
        changed = json.loads(json.dumps(self.manifest))
        dependency = changed['files'][-1]
        dependency.update(sourcePath=str(external), bytes=external.stat().st_size,
                          sha256=hashlib.sha256(external.read_bytes()).hexdigest())
        self.call(manifest=changed)
        self.assertFalse((self.output / 'derived/images').exists())
        self.assertEqual(b'owned-outside-dependency', external.read_bytes())

    def test_t05_real_cli_public_arguments_and_exclusive_output(self):
        tool = Path(__file__).with_name('original_input_transform.py')
        argv = [sys.executable, '-B', str(tool), '--source-root', str(self.source),
                '--output', str(self.output), '--identity', str(self.identity),
                '--allowed-output-root', str(self.allowed), '--forbid-root', str(self.old)]
        log = self.base / 'cli-first.log'
        with log.open('xb') as stream:
            first = subprocess.run(argv, stdout=stream, stderr=subprocess.STDOUT)
        self.assertEqual(0, first.returncode, log.read_text('utf-8', errors='replace'))
        with (self.base / 'cli-second.log').open('xb') as stream:
            second = subprocess.run(argv, stdout=stream, stderr=subprocess.STDOUT)
        self.assertEqual(2, second.returncode)
        self.assertIn('output-exists', (self.base / 'cli-second.log').read_text('utf-8'))
        self.assertEqual(self.before, {p: p.read_bytes() for p in self.before})


if __name__ == '__main__':
    unittest.main()
