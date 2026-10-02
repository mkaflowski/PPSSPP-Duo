package org.ppsspp.ppsspp.duo;

import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

// Immutable snapshot of the emulator state, as published by DuoBridge.cpp.
public final class DuoStatus {
	private static final String TAG = "PPSSPPDuo";

	public static final DuoStatus EMPTY = new DuoStatus();

	// "menu", "ingame", "paused" (pause menu), "exception", "exit".
	public final String state;
	// Emulation halted without a menu (pause-no-menu, debugger).
	public final boolean stepping;
	public final String gameId;
	public final String title;
	public final String path;
	public final float vps;
	public final float fps;
	public final float actualFps;
	public final boolean fastForward;
	public final int fpsLimit;  // 0 = normal, 1 = custom 1, 2 = custom 2, ...
	public final int slot;
	public final int slotCount;
	public final boolean slotUsed;
	public final String slotDate;
	public final int iconGeneration;
	public final long frame;

	private DuoStatus() {
		state = "menu";
		stepping = false;
		gameId = "";
		title = "";
		path = "";
		vps = 0;
		fps = 0;
		actualFps = 0;
		fastForward = false;
		fpsLimit = 0;
		slot = 0;
		slotCount = 0;
		slotUsed = false;
		slotDate = "";
		iconGeneration = 0;
		frame = 0;
	}

	private DuoStatus(JSONObject o) {
		state = o.optString("state", "menu");
		stepping = o.optBoolean("stepping");
		gameId = o.optString("gameId", "");
		title = o.optString("title", "");
		path = o.optString("path", "");
		vps = (float)o.optDouble("vps", 0);
		fps = (float)o.optDouble("fps", 0);
		actualFps = (float)o.optDouble("actualFps", 0);
		fastForward = o.optBoolean("fastForward");
		fpsLimit = o.optInt("fpsLimit");
		slot = o.optInt("slot");
		slotCount = o.optInt("slotCount");
		slotUsed = o.optBoolean("slotUsed");
		slotDate = o.optString("slotDate", "");
		iconGeneration = o.optInt("iconGeneration");
		frame = o.optLong("frame");
	}

	static DuoStatus parse(String json) {
		if (json == null || json.isEmpty()) {
			return EMPTY;
		}
		try {
			return new DuoStatus(new JSONObject(json));
		} catch (JSONException e) {
			Log.e(TAG, "Bad status JSON: " + e);
			return EMPTY;
		}
	}

	// A game is loaded (running, in the pause menu or halted).
	public boolean hasGame() {
		return !path.isEmpty();
	}

	public boolean isInGame() {
		return "ingame".equals(state);
	}

	public boolean isPauseMenu() {
		return "paused".equals(state);
	}

	// The title from PARAM.SFO, or for homebrew without one, the file (or folder for EBOOT.PBP) name.
	public String displayTitle() {
		if (!title.isEmpty()) {
			return title;
		}
		String p = path;
		while (p.endsWith("/")) {
			p = p.substring(0, p.length() - 1);
		}
		int slash = p.lastIndexOf('/');
		String name = p.substring(slash + 1);
		if (name.equalsIgnoreCase("EBOOT.PBP") && slash > 0) {
			String parent = p.substring(0, slash);
			name = parent.substring(parent.lastIndexOf('/') + 1);
		}
		return name;
	}

	// Emulation speed in percent of a real PSP (59.94 vblanks per second).
	public int speedPercent() {
		return Math.round(vps / 59.94f * 100.0f);
	}
}
