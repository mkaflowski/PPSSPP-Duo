# Polyphony TXS3 texture sets, PSP flavour ('3SXT', little endian), as in Gran Turismo PSP.
# The Java version is android/src/org/ppsspp/ppsspp/duo/games/Txs3.java; layout notes are there.
# After Nenkai's PDTools TextureSet3 / PGLUGETextureInfo (MIT).
#
#   python txs3.py file.img [more files]         decodes every set inside (also embedded ones, e.g. in
#                                                .gpb UI files) to <file>.<n>_<texture name>.png
import struct
import sys

from PIL import Image


def unswizzle(data, row_bytes, height):
    out = bytearray(row_bytes * height)
    row_blocks = row_bytes // 16
    o = 0
    for y in range(height):
        for x in range(row_bytes):
            bx, by = x // 16, y // 8
            src = (bx + by * row_blocks) * 128 + (x - bx * 16) + (y - by * 8) * 16
            out[o] = data[src] if src < len(data) else 0
            o += 1
    return bytes(out)


def color16(v, kind):
    if kind == 2:   # 4444
        return ((v & 15) * 17, (v >> 4 & 15) * 17, (v >> 8 & 15) * 17, (v >> 12) * 17)
    if kind == 1:   # 5551
        return ((v & 31) * 255 // 31, (v >> 5 & 31) * 255 // 31, (v >> 10 & 31) * 255 // 31, 255 if v >> 15 else 0)
    return ((v & 31) * 255 // 31, (v >> 5 & 63) * 255 // 63, (v >> 11) * 255 // 31, 255)   # 565


def textures(d, base=0):
    """[(name, PIL image)] of the set at d[base:]."""
    u32 = lambda o: struct.unpack_from('<I', d, base + o)[0]
    u16 = lambda o: struct.unpack_from('<H', d, base + o)[0]
    reloc = u32(0x08)
    ntex = u16(0x14)
    tex_off, buf_off = u32(0x18) - reloc, u32(0x1C) - reloc
    nclut, clut_off = u16(0x26), u32(0x2C) - reloc
    out = []
    for i in range(ntex):
        t = tex_off + i * 0x98
        tmode = u32(t + 0x20)
        clut_idx = struct.unpack_from('<h', d, base + t + 0x8A)[0]
        buf = buf_off + u16(t + 0x8E) * 0x20
        name_off = u32(t + 0x94)
        name = ''
        if name_off:
            q = base + name_off - reloc
            name = d[q:d.index(b'\0', q)].decode('ascii', 'replace')
        pix, size = u32(buf) - reloc, u32(buf + 4)
        fmt = d[base + buf + 9]
        w, h = u16(buf + 12), u16(buf + 14)
        clut = None
        if 0 <= clut_idx < nclut:
            c = clut_off + clut_idx * 0x0C
            kind, n, coff = d[base + c + 1], u16(c + 2), u32(c + 4) - reloc
            if kind == 3:
                clut = [tuple(d[base + coff + k * 4:base + coff + k * 4 + 4]) for k in range(n)]
            else:
                clut = [color16(struct.unpack_from('<H', d, base + coff + k * 2)[0], kind) for k in range(n)]
        data = d[base + pix:base + pix + size]
        out.append((name, pixels(data, fmt, w, h, tmode & 1, clut)))
    return out


def pixels(data, fmt, w, h, swizzled, clut):
    if fmt in (8, 9, 10):
        return dxt(data, fmt, w, h)
    bpp = {0: 16, 1: 16, 2: 16, 3: 32, 4: 4, 5: 8}[fmt]
    pw = 1
    while pw < w:
        pw *= 2
    row = max(16, pw * bpp // 8)    # rows padded to the GE buffer width
    ph = (h + 7) // 8 * 8
    if swizzled:
        data = unswizzle(data, row, ph)
    img = Image.new('RGBA', (w, h))
    px = img.load()
    for y in range(h):
        for x in range(w):
            if fmt == 5:
                px[x, y] = clut[data[y * row + x] % len(clut)]
            elif fmt == 4:
                v = data[y * row + x // 2]
                px[x, y] = clut[((v >> 4) if x & 1 else (v & 15)) % len(clut)]
            elif fmt == 3:
                px[x, y] = tuple(data[y * row + x * 4:y * row + x * 4 + 4])
            else:
                px[x, y] = color16(struct.unpack_from('<H', data, y * row + x * 2)[0], fmt)
    return img


def dxt(data, fmt, w, h):
    # PSP blocks (as in PPSSPP's decoder): u8 lines[4], u16 color1, u16 color2 (RGB565), then
    # DXT3: u16 alphaLines[4]; DXT5: u32 alpha bits low, u16 alpha bits high, u8 alpha1, u8 alpha2.
    bsize = 8 if fmt == 8 else 16
    bh = (h + 3) // 4
    bw = len(data) // bsize // bh    # stride from the buffer size (not padded)
    img = Image.new('RGBA', (bw * 4, bh * 4))
    px = img.load()
    rgb = lambda v: ((v >> 11) * 255 // 31, (v >> 5 & 63) * 255 // 63, (v & 31) * 255 // 31)
    for bi in range(bw * bh):
        o = bi * bsize
        lines = data[o:o + 4]
        c0, c1 = struct.unpack_from('<HH', data, o + 4)
        p0, p1 = rgb(c0), rgb(c1)
        four = fmt != 8 or c0 > c1
        if four:
            pal = [p0, p1, tuple((2 * a + b) // 3 for a, b in zip(p0, p1)), tuple((a + 2 * b) // 3 for a, b in zip(p0, p1))]
        else:
            pal = [p0, p1, tuple((a + b) // 2 for a, b in zip(p0, p1)), None]
        if fmt == 10:
            lo, hi, a0, a1 = struct.unpack_from('<IHBB', data, o + 8)
            bits = hi << 32 | lo
            ap = [a0, a1] + ([((6 - k) * a0 + (k + 1) * a1) // 7 for k in range(6)] if a0 > a1
                             else [((4 - k) * a0 + (k + 1) * a1) // 5 for k in range(4)] + [0, 255])
        elif fmt == 9:
            al = struct.unpack_from('<4H', data, o + 8)
        bx, by = bi % bw, bi // bw
        for k in range(16):
            xx, yy = k % 4, k // 4
            c = pal[(lines[yy] >> (2 * xx)) & 3]
            if fmt == 10:
                a = ap[(bits >> (3 * k)) & 7]
            elif fmt == 9:
                a = ((al[yy] >> (4 * xx)) & 15) * 17
            else:
                a = 0 if c is None else 255
            px[bx * 4 + xx, by * 4 + yy] = (*(c or (0, 0, 0)), a)
    return img.crop((0, 0, w, h))


def all_sets(d):
    i = d.find(b'3SXT')
    while i >= 0:
        yield i
        i = d.find(b'3SXT', i + 4)


if __name__ == '__main__':
    for path in sys.argv[1:]:
        d = open(path, 'rb').read()
        for n, base in enumerate(all_sets(d)):
            try:
                for k, (name, img) in enumerate(textures(d, base)):
                    out = '%s.%d_%d_%s.png' % (path, n, k, name.replace('/', '_').replace('\\', '_'))
                    img.save(out)
                    print(out, img.size)
            except Exception as e:
                print('%s @%x: %s' % (path, base, e))
