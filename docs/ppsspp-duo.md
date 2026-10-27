# PPSSPP Duo (dual-screen Android build)

`duo` flavor of the Android port (`org.ppsspp.ppssppduo`, arm64 only) for dual-screen handhelds
such as the AYN Thor (main panel 1920x1080, second panel "Screen-2" 1240x1080 with its own touch).

Build: `gradlew :android:assembleDuoOptimized` (JDK 17+ to start Gradle) for playing. The
`duoDebug` variant builds the native code without optimizations: fine for light games, but heavy
ones run at about half speed (GTA LCS: 56% vs 100% in the same scene on the AYN Thor). Version: `duoVersionName` /
`duoVersionCode` in `android/build.gradle.kts`.

## Architecture

```
PpssppActivity ── DuoManager ── DuoPresentation (android.app.Presentation on the 2nd display)
                     │              ├─ tab bar (one tab per mod) + settings page
                     │              └─ active DuoMod (Android views)
                     └─ DuoDisplayPicker
DuoNative (JNI) ── android/jni/DuoBridge.cpp ── DuoBridge_OnFrame() on the emu thread
```

- The game keeps its single surface; the second screen is plain Android views, so it never slows
  the emulator down and keeps working while emulation is paused, in menus or loading.
- `DuoManager`: shown between `onStart` and `onStop` (survives `onPause`). Follows display hotplug
  and panel on/off with a debounced refresh. Displays are ranked by flags (user choice,
  `FLAG_PRESENTATION`, `FLAG_SECURE`), no device names.
- `DuoPresentation`: `FLAG_NOT_FOCUSABLE` so touching the second screen doesn't take input focus
  from the game (verified on the Thor: focus stays on display 0). Every mod callback is wrapped, a
  failing mod is logged and replaced by an error view.
- `DuoBridge.cpp`: input from mods is queued and applied on the emu thread after `NativeFrame()`
  (through `ControlMapper::PSPKey`, so the pause menu etc. gate it like touch controls). Presses
  older than 0.5 s are dropped; releases never are. Status, button presses and memory watches are
  copied between frames, so the UI thread never reads live emulator state.
- Master switch: `g_Config.bDualScreen` (Settings > System > "Use second screen"). Mod choices
  live in SharedPreferences (`DuoSettings`): default mod, remembered mod per game, haptics,
  immersive mode, display.
- Immersive mode (on by default): a mod made for the running game (priority above 0) gets the
  whole screen, the tab bar is hidden. The generic mods and the settings keep their tabs. A swipe
  in from the middle of the left or right edge shows the tabs for 4 s or hides them again; the
  first time, an overlay explains it. The mod sees the touch until the swipe is recognized, then
  gets `ACTION_CANCEL`.
- The second screen has its own system back gesture, which takes edge swipes before the window
  sees them and sends the back to whatever has focus (never the Presentation, which can't take
  focus). While the tabs are hidden or the settings are open (the swipe closes them), the middle 200dp of both side edges (the most Android
  allows) is excluded from it with `setSystemGestureExclusionRects`; swipes elsewhere still go to
  the system.

## Writing a mod

The whole process (finding the data in RAM and the art on the disc, testing on a device or in
headless, the traps met so far) is in the
[ppsspp-duo-mod skill](../.claude/skills/ppsspp-duo-mod/SKILL.md), which Claude Code and OpenCode
load by themselves; the steps below are the minimum.

1. Subclass `org.ppsspp.ppsspp.duo.DuoMod` (generic mods in `duo/mods`, game-specific in `duo/games`).
2. Register it in `DuoModRegistry.createAll()`.

```java
public final class MyMod extends DuoMod {
	public String getId() { return "my_mod"; }          // stored in settings, never change it
	public String getTitle(Context c) { return "Mine"; }
	public int getPriority(DuoStatus s) {               // >0: auto-picked, <0: hidden
		return "UCES00995".equals(s.gameId) ? 100 : -1;
	}
	public View onCreateView(DuoModContext host) { ... }
	public void onStatus(DuoStatus s) { ... }           // every getStatusIntervalMs()
	public void onDestroyView() { ... }
}
```

`DuoModContext` gives a mod:

| Call | Notes |
|---|---|
| `pressButton(mask, down)` | `DuoNative.CTRL_*`. Hold for at least a frame, games poll once per frame. |
| `tapVirtKey(vk)` / `virtKey(vk, down)` | `DuoNative.VIRTKEY_*`: save/load state, fast forward, pause... |
| `setAnalog(stick, x, y)` | -1..1, positive y is up. |
| `pollButtonPresses()` | Presses from any source (physical pad too), with timestamps. |
| `setMemoryWatches(addr[], size[])` / `readMemoryWatch(i)` | Up to 16 ranges of 16 KB, copied every frame. Cleared when the mod is deactivated. |
| `findMemory(start, end, offsets[], values[], cb)` | Signature scan (u32 values at given offsets), one per frame. Use it instead of fixed addresses so other releases work. |
| `readGameFile(path, offset, size, cb)` | Up to 1 MB from `disc0:/...`, one request per frame, on the emu thread. |
| `getStatus()` / `getGameIcon()` | Game ID, title, FPS, speed, save slot, pause state. |
| `setTabBarVisible(false)` | Full-screen mods. A tap on the top edge or a swipe in from the middle of a side edge shows the tabs for 4 s. |
| `haptic(view)` | Respects the haptics setting. |

