"""Bounded original-only input carrier transform. Not a loader or equivalence approval."""
import base64
import hashlib
import re
import struct
import argparse
import json
import os
from pathlib import Path
import stat
import sys
from xml.parsers import expat


class DeriveError(ValueError):
    def __init__(self, code):
        super().__init__(code)
        self.code = code


def _text(raw):
    if not isinstance(raw, bytes) or not raw:
        raise DeriveError('invalid-input')
    try:
        value = raw.decode('utf-8-sig')
    except UnicodeError as error:
        raise DeriveError('invalid-input') from error
    if not value.strip() or '\0' in value:
        raise DeriveError('invalid-input')
    return value


def _xml_tree(raw):
    parser = expat.ParserCreate()
    stack = []
    roots = []
    def start(name, attributes):
        node = {'name': name, 'attrs': attributes, 'start': parser.CurrentByteIndex, 'children': [], 'text': ''}
        if stack:
            stack[-1]['children'].append(node)
        else:
            roots.append(node)
        stack.append(node)
    def end(name):
        node = stack.pop()
        node['close'] = parser.CurrentByteIndex
        node['end'] = raw.find(b'>', node['close']) + 1
    def text(value):
        if stack:
            stack[-1]['text'] += value
    parser.StartElementHandler = start
    parser.EndElementHandler = end
    parser.CharacterDataHandler = text
    def forbidden(*args):
        raise DeriveError('unsupported-input')
    parser.StartDoctypeDeclHandler = forbidden
    parser.EntityDeclHandler = forbidden
    parser.ExternalEntityRefHandler = forbidden
    try:
        parser.Parse(raw, True)
    except expat.ExpatError as error:
        raise DeriveError('unsupported-input') from error
    if len(roots) != 1:
        raise DeriveError('unsupported-input')
    return roots[0]


def derive_map(tmx: bytes, allowed_gids: frozenset[int]):
    _text(tmx)
    if (not isinstance(allowed_gids, frozenset) or not allowed_gids
            or any(type(gid) is not int or not 0 <= gid <= 0xffffffff for gid in allowed_gids)):
        raise DeriveError('invalid-input')
    root = _xml_tree(tmx)
    try:
        width, height = int(root['attrs']['width']), int(root['attrs']['height'])
        if root['name'] != 'map' or width <= 0 or height <= 0 or width * height > 1000000:
            raise ValueError('dimensions')
    except (ValueError, KeyError) as error:
        raise DeriveError('invalid-dimensions') from error
    grounds = [n for n in root['children'] if n['name'] == 'layer' and n['attrs'].get('name') == 'Ground']
    if len(grounds) != 1:
        raise DeriveError('ground-not-unique')
    ground = grounds[0]
    data_nodes = [n for n in ground['children'] if n['name'] == 'data']
    if len(data_nodes) != 1 or len(ground['children']) != 1:
        raise DeriveError('ground-not-unique')
    if ground['attrs'].get('width') != str(width) or ground['attrs'].get('height') != str(height):
        raise DeriveError('invalid-dimensions')
    data = data_nodes[0]
    if data['attrs'] != {'encoding': 'csv'} or data['children']:
        raise DeriveError('unsupported-encoding')
    tokens = data['text'].strip().split(',')
    if len(tokens) != width * height:
        raise DeriveError('cell-count-mismatch')
    if any(re.fullmatch(r'[0-9]+', token.strip()) is None for token in tokens):
        raise DeriveError('unsupported-gid')
    gids = [int(token.strip()) for token in tokens]
    if any(gid > 0xffffffff or gid not in allowed_gids for gid in gids):
        raise DeriveError('unsupported-gid')
    if any(n['name'] == 'layer' and n['attrs'].get('name', '').lower() in ('items', 'objects') for n in root['children']):
        raise DeriveError('items-already-present')
    if (tmx[data['start']:data['start'] + 5] != b'<data'
            or not tmx[data['close']:data['end']].startswith(b'</data>')
            or not tmx[root['close']:root['end']].startswith(b'</map>')):
        raise DeriveError('unsupported-input')
    raw = b''.join(struct.pack('<I', gid) for gid in gids)
    payload = base64.b64encode(raw)
    zero = base64.b64encode(bytes(width * height * 4))
    replacement = b'<data encoding="base64">' + payload + b'</data>'
    newline = b'\r\n' if b'\r\n' in tmx else b'\n'
    items = (newline + b' <layer name="Items" width="' + str(width).encode() + b'" height="' + str(height).encode()
             + b'"><data encoding="base64">' + zero + b'</data></layer>' + newline)
    spans = [(data['start'], data['end'], replacement), (root['close'], root['close'], items)]
    result = tmx
    for start, end, value in sorted(spans, reverse=True):
        result = result[:start] + value + result[end:]
    return result, {'groundRawBytes': len(raw), 'cells': len(gids), 'gids': gids,
                    'width': width, 'height': height, 'groundBase64Chars': len(payload),
                    'itemsRawBytes': width * height * 4, 'itemsBase64Chars': len(zero),
                    'touchedSpans': [{'start': s, 'end': e, 'beforeSha256': hashlib.sha256(tmx[s:e]).hexdigest(),
                                     'afterSha256': hashlib.sha256(v).hexdigest()} for s,e,v in spans]}


