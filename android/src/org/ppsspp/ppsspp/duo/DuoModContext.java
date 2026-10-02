package org.ppsspp.ppsspp.duo;

import android.content.Context;
import android.graphics.Bitmap;
import android.view.Display;
import android.view.View;

// Services the host offers to the active mod.
public interface DuoModContext {
	// Themed context of the second display. Use it to build views.
	Context getContext();

	Display getDisplay();

	DuoSettings getSettings();

	DuoStatus getStatus();

	// Icon of the running game, or null.
	Bitmap getGameIcon();

	// Short haptic tick, if enabled in the settings.
	void haptic(View view);

	// Hides or shows the host's tab bar. When hidden, a tap near the top edge brings it back for a
	// few seconds.
	void setTabBarVisible(boolean visible);

	// Input to the emulator. Presses are only applied while a game is in the foreground.
	void pressButton(int pspButtonMask, boolean down);

	// Down and up in the same frame. Fine for virtual keys (save state, screenshot...), but too
	// short for PSP buttons: games poll those once per frame, so hold them with pressButton.
	void tapVirtKey(int virtKey);

	void virtKey(int virtKey, boolean down);

	void setAnalog(int stick, float x, float y);

	// Button presses since the last call (any source), oldest first. Sampled once per frame and
	// buffered (up to 64), so poll at least a few times per second if you need every press.
	java.util.List<DuoButtonPress> pollButtonPresses();

	// Memory ranges copied every frame while this mod is active. Cleared when the mod is deactivated.
	boolean setMemoryWatches(int[] addresses, int[] sizes);

	// Latest copy of watch #index, or null if not readable.
	byte[] readMemoryWatch(int index);
}
