package org.ppsspp.ppsspp.duo;

import android.content.Context;
import android.content.SharedPreferences;

// Second-screen settings that only the Java side cares about. The master switch is g_Config.bDualScreen
// on the native side (System settings), so it's saved with the rest of the PPSSPP config.
public final class DuoSettings {
	private static final String PREFS = "ppsspp_duo";

	private static final String KEY_DEFAULT_MOD = "default_mod";
	private static final String KEY_GAME_MOD_PREFIX = "game_mod.";
	private static final String KEY_REMEMBER_PER_GAME = "remember_per_game";
	private static final String KEY_HAPTICS = "haptics";
	private static final String KEY_IMMERSIVE = "immersive";
	private static final String KEY_GESTURE_HINT_SHOWN = "gesture_hint_shown";
	private static final String KEY_PREFERRED_DISPLAY = "preferred_display";

	private final SharedPreferences prefs;

	public DuoSettings(Context context) {
		prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
	}

	public String getDefaultMod() {
		return prefs.getString(KEY_DEFAULT_MOD, DuoModRegistry.DEFAULT_MOD_ID);
	}

	public void setDefaultMod(String modId) {
		prefs.edit().putString(KEY_DEFAULT_MOD, modId).apply();
	}

	// Null if the user never picked a mod for this game.
	public String getModForGame(String gameId) {
		if (gameId == null || gameId.isEmpty()) {
			return null;
		}
		return prefs.getString(KEY_GAME_MOD_PREFIX + gameId, null);
	}

	public void setModForGame(String gameId, String modId) {
		if (gameId == null || gameId.isEmpty()) {
			return;
		}
		prefs.edit().putString(KEY_GAME_MOD_PREFIX + gameId, modId).apply();
	}

	public boolean getRememberPerGame() {
		return prefs.getBoolean(KEY_REMEMBER_PER_GAME, true);
	}

	public void setRememberPerGame(boolean value) {
		prefs.edit().putBoolean(KEY_REMEMBER_PER_GAME, value).apply();
	}

	public boolean getHaptics() {
		return prefs.getBoolean(KEY_HAPTICS, true);
	}

	public void setHaptics(boolean value) {
		prefs.edit().putBoolean(KEY_HAPTICS, value).apply();
	}

	// Tab bar hidden, brought back with a swipe in from a side edge.
	public boolean getImmersive() {
		return prefs.getBoolean(KEY_IMMERSIVE, true);
	}

	public void setImmersive(boolean value) {
		prefs.edit().putBoolean(KEY_IMMERSIVE, value).apply();
	}

	// Whether the user has dismissed the explanation of the back gesture.
	public boolean getGestureHintShown() {
		return prefs.getBoolean(KEY_GESTURE_HINT_SHOWN, false);
	}

	public void setGestureHintShown(boolean value) {
		prefs.edit().putBoolean(KEY_GESTURE_HINT_SHOWN, value).apply();
	}

	// Display name, or "" for automatic.
	public String getPreferredDisplay() {
		return prefs.getString(KEY_PREFERRED_DISPLAY, "");
	}

	public void setPreferredDisplay(String name) {
		prefs.edit().putString(KEY_PREFERRED_DISPLAY, name == null ? "" : name).apply();
	}
}
