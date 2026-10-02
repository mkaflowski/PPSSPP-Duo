package org.ppsspp.ppsspp.duo;

import android.hardware.display.DisplayManager;
import android.view.Display;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

// Chooses the display for the second screen. No device names are hardcoded: candidates are ranked by
// what the display says about itself.
final class DuoDisplayPicker {
	private DuoDisplayPicker() {}

	// All displays that could host the second screen, best first.
	// excludeDisplayId: the display the activity is on.
	// failedIds: displays that refused a Presentation recently.
	static List<Display> candidates(DisplayManager dm, int excludeDisplayId, String preferredName, Set<Integer> failedIds) {
		// Some devices only list the secondary panel under the presentation category, others only
		// in the full list, so merge both.
		Map<Integer, Display> all = new LinkedHashMap<>();
		for (Display d : dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)) {
			all.put(d.getDisplayId(), d);
		}
		for (Display d : dm.getDisplays()) {
			all.put(d.getDisplayId(), d);
		}

		List<Display> result = new ArrayList<>();
		for (Display d : all.values()) {
			if (d.getDisplayId() == excludeDisplayId || failedIds.contains(d.getDisplayId())) {
				continue;
			}
			if (!d.isValid() || d.getState() == Display.STATE_OFF) {
				continue;
			}
			// Private virtual displays belong to another app.
			if ((d.getFlags() & Display.FLAG_PRIVATE) != 0) {
				continue;
			}
			result.add(d);
		}

		Collections.sort(result, (a, b) -> Integer.compare(score(b, preferredName), score(a, preferredName)));
		return result;
	}

	private static int score(Display d, String preferredName) {
		int score = 0;
		if (preferredName != null && !preferredName.isEmpty() && preferredName.equals(d.getName())) {
			score += 1000;
		}
		int flags = d.getFlags();
		if ((flags & Display.FLAG_PRESENTATION) != 0) {
			score += 100;
		}
		// Built-in panels are secure; screen recorders and casting virtual displays usually aren't.
		if ((flags & Display.FLAG_SECURE) != 0) {
			score += 10;
		}
		return score;
	}

	static String describe(Display d) {
		android.graphics.Point size = new android.graphics.Point();
		d.getRealSize(size);
		return "#" + d.getDisplayId() + " \"" + d.getName() + "\" " + size.x + "x" + size.y +
			" @" + Math.round(d.getRefreshRate()) + "Hz flags=0x" + Integer.toHexString(d.getFlags()) +
			" state=" + d.getState();
	}
}
