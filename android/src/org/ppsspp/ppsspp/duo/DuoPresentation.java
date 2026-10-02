package org.ppsspp.ppsspp.duo;

import android.app.Presentation;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.ppsspp.ppsspp.R;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

// The second-screen window. Hosts one mod at a time plus the tab bar used to switch between them.
//
// Everything here runs on the UI thread. The window is independent from the emulator's surface:
// it keeps working while the emulator is paused, in the menus or loading.
final class DuoPresentation extends Presentation implements DuoModContext {
	private static final String TAG = "PPSSPPDuo";
	private static final long TAB_BAR_PEEK_MS = 4000;

	interface Listener {
		// The user changed something that affects which display we're on (or whether we're shown at all).
		void onDisplayConfigChanged();
	}

	private final DuoSettings settings;
	private final List<DuoMod> mods;
	private final Listener listener;
	private final Handler handler = new Handler(Looper.getMainLooper());

	private LinearLayout tabBar;
	private FrameLayout content;
	private View peekStrip;
	private final Map<DuoMod, TextView> tabs = new HashMap<>();
	private final Map<Integer, GameFileCallback> fileRequests = new HashMap<>();
	private TextView settingsTab;

	private DuoMod activeMod;
	private boolean settingsOpen;
	private DuoSettingsView settingsView;
	private boolean tabBarWanted = true;

	private DuoStatus status = DuoStatus.EMPTY;
	private String currentGameKey;  // null until the first status arrives
	private Bitmap gameIcon;
	private int iconGeneration = -1;
	private boolean polling;
	private boolean hostPaused;