`DiagnosticsMod` is the reference for memory reads, `PataponMod` for game-specific mods.

## Built-in mods

- **Dashboard**: icon, title, FPS/speed, clock/battery, save/load state with slot picker, fast
  forward, alt. speed, rewind, pause menu, pause, screenshot, mute.
- **Gamepad**: all PSP buttons and the analog stick, multi-touch.
- **Drums** (Patapon 1: UCES00995 tested; Patapon 2: UCES01177 tested, with its own Party song,
  PATA PON DON CHAKA; other releases by ID or title): drum pads and a song book that follows
  your drumming, physical buttons included. The pads can show the game's own drum artwork, read
  from `DATA_CMN.BND` at runtime (see `PataponArt`) and cached as a PNG. Next step: a beat indicator from the game's rhythm
  timer (needs its address, from a savestate taken during a mission).
- **Map** (GTA: Liberty City Stories): the city map built at runtime from the game's 64 radar
  textures in `GTA3PSPHR.IMG` (read through `readGameFile`, cached as a PNG in the app's files),
  with the player's arrow and trail, the radar blips (mission targets, destinations, with edge
  arrows when off screen) and a heading-up mode. The player pointer and the blip array are found
  by signature scans and cached per game version (on ULES00151 v3.00: `0x08B35EF8` and
  `0x08E4AAA0`), so other releases should work as long as those structures look the same.
- **Lumines** (ULES00043 v1.01; other releases by title): big preview of the three next blocks,
  score, high score, squares deleted, time and how full the board is. The game objects are on its
  heap, so they're found with a signature scan every session (see `LuminesMod`).
- **Telemetry** (Gran Turismo, UCES01245 v2.00): speed, rpm against the car's red line, gear,
  throttle and brake, and the whole track with every car on it (from the race map the game keeps
  in memory). The game look uses the HUD's digits, the car's picture and the track's logo and
  photo, read from `GT.VOL` at runtime (`GtVolume`, `Txs3`, `GtArt`) and cached as PNGs; a button
  switches to a plain look. The cars, telemetry and map are found by signature scans every race
  (see `GranTurismoMod`).
- **Battle** (Jeanne d'Arc, UCUS98700 v1.00): portrait, HP, MP and level of the party and the
  enemies (defeated ones in grayscale, guests tagged), with the turn number, drawn like the game's
  status window. The busts, the lettering (digits, HP, MP, TURN, Lv, PLAYER, ENEMY, GUEST) and the
  name plate are the game's own, decoded at runtime from `DATA/FACE/AF*_00.GIM` (with
  `MFACE*.GIM` as the fallback) and `DATA/GUI/MENU04.GIM` / `MENU01.GIM` on the disc (see
  `GimImage`, `JeanneArt`). The battle state is in the
  main module's data, so it's read from fixed addresses (see `JeanneDArcMod`); other releases
  aren't supported. Columns with many units switch to one-line rows, then scroll.
- **Cards** (Metal Gear Ac!d, ULUS10006 v1.00): the hand with each card's type, cost and short
  text, the full text of the card under the cursor, Snake's life, deck, cost and turn. Tapping a
  card moves the game's cursor to it (left/right presses, only while the game waits for a card from
  the hand, and only as long as each press moves the cursor). The cards are drawn as the game does,
  with their illustrations from `resident.qar` in `stage/init/_zar`, read at runtime and cached
  (see `MgaArt`). The hand, Snake and the status panel are
  found by the code pointers they hold; card records and the battle phase are static, names and
  texts come from the string table in `stage/com/_zar` (see `MetalGearAcidMod`).
- **Race** (WipEout Pure, UCUS98612 v2.00): position, lap, speed, shield energy, race and lap times
  (lap times are worked out from the race time when the lap changes), and a map of the track drawn
  from the cells every ship has passed. The ships are a static array in the main module (8 x 0x1C0
  at `0x08B72024`); the player's is the one with 0 at +0x84, and its controller (+0x144) leads to
  the energy and, through the physics object, the speed. Drawn like the game's HUD with its own
  lettering, the `FNT` font 0xD7B3DDA5 in `FE.wad`, read at runtime and cached (see
  `WipeoutFont`: the WAD is a hash table of files; the font's palette grey levels are the alpha).
  Other releases aren't supported.
- **Analyze** (Persona 3 Portable, ULES01523 v1.01; ULUS10512 is recognized but untested): in
  battles, the party's HP and SP (max HP and SP come from the per-level table, they aren't stored)
  and each enemy's level, arcana, HP and affinities, drawn like the Analyze screen. All affinities
  are shown, including those the player hasn't discovered yet (the save that records them is
  encrypted on disc and wasn't looked for in RAM). A flag word at `0x08C3BDB8` says whether a
  battle is on and points to its participants; characters and the battle tables (from `UNIT.TBL`)
  are in the main module's data, the enemy group on the heap (see `Persona3Mod`). The element icons,
  the Wk / Str / Nul / Rpl / Dm labels and the HUD faces (with their outlines) are read from
  `umd0.cpk` at runtime (a CRI CPK with CRILAYLA compression; Atlus SPR0 / TMX0 textures, see
  `Persona3Art`) and cached as PNGs.
