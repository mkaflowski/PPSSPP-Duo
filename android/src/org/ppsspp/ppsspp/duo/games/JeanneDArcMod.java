package org.ppsspp.ppsspp.duo.games;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.widget.OverScroller;

import org.ppsspp.ppsspp.R;
import org.ppsspp.ppsspp.duo.DuoMod;
import org.ppsspp.ppsspp.duo.DuoModContext;
import org.ppsspp.ppsspp.duo.DuoStatus;
import org.ppsspp.ppsspp.duo.DuoUi;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

// Jeanne d'Arc: every unit on the battlefield with its portrait, HP and MP, your party on the left
// and the enemies on the right, drawn like the game's status window with its own lettering and
// portraits (see JeanneArt).
//
// Found on UCUS98700 v1.00 (from savestates in the first battle). The battle state is in the main
// module's data, so the addresses are fixed:
// - 0x08ABA500: turn number (u16), 0 outside battles.
// - 0x08ABA6E0: 64 unit records of 0x140 bytes. +0x0C name (ASCII, 32 bytes), +0x2D character
//   (u8), +0x30 side (2 = player, 3 = enemy, others are guests), +0x34 portrait (u16), +0x36 max HP,
//   +0x38 max MP, +0x8E HP, +0x90 MP, +0x94 level, +0x96 exp (all u16), +0x99 / +0x9A position on
//   the map. Unused records are zero.
// A copy of the table follows at 0x08ABF6E0; the game doesn't read it during a turn (checked with
// read breakpoints), so it's a snapshot rather than the live data.
//
// Portraits are read from the disc, DATA/FACE/AF<portrait>_00.GIM: 128x128, the status window's
// bust in the left 96 columns (the rest holds pieces for its expressions). Not every portrait has
// one (the Orc Knight's 101 doesn't), so the 64x64 DATA/FACE/MFACE<portrait>.GIM is the fallback.
// The portrait follows the story (Jeanne is 021 in the first battle and 001 once she wears armor),
// so it's taken from the record; the character number is the last resort.
public final class JeanneDArcMod extends DuoMod {
	public static final String ID = "jeanne_darc";

	private static final String[] GAME_IDS = {"UCUS98700"};

	private static final int TURN_ADDR = 0x08ABA500;
	private static final int UNITS_ADDR = 0x08ABA6E0;
	private static final int UNIT_SIZE = 0x140;
	private static final int UNIT_COUNT = 64;
	// A watch holds up to 16 KB, so the table is split in two.
	private static final int UNITS_PER_WATCH = UNIT_COUNT / 2;

	private static final int SIDE_PLAYER = 2;
	private static final int SIDE_ENEMY = 3;

	private static final int W_TURN = 0;
	private static final int W_UNITS = 1;

	private static final int KIND_BUST = 0;
	private static final int KIND_SMALL = 1;
	private static final String[] FACE_PATHS = {
		"disc0:/PSP_GAME/USRDIR/DATA/FACE/AF%03d_00.GIM",
		"disc0:/PSP_GAME/USRDIR/DATA/FACE/MFACE%03d.GIM",
	};
	private static final int[] FACE_READ_SIZES = {32 * 1024, 16 * 1024};
	private static final int ATTEMPTS = 3;
	private static final long RETRY_MS = 3000;

	private DuoModContext host;
	private BattleView view;

	// Portraits by kind << 16 | number, kept across tab switches. A failed read (the emulator
	// paused, a missing file) is retried a few times.
	private final Map<Integer, Bitmap> faces = new HashMap<>();
	private final Map<Integer, Integer> faceAttempts = new HashMap<>();
	private final Map<Integer, Long> faceLastTry = new HashMap<>();
	private final Map<Integer, Boolean> facePending = new HashMap<>();

	private JeanneArt art;
	private boolean artPending;
	private int artAttempts;
	private long artLastTry;

	static final class Unit {
		String name;
		// Record index, which stays the unit's for the battle.
		int slot;
		int side, character, portrait, level, exp, hp, maxHp, mp, maxMp;
		Bitmap face;

		boolean defeated() {
			return hp <= 0;
		}

		String signature() {
			return slot + ":" + name + side + "," + System.identityHashCode(face) + "," + level + "," + exp + "," + hp + "/" + maxHp + "," + mp + "/" + maxMp + ";";
		}
	}

	@Override
	public String getId() {
		return ID;
	}

	@Override
	public String getTitle(Context context) {
		return context.getString(R.string.duo_mod_jeanne);
	}

	@Override
	public String getDescription(Context context) {
		return context.getString(R.string.duo_mod_jeanne_desc);
	}

	@Override
	public int getPriority(DuoStatus s) {
		for (String id : GAME_IDS) {
			if (id.equals(s.gameId)) {
				return 100;
			}
		}
		return -1;
	}

	@Override
	public long getStatusIntervalMs() {
		return 200;
	}

	@Override
	public View onCreateView(DuoModContext host) {
		this.host = host;
		view = new BattleView(host.getContext());
		view.setArt(art);
		int half = UNITS_PER_WATCH * UNIT_SIZE;
		host.setMemoryWatches(
			new int[] {TURN_ADDR, UNITS_ADDR, UNITS_ADDR + half},
			new int[] {2, half, half});
		// Requests still in flight were dropped with the previous view.
		facePending.clear();
		artPending = false;
		return view;
	}

