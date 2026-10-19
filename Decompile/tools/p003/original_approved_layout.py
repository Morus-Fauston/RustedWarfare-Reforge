"""Produce only the approved L-A original-only host payload; never permit installation."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import sys
from xml.parsers import expat

from original_dependency_layout import LayoutError, audit_layout
from original_input_transform import derive_map, derive_unit


MAP = 'maps/p001-controlled.tmx'
UNIT = 'units/weaponless_tank.ini'
COPIES = ('tilesets/p001-ground.tsx', 'tilesets/p001-rock.tsx')
DEPENDENCIES = ('longgrass.png', 'mountain.png', 'tank.png')
UNIT_BEFORE = b'../../../../Rusted Warfare/assets/units/tanks/tank.png'
_ATTRIBUTE = re.compile(rb'\s+([A-Za-z_:][A-Za-z0-9_:.-]*)\s*=\s*("[^"]*"|\x27[^\x27]*\x27)')


def _sha(raw):
    return hashlib.sha256(raw).hexdigest()


def _plain(value):
    try:
        text = os.fspath(value)
        parts = text.replace('\\', '/').split('/')
        if (not text or any(ord(c) < 32 for c in text) or text.startswith(('//', '\\\\'))
                or any(p in ('.', '..') or p.endswith((' ', '.')) for p in parts if p)
                or any(p == '' for p in parts[1:-1])):
            raise LayoutError('unsafe-output')
        path = Path(text)
        if not path.is_absolute():
            raise LayoutError('unsafe-output')
        for component in (path, *path.parents):
            try:
                attributes = component.lstat()
            except FileNotFoundError:
                continue
            if stat.S_ISLNK(attributes.st_mode) or getattr(attributes, 'st_file_attributes', 0) & 0x400:
                raise LayoutError('redirected-input')
        return path
    except (TypeError, ValueError) as error:
        if isinstance(error, LayoutError):
            raise
        raise LayoutError('unsafe-output') from error


def _overlap(left, right):
    return left == right or left in right.parents or right in left.parents


def _map_spans(raw):
    parser = expat.ParserCreate()
    stack, spans = [], []

    def start(tag, attributes):
        if tag == 'tileset' and stack == ['map']:
            before = attributes.get('source', '').encode('utf-8')
            expected = {b'../tilesets/p001-ground.tsx': b'p001-ground.tsx',
                        b'../tilesets/p001-rock.tsx': b'p001-rock.tsx'}
            if before not in expected or raw.count(before) != 1:
                raise LayoutError('rewrite-target-mismatch')
            cursor = parser.CurrentByteIndex + len(b'<tileset')
            locations = []
            while not raw[cursor:].lstrip().startswith((b'>', b'/>')):
                attribute = _ATTRIBUTE.match(raw, cursor)
                if attribute is None:
                    raise LayoutError('rewrite-target-mismatch')
                if attribute[1] == b'source':
                    locations.append((attribute.start(2) + 1, attribute.end(2) - 1))
                cursor = attribute.end()
            if len(locations) != 1:
                raise LayoutError('rewrite-target-mismatch')
            left, right = locations[0]
            if raw[left:right] != before:
                raise LayoutError('rewrite-target-mismatch')
            spans.append((left, right, expected[before]))
        stack.append(tag)

    def forbidden(*args):
        raise LayoutError('unsupported-xml')

    parser.StartElementHandler = start
    parser.EndElementHandler = lambda tag: stack.pop()
    parser.StartDoctypeDeclHandler = forbidden
    parser.EntityDeclHandler = forbidden
    parser.ExternalEntityRefHandler = forbidden
    try:
        parser.Parse(raw, True)
    except expat.ExpatError as error:
        raise LayoutError('rewrite-target-mismatch') from error
    if len(spans) != 2 or {raw[a:b] for a, b, _ in spans} != {
            b'../tilesets/p001-ground.tsx', b'../tilesets/p001-rock.tsx'}:
        raise LayoutError('rewrite-target-mismatch')
    return spans


def _unit_spans(raw):
    if raw.count(UNIT_BEFORE) != 1:
        raise LayoutError('rewrite-target-mismatch')
    section, sections, spans, offset = None, set(), [], 0
    for line in raw.splitlines(keepends=True):
        value = line.strip()
        if offset == 0:
            value = value.removeprefix(b'\xef\xbb\xbf')
        if value and not value.startswith(b'#'):
            if value.startswith(b'['):
                if not re.fullmatch(rb'\[[^\[\]]+\]', value) or value in sections:
                    raise LayoutError('rewrite-target-mismatch')
                sections.add(value)
                section = value
            elif section == b'[graphics]' and value.split(b':', 1)[0].strip() == b'image':
                prefix, separator, image = line.partition(b':')
                if not separator or image.strip() != UNIT_BEFORE:
                    raise LayoutError('rewrite-target-mismatch')
                left = offset + len(prefix) + 1 + len(image) - len(image.lstrip())
                spans.append((left, left + len(UNIT_BEFORE), b'tank.png'))
        offset += len(line)
    if len(spans) != 1:
        raise LayoutError('rewrite-target-mismatch')
    return spans


def _rewrite(raw, spans, source_name, target_name):
    result, records, shift = raw, [], 0
    for left, right, after in sorted(spans):
        records.append({'input': source_name, 'output': target_name, 'inputStart': left, 'inputEnd': right,
                        'outputStart': left + shift, 'outputEnd': left + shift + len(after),
                        'before': raw[left:right].decode('utf-8'), 'after': after.decode('utf-8'),
                        'beforeSha256': _sha(raw[left:right]), 'afterSha256': _sha(after)})
        shift += len(after) - (right - left)
    for left, right, after in sorted(spans, reverse=True):
        result = result[:left] + after + result[right:]
    return result, records


def _plan(source_root, derived_root, identity_path, identity_sha256, di_manifest_path, di_manifest_sha256):
    source, derived = _plain(source_root), _plain(derived_root)
    identity_file, manifest_file = _plain(identity_path), _plain(di_manifest_path)
    diagnostic = audit_layout(source, derived, identity_file, identity_sha256,
                              manifest_file, di_manifest_sha256)
    profile = json.loads(identity_file.read_bytes())
    if (profile['map'] != MAP or profile['unit'] != UNIT or set(profile['copy']) != set(COPIES)
            or len(profile['files']) != 7):
        raise LayoutError('unexpected-layout-profile')
    expected_map, _ = derive_map((source / MAP).read_bytes(), frozenset(profile['allowedGids']))
    expected_unit, _ = derive_unit((source / UNIT).read_bytes())
    if (derived / MAP).read_bytes() != expected_map or (derived / UNIT).read_bytes() != expected_unit:
        raise LayoutError('unapproved-content-change')
    dependencies = {}
    for entry in profile['files']:
        if entry.get('role') == 'dependency':
            path = _plain(entry.get('sourcePath', source / entry['path']))
            if path.name not in DEPENDENCIES or path.name in dependencies:
                raise LayoutError('unexpected-layout-profile')
            dependencies[path.name] = (path, path.read_bytes())
    if set(dependencies) != set(DEPENDENCIES):
        raise LayoutError('unexpected-layout-profile')
    payload, records, provenance = {}, [], {}
    for name, span_function in ((MAP, _map_spans), (UNIT, _unit_spans)):
        input_path = _plain(derived / name)
        raw = input_path.read_bytes()
        target = 'private/' + name
        payload[target], changes = _rewrite(raw, span_function(raw), name, target)
        records.extend(changes)
        provenance[target] = {'sourcePath': str(input_path), 'sourceSha256': _sha(raw)}
    for name in COPIES:
        input_path = _plain(derived / name)
        target = 'assets/' + name
        payload[target] = input_path.read_bytes()
        provenance[target] = {'sourcePath': str(input_path), 'sourceSha256': _sha(payload[target])}
    for name in DEPENDENCIES:
        path, raw = dependencies[name]
        target = 'private/units/tank.png' if name == 'tank.png' else 'assets/tilesets/bitmaps/' + name
        payload[target] = raw
        provenance[target] = {'sourcePath': str(path), 'sourceSha256': _sha(raw)}
    references = [dict(r) for r in diagnostic['references']]
    for reference in references:
        reference['staged'] = True
        reference['stagedScope'] = 'host-payload-only'
        reference['guardCode'] = None
        reference['guardEvidence'] = 'lexical-only; actual frozen-helper and runtime checks pending'
        if reference['kind'] == 'tsx':
            reference['reference'] = reference['reference'].split('/')[-1]
            reference['rawKey'] = 'tilesets/' + reference['reference']
        elif reference['kind'] == 'unit-image':
            reference['reference'] = 'tank.png'
            reference['rawKey'] = 'units/tank.png'
            reference['rawKeyScope'] = 'relative to approved private root, not an asset key'
    report = {'schemaVersion': 1, 'kind': 'original-approved-host-layout', 'hostLayoutReady': True,
              'layoutReady': False, 'runtimeVerified': False, 'installAllowed': False,
              'layoutScope': 'host-payload-only', 'sourceRoot': str(source), 'derivedRoot': str(derived),
              'identityPath': str(identity_file), 'identitySha256': identity_sha256,
              'diManifestPath': str(manifest_file), 'diManifestSha256': di_manifest_sha256,
              'transforms': records, 'references': references, 'cells': diagnostic['cells'],
              'outputs': [{'path': name, 'bytes': len(raw), 'sha256': _sha(raw), **provenance[name]}
                          for name, raw in payload.items()],
              'unverified': ['apk-asset-staging', 'device-private-seeding', 'private-unit-scanning',
                             'frozen-helper-actual-routes', 'other-required-unit-fields',
                             'runtime-initialization', 'gameplay-equivalence']}
    return payload, report


def write_approved_layout(source_root, derived_root, identity_path, identity_sha256,
                          di_manifest_path, di_manifest_sha256, output, *, allowed_output_root,
                          forbidden_roots=()):
    try:
        target, allowed = _plain(output), _plain(allowed_output_root)
        protected = [_plain(p) for p in (source_root, derived_root, identity_path, di_manifest_path, *forbidden_roots)]
        if (target == allowed or allowed not in target.parents or not allowed.is_dir()
                or not target.parent.is_dir() or any(_overlap(target, p) for p in protected)):
            raise LayoutError('unsafe-output')
        if target.exists():
            raise LayoutError('output-exists')
        payload, report = _plan(source_root, derived_root, identity_path, identity_sha256,
                                di_manifest_path, di_manifest_sha256)
        report['outputRoot'] = str(target)
        # Rebuild the full pinned plan immediately before creating the new output.
        again_payload, again_report = _plan(source_root, derived_root, identity_path, identity_sha256,
                                          di_manifest_path, di_manifest_sha256)
        if again_payload != payload or any(report[k] != v for k, v in again_report.items()):
            raise LayoutError('identity-mismatch')
        _plain(target)
        target.mkdir(exist_ok=False)
        for name, raw in payload.items():
            path = target / name
            path.parent.mkdir(parents=True, exist_ok=True)
            with path.open('xb') as stream:
                stream.write(raw)
        with (target / 'manifest.json').open('x', encoding='utf-8') as stream:
            json.dump(report, stream, ensure_ascii=False, indent=2)
        return report
    except LayoutError:
        raise
    except (OSError, ValueError, KeyError, TypeError) as error:
        raise LayoutError('output-io-error') from error


def audit_approved_layout(source_root, derived_root, identity_path, identity_sha256,
                          di_manifest_path, di_manifest_sha256, layout_root,
                          layout_manifest_path, layout_manifest_sha256):
    try:
        root, manifest_path = _plain(layout_root), _plain(layout_manifest_path)
        if manifest_path != root / 'manifest.json':
            raise LayoutError('layout-payload-mismatch')
        manifest_raw = manifest_path.read_bytes()
        if _sha(manifest_raw) != layout_manifest_sha256:
            raise LayoutError('manifest-identity-mismatch')
        payload, expected = _plan(source_root, derived_root, identity_path, identity_sha256,
                                  di_manifest_path, di_manifest_sha256)
        expected['outputRoot'] = str(root)
        if json.loads(manifest_raw) != expected:
            raise LayoutError('layout-payload-mismatch')
        expected_files = set(payload) | {'manifest.json'}
        expected_directories = {str(p).replace('\\', '/') for name in payload
                                for p in Path(name).parents if str(p) != '.'}
        files, directories, pending = set(), set(), [root]
        while pending:
            directory = pending.pop()
            for child in directory.iterdir():
                _plain(child)
                name = child.relative_to(root).as_posix()
                if child.is_dir():
                    directories.add(name)
                    pending.append(child)
                elif child.is_file():
                    files.add(name)
                else:
                    raise LayoutError('layout-payload-mismatch')
        if files != expected_files or directories != expected_directories:
            raise LayoutError('layout-payload-mismatch')
        for name, raw in payload.items():
            if (root / name).read_bytes() != raw:
                raise LayoutError('layout-payload-mismatch')
        return expected
    except LayoutError:
        raise
    except (OSError, ValueError, KeyError, TypeError) as error:
        raise LayoutError('layout-payload-mismatch') from error


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('source-root', 'derived-root', 'identity', 'identity-sha256', 'di-manifest',
                 'di-manifest-sha256', 'output', 'allowed-output-root'):
        parser.add_argument('--' + name, required=True)
    parser.add_argument('--forbid-root', action='append', default=[])
    args = parser.parse_args(argv)
    try:
        result = write_approved_layout(args.source_root, args.derived_root, args.identity, args.identity_sha256,
                                      args.di_manifest, args.di_manifest_sha256, args.output,
                                      allowed_output_root=args.allowed_output_root, forbidden_roots=args.forbid_root)
        manifest = Path(result['outputRoot']) / 'manifest.json'
        print(json.dumps({'manifest': str(manifest), 'sha256': _sha(manifest.read_bytes()),
                          'hostLayoutReady': True, 'layoutReady': False,
                          'runtimeVerified': False, 'installAllowed': False}))
        return 0
    except LayoutError as error:
        print(str(error), file=sys.stderr)
        return 2


if __name__ == '__main__':
    sys.exit(main())
