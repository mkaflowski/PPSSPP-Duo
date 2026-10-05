package org.ppsspp.ppsspp.duo.games;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.View;

import org.ppsspp.ppsspp.R;
import org.ppsspp.ppsspp.duo.DuoMod;
import org.ppsspp.ppsspp.duo.DuoModContext;
import org.ppsspp.ppsspp.duo.DuoStatus;
import org.ppsspp.ppsspp.duo.DuoUi;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

// WipEout Pure: speed, shield energy, position, lap and lap times, and a map of the track drawn from
// where all eight ships have been, in the look of the game's HUD (see WipeoutFont).
//
// Found on UCUS98612 v2.00 (from a savestate in a single race). The ships are a static array in the
// main module, 8 records of 0x1C0 bytes at 0x08B72024:
// - +0x08 race time (f32 seconds), +0x84 0 for the player's ship (1 for the AI), +0x9C / +0xA4 x
//   and z on the ground plane (+0xA0 is height), +0x110 race position, +0x11C lap.
// - +0x144 (player only) points to the ship's controller: +0xF8 shield energy (f32, 0..100, the
//   ship is destroyed at 0), +0xF0 points to its physics, with the speed in km/h at +0x174 (f32;
//   the HUD shows the forward part of it, so they differ while scraping a wall).
public final class WipeoutPureMod extends DuoMod {
	public static final String ID = "wipeout_pure";

	private static final String[] GAME_IDS = {"UCUS98612"};

	private static final int SHIPS = 0x08B72024;
	private static final int SHIP_SIZE = 0x1C0;
	private static final int SHIP_COUNT = 8;

	private static final int W_SHIPS = 0;
	private static final int W_CONTROL = 1;
	private static final int W_PHYSICS = 2;

	private DuoModContext host;
	private RaceView view;
	private int control, physics;
	private long lastSeen;

	// Lap timing, worked out from the race time when the lap changes.
	private int lastLap = -1;
	private float lapStart = -1, lastLapTime = -1, bestLapTime = -1, lastTime = -1;

	// The game's HUD font, read from the disc once.
	private static WipeoutFont fontLarge;
	private static boolean fontsLoading;

	// The track, as the cells any ship has driven over. Kept across tab switches.
	private static final float CELL = 3.0f;
	private static final int MAX_CELLS = 30000;
	private final Set<Long> cells = new HashSet<>();
	private float minX, maxX, minZ, maxZ;

	static final class Race {
		float time, speed, energy;
		int position, lap, ships;
		int player;
		float[] x = new float[SHIP_COUNT], z = new float[SHIP_COUNT];
		boolean[] active = new boolean[SHIP_COUNT];
	}

	@Override
	public String getId() {
		return ID;
	}

	@Override
	public String getTitle(Context context) {
		return context.getString(R.string.duo_mod_wipeout);
	}

	@Override
	public String getDescription(Context context) {
		return context.getString(R.string.duo_mod_wipeout_desc);
	}

	@Override
	public int getPriority(DuoStatus s) {
		return Arrays.asList(GAME_IDS).contains(s.gameId) ? 100 : -1;
	}

	@Override
	public long getStatusIntervalMs() {
		return 100;
	}

	@Override
	public View onCreateView(DuoModContext host) {
		this.host = host;
		view = new RaceView(host.getContext());
		control = physics = 0;
		watch();
		loadFonts();
		return view;
	}

	private void watch() {
		host.setMemoryWatches(
			new int[] {SHIPS, control != 0 ? control : SHIPS, physics != 0 ? physics + 0x170 : SHIPS},
			new int[] {SHIP_SIZE * SHIP_COUNT, 0x100, 8});
	}

	private void loadFonts() {
		if (fontLarge != null || fontsLoading) {
			return;
		}
		fontsLoading = true;
		DuoStatus s = host.getStatus();
		File dir = new File(host.getContext().getFilesDir(), "duo/wipeout/" + s.gameId + "_" + s.discVersion + "_1");
		WipeoutFont.load(host, dir, new int[] {WipeoutFont.HASH_LARGE}, fonts -> {
			fontsLoading = false;
			fontLarge = fonts[0];
			if (view != null) {
				view.invalidate();
			}
		});
	}

	private static boolean isPointer(int p) {
		return p >= 0x08800000 && p < 0x0A000000;
	}

