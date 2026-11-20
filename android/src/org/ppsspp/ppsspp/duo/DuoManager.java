package org.ppsspp.ppsspp.duo;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.os.Build;
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

	// Marks the intent we send ourselves to move the game to another display. PpssppActivity
	// ignores it in onNewIntent.
	public static final String EXTRA_MOVE_DISPLAY = "org.ppsspp.ppssppduo.MOVE_DISPLAY";

	// The display we last asked the system to move the game to, or -1. Static because the move
	// recreates the activity. If the system ignored the request we'd otherwise ask again on every
	// display event; it's cleared when the setting changes or a display comes or goes.
	private static int moveAttemptedTo = -1;

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
		moveAttemptedTo = -1;
		scheduleRefresh(0);
	}

	@Override
	public void onDisplayConfigChanged() {
		// Called from inside the presentation; don't dismiss it in the middle of a click handler.
		moveAttemptedTo = -1;
		scheduleRefresh(0);
	}

	@Override
	public void onDisplayAdded(int displayId) {
		failedDisplays.clear();
		moveAttemptedTo = -1;
		scheduleRefresh(REFRESH_DEBOUNCE_MS);
	}

	@Override
	public void onDisplayRemoved(int displayId) {
		failedDisplays.clear();
		moveAttemptedTo = -1;
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
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
			Display d = activity.getDisplay();
			if (d != null) {
				return d.getDisplayId();
			}
		}
		WindowManager wm = activity.getWindowManager();
		Display d = wm != null ? wm.getDefaultDisplay() : null;
		return d != null ? d.getDisplayId() : Display.DEFAULT_DISPLAY;
	}

	private void refresh() {
		if (!started || activity.isFinishing() || displayManager == null) {
			return;
		}

		if (moveGameIfNeeded()) {
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

	// Puts the game on the display the "game on the second screen" setting asks for. Returns true if
	// it asked the system to move the game; the activity is then recreated on the new display and
	// shows the second screen from there.
	private boolean moveGameIfNeeded() {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
			return false;
		}
		int current = activityDisplayId();
		int target;
		if (isEnabled() && settings.getGameOnSecondDisplay()) {
			if (current != Display.DEFAULT_DISPLAY) {
				return false;
			}
			List<Display> others = DuoDisplayPicker.candidates(displayManager, Display.DEFAULT_DISPLAY,
				settings.getPreferredDisplay(), java.util.Collections.emptySet());
			if (others.isEmpty()) {
				return false;
			}
			target = others.get(0).getDisplayId();
		} else {
			// Back to the main display, also when the second screen is turned off.
			if (current == Display.DEFAULT_DISPLAY) {
				return false;
			}
			target = Display.DEFAULT_DISPLAY;
		}
		if (target == moveAttemptedTo) {
			// Already asked, and we're still not there.
			return false;
		}
		moveAttemptedTo = target;

		Display targetDisplay = displayManager.getDisplay(target);
		Log.i(TAG, "Moving the game from display " + current + " to " +
			(targetDisplay != null ? DuoDisplayPicker.describe(targetDisplay) : "#" + target));
		// The second screen may be on the target display, and it would sit on top of the game.
		dismiss();
		try {
			Intent intent = new Intent(activity, activity.getClass());
			intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
			intent.putExtra(EXTRA_MOVE_DISPLAY, true);
			ActivityOptions options = ActivityOptions.makeBasic();
			options.setLaunchDisplayId(target);
			activity.startActivity(intent, options.toBundle());
		} catch (RuntimeException e) {
			// SecurityException if the system won't launch on that display.
			Log.w(TAG, "Couldn't move the game to display " + target + ": " + e);
			return false;
		}
		// If the system ignores the request nothing else happens, so check again in a moment.
		scheduleRefresh(1000);
		return true;
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
