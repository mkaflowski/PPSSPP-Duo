# Finding a game's data in PSP RAM, through the debugger. All reads pause emulation.
#
#   python memscan.py dump ram.bin                        save RAM (0x08800000-0x0A000000)
#   python memscan.py value 1234 [--f32] [--tol 0.5]      every address holding a value (u32/u16/f32)
#   python memscan.py text "MINI COOPER"                  ASCII text anywhere, with context
#   python memscan.py diff a.bin b.bin --f32 [--changed|--same] [--range 0 100]
#   python memscan.py matrices [--near X Z --radius 50]   4x4 matrices with unit rows (objects in the world)
#   python memscan.py motion --hold cross [--secs 1]      matrices ranked by speed while a button is held;
#                                                         compare with the speed the game shows
#   python memscan.py compare ADDR STRIDE COUNT [--size 0x100]
#                                                         N objects side by side, fields that differ marked
#   python memscan.py sig ADDR OFFSET...
#                                                         how many objects match ADDR's u32s at OFFSETs (a
#                                                         signature must match exactly the objects you want)
#
# Rules that saved us from wrong answers:
# - Rank by agreement with what the game displays (speed, money, position on its map), not by how
#   much a value changes. The first "car" in Gran Turismo was some other object with the same pointers.
# - Seed after gameplay starts; heap objects are rebuilt per race / level.
# - Check the result on a second run (another track, another save) before trusting it.
import argparse
import struct
import time

import numpy as np

from duodbg import Dbg, RAM_START, dump


def load(path=None, d=None):
    if path:
        return open(path, "rb").read()
    return dump(d or Dbg())