	@Override
	public void onStatus(DuoStatus s) {
		if (!s.isInGame() && !s.isPauseMenu()) {
			return;
		}
		byte[] shipData = host.readMemoryWatch(W_SHIPS);
		if (shipData == null) {
			return;
		}
		ByteBuffer b = ByteBuffer.wrap(shipData).order(ByteOrder.LITTLE_ENDIAN);
		Race race = new Race();
		race.player = -1;
		for (int i = 0; i < SHIP_COUNT; i++) {
			int r = i * SHIP_SIZE;
			float x = b.getFloat(r + 0x9C), z = b.getFloat(r + 0xA4);
			race.active[i] = b.getInt(r) == i && !Float.isNaN(x) && !Float.isNaN(z) && Math.abs(x) < 1e6f && Math.abs(z) < 1e6f;
			race.x[i] = x;
			race.z[i] = z;
			if (race.active[i]) {
				race.ships++;
				if (b.getInt(r + 0x84) == 0 && isPointer(b.getInt(r + 0x144)) && race.player < 0) {
					race.player = i;
				}
			}
		}
		if (race.player < 0) {
			// Between races, or a moment while the game rebuilds its objects: keep the last
			// picture for a bit rather than flashing the idle screen.
			if (SystemClock.uptimeMillis() - lastSeen > 2000) {
				view.setRace(null);
			}
			return;
		}
		lastSeen = SystemClock.uptimeMillis();
		int p = race.player * SHIP_SIZE;
		race.time = b.getFloat(p + 0x08);
		race.position = b.getInt(p + 0x110);
		race.lap = b.getInt(p + 0x11C);
		int ctl = b.getInt(p + 0x144);
		if (ctl != control) {
			control = ctl;
			physics = 0;
			watch();
			return;
		}
		byte[] ctlData = host.readMemoryWatch(W_CONTROL);
		if (ctlData == null) {
			return;
		}
		ByteBuffer cb = ByteBuffer.wrap(ctlData).order(ByteOrder.LITTLE_ENDIAN);
		race.energy = cb.getFloat(0xF8);
		int phys = cb.getInt(0xF0);
		if (isPointer(phys) && phys != physics) {
			physics = phys;
			watch();
			return;
		}
		byte[] physData = host.readMemoryWatch(W_PHYSICS);
		if (physData != null && physics != 0) {
			race.speed = ByteBuffer.wrap(physData).order(ByteOrder.LITTLE_ENDIAN).getFloat(4);
		}
		if (Float.isNaN(race.speed) || race.speed < 0 || race.speed > 5000) {
			race.speed = 0;
		}

		// A race time going backwards is a new race (or a restart): forget the track and the laps.
		if (lastTime >= 0 && race.time < lastTime - 1) {
			cells.clear();
			lastLap = -1;
			lapStart = lastLapTime = bestLapTime = -1;
		}
		lastTime = race.time;
		if (race.lap != lastLap) {
			if (lastLap >= 0 && race.lap == lastLap + 1 && lapStart >= 0) {
				lastLapTime = race.time - lapStart;
				if (bestLapTime < 0 || lastLapTime < bestLapTime) {
					bestLapTime = lastLapTime;
				}
			}
			// The first lap starts with the race; joining mid-lap, its start isn't known.
			lapStart = lastLap < 0 ? (race.lap <= 1 && race.time < 1 ? 0 : -1) : race.time;
			lastLap = race.lap;
		}
		for (int i = 0; i < SHIP_COUNT; i++) {
			if (race.active[i]) {
				addCell(race.x[i], race.z[i]);
			}
		}
		view.setRace(race);
	}

	private void addCell(float x, float z) {
		if (cells.size() >= MAX_CELLS) {
			return;
		}
		long cx = (long)Math.floor(x / CELL), cz = (long)Math.floor(z / CELL);
		if (cells.add((cx << 32) ^ (cz & 0xFFFFFFFFL))) {
			if (cells.size() == 1) {
				minX = maxX = x;
				minZ = maxZ = z;
			}
			minX = Math.min(minX, x);
			maxX = Math.max(maxX, x);
			minZ = Math.min(minZ, z);
			maxZ = Math.max(maxZ, z);
		}
	}

	static String time(float t) {
		if (t < 0) {
			return "-:--.-";
		}
		int tenths = (int)(t * 10);
		return String.format(Locale.US, "%d:%02d.%d", tenths / 600, (tenths / 10) % 60, tenths % 10);
	}

	@Override
	public void onDestroyView() {
		view = null;
		host = null;
	}

	// Drawn like the game's HUD: its lettering, gold labels and outlines, the red speed bar and the
	// pale blue energy bar. The plain font stands in until (or unless) the game's is read.
	private final class RaceView extends View {
		private static final int BACKGROUND = 0xFF07111F;
		private static final int PANEL = 0xFF0D1C30;
		private static final int GOLD = 0xFFF2B21C;
		private static final int WHITE = 0xFFF2F6F8;
		private static final int RED = 0xFFB3120E;
		private static final int ENERGY = 0xFFDFF6FC;
		private static final int ENERGY_LOW = 0xFFF0A21C;
		private static final int ENERGY_CRITICAL = 0xFFE0302A;
		private static final int DIM = 0xFF5D7088;

