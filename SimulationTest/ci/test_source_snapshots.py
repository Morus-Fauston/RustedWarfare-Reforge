import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
import zipfile
from unittest.mock import patch

from source_snapshots import SnapshotStore


class SourceSnapshotTests(unittest.TestCase):
    def test_interrupted_tree_metadata_leaves_no_partial_registry_and_capture_can_retry(self):
        with tempfile.TemporaryDirectory(prefix='rwre-source-snapshot-') as temporary:
            root=Path(temporary).resolve();source=root/'source';source.mkdir()
            (source/'a.txt').write_bytes(b'metadata retry\n')
            store_root=root/'store';store=SnapshotStore(store_root)
            original_open=Path.open
            def interrupted_open(path,mode='r',*args,**kwargs):
                stream=original_open(path,mode,*args,**kwargs)
                if mode in ('xb','wb') and path.suffix=='.json' and path.resolve().is_relative_to(store_root):
                    stream.write(b'partial metadata');stream.close()
                    raise OSError('controlled metadata write interruption')
                return stream
            with patch.object(Path,'open',new=interrupted_open):
                with self.assertRaisesRegex(OSError,'controlled metadata write interruption'):
                    store.capture(source,['a.txt'],root/'failed-reference.json')
            self.assertFalse((root/'failed-reference.json').exists())
            self.assertEqual([],list(store_root.rglob('*.json')))
            receipt=store.capture(source,['a.txt'],root/'reference.json')
            self.assertEqual(0,receipt['newBlobBytes'])
            store.restore(root/'reference.json',root/'restored')
            self.assertEqual(b'metadata retry\n',(root/'restored/a.txt').read_bytes())

    def test_interrupted_capture_publishes_no_partial_zip_and_the_same_tree_can_retry(self):
        with tempfile.TemporaryDirectory(prefix='rwre-source-snapshot-') as temporary:
            root=Path(temporary).resolve();source=root/'source';source.mkdir()
            (source/'a.txt').write_bytes(b'first source\n');(source/'b.txt').write_bytes(b'second source\n')
            store_root=root/'store';store=SnapshotStore(store_root)
            original=zipfile.ZipFile.writestr
            def interrupted_write(archive,*args,**kwargs):
                original(archive,*args,**kwargs)
                raise OSError('controlled archive write interruption')
            with patch('zipfile.ZipFile.writestr',new=interrupted_write):
                with self.assertRaisesRegex(OSError,'controlled archive write interruption'):
                    store.capture(source,['a.txt','b.txt'],root/'failed-reference.json')
            self.assertFalse((root/'failed-reference.json').exists())
            self.assertEqual([], [p for p in store_root.rglob('*') if p.is_file()])
            store.capture(source,['a.txt','b.txt'],root/'reference.json')
            store.restore(root/'reference.json',root/'restored')
            self.assertEqual(b'first source\n',(root/'restored/a.txt').read_bytes())
            self.assertEqual(b'second source\n',(root/'restored/b.txt').read_bytes())

    def test_interrupted_copy_publishes_no_partial_object_and_the_same_reference_can_retry(self):
        with tempfile.TemporaryDirectory(prefix='rwre-source-snapshot-') as temporary:
            root=Path(temporary).resolve();source=root/'source';source.mkdir()
            (source/'a.txt').write_bytes(b'retry after interrupted copy\n')
            store=SnapshotStore(root/'original')
            store.capture(source,['a.txt'],root/'reference.json')
            destination=root/'destination'
            def interrupted_copy(input_stream,output_stream,*args,**kwargs):
                output_stream.write(input_stream.read(8))
                raise OSError('controlled filesystem copy interruption')
            # Inject only at the stdlib filesystem boundary, using real files.
            with patch('shutil.copyfileobj',side_effect=interrupted_copy):
                with self.assertRaisesRegex(OSError,'controlled filesystem copy interruption'):
                    store.copy_to(root/'reference.json',destination)
            self.assertEqual([], [p for p in destination.rglob('*') if p.is_file()])
            receipt=store.copy_to(root/'reference.json',destination)
            self.assertGreater(receipt['newBlobBytes'],0)
            SnapshotStore(destination).restore(root/'reference.json',root/'restored')
            self.assertEqual(b'retry after interrupted copy\n',(root/'restored/a.txt').read_bytes())

    def test_shared_archive_can_be_copied_once_and_restored_without_the_original_store(self):
        with tempfile.TemporaryDirectory(prefix='rwre-source-snapshot-') as temporary:
            root = Path(temporary).resolve();source = root/'source';source.mkdir()
            (source/'a.txt').write_bytes(b'portable copy\n')
            store_root = root/'store';store = SnapshotStore(store_root)
            store.capture(source, ['a.txt'], root/'reference.json')
            first = store.copy_to(root/'reference.json', root/'relocated')
            second = store.copy_to(root/'reference.json', root/'relocated')
            self.assertGreater(first['newBlobBytes'], 0)
            self.assertEqual(0, second['newBlobBytes'])
            hidden = root/'original-store-unavailable'
            self.assertEqual(root, store_root.resolve().parent)
            self.assertEqual(root, hidden.resolve().parent)
            store_root.rename(hidden)
            SnapshotStore(root/'relocated').restore(root/'reference.json', root/'restored')
            self.assertEqual(b'portable copy\n', (root/'restored/a.txt').read_bytes())
            self.assertEqual(1, len(list((root/'relocated/objects').iterdir())))

    def test_saved_objects_are_compressed_and_still_restore_exact_bytes(self):
        with tempfile.TemporaryDirectory(prefix='rwre-source-snapshot-') as temporary:
            root = Path(temporary).resolve();source = root/'source';source.mkdir()
            payload = b'compressible source line\n'*1000
            (source/'large.txt').write_bytes(payload)
            store_root = root/'store';store = SnapshotStore(store_root)
            result = store.capture(source, ['large.txt'], root/'reference.json')
            self.assertLess(sum(p.stat().st_size for p in (store_root/'objects').iterdir()), len(payload)//2)
            self.assertLess(result['newBlobBytes'], len(payload)//2)
            store.restore(root/'reference.json', root/'restored')
            self.assertEqual(payload, (root/'restored/large.txt').read_bytes())

    def test_corrupt_object_refuses_both_reuse_and_restore_without_overwriting_evidence(self):
        with tempfile.TemporaryDirectory(prefix='rwre-source-snapshot-') as temporary:
            root = Path(temporary).resolve();source = root/'source';source.mkdir()
            (source/'a.txt').write_bytes(b'first\n')
            store_root = root/'store';store = SnapshotStore(store_root)
            store.capture(source, ['a.txt'], root/'reference.json')
            row = json.loads((root/'reference.json').read_bytes())['files'][0]
            blob = (store_root/row['blob']).resolve()
            self.assertTrue(blob.is_relative_to(store_root.resolve()))
            blob.write_bytes(b'broken')
            with self.assertRaisesRegex(ValueError, 'bytes differ'):
                store.capture(source, ['a.txt'], root/'new-reference.json')
            with self.assertRaisesRegex(ValueError, 'bytes differ'):
                store.restore(root/'reference.json', root/'restored')
            self.assertFalse((root/'new-reference.json').exists())
            self.assertFalse((root/'restored').exists())
            self.assertEqual(b'broken', blob.read_bytes())

    def test_existing_restore_target_is_never_overwritten(self):
        with tempfile.TemporaryDirectory(prefix='rwre-source-snapshot-') as temporary:
            root = Path(temporary).resolve();source = root/'source';source.mkdir()
            (source/'a.txt').write_bytes(b'first\n')
            store = SnapshotStore(root/'store')
            store.capture(source, ['a.txt'], root/'reference.json')
            target = root/'restored';target.mkdir();(target/'kept.txt').write_bytes(b'kept')
            with self.assertRaises(FileExistsError):
                store.restore(root/'reference.json', target)
            self.assertEqual(['kept.txt'], [p.name for p in target.iterdir()])
            self.assertEqual(b'kept', (target/'kept.txt').read_bytes())

    def test_portable_cli_restores_using_only_this_reference_and_its_objects(self):
        with tempfile.TemporaryDirectory(prefix='rwre-source-snapshot-') as temporary:
            root = Path(temporary).resolve();source = root/'source';source.mkdir()
            (source/'a.txt').write_bytes(b'portable\n')
            SnapshotStore(root/'store').capture(source, ['a.txt'], root/'reference.json')
            portable = root/'portable';portable.mkdir()
            shutil.copyfile(Path(__file__).with_name('source_snapshots.py'), portable/'source_snapshots.py')
            shutil.copyfile(root/'reference.json', portable/'source.json')
            shutil.copytree(root/'store/objects', portable/'store/objects')
            source.rename(root/'source-hidden')
            result = subprocess.run([sys.executable, '-B', str(portable/'source_snapshots.py'),
                '--store', str(portable/'store'), 'restore', '--reference', str(portable/'source.json'),
                '--destination', str(portable/'restored')], cwd=portable, capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertTrue((portable/'restored/a.txt').is_file(), result.stdout)
            self.assertEqual(b'portable\n', (portable/'restored/a.txt').read_bytes())
            self.assertEqual(1, json.loads(result.stdout)['files'])
            self.assertFalse((portable/'store/trees').exists())

    def test_same_named_file_with_new_bytes_never_reuses_the_old_payload(self):
        with tempfile.TemporaryDirectory(prefix='rwre-source-snapshot-') as temporary:
            root = Path(temporary).resolve();source = root/'source';source.mkdir()
            (source/'a.txt').write_bytes(b'first\n')
            store = SnapshotStore(root/'store')
            first = store.capture(source, ['a.txt'], root/'first.json')
            (source/'a.txt').write_bytes(b'changed\n')
            second = store.capture(source, ['a.txt'], root/'second.json')
            self.assertNotEqual(first['treeSha256'], second['treeSha256'])
            self.assertEqual(8, second['newLogicalBytes'])
            store.restore(root/'first.json', root/'old-restored')
            store.restore(root/'second.json', root/'new-restored')
            self.assertEqual(b'first\n', (root/'old-restored/a.txt').read_bytes())
            self.assertEqual(b'changed\n', (root/'new-restored/a.txt').read_bytes())

    def test_missing_object_refuses_restore_without_reading_the_still_present_original(self):
        with tempfile.TemporaryDirectory(prefix='rwre-source-snapshot-') as temporary:
            root = Path(temporary).resolve();source = root/'source';source.mkdir()
            (source/'a.txt').write_bytes(b'first\n')
            store_root = root/'store';store = SnapshotStore(store_root)
            store.capture(source, ['a.txt'], root/'reference.json')
            document = json.loads((root/'reference.json').read_text(encoding='utf8'))
            blob = (store_root/document['files'][0]['blob']).resolve()
            self.assertTrue(blob.is_relative_to(store_root.resolve()))
            blob.unlink()
            with self.assertRaisesRegex(ValueError, 'Missing snapshot object'):
                store.verify(root/'reference.json')
            with self.assertRaisesRegex(ValueError, 'Missing snapshot object'):
                store.restore(root/'reference.json', root/'restored')
            self.assertFalse((root/'restored').exists())
            self.assertEqual(b'first\n', (source/'a.txt').read_bytes())

    def test_a_later_same_content_entry_cannot_hide_an_invalid_object_locator(self):
        with tempfile.TemporaryDirectory(prefix='rwre-source-snapshot-') as temporary:
            root = Path(temporary).resolve();source = root/'source';source.mkdir()
            for name in ['a.txt', 'b.txt']:
                (source/name).write_bytes(b'first\n')
            store = SnapshotStore(root/'store')
            store.capture(source, ['a.txt', 'b.txt'], root/'reference.json')
            document = json.loads((root/'reference.json').read_text(encoding='utf8'))
            document['files'][1]['blob'] = '../unrelated-object'
            (root/'reference.json').write_text(json.dumps(document), encoding='utf8')
            with self.assertRaisesRegex(ValueError, 'object identity'):
                store.restore(root/'reference.json', root/'restored')
            self.assertFalse((root/'restored').exists())

    def test_same_byte_tree_has_one_payload_and_restores_from_each_own_reference_without_source(self):
        with tempfile.TemporaryDirectory(prefix='rwre-source-snapshot-') as temporary:
            root = Path(temporary).resolve()
            source = root/'source';source.mkdir()
            (source/'nested').mkdir()
            (source/'a.txt').write_bytes(b'first\n')
            (source/'nested/b.bin').write_bytes(bytes([0, 1, 2, 3]))
            (source/'unselected.txt').write_text('Outside the declared capture', encoding='utf8')
            store = SnapshotStore(root/'store')
            first = store.capture(source, ['a.txt', 'nested/b.bin'], root/'run-one/source.json')
            second = store.capture(source, ['a.txt', 'nested/b.bin'], root/'run-two/source.json')
            self.assertEqual(first['treeSha256'], second['treeSha256'])
            self.assertEqual(10, first['newLogicalBytes'])
            self.assertEqual(0, second['newBlobBytes'])
            self.assertNotEqual(first['referenceSha256'], '')
            # Only this test's freshly created source is renamed. Neither restoration
            # receives it as an argument, nor has the old absolute locator available.
            hidden = root/'source-no-longer-current'
            self.assertEqual(root, source.resolve().parent)
            self.assertEqual(root, hidden.resolve().parent)
            source.rename(hidden)
            self.assertFalse(source.exists())
            for index, reference in enumerate([root/'run-one/source.json', root/'run-two/source.json']):
                restored = root/f'restored-{index}'
                receipt = store.restore(reference, restored)
                self.assertEqual(first['treeSha256'], receipt['treeSha256'])
                self.assertEqual(b'first\n', (restored/'a.txt').read_bytes())
                self.assertEqual(bytes([0, 1, 2, 3]), (restored/'nested/b.bin').read_bytes())
                self.assertEqual({'a.txt', 'nested/b.bin'},
                    {p.relative_to(restored).as_posix() for p in restored.rglob('*') if p.is_file()})
                self.assertEqual(2, len(json.loads(reference.read_text(encoding='utf8'))['files']))


if __name__ == '__main__':
    unittest.main()
