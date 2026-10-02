package org.ppsspp.ppsspp.duo.games;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
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
import java.util.Locale;

// Gran Turismo (PSP): speedometer, tachometer with the car's own red line, pedals, and a track map
// that draws itself as you drive.
//
// Found on UCES01245 v2.00. Both objects are on the heap, so they're located by signature every
// race (and found again when the old ones go away):
// - car body: the object with class pointers 0x08CFF290 / 0x08CFF2D0 at +0x48 / +0x50 and a
//   1.0 at +0x9C. It holds a 4x4 matrix at +0x60; the third row (+0x80) points backwards and the
//   position is at +0x90 (x, height, z; x and z are the ground plane).
// - telemetry holder: 0x08CC5AD0 at +0x00 and 0x08D75EB0 at +0x1C; +0x18 points to the telemetry:
//   +0x40 rpm (f32), +0x44 red line start and end (two u16), +0x48 speed in km/h (f32),
//   +0x50 brake, +0x54 throttle (0..1).
public final class GranTurismoMod extends DuoMod {
	public static final String ID = "gran_turismo";
	private static final String TAG = "PPSSPPDuo";

	private static final String[] GAME_IDS = {"UCES01245", "UCUS98632", "UCJS10100", "UCAS40265"};

	private static final int SCAN_START = 0x08804000;
	private static final int SCAN_END = 0x0A000000;
	private static final int[] CAR_OFFSETS = {0x48, 0x50, 0x9C};
	private static final int[] CAR_VALUES = {0x08CFF290, 0x08CFF2D0, 0x3F800000};
	private static final int[] HOLDER_OFFSETS = {0x00, 0x1C};
	private static final int[] HOLDER_VALUES = {0x08CC5AD0, 0x08D75EB0};

	private static final int W_CAR = 0;
	private static final int W_HOLDER = 1;
	private static final int W_TELEMETRY = 2;

	private DuoModContext host;
	private GtView view;

	private int car, holder, telemetry;
	private boolean searchingCar, searchingHolder;
	private long lastSearch;

	// The track drawn so far. Kept across tab switches; reset when the game or the track changes.
	private final Track track = new Track();
	private String trackGame = "";

	@Override
	public String getId() {
		return ID;
	}

	@Override
	public String getTitle(Context context) {
		return context.getString(R.string.duo_mod_gt);
	}

	@Override
	public String getDescription(Context context) {
		return context.getString(R.string.duo_mod_gt_desc);
	}

	@Override
	public int getPriority(DuoStatus s) {
		for (String id : GAME_IDS) {
			if (id.equals(s.gameId)) {
				return 100;
			}
		}
		return s.title.toLowerCase(Locale.ROOT).startsWith("gran turismo") ? 100 : -1;
	}

	@Override
	public long getStatusIntervalMs() {
		return 50;
	}

	@Override
	public View onCreateView(DuoModContext host) {
		this.host = host;
		view = new GtView(host.getContext(), track);
		car = holder = telemetry = 0;
		searchingCar = searchingHolder = false;
		return view;
	}

	private void watch() {
		host.setMemoryWatches(
			new int[] {car != 0 ? car : SCAN_START, holder != 0 ? holder : SCAN_START, telemetry != 0 ? telemetry : SCAN_START},
			new int[] {0xA0, 0x20, 0x60});
	}

	private void search() {
		long now = SystemClock.uptimeMillis();
		if (searchingCar || searchingHolder || now - lastSearch < 2000) {
			return;
		}
		lastSearch = now;
		if (car == 0) {
			searchingCar = host.findMemory(SCAN_START, SCAN_END, CAR_OFFSETS, CAR_VALUES, addr -> {
				searchingCar = false;
				if (addr != 0 && addr != car) {
					Log.i(TAG, "GT: car at " + Integer.toHexString(addr));
					car = addr;
					watch();
				}
			});
		}
		if (holder == 0) {
			searchingHolder = host.findMemory(SCAN_START, SCAN_END, HOLDER_OFFSETS, HOLDER_VALUES, addr -> {
				searchingHolder = false;
				if (addr != 0 && addr != holder) {
					Log.i(TAG, "GT: telemetry holder at " + Integer.toHexString(addr));
					holder = addr;
					telemetry = 0;
					watch();
				}
			});
		}
	}

	private static ByteBuffer le(byte[] b) {
		return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
	}

