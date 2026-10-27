"""Persona (PSP) archives, to PNG.

Two kinds:
- "pairs" (DVLAN.BIN, PTYALL.BIN, E0-E4.BIN): u32 (start, end) pairs from offset 8, files
  in order; a zero pair ends the table.
- "sized" (bt_menu.bin, sys.bin, na.bin): u32 count, u32 size per file, then the files.

  python p1_pack.py ARCHIVE OUTDIR [pairs|sized]
Writes every GIM it finds (also GIMs nested in a file of the archive) as OUTDIR/<n>_<k>.png.
"""
import os
import struct
import sys

from gim import decode

MAGIC = b"MIG.00.1PSP"


def pairs(d):
    out = []
    o = 8
    while o + 8 <= len(d):
        s, e = struct.unpack_from("<II", d, o)
        if s == 0 and e == 0 or e <= s or e > len(d):
            break
        out.append((s, e))
        o += 8
        if o >= out[0][0]:
            break
    return out


def sized(d):
    n = struct.unpack_from("<I", d, 0)[0]
    sizes = struct.unpack_from("<%dI" % n, d, 4)
    o = 4 + 4 * n
    o = (o + 15) // 16 * 16
    out = []
    for s in sizes:
        out.append((o, o + s))
        o += s
    return out


def gims(d):
    i = d.find(MAGIC)
    while i >= 0:
        yield i
        i = d.find(MAGIC, i + 1)


def main():
    path, outdir = sys.argv[1], sys.argv[2]
    kind = sys.argv[3] if len(sys.argv) > 3 else "pairs"
    d = open(path, "rb").read()
    os.makedirs(outdir, exist_ok=True)
    files = pairs(d) if kind == "pairs" else sized(d)
    print(len(files), "files")
    for n, (s, e) in enumerate(files):
        f = d[s:e]
        for k, g in enumerate(gims(f)):
            try:
                im = decode(f[g:])
            except Exception as ex:
                print(n, k, "failed", ex)
                continue
            im.save(os.path.join(outdir, "%03d_%d.png" % (n, k)))
            if n < 3 or k == 0 and n % 50 == 0:
                print(n, k, hex(s + g), im.size)


if __name__ == "__main__":
    main()
