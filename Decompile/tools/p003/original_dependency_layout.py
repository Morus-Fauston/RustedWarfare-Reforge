"""Read-only, identity-pinned diagnostic of original-only carrier dependencies.

This tool does not normalize loader keys, copy resources, approve path rewrites,
or certify a runtime layout. Relative asset and private file routes remain distinct.
"""
import argparse
import base64
import hashlib
import json
from pathlib import Path
import stat
import struct
import sys
import xml.etree.ElementTree as ET


class LayoutError(ValueError):
    pass


def _sha(raw):
    return hashlib.sha256(raw).hexdigest()


def _plain(value):
    path = Path(value)
    if not path.is_absolute() or '..' in path.parts:
        raise LayoutError('unsafe-input-path')
    for part in (path, *path.parents):
        try:
            attr = part.lstat()
        except FileNotFoundError:
            continue
        if stat.S_ISLNK(attr.st_mode) or getattr(attr, 'st_file_attributes', 0) & 0x400:
            raise LayoutError('redirected-input')
    return path


def _name(value):
    if (not isinstance(value, str) or not value or '\\' in value or ':' in value
            or any(p in ('', '.', '..') for p in value.split('/'))):
        raise LayoutError('invalid-manifest')
    return value


def _read(path, entry):
    try:
        raw = _plain(path).read_bytes()
    except OSError as error:
        raise LayoutError('missing-dependency') from error
    if len(raw) != entry['bytes'] or _sha(raw) != entry['sha256'].lower():
        raise LayoutError('identity-mismatch')
    return raw


def _xml(raw):
    if b'<!DOCTYPE' in raw.upper() or b'<!ENTITY' in raw.upper():
        raise LayoutError('unsupported-xml')
    return ET.fromstring(raw)


def _ini_image(raw):
    section = None
    images = []
    for text in raw.decode('utf-8-sig').splitlines():
        text = text.strip()
        if not text or text.startswith('#'):
            continue
        if text.startswith('['):
            section = text[1:-1]
        elif section == 'graphics' and text.split(':', 1)[0].strip() == 'image':
            images.append(text.split(':', 1)[1].strip())
    if len(images) != 1:
        raise LayoutError('unsupported-image-reference')
    return images[0]


def _guard_code(key):
    # Lexical prediction only; real Java public-interface controls accompany the report.
    if (not key or key.startswith('/') or '\\' in key or ':' in key or '\0' in key
            or any(p in ('', '.', '..') for p in key.split('/'))):
        return 'invalid-path'
    return None