	@Override
	public void onStatus(DuoStatus s) {
		if (!s.path.equals(trackGame)) {
			trackGame = s.path;
			track.clear();
		}
		if (!s.isInGame() && !s.isPauseMenu()) {
			view.setLive(false);
			return;
		}
		boolean haveCar = false, haveTelemetry = false;

		if (car != 0) {
			byte[] c = host.readMemoryWatch(W_CAR);
			if (c != null) {
				ByteBuffer b = le(c);
				if (b.getInt(0x48) != CAR_VALUES[0] || b.getInt(0x50) != CAR_VALUES[1]) {
					car = 0;  // gone (race over): look again
				} else {
					float x = b.getFloat(0x90), z = b.getFloat(0x98);
					float bx = b.getFloat(0x80), bz = b.getFloat(0x88);
					if (!Float.isNaN(x) && !Float.isNaN(z) && Math.abs(x) < 1e5 && Math.abs(z) < 1e5) {
						view.setCar(x, z, -bx, -bz);
						haveCar = true;
					}
				}
			}
		}

		if (holder != 0) {
			byte[] h = host.readMemoryWatch(W_HOLDER);
			if (h != null) {
				ByteBuffer b = le(h);
				if (b.getInt(0x00) != HOLDER_VALUES[0] || b.getInt(0x1C) != HOLDER_VALUES[1]) {
					holder = 0;
					telemetry = 0;
				} else {
					int t = b.getInt(0x18);
					if (t != telemetry && t >= 0x08800000 && t < 0x0A000000) {
						telemetry = t;
						watch();
					} else if (telemetry != 0) {
						byte[] tm = host.readMemoryWatch(W_TELEMETRY);
						if (tm != null) {
							ByteBuffer tb = le(tm);
							float rpm = tb.getFloat(0x40);
							int redStart = tb.getShort(0x44) & 0xFFFF;
							int redEnd = tb.getShort(0x46) & 0xFFFF;
							float speed = tb.getFloat(0x48);
							float brake = tb.getFloat(0x50);
							float throttle = tb.getFloat(0x54);
							if (rpm >= 0 && rpm < 30000 && speed > -50 && speed < 1000) {
								view.setTelemetry(speed, rpm, redStart, redEnd, throttle, brake);
								haveTelemetry = true;
							}
						}
					}
				}
			}
		}

		if (car == 0 || holder == 0) {
			search();
		}
		view.setLive(haveCar || haveTelemetry);
	}

	@Override
	public void onDestroyView() {
		view = null;
		host = null;
	}

	// Ground positions visited, in game units. Grows as you drive; the outline of the track appears
	// after the first lap.
	static final class Track {
		static final int MAX = 6000;
		static final float STEP = 2.0f;
		final float[] xz = new float[MAX * 2];
		int count;
		float minX, maxX, minZ, maxZ;

		void clear() {
			count = 0;
		}

		void add(float x, float z) {
			if (count > 0) {
				float lx = xz[(count - 1) * 2], lz = xz[(count - 1) * 2 + 1];
				float d = (float)Math.hypot(x - lx, z - lz);
				if (d < STEP) {
					return;
				}
				if (d > 200) {
					// New track or a reset: start over.
					count = 0;
				}
			}
			if (count >= MAX) {
				// Keep the outline: thin out the history.
				for (int i = 0; i < MAX / 2; i++) {
					xz[i * 2] = xz[i * 4];
					xz[i * 2 + 1] = xz[i * 4 + 1];
				}
				count = MAX / 2;
			}
			if (count == 0) {
				minX = maxX = x;
				minZ = maxZ = z;
			}
			xz[count * 2] = x;
			xz[count * 2 + 1] = z;
			count++;
			minX = Math.min(minX, x);
			maxX = Math.max(maxX, x);
			minZ = Math.min(minZ, z);
			maxZ = Math.max(maxZ, z);
		}
	}

	private static final class GtView extends View {
		private final Track track;
		private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
		private final Path path = new Path();
		private final RectF rect = new RectF();

		private boolean live;
		private boolean hasCar;
		private float carX, carZ, dirX, dirZ = 1;
		private boolean hasTelemetry;
		private float speed, rpm, throttle, brake;
		private int redStart, redEnd;
		private float shownRpmMax = 8000;

		GtView(Context context, Track track) {
			super(context);
			this.track = track;
			setBackgroundColor(DuoUi.COLOR_BACKGROUND);
			paint.setTypeface(Typeface.DEFAULT_BOLD);
		}

		void setLive(boolean l) {
			if (l != live) {
				live = l;
				invalidate();
			}
		}

		void setCar(float x, float z, float dx, float dz) {
			track.add(x, z);
			float len = (float)Math.hypot(dx, dz);
			if (len > 0.01f) {
				dirX = dx / len;
				dirZ = dz / len;
			}
			if (!hasCar || Math.abs(x - carX) > 0.05f || Math.abs(z - carZ) > 0.05f) {
				invalidate();
			}
			carX = x;
			carZ = z;
			hasCar = true;
		}

