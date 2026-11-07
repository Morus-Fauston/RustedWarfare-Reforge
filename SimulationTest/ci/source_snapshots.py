"""Content-addressed source snapshots for new, separately recorded ticket batches.

Callers serialize capture and coordinate their normal workflow resources. References
contain their complete relative file list; restoration needs no original source root.
No historical archive is rewritten, and existing objects are always checked.
"""
import hashlib
import argparse
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import stat
import sys
import tempfile
import zipfile
import zlib

_INVALID_NAME = re.compile(r'[\x00-\x1f<>:"\\|?*]')
_RESERVED_NAMES = {'CON', 'PRN', 'AUX', 'NUL',
                   *(f'COM{i}' for i in range(1, 10)), *(f'LPT{i}' for i in range(1, 10))}


def _json(value):
    return (json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':'))+'\n').encode('utf8')


def _sha(data):
    return hashlib.sha256(data).hexdigest()


def _name(value):
    if not isinstance(value, str) or not value or _INVALID_NAME.search(value):
        raise ValueError('Expected a portable relative source path')
    path = PurePosixPath(value)
    if path.is_absolute() or '..' in path.parts or path.as_posix() != value or value == '.':
        raise ValueError('Source path must stay inside its declared root')
    for part in path.parts:
        if part.endswith((' ', '.')) or part.split('.')[0].upper() in _RESERVED_NAMES:
            raise ValueError('Source path is not portable')
    return value


def _tree(files):
    return dict(schemaVersion=1, kind='candidate-source-tree', files=[
        {key: row[key] for key in ('path', 'sha256', 'bytes')} for row in files])


def _object_identity(row, tree_identity):
    if (not isinstance(row['sha256'], str) or not re.fullmatch(r'[0-9a-f]{64}', row['sha256'])
            or row['blob'] != 'objects/'+tree_identity+'.zip' or row['codec'] != 'zip-deflate'):
        raise ValueError('Invalid content object identity')


