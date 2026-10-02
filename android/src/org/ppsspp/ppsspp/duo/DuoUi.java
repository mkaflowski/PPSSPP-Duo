package org.ppsspp.ppsspp.duo;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.TextView;

// Shared look for the host and the built-in mods. Views are built in code, so mods don't need layouts.
public final class DuoUi {
	private DuoUi() {}

	public static final int COLOR_BACKGROUND = 0xFF0D1117;
	public static final int COLOR_BAR = 0xFF141A22;
	public static final int COLOR_SURFACE = 0xFF1C2430;
	public static final int COLOR_SURFACE_PRESSED = 0xFF2C3A4D;
	public static final int COLOR_SURFACE_DISABLED = 0xFF151B23;
	public static final int COLOR_ACCENT = 0xFF3D9BE9;
	public static final int COLOR_ACCENT_PRESSED = 0xFF2B7CC2;
	public static final int COLOR_TEXT = 0xFFE6EDF3;
	public static final int COLOR_TEXT_DIM = 0xFF8B98A5;
	public static final int COLOR_TEXT_DISABLED = 0xFF4A5560;
	public static final int COLOR_WARNING = 0xFFE3A33B;
	public static final int COLOR_GOOD = 0xFF4CC38A;

	public static int dp(Context context, float dp) {
		return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, context.getResources().getDisplayMetrics()));
	}

	public static GradientDrawable rounded(Context context, int color, float radiusDp) {
		GradientDrawable d = new GradientDrawable();
		d.setColor(color);
		d.setCornerRadius(dp(context, radiusDp));
		return d;
	}

	// Background with pressed, activated (toggled on) and disabled states.
	public static Drawable buttonBackground(Context context) {
		StateListDrawable states = new StateListDrawable();
		states.addState(new int[] {-android.R.attr.state_enabled}, rounded(context, COLOR_SURFACE_DISABLED, 14));
		states.addState(new int[] {android.R.attr.state_activated, android.R.attr.state_pressed}, rounded(context, COLOR_ACCENT_PRESSED, 14));
		states.addState(new int[] {android.R.attr.state_activated}, rounded(context, COLOR_ACCENT, 14));
		states.addState(new int[] {android.R.attr.state_pressed}, rounded(context, COLOR_SURFACE_PRESSED, 14));
		states.addState(new int[] {}, rounded(context, COLOR_SURFACE, 14));
		return states;
	}

	public static TextView text(Context context, CharSequence text, float sizeSp, int color) {
		TextView tv = new TextView(context);
		tv.setText(text);
		tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
		tv.setTextColor(color);
		return tv;
	}

	public static TextView button(Context context, CharSequence text) {
		TextView tv = text(context, text, 18, COLOR_TEXT);
		tv.setTextColor(new ColorStateList(
			new int[][] {{-android.R.attr.state_enabled}, {}},
			new int[] {COLOR_TEXT_DISABLED, COLOR_TEXT}));
		tv.setGravity(Gravity.CENTER);
		tv.setTypeface(Typeface.DEFAULT_BOLD);
		tv.setBackground(buttonBackground(context));
		int pad = dp(context, 8);
		tv.setPadding(pad, pad, pad, pad);
		tv.setClickable(true);
		tv.setFocusable(false);
		tv.setSoundEffectsEnabled(false);
		return tv;
	}
}
