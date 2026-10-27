"""Persona (PSP) dungeon maps: pack/dng/dXY/dXYm.bin.

u32 section count, a u32 size per section, then the sections from the next 16-byte boundary:
0x34 bytes, one variable section, a 0xC4-byte section per floor, a 0x4AC-byte section per floor,
then 0xA bytes. A floor's 0x4AC-byte section is a 0x28-byte header and a 24x24 grid of u16 tile
numbers (row by row, 0 = no floor); the tile number is the minimap's piece, which says where the
cell has walls.

  python p1_dngmap.py                 openings per tile number, from every map on the disc
  python p1_dngmap.py dXYm.bin [n]    floor n's grid as text
"""
import collections
import glob
import struct
import sys

ROOT = r"G:\PSP_GAME\USRDIR\pack\dng"


def floors(d):
    n = struct.unpack_from("<I", d, 0)[0]
    sizes = struct.unpack_from("<%dI" % n, d, 4)
    o = (4 + 4 * n + 15) // 16 * 16
    out = []
    for s in sizes:
        if s == 0x4AC:
            out.append(struct.unpack_from("<576H", d, o + 0x28))
        o += s
    return out


def openings():
    stats = collections.defaultdict(lambda: [[0, 0] for _ in range(4)])
    for f in sorted(glob.glob(ROOT + r"\*\d??m.bin")):
        for g in floors(open(f, "rb").read()):
            for r in range(24):
                for c in range(24):
                    v = g[r * 24 + c]
                    if not v:
                        continue
                    for s, (dr, dc) in enumerate(((-1, 0), (0, 1), (1, 0), (0, -1))):
                        rr, cc = r + dr, c + dc
                        nz = 0 <= rr < 24 and 0 <= cc < 24 and g[rr * 24 + cc] != 0
                        stats[v][s][0 if nz else 1] += 1
    return stats


if __name__ == "__main__":
    if len(sys.argv) > 1:
        g = floors(open(sys.argv[1], "rb").read())[int(sys.argv[2]) if len(sys.argv) > 2 else 0]
        for r in range(24):
            print(" ".join(".." if v == 0 else "%02x" % v for v in g[r * 24:r * 24 + 24]))
    else:
        st = openings()
        for v in sorted(st):
            s = st[v]
            sides = "".join("NESW"[i] if s[i][1] == 0 else "." for i in range(4))
            amb = any(s[i][0] and s[i][1] for i in range(4))
            print("%02x %s %s%s" % (v, sides, s, " AMBIG" if amb else ""))