		void setTelemetry(float speed, float rpm, int redStart, int redEnd, float throttle, float brake) {
			boolean changed = Math.abs(speed - this.speed) > 0.2f || Math.abs(rpm - this.rpm) > 20
				|| Math.abs(throttle - this.throttle) > 0.02f || Math.abs(brake - this.brake) > 0.02f;
			this.speed = speed;
			this.rpm = rpm;
			this.redStart = redStart;
			this.redEnd = redEnd;
			this.throttle = throttle;
			this.brake = brake;
			hasTelemetry = true;
			// Scale: the next 1000 above the end of the red line.
			int top = redEnd > 1000 && redEnd < 25000 ? redEnd : 8000;
			shownRpmMax = (float)Math.ceil(top / 1000.0) * 1000;
			if (changed) {
				invalidate();
			}
		}

		@Override
		protected void onDraw(Canvas canvas) {
			Context ctx = getContext();
			float w = getWidth(), h = getHeight();
			float pad = DuoUi.dp(ctx, 28);
			if (!live) {
				paint.setStyle(Paint.Style.FILL);
				paint.setTextAlign(Paint.Align.CENTER);
				paint.setTextSize(DuoUi.dp(ctx, 18));
				paint.setColor(DuoUi.COLOR_TEXT_DIM);
				canvas.drawText(ctx.getString(R.string.duo_gt_waiting), w / 2, h / 2, paint);
				return;
			}
			float split = w * 0.5f;
			drawGauges(canvas, pad, pad, split - pad / 2, h - pad);
			drawMap(canvas, split + pad / 2, pad, w - pad, h - pad);
		}

		private void drawGauges(Canvas canvas, float l, float t, float r, float b) {
			Context ctx = getContext();
			float cx = (l + r) / 2;
			float barsH = DuoUi.dp(ctx, 54);
			float radius = Math.min((r - l) / 2, (b - t - barsH) / 1.75f);
			float cy = t + radius + DuoUi.dp(ctx, 6);

			// Tachometer arc: 225 degrees, red zone from the car's red line.
			float start = 157.5f, sweep = 225f;
			float stroke = radius * 0.11f;
			rect.set(cx - radius + stroke, cy - radius + stroke, cx + radius - stroke, cy + radius - stroke);
			paint.setStyle(Paint.Style.STROKE);
			paint.setStrokeCap(Paint.Cap.BUTT);
			paint.setStrokeWidth(stroke);
			paint.setColor(DuoUi.COLOR_SURFACE);
			canvas.drawArc(rect, start, sweep, false, paint);
			if (hasTelemetry && redStart > 0 && redStart < shownRpmMax) {
				float rs = start + sweep * redStart / shownRpmMax;
				paint.setColor(0x60E04848);
				canvas.drawArc(rect, rs, start + sweep - rs, false, paint);
			}
			if (hasTelemetry) {
				float frac = Math.max(0, Math.min(1, rpm / shownRpmMax));
				boolean red = redStart > 0 && rpm >= redStart;
				paint.setColor(red ? 0xFFE04848 : DuoUi.COLOR_ACCENT);
				canvas.drawArc(rect, start, sweep * frac, false, paint);
			}
			// Ticks every 1000 rpm.
			paint.setStrokeWidth(DuoUi.dp(ctx, 2));
			paint.setColor(DuoUi.COLOR_TEXT_DIM);
			paint.setStyle(Paint.Style.FILL);
			paint.setTextAlign(Paint.Align.CENTER);
			paint.setTextSize(radius * 0.11f);
			int ticks = (int)(shownRpmMax / 1000);
			for (int i = 0; i <= ticks; i++) {
				double a = Math.toRadians(start + sweep * i / ticks);
				float ri = radius - stroke * 2.2f;
				canvas.drawText(String.valueOf(i), cx + (float)Math.cos(a) * ri, cy + (float)Math.sin(a) * ri + radius * 0.04f, paint);
			}

			// Speed in the middle.
			paint.setColor(DuoUi.COLOR_TEXT);
			paint.setTextSize(radius * 0.48f);
			String sp = hasTelemetry ? String.valueOf(Math.round(Math.max(0, speed))) : "--";
			canvas.drawText(sp, cx, cy + radius * 0.17f, paint);
			paint.setColor(DuoUi.COLOR_TEXT_DIM);
			paint.setTextSize(radius * 0.13f);
			canvas.drawText("km/h", cx, cy + radius * 0.38f, paint);
			if (hasTelemetry) {
				canvas.drawText(String.format(Locale.US, "%,d rpm", Math.round(rpm)), cx, cy + radius * 0.62f, paint);
			}

			// Pedals.
			float barH = DuoUi.dp(ctx, 16);
			float y = b - barsH + DuoUi.dp(ctx, 4);
			bar(canvas, l, r, y, barH, throttle, DuoUi.COLOR_GOOD, ctx.getString(R.string.duo_gt_throttle));
			bar(canvas, l, r, y + barH + DuoUi.dp(ctx, 10), barH, brake, 0xFFE04848, ctx.getString(R.string.duo_gt_brake));
		}

