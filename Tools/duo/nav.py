# Drives the game through the debugger and takes a screenshot at the end.
#   python nav.py cross start:10 w2 up up cross:10 w5      -> presses (frames after ':'), waits (w<seconds>)
#   python nav.py --shot bottom cross                      -> screenshot of the second screen instead
# Menus that move a cursor with an animation need longer presses (cross:10) and waits between steps.
import sys
import time

from duodbg import Dbg, small

args = sys.argv[1:]
which = "top"
if args[:1] == ["--shot"]:
    which = args[1]
    args = args[2:]
d = Dbg()
for a in args:
    if a.startswith("w") and a[1:].replace(".", "").isdigit():
        time.sleep(float(a[1:]))
        continue
    b, _, f = a.partition(":")
    d.press(b, int(f or 4))
    time.sleep(0.6)
time.sleep(1.5)
small(which)
