package org.ppsspp.ppsspp.duo.games;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
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
// and the enemies on the right.
//
// Found on UCUS98700 v1.00 (from savestates in the first battle). The battle state is in the main
// module's data, so the addresses are fixed:
// - 0x08ABA500: turn number (u16), 0 outside battles.
// - 0x08ABA6E0: 64 unit records of 0x140 bytes. +0x0C name (ASCII, 32 bytes), +0x2D character
//   (u8), +0x30 side (2 = player, 3 = enemy), +0x34 portrait (u16), +0x36 max HP, +0x38 max MP, +0x8E HP, +0x90 MP, +0x94 level, +0x96 exp
//   (all u16), +0x99 / +0x9A position on the map. Unused records are zero.
// A copy of the table follows at 0x08ABF6E0; the game doesn't read it during a turn (checked with
// read breakpoints), so it's a snapshot rather than the live data.
//
// Portraits are the 64x64 GIMs in DATA/FACE/MFACE<portrait>.GIM, read from the disc. The portrait
// follows the story (Jeanne is MFACE021 in the first battle and MFACE001 once she wears armor), so
// it's taken from the record; the character number is the fallback.
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

	private static final String FACE_PATH = "disc0:/PSP_GAME/USRDIR/DATA/FACE/MFACE%03d.GIM";
	private static final int FACE_READ_SIZE = 16 * 1024;
	private static final int FACE_ATTEMPTS = 3;
	private static final long FACE_RETRY_MS = 3000;

	private DuoModContext host;
	private BattleView view;

	// Portraits by number, kept across tab switches. A failed read (the emulator paused, a missing
	// file) is retried a few times.
	private final Map<Integer, Bitmap> faces = new HashMap<>();
	private final Map<Integer, Integer> faceAttempts = new HashMap<>();
	private final Map<Integer, Long> faceLastTry = new HashMap<>();
	private final Map<Integer, Boolean> facePending = new HashMap<>();

	static final class Unit {
		String name;
		int side, character, portrait, level, exp, hp, maxHp, mp, maxMp;
		Bitmap face;

		boolean defeated() {
			return hp <= 0;
		}

		String signature() {
			return name + side + "," + (face != null ? portrait : -1) + "," + level + "," + exp + "," + hp + "/" + maxHp + "," + mp + "/" + maxMp + ";";
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
		int half = UNITS_PER_WATCH * UNIT_SIZE;
		host.setMemoryWatches(
			new int[] {TURN_ADDR, UNITS_ADDR, UNITS_ADDR + half},
			new int[] {2, half, half});
		facePending.clear();
		return view;
	}

	@Override
	public void onStatus(DuoStatus s) {
		if (!s.isInGame() && !s.isPauseMenu()) {
			view.setData(0, null);
			return;
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
		// Game files can only be read while the game runs.
		boolean canLoad = s.isInGame();
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
					u.face = face(u.portrait, canLoad);
					if (u.face == null && hasFaceFailed(u.portrait)) {
						u.face = face(u.character, canLoad);
					}
					units.add(u);
				}
			}
		}
		view.setData(turn, units);
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

	// The portrait, or null while it loads (the read is started here) or if it can't be had.
	private Bitmap face(int id, boolean canLoad) {
		Bitmap bmp = faces.get(id);
		if (bmp != null || !canLoad || id <= 0 || id > 999 || hasFaceFailed(id) || facePending.containsKey(id)) {
			return bmp;
		}
		Long last = faceLastTry.get(id);
		long now = SystemClock.uptimeMillis();
		if (last != null && now - last < FACE_RETRY_MS) {
			return null;
		}
		String path = String.format(Locale.US, FACE_PATH, id);
		DuoModContext h = host;
		boolean queued = h.readGameFile(path, 0, FACE_READ_SIZE, data -> {
			facePending.remove(id);
			Bitmap decoded = data != null && data.length > 0 ? GimImage.decode(data) : null;
			if (decoded != null) {
				faces.put(id, trim(decoded));
			} else {
				faceAttempts.put(id, attempts(id) + 1);
			}
		});
		if (queued) {
			facePending.put(id, true);
			faceLastTry.put(id, now);
		}
		return null;
	}

	// The portraits have a few transparent columns on the right (and a row at the bottom), which
	// would leave a gap at the card's edge.
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

	private int attempts(int id) {
		Integer n = faceAttempts.get(id);
		return n != null ? n : 0;
	}

	private boolean hasFaceFailed(int id) {
		return id <= 0 || id > 999 || attempts(id) >= FACE_ATTEMPTS;
	}

	@Override
	public void onDestroyView() {
		view = null;
		host = null;
	}

	private static final class BattleView extends View {
		private static final int COLOR_HP = 0xFF4CC38A;
		private static final int COLOR_HP_LOW = 0xFFE3A33B;
		private static final int COLOR_HP_CRITICAL = 0xFFE04848;
		private static final int COLOR_MP = 0xFF3D9BE9;
		private static final int COLOR_PLAYER = 0xFF3D9BE9;
		private static final int COLOR_ENEMY = 0xFFE04848;
		private static final int COLOR_OTHER = 0xFF4CC38A;

		private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
		private final Paint facePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
		private final Paint fadePaint = new Paint();
		private final ColorMatrixColorFilter grayscale;
		private final RectF rect = new RectF();
		private final Path clip = new Path();
		private int turn;
		private final List<Unit> party = new ArrayList<>();
		private final List<Unit> enemies = new ArrayList<>();
		private String signature = "";

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
			setBackgroundColor(DuoUi.COLOR_BACKGROUND);
			scroller = new OverScroller(context);
			ColorMatrix m = new ColorMatrix();
			m.setSaturation(0);
			grayscale = new ColorMatrixColorFilter(m);
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
			party.clear();
			enemies.clear();
			if (units != null) {
				// Guests and other allies go with the party. The defeated sink to the bottom.
				for (int pass = 0; pass < 2; pass++) {
					for (Unit u : units) {
						if (u.defeated() == (pass == 1)) {
							(u.side == SIDE_ENEMY ? enemies : party).add(u);
						}
					}
				}
			}
			invalidate();
		}

		@Override
		protected void onDraw(Canvas canvas) {
			Context ctx = getContext();
			float w = getWidth(), h = getHeight();
			paint.setTypeface(Typeface.DEFAULT_BOLD);
			if (turn == 0) {
				paint.setColor(DuoUi.COLOR_TEXT_DIM);
				paint.setTextAlign(Paint.Align.CENTER);
				paint.setTextSize(DuoUi.dp(ctx, 18));
				canvas.drawText(ctx.getString(R.string.duo_jeanne_idle), w / 2, h / 2, paint);
				return;
			}

			// The margin keeps clear of the panel's rounded corners.
			float pad = DuoUi.dp(ctx, 28);
			float gap = DuoUi.dp(ctx, 20);
			float headerH = DuoUi.dp(ctx, 44);

			paint.setTextAlign(Paint.Align.CENTER);
			paint.setTextSize(DuoUi.dp(ctx, 22));
			paint.setColor(DuoUi.COLOR_TEXT);
			canvas.drawText(ctx.getString(R.string.duo_jeanne_turn, turn), w / 2, pad + DuoUi.dp(ctx, 22), paint);

			float colW = (w - 2 * pad - gap) / 2;
			float top = pad + headerH;
			drawColumn(canvas, ctx.getString(R.string.duo_jeanne_party), party, pad, top, colW, h - pad, true);
			drawColumn(canvas, ctx.getString(R.string.duo_jeanne_enemies), enemies, pad + colW + gap, top, colW, h - pad, false);
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

		private void drawColumn(Canvas canvas, String title, List<Unit> units, float x, float top, float colW, float bottom, boolean isParty) {
			Context ctx = getContext();
			int col = isParty ? 0 : 1;
			paint.setTextAlign(Paint.Align.LEFT);
			paint.setTextSize(DuoUi.dp(ctx, 15));
			paint.setColor(DuoUi.COLOR_TEXT_DIM);
			canvas.drawText(title, x, top + DuoUi.dp(ctx, 15), paint);
			paint.setTextAlign(Paint.Align.RIGHT);
			canvas.drawText(alive(units) + " / " + units.size(), x + colW, top + DuoUi.dp(ctx, 15), paint);
			top += DuoUi.dp(ctx, 26);
			columns[col].set(x, top, x + colW, bottom);

			// Cards shrink to fit. Below a size they turn into one-line rows, and when even those
			// don't fit, the column scrolls.
			int n = units.size();
			float avail = bottom - top;
			float cardSpacing = DuoUi.dp(ctx, 8);
			float fit = n > 0 ? (avail + cardSpacing) / n - cardSpacing : avail;
			boolean compact = fit < DuoUi.dp(ctx, 64);
			float itemH = compact ? DuoUi.dp(ctx, 40) : Math.min(DuoUi.dp(ctx, isParty ? 104 : 88), fit);
			float spacing = compact ? DuoUi.dp(ctx, 5) : cardSpacing;
			float contentH = n > 0 ? n * (itemH + spacing) - spacing : 0;
			maxScroll[col] = Math.max(0, contentH - avail);
			scroll[col] = Math.max(0, Math.min(maxScroll[col], scroll[col]));

			canvas.save();
			canvas.clipRect(x - 1, top, x + colW + 1, bottom);
			float y = top - scroll[col];
			for (Unit u : units) {
				if (y + itemH >= top && y <= bottom) {
					if (compact) {
						drawRow(canvas, u, x, y, colW, itemH);
					} else {
						drawUnit(canvas, u, x, y, colW, itemH);
					}
				}
				y += itemH + spacing;
			}
			canvas.restore();

			if (maxScroll[col] > 0) {
				// Scrollbar, in the gap next to the column.
				float trackX = x + colW + DuoUi.dp(ctx, 6);
				float thumbH = Math.max(DuoUi.dp(ctx, 24), avail * avail / contentH);
				float thumbY = top + (avail - thumbH) * scroll[col] / maxScroll[col];
				float bw = DuoUi.dp(ctx, 3);
				rect.set(trackX, top, trackX + bw, bottom);
				paint.setColor(DuoUi.COLOR_SURFACE);
				canvas.drawRoundRect(rect, bw / 2, bw / 2, paint);
				rect.set(trackX, thumbY, trackX + bw, thumbY + thumbH);
				paint.setColor(DuoUi.COLOR_TEXT_DIM);
				canvas.drawRoundRect(rect, bw / 2, bw / 2, paint);
			}
		}

		// Background, portrait and side stripe. Returns the portrait's width.
		private float drawCard(Canvas canvas, Unit u, float x, float y, float w, float h, float r) {
			Context ctx = getContext();
			boolean dead = u.defeated();
			rect.set(x, y, x + w, y + h);
			paint.setColor(DuoUi.COLOR_SURFACE);
			canvas.drawRoundRect(rect, r, r, paint);
			// Portrait on the right, fading into the card so the text stays readable.
			float faceSize = 0;
			if (u.face != null) {
				// Card height, right and bottom edges flush with the card.
				float faceH = Math.min(h, w * 0.5f * u.face.getHeight() / u.face.getWidth());
				faceSize = faceH * u.face.getWidth() / u.face.getHeight();
				float fx = x + w - faceSize;
				canvas.save();
				clip.reset();
				clip.addRoundRect(rect, r, r, Path.Direction.CW);
				canvas.clipPath(clip);
				facePaint.setColorFilter(dead ? grayscale : null);
				facePaint.setAlpha(dead ? 110 : 255);
				rect.set(fx, y + h - faceH, x + w, y + h);
				canvas.drawBitmap(u.face, null, rect, facePaint);
				fadePaint.setShader(new LinearGradient(fx, 0, fx + faceSize * 0.6f, 0,
					DuoUi.COLOR_SURFACE, DuoUi.COLOR_SURFACE & 0x00FFFFFF, Shader.TileMode.CLAMP));
				canvas.drawRect(fx - 1, y, fx + faceSize * 0.6f, y + h, fadePaint);
				canvas.restore();
			}
			// Side stripe.
			int sideColor = u.side == SIDE_PLAYER ? COLOR_PLAYER : u.side == SIDE_ENEMY ? COLOR_ENEMY : COLOR_OTHER;
			paint.setColor(dead ? DuoUi.COLOR_TEXT_DISABLED : sideColor);
			float sw = DuoUi.dp(ctx, 6);
			rect.set(x, y, x + sw, y + h);
			canvas.drawRoundRect(rect, Math.min(r, sw), Math.min(r, sw), paint);
			rect.set(x + sw / 2, y, x + sw, y + h);
			canvas.drawRect(rect, paint);
			return faceSize;
		}

		// One line: name, then HP.
		private void drawRow(Canvas canvas, Unit u, float x, float y, float w, float h) {
			Context ctx = getContext();
			boolean dead = u.defeated();
			float faceSize = drawCard(canvas, u, x, y, w, h, DuoUi.dp(ctx, 8));
			float inner = DuoUi.dp(ctx, 12);
			float left = x + DuoUi.dp(ctx, 6) + inner;
			float right = x + w - inner - faceSize * 0.4f;
			float nameW = (right - left) * 0.42f;
			paint.setTextSize(Math.min(DuoUi.dp(ctx, 15), h * 0.4f));
			paint.setTextAlign(Paint.Align.LEFT);
			paint.setColor(dead ? DuoUi.COLOR_TEXT_DISABLED : DuoUi.COLOR_TEXT);
			canvas.drawText(ellipsize(u.name, nameW), left, y + h / 2 + paint.getTextSize() * 0.35f, paint);
			float hpFrac = u.maxHp > 0 ? u.hp / (float)u.maxHp : 0;
			int hpColor = hpFrac > 0.5f ? COLOR_HP : hpFrac > 0.25f ? COLOR_HP_LOW : COLOR_HP_CRITICAL;
			drawBar(canvas, "HP", u.hp, u.maxHp, dead ? DuoUi.COLOR_TEXT_DISABLED : hpColor, left + nameW + DuoUi.dp(ctx, 8), y, right, h);
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
					if (e.getActionMasked() == MotionEvent.ACTION_UP && Math.abs(vy) > DuoUi.dp(getContext(), 200)) {
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

		private void drawUnit(Canvas canvas, Unit u, float x, float y, float w, float h) {
			Context ctx = getContext();
			boolean dead = u.defeated();
			float faceSize = drawCard(canvas, u, x, y, w, h, DuoUi.dp(ctx, 10));

			float inner = DuoUi.dp(ctx, 14);
			float left = x + DuoUi.dp(ctx, 6) + inner;
			// Keep the text off the clear part of the portrait.
			float right = x + w - inner - faceSize * 0.4f;
			// Name and level fit the top part, the bars share the rest.
			float nameSize = Math.min(DuoUi.dp(ctx, 17), h * 0.21f);
			float pad = Math.min(DuoUi.dp(ctx, 10), h * 0.12f);
			float nameBase = y + pad + nameSize * 0.95f;
			String level = dead ? getContext().getString(R.string.duo_jeanne_defeated) : getContext().getString(R.string.duo_jeanne_level, u.level);
			paint.setTextSize(nameSize * 0.8f);
			float levelW = paint.measureText(level);
			paint.setTextAlign(Paint.Align.RIGHT);
			paint.setColor(dead ? DuoUi.COLOR_TEXT_DIM : DuoUi.COLOR_TEXT);
			canvas.drawText(level, right, nameBase, paint);
			paint.setTextSize(nameSize);
			paint.setTextAlign(Paint.Align.LEFT);
			paint.setColor(dead ? DuoUi.COLOR_TEXT_DISABLED : DuoUi.COLOR_TEXT);
			canvas.drawText(ellipsize(u.name, right - left - levelW - nameSize * 0.6f), left, nameBase, paint);

			float barsTop = nameBase + pad;
			float barsBottom = y + h - pad;
			boolean showMp = u.maxMp > 0 && barsBottom - barsTop > DuoUi.dp(ctx, 30);
			int rows = showMp ? 2 : 1;
			float rowH = (barsBottom - barsTop) / rows;
			float hpFrac = u.maxHp > 0 ? u.hp / (float)u.maxHp : 0;
			int hpColor = hpFrac > 0.5f ? COLOR_HP : hpFrac > 0.25f ? COLOR_HP_LOW : COLOR_HP_CRITICAL;
			drawBar(canvas, "HP", u.hp, u.maxHp, dead ? DuoUi.COLOR_TEXT_DISABLED : hpColor, left, barsTop, right, rowH);
			if (showMp) {
				drawBar(canvas, "MP", u.mp, u.maxMp, dead ? DuoUi.COLOR_TEXT_DISABLED : COLOR_MP, left, barsTop + rowH, right, rowH);
			}
		}

		private void drawBar(Canvas canvas, String label, int value, int max, int color, float left, float top, float right, float rowH) {
			Context ctx = getContext();
			float textSize = Math.min(DuoUi.dp(ctx, 15), rowH * 0.7f);
			float base = top + rowH / 2 + textSize * 0.35f;
			paint.setTextSize(textSize);
			paint.setTextAlign(Paint.Align.LEFT);
			paint.setColor(DuoUi.COLOR_TEXT_DIM);
			canvas.drawText(label, left, base, paint);
			String numbers = value + " / " + max;
			paint.setTextAlign(Paint.Align.RIGHT);
			paint.setColor(DuoUi.COLOR_TEXT);
			canvas.drawText(numbers, right, base, paint);
			float barLeft = left + textSize * 2;
			float barRight = right - paint.measureText("000 / 000") - textSize * 0.6f;
			if (barRight <= barLeft) {
				return;
			}
			float barH = Math.max(DuoUi.dp(ctx, 4), Math.min(DuoUi.dp(ctx, 10), rowH * 0.35f));
			float barTop = top + (rowH - barH) / 2;
			rect.set(barLeft, barTop, barRight, barTop + barH);
			paint.setColor(DuoUi.COLOR_BACKGROUND);
			canvas.drawRoundRect(rect, barH / 2, barH / 2, paint);
			float frac = max > 0 ? Math.max(0, Math.min(1, value / (float)max)) : 0;
			if (frac > 0) {
				rect.set(barLeft, barTop, barLeft + Math.max(barH, (barRight - barLeft) * frac), barTop + barH);
				paint.setColor(color);
				canvas.drawRoundRect(rect, barH / 2, barH / 2, paint);
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