		private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
		private final Paint fallback = new Paint(Paint.ANTI_ALIAS_FLAG);
		private final RectF rect = new RectF();
		private final Path path = new Path();
		private Race race;
		private float topSpeed = 600;

		RaceView(Context context) {
			super(context);
			setBackgroundColor(BACKGROUND);
			fallback.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
		}

		void setRace(Race race) {
			this.race = race;
			if (race != null) {
				topSpeed = Math.max(topSpeed, race.speed * 1.1f);
			}
			invalidate();
		}

		// Text in the game's font when it's loaded, the plain one otherwise. Top at y.
		private float text(Canvas canvas, String s, float x, float y, float h, int color, boolean alignRight) {
			WipeoutFont f = fontLarge;
			if (f != null) {
				float w = f.measure(s, h);
				float left = alignRight ? x - w : x;
				f.draw(canvas, s, left, y, h, color);
				return alignRight ? left : left + w;
			}
			fallback.setColor(color);
			fallback.setTextSize(h * 1.1f);
			float w = fallback.measureText(s);
			float left = alignRight ? x - w : x;
			canvas.drawText(s, left, y + h, fallback);
			return alignRight ? left : left + w;
		}

		@Override
		protected void onDraw(Canvas canvas) {
			Context ctx = getContext();
			float w = getWidth(), h = getHeight();
			if (race == null) {
				fallback.setColor(DIM);
				fallback.setTextAlign(Paint.Align.CENTER);
				fallback.setTextSize(DuoUi.dp(ctx, 18));
				canvas.drawText(ctx.getString(R.string.duo_wipeout_idle), w / 2, h / 2, fallback);
				fallback.setTextAlign(Paint.Align.LEFT);
				return;
			}
			// Four quadrants: position and race time, speed and energy, lap times, the map. The
			// margin keeps clear of the panel's rounded corners.
			float pad = DuoUi.dp(ctx, 26);
			float gap = DuoUi.dp(ctx, 28);
			float midX = w / 2, midY = h / 2;
			drawPosition(canvas, pad, pad, midX - gap / 2, midY - gap / 2);
			drawSpeed(canvas, midX + gap / 2, pad, w - pad, midY - gap / 2);
			drawTimes(canvas, pad, midY + gap / 2, midX - gap / 2, h - pad);
			drawMap(canvas, midX + gap / 2, midY + gap / 2, w - pad, h - pad);
		}

		private void drawPosition(Canvas canvas, float l, float t, float r, float b) {
			Context ctx = getContext();
			float label = DuoUi.dp(ctx, 15);
			float big = Math.min(DuoUi.dp(ctx, 64), (b - t) * 0.36f);

			// POS 8/8 on the left, LAP on the right, like the HUD's corners.
			text(canvas, "POS", l, t, label, GOLD, false);
			text(canvas, "LAP", r, t, label, GOLD, true);
			float y = t + label + DuoUi.dp(ctx, 8);
			float x = text(canvas, String.valueOf(race.position), l, y, big, WHITE, false);
			x = text(canvas, "/", x + DuoUi.dp(ctx, 2), y + big * 0.45f, big * 0.55f, GOLD, false);
			text(canvas, String.valueOf(race.ships), x + DuoUi.dp(ctx, 2), y + big * 0.45f, big * 0.55f, WHITE, false);
			text(canvas, String.valueOf(Math.max(1, race.lap)), r, y, big, WHITE, true);

			// The race time at the bottom of the quadrant.
			float timeH = Math.min(DuoUi.dp(ctx, 40), (b - t) * 0.22f);
			float ty = b - timeH;
			text(canvas, "CURRENT", l, ty - label - DuoUi.dp(ctx, 6), label, GOLD, false);
			text(canvas, time(race.time), l, ty, timeH, WHITE, false);
		}

