"""Metal Gear Ac!d archives and textures (prototype of MgaArt.java).

  python mga_txp.py G:\\PSP_GAME\\USRDIR\\stage\\init\\_zar out_dir
writes every card illustration of resident.qar as out_dir/cd_<type><nnn>.png.

_zar: u32 unpacked size, zlib. Entries: name\\0 padded to 4, u32 size, 12 bytes, data.
QAR: files back to back (aligned to 128), then u32 count, count x (u32 hash, u32 size), names.
TXP: +0x04 textures, +0x0C sprites, +0x10 sprite table; textures at +0x1C (16 bytes: w, log2 bw<<4,
h, log2 bh<<4, u16 format 4/5 = CLUT4/8, u16 flags 2 = zlib, pixels, palette). Pixels swizzled,
rows w*bpp/8 bytes; the RGBA8888 palette starts 4 bytes before its stated offset. Sprites 0x30
bytes: +0x00 StrCode of the name, +0x10 0x20 + 16 * texture index.
"""
import os
import struct
import sys
import zlib

from PIL import Image

TYPES = ['ch', 'it', 'sp', 'ac', 'wp']


def u32(d, o):
    return struct.unpack_from('<I', d, o)[0]


def strcode(s):
    h = 0
    for c in s.encode():
        h = ((h >> 19) | (h << 5)) & 0xFFFFFF
        h = (h + c) & 0xFFFFFF
    return h


def zar_entry(u, name):
    i = u.index(name.encode() + b'\0')
    p = (i + len(name) + 1 + 3) & ~3
    return u[p + 16:p + 16 + u32(u, p)]


def qar_files(q):
    for p in range((len(q) - 8) & ~3, 0, -4):
        n = u32(q, p)
        if 0 < n < 4096 and p + 4 + n * 8 < len(q):
            sizes = [u32(q, p + 8 + i * 8) for i in range(n)]
            if abs(sum((s + 127) & ~127 for s in sizes) - p) < 0x400:
                names = q[p + 4 + n * 8:].split(b'\0')[:n]
                out, off = {}, 0
                for name, size in zip(names, sizes):
                    out[name.decode()] = q[off:off + size]
                    off = (off + size + 127) & ~127
                return out
    raise ValueError('no QAR table')


def unswizzle(raw, row, h):
    out = bytearray(row * h)
    src = 0
    for by in range((h + 7) // 8):
        for bx in range(max(1, row // 16)):
            for y in range(8):
                r = by * 8 + y
                if r < h:
                    out[r * row + bx * 16:r * row + bx * 16 + 16] = raw[src:src + 16]
                src += 16
    return bytes(out)


def texture(d, i):
    e = 0x1C + i * 16
    w, h = d[e], d[e + 2]
    fmt, flags, pix, clut = struct.unpack_from('<HHII', d, e + 4)
    bpp = 4 if fmt == 4 else 8
    row = w * bpp // 8
    raw = zlib.decompress(d[pix:clut]) if flags & 2 else d[pix:pix + row * h]
    px = unswizzle(raw, row, h)
    clut -= 4
    pal = [tuple(d[clut + k * 4:clut + k * 4 + 4]) for k in range(1 << bpp)]
    img = Image.new('RGBA', (w, h))
    img.putdata([pal[px[y * row + x]] if bpp == 8 else pal[(px[y * row + x // 2] >> (4 * (x & 1))) & 15]
                 for y in range(h) for x in range(w)])
    return img


def sprites(d):
    n, table = u32(d, 0x0C), u32(d, 0x10)
    return {u32(d, table + i * 0x30): (u32(d, table + i * 0x30 + 0x10) - 0x20) // 16 for i in range(n)}


if __name__ == '__main__':
    zar = open(sys.argv[1], 'rb').read()
    files = qar_files(zar_entry(zlib.decompress(zar[4:]), 'resident.qar'))
    os.makedirs(sys.argv[2], exist_ok=True)
    for t in TYPES:
        d = files['cd_illust%s.txp' % t]
        sp = sprites(d)
        for num in range(1, 256):
            ti = sp.get(strcode('cd_%s%03d_alp_ovl' % (t, num)))
            if ti is not None:
                texture(d, ti).save(os.path.join(sys.argv[2], 'cd_%s%03d.png' % (t, num)))
