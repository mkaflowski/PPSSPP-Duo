package org.ppsspp.ppsspp.duo;

import android.app.Activity;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;

import org.ppsspp.ppsspp.NativeApp;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

// Owns the second screen: picks the display, shows and dismisses the Presentation, and follows
// display hotplug and the activity lifecycle. Driven by PpssppActivity, UI thread only.
//
// The Presentation is shown between onStart and onStop. It survives onPause, so a dialog or a
// permission prompt on the main screen doesn't blank the second one.
public final class DuoManager implements DisplayManager.DisplayListener, DuoPresentation.Listener {
	private static final String TAG = "PPSSPPDuo";
	// Display events come in bursts (added, then changed a few times while the panel powers up).
	private static final long REFRESH_DEBOUNCE_MS = 300;

	private final Activity activity;
	private final DisplayManager displayManager;
	private final DuoSettings settings;
	private final List<DuoMod> mods;
	private final Handler handler = new Handler(Looper.getMainLooper());

	// Displays that threw on show(). Cleared when displays are added or removed.
	private final Set<Integer> failedDisplays = new HashSet<>();

	private DuoPresentation presentation;
	private boolean started;
	private boolean paused;

	public DuoManager(Activity activity) {
		this.activity = activity;
		this.displayManager = (DisplayManager)activity.getSystemService(Context.DISPLAY_SERVICE);
		this.settings = new DuoSettings(activity);
		this.mods = DuoModRegistry.createAll();
	}

	public void onStart() {
		started = true;
		if (displayManager != null) {
			displayManager.registerDisplayListener(this, handler);
		}
		scheduleRefresh(0);
	}

	public void onStop() {
		started = false;
		if (displayManager != null) {
			displayManager.unregisterDisplayListener(this);
		}
		handler.removeCallbacks(refreshRunnable);
		dismiss();
	}

	public void onPause() {
		paused = true;
		if (presentation != null) {
			presentation.onHostPause();
		}
	}

	public void onResume() {
		paused = false;
		if (presentation != null) {
			presentation.onHostResume();
		}
	}

	public void onDestroy() {
		onStop();
	}

	// g_Config.bDualScreen changed (System settings).
	public void onConfigChanged() {
		scheduleRefresh(0);
	}

	@Override
	public void onDisplayConfigChanged() {
		// Called from inside the presentation; don't dismiss it in the middle of a click handler.
		scheduleRefresh(0);
	}

	@Override
	public void onDisplayAdded(int displayId) {
		failedDisplays.clear();
		scheduleRefresh(REFRESH_DEBOUNCE_MS);
	}

	@Override
	public void onDisplayRemoved(int displayId) {
		failedDisplays.clear();
		scheduleRefresh(REFRESH_DEBOUNCE_MS);
	}

	@Override
	public void onDisplayChanged(int displayId) {
		// Includes the panel turning off and on. refresh() is cheap when nothing changed.
		scheduleRefresh(REFRESH_DEBOUNCE_MS);
	}

	private final Runnable refreshRunnable = this::refresh;

	private void scheduleRefresh(long delayMs) {
		handler.removeCallbacks(refreshRunnable);
		handler.postDelayed(refreshRunnable, delayMs);
	}

	private boolean isEnabled() {
		return "1".equals(NativeApp.queryConfig("dualScreen"));
	}

	@SuppressWarnings("deprecation")
	private int activityDisplayId() {
		WindowManager wm = activity.getWindowManager();
		Display d = wm != null ? wm.getDefaultDisplay() : null;
		return d != null ? d.getDisplayId() : Display.DEFAULT_DISPLAY;
	}

	private void refresh() {
		if (!started || activity.isFinishing() || displayManager == null) {
			return;
		}

		List<Display> candidates = isEnabled()
			? DuoDisplayPicker.candidates(displayManager, activityDisplayId(), settings.getPreferredDisplay(), failedDisplays)
			: java.util.Collections.emptyList();

		if (presentation != null) {
			Display current = presentation.getDisplay();
			boolean stillBest = !candidates.isEmpty()
				&& candidates.get(0).getDisplayId() == current.getDisplayId()
				&& presentation.isShowing();
			if (stillBest) {
				return;
			}
			dismiss();
		}

		for (Display display : candidates) {
			if (show(display)) {
				return;
			}
			failedDisplays.add(display.getDisplayId());
		}
		if (candidates.isEmpty()) {
			Log.i(TAG, isEnabled() ? "No secondary display" : "Second screen disabled");
		}
	}

	private boolean show(Display display) {
		Log.i(TAG, "Showing second screen on " + DuoDisplayPicker.describe(display));
		DuoPresentation p = new DuoPresentation(activity, display, settings, mods, this);
		p.setOnDismissListener(dialog -> {
			// Also fires when the system dismisses us (display removed).
			if (presentation == dialog) {
				presentation = null;
				if (started) {
					scheduleRefresh(REFRESH_DEBOUNCE_MS);
				}
			}
		});
		try {
			p.show();
		} catch (WindowManager.InvalidDisplayException | WindowManager.BadTokenException e) {
			Log.w(TAG, "Display " + display.getDisplayId() + " refused the second screen: " + e);
			return false;
		}
		presentation = p;
		if (paused) {
			p.onHostPause();
		}
		return true;
	}

	private void dismiss() {
		if (presentation == null) {
			return;
		}
		DuoPresentation p = presentation;
		presentation = null;
		try {
			p.dismiss();
		} catch (IllegalArgumentException e) {
			// The window is already gone (display removed under us).
			Log.w(TAG, "dismiss: " + e);
		}
	}
}