	DuoPresentation(Context outerContext, Display display, DuoSettings settings, List<DuoMod> mods, Listener listener) {
		super(outerContext, display, android.R.style.Theme_Material_NoActionBar_Fullscreen);
		this.settings = settings;
		this.mods = mods;
		this.listener = listener;
	}

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		Window window = getWindow();
		if (window != null) {
			// NOT_FOCUSABLE: otherwise touching the second screen moves input focus to it and the
			// gamepad stops reaching the game. KEEP_SCREEN_ON: the main window's flag doesn't cover
			// this display.
			window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
				| WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
				| WindowManager.LayoutParams.FLAG_FULLSCREEN
				| WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);
			window.setBackgroundDrawableResource(android.R.color.black);
		}
		setContentView(buildRoot());
		hideSystemBars();
	}

	@SuppressWarnings("deprecation")
	private void hideSystemBars() {
		Window window = getWindow();
		if (window == null) {
			return;
		}
		window.getDecorView().setSystemUiVisibility(
			View.SYSTEM_UI_FLAG_LAYOUT_STABLE
				| View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
				| View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
				| View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
				| View.SYSTEM_UI_FLAG_FULLSCREEN
				| View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
	}

	private View buildRoot() {
		Context ctx = getContext();
		FrameLayout root = new FrameLayout(ctx);
		root.setBackgroundColor(DuoUi.COLOR_BACKGROUND);

		LinearLayout column = new LinearLayout(ctx);
		column.setOrientation(LinearLayout.VERTICAL);
		root.addView(column, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

		column.addView(buildTabBar(), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, DuoUi.dp(ctx, 48)));

		content = new FrameLayout(ctx);
		column.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f));

		// Invisible strip that brings a hidden tab bar back.
		peekStrip = new View(ctx);
		peekStrip.setVisibility(View.GONE);
		peekStrip.setOnTouchListener((v, ev) -> {
			if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
				peekTabBar();
			}
			return true;
		});
		root.addView(peekStrip, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, DuoUi.dp(ctx, 28), Gravity.TOP));
		return root;
	}

	private View buildTabBar() {
		Context ctx = getContext();
		tabBar = new LinearLayout(ctx);
		tabBar.setOrientation(LinearLayout.HORIZONTAL);
		tabBar.setBackgroundColor(DuoUi.COLOR_BAR);
		tabBar.setGravity(Gravity.CENTER_VERTICAL);
		int pad = DuoUi.dp(ctx, 6);
		tabBar.setPadding(pad, 0, pad, 0);

		HorizontalScrollView scroll = new HorizontalScrollView(ctx);
		scroll.setHorizontalScrollBarEnabled(false);
		LinearLayout tabRow = new LinearLayout(ctx);
		tabRow.setOrientation(LinearLayout.HORIZONTAL);
		tabRow.setGravity(Gravity.CENTER_VERTICAL);
		scroll.addView(tabRow);
		tabBar.addView(scroll, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.0f));

		for (DuoMod mod : mods) {
			TextView tab = makeTab(safeTitle(mod));
			tab.setOnClickListener(v -> {
				haptic(v);
				onTabClicked(mod);
			});
			tabs.put(mod, tab);
			tabRow.addView(tab, tabLayoutParams());
		}

		settingsTab = makeTab("\u2699");  // gear
		settingsTab.setContentDescription(ctx.getString(R.string.duo_settings));
		settingsTab.setTextSize(22);
		settingsTab.setOnClickListener(v -> {
			haptic(v);
			openSettings();
		});
		tabBar.addView(settingsTab, tabLayoutParams());
		return tabBar;
	}

	private TextView makeTab(String title) {
		Context ctx = getContext();
		TextView tab = DuoUi.button(ctx, title);
		tab.setTextSize(15);
		tab.setTypeface(Typeface.DEFAULT_BOLD);
		tab.setSingleLine(true);
		int padH = DuoUi.dp(ctx, 12);
		int padV = DuoUi.dp(ctx, 4);
		tab.setPadding(padH, padV, padH, padV);
		return tab;
	}

	private LinearLayout.LayoutParams tabLayoutParams() {
		Context ctx = getContext();
		LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, DuoUi.dp(ctx, 36));
		lp.setMargins(DuoUi.dp(ctx, 2), 0, DuoUi.dp(ctx, 2), 0);
		return lp;
	}

	@Override
	protected void onStart() {
		super.onStart();
		Log.i(TAG, "Second screen shown on " + DuoDisplayPicker.describe(getDisplay()));
		DuoNative.nativeSetActive(true);
		polling = true;
		// The first poll picks the mod.
		handler.post(pollRunnable);
	}

	@Override
	protected void onStop() {
		polling = false;
		handler.removeCallbacksAndMessages(null);
		closeSettings();
		deactivateMod();
		DuoNative.nativeSetActive(false);
		currentGameKey = null;
		Log.i(TAG, "Second screen hidden");
		super.onStop();
	}

	void onHostPause() {
		hostPaused = true;
		DuoNative.nativeReleaseAll();
		if (activeMod != null) {
			try {
				activeMod.onHostPause();
			} catch (Throwable t) {
				reportModError(activeMod, t);
			}
		}
	}

	void onHostResume() {
		hostPaused = false;
		hideSystemBars();
		if (activeMod != null) {
			try {
				activeMod.onHostResume();
			} catch (Throwable t) {
				reportModError(activeMod, t);
			}
		}
	}

	private final Runnable pollRunnable = new Runnable() {
		@Override
		public void run() {
			if (!polling) {
				return;
			}
			poll();
			long interval = activeMod != null ? activeMod.getStatusIntervalMs() : 500;
			handler.postDelayed(this, Math.max(50, interval));
		}
	};

	private void poll() {
		DuoStatus s = DuoStatus.parse(DuoNative.nativeGetStatus());
		status = s;

		if (s.iconGeneration != iconGeneration) {
			iconGeneration = s.iconGeneration;
			byte[] png = DuoNative.nativeGetIcon();
			gameIcon = png != null ? BitmapFactory.decodeByteArray(png, 0, png.length) : null;
		}

		// The path identifies a game better than the ID (homebrew often has none).
		String gameKey = s.hasGame() ? s.gameId + "|" + s.path : "";
		if (!gameKey.equals(currentGameKey)) {
			currentGameKey = gameKey;
			onGameChanged(s);
		}

		pollGameFiles();

		if (activeMod != null) {
			try {
				activeMod.onStatus(s);
			} catch (Throwable t) {
				reportModError(activeMod, t);
			}
		}
	}

	private void onGameChanged(DuoStatus s) {
		Log.i(TAG, "Game changed: '" + s.gameId + "' " + s.title);
		for (Map.Entry<DuoMod, TextView> e : tabs.entrySet()) {
			e.getValue().setVisibility(isAvailable(e.getKey(), s) ? View.VISIBLE : View.GONE);
		}
		if (settingsOpen) {
			return;
		}
		DuoMod mod = pickMod(s);
		if (mod != activeMod) {
			activateMod(mod);
		}
	}

	private boolean isAvailable(DuoMod mod, DuoStatus s) {
		try {
			return mod.getPriority(s) >= 0;
		} catch (Throwable t) {
			Log.e(TAG, "getPriority failed for " + mod.getId(), t);
			return false;
		}
	}

	private DuoMod findMod(String id) {
		if (id == null) {
			return null;
		}
		for (DuoMod mod : mods) {
			if (mod.getId().equals(id)) {
				return mod;
			}
		}
		return null;
	}

	// Remembered choice for this game, then a game-specific mod, then the default.
	private DuoMod pickMod(DuoStatus s) {
		if (s.hasGame() && settings.getRememberPerGame()) {
			DuoMod remembered = findMod(settings.getModForGame(s.gameId));
			if (remembered != null && isAvailable(remembered, s)) {
				return remembered;
			}
		}
		DuoMod best = null;
		int bestPriority = 0;
		for (DuoMod mod : mods) {
			int p;
			try {
				p = mod.getPriority(s);
			} catch (Throwable t) {
				continue;
			}
			if (p > bestPriority) {
				best = mod;
				bestPriority = p;
			}
		}
		if (best != null) {
			return best;
		}
		DuoMod def = findMod(settings.getDefaultMod());
		if (def != null && isAvailable(def, s)) {
			return def;
		}
		for (DuoMod mod : mods) {
			if (isAvailable(mod, s)) {
				return mod;
			}
		}
		return mods.get(0);
	}

	private void onTabClicked(DuoMod mod) {
		if (status.hasGame()) {
			if (settings.getRememberPerGame()) {
				settings.setModForGame(status.gameId, mod.getId());
			}
		} else {
			settings.setDefaultMod(mod.getId());
		}
		closeSettings();
		activateMod(mod);
	}

	private void activateMod(DuoMod mod) {
		if (mod == activeMod) {
			return;
		}
		deactivateMod();
		activeMod = mod;
		tabBarWanted = true;
		applyTabBarVisibility();
		// Don't hand the new mod presses that happened before it existed.
		DuoNative.nativeGetButtonPresses();
		Log.i(TAG, "Activating mod " + mod.getId());
		View view;
		try {
			view = mod.onCreateView(this);
		} catch (Throwable t) {
			reportModError(mod, t);
			view = errorView(mod);
		}
		content.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
		updateTabHighlight();
		try {
			mod.onStatus(status);
			if (hostPaused) {
				mod.onHostPause();
			}
		} catch (Throwable t) {
			reportModError(mod, t);
		}
		// Pick up the new mod's polling rate right away.
		if (polling) {
			handler.removeCallbacks(pollRunnable);
			handler.postDelayed(pollRunnable, Math.max(50, mod.getStatusIntervalMs()));
		}
	}

	private void deactivateMod() {
		if (activeMod == null) {
			return;
		}
		try {
			activeMod.onDestroyView();
		} catch (Throwable t) {
			reportModError(activeMod, t);
		}
		content.removeAllViews();
		DuoNative.nativeReleaseAll();
		DuoNative.nativeSetWatches(null, null);
		DuoNative.nativeCancelGameFiles();
		fileRequests.clear();
		activeMod = null;
		updateTabHighlight();
	}

	private void openSettings() {
		if (settingsOpen) {
			return;
		}
		deactivateMod();
		settingsOpen = true;
		tabBarWanted = true;
		applyTabBarVisibility();
		settingsView = new DuoSettingsView(getContext(), settings, mods, getDisplay(), new DuoSettingsView.Callback() {
			@Override
			public void onDisplayConfigChanged() {
				listener.onDisplayConfigChanged();
			}

			@Override
			public void haptic(View v) {
				DuoPresentation.this.haptic(v);
			}
		});
		content.addView(settingsView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
		updateTabHighlight();
	}

	private void closeSettings() {
		if (!settingsOpen) {
			return;
		}
		settingsOpen = false;
		content.removeView(settingsView);
		settingsView = null;
		updateTabHighlight();
		// Nothing active now; the caller activates a mod, or we fall back to the usual pick.
		if (polling && activeMod == null) {
			handler.post(() -> {
				if (activeMod == null && !settingsOpen && polling) {
					activateMod(pickMod(status));
				}
			});
		}
	}

	private void updateTabHighlight() {
		for (Map.Entry<DuoMod, TextView> e : tabs.entrySet()) {
			e.getValue().setActivated(!settingsOpen && e.getKey() == activeMod);
		}
		if (settingsTab != null) {
			settingsTab.setActivated(settingsOpen);
		}
	}

	private void applyTabBarVisibility() {
		boolean visible = tabBarWanted || settingsOpen;
		tabBar.setVisibility(visible ? View.VISIBLE : View.GONE);
		peekStrip.setVisibility(visible ? View.GONE : View.VISIBLE);
	}

	private final Runnable hidePeekRunnable = this::applyTabBarVisibility;

	private void peekTabBar() {
		tabBar.setVisibility(View.VISIBLE);
		peekStrip.setVisibility(View.GONE);
		handler.removeCallbacks(hidePeekRunnable);
		handler.postDelayed(hidePeekRunnable, TAB_BAR_PEEK_MS);
	}

	private View errorView(DuoMod mod) {
		TextView tv = DuoUi.text(getContext(), getContext().getString(R.string.duo_mod_failed, mod.getId()), 18, DuoUi.COLOR_WARNING);
		tv.setGravity(Gravity.CENTER);
		return tv;
	}

	private void reportModError(DuoMod mod, Throwable t) {
		// A broken mod must never take the emulator down with it.
		Log.e(TAG, "Mod " + mod.getId() + " failed", t);
	}

	private String safeTitle(DuoMod mod) {
		try {
			return mod.getTitle(getContext());
		} catch (Throwable t) {
			return mod.getId();
		}
	}

	// DuoModContext

	@Override
	public DuoSettings getSettings() {
		return settings;
	}

	@Override
	public DuoStatus getStatus() {
		return status;
	}

	@Override
	public Bitmap getGameIcon() {
		return gameIcon;
	}

	@Override
	public void haptic(View view) {
		if (view != null && settings.getHaptics()) {
			view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
		}
	}

	@Override
	public void setTabBarVisible(boolean visible) {
		tabBarWanted = visible;
		handler.removeCallbacks(hidePeekRunnable);
		applyTabBarVisibility();
	}

	@Override
	public void pressButton(int pspButtonMask, boolean down) {
		DuoNative.nativeButton(pspButtonMask, down);
	}

	@Override
	public void tapVirtKey(int virtKey) {
		DuoNative.nativeVirtKey(virtKey, true);
		DuoNative.nativeVirtKey(virtKey, false);
	}

	@Override
	public void virtKey(int virtKey, boolean down) {
		DuoNative.nativeVirtKey(virtKey, down);
	}

	@Override
	public void setAnalog(int stick, float x, float y) {
		DuoNative.nativeAnalog(stick, x, y);
	}

	@Override
	public boolean readGameFile(String path, int offset, int size, GameFileCallback callback) {
		int id = DuoNative.nativeRequestGameFile(path, offset, size);
		if (id == 0) {
			return false;
		}
		fileRequests.put(id, callback);
		return true;
	}

	private void pollGameFiles() {
		if (fileRequests.isEmpty()) {
			return;
		}
		for (Integer id : new java.util.ArrayList<>(fileRequests.keySet())) {
			byte[] data = DuoNative.nativePollGameFile(id);
			if (data == null) {
				continue;
			}
			GameFileCallback cb = fileRequests.remove(id);
			if (cb != null && activeMod != null) {
				try {
					cb.onGameFile(data.length > 0 ? data : null);
				} catch (Throwable t) {
					reportModError(activeMod, t);
				}
			}
		}
	}

	@Override
	public List<DuoButtonPress> pollButtonPresses() {
		return DuoButtonPress.fromNative(DuoNative.nativeGetButtonPresses());
	}

	@Override
	public boolean setMemoryWatches(int[] addresses, int[] sizes) {
		return DuoNative.nativeSetWatches(addresses, sizes);
	}

	@Override
	public byte[] readMemoryWatch(int index) {
		return DuoNative.nativeGetWatch(index);
	}
}
