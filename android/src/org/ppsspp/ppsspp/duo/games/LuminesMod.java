package org.ppsspp.ppsspp.duo.games;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;

import org.ppsspp.ppsspp.R;
import org.ppsspp.ppsspp.duo.DuoMod;
import org.ppsspp.ppsspp.duo.DuoModContext;
import org.ppsspp.ppsspp.duo.DuoStatus;
import org.ppsspp.ppsspp.duo.DuoUi;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Locale;

// Lumines: big preview of the next blocks and live stats.
//
// Layout found on ULES00043 v1.01. The game objects live on the game's own heap, so they're
// located every session by a signature scan rather than a fixed address:
// - session object: the only object with these two code pointers at +0x38 / +0x40. +0x30 points
//   to the block queue object, +0x34 to the score object.
// - block queue: +0x24 next ring slot to write, +0x2C pointer to a ring of 5 blocks of 5 u32
//   (header, then cells TL, TR, BL, BR; 1 = first skin color, 2 = second). Slot "next" holds the
//   falling block; the three after it are the visible queue (the slot before it is generated
//   ahead and not shown by the game).
// - score object: +0x04 time in frames, +0x08 score, +0x0C high score, +0x14 squares deleted,
//   +0x2C empty cells (of 160).
public final class LuminesMod extends DuoMod {
	public static final String ID = "lumines";
	private static final String TAG = "PPSSPPDuo";

	private static final String[] GAME_IDS = {"ULES00043", "ULUS10027", "ULJM05012"};

	private static final int SCAN_START = 0x08804000;
	private static final int SCAN_END = 0x0A000000;
	private static final int[] SESSION_OFFSETS = {0x38, 0x40};
	private static final int[] SESSION_VALUES = {0x0880C488, 0x0880C5E4};

	private static final int RING_SLOTS = 5;
	private static final int RING_RECORD = 20;
	private static final int BOARD_CELLS = 16 * 10;

	private static final int W_SESSION = 0;
	private static final int W_QUEUE = 1;
	private static final int W_SCORE = 2;
	private static final int W_RING = 3;

	private DuoModContext host;
	private LuminesView view;

	private int session, queueObj, scoreObj, ring;
	private boolean searching;
	private long lastSearch;
	private boolean unsupported;

	@Override
	public String getId() {
		return ID;
	}

	@Override
	public String getTitle(Context context) {
		return context.getString(R.string.duo_mod_lumines);
	}

	@Override
	public String getDescription(Context context) {
		return context.getString(R.string.duo_mod_lumines_desc);
	}

	@Override
	public int getPriority(DuoStatus s) {
		boolean match = Arrays.asList(GAME_IDS).contains(s.gameId) || s.title.trim().equalsIgnoreCase("LUMINES");
		return match ? 100 : -1;
	}

	@Override
	public long getStatusIntervalMs() {
		return 100;
	}

	@Override
	public View onCreateView(DuoModContext host) {
		this.host = host;
		view = new LuminesView(host.getContext());
		session = queueObj = scoreObj = ring = 0;
		searching = false;
		unsupported = false;
		return view;
	}

	private void watch() {
		int fallback = SCAN_START;
		host.setMemoryWatches(
			new int[] {session != 0 ? session : fallback, queueObj != 0 ? queueObj : fallback, scoreObj != 0 ? scoreObj : fallback, ring != 0 ? ring : fallback},
			new int[] {0x48, 0x30, 0x40, RING_SLOTS * RING_RECORD});
	}

	private void search() {
		long now = SystemClock.uptimeMillis();
		if (searching || now - lastSearch < 2000) {
			return;
		}
		searching = true;
		lastSearch = now;
		boolean queued = host.findMemory(SCAN_START, SCAN_END, SESSION_OFFSETS, SESSION_VALUES, addr -> {
			searching = false;
			if (addr == 0) {
				// Not in a game (menus), or a version with another layout.
				unsupported = true;
				return;
			}
			unsupported = false;
			if (addr != session) {
				Log.i(TAG, "Lumines: session object at " + Integer.toHexString(addr));
				session = addr;
				queueObj = scoreObj = ring = 0;
				watch();
			}
		});
		if (!queued) {
			searching = false;
		}
	}

	private static int u32(ByteBuffer b, int o) {
		return b.getInt(o);
	}

	private static boolean isPointer(int p) {
		return p >= 0x08800000 && p < 0x0A000000;
	}