		private void drawSpeed(Canvas canvas, float l, float t, float r, float b) {
			Context ctx = getContext();
			float label = DuoUi.dp(ctx, 15);
			float barH = DuoUi.dp(ctx, 30);
			float kmh = DuoUi.dp(ctx, 12);
			float energyH = DuoUi.dp(ctx, 22);
			// From the bottom up: energy label, energy bar, KM/H, speed bar; the number takes the rest.
			float energyLabelY = b - label * 0.8f;
			float energyTop = energyLabelY - DuoUi.dp(ctx, 6) - energyH;
			float kmhTop = energyTop - DuoUi.dp(ctx, 14) - kmh - DuoUi.dp(ctx, 6);
			float barTop = kmhTop - DuoUi.dp(ctx, 4) - barH;
			float speedH = Math.min(DuoUi.dp(ctx, 90), barTop - DuoUi.dp(ctx, 12) - t);

			text(canvas, String.valueOf((int)race.speed), r, barTop - DuoUi.dp(ctx, 12) - speedH, speedH, WHITE, true);
			drawSpeedBar(canvas, l, barTop, r, barTop + barH, Math.max(0, Math.min(1, race.speed / topSpeed)));
			float kmhW = fontLarge != null ? fontLarge.measure("KM/H", kmh) : kmh * 3;
			rect.set(r - kmhW - DuoUi.dp(ctx, 10), kmhTop, r, kmhTop + kmh + DuoUi.dp(ctx, 6));
			paint.setStyle(Paint.Style.FILL);
			paint.setColor(PANEL);
			canvas.drawRect(rect, paint);
			text(canvas, "KM/H", r - DuoUi.dp(ctx, 5), kmhTop + DuoUi.dp(ctx, 3), kmh, WHITE, true);

			float energy = Math.max(0, Math.min(100, race.energy));
			drawEnergyBar(canvas, l, energyTop, r, energyTop + energyH, energy / 100);
			text(canvas, "ENERGY", l, energyLabelY, label * 0.8f, GOLD, false);
			// The game's font has no percent sign.
			text(canvas, String.valueOf(Math.round(energy)), r, energyLabelY, label * 0.8f, WHITE, true);
		}

		private void drawTimes(Canvas canvas, float l, float t, float r, float b) {
			Context ctx = getContext();
			float label = DuoUi.dp(ctx, 15);
			float lapNow = lapStart >= 0 ? race.time - lapStart : -1;
			String[] labels = {"LAP", "LAST", "BEST"};
			float[] values = {lapNow, lastLapTime, bestLapTime};
			float rowH = (b - t) / 3;
			float valueH = Math.min(DuoUi.dp(ctx, 34), rowH - label - DuoUi.dp(ctx, 12));
			for (int i = 0; i < 3; i++) {
				float y = t + rowH * i;
				text(canvas, labels[i], l, y, label, GOLD, false);
				int color = i == 2 && bestLapTime >= 0 ? GOLD : WHITE;
				text(canvas, time(values[i]), l, y + label + DuoUi.dp(ctx, 6), valueH, values[i] < 0 ? DIM : color, false);
			}
		}

		// The HUD's speed bar: low on the left, a step up two thirds along, gold outline, red fill
		// along the lower band.
		private void drawSpeedBar(Canvas canvas, float l, float t, float r, float b, float frac) {
			float h = b - t;
			float step = l + (r - l) * 0.62f;
			float slope = h * 0.45f;
			float low = t + h * 0.45f;
			path.reset();
			path.moveTo(l + slope * 0.4f, low);
			path.lineTo(step, low);
			path.lineTo(step + slope, t);
			path.lineTo(r, t);
			path.lineTo(r, b);
			path.lineTo(l + slope * 0.4f, b);
			path.lineTo(l, b - slope * 0.4f);
			path.lineTo(l, low + slope * 0.4f);
			path.close();
			paint.setStyle(Paint.Style.FILL);
			paint.setColor(PANEL);
			canvas.drawPath(path, paint);
			if (frac > 0) {
				canvas.save();
				canvas.clipPath(path);
				rect.set(l, low, l + (r - l) * frac, b);
				paint.setColor(RED);
				canvas.drawRect(rect, paint);
				canvas.restore();
			}
			paint.setStyle(Paint.Style.STROKE);
			paint.setStrokeWidth(DuoUi.dp(getContext(), 2));
			paint.setStrokeJoin(Paint.Join.MITER);
			paint.setColor(GOLD);
			canvas.drawPath(path, paint);
			paint.setStyle(Paint.Style.FILL);
		}

		// The HUD's energy bar: thick on the left, a step down to a thinner right part.
		private void drawEnergyBar(Canvas canvas, float l, float t, float r, float b, float frac) {
			float h = b - t;
			float step = l + (r - l) * 0.55f;
			float thin = t + h * 0.35f;
			path.reset();
			path.moveTo(l, t);
			path.lineTo(step, t);
			path.lineTo(step + h * 0.65f, thin);
			path.lineTo(r, thin);
			path.lineTo(r, b);
			path.lineTo(l, b);
			path.close();
			paint.setStyle(Paint.Style.FILL);
			paint.setColor(PANEL);
			canvas.drawPath(path, paint);
			if (frac > 0) {
				canvas.save();
				canvas.clipPath(path);
				rect.set(l, t, l + (r - l) * frac, b);
				paint.setColor(frac > 0.5f ? ENERGY : frac > 0.25f ? ENERGY_LOW : ENERGY_CRITICAL);
				canvas.drawRect(rect, paint);
				canvas.restore();
			}
		}

