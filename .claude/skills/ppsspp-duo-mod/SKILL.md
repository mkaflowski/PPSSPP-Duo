---
name: ppsspp-duo-mod
description: Use when building or fixing a PPSSPP Duo second-screen mod for a PSP game (DuoMod, games/*Mod.java, GranTurismoMod, PataponMod, GtaLcsMapMod, LuminesMod, MetalGearAcidMod, JeanneDArcMod, WipeoutPureMod), when reverse engineering a PSP game's memory or disc files for one (signature scans, RAM dumps, texture or archive formats), when testing on the AYN Thor over adb and the WebSocket debugger, or when releasing a new PPSSPP Duo version.
---

# Building a PPSSPP Duo game mod

PPSSPP Duo (github.com/mkaflowski/PPSSPP-Duo, branch `ppsspp-duo`; upstream PPSSPP is a separate
remote) shows a mod on the second screen of dual-screen Android handhelds. Read
`docs/ppsspp-duo.md` first: architecture, the `DuoModContext` API, the existing mods.

This skill lives in `.claude/skills/`, where both Claude Code and OpenCode find it; other agents and
people reach it from `AGENTS.md` and `docs/ppsspp-duo.md`. Keep it the only copy, and add what a
mod taught you here.

## Your setup

The examples below come from the maintainer's AYN Thor on Windows. Look up your own values once:

| What | How to find it | Thor example |
|---|---|---|
| adb | Android SDK `platform-tools` (`%LOCALAPPDATA%\Android\Sdk` on Windows) | |
| Device serial (`adb -s`) | `adb devices` | `5b47bb6` |
| SD card and ROM folder | `adb shell ls /storage` | `/storage/3233-6631/ROMS/psp/` |
| Display IDs for `screencap -d` | `adb shell dumpsys SurfaceFlinger --display-id` | main `4630946441858561667`, second `4630946482288158084` |
| Display ID for `input -d` | `adb shell dumpsys display \| grep mDisplayId` | `4` |
| Second screen size | `dumpsys display`, or a screenshot | 1240x1080 |

`Tools/duo/thor.ps1` takes the app folder on the SD card as `-AppDir`; tap coordinates in this
skill are for a 1240x1080 second screen, so scale or re-measure them on other devices.

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
| `formats/gim.py`, `formats/spf.py` | GIM to PNG (as `GimImage`), Jeanne d'Arc's SPF packs (list, extract) |
| `release.py VERSION notes.md` | GitHub release with the APK |

Screenshots and dumps go to `Tools/duo/out/` (ignored by git).

## Workflow

1. **Identify the game**: game ID and disc version (`DuoStatus.gameId`, `discVersion`). Mods match
   by ID list plus a title fallback in `getPriority()`.
2. **Decide what the second screen shows.** Things the game knows but hides or shows small: a map,
   the next pieces, a hand of cards, telemetry. Look at the in-game HUD and menus for the values.
3. **Find the data in RAM** (below). Prefer things the game itself displays, so they can be checked.
4. **Find the art on the disc** (below) if the mod should look like the game.
5. **Write the mod** (conventions below). Older mods have a plain look and a game look with a button
   to switch (`PataponMod`, `GranTurismoMod`); new ones are drawn in the game's look only
   (`MetalGearAcidMod`, `JeanneDArcMod`, `WipeoutPureMod`), with plain drawing just as the fallback
   when the art can't be read.
6. **Test on the device** against what the game shows, on two different sessions (another track,
   level or save). Take full-size screenshots of the second screen for the README.
7. **Document**: README table row (game, mod, what it shows, tested version) and screenshot (620x540,
   `docs/images/duo/`), `docs/ppsspp-duo.md` entry with the memory layout and file formats.
8. **Share it**: contributors open a pull request against `ppsspp-duo`; releases (below) are the
   maintainer's. Never push without the person you work for asking.

## Running a game on the device

- adb disconnects now and then; wait and retry, don't assume the device state.
- ROMs are wherever the player keeps them (the Thor: `/storage/<sd>/ROMS/psp/`). An adb-started
  `file://` path used to work only inside the app folder: `mv` the ISO to
  `/storage/<sd>/Android/data/org.ppsspp.ppssppduo/files/` for the session and **move it back
  afterwards** (same volume, instant), then `thor.ps1 -Iso <name>`. Launching from the ROM folder
  itself now works too (below).
- `thor.ps1` sets `svc power stayon usb` (the screen sleeps in menus and input stops). Restore with
  `adb shell svc power stayon false` when done.
- Displays: see "Your setup" (`duodbg.displays()` reads them). On the second screen
  `adb shell input -d <input display> tap X Y` taps, in its own pixels.
- `d.press()` returns only after the press, so it times out if the game is paused or the screen is
  off. Menus with animated cursors need `cross:10` and 3 s waits; screenshot after each block of
  steps, menus drift by one step easily.
- Installing a new APK kills the game: batch code changes, then reinstall and drive back in. Write
  the menu path down as a `nav.py` line once it works. Quicker: keep a savestate in slot 1 and load
  it from the Dashboard tab (on a 1240x1080 second screen: `input -d 4 tap 128 56`, then `555 478`).
- States that are hard to reach in the game (many units, a defeated one, a guest) can be faked
  with `memory.write` on the data the mod reads, to test the layouts; load the savestate after.
- Logs: tag `PPSSPPDuo` (`adb logcat -d -s PPSSPPDuo:*`). Log what the mod found (addresses, names)
  once, not per frame. When a mod shows nothing and nothing is logged, add a temporary log every
  2 s of what `onStatus` sees (which watches are null, the fields it checks) rather than guessing.
- Launching straight from the ROM folder also worked (Oct 2026):
  `adb shell "am start -a android.intent.action.VIEW -d 'file:///storage/<sd>/ROMS/psp/<name>.iso' -n org.ppsspp.ppssppduo/org.ppsspp.ppsspp.PpssppActivity"`,
  with spaces, commas and parentheses URL-encoded and the whole command quoted for the device
  shell. Wake the screen first (`input keyevent KEYCODE_WAKEUP`); a black screenshot of the second
  screen means the app isn't running or the device is asleep.
- `adb shell input keyevent KEYCODE_BUTTON_*` doesn't reach the game as pad input. Use the Gamepad
  tab with a hold, `input -d <input display> swipe X Y X Y 300` (a tap is too short for games that
  poll), or the debugger.
- Immersive mode hides the tab bar on game screens; a swipe in from the left or right edge at mid
  height brings it back (not from the top). That swipe can also open PPSSPP's pause menu on the main
  screen, which pauses the game: resume with Continue before judging what the mod shows.
- From Git Bash, prefix adb commands that name device paths with `MSYS_NO_PATHCONV=1`, or
  `/sdcard/...` is rewritten into a Windows path. In PowerShell, `>` turns binary output into
  UTF-16, so `adb exec-out screencap -p > x.png` gives a broken file: screencap to `/sdcard/x.png`,
  `adb pull` it, then `adb shell rm` it.

On a PC, `PPSSPPWindows64.exe` with the remote debugger works the same (port from
`Get-NetTCPConnection`). Never screenshot the desktop (private windows); use `winprint` style
window capture only.

## Driving a game in headless (no device)

When the device is busy or a menu path is long, run the game in `PPSSPPHeadless` and drive it over
the debugger. Build it with `MSBuild Windows\PPSSPP.sln /t:PPSSPPHeadless /p:Configuration=Release
/p:Platform=x64` (`vswhere -latest` can name a Visual Studio without MSBuild; take one from
`vswhere -all` that has `MSBuild\Current\Bin\MSBuild.exe`).

- **Start from the player's savestate**: ask for a state at the moment that matters (a battle, a
  race, the card screen) and what the HUD shows then. `adb pull
  /sdcard/PSP/PPSSPP_STATE/<ID>_<ver>_0.ppst` (slot 1 in the UI is `_0`); a state from the device or
  from a desktop PPSSPP loads in a headless built from this tree.
- `PPSSPPHeadless <iso> --state=<ppst> --debugger-run=<port> --graphics=software
  --memstick=<scratch dir> --timeout-wall=<s>`. With `--debugger` instead of `--debugger-run` it
  waits at the entry point until `cpu.resume`.
- Screenshots (`gpu.buffer.screenshot`) and consistent dumps need the CPU stepping first:
  `cpu.stepping` answers with a broadcast, not with the request's ticket. Take the screenshot and
  the RAM in the same pause, so the HUD you read belongs to the dump you search.
- Hold a button with `input.buttons.send` on one connection; it's released when that connection
  closes (a ship then drifts into a wall). Menus need `input.buttons.press` with `duration` 10-20
  frames and a few seconds between presses. The debugger can't save states: keep one instance
  running across commands, or reload the state.
- System dialogs (savedata, Memory Stick) render without text in headless; cross or circle gets
  through them.
- A game in attract mode after a timeout on the title screen is a real race or level and can
  already be searched.

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
- **Static tables are common**: Jeanne d'Arc's units and WipEout's ships are arrays in the main
  module's data (check with `hle.module.list` that the address is inside the main module), so a
  fixed address per version is fine there. Index fields that run 0..N-1 across the records mark
  where each record starts; a permutation of 1..N is usually the race or turn order.
- **Prove a field by writing it**: `memory.write` a different value and see the HUD follow (MGA's
  life 24 -> 20 showed "20/30"). A field that only correlates may be a copy or a smoothed value.
- What the HUD shows can differ from the stored value: WipEout's speed field is the length of the
  velocity, the HUD shows its forward part, so they differ while scraping a wall.
- Text in RAM (card names, menus) usually comes from a string table of offsets; find the table
  (entries like `0x80000000 | offset`) and the index rule (MGA: name = 8 + card, text = 259 + card),
  then read the same table from the disc instead of RAM.
- Validate against the game's own display, then on a second run, before building UI on it.
- Memory breakpoints (`memory.breakpoint.add read=true`) didn't trip on the Thor (ARM64 JIT), even
  for a fixed address the game reads every frame; diffing dumps works there.

Per frame the host copies up to 16 watched ranges of 16 KB each (`setMemoryWatches`); read them in
`onStatus` with `readMemoryWatch(i)` and re-check the signature fields every time (objects die
when a race ends: then search again). After `setMemoryWatches`, every watch reads null until the
next frame: treat null as "wait", never as "the object is gone" (MGA's mod kept losing the hand
that way). Keep the last picture for a second or two when the data briefly disappears.

## Finding the art on the disc

- `readGameFile("disc0:/PSP_GAME/USRDIR/...", offset, size, cb)` reads up to 1 MB per request, one
  request per frame; chain requests for larger reads (see `GtVolume.readRange`).
- First list the ISO (`Mount-DiskImage` on Windows) and look at headers. Search for existing
  research before reversing a format: Nenkai's tools (GT), community wikis, PDTools. Credit them
  in the class comment and keep the license compatible (MIT is fine).
- Prototype the decoder in Python in `Tools/duo/formats/` against the mounted ISO, check the PNGs,
  then port to Java. Existing decoders: `PataponArt` (BND, GXT), `GimImage` (GIM), `GtVolume`
  (GT.VOL), `Txs3` (TXS3 with swizzle, CLUT4/8, DXT3/5), `GtaRadar`, `MgaArt` (Konami _zar, QAR,
  TXP; prototype in `formats/mga_txp.py`), `JeanneArt` (glyphs and window parts cut from GIM
  atlases).
- A game's HUD lettering (digits, labels) is usually in a GUI atlas: decode them all to PNG, find
  the glyphs, and measure their boxes from the alpha channel (column and row sums), not by eye.
  Drawing a sub-rectangle with filtering samples one pixel beyond it, so leave an empty pixel on
  each side or a sliver of the neighbor shows.
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
- **List what the GPU draws**: scan a RAM dump for words `0xA0xxxxxx` with `0xA8`, `0xB8` (size as
  powers of two) and `0xC3` within a dozen words, and decode each texture with its CLUT. HUD
  textures may sit in VRAM: `memory.read` reads `0x04000000-0x04200000` too. Then find the decoded
  bytes in the disc files to learn which file and offset they come from. That's how WipEout's HUD
  font turned up: its pixels start at +0x80 of the texture, not after the 16-byte header, and the
  game draws it white with alpha = the palette entry's grey level.
- Formats met so far, beyond the decoders above: WipEout's WAD (u32 version, u32 count, 16-byte
  entries of name hash, offset, size, size; no names) and FNT (see `WipeoutFont`); MGA's `_zar`
  (u32 unpacked size, then zlib); Patapon 2 keeps the drum art at `loadinggroup/gamedata.bnd ::
  loadinggroupcmn.bndz :: modellist.bnd :: game.mdll :: int_font00.gxt` (see `PataponArt`).
- A game's own font rarely has non-Latin-1 letters (ą, ż) or `%`: keep labels drawn with it in the
  game's English, like its HUD, and draw translated text (waiting messages) with the system font.
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

- JDK 17 or newer in `JAVA_HOME`, then from the repo root
  `gradlew.bat :android:assembleDuoOptimized` (`./gradlew` elsewhere; on Windows, run it through
  `Start-Process cmd.exe ... -Wait` if your shell tool has a short timeout;
  long builds through the shell tool get killed by its timeout: start without `-Wait`, sleep, read
  the log). Always the `duoOptimized` variant: `duoDebug` runs heavy games at half speed.
- APK: `android/build/outputs/apk/duo/optimized/android-duo-optimized.apk`.
- `gradlew.bat :android:installDuoOptimized` builds and installs on the Thor in one step (only Java
  changed: about 10 s). Installing restarts the app, so the game has to be started and the state
  loaded again.
- Patching files from Git Bash: a heredoc strips one level of backslashes, so Java strings like
  `"\n"` lose theirs; write the patch script with the editor tool and run it. Keep each file's line
  endings (`newline=''`, or replace `\r\n` and write back), see `AGENTS.md`.
- Another session may be working in the same tree: check `git status` and `git log` before
  committing, and commit only the files you changed.

## Releasing (maintainer, only when asked)

1. Bump `duoVersionName` / `duoVersionCode` in `android/build.gradle.kts`, commit "PPSSPP Duo X.Y.Z".
2. `git tag -a duo-vX.Y.Z -m "PPSSPP Duo X.Y.Z"`; tags are `duo-v*` so they don't mix with upstream's.
3. Build, then check the version inside the native lib, and **build again until it matches** (the
   first builds after a tag can still carry the previous `git describe`; 0.7.0 took three): search
   `lib/arm64-v8a/libppsspp_jni.so` in the APK for `duo-vX.Y.Z`, and `aapt dump badging` for
   versionCode/versionName.
4. `git push duo ppsspp-duo:main` and `git push duo duo-vX.Y.Z`.
5. Notes in English (what's new per game, install line, "Based on PPSSPP ... Games are not
   included"), then `python Tools/duo/release.py X.Y.Z notes.md`.
6. Install the release APK on the test device too.

Commits: under your own git identity, no session markers, `Co-Authored-By` is fine. Feature work
on a topic branch is fine; it's merged into `ppsspp-duo` before a release.
