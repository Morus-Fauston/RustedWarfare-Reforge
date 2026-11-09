"""Reproduce the owned landing-craft placeholder; never reads original artwork.

Default: verify every format's decoded pixels. --output writes fresh PNGs to a new directory.
"""
import argparse
import binascii
from pathlib import Path
import struct
import zlib


def pixels(dead=False):
    rows = []
    for y in range(32):
        row = bytearray()
        for x in range(20):
            rgba = (0, 0, 0, 0)
            if 3 <= x <= 16 and 2 <= y <= 29:
                rgba = (80, 90, 80, 255) if dead else ((155, 170, 150, 255) if 6 <= x <= 13 else (55, 100, 75, 255))
            row.extend(rgba)
        rows.append(b'\0' + row)
    return b''.join(rows)


def chunk(kind, data):
    return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', binascii.crc32(kind + data) & 0xffffffff)


def png(dead=False):
    return (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', 20, 32, 8, 6, 0, 0, 0)) +
            chunk(b'IDAT', zlib.compress(pixels(dead))) + chunk(b'IEND', b''))


def verify(file, dead):
    data = file.read_bytes()
    assert data[:8] == b'\x89PNG\r\n\x1a\n'
    position = 8
    compressed = b''
    while position < len(data):
        size, = struct.unpack('>I', data[position:position+4])
        kind, body = data[position+4:position+8], data[position+8:position+8+size]
        assert struct.unpack('>I', data[position+8+size:position+12+size])[0] == binascii.crc32(kind+body) & 0xffffffff
        if kind == b'IHDR':
            assert body == struct.pack('>IIBBBBB', 20, 32, 8, 6, 0, 0, 0)
        if kind == b'IDAT':
            compressed += body
        position += size+12
    assert zlib.decompress(compressed) == pixels(dead), file


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    if args.output:
        args.output.mkdir(parents=True, exist_ok=False)
        for dead in (False, True):
            (args.output/('hovercraft_dead.png' if dead else 'hovercraft.png')).write_bytes(png(dead))
    else:
        root = Path(__file__).resolve().parents[1]/'public/inputs/transport-production'
        for fmt in ('ini', 'json', 'hjson'):
            for dead in (False, True):
                verify(root/fmt/'production-package/units'/('hovercraft_dead.png' if dead else 'hovercraft.png'), dead)
        print('All six owned landing-craft PNGs match the public procedural pixel recipe')


if __name__ == '__main__':
    main()
