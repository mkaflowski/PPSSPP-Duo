package org.ppsspp.ppsspp.duo.mods;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.os.BatteryManager;
import android.os.SystemClock;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.ppsspp.ppsspp.R;
import org.ppsspp.ppsspp.duo.DuoMod;
import org.ppsspp.ppsspp.duo.DuoModContext;
import org.ppsspp.ppsspp.duo.DuoNative;
import org.ppsspp.ppsspp.duo.DuoStatus;
import org.ppsspp.ppsspp.duo.DuoUi;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

// Game info and quick actions: save states, fast forward, rewind, screenshot, pause.
public final class DashboardMod extends DuoMod {
	public static final String ID = "dashboard";

	private DuoModContext host;
	private ImageView icon;
	private TextView title;
	private TextView subtitle;
	private TextView perf;
	private TextView clock;
	private TextView slotLabel;
	private TextView fastForward;
	private final List<View> gameButtons = new ArrayList<>();

	private Bitmap shownIcon;
	private String sessionGame = "";
	private long sessionStart;
	private boolean holdingFastForward;
	private long lastClockUpdate;

	@Override
	public String getId() {
		return ID;
	}

	@Override
	public String getTitle(Context context) {
		return context.getString(R.string.duo_mod_dashboard);
	}

	@Override
	public String getDescription(Context context) {
		return context.getString(R.string.duo_mod_dashboard_desc);
	}

	@Override
	public long getStatusIntervalMs() {
		return 250;
	}

	@Override
	public View onCreateView(DuoModContext host) {
		this.host = host;
		Context ctx = host.getContext();
		gameButtons.clear();
		shownIcon = null;
		holdingFastForward = false;
		lastClockUpdate = 0;

		LinearLayout root = new LinearLayout(ctx);
		root.setOrientation(LinearLayout.VERTICAL);
		int pad = DuoUi.dp(ctx, 12);
		root.setPadding(pad, pad, pad, pad);

		root.addView(buildHeader(ctx), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, DuoUi.dp(ctx, 88)));

