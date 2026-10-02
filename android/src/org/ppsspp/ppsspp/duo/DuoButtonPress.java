package org.ppsspp.ppsspp.duo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

// PSP buttons that went down, from any source: the physical gamepad, the main screen's touch
// controls or a mod.
public final class DuoButtonPress {
	// DuoNative.CTRL_* bits that went down at this moment (usually one).
	public final int buttons;
	// Milliseconds on the emulator's clock. Only differences between presses are meaningful.
	public final int timeMs;

	DuoButtonPress(int buttons, int timeMs) {
		this.buttons = buttons;
		this.timeMs = timeMs;
	}

	static List<DuoButtonPress> fromNative(int[] flat) {
		if (flat == null || flat.length < 2) {
			return Collections.emptyList();
		}
		List<DuoButtonPress> list = new ArrayList<>(flat.length / 2);
		for (int i = 0; i + 1 < flat.length; i += 2) {
			list.add(new DuoButtonPress(flat[i], flat[i + 1]));
		}
		return list;
	}
}
