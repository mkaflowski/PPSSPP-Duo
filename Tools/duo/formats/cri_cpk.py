"""CRI CPK archives (Persona 3 Portable's umd0.cpk, many other PSP games): list and extract, with
CRILAYLA decompression. The same reader as in Persona3Art.java.

  python cri_cpk.py ARCHIVE.cpk                 list DirName/FileName, stored and extracted size
  python cri_cpk.py ARCHIVE.cpk SUBSTRING OUT   extract the files whose path contains SUBSTRING
"""
import os
import struct
import sys


def _decrypt(b):
    # Some games XOR their @UTF tables; P3P's aren't.
    if b[:4] == b'@UTF':
        return b
    out = bytearray(len(b))
    m = 0x655F
    for i, c in enumerate(b):
        out[i] = c ^ (m & 0xFF)
        m = (m * 0x4115) & 0xFFFFFFFF
    return bytes(out)


def _value(b, p, t, string, data):
    if t in (0, 1):
        return b[p], p + 1
    if t in (2, 3):
        return struct.unpack('>H', b[p:p + 2])[0], p + 2
    if t in (4, 5):
        return struct.unpack('>I', b[p:p + 4])[0], p + 4
    if t in (6, 7):
        return struct.unpack('>Q', b[p:p + 8])[0], p + 8
    if t == 8:
        return struct.unpack('>f', b[p:p + 4])[0], p + 4
    if t == 0xA:
        return string(struct.unpack('>I', b[p:p + 4])[0]), p + 4
    if t == 0xB:
        o, s = struct.unpack('>II', b[p:p + 8])
        return (data + o, s), p + 8
    raise ValueError(t)


def parse_utf(b):
    """Rows of an @UTF table as dicts. Columns: flags (0x30 constant, 0x50 per row, low nibble
    the type), name offset; big-endian throughout."""
    assert b[:4] == b'@UTF', b[:4]
    _, rows_off, str_off, data_off, _, ncols, row_w, nrows = struct.unpack('>IIIIIHHI', b[4:32])
    base = 8

    def string(o):
        s = base + str_off + o
        return b[s:b.index(b'\0', s)].decode('utf-8', 'replace')

    p, cols = 32, []
    for _ in range(ncols):
        flags = b[p]
        name = string(struct.unpack('>I', b[p + 1:p + 5])[0])
        p += 5
        const = None
        if flags & 0xF0 == 0x30:
            const, p = _value(b, p, flags & 0xF, string, base + data_off)
        cols.append((name, flags, const))
    rows = []
    for r in range(nrows):
        p, row = base + rows_off + r * row_w, {}
        for name, flags, const in cols:
            if flags & 0xF0 == 0x50:
                row[name], p = _value(b, p, flags & 0xF, string, base + data_off)
            else:
                row[name] = const
        rows.append(row)
    return rows


def _chunk(f, off):
    f.seek(off)
    hdr = f.read(16)
    return _decrypt(f.read(struct.unpack('<I', hdr[8:12])[0]))


def toc(path):
    f = open(path, 'rb')
    cpk = parse_utf(_chunk(f, 0))[0]
    files = parse_utf(_chunk(f, cpk['TocOffset']))
    for r in files:
        # Offsets count from the TOC (from the content when that comes first).
        r['abs'] = min(cpk['TocOffset'], cpk['ContentOffset'] or cpk['TocOffset']) + r['FileOffset']
    return f, files


def crilayla(src):
    """'CRILAYLA', u32 size, u32 compressed size, the compressed part, then the first 0x100 bytes
    raw. Decodes backwards, reading the bits backwards: 1 = copy (13-bit distance + 3, length 3 + 2,
    3, 5 then 8-bit chunks while all ones), 0 = literal byte."""
    usize, hsize = struct.unpack('<II', src[8:16])
    out = bytearray(usize + 0x100)
    out[:0x100] = src[16 + hsize:16 + hsize + 0x100]
    comp = src[16:16 + hsize]
    state = {'pos': len(comp) - 1, 'pool': 0, 'bits': 0}

    def get(n):
        v = 0
        while n:
            if state['bits'] == 0:
                state['pool'] = comp[state['pos']] if state['pos'] >= 0 else 0
                state['pos'] -= 1
                state['bits'] = 8
            take = min(state['bits'], n)
            v = (v << take) | ((state['pool'] >> (state['bits'] - take)) & ((1 << take) - 1))
            state['bits'] -= take
            n -= take
        return v

    w = len(out) - 1
    lens = [2, 3, 5, 8]
    while w >= 0x100:
        if get(1):
            off = w + get(13) + 3
            ln, i = 3, 0
            while True:
                v = get(lens[min(i, 3)])
                ln += v
                if v != (1 << lens[min(i, 3)]) - 1:
                    break
                i += 1
            for _ in range(ln):
                if w < 0x100:
                    break
                out[w] = out[off]
                w -= 1
                off -= 1
        else:
            out[w] = get(8)
            w -= 1
    return bytes(out)


def extract(f, r):
    f.seek(r['abs'])
    d = f.read(r['FileSize'])
    return crilayla(d) if d[:8] == b'CRILAYLA' else d


if __name__ == '__main__':
    f, files = toc(sys.argv[1])
    for r in files:
        name = f"{r.get('DirName') or ''}/{r['FileName']}".lstrip('/')
        if len(sys.argv) == 2:
            print(name, r['FileSize'], r['ExtractSize'])
        elif sys.argv[2] in name:
            out = os.path.join(sys.argv[3], name.replace('/', '__'))
            open(out, 'wb').write(extract(f, r))
            print(out)
