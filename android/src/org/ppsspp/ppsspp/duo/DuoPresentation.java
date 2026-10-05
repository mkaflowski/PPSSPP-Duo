package org.ppsspp.ppsspp.duo;

import android.app.Presentation;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.ppsspp.ppsspp.R;

import java.util.ArrayList;
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
	// Back gesture: where a swipe has to start, and how far in it has to go.
	private static final float BACK_EDGE_DP = 32;
	// Taken from the system's back gesture: the most Android allows per edge, and a bit wider than
	// its edge so the start of a swipe is always ours.
	private static final float BACK_ZONE_DP = 200;
	private static final float BACK_EXCLUSION_DP = 48;
	private static final float BACK_COMMIT_DP = 72;
	private static final float BACK_ARROW_DP = 44;

	interface Listener {
		// The user changed something that affects which display we're on (or whether we're shown at all).
		void onDisplayConfigChanged();
	}

	private final DuoSettings settings;
	private final List<DuoMod> mods;
	private final Listener listener;
	private final Handler handler = new Handler(Looper.getMainLooper());

	private FrameLayout root;
	private LinearLayout tabBar;
	private FrameLayout content;
	private View peekStrip;
	private TextView backArrow;
	private GradientDrawable backArrowBackground;
	private DuoGestureHint gestureHint;
	private final Map<DuoMod, TextView> tabs = new HashMap<>();
	private final Map<Integer, GameFileCallback> fileRequests = new HashMap<>();
	private final Map<Integer, FindCallback> findRequests = new HashMap<>();
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

	private int backEdge;  // -1: swipe from the left edge, 1: from the right, 0: none
	private boolean backDragging;
	private float backDownX;
	private float backDownY;

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
		root = new FrameLayout(ctx);
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

		// Follows the finger during the back gesture.
		backArrowBackground = new GradientDrawable();
		backArrowBackground.setShape(GradientDrawable.OVAL);
		backArrow = DuoUi.text(ctx, "", 26, DuoUi.COLOR_TEXT);
		backArrow.setGravity(Gravity.CENTER);
		backArrow.setTypeface(Typeface.DEFAULT_BOLD);
		backArrow.setBackground(backArrowBackground);
		backArrow.setVisibility(View.GONE);
		int arrowSize = DuoUi.dp(ctx, BACK_ARROW_DP);
		root.addView(backArrow, new FrameLayout.LayoutParams(arrowSize, arrowSize, Gravity.TOP | Gravity.START));
		root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> updateGestureExclusion());
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
		// In immersive mode, picking a tab (even the current one) puts the bar away.
		handler.removeCallbacks(hidePeekRunnable);
		applyTabBarVisibility();
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
		findRequests.clear();
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
		applyTabBarVisibility();
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

	// Immersive mode only covers the screens made for the running game (priority above 0); the
	// generic mods and the settings keep their tabs.
	private boolean isImmersive() {
		if (settingsOpen || activeMod == null || !settings.getImmersive()) {
			return false;
		}
		try {
			return activeMod.getPriority(status) > 0;
		} catch (Throwable t) {
			return false;
		}
	}

	// Whether the tab bar is up without being peeked.
	private boolean tabBarShownByDefault() {
		return settingsOpen || (tabBarWanted && !isImmersive());
	}

	private boolean isTabBarPeeking() {
		return tabBar.getVisibility() == View.VISIBLE && !tabBarShownByDefault();
	}

	private void applyTabBarVisibility() {
		boolean visible = tabBarShownByDefault();
		boolean immersive = isImmersive();
		tabBar.setVisibility(visible ? View.VISIBLE : View.GONE);
		// In immersive mode the top edge belongs to the mod; only the back gesture brings the bar.
		peekStrip.setVisibility(visible || immersive ? View.GONE : View.VISIBLE);
		if (immersive && !visible) {
			maybeShowGestureHint();
		} else if (!immersive && gestureHint != null) {
			// Left the game screen before reading it; show it again next time.
			root.removeView(gestureHint);
			gestureHint = null;
		}
		updateGestureExclusion();
	}

	// The system's back gesture takes edge swipes before this window sees them (and sends the back
	// to whatever has focus, never to us). Android lets a window keep up to 200dp of each edge, so
	// while the tabs are hidden or the settings are open, the middle of both side edges is ours.
	private void updateGestureExclusion() {
		if (Build.VERSION.SDK_INT < 29 || root == null) {
			return;
		}
		List<Rect> rects = new ArrayList<>();
		int width = root.getWidth();
		int height = root.getHeight();
		if ((settingsOpen || !tabBarShownByDefault()) && width > 0 && height > 0) {
			Context ctx = getContext();
			int zone = Math.min(height, DuoUi.dp(ctx, BACK_ZONE_DP));
			int top = (height - zone) / 2;
			int edge = DuoUi.dp(ctx, BACK_EXCLUSION_DP);
			rects.add(new Rect(0, top, edge, top + zone));
			rects.add(new Rect(width - edge, top, width, top + zone));
		}
		root.setSystemGestureExclusionRects(rects);
	}

	// The first time the tabs are hidden, explain how to get them back.
	private void maybeShowGestureHint() {
		if (gestureHint != null || settings.getGestureHintShown()) {
			return;
		}
		gestureHint = new DuoGestureHint(getContext(), BACK_ZONE_DP, v -> {
			haptic(v);
			dismissGestureHint();
		});
		// Under the back arrow, so the gesture can be tried right away.
		root.addView(gestureHint, root.indexOfChild(backArrow), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
	}

	private void dismissGestureHint() {
		if (gestureHint == null) {
			return;
		}
		settings.setGestureHintShown(true);
		root.removeView(gestureHint);
		gestureHint = null;
	}

	private final Runnable hidePeekRunnable = this::applyTabBarVisibility;

	private void peekTabBar() {
		tabBar.setVisibility(View.VISIBLE);
		peekStrip.setVisibility(View.GONE);
		handler.removeCallbacks(hidePeekRunnable);
		handler.postDelayed(hidePeekRunnable, TAB_BAR_PEEK_MS);
	}

	// Like Android's back: closes the settings, else shows a hidden tab bar or hides a peeked one.
	private void onBackGesture() {
		dismissGestureHint();
		if (settingsOpen) {
			closeSettings();
		} else if (tabBar.getVisibility() == View.VISIBLE) {
			handler.removeCallbacks(hidePeekRunnable);
			applyTabBarVisibility();
		} else {
			peekTabBar();
		}
	}

	@Override
	public boolean dispatchTouchEvent(MotionEvent ev) {
		if (trackBackGesture(ev)) {
			return true;
		}
		return super.dispatchTouchEvent(ev);
	}

	// A swipe in from the left or right edge. The mod sees the touch until the swipe is recognized,
	// then gets a cancel. Returns true while the gesture owns the touch.
	private boolean trackBackGesture(MotionEvent ev) {
		Context ctx = getContext();
		switch (ev.getActionMasked()) {
		case MotionEvent.ACTION_DOWN: {
			backEdge = 0;
			backDragging = false;
			if (isTabBarPeeking()) {
				// Keep a peeked bar up while it's being used.
				handler.removeCallbacks(hidePeekRunnable);
				handler.postDelayed(hidePeekRunnable, TAB_BAR_PEEK_MS);
			}
			if (!settingsOpen && tabBarShownByDefault()) {
				// The gesture would do nothing, leave the edges to the mod.
				return false;
			}
			Window window = getWindow();
			int width = window != null ? window.getDecorView().getWidth() : 0;
			float edge = DuoUi.dp(ctx, BACK_EDGE_DP);
			if (ev.getX() < edge) {
				backEdge = -1;
			} else if (width > 0 && ev.getX() > width - edge) {
				backEdge = 1;
			}
			backDownX = ev.getX();
			backDownY = ev.getY();
			return false;
		}
		case MotionEvent.ACTION_POINTER_DOWN:
			if (!backDragging) {
				// More fingers: that's play, not a gesture.
				backEdge = 0;
			}
			return backDragging;
		case MotionEvent.ACTION_MOVE: {
			if (backEdge == 0) {
				return false;
			}
			float inward = (ev.getX() - backDownX) * -backEdge;
			if (!backDragging) {
				float dy = Math.abs(ev.getY() - backDownY);
				int slop = ViewConfiguration.get(ctx).getScaledTouchSlop();
				if (dy > slop && dy > inward) {
					backEdge = 0;
					return false;
				}
				if (inward <= slop) {
					return false;
				}
				backDragging = true;
				MotionEvent cancel = MotionEvent.obtain(ev);
				cancel.setAction(MotionEvent.ACTION_CANCEL);
				super.dispatchTouchEvent(cancel);
				cancel.recycle();
			}
			showBackArrow(ev.getY(), inward);
			return true;
		}
		case MotionEvent.ACTION_UP:
		case MotionEvent.ACTION_CANCEL: {
			boolean dragging = backDragging;
			float inward = (ev.getX() - backDownX) * -backEdge;
			backEdge = 0;
			backDragging = false;
			if (!dragging) {
				return false;
			}
			backArrow.setVisibility(View.GONE);
			if (ev.getActionMasked() == MotionEvent.ACTION_UP && inward >= DuoUi.dp(ctx, BACK_COMMIT_DP)) {
				haptic(backArrow);
				onBackGesture();
			}
			return true;
		}
		default:
			return backDragging;
		}
	}

	private void showBackArrow(float y, float inward) {
		Context ctx = getContext();
		float size = DuoUi.dp(ctx, BACK_ARROW_DP);
		float progress = Math.max(0.0f, Math.min(1.0f, inward / DuoUi.dp(ctx, BACK_COMMIT_DP)));
		backArrow.setText(backEdge < 0 ? "\u2039" : "\u203A");  // single angle quotes
		backArrowBackground.setColor(progress >= 1.0f ? DuoUi.COLOR_ACCENT : DuoUi.COLOR_SURFACE_PRESSED);
		backArrow.setAlpha(0.3f + 0.7f * progress);
		float travel = size * 0.5f + progress * DuoUi.dp(ctx, 16);
		View parent = (View)backArrow.getParent();
		backArrow.setTranslationX(backEdge < 0 ? travel - size : parent.getWidth() - travel);
		backArrow.setTranslationY(y - size * 0.5f);
		backArrow.setVisibility(View.VISIBLE);
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

	@Override
	public boolean findMemory(int start, int end, int[] offsets, int[] values, FindCallback callback) {
		int id = DuoNative.nativeRequestFind(start, end, offsets, values);
		if (id == 0) {
			return false;
		}
		findRequests.put(id, callback);
		return true;
	}

	private void pollFinds() {
		if (findRequests.isEmpty()) {
			return;
		}
		for (Integer id : new java.util.ArrayList<>(findRequests.keySet())) {
			long result = DuoNative.nativePollFind(id);
			if (result < 0) {
				continue;
			}
			FindCallback cb = findRequests.remove(id);
			if (cb != null && activeMod != null) {
				try {
					cb.onFound((int)result);
				} catch (Throwable t) {
					reportModError(activeMod, t);
				}
			}
		}
	}

	private void pollGameFiles() {
		pollFinds();
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