	@Override
	public void onStatus(DuoStatus s) {
		if (!s.isInGame() && !s.isPauseMenu()) {
			view.setData(0, null);
			return;
		}
		// Game files can only be read while the game runs.
		boolean canLoad = s.isInGame();
		if (canLoad) {
			loadArt();
		}
		byte[] turnData = host.readMemoryWatch(W_TURN);
		if (turnData == null) {
			return;
		}
		int turn = ByteBuffer.wrap(turnData).order(ByteOrder.LITTLE_ENDIAN).getShort(0) & 0xFFFF;
		if (turn == 0 || turn > 999) {
			view.setData(0, null);
			return;
		}
		List<Unit> units = new ArrayList<>();
		for (int w = 0; w < 2; w++) {
			byte[] data = host.readMemoryWatch(W_UNITS + w);
			if (data == null) {
				return;
			}
			ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
			for (int i = 0; i < UNITS_PER_WATCH; i++) {
				Unit u = parse(b, i * UNIT_SIZE);
				if (u != null) {
					u.slot = w * UNITS_PER_WATCH + i;
					u.face = portrait(u.portrait, canLoad);
					if (u.face == null && hasFailed(KIND_BUST, u.portrait) && hasFailed(KIND_SMALL, u.portrait)) {
						u.face = portrait(u.character, canLoad);
					}
					units.add(u);
				}
			}
		}
		view.setData(turn, units);
	}

	private void loadArt() {
		long now = SystemClock.uptimeMillis();
		if (art != null || artPending || artAttempts >= ATTEMPTS || now - artLastTry < RETRY_MS) {
			return;
		}
		artPending = true;
		artLastTry = now;
		JeanneArt.load(host, a -> {
			artPending = false;
			if (a == null) {
				artAttempts++;
				return;
			}
			art = a;
			if (view != null) {
				view.setArt(a);
			}
		});
	}

	private static Unit parse(ByteBuffer b, int r) {
		Unit u = new Unit();
		u.character = b.get(r + 0x2D) & 0xFF;
		u.side = b.get(r + 0x30) & 0xFF;
		u.portrait = b.getShort(r + 0x34) & 0xFFFF;
		u.maxHp = b.getShort(r + 0x36);
		u.maxMp = b.getShort(r + 0x38);
		u.hp = b.getShort(r + 0x8E);
		u.mp = b.getShort(r + 0x90);
		u.level = b.getShort(r + 0x94);
		u.exp = b.getShort(r + 0x96);
		if (u.side == 0 || u.maxHp <= 0 || u.maxHp > 9999 || u.hp < 0 || u.hp > u.maxHp) {
			return null;
		}
		int len = 0;
		while (len < 32 && b.get(r + 0x0C + len) != 0) {
			int c = b.get(r + 0x0C + len) & 0xFF;
			if (c < 0x20 || c >= 0x7F) {
				return null;
			}
			len++;
		}
		if (len == 0) {
			return null;
		}
		byte[] name = new byte[len];
		for (int i = 0; i < len; i++) {
			name[i] = b.get(r + 0x0C + i);
		}
		u.name = new String(name, StandardCharsets.US_ASCII);
		return u;
	}

	// The bust if there is one, else the small portrait, else null (reads are started here).
	private Bitmap portrait(int id, boolean canLoad) {
		Bitmap bust = face(KIND_BUST, id, canLoad);
		return bust != null ? bust : face(KIND_SMALL, id, canLoad);
	}

	private Bitmap face(int kind, int id, boolean canLoad) {
		int key = kind << 16 | id;
		Bitmap bmp = faces.get(key);
		if (bmp != null || !canLoad || hasFailed(kind, id) || facePending.containsKey(key)) {
			return bmp;
		}
		Long last = faceLastTry.get(key);
		long now = SystemClock.uptimeMillis();
		if (last != null && now - last < RETRY_MS) {
			return null;
		}
		String path = String.format(Locale.US, FACE_PATHS[kind], id);
		boolean queued = host.readGameFile(path, 0, FACE_READ_SIZES[kind], data -> {
			facePending.remove(key);
			Bitmap decoded = data != null && data.length > 0 ? GimImage.decode(data) : null;
			if (decoded != null) {
				faces.put(key, kind == KIND_BUST ? bust(decoded) : trim(decoded));
			} else {
				faceAttempts.put(key, attempts(key) + 1);
			}
		});
		if (queued) {
			facePending.put(key, true);
			faceLastTry.put(key, now);
		}
		return null;
	}

	private static Bitmap bust(Bitmap bmp) {
		if (bmp.getWidth() != 128 || bmp.getHeight() != 128) {
			return trim(bmp);
		}
		return Bitmap.createBitmap(bmp, 0, 0, 96, 128);
	}