def audit_layout(source_root, derived_root, identity_path, identity_sha256,
                 manifest_path, manifest_sha256):
    """Verify pinned originals/carrier bytes and expose unnormalized loader routes.

    Successful diagnostics deliberately never set layoutReady. Actual asset staging,
    private scanning, runtime initialization and other required fields are unproved.
    """
    try:
        source, derived = _plain(source_root), _plain(derived_root)
        identity_raw = _plain(identity_path).read_bytes()
        manifest_raw = _plain(manifest_path).read_bytes()
        if _sha(identity_raw) != identity_sha256 or _sha(manifest_raw) != manifest_sha256:
            raise LayoutError('manifest-identity-mismatch')
        identity, manifest = json.loads(identity_raw), json.loads(manifest_raw)
        if (identity['schemaVersion'] != 1 or manifest['schemaVersion'] != 1
                or manifest['kind'] != 'original-only-derived-inputs'):
            raise LayoutError('invalid-manifest')
        entries = identity['files']
        names = [_name(e['path']) for e in entries]
        outputs = manifest['outputs']
        output_names = [_name(e['path']) for e in outputs]
        expected_names = [identity['map'], identity['unit'], *identity['copy']]
        if (len(names) != len(set(n.casefold() for n in names)) or len(outputs) != 4
                or len(set(output_names)) != 4 or set(output_names) != set(expected_names)
                or len(identity['copy']) != 2):
            raise LayoutError('invalid-manifest')
        original = {}
        inputs = []
        for entry in entries:
            path = _plain(entry.get('sourcePath', source / entry['path']))
            raw = _read(path, entry)
            original[entry['path']] = raw
            inputs.append({'path': str(path), 'bytes': len(raw), 'sha256': _sha(raw), 'role': entry.get('role')})
        carrier = {entry['path']: _read(derived / entry['path'], entry) for entry in outputs}
        for name in identity['copy']:
            if carrier[name] != original[name]:
                raise LayoutError('unapproved-content-change')
        unit_name, map_name = identity['unit'], identity['map']
        newline = b'\r\n' if b'\r\n' in original[unit_name] else b'\n'
        prefix = original[unit_name]
        suffix = (b'' if prefix.endswith(b'\n') else newline) + newline + b'[attack]' + newline + b'canAttack: false' + newline
        if carrier[unit_name] != prefix + suffix:
            raise LayoutError('unapproved-content-change')
        src_map, dst_map = _xml(original[map_name]), _xml(carrier[map_name])
        width, height = int(src_map.attrib['width']), int(src_map.attrib['height'])
        src_ground = src_map.findall("layer[@name='Ground']/data")
        dst_ground = dst_map.findall("layer[@name='Ground']/data")
        dst_items = dst_map.findall("layer[@name='Items']/data")
        if (len(src_ground) != 1 or len(dst_ground) != 1 or len(dst_items) != 1
                or width <= 0 or height <= 0 or width * height > 1000000):
            raise LayoutError('carrier-map-mismatch')
        gids = [int(s.strip()) for s in src_ground[0].text.strip().split(',')]
        def decoded(node):
            if node.attrib != {'encoding': 'base64'}:
                raise LayoutError('carrier-map-mismatch')
            raw = base64.b64decode(node.text.strip(), validate=True)
            if len(raw) != width * height * 4:
                raise LayoutError('carrier-map-mismatch')
            return list(struct.unpack('<' + 'I' * (width * height), raw))
        ground, items = decoded(dst_ground[0]), decoded(dst_items[0])
        if (gids != ground or len(gids) != width * height or any(items)
                or manifest['map']['gids'] != ground or manifest['map']['width'] != width
                or manifest['map']['height'] != height or dst_map.attrib != src_map.attrib):
            raise LayoutError('carrier-map-mismatch')
        items_layers = dst_map.findall("layer[@name='Items']")
        if (len(items_layers) != 1 or items_layers[0].attrib !=
                {'name': 'Items', 'width': str(width), 'height': str(height)}
                or len(items_layers[0]) != 1 or len(dst_items[0]) != 0):
            raise LayoutError('unapproved-content-change')
        dst_map.remove(items_layers[0])
        dst_ground[0].attrib.clear()
        dst_ground[0].attrib.update(src_ground[0].attrib)
        dst_ground[0].text = src_ground[0].text
        def structure(node):
            return (node.tag, node.attrib, (node.text or '').strip(),
                    [(structure(child), (child.tail or '').strip()) for child in node])
        if structure(src_map) != structure(dst_map):
            raise LayoutError('unapproved-content-change')
        dependencies = {Path(e.get('sourcePath', e['path'])).name: e for e in entries if e.get('role') == 'dependency'}
        if len(dependencies) != 3:
            raise LayoutError('invalid-dependency-set')
        references = []
        for node in dst_map.findall('tileset'):
            reference = node.attrib['source']
            matches = [name for name in identity['copy'] if Path(name).name == reference.rsplit('/', 1)[-1]]
            if len(matches) != 1:
                raise LayoutError('unmapped-reference')
            key = 'tilesets/' + reference
            references.append({'kind': 'tsx', 'owner': map_name, 'reference': reference,
                               'rawKey': key, 'guardCode': _guard_code(key), 'input': matches[0],
                               'route': 'asset', 'staged': False})
        if len(references) != 2:
            raise LayoutError('unmapped-reference')
        for name in identity['copy']:
            images = _xml(carrier[name]).findall('image')
            if len(images) != 1:
                raise LayoutError('unsupported-image-reference')
            reference = images[0].attrib['source'].strip()
            leaf = reference.replace('\\', '/').rsplit('/', 1)[-1]
            if leaf not in dependencies:
                raise LayoutError('unmapped-reference')
            key = 'tilesets/bitmaps/' + leaf
            references.append({'kind': 'terrain-image', 'owner': name, 'reference': reference,
                               'rawKey': key, 'guardCode': _guard_code(key),
                               'input': dependencies[leaf]['path'], 'route': 'asset', 'staged': False})
        reference = _ini_image(carrier[unit_name])
        leaf = reference.replace('\\', '/').rsplit('/', 1)[-1]
        if leaf not in dependencies or reference.startswith(('ROOT:', 'CORE:', 'SHARED:', 'SHADOW:')):
            raise LayoutError('unsupported-image-reference')
        key = unit_name.rsplit('/', 1)[0] + '/' + reference
        references.append({'kind': 'unit-image', 'owner': unit_name, 'reference': reference,
                           'rawKey': key, 'guardCode': _guard_code(key),
                           'input': dependencies[leaf]['path'], 'route': 'private-under-root', 'staged': False})
        return {'schemaVersion': 1, 'kind': 'original-dependency-layout-diagnostic',
                'layoutReady': False, 'runtimeVerified': False, 'installAllowed': False,
                'identitySha256': _sha(identity_raw), 'diManifestSha256': _sha(manifest_raw),
                'inputs': inputs, 'derived': outputs, 'references': references,
                'cells': [{'index': i, 'x': i % width, 'y': i // width, 'originalGid': g,
                           'derivedGid': ground[i], 'itemsGid': items[i]} for i, g in enumerate(gids)],
                'unverified': ['asset-staging', 'private-unit-scanning', 'other-required-unit-fields',
                               'runtime-initialization', 'gameplay-equivalence']}
    except LayoutError:
        raise
    except (KeyError, TypeError, ValueError, OSError, ET.ParseError, AttributeError) as error:
        raise LayoutError('invalid-input') from error


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('source-root', 'derived-root', 'identity', 'identity-sha256', 'manifest', 'manifest-sha256'):
        parser.add_argument('--' + name, required=True)
    args = parser.parse_args(argv)
    try:
        result = audit_layout(args.source_root, args.derived_root, args.identity, args.identity_sha256,
                              args.manifest, args.manifest_sha256)
        print(json.dumps(result, ensure_ascii=True, indent=2))
        return 0
    except LayoutError as error:
        print(str(error), file=sys.stderr)
        return 2


if __name__ == '__main__':
    sys.exit(main())
