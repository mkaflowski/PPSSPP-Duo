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
  live in SharedPreferences (`DuoSettings`): default mod, remembered mod per game, haptics, display.

## Writing a mod

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
| `setTabBarVisible(false)` | Full-screen mods. A tap on the top edge shows the tabs for 4 s. |
| `haptic(view)` | Respects the haptics setting. |

`DiagnosticsMod` is the reference for memory reads, `PataponMod` for game-specific mods.

## Built-in mods

- **Dashboard**: icon, title, FPS/speed, clock/battery, save/load state with slot picker, fast
  forward, alt. speed, rewind, pause menu, pause, screenshot, mute.
- **Gamepad**: all PSP buttons and the analog stick, multi-touch.
- **Drums** (Patapon 1: UCES00995 tested, other releases by ID or title): drum pads and a song book that follows
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
  throttle and brake, and a map of the track drawn from the car's position as you drive. The car
  and telemetry objects are found by signature scans every race (see `GranTurismoMod`).
- **Screen off**: black, tab bar hidden.
- **Diagnostics**: raw status, display info, memory dump.

## Notes from eden-duo

Kept: Presentation + `FLAG_NOT_FOCUSABLE`/`KEEP_SCREEN_ON`, union of presentation-category and all
displays, debounced hotplug, dismiss listener, latched input, installable per-game content.
Avoided: driving the second screen from the guest's frame present (it froze on pause/loading), a
second swapchain on the game's queue (needed a vendor frame-pacer workaround on the Thor), tearing
down GPU objects from the UI thread after a timeout, game-specific code in the renderer, display
picking by name, no user setting to turn it off, logging enabled by default.
