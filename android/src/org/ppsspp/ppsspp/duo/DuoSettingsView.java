package org.ppsspp.ppsspp.duo;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.graphics.Typeface;
import android.hardware.display.DisplayManager;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.ppsspp.ppsspp.NativeApp;
import org.ppsspp.ppsspp.R;

import java.util.ArrayList;
import java.util.List;

// Settings page of the second screen, opened with the gear tab. Touch-only, so no text input.
final class DuoSettingsView extends ScrollView {
	interface Callback {
		void onDisplayConfigChanged();
		void haptic(View v);
	}

	private final DuoSettings settings;
	private final List<DuoMod> mods;
	private final Display currentDisplay;
	private final Callback callback;
	private final LinearLayout list;

	private final List<TextView> modRows = new ArrayList<>();
	private final List<TextView> displayRows = new ArrayList<>();

	DuoSettingsView(Context context, DuoSettings settings, List<DuoMod> mods, Display currentDisplay, Callback callback) {
		super(context);
		this.settings = settings;
		this.mods = mods;
		this.currentDisplay = currentDisplay;
		this.callback = callback;

		list = new LinearLayout(context);
		list.setOrientation(LinearLayout.VERTICAL);
		int pad = DuoUi.dp(context, 16);
		list.setPadding(pad, pad / 2, pad, pad);
		addView(list, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

		build();
	}

	private void build() {
		Context ctx = getContext();

		header(ctx.getString(R.string.duo_settings_general));
		toggle(ctx.getString(R.string.duo_setting_remember_per_game), settings.getRememberPerGame(), settings::setRememberPerGame);
		toggle(ctx.getString(R.string.duo_setting_haptics), settings.getHaptics(), settings::setHaptics);
		toggle(ctx.getString(R.string.duo_setting_immersive) + "\n" + ctx.getString(R.string.duo_setting_immersive_desc), settings.getImmersive(), value -> {
			settings.setImmersive(value);
			if (value) {
				// Explain the gesture again.
				settings.setGestureHintShown(false);
			}
		});

		header(ctx.getString(R.string.duo_settings_default_mod));
		for (DuoMod mod : mods) {
			// Game-specific mods can't be the default.
			try {
				if (mod.getPriority(DuoStatus.EMPTY) < 0) {
					continue;
				}
			} catch (Throwable t) {
				continue;
			}
			String title;
			String desc;
			try {
				title = mod.getTitle(ctx);
				desc = mod.getDescription(ctx);
			} catch (Throwable t) {
				title = mod.getId();
				desc = "";
			}
			TextView row = row(desc.isEmpty() ? title : title + "\n" + desc);
			row.setTag(mod.getId());
			row.setOnClickListener(v -> {
				callback.haptic(v);
				settings.setDefaultMod((String)v.getTag());
				updateModRows();
			});
			modRows.add(row);
		}
		updateModRows();

		header(ctx.getString(R.string.duo_settings_display));
		TextView auto = row(ctx.getString(R.string.duo_setting_display_auto));
		auto.setTag("");
		displayRows.add(auto);
		DisplayManager dm = (DisplayManager)ctx.getSystemService(Context.DISPLAY_SERVICE);
		if (dm != null) {
			for (Display d : dm.getDisplays()) {
				if (d.getDisplayId() == Display.DEFAULT_DISPLAY) {
					continue;
				}
				android.graphics.Point size = new android.graphics.Point();
				d.getRealSize(size);
				TextView r = row(d.getName() + "  (" + size.x + "\u00d7" + size.y + ")");
				r.setTag(d.getName());
				displayRows.add(r);
			}
		}
		for (TextView r : displayRows) {
			r.setOnClickListener(v -> {
				callback.haptic(v);
				settings.setPreferredDisplay((String)v.getTag());
				updateDisplayRows();
				callback.onDisplayConfigChanged();
			});
		}
		updateDisplayRows();

		header("");
		TextView off = DuoUi.button(ctx, ctx.getString(R.string.duo_turn_off));
		off.setTextColor(DuoUi.COLOR_WARNING);
		off.setOnClickListener(v -> {
			callback.haptic(v);
			NativeApp.sendMessageFromJava("duo_set_enabled", "0");
			callback.onDisplayConfigChanged();
		});
		list.addView(off, rowParams());
		TextView hint = DuoUi.text(ctx, ctx.getString(R.string.duo_turn_off_hint), 13, DuoUi.COLOR_TEXT_DIM);
		hint.setGravity(Gravity.CENTER);
		list.addView(hint, rowParams());

		TextView about = DuoUi.text(ctx, aboutText(ctx), 12, DuoUi.COLOR_TEXT_DIM);
		about.setGravity(Gravity.CENTER);
		about.setPadding(0, DuoUi.dp(ctx, 16), 0, 0);
		list.addView(about);
	}

	private String aboutText(Context ctx) {
		String version = "";
		try {
			PackageInfo info = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
			version = info.versionName;
		} catch (Exception ignored) {
		}
		return "PPSSPP Duo " + version + "\n" + DuoDisplayPicker.describe(currentDisplay);
	}

	private void updateModRows() {
		String def = settings.getDefaultMod();
		for (TextView r : modRows) {
			r.setActivated(def.equals(r.getTag()));
		}
	}

	private void updateDisplayRows() {
		String pref = settings.getPreferredDisplay();
		for (TextView r : displayRows) {
			r.setActivated(pref.equals(r.getTag()));
		}
	}

	private interface BoolSetter {
		void set(boolean value);
	}

	private void toggle(String title, boolean initial, BoolSetter setter) {
		TextView row = row(title);
		row.setActivated(initial);
		row.setOnClickListener(v -> {
			callback.haptic(v);
			boolean value = !v.isActivated();
			v.setActivated(value);
			setter.set(value);
		});
	}

	private void header(String title) {
		TextView tv = DuoUi.text(getContext(), title, 14, DuoUi.COLOR_ACCENT);
		tv.setTypeface(Typeface.DEFAULT_BOLD);
		tv.setPadding(DuoUi.dp(getContext(), 4), DuoUi.dp(getContext(), 14), 0, DuoUi.dp(getContext(), 4));
		list.addView(tv);
	}

	// A full-width row that shows its "activated" state, used both for toggles and choices.
	private TextView row(String text) {
		TextView tv = DuoUi.button(getContext(), text);
		tv.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
		tv.setTextSize(16);
		tv.setTypeface(Typeface.DEFAULT);
		int padH = DuoUi.dp(getContext(), 16);
		int padV = DuoUi.dp(getContext(), 12);
		tv.setPadding(padH, padV, padH, padV);
		list.addView(tv, rowParams());
		return tv;
	}

	private LinearLayout.LayoutParams rowParams() {
		LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		lp.setMargins(0, DuoUi.dp(getContext(), 3), 0, DuoUi.dp(getContext(), 3));
		return lp;
	}
}