class SnapshotStore:
    def __init__(self, root):
        self.root = Path(root).resolve()

    def _member(self, name):
        path = self.root/_name(name)
        if path.is_symlink() or not path.resolve().is_relative_to(self.root):
            raise ValueError('Object locator escaped the snapshot store')
        return path

    def _publish_member(self, name, write, check):
        """Check a complete staged member before making its final name visible."""
        target = self._member(name)
        if target.exists():
            check(self)
            return 0
        target.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='.source-publish-', dir=target.parent) as temporary:
            pending = SnapshotStore(temporary)
            path = pending._member(name)
            path.parent.mkdir(parents=True, exist_ok=True)
            write(path)
            check(pending)
            if target.exists():
                check(self)
                return 0
            path.rename(target)
            return target.stat().st_size

    def _check_archive(self, files, identity):
        for row in files:
            _object_identity(row, identity)
        path = self._member('objects/'+identity+'.zip')
        if not path.is_file():
            raise ValueError('Missing snapshot object: '+path.name)
        payloads = {}
        try:
            with zipfile.ZipFile(path) as archive:
                if sorted(archive.namelist()) != [row['path'] for row in files]:
                    raise ValueError('Snapshot object file list differs')
                for row in files:
                    if archive.getinfo(row['path']).file_size != row['bytes']:
                        raise ValueError('Snapshot object bytes differ: '+row['path'])
                    data = archive.read(row['path'])
                    if len(data) != row['bytes'] or _sha(data) != row['sha256']:
                        raise ValueError('Snapshot object bytes differ: '+row['path'])
                    payloads[row['path']] = data
        except (zipfile.BadZipFile, EOFError, RuntimeError, zlib.error) as error:
            raise ValueError('Snapshot object bytes differ: '+path.name) from error
        return payloads

    def capture(self, source_root, paths, reference):
        source_root = Path(source_root).resolve(strict=True)
        reference = Path(reference).resolve()
        if reference.exists():
            raise FileExistsError('Snapshot reference already exists')
        names = sorted(_name(name) for name in paths)
        if not names or len(names) != len(set(name.casefold() for name in names)):
            raise ValueError('A nonempty, unique source file set is required')
        files, payloads, parents = [], {}, {}
        for name in names:
            source = source_root/name
            if source.parent not in parents:
                parents[source.parent] = source.parent.resolve(strict=True)
            attributes = source.lstat()
            if not parents[source.parent].is_relative_to(source_root) or not stat.S_ISREG(attributes.st_mode):
                raise ValueError('Source must be a regular file inside its root')
            data = source.read_bytes(); identity = _sha(data)
            files.append(dict(path=name, sha256=identity, bytes=len(data),
                              mode=stat.S_IMODE(attributes.st_mode)))
            payloads[name] = data
        tree = _json(_tree(files)); identity = _sha(tree)
        for row in files:
            row.update(blob='objects/'+identity+'.zip', codec='zip-deflate')
        self._member('objects').mkdir(parents=True, exist_ok=True)
        self._member('trees').mkdir(parents=True, exist_ok=True)
        def write_archive(path):
            with zipfile.ZipFile(path, 'x', compression=zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
                for row in files:
                    entry = zipfile.ZipInfo(row['path'], date_time=(1980, 1, 1, 0, 0, 0))
                    entry.compress_type = zipfile.ZIP_DEFLATED
                    archive.writestr(entry, payloads[row['path']], compresslevel=6)
        written = self._publish_member('objects/'+identity+'.zip', write_archive,
            lambda store: store._check_archive(files, identity))
        logical_written = sum(row['bytes'] for row in files) if written else 0
        manifest_name = 'trees/'+identity+'.json'
        def write_metadata(path):
            with path.open('xb') as stream:
                stream.write(tree)
        def check_metadata(store):
            if store._member(manifest_name).read_bytes() != tree:
                raise ValueError('Existing snapshot tree metadata differs')
        self._publish_member(manifest_name, write_metadata, check_metadata)
        document = dict(schemaVersion=1, kind='candidate-source-reference', treeSha256=identity, files=files)
        reference.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='.source-reference-', dir=reference.parent) as staging:
            ready = Path(staging)/'reference.json'
            ready.write_bytes(_json(document))
            if reference.exists():
                raise FileExistsError('Snapshot reference already exists')
            ready.rename(reference)
        return dict(treeSha256=identity, referenceSha256=_sha(reference.read_bytes()),
                    files=len(files), logicalBytes=sum(row['bytes'] for row in files), newBlobBytes=written,
                    newLogicalBytes=logical_written)

    def _load_reference(self, reference):
        document = json.loads(Path(reference).read_bytes())
        if not isinstance(document, dict) or document.get('schemaVersion') != 1 or document.get('kind') != 'candidate-source-reference':
            raise ValueError('Unsupported source snapshot reference')
        files = document.get('files')
        if not isinstance(files, list) or not files:
            raise ValueError('Missing snapshot source file list')
        if any(not isinstance(row, dict) or not {'path', 'sha256', 'bytes', 'mode', 'blob', 'codec'} <= row.keys() for row in files):
            raise ValueError('Incomplete snapshot source file entry')
        names = [_name(row['path']) for row in files]
        if names != sorted(names) or len(set(name.casefold() for name in names)) != len(names):
            raise ValueError('Unordered or duplicate restored paths')
        selected = set(name.casefold() for name in names)
        if any(parent.as_posix().casefold() in selected for name in names for parent in PurePosixPath(name).parents if parent.as_posix() != '.'):
            raise ValueError('Restored file conflicts with a parent directory')
        if _sha(_json(_tree(files))) != document.get('treeSha256'):
            raise ValueError('Snapshot reference tree identity differs')
        for row in files:
            if type(row['bytes']) is not int or row['bytes'] < 0 or type(row['mode']) is not int or not 0 <= row['mode'] <= 0o777:
                raise ValueError('Invalid source size or mode')
            _object_identity(row, document['treeSha256'])
        payloads = self._check_archive(files, document['treeSha256'])
        return document, payloads

    def copy_to(self, reference, destination_store):
        """Copy the validated referenced snapshot; a batch reference remains independent."""
        document, payloads = self._load_reference(reference)
        del payloads
        destination = SnapshotStore(destination_store)
        name = document['files'][0]['blob']
        def write(path):
            with self._member(name).open('rb') as source, path.open('xb') as output:
                shutil.copyfileobj(source, output)
        written = destination._publish_member(name, write,
            lambda store: store._check_archive(document['files'], document['treeSha256']))
        return dict(treeSha256=document['treeSha256'], newBlobBytes=written)

    def verify(self, reference):
        """Validate the complete referenced snapshot without materializing a tree."""
        document, payloads = self._load_reference(reference)
        return dict(treeSha256=document['treeSha256'], files=len(payloads),
                    logicalBytes=sum(len(data) for data in payloads.values()))

    def restore(self, reference, destination):
        document, payloads = self._load_reference(reference)
        files = document['files']
        destination = Path(destination).absolute()
        if destination.exists() or destination.is_symlink():
            raise FileExistsError('Restore requires a new destination')
        destination.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='.source-restore-', dir=destination.parent) as staging:
            ready = Path(staging)/'ready';ready.mkdir()
            for row in files:
                target = ready/row['path']
                target.parent.mkdir(parents=True, exist_ok=True)
                with target.open('xb') as stream:
                    stream.write(payloads[row['path']])
                    mode_differs = stat.S_IMODE(os.fstat(stream.fileno()).st_mode) != row['mode']
                if mode_differs:
                    os.chmod(target, row['mode'])
                if _sha(target.read_bytes()) != row['sha256']:
                    raise ValueError('Restored file bytes differ')
            if destination.exists() or destination.is_symlink():
                raise FileExistsError('Restore requires a new destination')
            ready.rename(destination)
        return dict(treeSha256=document['treeSha256'], referenceSha256=_sha(Path(reference).read_bytes()),
                    files=len(files), logicalBytes=sum(row['bytes'] for row in files))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--store', required=True, type=Path)
    commands = parser.add_subparsers(dest='operation', required=True)
    capture = commands.add_parser('capture', help='Capture the explicit public source closure')
    capture.add_argument('--repo', required=True, type=Path)
    capture.add_argument('--reference', required=True, type=Path)
    restore = commands.add_parser('restore', help='Restore solely from a batch reference and its objects')
    restore.add_argument('--reference', required=True, type=Path)
    restore.add_argument('--destination', required=True, type=Path)
    args = parser.parse_args()
    store = SnapshotStore(args.store)
    try:
        if args.operation == 'capture':
            from public_check import source_files
            repo = args.repo.resolve(strict=True)
            result = store.capture(repo, [p.relative_to(repo).as_posix() for p in source_files(repo)], args.reference)
        else:
            result = store.restore(args.reference, args.destination)
        print(json.dumps(result, sort_keys=True))
        return 0
    except (ValueError, OSError, KeyError, TypeError) as error:
        print(str(error), file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