		private void bar(Canvas canvas, float l, float r, float y, float barH, float value, int color, String label) {
			Context ctx = getContext();
			float labelW = DuoUi.dp(ctx, 64);
			paint.setStyle(Paint.Style.FILL);
			paint.setTextAlign(Paint.Align.LEFT);
			paint.setTextSize(DuoUi.dp(ctx, 13));
			paint.setColor(DuoUi.COLOR_TEXT_DIM);
			canvas.drawText(label, l, y + barH * 0.85f, paint);
			rect.set(l + labelW, y, r, y + barH);
			paint.setColor(DuoUi.COLOR_SURFACE);
			canvas.drawRoundRect(rect, barH / 2, barH / 2, paint);
			float v = Math.max(0, Math.min(1, value));
			if (v > 0.01f) {
				rect.set(l + labelW, y, l + labelW + (r - l - labelW) * v, y + barH);
				paint.setColor(color);
				canvas.drawRoundRect(rect, barH / 2, barH / 2, paint);
			}
		}

		private void drawMap(Canvas canvas, float l, float t, float r, float b) {
			Context ctx = getContext();
			rect.set(l, t, r, b);
			paint.setStyle(Paint.Style.FILL);
			paint.setColor(DuoUi.COLOR_SURFACE);
			float corner = DuoUi.dp(ctx, 14);
			canvas.drawRoundRect(rect, corner, corner, paint);
			if (track.count < 2) {
				paint.setTextAlign(Paint.Align.CENTER);
				paint.setTextSize(DuoUi.dp(ctx, 14));
				paint.setColor(DuoUi.COLOR_TEXT_DIM);
				canvas.drawText(ctx.getString(R.string.duo_gt_map_hint), (l + r) / 2, (t + b) / 2, paint);
				return;
			}
			float m = DuoUi.dp(ctx, 18);
			float spanX = Math.max(50, track.maxX - track.minX);
			float spanZ = Math.max(50, track.maxZ - track.minZ);
			float scale = Math.min((r - l - 2 * m) / spanX, (b - t - 2 * m) / spanZ);
			float ox = (l + r) / 2 - (track.minX + track.maxX) / 2 * scale;
			float oz = (t + b) / 2 - (track.minZ + track.maxZ) / 2 * scale;

			path.reset();
			for (int i = 0; i < track.count; i++) {
				float sx = ox + track.xz[i * 2] * scale, sy = oz + track.xz[i * 2 + 1] * scale;
				if (i == 0) {
					path.moveTo(sx, sy);
				} else {
					path.lineTo(sx, sy);
				}
			}
			paint.setStyle(Paint.Style.STROKE);
			paint.setStrokeJoin(Paint.Join.ROUND);
			paint.setStrokeCap(Paint.Cap.ROUND);
			paint.setStrokeWidth(DuoUi.dp(ctx, 7));
			paint.setColor(0xFF3A4656);
			canvas.drawPath(path, paint);
			paint.setStrokeWidth(DuoUi.dp(ctx, 2));
			paint.setColor(DuoUi.COLOR_TEXT_DIM);
			canvas.drawPath(path, paint);

			if (hasCar) {
				float sx = ox + carX * scale, sy = oz + carZ * scale;
				float rr = DuoUi.dp(ctx, 11);
				float nx = -dirZ, nz = dirX;
				path.reset();
				path.moveTo(sx + dirX * rr * 1.3f, sy + dirZ * rr * 1.3f);
				path.lineTo(sx - dirX * rr * 0.8f + nx * rr * 0.8f, sy - dirZ * rr * 0.8f + nz * rr * 0.8f);
				path.lineTo(sx - dirX * rr * 0.35f, sy - dirZ * rr * 0.35f);
				path.lineTo(sx - dirX * rr * 0.8f - nx * rr * 0.8f, sy - dirZ * rr * 0.8f - nz * rr * 0.8f);
				path.close();
				paint.setStyle(Paint.Style.FILL);
				paint.setColor(0xFFFFFFFF);
				canvas.drawPath(path, paint);
				paint.setStyle(Paint.Style.STROKE);
				paint.setStrokeWidth(DuoUi.dp(ctx, 2));
				paint.setColor(0xFFE0402A);
				canvas.drawPath(path, paint);
			}
		}
	}
}