def matrices(ram):
    f = np.frombuffer(ram, dtype="<f4")
    with np.errstate(all="ignore"):
        m = np.lib.stride_tricks.sliding_window_view(f, 16)
        ok = np.isfinite(m).all(1)
        for r in (0, 4, 8):
            ok &= abs((m[:, r:r + 3] ** 2).sum(1) - 1) < 0.02
        ok &= (abs(m[:, 15] - 1) < 1e-3) & (abs(m[:, 3]) < 1e-3) & (abs(m[:, 7]) < 1e-3) & (abs(m[:, 11]) < 1e-3)
        ok &= (abs(m[:, 12]) < 1e5) & (abs(m[:, 14]) < 1e5) & ((abs(m[:, 12]) > 1) | (abs(m[:, 14]) > 1))
    idx = np.nonzero(ok)[0]
    return [(RAM_START + int(i) * 4, m[i, 12], m[i, 13], m[i, 14]) for i in idx]


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("dump"); p.add_argument("out")
    p = sub.add_parser("value"); p.add_argument("value"); p.add_argument("--f32", action="store_true")
    p.add_argument("--u16", action="store_true"); p.add_argument("--tol", type=float, default=0.01); p.add_argument("--ram")
    p = sub.add_parser("text"); p.add_argument("text"); p.add_argument("--ram")
    p = sub.add_parser("diff"); p.add_argument("a"); p.add_argument("b"); p.add_argument("--f32", action="store_true")
    p.add_argument("--changed", action="store_true"); p.add_argument("--same", action="store_true")
    p.add_argument("--range", type=float, nargs=2)
    p = sub.add_parser("matrices"); p.add_argument("--near", type=float, nargs=2); p.add_argument("--radius", type=float, default=50)
    p.add_argument("--ram")
    p = sub.add_parser("motion"); p.add_argument("--hold", default="cross"); p.add_argument("--secs", type=float, default=1.0)
    p.add_argument("--warmup", type=float, default=3.0)
    p = sub.add_parser("compare"); p.add_argument("addr"); p.add_argument("stride"); p.add_argument("count", type=int)
    p.add_argument("--size", default="0x100")
    p = sub.add_parser("sig"); p.add_argument("addr"); p.add_argument("offsets", nargs="+");
    a = ap.parse_args()

    if a.cmd == "dump":
        open(a.out, "wb").write(load())
        print("saved", a.out)
    elif a.cmd == "value":
        ram = load(a.ram)
        if a.f32:
            arr = np.frombuffer(ram, dtype="<f4")
            with np.errstate(all="ignore"):
                hits = np.nonzero(abs(arr - float(a.value)) <= a.tol)[0] * 4
        else:
            dt = "<u2" if a.u16 else "<u4"
            arr = np.frombuffer(ram[:len(ram) // 4 * 4], dtype=dt)
            hits = np.nonzero(arr == int(a.value, 0))[0] * (2 if a.u16 else 4)
        print(len(hits), "hits")
        for h in hits[:200]:
            print(hex(RAM_START + int(h)))
    elif a.cmd == "text":
        ram = load(a.ram)
        pat = a.text.encode()
        i = ram.find(pat)
        n = 0
        while i >= 0 and n < 50:
            print(hex(RAM_START + i), ram[max(0, i - 32):i + len(pat) + 32])
            i = ram.find(pat, i + 1)
            n += 1
    elif a.cmd == "diff":
        x, y = open(a.a, "rb").read(), open(a.b, "rb").read()
        dt = "<f4" if a.f32 else "<u4"
        xa, ya = np.frombuffer(x, dtype=dt), np.frombuffer(y, dtype=dt)
        with np.errstate(all="ignore"):
            mask = (xa != ya) if a.changed else (xa == ya) if a.same else np.ones(len(xa), bool)
            if a.range:
                mask &= (ya >= a.range[0]) & (ya <= a.range[1])
        idx = np.nonzero(mask)[0]
        print(len(idx), "addresses")
        for i in idx[:200]:
            print(hex(RAM_START + int(i) * 4), xa[i], "->", ya[i])
    elif a.cmd == "matrices":
        for addr, x, y, z in matrices(load(a.ram)):
            if a.near and np.hypot(x - a.near[0], z - a.near[1]) > a.radius:
                continue
            print(hex(addr), "pos %.1f %.1f %.1f" % (x, y, z))
    elif a.cmd == "motion":
        d = Dbg()
        d.buttons(**{a.hold: True})
        time.sleep(a.warmup)
        ram = dump(d)
        before = {m[0]: (m[1], m[3]) for m in matrices(ram)}
        t0 = time.time()
        time.sleep(a.secs)
        d.pause()
        dt = time.time() - t0
        res = []
        for addr, (x0, z0) in before.items():
            x1, _, z1 = struct.unpack("<3f", d.read(addr + 0x30, 12))
            res.append((np.hypot(x1 - x0, z1 - z0) / dt, addr, x1, z1))
        d.resume()
        d.buttons(**{a.hold: False})
        print("units/s (x3.6 for km/h if the game uses metres)")
        for v, addr, x, z in sorted(res, reverse=True)[:60]:
            print(hex(addr), "%.1f/s  %.1f km/h  pos %.1f %.1f" % (v, v * 3.6, x, z))
    elif a.cmd == "compare":
        d = Dbg()
        base, stride, size = int(a.addr, 0), int(a.stride, 0), int(a.size, 0)
        d.pause()
        try:
            objs = [d.read(base + k * stride, size) for k in range(a.count)]
        finally:
            d.resume()
        for o in range(0, size, 4):
            us = [struct.unpack_from("<I", b, o)[0] for b in objs]
            fs = [struct.unpack_from("<f", b, o)[0] for b in objs]
            cells = [("%.2f" % f) if 1e-4 < abs(f) < 1e6 else "%08x" % u for u, f in zip(us, fs)]
            print("%04x %s %s" % (o, "same" if len(set(us)) == 1 else "    ", " ".join(c.rjust(10) for c in cells)))
    elif a.cmd == "sig":
        d = Dbg()
        addr = int(a.addr, 0)
        offs = [int(o, 0) for o in a.offsets]
        vals = [struct.unpack("<I", d.read(addr + o, 4))[0] for o in offs]
        print("signature", [(hex(o), hex(v)) for o, v in zip(offs, vals)])
        ram = dump(d)
        u = np.frombuffer(ram, dtype="<u4")
        cand = np.nonzero(u == vals[0])[0] - offs[0] // 4
        hits = [c for c in cand if c >= 0 and all(c + o // 4 < len(u) and u[c + o // 4] == v for o, v in zip(offs, vals))]
        print(len(hits), "matches:", [hex(RAM_START + int(c) * 4) for c in hits[:40]])


if __name__ == "__main__":
    main()
