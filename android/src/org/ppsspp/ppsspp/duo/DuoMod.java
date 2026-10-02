package org.ppsspp.ppsspp.duo;

import android.content.Context;
import android.view.View;

// A second-screen mod: decides what the second screen shows and how it reacts to touch.
//
// The host (DuoPresentation) owns the display, the window, the lifecycle, the status polling and
// the input bridge. A mod only builds a view and reacts to callbacks, all on the UI thread.
// Lifecycle of the active mod:
//   onCreateView -> onStatus (repeatedly) ... -> onDestroyView
// A mod instance can be activated again after onDestroyView, so reset view state in onCreateView.
//
// To add a mod: subclass this, then register it in DuoModRegistry.
public abstract class DuoMod {
	// Stable identifier, stored in settings. Never change it once released.
	public abstract String getId();

	public abstract String getTitle(Context context);

	// Shown in the settings page.
	public String getDescription(Context context) {
		return "";
	}

	// Used when picking a mod for a game the user hasn't chosen one for: the highest value wins,
	// generic mods return 0. A game-specific mod can return e.g. 100 for its game IDs. Return a
	// negative value to hide the mod for this status (for example, a companion for another game).
	public int getPriority(DuoStatus status) {
		return 0;
	}

	public abstract View onCreateView(DuoModContext host);

	public void onDestroyView() {}

	// Called after onCreateView and then every getStatusIntervalMs() while the mod is active.
	public void onStatus(DuoStatus status) {}

	public long getStatusIntervalMs() {
		return 250;
	}

	// The main activity went to the background / came back. The second screen stays up for a short
	// pause (a dialog, the permission prompt), but the emulator doesn't run.
	public void onHostPause() {}

	public void onHostResume() {}
}
