"""Jeanne d'Arc SPF packs ('SFP\\0'): list or extract.

  python spf.py FILE.SPF [out_dir]

Header 0x20 bytes (+0x10: start of the data); then 16-byte entries (u32 name offset, u32 size,
u32 data offset / 16 from the start of the data, 0) up to the first name; names are NUL-terminated.
"""
import os
import struct
import sys


def entries(d):
    assert d[:4] == b'SFP\0', d[:4]
    first = struct.unpack_from('<I', d, 0x20)[0]
    base = struct.unpack_from('<I', d, 0x10)[0]
    out = []
    for e in range(0x20, first, 16):
        name_off, size, off16, _ = struct.unpack_from('<4I', d, e)
        end = d.index(b'\0', name_off)
        out.append((d[name_off:end].decode('ascii', 'replace'), base + off16 * 16, size))
    return out


if __name__ == '__main__':
    d = open(sys.argv[1], 'rb').read()
    for name, off, size in entries(d):
        print('%-24s %8x %7d %s' % (name, off, size, d[off:off + 8]))
        if len(sys.argv) > 2:
            os.makedirs(sys.argv[2], exist_ok=True)
            open(os.path.join(sys.argv[2], name), 'wb').write(d[off:off + size])