- **Party** (Persona, ULUS10432 v1.00): in battles, a card per party member and per enemy, laid out
  like Shin Megami Tensei IV's (name, HP and SP with bars, portrait, level; knocked out units in
  black and white on gray, a card shakes and flashes when its unit loses HP). In dungeons, the
  floor's map instead, with a strip of the party below. Everything is in the main module's data:
  - Battle units at `0x08D03DC8 + n * 0x118` (0-4 the party in HUD order, 5-12 the enemies; the
    HUD reads these): u32 HP, max HP, SP, max SP, u16 id at +0x3E (the character, 1 the hero to 9
    Reiji, or the demon), the name at +0x40 (big-endian u16, uppercase and digits ASCII + 0x9F,
    lowercase ASCII + 0xA0, 0x8140 a space, 0xFFFF at the end), u8 level at +0x6A. The last
    battle's enemies stay with 0 HP. The party at `0x08CC51F8 + n * 0x80`, same fields, is up to
    date on the field but behind in battles.
  - `0x08B967BC` is 8 on the field and on the result screen, 0 in a battle and 13 while talking to
    a demon; a battle is shown while it isn't 8 and an enemy is alive.
  - Dungeons: the facing as f32 degrees at `0x08CCD8CC` (0 north, -90 east); the dungeon object at
    `*0x08CCBD5C` has the column at +0x2A, the row at +0x2C, the dungeon (0x11 for `pack/dng/d11`)
    at +0x46 and the floor at +0x48. The cells walked through are 72-byte bitmaps (24 rows of 3
    bytes, most significant bit first) from `0x08CC689C`, block `u8[0x08A92AD4 + dungeon] +
    floor`, the formula in the game's marking code at `0x0884C3DC`; a cell is marked once it's left.
  - Walls and doors come from `dXYm.bin`: u32 section count and sizes, sections from the next
    16-byte boundary; each floor is a 0x4AC-byte section (0x28-byte header, then 24x24 u16 cell
    types), and section 1 holds 12-byte type records from +0x14 (type t at (t - 1) * 12) with the
    north, south, east and west wall pieces in bytes 4-7. A door type has 4 or 5 in the low half of
    byte 3 and its side in the high half of byte 2 (1 N, 2 W, 4 E, 8 S); its pieces belong to the
    door. Room types have no pieces (walls come from the empty cells around them), and the pieces
    of a room's entrance cell towards the room are a frame. Checked on Lost Forest 1F only.
  - Portraits from the disc: the party's HUD faces are GIMs 0-8 of `pack/ch_menu.bin` (u32 count,
    u32 sizes, files from the next 16-byte boundary), demon n is GIM 2n of `pack/DVLAN.BIN` (u32
    start/end pairs from +8), the Analyze sprite (see `Persona1Art`, `Tools/duo/formats/p1_pack.py`
    and `p1_dngmap.py`).
- **Duel** (Yu-Gi-Oh! GX Tag Force, ULES00600 v1.01): the card under the cursor drawn like the
  game's own (frame tinted by kind, attribute icon, level stars, text box with ATK/DEF), its name,
  type and full text, a silver ATK/DEF panel with gold digits, both players' Life Points, whose
  turn it is and the phase. The duel modules load at the same addresses in every duel, so the
  state is read from fixed addresses (`0x09E4D1A0` card info, `0x09E6418C` / `0x09E64190` LP,
  `0x09E641AC` turn side, `0x09E641B8` phase). Card names, texts and properties come from
  `duelsys/cardinfo_eng.ehp` (EHP archive), the artwork from `card/cardh_e.cip` (a 128x64 DXT1
  atlas per card, put back together into 78x78), the frame, icons, panels and digits from
  `duel/basic_be.ehp` (see `TagForceMod`). The turn number isn't found yet, and only the DP and
  MP1 phase values were seen.
- **Screen off**: black, tab bar hidden.
- **Diagnostics**: raw status, display info, memory dump.

## Notes from eden-duo

Kept: Presentation + `FLAG_NOT_FOCUSABLE`/`KEEP_SCREEN_ON`, union of presentation-category and all
displays, debounced hotplug, dismiss listener, latched input, installable per-game content.
Avoided: driving the second screen from the guest's frame present (it froze on pause/loading), a
second swapchain on the game's queue (needed a vendor frame-pacer workaround on the Thor), tearing
down GPU objects from the UI thread after a timeout, game-specific code in the renderer, display
picking by name, no user setting to turn it off, logging enabled by default.