	@Override
	public void onStatus(DuoStatus s) {
		if (!s.isInGame() && !s.isPauseMenu()) {
			view.setData(null, null, null);
			return;
		}
		if (session == 0) {
			search();
			view.setData(null, null, null);
			view.setMessage(unsupported ? R.string.duo_lumines_waiting : 0);
			return;
		}
		byte[] sessionData = host.readMemoryWatch(W_SESSION);
		if (sessionData == null) {
			return;
		}
		ByteBuffer sb = ByteBuffer.wrap(sessionData).order(ByteOrder.LITTLE_ENDIAN);
		if (u32(sb, 0x38) != SESSION_VALUES[0] || u32(sb, 0x40) != SESSION_VALUES[1]) {
			// The object went away (game over, back to the menu): find the next one.
			session = 0;
			search();
			return;
		}
		int q = u32(sb, 0x30), sc = u32(sb, 0x34);
		if (!isPointer(q) || !isPointer(sc)) {
			return;
		}
		if (q != queueObj || sc != scoreObj) {
			queueObj = q;
			scoreObj = sc;
			ring = 0;
			watch();
			return;
		}
		byte[] queueData = host.readMemoryWatch(W_QUEUE);
		byte[] scoreData = host.readMemoryWatch(W_SCORE);
		if (queueData == null || scoreData == null) {
			return;
		}
		ByteBuffer qb = ByteBuffer.wrap(queueData).order(ByteOrder.LITTLE_ENDIAN);
		int r = u32(qb, 0x2C);
		int next = u32(qb, 0x24);
		if (!isPointer(r) || next < 0 || next >= RING_SLOTS) {
			return;
		}
		if (r != ring) {
			ring = r;
			watch();
			return;
		}
		byte[] ringData = host.readMemoryWatch(W_RING);
		if (ringData == null) {
			return;
		}
		ByteBuffer rb = ByteBuffer.wrap(ringData).order(ByteOrder.LITTLE_ENDIAN);
		// Falling block, then the three visible ones.
		int[][] blocks = new int[4][4];
		for (int i = 0; i < 4; i++) {
			int slot = (next + i) % RING_SLOTS;
			for (int c = 0; c < 4; c++) {
				int v = u32(rb, slot * RING_RECORD + 4 + c * 4);
				if (v != 1 && v != 2) {
					return;  // mid-update or not a block
				}
				blocks[i][c] = v;
			}
		}
		ByteBuffer cb = ByteBuffer.wrap(scoreData).order(ByteOrder.LITTLE_ENDIAN);
		int[] stats = {u32(cb, 0x08), u32(cb, 0x0C), u32(cb, 0x14), u32(cb, 0x04), u32(cb, 0x2C)};
		view.setMessage(0);
		view.setData(blocks, stats, s);
	}

	@Override
	public void onDestroyView() {
		view = null;
		host = null;
	}

	private static final class LuminesView extends View {
		private static final int COLOR_1 = 0xFFE9EEF2;
		private static final int COLOR_2 = 0xFFF2933A;

		private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
		private final RectF rect = new RectF();
		private int[][] blocks;
		private int[] stats;
		private int messageRes;
		private String signature = "";

		LuminesView(Context context) {
			super(context);
			setBackgroundColor(DuoUi.COLOR_BACKGROUND);
		}

		void setMessage(int res) {
			if (res != messageRes) {
				messageRes = res;
				invalidate();
			}
		}

		void setData(int[][] blocks, int[] stats, DuoStatus s) {
			String sig = blocks == null ? "" : Arrays.deepToString(blocks) + Arrays.toString(stats);
			if (!sig.equals(signature)) {
				signature = sig;
				this.blocks = blocks;
				this.stats = stats;
				invalidate();
			}
		}