def derive_unit(ini: bytes):
    text = _text(ini)
    section = None
    sections = set()
    for raw in text.splitlines():
        line = raw.strip()
        if not line or line.startswith('#'):
            continue
        if line.startswith('['):
            if re.fullmatch(r'\[[^\[\]]+\]', line) is None:
                raise DeriveError('unsupported-input')
            section = line[1:-1].strip()
            if section.lower() == 'attack':
                raise DeriveError('attack-already-present')
            if not section or section in sections:
                raise DeriveError('unsupported-input')
            sections.add(section)
        elif section is None or ':' not in line or not line.split(':', 1)[0].strip():
            raise DeriveError('unsupported-input')
    if not sections:
        raise DeriveError('unsupported-input')
    newline = b'\r\n' if b'\r\n' in ini else b'\n'
    suffix = (b'' if ini.endswith(b'\n') else newline) + newline + b'[attack]' + newline + b'canAttack: false' + newline
    return ini + suffix, {'addedField': {'section': 'attack', 'field': 'canAttack', 'value': 'false'},
                          'originalPrefixBytes': len(ini), 'appendedBytes': len(suffix)}


def _digest(data):
    return hashlib.sha256(data).hexdigest()


def _plain_path(value):
    try:
        path = Path(value)
        if not path.is_absolute() or '..' in path.parts or '.' in path.parts:
            raise DeriveError('unsafe-output')
        for part in (path, *path.parents):
            try:
                attributes = part.lstat()
            except FileNotFoundError:
                continue
            if stat.S_ISLNK(attributes.st_mode) or getattr(attributes, 'st_file_attributes', 0) & 0x400:
                raise DeriveError('redirected-path')
        return path
    except DeriveError:
        raise
    except (TypeError, ValueError) as error:
        raise DeriveError('invalid-input') from error


def _relative_name(name):
    if (not isinstance(name, str) or not name or '\\' in name or ':' in name
            or name.startswith('/') or any(p in ('', '.', '..') for p in name.split('/'))):
        raise DeriveError('invalid-input')
    return name