		// Centered in the box, wrapped at word boundaries.
		private void drawHint(Canvas canvas, String hint, float l, float t, float r, float b) {
			Context ctx = getContext();
			fallback.setColor(DIM);
			fallback.setTextAlign(Paint.Align.CENTER);
			fallback.setTextSize(DuoUi.dp(ctx, 15));
			float maxW = r - l - DuoUi.dp(ctx, 40);
			java.util.List<String> lines = new java.util.ArrayList<>();
			StringBuilder line = new StringBuilder();
			for (String word : hint.split(" ")) {
				String next = line.length() == 0 ? word : line + " " + word;
				if (fallback.measureText(next) > maxW && line.length() > 0) {
					lines.add(line.toString());
					line = new StringBuilder(word);
				} else {
					line = new StringBuilder(next);
				}
			}
			lines.add(line.toString());
			float lineH = fallback.getTextSize() * 1.4f;
			float y = (t + b) / 2 - lineH * (lines.size() - 1) / 2 + fallback.getTextSize() * 0.35f;
			for (String s : lines) {
				canvas.drawText(s, (l + r) / 2, y, fallback);
				y += lineH;
			}
			fallback.setTextAlign(Paint.Align.LEFT);
		}

		private void drawMap(Canvas canvas, float l, float t, float r, float b) {
			Context ctx = getContext();
			// A panel with the gold corner brackets of the game's menus.
			rect.set(l, t, r, b);
			paint.setStyle(Paint.Style.FILL);
			paint.setColor(PANEL);
			canvas.drawRect(rect, paint);
			float arm = DuoUi.dp(ctx, 18);
			paint.setStyle(Paint.Style.STROKE);
			paint.setStrokeWidth(DuoUi.dp(ctx, 2));
			paint.setColor(GOLD);
			path.reset();
			path.moveTo(l, t + arm); path.lineTo(l, t); path.lineTo(l + arm, t);
			path.moveTo(r - arm, t); path.lineTo(r, t); path.lineTo(r, t + arm);
			path.moveTo(r, b - arm); path.lineTo(r, b); path.lineTo(r - arm, b);
			path.moveTo(l + arm, b); path.lineTo(l, b); path.lineTo(l, b - arm);
			canvas.drawPath(path, paint);
			paint.setStyle(Paint.Style.FILL);
			text(canvas, "TRACK", l + DuoUi.dp(ctx, 12), t + DuoUi.dp(ctx, 10), DuoUi.dp(ctx, 12), GOLD, false);

			if (cells.size() < 20) {
				drawHint(canvas, ctx.getString(R.string.duo_wipeout_map_hint), l, t, r, b);
				return;
			}
			// Fit the track, north up (z grows downwards on screen).
			float inset = DuoUi.dp(ctx, 30);
			float spanX = Math.max(1, maxX - minX), spanZ = Math.max(1, maxZ - minZ);
			float scale = Math.min((r - l - 2 * inset) / spanX, (b - t - 2 * inset) / spanZ);
			float ox = (l + r) / 2 - (minX + maxX) / 2 * scale;
			float oz = (t + b) / 2 - (minZ + maxZ) / 2 * scale;
			float dot = Math.max(DuoUi.dp(ctx, 2.5f), CELL * scale * 0.9f);
			paint.setColor(0x80DFF6FC);
			for (long key : cells) {
				float cx = (key >> 32) * CELL + CELL / 2;
				float cz = (int)key * CELL + CELL / 2;
				canvas.drawCircle(ox + cx * scale, oz + cz * scale, dot, paint);
			}
			for (int i = 0; i < SHIP_COUNT; i++) {
				if (race.active[i] && i != race.player) {
					paint.setColor(GOLD);
					canvas.drawCircle(ox + race.x[i] * scale, oz + race.z[i] * scale, DuoUi.dp(ctx, 6), paint);
				}
			}
			float px = ox + race.x[race.player] * scale, pz = oz + race.z[race.player] * scale;
			paint.setColor(WHITE);
			canvas.drawCircle(px, pz, DuoUi.dp(ctx, 11), paint);
			paint.setColor(RED);
			canvas.drawCircle(px, pz, DuoUi.dp(ctx, 8), paint);
		}
	}
}