		@Override
		protected void onDraw(Canvas canvas) {
			Context ctx = getContext();
			float w = getWidth(), h = getHeight();
			float m = DuoUi.dp(ctx, 12);
			paint.setTypeface(Typeface.DEFAULT_BOLD);
			if (blocks == null) {
				paint.setColor(DuoUi.COLOR_TEXT_DIM);
				paint.setTextAlign(Paint.Align.CENTER);
				paint.setTextSize(DuoUi.dp(ctx, 18));
				String text = ctx.getString(messageRes != 0 ? messageRes : R.string.duo_lumines_idle);
				canvas.drawText(text, w / 2, h / 2, paint);
				return;
			}

			// Left: next blocks, the soonest at the top and biggest.
			float colW = w * 0.48f;
			float[] sizes = {1.0f, 0.8f, 0.65f};
			float total = 0;
			for (float s : sizes) {
				total += s;
			}
			float labelH = DuoUi.dp(ctx, 22);
			float avail = h - 2 * m - labelH;
			float unit = Math.min(avail / (total + 0.5f), colW - 2 * m);
			paint.setTextAlign(Paint.Align.LEFT);
			paint.setTextSize(DuoUi.dp(ctx, 15));
			paint.setColor(DuoUi.COLOR_TEXT_DIM);
			canvas.drawText(ctx.getString(R.string.duo_lumines_next), m, m + DuoUi.dp(ctx, 15), paint);
			float y = m + labelH;
			for (int i = 0; i < 3; i++) {
				float size = unit * sizes[i];
				float x = m + (colW - 2 * m - size) / 2;
				drawBlock(canvas, blocks[i + 1], x, y, size);
				y += size + unit * 0.5f / 2;
			}

			// Right: stats.
			float left = colW + m;
			float right = w - m;
			y = m;
			y = stat(canvas, ctx.getString(R.string.duo_lumines_score), String.format(Locale.US, "%,d", stats[0]), left, right, y, 34);
			y = stat(canvas, ctx.getString(R.string.duo_lumines_hiscore), String.format(Locale.US, "%,d", stats[1]), left, right, y, 20);
			y = stat(canvas, ctx.getString(R.string.duo_lumines_deleted), String.valueOf(stats[2]), left, right, y, 26);
			int secs = stats[3] / 60;
			y = stat(canvas, ctx.getString(R.string.duo_lumines_time), String.format(Locale.US, "%d:%02d", secs / 60, secs % 60), left, right, y, 26);

			// Board fill, the danger meter.
			int empty = Math.max(0, Math.min(BOARD_CELLS, stats[4]));
			float fill = (BOARD_CELLS - empty) / (float)BOARD_CELLS;
			paint.setTextSize(DuoUi.dp(ctx, 14));
			paint.setColor(DuoUi.COLOR_TEXT_DIM);
			paint.setTextAlign(Paint.Align.LEFT);
			y += DuoUi.dp(ctx, 8);
			canvas.drawText(ctx.getString(R.string.duo_lumines_fill, Math.round(fill * 100)), left, y + DuoUi.dp(ctx, 14), paint);
			y += DuoUi.dp(ctx, 22);
			float barH = DuoUi.dp(ctx, 18);
			rect.set(left, y, right, y + barH);
			paint.setColor(DuoUi.COLOR_SURFACE);
			canvas.drawRoundRect(rect, barH / 2, barH / 2, paint);
			rect.set(left, y, left + (right - left) * fill, y + barH);
			paint.setColor(fill < 0.5f ? DuoUi.COLOR_GOOD : fill < 0.75f ? DuoUi.COLOR_WARNING : 0xFFE04848);
			canvas.drawRoundRect(rect, barH / 2, barH / 2, paint);
		}

		private float stat(Canvas canvas, String label, String value, float left, float right, float y, float valueSp) {
			Context ctx = getContext();
			paint.setTextAlign(Paint.Align.LEFT);
			paint.setTextSize(DuoUi.dp(ctx, 14));
			paint.setColor(DuoUi.COLOR_TEXT_DIM);
			y += DuoUi.dp(ctx, 16);
			canvas.drawText(label, left, y, paint);
			paint.setTextAlign(Paint.Align.RIGHT);
			paint.setTextSize(DuoUi.dp(ctx, valueSp));
			paint.setColor(DuoUi.COLOR_TEXT);
			y += DuoUi.dp(ctx, valueSp) + DuoUi.dp(ctx, 4);
			canvas.drawText(value, right, y, paint);
			return y + DuoUi.dp(ctx, 6);
		}

		// cells: TL, TR, BL, BR.
		private void drawBlock(Canvas canvas, int[] cells, float x, float y, float size) {
			float cell = size / 2;
			float gap = Math.max(2, cell * 0.06f);
			float r = cell * 0.14f;
			for (int i = 0; i < 4; i++) {
				float cx = x + (i % 2) * cell, cy = y + (i / 2) * cell;
				rect.set(cx + gap, cy + gap, cx + cell - gap, cy + cell - gap);
				paint.setColor(cells[i] == 1 ? COLOR_1 : COLOR_2);
				canvas.drawRoundRect(rect, r, r, paint);
				// Bevel, like the game's blocks.
				rect.inset(cell * 0.22f, cell * 0.22f);
				paint.setColor(cells[i] == 1 ? 0xFFC9D0D6 : 0xFFD9762A);
				canvas.drawRoundRect(rect, r * 0.6f, r * 0.6f, paint);
			}
		}
	}
}