def write_derived_inputs(source_root, output, identity, *, allowed_output_root, forbidden_roots=()):
    """Preflight all input and transformations before creating an exclusive new run.

    This controlled serial writer rejects pre-existing redirects; it is not an adversarial
    same-UID/concurrent filesystem transaction or a rollback-on-disk-error guarantee.
    """
    if not isinstance(identity, dict) or identity.get('schemaVersion') != 1:
        raise DeriveError('invalid-input')
    source = _plain_path(source_root)
    target = _plain_path(output)
    allowed = _plain_path(allowed_output_root)
    forbidden = [_plain_path(p) for p in forbidden_roots]
    if (target == source or target in source.parents or source in target.parents
            or target == allowed or allowed not in target.parents
            or any(target == p or p in target.parents or target in p.parents for p in forbidden)):
        raise DeriveError('unsafe-output')
    if target.exists():
        raise DeriveError('output-exists')
    if not source.is_dir() or not allowed.is_dir() or not target.parent.is_dir():
        raise DeriveError('unsafe-output')
    try:
        entries = identity['files']
        map_name = _relative_name(identity['map'])
        unit_name = _relative_name(identity['unit'])
        copies = identity['copy']
        allowed_gids = frozenset(identity['allowedGids'])
        expected_map = identity['mapExpected']
        if not isinstance(entries, list) or not entries or not isinstance(copies, list) or len(copies) != 2:
            raise DeriveError('invalid-input')
        copies = [_relative_name(p) for p in copies]
        names = [_relative_name(p['path']) for p in entries]
        output_names = [map_name, unit_name] + copies
        if len(set(names)) != len(names) or len(set(output_names)) != 4 or not set(output_names) <= set(names):
            raise DeriveError('invalid-input')
        if any(not name.startswith('tilesets/') or not name.endswith('.tsx') for name in copies):
            raise DeriveError('invalid-input')
        if not map_name.startswith('maps/') or not unit_name.startswith('units/'):
            raise DeriveError('invalid-input')
        data = {}
        for entry in entries:
            name = entry['path']
            if (not isinstance(entry['bytes'], int) or entry['bytes'] < 1
                    or not isinstance(entry['sha256'], str) or re.fullmatch(r'[0-9a-fA-F]{64}', entry['sha256']) is None):
                raise DeriveError('invalid-input')
            if 'sourcePath' in entry and (entry.get('role') != 'dependency' or name in output_names):
                raise DeriveError('invalid-input')
            path = _plain_path(entry.get('sourcePath', source / name))
            if target == path or target in path.parents or path in target.parents:
                raise DeriveError('unsafe-output')
            if not path.is_file():
                raise DeriveError('source-identity-mismatch')
            raw = path.read_bytes()
            if len(raw) != entry['bytes'] or _digest(raw).lower() != entry['sha256'].lower():
                raise DeriveError('source-identity-mismatch')
            data[name] = raw
        transformed_map, map_report = derive_map(data[map_name], allowed_gids)
        if (map_report['width'] != expected_map['width'] or map_report['height'] != expected_map['height']
                or {str(g): map_report['gids'].count(g) for g in sorted(set(map_report['gids']))} != expected_map['gids']):
            raise DeriveError('source-identity-mismatch')
        transformed_unit, unit_report = derive_unit(data[unit_name])
        output_data = {map_name: transformed_map, unit_name: transformed_unit,
                       copies[0]: data[copies[0]], copies[1]: data[copies[1]]}
    except (KeyError, TypeError, OSError) as error:
        raise DeriveError('invalid-input' if not isinstance(error, OSError) else 'source-identity-mismatch') from error
    report = {'schemaVersion': 1, 'kind': 'original-only-derived-inputs', 'layoutReady': False,
              'runtimeVerified': False, 'installAllowed': False, 'sourceRoot': str(source), 'outputRoot': str(target),
              'map': map_report, 'unit': unit_report, 'dependencyMappings': identity.get('dependencyMappings', []),
              'inputs': entries, 'outputs': [{'path': p, 'bytes': len(value), 'sha256': _digest(value)} for p,value in output_data.items()]}
    # Recheck the full plan's bytes and path boundaries immediately before the first write.
    for entry in entries:
        path = _plain_path(entry.get('sourcePath', source / entry['path']))
        if _digest(path.read_bytes()).lower() != entry['sha256'].lower():
            raise DeriveError('source-identity-mismatch')
    _plain_path(target)
    try:
        target.mkdir(exist_ok=False)
    except FileExistsError as error:
        raise DeriveError('output-exists') from error
    derived = target / 'derived'
    derived.mkdir()
    for name, raw in output_data.items():
        path = derived / name
        path.parent.mkdir(parents=True, exist_ok=True)
        with path.open('xb') as stream:
            stream.write(raw)
    with (target / 'manifest.json').open('x', encoding='utf-8') as stream:
        json.dump(report, stream, ensure_ascii=False, indent=2)
    return report


def main(argv=None):
    parser = argparse.ArgumentParser(description='Create a strictly traced original-only carrier; layout remains unready.')
    parser.add_argument('--source-root', required=True)
    parser.add_argument('--output', required=True)
    parser.add_argument('--identity', required=True)
    parser.add_argument('--allowed-output-root', required=True)
    parser.add_argument('--forbid-root', action='append', default=[])
    args = parser.parse_args(argv)
    try:
        identity_path = _plain_path(Path(args.identity).absolute())
        identity = json.loads(identity_path.read_text('utf-8'))
        report = write_derived_inputs(Path(args.source_root).absolute(), Path(args.output).absolute(), identity,
                                      allowed_output_root=Path(args.allowed_output_root).absolute(),
                                      forbidden_roots=[Path(p).absolute() for p in args.forbid_root])
        print(json.dumps({'output': report['outputRoot'], 'layoutReady': False, 'outputs': report['outputs']}, ensure_ascii=True))
        return 0
    except DeriveError as error:
        print(error.code, file=sys.stderr)
        return 2
    except (OSError, ValueError, UnicodeError) as error:
        print('invalid-input: ' + type(error).__name__, file=sys.stderr)
        return 2


if __name__ == '__main__':
    sys.exit(main())
