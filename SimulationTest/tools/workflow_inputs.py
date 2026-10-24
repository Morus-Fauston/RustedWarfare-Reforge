"""Frozen private input closure for current TMX/TSX/INI and JSON references.

This checks references; the existing Kotlin content loader remains authoritative.
Negative dependencies are frozen verbatim, never interpreted as valid content.
"""
import hashlib
import json
from pathlib import Path
import re
import shutil
import xml.etree.ElementTree as ET


def sha(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def within(root, name):
    root = Path(root).resolve(strict=True)
    path = root/Path(name)
    if Path(name).is_absolute() or '..' in Path(name).parts:
        raise ValueError(f'Non-canonical input path: {name}')
    resolved = path.resolve()
    if not resolved.is_relative_to(root) or resolved != path:
        raise ValueError(f'Input escapes root or has an alias: {name}')
    return path


def freeze(root, seeds):
    root = Path(root).resolve(strict=True)
    found = {}

    def visit(name, expected=None, recurse=True):
        path = within(root, name)
        if not path.is_file():
            raise ValueError(f'Missing input: {name}')
        digest = sha(path)
        if expected and digest != expected:
            raise ValueError(f'Input SHA mismatch: {name}')
        if name in found:
            return
        found[name] = {'path': name, 'sha256': digest, 'size': path.stat().st_size}
        if not recurse:
            return

        def relative(value):
            candidate = (path.parent/value).resolve()
            if not candidate.is_relative_to(root):
                raise ValueError(f'Reference escapes root: {name} -> {value}')
            visit(candidate.relative_to(root).as_posix())

        if path.suffix.lower() in ('.tmx', '.tsx'):
            for element in ET.parse(path).iter():
                if element.tag in ('tileset', 'image') and 'source' in element.attrib:
                    relative(element.attrib['source'])
        elif path.suffix.lower() == '.ini':
            for line in path.read_text(encoding='utf-8-sig').splitlines():
                match = re.match(r'\s*(image|copyFrom)\s*[:=]\s*(.*?)\s*$', line)
                if match:
                    for value in match[2].split(','):
                        value = value.strip()
                        if value and value.upper() not in ('NONE', 'AUTO'):
                            relative(value)
        elif path.suffix.lower() == '.json':
            document = json.loads(path.read_text(encoding='utf-8-sig'))
            def walk(value, recurse=True):
                if isinstance(value, dict):
                    if isinstance(value.get('path'), str) and 'sha256' in value:
                        ref = value['path']
                        if document.get('kind') == 'scenario':
                            resolved = (path.parent/ref).resolve()
                            if not resolved.is_relative_to(root):
                                raise ValueError(f'Scenario reference escapes root: {ref}')
                            ref = resolved.relative_to(root).as_posix()
                        visit(ref, value['sha256'], recurse)
                    for key, child in value.items():
                        walk(child, recurse and key != 'negativeDependencies')
                elif isinstance(value, list):
                    for child in value:
                        walk(child, recurse)
            walk(document)
    for name in seeds:
        visit(name)
    return {'schemaVersion': 1, 'seeds': list(seeds), 'files': sorted(found.values(), key=lambda r: r['path'])}


def verify(manifest, root):
    if set(manifest)-{'schemaVersion','seeds','files','externalFiles'} or manifest['schemaVersion'] != 1:
        raise ValueError('Unknown input manifest schema')
    names = [r['path'] for r in manifest['files']]
    if not names or len(set(names)) != len(names) or not manifest['seeds']:
        raise ValueError('Empty/duplicate input manifest or missing seeds')
    for item in manifest['files']:
        path = within(root, item['path'])
        if not path.is_file():
            raise ValueError(f'Missing input: {item["path"]}')
        if sha(path) != item['sha256'] or path.stat().st_size != item['size']:
            raise ValueError(f'Input SHA mismatch: {item["path"]}')
    closure = freeze(root, manifest['seeds'])
    declared = {r['path']:r for r in manifest['files']}
    for row in closure['files']:
        if declared.get(row['path']) != row:
            raise ValueError(f'Manifest omits/mismatches referenced input: {row["path"]}')
    for row in manifest.get('externalFiles',[]):
        path=Path(row['path'])
        if not path.is_absolute() or '..' in path.parts or path.resolve()!=path:
            raise ValueError(f'Non-canonical external frozen reference: {path}')
        if not path.is_file() or sha(path)!=row['sha256'] or path.stat().st_size!=row['size']:
            raise ValueError(f'External frozen input drift: {path}')


def prepare(manifest, source, target):
    """Preflight the whole set before any copy; never replace differing bytes."""
    verify(manifest, source)
    target = Path(target).resolve(strict=True)
    paths = [(item, within(target, item['path'])) for item in manifest['files']]
    for item, path in paths:
        if path.exists() and (not path.is_file() or sha(path) != item['sha256']):
            raise ValueError(f'Target conflict: {item["path"]}')
        for parent in path.parents:
            if parent == target:
                break
            if parent.exists() and not parent.is_dir():
                raise ValueError(f'Target parent conflict: {parent}')
    copied = []
    for item, path in paths:
        if not path.exists():
            path.parent.mkdir(parents=True, exist_ok=True)
            with path.open('xb') as stream, within(source, item['path']).open('rb') as incoming:
                shutil.copyfileobj(incoming, stream)
            copied.append(item['path'])
    verify(manifest, source)
    verify(manifest, target)
    return {'copied': copied, 'verifiedFiles': len(paths)}