		LinearLayout grid = new LinearLayout(ctx);
		grid.setOrientation(LinearLayout.VERTICAL);
		LinearLayout.LayoutParams gridLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f);
		gridLp.topMargin = DuoUi.dp(ctx, 10);
		root.addView(grid, gridLp);

		// Row 1: save states.
		LinearLayout row1 = row(ctx, grid);
		action(row1, ctx.getString(R.string.duo_save_state), DuoNative.VIRTKEY_SAVE_STATE);
		action(row1, ctx.getString(R.string.duo_load_state), DuoNative.VIRTKEY_LOAD_STATE);
		row1.addView(buildSlotPicker(ctx), cell(ctx, 1.4f));

		// Row 2: speed.
		LinearLayout row2 = row(ctx, grid);
		fastForward = DuoUi.button(ctx, ctx.getString(R.string.duo_fast_forward));
		fastForward.setOnClickListener(v -> {
			host.haptic(v);
			// Hold the virtual key down until tapped again, like a held button.
			holdingFastForward = !holdingFastForward;
			host.virtKey(DuoNative.VIRTKEY_FASTFORWARD, holdingFastForward);
			v.setActivated(holdingFastForward);
		});
		gameButtons.add(fastForward);
		row2.addView(fastForward, cell(ctx, 1.0f));
		action(row2, ctx.getString(R.string.duo_speed_toggle), DuoNative.VIRTKEY_SPEED_TOGGLE);
		action(row2, ctx.getString(R.string.duo_rewind), DuoNative.VIRTKEY_REWIND);

		// Row 3: misc.
		LinearLayout row3 = row(ctx, grid);
		action(row3, ctx.getString(R.string.duo_pause_menu), DuoNative.VIRTKEY_PAUSE);
		action(row3, ctx.getString(R.string.duo_freeze), DuoNative.VIRTKEY_PAUSE_NO_MENU);
		action(row3, ctx.getString(R.string.duo_screenshot), DuoNative.VIRTKEY_SCREENSHOT);
		action(row3, ctx.getString(R.string.duo_mute), DuoNative.VIRTKEY_MUTE_TOGGLE);

		return root;
	}

	private View buildHeader(Context ctx) {
		LinearLayout header = new LinearLayout(ctx);
		header.setOrientation(LinearLayout.HORIZONTAL);
		header.setGravity(Gravity.CENTER_VERTICAL);
		header.setBackground(DuoUi.rounded(ctx, DuoUi.COLOR_SURFACE, 14));
		int pad = DuoUi.dp(ctx, 10);
		header.setPadding(pad, pad, pad * 2, pad);

		icon = new ImageView(ctx);
		icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
		// ICON0 is 144x80.
		header.addView(icon, new LinearLayout.LayoutParams(DuoUi.dp(ctx, 122), ViewGroup.LayoutParams.MATCH_PARENT));

		LinearLayout texts = new LinearLayout(ctx);
		texts.setOrientation(LinearLayout.VERTICAL);
		texts.setPadding(DuoUi.dp(ctx, 12), 0, 0, 0);
		title = DuoUi.text(ctx, "", 19, DuoUi.COLOR_TEXT);
		title.setTypeface(Typeface.DEFAULT_BOLD);
		title.setSingleLine(true);
		title.setEllipsize(android.text.TextUtils.TruncateAt.END);
		subtitle = DuoUi.text(ctx, "", 14, DuoUi.COLOR_TEXT_DIM);
		subtitle.setSingleLine(true);
		texts.addView(title);
		texts.addView(subtitle);
		header.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

		LinearLayout right = new LinearLayout(ctx);
		right.setOrientation(LinearLayout.VERTICAL);
		right.setGravity(Gravity.END);
		perf = DuoUi.text(ctx, "", 22, DuoUi.COLOR_GOOD);
		perf.setTypeface(Typeface.DEFAULT_BOLD);
		perf.setGravity(Gravity.END);
		clock = DuoUi.text(ctx, "", 14, DuoUi.COLOR_TEXT_DIM);
		clock.setGravity(Gravity.END);
		right.addView(perf);
		right.addView(clock);
		header.addView(right, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
		return header;
	}

	private View buildSlotPicker(Context ctx) {
		LinearLayout picker = new LinearLayout(ctx);
		picker.setOrientation(LinearLayout.HORIZONTAL);
		picker.setBackground(DuoUi.buttonBackground(ctx));
		gameButtons.add(picker);

		TextView prev = DuoUi.button(ctx, "\u25C0");
		prev.setOnClickListener(v -> {
			host.haptic(v);
			host.tapVirtKey(DuoNative.VIRTKEY_PREVIOUS_SLOT);
		});
		TextView next = DuoUi.button(ctx, "\u25B6");
		next.setOnClickListener(v -> {
			host.haptic(v);
			host.tapVirtKey(DuoNative.VIRTKEY_NEXT_SLOT);
		});
		slotLabel = DuoUi.text(ctx, "", 15, DuoUi.COLOR_TEXT);
		slotLabel.setGravity(Gravity.CENTER);

		picker.addView(prev, new LinearLayout.LayoutParams(DuoUi.dp(ctx, 52), ViewGroup.LayoutParams.MATCH_PARENT));
		picker.addView(slotLabel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.0f));
		picker.addView(next, new LinearLayout.LayoutParams(DuoUi.dp(ctx, 52), ViewGroup.LayoutParams.MATCH_PARENT));
		gameButtons.add(prev);
		gameButtons.add(next);
		return picker;
	}

	private LinearLayout row(Context ctx, LinearLayout grid) {
		LinearLayout row = new LinearLayout(ctx);
		row.setOrientation(LinearLayout.HORIZONTAL);
		grid.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f));
		return row;
	}

	private LinearLayout.LayoutParams cell(Context ctx, float weight) {
		LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight);
		int m = DuoUi.dp(ctx, 5);
		lp.setMargins(m, m, m, m);
		return lp;
	}

	private void action(LinearLayout row, String label, int virtKey) {
		Context ctx = row.getContext();
		TextView b = DuoUi.button(ctx, label);
		b.setOnClickListener(v -> {
			host.haptic(v);
			host.tapVirtKey(virtKey);
		});
		gameButtons.add(b);
		row.addView(b, cell(ctx, 1.0f));
	}

	@Override
	public void onStatus(DuoStatus s) {
		Context ctx = host.getContext();
		boolean hasGame = s.hasGame();

		for (View v : gameButtons) {
			v.setEnabled(hasGame);
		}

		Bitmap bmp = host.getGameIcon();
		if (bmp != shownIcon) {
			shownIcon = bmp;
			icon.setImageBitmap(bmp);
		}
		icon.setVisibility(bmp != null ? View.VISIBLE : View.GONE);

		if (!hasGame) {
			title.setText(R.string.duo_no_game);
			subtitle.setText(R.string.duo_no_game_hint);
			perf.setText("");
			slotLabel.setText("");
			sessionGame = "";
			if (holdingFastForward) {
				holdingFastForward = false;
				fastForward.setActivated(false);
			}
		} else {
			if (!s.path.equals(sessionGame)) {
				sessionGame = s.path;
				sessionStart = SystemClock.elapsedRealtime();
			}
			title.setText(s.displayTitle());
			long minutes = (SystemClock.elapsedRealtime() - sessionStart) / 60000;
			String state = s.isPauseMenu() ? ctx.getString(R.string.duo_state_paused)
				: s.stepping ? ctx.getString(R.string.duo_state_frozen) : "";
			subtitle.setText(join(" \u00b7 ", s.gameId, ctx.getString(R.string.duo_session_minutes, (int)minutes), state));

			int speed = s.speedPercent();
			perf.setText(ctx.getString(R.string.duo_perf, Math.round(s.fps), speed));
			perf.setTextColor(speed >= 97 ? DuoUi.COLOR_GOOD : DuoUi.COLOR_WARNING);

			String slotText = ctx.getString(R.string.duo_slot, s.slot + 1);
			slotText += "\n" + (s.slotUsed ? s.slotDate : ctx.getString(R.string.duo_slot_empty));
			slotLabel.setText(slotText);
		}
		fastForward.setActivated(holdingFastForward || s.fastForward);
		long now = SystemClock.elapsedRealtime();
		if (now - lastClockUpdate > 5000) {
			lastClockUpdate = now;
			clock.setText(clockText(ctx));
		}
	}

	private static String join(String sep, String... parts) {
		StringBuilder sb = new StringBuilder();
		for (String p : parts) {
			if (p == null || p.isEmpty()) {
				continue;
			}
			if (sb.length() > 0) {
				sb.append(sep);
			}
			sb.append(p);
		}
		return sb.toString();
	}

	private static String clockText(Context ctx) {
		String time = DateFormat.getTimeFormat(ctx).format(new Date());
		Intent battery = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
		if (battery == null) {
			return time;
		}
		int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
		int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
		int plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
		if (level < 0 || scale <= 0) {
			return time;
		}
		return time + "  " + (level * 100 / scale) + "%" + (plugged != 0 ? " \u26A1" : "");
	}

	@Override
	public void onHostPause() {
		if (holdingFastForward) {
			// The host releases everything on pause, keep the button in sync.
			holdingFastForward = false;
			fastForward.setActivated(false);
		}
	}

	@Override
	public void onDestroyView() {
		host = null;
		gameButtons.clear();
		icon = null;
		shownIcon = null;
	}
}
