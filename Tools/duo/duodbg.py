# Helpers for building PPSSPP Duo mods: the WebSocket debugger, adb screenshots of both screens,
# and paused RAM dumps. Used by the other scripts in this folder.
#
#   from duodbg import *
#   d = Dbg()                      # ws://127.0.0.1:45678/debugger (adb forward tcp:45678 tcp:45678)
#   d.press('cross'); d.buttons(cross=True)
#   ram = dump(d)                  # 24 MB of PSP RAM from 0x08800000, emulation paused meanwhile
#   small('bottom')                # screenshot of the second screen, 640 wide, unique file name
#
# Needs: pip install websocket-client pillow numpy
import base64
import itertools
import json
import os
import random
import re
import subprocess
import time

import websocket

ADB = os.path.expandvars(r"%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe") if os.name == "nt" else "adb"
OUT = os.environ.get("DUO_OUT", os.path.join(os.path.dirname(os.path.abspath(__file__)), "out"))
RAM_START, RAM_END = 0x08800000, 0x0A000000


def sh(cmd):
    return subprocess.run([ADB, "shell", cmd], capture_output=True, text=True).stdout


class Dbg:
    def __init__(self, port=45678, timeout=30):
        self.ws = websocket.create_connection(f"ws://127.0.0.1:{port}/debugger", timeout=timeout)
        self.tickets = itertools.count(1)

    def call(self, event, **params):
        t = str(next(self.tickets))
        self.ws.send(json.dumps(dict(event=event, ticket=t, **params)))
        while True:
            r = json.loads(self.ws.recv())
            if r.get("ticket") == t or (r.get("event") == event and "ticket" not in r):
                if r.get("event") == "error":
                    raise RuntimeError(r.get("message"))
                return r

    # Answers only after the press is over, so it times out while the game isn't running.
    def press(self, button, frames=4):
        return self.call("input.buttons.press", button=button, duration=frames)

    def buttons(self, **state):
        return self.call("input.buttons.send", buttons=state)

    def analog(self, x, y, stick="left"):
        return self.call("input.analog.send", stick=stick, x=x, y=y)

    def read(self, addr, size):
        out = bytearray()
        while size > 0:
            n = min(size, 0x100000)
            out += base64.b64decode(self.call("memory.read", address=addr, size=n)["base64"])
            addr += n
            size -= n
        return bytes(out)

    def pause(self):
        self.call("cpu.stepping")

    def resume(self):
        self.call("cpu.resume")


# Whole user RAM. Pause, or objects move while the 24 MB are read and you get torn values.
def dump(d, paused=True, start=RAM_START, end=RAM_END):
    if paused:
        d.pause()
    try:
        return d.read(start, end - start)
    finally:
        if paused:
            d.resume()


_displays = None


# Physical display ids for screencap, main screen first. Order is from SurfaceFlinger; check once
# with both screenshots on a new device.
def displays():
    global _displays
    if _displays is None:
        out = subprocess.run([ADB, "shell", "dumpsys", "SurfaceFlinger", "--display-id"], capture_output=True, text=True).stdout
        _displays = re.findall(r"Display (\d+)", out)
    return _displays


def shot(which="top", name=None):
    ids = displays()
    disp = ids[0] if which in ("top", "main") else ids[-1]
    os.makedirs(OUT, exist_ok=True)
    # Unique names: image viewers (and the agent's file reader) cache by path.
    name = name or f"{which}_{random.randint(0, 1 << 30)}.png"
    subprocess.run([ADB, "shell", "screencap", "-p", "-d", disp, "/sdcard/duo_shot.png"], check=True)
    path = os.path.join(OUT, name)
    subprocess.run([ADB, "pull", "/sdcard/duo_shot.png", path], check=True, capture_output=True)
    subprocess.run([ADB, "shell", "rm", "/sdcard/duo_shot.png"], capture_output=True)
    return path


def small(which="top", w=640):
    from PIL import Image
    p = shot(which)
    im = Image.open(p)
    im = im.resize((w, int(im.height * w / im.width)))
    out = p.replace(".png", "_s.png")
    im.save(out)
    print(out)
    return out


def logcat(tag="PPSSPPDuo", last=20):
    out = subprocess.run([ADB, "logcat", "-d", "-s", f"{tag}:*"], capture_output=True, text=True).stdout
    return out.splitlines()[-last:]


def wake():
    sh("input keyevent KEYCODE_WAKEUP")