	// The small portraits have a few transparent columns on the right (and a row at the bottom).
	private static Bitmap trim(Bitmap bmp) {
		int w = bmp.getWidth(), h = bmp.getHeight();
		int[] px = new int[w * h];
		bmp.getPixels(px, 0, w, 0, 0, w, h);
		int minX = w, minY = h, maxX = -1, maxY = -1;
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				if ((px[y * w + x] >>> 24) != 0) {
					minX = Math.min(minX, x);
					maxX = Math.max(maxX, x);
					minY = Math.min(minY, y);
					maxY = Math.max(maxY, y);
				}
			}
		}
		if (maxX < 0 || (minX == 0 && minY == 0 && maxX == w - 1 && maxY == h - 1)) {
			return bmp;
		}
		return Bitmap.createBitmap(bmp, minX, minY, maxX - minX + 1, maxY - minY + 1);
	}

	private int attempts(int key) {
		Integer n = faceAttempts.get(key);
		return n != null ? n : 0;
	}

	private boolean hasFailed(int kind, int id) {
		return id <= 0 || id > 999 || attempts(kind << 16 | id) >= ATTEMPTS;
	}

	@Override
	public void onDestroyView() {
		view = null;
		host = null;
	}

	private static final class BattleView extends View {
		private static final int COLOR_BG_TOP = 0xFF1C2438;
		private static final int COLOR_BG_BOTTOM = 0xFF090C15;
		// The status window: translucent slate with a bronze frame and a beige top edge.
		private static final int COLOR_PANEL_TOP = 0xF23A475E;
		private static final int COLOR_PANEL_BOTTOM = 0xF21C2333;
		private static final int COLOR_FRAME = 0xFF7A6142;
		private static final int COLOR_EDGE = 0xFFD5AE7C;
		private static final int COLOR_ROW_TOP = 0xFF0A0E16;
		private static final int COLOR_ROW_BOTTOM = 0xFF27303F;
		private static final int COLOR_ROW_EDGE = 0xFF505B70;
		private static final int COLOR_WELL = 0xFF121824;
		private static final int COLOR_TRACK = 0xFF05070B;
		private static final int COLOR_DIM = 0x66060910;
		// Bars as in the game: blue HP for your side, the enemies' red as over their heads on the
		// map, and green MP.
		private static final int[] HP_PLAYER = {0xFF2457C8, 0xFF8AE2F6};
		private static final int[] HP_ENEMY = {0xFFB01E26, 0xFFF2A646};
		private static final int[] MP = {0xFF1E7A35, 0xFF72D67E};
		private static final int[] DEFEATED = {0xFF3A3F48, 0xFF5A606B};
		// Lettering when the game's textures aren't available.
		private static final int COLOR_CREAM = 0xFFF2E3C0;
		private static final int COLOR_NAME = 0xFF2A1F14;
		private static final int COLOR_NAME_DEFEATED = 0xFF5E564D;
		private static final int COLOR_PLAYER = 0xFF6FE0F0;
		private static final int COLOR_ENEMY = 0xFFF04848;
		private static final int COLOR_GUEST = 0xFF5FE08A;

		private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
		private final Paint fill = new Paint();
		private final Paint glyphPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
		private final Paint dimGlyphPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
		private final Paint facePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
		private final ColorMatrixColorFilter grayscale;
		private final Typeface serif = Typeface.create(Typeface.SERIF, Typeface.BOLD);
		private final RectF rect = new RectF();
		private final RectF plate = new RectF();
		private final Rect src = new Rect();
		private JeanneArt art;
		private int turn;
		private final List<Unit> all = new ArrayList<>();
		private final List<Unit> party = new ArrayList<>();
		private final List<Unit> enemies = new ArrayList<>();
		private String signature = "";

		// A hit shakes the card and drains the lost part of the bar. A unit that falls turns gray
		// after the shake, then its card slides to the bottom of the column while the ones below
		// move up (like RecyclerView's item animations).
		private static final long SHAKE_MS = 450;
		private static final long BAR_DELAY_MS = 150;
		private static final long BAR_MS = 450;
		private static final long GRAY_MS = 300;
		private static final long MOVE_MS = 400;
		private static final int COLOR_HIT = 0xFFE03C32;
		private static final int COLOR_GHOST = 0xFFF4E6C8;

		private static final class Bar {
			float from, to;
			long start = -1;

			void set(float value, long now) {
				from = shown(now);
				to = value;
				start = now;
			}

			// Eases from the old value to the new, after a moment (so a hit reads first).
			float shown(long now) {
				if (start < 0) {
					return to;
				}
				float p = clamp((now - start - BAR_DELAY_MS) / (float)BAR_MS);
				return from + (to - from) * (1 - (1 - p) * (1 - p));
			}

			boolean running(long now) {
				return start >= 0 && now - start < BAR_DELAY_MS + BAR_MS;
			}
		}

		private static final class Anim {
			String name;
			int hp, mp;
			final Bar hpBar = new Bar(), mpBar = new Bar();
			long shakeStart = -1;
			// When the unit fell: -1 while it stands, 0 if it was already down when first seen.
			long deadSince = -1;
			boolean sunk;
			float fromIndex, toIndex = -1;
			long moveStart = -1;

			float index(long now) {
				if (moveStart < 0) {
					return toIndex;
				}
				float p = clamp((now - moveStart) / (float)MOVE_MS);
				// Ease in and out.
				p = p * p * (3 - 2 * p);
				return fromIndex + (toIndex - fromIndex) * p;
			}

			boolean moving(long now) {
				return moveStart >= 0 && now - moveStart < MOVE_MS;
			}

			// 0 standing, 1 shown as defeated; the fade starts when the shake is over.
			float deadness(long now) {
				if (deadSince < 0) {
					return 0;
				}
				if (deadSince == 0) {
					return 1;
				}
				return clamp((now - deadSince - SHAKE_MS) / (float)GRAY_MS);
			}

			boolean shouldSink(long now) {
				return deadSince == 0 || (deadSince > 0 && now - deadSince >= SHAKE_MS + GRAY_MS);
			}

			// Sideways offset, as a fraction of the amplitude.
			float shake(long now) {
				if (shakeStart < 0 || now - shakeStart >= SHAKE_MS) {
					return 0;
				}
				float p = (now - shakeStart) / (float)SHAKE_MS;
				return (float)Math.sin(p * Math.PI * 2 * 3.5) * (1 - p);
			}

			float hit(long now) {
				if (shakeStart < 0 || now - shakeStart >= SHAKE_MS) {
					return 0;
				}
				float p = 1 - (now - shakeStart) / (float)SHAKE_MS;
				return p * p;
			}

			boolean active(long now) {
				return hit(now) > 0 || hpBar.running(now) || mpBar.running(now) || moving(now)
					|| (deadSince > 0 && !sunk);
			}
		}

		private final Map<Integer, Anim> anims = new HashMap<>();

		private static float clamp(float v) {
			return Math.max(0, Math.min(1, v));
		}

		private static float frac(int value, int max) {
			return max > 0 ? clamp(value / (float)max) : 0;
		}

		// Per column (party, enemies): where it is on screen, and how far it's scrolled.
		private final RectF[] columns = {new RectF(), new RectF()};
		private final float[] scroll = new float[2];
		private final float[] maxScroll = new float[2];
		private final OverScroller scroller;
		private VelocityTracker velocity;
		private int dragColumn = -1;
		private int flingColumn = -1;
		private float lastY;

		BattleView(Context context) {
			super(context);
			scroller = new OverScroller(context);
			ColorMatrix m = new ColorMatrix();
			m.setSaturation(0);
			grayscale = new ColorMatrixColorFilter(m);
			dimGlyphPaint.setColorFilter(grayscale);
			dimGlyphPaint.setAlpha(150);
		}

		void setArt(JeanneArt art) {
			this.art = art;
			invalidate();
		}

		void setData(int turn, List<Unit> units) {
			StringBuilder sig = new StringBuilder().append(turn).append(':');
			if (units != null) {
				for (Unit u : units) {
					sig.append(u.signature());
				}
			}
			if (sig.toString().equals(signature)) {
				return;
			}
			signature = sig.toString();
			this.turn = turn;
			long now = SystemClock.uptimeMillis();
			all.clear();
			if (units == null) {
				anims.clear();
				party.clear();
				enemies.clear();
				invalidate();
				return;
			}
			all.addAll(units);
			Map<Integer, Anim> kept = new HashMap<>();
			for (Unit u : units) {
				Anim a = anims.get(u.slot);
				if (a == null || !a.name.equals(u.name)) {
					// New to this view: shown as it is, nothing animates.
					a = new Anim();
					a.name = u.name;
					a.hpBar.to = frac(u.hp, u.maxHp);
					a.mpBar.to = frac(u.mp, u.maxMp);
					a.deadSince = u.defeated() ? 0 : -1;
				} else {
					if (u.hp < a.hp) {
						a.shakeStart = now;
					}
					if (u.hp != a.hp || frac(u.hp, u.maxHp) != a.hpBar.to) {
						a.hpBar.set(frac(u.hp, u.maxHp), now);
					}
					if (u.mp != a.mp || frac(u.mp, u.maxMp) != a.mpBar.to) {
						a.mpBar.set(frac(u.mp, u.maxMp), now);
					}
					if (u.defeated() && a.deadSince < 0) {
						a.deadSince = now;
					} else if (!u.defeated()) {
						a.deadSince = -1;
					}
				}
				a.hp = u.hp;
				a.mp = u.mp;
				kept.put(u.slot, a);
			}
			anims.clear();
			anims.putAll(kept);
			reorder(now);
			invalidate();
		}

		// Guests and other allies go with the party. The defeated sink to the bottom, once they've
		// been seen falling. A unit whose place changed moves there from where it's drawn now.
		private void reorder(long now) {
			party.clear();
			enemies.clear();
			for (int pass = 0; pass < 2; pass++) {
				for (Unit u : all) {
					Anim a = anims.get(u.slot);
					if (a.shouldSink(now) == (pass == 1)) {
						(u.side == SIDE_ENEMY ? enemies : party).add(u);
					}
				}
			}
			for (int c = 0; c < 2; c++) {
				List<Unit> column = c == 0 ? party : enemies;
				for (int i = 0; i < column.size(); i++) {
					Anim a = anims.get(column.get(i).slot);
					a.sunk = a.shouldSink(now);
					if (a.toIndex < 0) {
						a.fromIndex = a.toIndex = i;
					} else if (a.toIndex != i) {
						a.fromIndex = a.index(now);
						a.toIndex = i;
						a.moveStart = now;
					}
				}
			}
		}

		private float dp(float v) {
			return DuoUi.dp(getContext(), v);
		}

		@Override
		protected void onDraw(Canvas canvas) {
			Context ctx = getContext();
			float w = getWidth(), h = getHeight();
			fill.setShader(new LinearGradient(0, 0, 0, h, COLOR_BG_TOP, COLOR_BG_BOTTOM, Shader.TileMode.CLAMP));
			canvas.drawRect(0, 0, w, h, fill);
			fill.setShader(null);
			paint.setTypeface(serif);
			if (turn == 0) {
				paint.setColor(COLOR_CREAM & 0x99FFFFFF);
				paint.setTextAlign(Paint.Align.CENTER);
				paint.setTextSize(dp(20));
				canvas.drawText(ctx.getString(R.string.duo_jeanne_idle), w / 2, h / 2, paint);
				return;
			}

			// The margin keeps clear of the panel's rounded corners.
			float pad = dp(28);
			float gap = dp(20);
			float headerH = dp(48);

			// TURN n, centered.
			float turnH = dp(26);
			float labelH = turnH * 0.85f;
			if (art != null) {
				float lw = JeanneArt.width(JeanneArt.TURN, labelH);
				float total = lw + dp(10) + art.numberWidth(turn, true, turnH);
				float x = (w - total) / 2;
				float top = pad + dp(4);
				art.label(canvas, JeanneArt.TURN, x, top + (turnH - labelH) * 0.7f, labelH, glyphPaint);
				art.number(canvas, turn, true, x + lw + dp(10), top, turnH, glyphPaint);
			} else {
				paint.setTextAlign(Paint.Align.CENTER);
				paint.setTextSize(dp(24));
				paint.setColor(COLOR_CREAM);
				canvas.drawText(ctx.getString(R.string.duo_jeanne_turn, turn), w / 2, pad + dp(24), paint);
			}

			long now = SystemClock.uptimeMillis();
			for (Unit u : all) {
				Anim a = anims.get(u.slot);
				if (a.shouldSink(now) != a.sunk) {
					reorder(now);
					break;
				}
			}
			float colW = (w - 2 * pad - gap) / 2;
			float top = pad + headerH;
			drawColumn(canvas, party, pad, top, colW, h - pad, true, now);
			drawColumn(canvas, enemies, pad + colW + gap, top, colW, h - pad, false, now);
			for (Unit u : all) {
				if (anims.get(u.slot).active(now)) {
					postInvalidateOnAnimation();
					break;
				}
			}
		}

		private static int alive(List<Unit> units) {
			int n = 0;
			for (Unit u : units) {
				if (!u.defeated()) {
					n++;
				}
			}
			return n;
		}

		private void drawColumn(Canvas canvas, List<Unit> units, float x, float top, float colW, float bottom, boolean isParty, long now) {
			Context ctx = getContext();
			int col = isParty ? 0 : 1;
			float titleH = dp(20);
			if (art != null) {
				Rect label = isParty ? JeanneArt.PLAYER : JeanneArt.ENEMY;
				art.label(canvas, label, x, top, titleH, glyphPaint);
				float numH = titleH * 0.95f;
				float right = x + colW;
				right -= art.numberRight(canvas, units.size(), true, right, top, numH, glyphPaint);
				right -= JeanneArt.width(JeanneArt.BIG_SLASH, numH);
				art.label(canvas, JeanneArt.BIG_SLASH, right, top, numH, glyphPaint);
				art.numberRight(canvas, alive(units), true, right, top, numH, glyphPaint);
			} else {
				paint.setTextSize(dp(17));
				paint.setTextAlign(Paint.Align.LEFT);
				paint.setColor(isParty ? COLOR_PLAYER : COLOR_ENEMY);
				canvas.drawText(ctx.getString(isParty ? R.string.duo_jeanne_party : R.string.duo_jeanne_enemies), x, top + dp(16), paint);
				paint.setTextAlign(Paint.Align.RIGHT);
				paint.setColor(COLOR_CREAM);
				canvas.drawText(alive(units) + " / " + units.size(), x + colW, top + dp(16), paint);
			}
			// Bronze rule under the title, like the window's top edge.
			fill.setColor(COLOR_FRAME);
			canvas.drawRect(x, top + titleH + dp(4), x + colW, top + titleH + dp(5.5f), fill);
			top += titleH + dp(12);
			columns[col].set(x, top, x + colW, bottom);

			// Cards shrink to fit. Below a size they turn into one-line rows, and when even those
			// don't fit, the column scrolls.
			int n = units.size();
			float avail = bottom - top;
			float cardSpacing = dp(8);
			float fit = n > 0 ? (avail + cardSpacing) / n - cardSpacing : avail;
			boolean compact = fit < dp(72);
			float itemH = compact ? dp(44) : Math.min(dp(isParty ? 120 : 104), fit);
			float spacing = compact ? dp(5) : cardSpacing;
			float contentH = n > 0 ? n * (itemH + spacing) - spacing : 0;
			maxScroll[col] = Math.max(0, contentH - avail);
			scroll[col] = Math.max(0, Math.min(maxScroll[col], scroll[col]));

			canvas.save();
			// Wider than the column, for the shake.
			canvas.clipRect(x - dp(8), top, x + colW + dp(8), bottom);
			// Cards on their way down go under the ones moving up.
			for (int pass = 0; pass < 2; pass++) {
				for (Unit u : units) {
					Anim a = anims.get(u.slot);
					boolean sinking = a.moving(now) && a.toIndex > a.fromIndex;
					if (sinking != (pass == 0)) {
						continue;
					}
					float y = top - scroll[col] + a.index(now) * (itemH + spacing);
					if (y + itemH < top || y > bottom) {
						continue;
					}
					float dx = a.shake(now) * dp(7);
					canvas.save();
					canvas.translate(dx, 0);
					if (compact) {
						drawRow(canvas, u, a, now, x, y, colW, itemH);
					} else {
						drawUnit(canvas, u, a, now, x, y, colW, itemH);
					}
					float hit = a.hit(now);
					if (hit > 0) {
						fill.setColor(COLOR_HIT);
						fill.setAlpha((int)(110 * hit));
						canvas.drawRect(x, y, x + colW, y + itemH, fill);
					}
					canvas.restore();
				}
			}
			canvas.restore();

			if (maxScroll[col] > 0) {
				// Scrollbar, in the gap next to the column.
				float trackX = x + colW + dp(6);
				float thumbH = Math.max(dp(24), avail * avail / contentH);
				float thumbY = top + (avail - thumbH) * scroll[col] / maxScroll[col];
				float bw = dp(3);
				rect.set(trackX, top, trackX + bw, bottom);
				paint.setColor(COLOR_ROW_BOTTOM);
				canvas.drawRoundRect(rect, bw / 2, bw / 2, paint);
				rect.set(trackX, thumbY, trackX + bw, thumbY + thumbH);
				paint.setColor(COLOR_FRAME);
				canvas.drawRoundRect(rect, bw / 2, bw / 2, paint);
			}
		}

		private void drawPanel(Canvas canvas, float x, float y, float w, float h) {
			fill.setShader(new LinearGradient(0, y, 0, y + h, COLOR_PANEL_TOP, COLOR_PANEL_BOTTOM, Shader.TileMode.CLAMP));
			canvas.drawRect(x, y, x + w, y + h, fill);
			fill.setShader(null);
			float fw = dp(1.5f);
			paint.setStyle(Paint.Style.STROKE);
			paint.setStrokeWidth(fw);
			paint.setColor(COLOR_FRAME);
			canvas.drawRect(x + fw / 2, y + fw / 2, x + w - fw / 2, y + h - fw / 2, paint);
			paint.setStyle(Paint.Style.FILL);
			fill.setColor(COLOR_EDGE);
			canvas.drawRect(x, y, x + w, y + dp(2), fill);
		}

		// Cropped to fill the box, keeping the top (the face). Fades to gray as deadness goes to 1.
		private void drawPortrait(Canvas canvas, Bitmap face, RectF box, float deadness) {
			fill.setColor(COLOR_WELL);
			canvas.drawRect(box, fill);
			if (face != null) {
				int bw = face.getWidth(), bh = face.getHeight();
				float aspect = box.width() / box.height();
				if (bw > bh * aspect) {
					int sw = Math.round(bh * aspect);
					src.set((bw - sw) / 2, 0, (bw - sw) / 2 + sw, bh);
				} else {
					src.set(0, 0, bw, Math.round(bw / aspect));
				}
				if (deadness <= 0) {
					facePaint.setColorFilter(null);
				} else if (deadness >= 1) {
					facePaint.setColorFilter(grayscale);
				} else {
					ColorMatrix m = new ColorMatrix();
					m.setSaturation(1 - deadness);
					facePaint.setColorFilter(new ColorMatrixColorFilter(m));
				}
				facePaint.setAlpha((int)(255 - 105 * deadness));
				canvas.drawBitmap(face, src, box, facePaint);
			}
			paint.setStyle(Paint.Style.STROKE);
			paint.setStrokeWidth(dp(1));
			paint.setColor(COLOR_FRAME);
			canvas.drawRect(box, paint);
			paint.setStyle(Paint.Style.FILL);
		}

		// The beige plate with the unit's name in the game's serif.
		private void drawNamePlate(Canvas canvas, Unit u, boolean dead, RectF where, float reserveRight) {
			if (art != null) {
				art.namePlate(canvas, where, dead ? dimGlyphPaint : glyphPaint);
			} else {
				fill.setColor(dead ? 0x80807060 : 0xD0D5AE7C);
				canvas.drawRect(where, fill);
			}
			float size = where.height() * (where.height() < dp(26) ? 0.62f : 0.68f);
			paint.setTextSize(size);
			paint.setTextAlign(Paint.Align.LEFT);
			paint.setColor(dead ? COLOR_NAME_DEFEATED : COLOR_NAME);
			float left = where.left + where.height() * 0.35f;
			canvas.drawText(ellipsize(u.name, where.right - reserveRight - left - size * 0.3f), left, where.centerY() + size * 0.35f, paint);
		}

		private boolean isGuest(Unit u) {
			return u.side != SIDE_PLAYER && u.side != SIDE_ENEMY;
		}

		// Room the GUEST tag takes on a name plate this tall.
		private float guestWidth(Unit u, float plateH) {
			if (!isGuest(u)) {
				return 0;
			}
			float h = plateH * 0.62f;
			if (art != null) {
				return JeanneArt.width(JeanneArt.GUEST, h) + plateH * 0.3f;
			}
			paint.setTextSize(h);
			return paint.measureText("GUEST") + plateH * 0.3f;
		}

		// GUEST on the right of the name plate.
		private void drawGuest(Canvas canvas, RectF where) {
			float h = where.height() * 0.62f;
			float right = where.right - where.height() * 0.3f;
			if (art != null) {
				float w = JeanneArt.width(JeanneArt.GUEST, h);
				art.label(canvas, JeanneArt.GUEST, right - w, where.centerY() - h / 2, h, glyphPaint);
				return;
			}
			paint.setTextSize(h);
			paint.setTextAlign(Paint.Align.RIGHT);
			paint.setColor(COLOR_GUEST);
			canvas.drawText("GUEST", right, where.centerY() + h * 0.35f, paint);
		}

		private void drawUnit(Canvas canvas, Unit u, Anim a, long now, float x, float y, float w, float h) {
			float deadness = a.deadness(now);
			boolean dead = deadness >= 0.5f;
			drawPanel(canvas, x, y, w, h);
			float inset = dp(6);

			// Portrait on the left, Lv over its bottom as in the game.
			float ph = h - 2 * inset - dp(2);
			float pw = Math.min(ph * 0.75f, w * 0.3f);
			float px = x + inset, py = y + inset + dp(2);
			rect.set(px, py, px + pw, py + ph);
			drawPortrait(canvas, u.face, rect, deadness);
			float lvH = Math.min(dp(17), ph * 0.18f);
			float shadeTop = py + ph - lvH * 2.4f;
			fill.setShader(new LinearGradient(0, shadeTop, 0, py + ph - lvH * 1.2f, 0x00000000, 0xE8000000, Shader.TileMode.CLAMP));
			canvas.drawRect(px + dp(1), shadeTop, px + pw - dp(1), py + ph - dp(1), fill);
			fill.setShader(null);
			drawLevel(canvas, u, dead, px + dp(4), py + ph - lvH - dp(3), lvH, px + pw - dp(4));

			float left = px + pw + dp(8);
			float right = x + w - inset;
			float plateH = Math.min(dp(28), h * 0.26f);
			plate.set(left, py, right, py + plateH);
			drawNamePlate(canvas, u, dead, plate, guestWidth(u, plate.height()));
			if (isGuest(u)) {
				drawGuest(canvas, plate);
			}

			float rowsTop = plate.bottom + dp(5);
			float rowsBottom = y + h - inset;
			float rowGap = dp(4);
			boolean showMp = u.maxMp > 0 && rowsBottom - rowsTop > dp(48);
			int rows = showMp ? 2 : 1;
			float rowH = (rowsBottom - rowsTop - (rows - 1) * rowGap) / rows;
			int[] hpColors = dead ? DEFEATED : u.side == SIDE_ENEMY ? HP_ENEMY : HP_PLAYER;
			drawStat(canvas, JeanneArt.HP, "HP", u.hp, u.maxHp, a.hpBar.shown(now), hpColors, dead, left, rowsTop, right, rowH);
			if (showMp) {
				drawStat(canvas, JeanneArt.MP, "MP", u.mp, u.maxMp, a.mpBar.shown(now), dead ? DEFEATED : MP, dead,
					left, rowsTop + rowH + rowGap, right, rowH);
			}
			drawDim(canvas, deadness, x, y, w, h);
		}

		private void drawDim(Canvas canvas, float deadness, float x, float y, float w, float h) {
			if (deadness > 0) {
				fill.setColor(COLOR_DIM);
				fill.setAlpha((int)((COLOR_DIM >>> 24) * deadness));
				canvas.drawRect(x, y, x + w, y + h, fill);
			}
		}

		// "Lv n", n in the small digits.
		private void drawLevel(Canvas canvas, Unit u, boolean dead, float x, float top, float h, float maxRight) {
			Paint gp = dead ? dimGlyphPaint : glyphPaint;
			if (art != null) {
				float lw = art.label(canvas, JeanneArt.LV, x, top, h, gp);
				float nx = x + lw + h * 0.3f;
				if (nx + art.numberWidth(u.level, false, h) <= maxRight + h) {
					art.number(canvas, u.level, false, nx, top, h, gp);
				}
				return;
			}
			paint.setTextSize(h);
			paint.setTextAlign(Paint.Align.LEFT);
			paint.setColor(COLOR_CREAM);
			canvas.drawText(getContext().getString(R.string.duo_jeanne_level, u.level), x, top + h * 0.85f, paint);
		}

		// One status row: label, value / max in the big digits, and the bar under them. shown is
		// the bar's animated fill: above the value, the difference is a hit draining away.
		private void drawStat(Canvas canvas, Rect label, String fallback, int value, int max, float shown, int[] colors,
				boolean dead, float left, float top, float right, float rowH) {
			fill.setShader(new LinearGradient(0, top, 0, top + rowH, COLOR_ROW_TOP, COLOR_ROW_BOTTOM, Shader.TileMode.CLAMP));
			canvas.drawRect(left, top, right, top + rowH, fill);
			fill.setShader(null);
			paint.setStyle(Paint.Style.STROKE);
			paint.setStrokeWidth(dp(1));
			paint.setColor(COLOR_ROW_EDGE);
			canvas.drawRect(left, top, right, top + rowH, paint);
			paint.setStyle(Paint.Style.FILL);

			float inner = Math.min(dp(10), rowH * 0.25f);
			float barH = Math.max(dp(3), Math.min(dp(6), rowH * 0.12f));
			float barBottom = top + rowH - Math.max(dp(3), rowH * 0.1f);
			float x0 = left + inner, x1 = right - inner;
			float digitsH = Math.min(dp(28), (barBottom - barH - top) * 0.82f);
			if (art != null) {
				// Label, gap, value, slash, gap, max: all scale with the digits' height.
				float perH = JeanneArt.width(label, 0.82f) + 0.4f + 2 * art.numberWidth(888, true, 1)
					+ JeanneArt.width(JeanneArt.BIG_SLASH, 1) + 0.25f;
				digitsH = Math.min(digitsH, (x1 - x0) / perH);
			}
			float digitsTop = top + (barBottom - barH - top - digitsH) / 2 + digitsH * 0.06f;
			float labelH = digitsH * 0.82f;
			float labelTop = digitsTop + digitsH - labelH - digitsH * 0.08f;
			Paint gp = dead ? dimGlyphPaint : glyphPaint;
			float labelRight;
			if (art != null) {
				labelRight = x0 + art.label(canvas, label, x0, labelTop, labelH, gp);
				// "value/ max", each right-aligned in a three-digit field as in the game.
				float field = art.numberWidth(888, true, digitsH);
				float maxW = art.numberWidth(max, true, digitsH);
				float slashW = JeanneArt.width(JeanneArt.BIG_SLASH, digitsH);
				float maxLeft = x1 - Math.max(maxW, field);
				float slashX = maxLeft - slashW - digitsH * 0.25f;
				art.numberRight(canvas, max, true, x1, digitsTop, digitsH, gp);
				art.label(canvas, JeanneArt.BIG_SLASH, slashX, digitsTop, digitsH, gp);
				art.numberRight(canvas, value, true, slashX, digitsTop, digitsH, gp);
			} else {
				paint.setTextSize(labelH);
				paint.setTextAlign(Paint.Align.LEFT);
				paint.setColor(dead ? 0xFF8A8F98 : COLOR_CREAM);
				canvas.drawText(fallback, x0, labelTop + labelH * 0.85f, paint);
				labelRight = x0 + paint.measureText(fallback);
				paint.setTextSize(digitsH);
				paint.setTextAlign(Paint.Align.RIGHT);
				canvas.drawText(value + "/ " + max, x1, digitsTop + digitsH * 0.85f, paint);
			}

			float barLeft = labelRight + inner * 0.6f;
			if (x1 - barLeft < dp(16)) {
				return;
			}
			fill.setColor(COLOR_TRACK);
			canvas.drawRect(barLeft, barBottom - barH, x1, barBottom, fill);
			float target = frac(value, max);
			if (shown > target) {
				fill.setColor(COLOR_GHOST);
				canvas.drawRect(barLeft + (x1 - barLeft) * target, barBottom - barH, barLeft + (x1 - barLeft) * shown, barBottom, fill);
			}
			float frac = Math.min(target, shown);
			if (frac > 0) {
				float end = barLeft + Math.max(dp(2), (x1 - barLeft) * frac);
				fill.setShader(new LinearGradient(barLeft, 0, x1, 0, colors[0], colors[1], Shader.TileMode.CLAMP));
				canvas.drawRect(barLeft, barBottom - barH, end, barBottom, fill);
				fill.setShader(null);
				// Highlight along the top, for the game's rounded look.
				fill.setColor(0x40FFFFFF);
				canvas.drawRect(barLeft, barBottom - barH, end, barBottom - barH * 0.6f, fill);
			}
		}

		// One line: portrait, name plate, then HP.
		private void drawRow(Canvas canvas, Unit u, Anim a, long now, float x, float y, float w, float h) {
			float deadness = a.deadness(now);
			boolean dead = deadness >= 0.5f;
			drawPanel(canvas, x, y, w, h);
			float inset = dp(4);
			float ps = h - 2 * inset - dp(2);
			float px = x + inset, py = y + inset + dp(2);
			rect.set(px, py, px + ps, py + ps);
			drawPortrait(canvas, u.face, rect, deadness);
			float left = px + ps + dp(6);
			float right = x + w - inset;
			float nameW = (right - left) * 0.5f;
			plate.set(left, py, left + nameW, py + ps);
			float plateH = Math.min(ps, dp(24));
			plate.top = py + (ps - plateH) / 2;
			plate.bottom = plate.top + plateH;
			drawNamePlate(canvas, u, dead, plate, guestWidth(u, plateH));
			if (isGuest(u)) {
				drawGuest(canvas, plate);
			}
			int[] hpColors = dead ? DEFEATED : u.side == SIDE_ENEMY ? HP_ENEMY : HP_PLAYER;
			drawStat(canvas, JeanneArt.HP, "HP", u.hp, u.maxHp, a.hpBar.shown(now), hpColors, dead, left + nameW + dp(6), py, right, ps);
			drawDim(canvas, deadness, x, y, w, h);
		}

		private int columnAt(float x, float y) {
			for (int i = 0; i < 2; i++) {
				if (maxScroll[i] > 0 && columns[i].contains(x, y)) {
					return i;
				}
			}
			return -1;
		}

		@Override
		public boolean onTouchEvent(MotionEvent e) {
			switch (e.getActionMasked()) {
			case MotionEvent.ACTION_DOWN:
				dragColumn = columnAt(e.getX(), e.getY());
				if (dragColumn < 0) {
					return false;
				}
				scroller.forceFinished(true);
				flingColumn = -1;
				lastY = e.getY();
				velocity = VelocityTracker.obtain();
				velocity.addMovement(e);
				return true;
			case MotionEvent.ACTION_MOVE:
				if (dragColumn >= 0) {
					velocity.addMovement(e);
					scroll[dragColumn] = Math.max(0, Math.min(maxScroll[dragColumn], scroll[dragColumn] + lastY - e.getY()));
					lastY = e.getY();
					invalidate();
				}
				return true;
			case MotionEvent.ACTION_UP:
			case MotionEvent.ACTION_CANCEL:
				if (dragColumn >= 0 && velocity != null) {
					velocity.addMovement(e);
					velocity.computeCurrentVelocity(1000);
					int vy = (int)-velocity.getYVelocity();
					if (e.getActionMasked() == MotionEvent.ACTION_UP && Math.abs(vy) > dp(200)) {
						flingColumn = dragColumn;
						scroller.fling(0, (int)scroll[dragColumn], 0, vy, 0, 0, 0, (int)maxScroll[dragColumn]);
						postInvalidateOnAnimation();
					}
				}
				if (velocity != null) {
					velocity.recycle();
					velocity = null;
				}
				dragColumn = -1;
				return true;
			}
			return false;
		}

		@Override
		public void computeScroll() {
			if (flingColumn >= 0 && scroller.computeScrollOffset()) {
				scroll[flingColumn] = scroller.getCurrY();
				postInvalidateOnAnimation();
			}
		}

		private String ellipsize(String text, float maxWidth) {
			if (paint.measureText(text) <= maxWidth) {
				return text;
			}
			int n = text.length();
			while (n > 1 && paint.measureText(text, 0, n) + paint.measureText("…") > maxWidth) {
				n--;
			}
			return text.substring(0, n) + "…";
		}
	}
}
