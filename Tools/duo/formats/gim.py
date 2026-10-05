"""GIM ("MIG.00.1PSP") decoder, the same as GimImage.java.

  python gim.py FILE.GIM [out.png]
"""
import struct
import sys

from PIL import Image


def _walk(d, off, end, planes, depth=0):
    while off + 0x10 <= end and depth < 4:
        typ = struct.unpack_from('<H', d, off)[0]
        size, _, data_off = struct.unpack_from('<III', d, off + 4)
        if size <= 0:
            return
        if typ in (2, 3):
            _walk(d, off + data_off, off + size, planes, depth + 1)
        elif typ in (4, 5) and planes[typ - 4] is None:
            h = off + data_off
            fmt, order, w, hgt, bpp, align = struct.unpack_from('<6H', d, h + 4)
            pitch = ((w * bpp // 8) + max(1, align) - 1) // max(1, align) * max(1, align)
            planes[typ - 4] = dict(fmt=fmt, order=order, w=w, h=hgt, bpp=bpp, pitch=pitch,
                                   data=h + struct.unpack_from('<I', d, h + 0x1C)[0])
        off += size


def _color(d, fmt, o):
    if fmt == 3:
        r, g, b, a = d[o:o + 4]
        return r, g, b, a
    v = struct.unpack_from('<H', d, o)[0]
    if fmt == 0:
        return (v & 31) * 255 // 31, ((v >> 5) & 63) * 255 // 63, ((v >> 11) & 31) * 255 // 31, 255
    if fmt == 1:
        return (v & 31) * 255 // 31, ((v >> 5) & 31) * 255 // 31, ((v >> 10) & 31) * 255 // 31, 255 if v >> 15 else 0
    return (v & 15) * 17, ((v >> 4) & 15) * 17, ((v >> 8) & 15) * 17, (v >> 12) * 17


def decode(d):
    planes = [None, None]
    _walk(d, 0x10, len(d), planes)
    img, pal = planes
    raw = d[img['data']:img['data'] + img['pitch'] * img['h']]
    if img['order'] == 1:
        out = bytearray(len(raw))
        i = 0
        for by in range(0, img['h'] - 7, 8):
            for bx in range(0, img['pitch'] - 15, 16):
                for y in range(8):
                    o = (by + y) * img['pitch'] + bx
                    out[o:o + 16] = raw[i:i + 16]
                    i += 16
        raw = bytes(out)
    colors = None
    if img['fmt'] in (4, 5):
        step = 4 if pal['fmt'] == 3 else 2
        colors = [_color(d, pal['fmt'], pal['data'] + i * step) for i in range(min(pal['w'], 256))]
        colors += [(0, 0, 0, 0)] * (256 - len(colors))
    w, h, p = img['w'], img['h'], img['pitch']
    px = []
    for y in range(h):
        for x in range(w):
            if img['fmt'] == 5:
                px.append(colors[raw[y * p + x]])
            elif img['fmt'] == 4:
                v = raw[y * p + x // 2]
                px.append(colors[(v >> 4) if x & 1 else (v & 15)])
            else:
                px.append(_color(raw, img['fmt'], y * p + x * img['bpp'] // 8))
    im = Image.new('RGBA', (w, h))
    im.putdata(px)
    return im


if __name__ == '__main__':
    im = decode(open(sys.argv[1], 'rb').read())
    print(im.size)
    im.save(sys.argv[2] if len(sys.argv) > 2 else sys.argv[1] + '.png')
