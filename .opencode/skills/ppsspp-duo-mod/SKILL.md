---
name: ppsspp-duo-mod
description: Use when building or fixing a PPSSPP Duo second-screen mod for a PSP game (DuoMod, games/*Mod.java, GranTurismoMod, PataponMod, GtaLcsMapMod, LuminesMod), when reverse engineering a PSP game's memory or disc files for one (signature scans, RAM dumps, texture or archive formats), when testing on the AYN Thor over adb and the WebSocket debugger, or when releasing a new PPSSPP Duo version.
---

# Building a PPSSPP Duo game mod

PPSSPP Duo (repo `F:\Projects\PPSSPP-Duo`, branch `ppsspp-duo`, remote `duo` =
github.com/mkaflowski/PPSSPP-Duo) shows a mod on the second screen of dual-screen Android handhelds.
Read `docs/ppsspp-duo.md` first: architecture, the `DuoModContext` API, the existing mods.

A mod is one Java class in `android/src/org/ppsspp/ppsspp/duo/games/` (generic ones in `duo/mods/`),
registered in `DuoModRegistry.createAll()`. It's compiled into the APK; there are no separate
packages. Nothing from the game is ever shipped: art, fonts and data are read from the player's own
disc at runtime and cached.

Tools live in `Tools/duo/` (Python 3 + `pip install websocket-client pillow numpy`):

| Tool | For |
|---|---|
| `duodbg.py` | Library: `Dbg` (WebSocket debugger), `dump()` (paused RAM), `small('top'/'bottom')` screenshots, `logcat()` |
| `thor.ps1 -Iso X.iso [-Install]` | Start a game on the Thor with the debugger forwarded to `127.0.0.1:45678` |
| `nav.py cross:10 w3 right ...` | Press buttons, wait, screenshot (drive menus) |
| `memscan.py` | `value`, `text`, `diff`, `matrices`, `motion`, `compare`, `sig` searches in RAM |
| `formats/gt_vol.py`, `formats/txs3.py` | Gran Turismo archive and texture decoders (models for new formats) |
| `release.py VERSION notes.md` | GitHub release with the APK |

Screenshots and dumps go to `Tools/duo/out/` (ignored by git).

## Workflow

1. **Identify the game**: game ID and disc version (`DuoStatus.gameId`, `discVersion`). Mods match
   by ID list plus a title fallback in `getPriority()`.
2. **Decide what the second screen shows.** Things the game knows but hides or shows small: a map,
   the next pieces, a hand of cards, telemetry. Look at the in-game HUD and menus for the values.
3. **Find the data in RAM** (below). Prefer things the game itself displays, so they can be checked.
4. **Find the art on the disc** (below) if the mod should look like the game.
5. **Write the mod** (conventions below), with a plain look first; the game look on top of it, with a
   button to switch, like `PataponMod` and `GranTurismoMod`.
6. **Test on the device** against what the game shows, on two different sessions (another track,
   level or save). Take full-size screenshots of the second screen for the README.
7. **Document**: README table row (game, mod, what it shows, tested version) and screenshot (620x540,
   `docs/images/duo/`), `docs/ppsspp-duo.md` entry with the memory layout and file formats.
8. **Release** when asked (below). Never push without the user asking.

## Running a game on the Thor

- adb: `$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe`, device `5b47bb6`. It disconnects
  now and then; wait and retry, don't assume the device state.
- ROMs are in `/storage/3233-6631/ROMS/psp/`. An adb-started `file://` path only works inside the
  app folder: `mv` the ISO to `/storage/3233-6631/Android/data/org.ppsspp.ppssppduo/files/` for the
  session and **move it back afterwards** (same volume, instant). Then `thor.ps1 -Iso <name>`.
- `thor.ps1` sets `svc power stayon usb` (the screen sleeps in menus and input stops). Restore with
  `adb shell svc power stayon false` when done.
- Displays: main `4630946441858561667`, second `4630946482288158084` (`duodbg.displays()` reads them).
  On the second screen `adb shell input -d 4 tap X Y` taps (coordinates in its 1240x1080 pixels).
- `d.press()` returns only after the press, so it times out if the game is paused or the screen is
  off. Menus with animated cursors need `cross:10` and 3 s waits; screenshot after each block of
  steps, menus drift by one step easily.
- Installing a new APK kills the game: batch code changes, then reinstall and drive back in. Write
  the menu path down as a `nav.py` line once it works.
- Logs: tag `PPSSPPDuo` (`adb logcat -d -s PPSSPPDuo:*`). Log what the mod found (addresses, names)
  once, not per frame.

On a PC, `PPSSPPWindows64.exe` with the remote debugger works the same (port from
`Get-NetTCPConnection`). Never screenshot the desktop (private windows); use `winprint` style
window capture only.

## Finding the data in RAM

PSP user RAM is `0x08800000-0x0A000000`. Code and static data of the main module are fixed per
version; game objects are on the heap and move per race/level/boot.

- **Pause while dumping** (`dump(d)` does): a 24 MB read takes seconds and live objects tear.
- **Start from a value the game shows**: speed, money, lap, a name. `memscan.py value 1234`,
  `--f32 --tol`, `text "MINI COOPER"`. Then `diff` two dumps with the value changed.
- **Positions**: `memscan.py matrices` lists 4x4 matrices with unit rows; `motion --hold cross`
  ranks them by speed while a button is held. The player is the one matching the displayed speed,
  not the fastest one (AI cars move too).
- **Objects of one kind** (cars, units): `compare ADDR STRIDE COUNT` shows fields side by side;
  constant fields are signature material, a field that's 0 only for the player identifies it (GT:
  the AI driver pointer at +0x320).
- **Signatures**: `DuoModContext.findMemory(start, end, offsets, values)` finds the first 4-aligned
  match; repeat from `match + 4` to collect several objects. Check with `memscan.py sig ADDR offsets`
  that it matches exactly the objects you want, **on two different sessions**. A weak signature
  silently finds a different object (GT's first car signature matched an unrelated array on another
  track). Main-module pointers (`0x088x`-`0x08Dx`) in objects are good signature values.
- **Files the game loads stay in RAM**: a magic number at a 4-aligned address (GT's race map,
  'GTCM') is a stable anchor, and its bytes can be matched against the disc to learn the file name.
- **Duplicates**: when there are several copies of a block, pick the live one by its values (GT
  telemetry has three copies and which are live varies per race).
- Validate against the game's own display, then on a second run, before building UI on it.

Per frame the host copies up to 16 watched ranges of 16 KB each (`setMemoryWatches`); read them in
`onStatus` with `readMemoryWatch(i)` and re-check the signature fields every time (objects die
when a race ends: then search again).

## Finding the art on the disc

- `readGameFile("disc0:/PSP_GAME/USRDIR/...", offset, size, cb)` reads up to 1 MB per request, one
  request per frame; chain requests for larger reads (see `GtVolume.readRange`).
- First list the ISO (`Mount-DiskImage` on Windows) and look at headers. Search for existing
  research before reversing a format: Nenkai's tools (GT), community wikis, PDTools. Credit them
  in the class comment and keep the license compatible (MIT is fine).
- Prototype the decoder in Python in `Tools/duo/formats/` against the mounted ISO, check the PNGs,
  then port to Java. Existing decoders: `PataponArt` (BND, GXT), `GimImage` (GIM), `GtVolume`
  (GT.VOL), `Txs3` (TXS3 with swizzle, CLUT4/8, DXT3/5), `GtaRadar`, `MgaArt` (Konami _zar, QAR,
  TXP; prototype in `formats/mga_txp.py`).
- PSP texture facts that cost time: swizzle is 16 bytes x 8 rows blocks; rows are padded to the
  power of two above the width in some formats (TXS3) and not in others (MGA's TXP: take the
  stride from the data size); PSP DXT blocks put the colour indices first, then two RGB565 colours,
  then alpha; atlases pack glyphs in fixed cells (measure the cells by transparent gaps, then crop
  one pixel short of the next row).
- **Art that comes out recognisable but noisy, or in the wrong colours, means the decode is
  slightly off**, not that the art is grainy. Ask the GPU: find the decoded pixels in a RAM dump
  (search for the bytes you decompressed), then the display list command `0xA0 | addr & 0xFFFFFF`
  (TEXADDR0); next to it are TEXBUFW0 (0xA8), TEXMODE (0xC2, bit 0 = swizzled), TEXFORMAT (0xC3)
  and CLUTADDR (0xB0/0xB1). Compare that palette with yours (MGA's starts 4 bytes before the
  offset the file gives).
- **Which art belongs to which item**: the game builds the name at runtime. Search RAM for format
  strings (`%03d`), find the code that uses one (its address is loaded as `lui` + `addiu low16`:
  search the code for the `addiu` immediate) and read where the arguments come from. Konami names
  are hashed with StrCode (24 bit: `h = rol5(h) + c`).
- Cache decoded art as PNG under `filesDir/duo/<game>/<gameId>_<discVersion>_<n>/` and bump `<n>`
  when the cache content changes. Mark "not on this disc" (`.none`) only when the disc was read
  fine and the file really isn't there, never after a read or parse error.
- Small PSP art upscales soft: use it at moderate sizes, trim transparent borders, and draw lines,
  maps and bars as vectors in the game's colours.

## Mod conventions

- 4-wide tabs, braces always, comments short (see `AGENTS.md`). English in code and docs; UI
  strings in `android/res/values/duo_strings.xml` and `values-pl/` (Polish).
- `getStatusIntervalMs()`: 50 for fast data, slower otherwise. `invalidate()` only when a shown
  value changed. Allocate nothing per draw except where unavoidable.
- Bottom-right button row (36 dp, 12 dp margin, `DuoUi.button`), state in SharedPreferences:
  e.g. "Classic"/"Game look", "Hide map"/"Show map". Leave that row free of content.
- Everything the mod draws must also work without the art (plain look) and without the data yet
  (waiting text).
- Text that may not fit: wrap it to the box (`drawWrapped` in `GranTurismoMod`).
- Gotcha in Java parsers: `pos += readVarint()` uses the old `pos` (the call's own advance is lost).
  Read into a local first.

## Building

- `$env:JAVA_HOME='C:\Program Files\Java\jdk-17.0.1'`, then from the repo root
  `gradlew.bat :android:assembleDuoOptimized` (run it through `Start-Process cmd.exe ... -Wait`;
  long builds through the shell tool get killed by its timeout: start without `-Wait`, sleep, read
  the log). Always the `duoOptimized` variant: `duoDebug` runs heavy games at half speed.
- APK: `android/build/outputs/apk/duo/optimized/android-duo-optimized.apk`.

## Releasing (only when asked)

1. Bump `duoVersionName` / `duoVersionCode` in `android/build.gradle.kts`, commit "PPSSPP Duo X.Y.Z".
2. `git tag -a duo-vX.Y.Z -m "PPSSPP Duo X.Y.Z"`; tags are `duo-v*` so they don't mix with upstream's.
3. Build **twice**, then check the version inside the native lib (the first build after a tag still
   carries the previous `git describe`): search `lib/arm64-v8a/libppsspp_jni.so` in the APK for
   `duo-vX.Y.Z`, and `aapt dump badging` for versionCode/versionName.
4. `git push duo ppsspp-duo:main` and `git push duo duo-vX.Y.Z`.
5. Notes in English (what's new per game, install line, "Based on PPSSPP ... Games are not
   included"), then `python Tools/duo/release.py X.Y.Z notes.md`.
6. Install the release APK on the Thor too.

Commits: author Mateusz Kaflowski <mkaflowski@gmail.com>, no session markers, `Co-Authored-By` is
fine. Feature work on a topic branch is fine; merge into `ppsspp-duo` before releasing.
