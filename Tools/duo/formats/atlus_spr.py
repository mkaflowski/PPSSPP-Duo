"""Atlus PSP textures (Persona 3 Portable): packs of SPR0 sprite files holding TMX0 textures. The
same decoder as in Persona3Art.java.

  python atlus_spr.py PACK.bin OUTDIR     every texture of every SPR0 in a name[32] + size pack
"""
import os
import struct
import sys

from PIL import Image


def pack_entries(d):
    """u32 count, then name[32], u32 size, data, each 4-aligned: [(name, data offset, size)]."""
    out, p = [], 4
    for _ in range(struct.unpack_from('<I', d, 0)[0]):
        name = d[p:p + 32].split(b'\0')[0].decode('latin1')
        size = struct.unpack_from('<I', d, p + 32)[0]
        out.append((name, p + 36, size))
        p = (p + 36 + size + 3) & ~3
    return out


def tmx(d, o):
    """TMX0 at o ('TMX0' at +8): (image, comment). +0x10 u8 palettes, u8, u16 w, u16 h, u8 format
    (0x13 8 bpp, 0x14 4 bpp, 0 RGBA), comment at +0x24, palette then linear pixels from +0x40.
    Alpha is 0..0x80; 256-colour palettes are in the PS2 order (8-15 and 16-23 of every 32 swapped)."""
    assert d[o + 8:o + 12] == b'TMX0'
    w, h = struct.unpack_from('<HH', d, o + 0x12)
    fmt = d[o + 0x16]
    comment = d[o + 0x24:o + 0x40].split(b'\0')[0].decode('latin1', 'replace')
    p = o + 0x40
    im = Image.new('RGBA', (w, h))
    px = im.load()
    if fmt in (0x13, 0x14):
        n = 256 if fmt == 0x13 else 16
        raw = [struct.unpack_from('<BBBB', d, p + i * 4) for i in range(n)]
        pal = []
        for i in range(n):
            j = (i & 0xE7) | ((i & 8) << 1) | ((i & 0x10) >> 1) if n == 256 else i
            r, g, b, a = raw[j]
            pal.append((r, g, b, min(255, a * 2)))
        p += n * 4
        for y in range(h):
            for x in range(w):
                if n == 256:
                    px[x, y] = pal[d[p + y * w + x]]
                else:
                    v = d[p + (y * w + x) // 2]
                    px[x, y] = pal[v >> 4 if x & 1 else v & 15]
    else:
        for y in range(h):
            for x in range(w):
                r, g, b, a = d[p + (y * w + x) * 4:p + (y * w + x) * 4 + 4]
                px[x, y] = (r, g, b, min(255, a * 2))
    return im, comment


def spr_textures(d, o):
    """SPR0 at o: u16 texture count at +0x14, u32 table at +0x18, 8-byte entries (offset second)."""
    assert d[o + 8:o + 12] == b'SPR0'
    count, table = struct.unpack_from('<H', d, o + 0x14)[0], struct.unpack_from('<I', d, o + 0x18)[0]
    return [tmx(d, o + struct.unpack_from('<I', d, o + table + i * 8 + 4)[0]) for i in range(count)]


if __name__ == '__main__':
    data = open(sys.argv[1], 'rb').read()
    os.makedirs(sys.argv[2], exist_ok=True)
    for name, off, size in pack_entries(data):
        if data[off + 8:off + 12] != b'SPR0':
            continue
        for k, (img, com) in enumerate(spr_textures(data, off)):
            img.save(os.path.join(sys.argv[2], f'{name}_{k}_{com}.png'))
            print(name, k, img.size, com)
