package org.ppsspp.ppsspp.duo.games;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.ppsspp.ppsspp.R;
import org.ppsspp.ppsspp.duo.DuoMod;
import org.ppsspp.ppsspp.duo.DuoModContext;
import org.ppsspp.ppsspp.duo.DuoStatus;
import org.ppsspp.ppsspp.duo.DuoUi;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;

// Gran Turismo (PSP): speedometer, tachometer with the car's own red line, gear, pedals, and a map
// of the track with every car on it. Two looks: the game's own (its HUD digits, the car's picture,
// the track's logo and photo, read from the disc by GtArt) and a plain one.
//
// Found on UCES01245 v2.00. Everything is on the heap, so it's found by signature every race (and
// again when it goes away):
// - cars: one object per car (0x430 apart here) with a 4x4 matrix at +0x60 (the third row, +0x80,
//   points backwards; position at +0x90: x, height, z), the car's name at +0xD8, and the same
//   pointer at +0x24/+0x30/+0x3C/+0x48 followed by 0/1/2/3. AI cars have their driver at +0x320,
//   the player's car has 0 there.
// - telemetry holder: 0x08CC5AD0 at +0x00 and 0x08D75EB0 at +0x1C; +0x18 points to three copies
//   of the telemetry block, 0x14C apart (which ones are live varies between races):
//   +0x24 gear as an ASCII character in the low byte, +0x40 rpm (f32), +0x44 red line start and
//   end (two u16), +0x48 speed in km/h (f32), +0x50 brake, +0x54 throttle (0..1).
// - race map: the race's piece_gt5m/course_map_race/*.bin as loaded ('GTCM', a 'TXS3' at +0x80):
//   +0x2C point count, +0x30 offset of the points, each the left and right edge of the road
//   (x, z, x, z floats) in world coordinates.
public final class GranTurismoMod extends DuoMod {
	public static final String ID = "gran_turismo";
	private static final String TAG = "PPSSPPDuo";

	private static final String[] GAME_IDS = {"UCES01245", "UCUS98632", "UCJS10100", "UCAS40265"};

	private static final int SCAN_START = 0x08804000;
	private static final int SCAN_END = 0x0A000000;
	private static final int CAR_PTR = 0x08CC2E48;
	private static final int[] CAR_OFFSETS = {0x24, 0x30, 0x3C, 0x48, 0x34, 0x40, 0x4C};
	private static final int[] CAR_VALUES = {CAR_PTR, CAR_PTR, CAR_PTR, CAR_PTR, 1, 2, 3};
	private static final int CAR_SIZE = 0x330;
	private static final int MAX_CARS = 8;
	private static final int[] HOLDER_OFFSETS = {0x00, 0x1C};
	private static final int[] HOLDER_VALUES = {0x08CC5AD0, 0x08D75EB0};
	private static final int[] MAP_OFFSETS = {0x00, 0x80};
	private static final int[] MAP_VALUES = {0x4D435447, 0x33535854};   // 'GTCM', 'TXS3'
	private static final int MAP_SIZE = 0x4000;

	private static final int TELEMETRY_COPY = 0x14C;
	private static final int TELEMETRY_COPIES = 3;

	private static final int W_HOLDER = 0;
	private static final int W_TELEMETRY = 1;
	private static final int W_MAP = 2;
	private static final int W_CAR = 3;

	private static final String PREFS = "duo_gran_turismo";
	private static final String PREF_SHOW_MAP = "show_map";
	private static final String PREF_GAME_LOOK = "game_look";

	private DuoModContext host;
	private GtView view;
	private TextView mapButton, styleButton;

	private int holder, telemetry, map;
	private final int[] cars = new int[MAX_CARS];
	private int carCount;
	private final ArrayList<Integer> foundCars = new ArrayList<>();
	private boolean searchingCars, searchingHolder, searchingMap;
	private long lastSearch;

	// The track drawn so far. Kept across tab switches; reset when the game or the track changes.
	private final Track track = new Track();
	private String trackGame = "";
	private String mapKey = "";
	private String carName = "";

	private GtArt art;
	private String artGame = "";

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
		Context ctx = host.getContext();
		FrameLayout root = new FrameLayout(ctx);
		view = new GtView(ctx, track);
		root.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
		holder = telemetry = map = 0;
		carCount = 0;
		searchingCars = searchingHolder = searchingMap = false;
		mapKey = "";
		carName = "";
		artGame = "";

		// Bottom row: the look (game / plain) and hiding the map, which gives the gauges the whole screen.
		SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
		view.setShowMap(prefs.getBoolean(PREF_SHOW_MAP, true));
		view.setGameLook(prefs.getBoolean(PREF_GAME_LOOK, true));
		LinearLayout row = new LinearLayout(ctx);
		row.setOrientation(LinearLayout.HORIZONTAL);
		int padH = DuoUi.dp(ctx, 12);
		int gap = DuoUi.dp(ctx, 8);
		styleButton = DuoUi.button(ctx, "");
		styleButton.setTextSize(13);
		styleButton.setPadding(padH, 0, padH, 0);
		styleButton.setOnClickListener(v -> {
			host.haptic(v);
			view.setGameLook(!view.gameLook);
			prefs.edit().putBoolean(PREF_GAME_LOOK, view.gameLook).apply();
			updateButtons();
		});
		mapButton = DuoUi.button(ctx, "");
		mapButton.setTextSize(13);
		mapButton.setPadding(padH, 0, padH, 0);
		mapButton.setOnClickListener(v -> {
			host.haptic(v);
			view.setShowMap(!view.showMap);
			prefs.edit().putBoolean(PREF_SHOW_MAP, view.showMap).apply();
			updateButtons();
		});
		int bh = DuoUi.dp(ctx, GtView.BUTTON_H_DP);
		LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, bh);
		row.addView(styleButton, blp);
		LinearLayout.LayoutParams blp2 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, bh);
		blp2.leftMargin = gap;
		row.addView(mapButton, blp2);
		FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, bh, Gravity.BOTTOM | Gravity.END);
		int m = DuoUi.dp(ctx, GtView.BUTTON_MARGIN_DP);
		lp.setMargins(m, m, m, m);
		root.addView(row, lp);
		updateButtons();
		return root;
	}

	private void updateButtons() {
		mapButton.setText(view.showMap ? R.string.duo_gt_hide_map : R.string.duo_gt_show_map);
		styleButton.setText(view.gameLook ? R.string.duo_gt_style_classic : R.string.duo_gt_style_game);
		styleButton.setVisibility(view.hasHud() ? View.VISIBLE : View.GONE);
	}

	private void watch() {
		int n = W_CAR + carCount;
		int[] addr = new int[n];
		int[] size = new int[n];
		addr[W_HOLDER] = holder != 0 ? holder : SCAN_START;
		size[W_HOLDER] = 0x20;
		addr[W_TELEMETRY] = telemetry != 0 ? telemetry : SCAN_START;
		size[W_TELEMETRY] = TELEMETRY_COPY * TELEMETRY_COPIES + 0x60;
		addr[W_MAP] = map != 0 ? map : SCAN_START;
		size[W_MAP] = map != 0 ? MAP_SIZE : 0x40;
		for (int i = 0; i < carCount; i++) {
			addr[W_CAR + i] = cars[i];
			size[W_CAR + i] = CAR_SIZE;
		}
		host.setMemoryWatches(addr, size);
	}

	private void search() {
		long now = SystemClock.uptimeMillis();
		if (searchingCars || searchingHolder || searchingMap || now - lastSearch < 2000) {
			return;
		}
		lastSearch = now;
		if (carCount == 0) {
			foundCars.clear();
			searchingCars = findCars(SCAN_START);
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
		if (map == 0) {
			searchingMap = host.findMemory(SCAN_START, SCAN_END, MAP_OFFSETS, MAP_VALUES, addr -> {
				searchingMap = false;
				if (addr != 0) {
					Log.i(TAG, "GT: race map at " + Integer.toHexString(addr));
					map = addr;
					watch();
				}
			});
		}
	}

	// Every car object, one scan after the other.
	private boolean findCars(int from) {
		return host.findMemory(from, SCAN_END, CAR_OFFSETS, CAR_VALUES, addr -> {
			if (addr != 0 && foundCars.size() < MAX_CARS) {
				foundCars.add(addr);
				if (findCars(addr + 4)) {
					return;
				}
			}
			searchingCars = false;
			carCount = foundCars.size();
			for (int i = 0; i < carCount; i++) {
				cars[i] = foundCars.get(i);
			}
			if (carCount > 0) {
				Log.i(TAG, "GT: " + carCount + " cars, first at " + Integer.toHexString(cars[0]));
				watch();
			}
		});
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
		if (art == null || !artGame.equals(s.gameId + s.discVersion)) {
			artGame = s.gameId + s.discVersion;
			File dir = new File(host.getContext().getFilesDir(), "duo/gt/" + s.gameId + "_" + s.discVersion + "_2");
			art = new GtArt(host, dir);
			art.hud(hud -> {
				if (view != null) {
					view.setHud(hud);
					updateButtons();
				}
			});
		}
		if (!s.isInGame() && !s.isPauseMenu()) {
			view.setLive(false);
			return;
		}
		boolean haveCar = readCars();
		boolean haveTelemetry = readTelemetry();
		readMap();
		if (carCount == 0 || holder == 0 || map == 0) {
			search();
		}
		view.setLive(haveCar || haveTelemetry);
	}

	private boolean readCars() {
		if (carCount == 0) {
			return false;
		}
		int player = -1;
		float[] rivals = new float[carCount * 2];
		int nr = 0;
		boolean gone = false;
		float px = 0, pz = 0, dx = 0, dz = 1;
		String name = null;
		for (int i = 0; i < carCount; i++) {
			byte[] c = host.readMemoryWatch(W_CAR + i);
			if (c == null) {
				continue;
			}
			ByteBuffer b = le(c);
			if (b.getInt(0x24) != CAR_PTR || b.getInt(0x48) != CAR_PTR || b.getInt(0x4C) != 3) {
				gone = true;   // race over: look again
				break;
			}
			float x = b.getFloat(0x90), z = b.getFloat(0x98);
			if (Float.isNaN(x) || Float.isNaN(z) || Math.abs(x) > 1e5 || Math.abs(z) > 1e5) {
				continue;
			}
			if (player < 0 && b.getInt(0x320) == 0) {
				player = i;
				px = x;
				pz = z;
				dx = -b.getFloat(0x80);
				dz = -b.getFloat(0x88);
				int e = 0xD8;
				while (e < 0x118 && c[e] != 0) {
					e++;
				}
				name = new String(c, 0xD8, e - 0xD8, StandardCharsets.US_ASCII).trim();
			} else {
				rivals[nr * 2] = x;
				rivals[nr * 2 + 1] = z;
				nr++;
			}
		}
		if (gone) {
			carCount = 0;
			watch();
			return false;
		}
		view.setRivals(rivals, nr);
		if (player < 0) {
			return false;
		}
		view.setCar(px, pz, dx, dz);
		if (name != null && !name.isEmpty() && !name.equals(carName)) {
			carName = name;
			view.setCarImage(null, name);
			String asked = name;
			art.car(name, bmp -> {
				if (view != null && asked.equals(carName)) {
					view.setCarImage(bmp, asked);
				}
			});
		}
		return true;
	}

	private boolean readTelemetry() {
		if (holder == 0) {
			return false;
		}
		byte[] h = host.readMemoryWatch(W_HOLDER);
		if (h == null) {
			return false;
		}
		ByteBuffer b = le(h);
		if (b.getInt(0x00) != HOLDER_VALUES[0] || b.getInt(0x1C) != HOLDER_VALUES[1]) {
			holder = 0;
			telemetry = 0;
			return false;
		}
		int t = b.getInt(0x18);
		if (t != telemetry && t >= 0x08800000 && t < 0x0A000000) {
			telemetry = t;
			watch();
			return false;
		}
		if (telemetry == 0) {
			return false;
		}
		byte[] tm = host.readMemoryWatch(W_TELEMETRY);
		if (tm == null) {
			return false;
		}
		ByteBuffer tb = le(tm);
		// Three copies of the block, 0x14C apart. Which ones are live differs between races (one
		// stays at zero or frozen), so use the one with the highest rpm.
		int o = 0;
		for (int k = 1; k < TELEMETRY_COPIES; k++) {
			if (tb.getFloat(k * TELEMETRY_COPY + 0x40) > tb.getFloat(o + 0x40)) {
				o = k * TELEMETRY_COPY;
			}
		}
		float rpm = tb.getFloat(o + 0x40);
		int redStart = tb.getShort(o + 0x44) & 0xFFFF;
		int redEnd = tb.getShort(o + 0x46) & 0xFFFF;
		float speed = tb.getFloat(o + 0x48);
		float brake = tb.getFloat(o + 0x50);
		float throttle = tb.getFloat(o + 0x54);
		// Gear as an ASCII character (1-6, N, R, D) in the low byte of +0x24; only one copy has it.
		char gear = 0;
		for (int k = 0; k < TELEMETRY_COPIES && gear == 0; k++) {
			int g = tb.get(k * TELEMETRY_COPY + 0x24) & 0xFF;
			if ((g >= '1' && g <= '9') || g == 'N' || g == 'R' || g == 'D') {
				gear = (char)g;
			}
		}
		if (rpm >= 0 && rpm < 30000 && speed > -50 && speed < 1000) {
			view.setTelemetry(speed, rpm, redStart, redEnd, throttle, brake, gear);
			return true;
		}
		return false;
	}

	private void readMap() {
		if (map == 0) {
			return;
		}
		byte[] d = host.readMemoryWatch(W_MAP);
		if (d == null || d.length < 0x40) {
			return;
		}
		ByteBuffer b = le(d);
		if (b.getInt(0) != MAP_VALUES[0]) {
			map = 0;   // race over
			watch();
			return;
		}
		if (d.length < MAP_SIZE) {
			return;   // header-only watch until the next one
		}
		String key = GtArt.mapKey(d, 0);
		if (key.equals(mapKey)) {
			return;
		}
		int n = b.getInt(0x2C), off = b.getInt(0x30);
		if (n < 2 || off < 0x40 || off + n * 16 > d.length) {
			return;
		}
		mapKey = key;
		float[] edges = new float[n * 4];
		for (int i = 0; i < n * 4; i++) {
			edges[i] = b.getFloat(off + i * 4);
		}
		track.clear();
		view.setOutline(edges, n);
		view.setTrackArt(null, null);
		art.trackName(key, name -> {
			Log.i(TAG, "GT: track " + name);
			if (name == null || view == null || !key.equals(mapKey)) {
				return;
			}
			art.trackImage(name, false, logo -> art.trackImage(name, true, photo -> {
				if (view != null && key.equals(mapKey)) {
					view.setTrackArt(logo, photo);
				}
			}));
		});
	}

	@Override
	public void onDestroyView() {
		view = null;
		mapButton = null;
		styleButton = null;
		host = null;
		art = null;
	}

	// Ground positions visited, in game units. Grows as you drive; used when the race map isn't
	// found.
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
		// The buttons sit in a row of their own at the bottom.
		static final int BUTTON_H_DP = 36;
		static final int BUTTON_MARGIN_DP = 12;

		private static final int RED = 0xFFE04848;
		private static final int GT_BLUE = 0xFF4FA3FF;
		private static final int SEG_OFF = 0xFF27303B;

		private final Track track;
		boolean showMap = true;
		boolean gameLook = true;
		private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
		private final Paint bmpPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
		private final Path path = new Path();
		private final RectF rect = new RectF();
		private final Rect src = new Rect();

		private boolean live;
		private boolean hasCar;
		private float carX, carZ, dirX, dirZ = 1;
		private float[] rivals = new float[0];
		private int rivalCount;
		private boolean hasTelemetry;
		private float speed, rpm, throttle, brake;
		private char gear;
		private int redStart, redEnd;
		private float shownRpmMax = 8000;

		private float[] edges;
		private int edgeCount;
		private float oMinX, oMaxX, oMinZ, oMaxZ;

		private GtArt.Hud hud;
		private Bitmap carImage, trackLogo, trackPhoto;
		private String carLabel = "";

		GtView(Context context, Track track) {
			super(context);
			this.track = track;
			setBackgroundColor(DuoUi.COLOR_BACKGROUND);
			paint.setTypeface(Typeface.DEFAULT_BOLD);
		}

		boolean hasHud() {
			return hud != null && hud.font != null;
		}

		private boolean game() {
			return gameLook && hasHud();
		}

		void setHud(GtArt.Hud hud) {
			this.hud = hud;
			invalidate();
		}

		void setCarImage(Bitmap bmp, String name) {
			carImage = bmp;
			carLabel = name;
			invalidate();
		}

		void setTrackArt(Bitmap logo, Bitmap photo) {
			trackLogo = logo;
			trackPhoto = photo;
			invalidate();
		}

		void setOutline(float[] e, int n) {
			edges = e;
			edgeCount = n;
			oMinX = oMinZ = Float.MAX_VALUE;
			oMaxX = oMaxZ = -Float.MAX_VALUE;
			for (int i = 0; i < n * 2; i++) {
				float x = e[i * 2], z = e[i * 2 + 1];
				oMinX = Math.min(oMinX, x);
				oMaxX = Math.max(oMaxX, x);
				oMinZ = Math.min(oMinZ, z);
				oMaxZ = Math.max(oMaxZ, z);
			}
			invalidate();
		}

		void setRivals(float[] xz, int n) {
			boolean changed = n != rivalCount;
			for (int i = 0; i < n * 2 && !changed; i++) {
				changed = Math.abs(xz[i] - rivals[i]) > 0.5f;
			}
			rivals = xz;
			rivalCount = n;
			if (changed) {
				invalidate();
			}
		}

		void setShowMap(boolean show) {
			showMap = show;
			invalidate();
		}

		void setGameLook(boolean on) {
			gameLook = on;
			invalidate();
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

		void setTelemetry(float speed, float rpm, int redStart, int redEnd, float throttle, float brake, char gear) {
			boolean changed = Math.abs(speed - this.speed) > 0.2f || Math.abs(rpm - this.rpm) > 20
				|| Math.abs(throttle - this.throttle) > 0.02f || Math.abs(brake - this.brake) > 0.02f || gear != this.gear;
			this.gear = gear;
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
			float pad = DuoUi.dp(ctx, 24);
			float bottom = h - DuoUi.dp(ctx, BUTTON_H_DP + 2 * BUTTON_MARGIN_DP);
			if (!live) {
				paint.setStyle(Paint.Style.FILL);
				paint.setTextAlign(Paint.Align.CENTER);
				paint.setTextSize(DuoUi.dp(ctx, 18));
				paint.setColor(DuoUi.COLOR_TEXT_DIM);
				canvas.drawText(ctx.getString(R.string.duo_gt_waiting), w / 2, h / 2, paint);
				return;
			}
			float split = showMap ? w * 0.5f : w;
			if (game()) {
				drawGaugesGame(canvas, pad, pad, split - (showMap ? pad / 2 : pad), bottom);
				if (showMap) {
					drawMapGame(canvas, split + pad / 2, pad, w - pad, bottom);
				}
			} else {
				drawGauges(canvas, pad, pad, split - (showMap ? pad / 2 : pad), bottom);
				if (showMap) {
					drawMap(canvas, split + pad / 2, pad, w - pad, bottom);
				}
			}
		}

		// ---- Plain look ----

		private void drawGauges(Canvas canvas, float l, float t, float r, float b) {
			Context ctx = getContext();
			float cx = (l + r) / 2;
			// The pedal bars grow a bit with the gauge (when it has the whole screen).
			float barH = Math.max(DuoUi.dp(ctx, 16), Math.min(DuoUi.dp(ctx, 24), (b - t) * 0.045f));
			float barGap = barH * 0.6f;
			float barsH = barH * 2 + barGap + DuoUi.dp(ctx, 18);
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
				paint.setColor(red ? RED : DuoUi.COLOR_ACCENT);
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

			// Gear above the speed.
			if (hasTelemetry && gear != 0) {
				float gs = radius * 0.14f;
				float gy = cy - radius * 0.45f;
				rect.set(cx - gs, gy - gs, cx + gs, gy + gs);
				paint.setStyle(Paint.Style.FILL);
				paint.setColor(DuoUi.COLOR_SURFACE_PRESSED);
				canvas.drawRoundRect(rect, gs * 0.3f, gs * 0.3f, paint);
				paint.setColor(DuoUi.COLOR_TEXT);
				paint.setTextSize(gs * 1.4f);
				canvas.drawText(String.valueOf(gear), cx, rect.centerY() - (paint.descent() + paint.ascent()) / 2, paint);
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

			drawPedals(canvas, cx, Math.min((r - l) / 2, radius * 1.3f), b, barH, barGap, DuoUi.COLOR_GOOD);
		}

		// Pedals, no wider than the gauge (so they don't stretch across the whole screen).
		private void drawPedals(Canvas canvas, float cx, float half, float b, float barH, float barGap, int throttleColor) {
			Context ctx = getContext();
			float y = b - barH * 2 - barGap;
			String throttleLabel = ctx.getString(R.string.duo_gt_throttle);
			String brakeLabel = ctx.getString(R.string.duo_gt_brake);
			paint.setTextSize(barH * 0.8f);
			float labelW = Math.max(paint.measureText(throttleLabel), paint.measureText(brakeLabel)) + barH * 0.8f;
			bar(canvas, cx - half, cx + half, y, barH, labelW, throttle, throttleColor, throttleLabel);
			bar(canvas, cx - half, cx + half, y + barH + barGap, barH, labelW, brake, RED, brakeLabel);
		}

		private void bar(Canvas canvas, float l, float r, float y, float barH, float labelW, float value, int color, String label) {
			paint.setStyle(Paint.Style.FILL);
			paint.setTextAlign(Paint.Align.LEFT);
			paint.setTextSize(barH * 0.8f);
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

		// Centered text broken into lines at spaces to fit maxW, with the paint's current size.
		private void drawWrapped(Canvas canvas, String text, float cx, float cy, float maxW) {
			ArrayList<String> lines = new ArrayList<>();
			String line = "";
			for (String word : text.split(" ")) {
				String next = line.isEmpty() ? word : line + " " + word;
				if (!line.isEmpty() && paint.measureText(next) > maxW) {
					lines.add(line);
					line = word;
				} else {
					line = next;
				}
			}
			lines.add(line);
			float lineH = paint.getTextSize() * 1.3f;
			float y = cy - lineH * (lines.size() - 1) / 2 - (paint.descent() + paint.ascent()) / 2;
			for (String s : lines) {
				canvas.drawText(s, cx, y, paint);
				y += lineH;
			}
		}

		// World to screen for the map: x right, z down (as on the game's own map), fitted to the box.
		private float mapScale, mapOx, mapOz;

		private boolean fitMap(float l, float t, float r, float b, float m) {
			float minX, maxX, minZ, maxZ;
			if (edges != null) {
				minX = oMinX; maxX = oMaxX; minZ = oMinZ; maxZ = oMaxZ;
			} else if (track.count >= 2) {
				minX = track.minX; maxX = track.maxX; minZ = track.minZ; maxZ = track.maxZ;
			} else {
				return false;
			}
			float spanX = Math.max(50, maxX - minX);
			float spanZ = Math.max(50, maxZ - minZ);
			mapScale = Math.min((r - l - 2 * m) / spanX, (b - t - 2 * m) / spanZ);
			mapOx = (l + r) / 2 - (minX + maxX) / 2 * mapScale;
			mapOz = (t + b) / 2 - (minZ + maxZ) / 2 * mapScale;
			return true;
		}

		private void centerline(Path p) {
			p.reset();
			for (int i = 0; i <= edgeCount; i++) {
				int k = (i % edgeCount) * 4;
				float sx = mapOx + (edges[k] + edges[k + 2]) / 2 * mapScale;
				float sy = mapOz + (edges[k + 1] + edges[k + 3]) / 2 * mapScale;
				if (i == 0) {
					p.moveTo(sx, sy);
				} else {
					p.lineTo(sx, sy);
				}
			}
		}

		private void trail(Path p) {
			p.reset();
			for (int i = 0; i < track.count; i++) {
				float sx = mapOx + track.xz[i * 2] * mapScale, sy = mapOz + track.xz[i * 2 + 1] * mapScale;
				if (i == 0) {
					p.moveTo(sx, sy);
				} else {
					p.lineTo(sx, sy);
				}
			}
		}

		private void drawMap(Canvas canvas, float l, float t, float r, float b) {
			Context ctx = getContext();
			rect.set(l, t, r, b);
			paint.setStyle(Paint.Style.FILL);
			paint.setColor(DuoUi.COLOR_SURFACE);
			float corner = DuoUi.dp(ctx, 14);
			canvas.drawRoundRect(rect, corner, corner, paint);
			if (!fitMap(l, t, r, b, DuoUi.dp(ctx, 18))) {
				paint.setTextAlign(Paint.Align.CENTER);
				paint.setTextSize(DuoUi.dp(ctx, 14));
				paint.setColor(DuoUi.COLOR_TEXT_DIM);
				drawWrapped(canvas, ctx.getString(R.string.duo_gt_map_hint), (l + r) / 2, (t + b) / 2, r - l - DuoUi.dp(ctx, 32));
				return;
			}
			if (edges != null) {
				centerline(path);
			} else {
				trail(path);
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
			drawRivals(canvas, DuoUi.dp(ctx, 4), DuoUi.COLOR_TEXT_DIM, 0);
			drawArrow(canvas, DuoUi.dp(ctx, 11), 0xFFFFFFFF, 0xFFE0402A);
		}

		private void drawRivals(Canvas canvas, float rr, int fill, int ring) {
			paint.setStyle(Paint.Style.FILL);
			for (int i = 0; i < rivalCount; i++) {
				float sx = mapOx + rivals[i * 2] * mapScale, sy = mapOz + rivals[i * 2 + 1] * mapScale;
				if (ring != 0) {
					paint.setColor(ring);
					canvas.drawCircle(sx, sy, rr * 1.35f, paint);
				}
				paint.setColor(fill);
				canvas.drawCircle(sx, sy, rr, paint);
			}
		}

		private void drawArrow(Canvas canvas, float rr, int fill, int outline) {
			if (!hasCar) {
				return;
			}
			float sx = mapOx + carX * mapScale, sy = mapOz + carZ * mapScale;
			float nx = -dirZ, nz = dirX;
			path.reset();
			path.moveTo(sx + dirX * rr * 1.3f, sy + dirZ * rr * 1.3f);
			path.lineTo(sx - dirX * rr * 0.8f + nx * rr * 0.8f, sy - dirZ * rr * 0.8f + nz * rr * 0.8f);
			path.lineTo(sx - dirX * rr * 0.35f, sy - dirZ * rr * 0.35f);
			path.lineTo(sx - dirX * rr * 0.8f - nx * rr * 0.8f, sy - dirZ * rr * 0.8f - nz * rr * 0.8f);
			path.close();
			paint.setStyle(Paint.Style.FILL);
			paint.setColor(fill);
			canvas.drawPath(path, paint);
			paint.setStyle(Paint.Style.STROKE);
			paint.setStrokeJoin(Paint.Join.ROUND);
			paint.setStrokeWidth(DuoUi.dp(getContext(), 2));
			paint.setColor(outline);
			canvas.drawPath(path, paint);
		}

		// ---- Game look ----

		private void panel(Canvas canvas, float l, float t, float r, float b) {
			Context ctx = getContext();
			float corner = DuoUi.dp(ctx, 10);
			rect.set(l, t, r, b);
			paint.setStyle(Paint.Style.FILL);
			paint.setShader(new LinearGradient(0, t, 0, b, 0xFF1E2631, 0xFF10151C, Shader.TileMode.CLAMP));
			canvas.drawRoundRect(rect, corner, corner, paint);
			paint.setShader(null);
			paint.setStyle(Paint.Style.STROKE);
			paint.setStrokeWidth(DuoUi.dp(ctx, 1.5f));
			paint.setColor(0xFF3B4757);
			canvas.drawRoundRect(rect, corner, corner, paint);
		}

		// One glyph from the game's RaceFonts atlas into dst, tinted.
		private void glyph(Canvas canvas, int sx, int sy, int sw, int sh, RectF dst, int color) {
			src.set(sx, sy, sx + sw, sy + sh);
			bmpPaint.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
			canvas.drawBitmap(hud.font, src, dst, bmpPaint);
			bmpPaint.setColorFilter(null);
		}

		// Big italic digits: 26x26 cells (25 high, the next row starts right below), 0-4 then 5-9.
		private float bigDigits(Canvas canvas, String s, float right, float baseline, float size, int color) {
			float adv = size * 0.86f;
			float x = right - adv * s.length() - size * 0.14f;
			for (int i = 0; i < s.length(); i++) {
				int dgt = s.charAt(i) - '0';
				if (dgt < 0 || dgt > 9) {
					continue;
				}
				rect.set(x, baseline - size, x + size, baseline);
				glyph(canvas, (dgt % 5) * 26, (dgt / 5) * 26, 26, 25, rect, color);
				x += adv;
			}
			return right - adv * s.length() - size * 0.14f;
		}

		// Small digits: 12x18 cells from x 132, 0-4 then 5-9.
		private void smallDigit(Canvas canvas, int dgt, float cx, float cy, float h, int color) {
			float w = h * 12 / 18f;
			rect.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2);
			glyph(canvas, 132 + (dgt % 5) * 12, (dgt / 5) * 18, 12, 18, rect, color);
		}

		// Gear characters: 14x19 cells, 0-8 from y 52, then 9 D N R from y 72.
		private void gearGlyph(Canvas canvas, char g, RectF box, int color) {
			int cell;
			if (g >= '0' && g <= '8') {
				cell = g - '0';
			} else if (g == '9') {
				cell = 9;
			} else if (g == 'D') {
				cell = 10;
			} else if (g == 'N') {
				cell = 11;
			} else if (g == 'R') {
				cell = 12;
			} else {
				return;
			}
			int sx = cell < 9 ? cell * 14 : (cell - 9) * 14;
			int sy = cell < 9 ? 52 : 72;
			float gh = box.height() * 0.72f;
			float gw = gh * 14 / 19f;
			RectF dst = new RectF(box.centerX() - gw / 2, box.centerY() - gh / 2, box.centerX() + gw / 2, box.centerY() + gh / 2);
			glyph(canvas, sx, sy, 14, 19, dst, color);
		}

		private void drawGaugesGame(Canvas canvas, float l, float t, float r, float b) {
			Context ctx = getContext();
			panel(canvas, l, t, r, b);
			float in = DuoUi.dp(ctx, 16);
			l += in;
			r -= in;
			t += in;
			b -= in;
			float w = r - l, h = b - t;
			float cx = (l + r) / 2;

			// Bands from the top: car picture, name, speed with the gear, tachometer, pedals.
			float barH = Math.max(DuoUi.dp(ctx, 16), Math.min(DuoUi.dp(ctx, 24), h * 0.045f));
			float barGap = barH * 0.6f;
			float pedalsTop = b - barH * 2 - barGap;
			float nameSize = Math.min(DuoUi.dp(ctx, 20), h * 0.045f);
			float digitH = Math.min(h * 0.24f, w / 4.6f);
			float tachH = Math.max(DuoUi.dp(ctx, 12), Math.min(DuoUi.dp(ctx, 34), h * 0.07f));
			float numH = Math.max(DuoUi.dp(ctx, 10), tachH * 0.6f);
			// What's left above the speed goes to the picture.
			float below = nameSize * 1.6f + digitH * 1.15f + numH * 1.6f + tachH + barH * 1.2f;
			float imgH = Math.max(0, Math.min(Math.min(h * 0.36f, w * 0.7f * 168 / 224f), pedalsTop - t - below));
			// Spare height spreads out between the bands.
			float spare = Math.max(0, pedalsTop - t - below - imgH) / 4;
			float y = t + spare;
			if (carImage != null && imgH > 8) {
				float imgW = imgH * carImage.getWidth() / carImage.getHeight();
				rect.set(cx - imgW / 2, y, cx + imgW / 2, y + imgH);
				canvas.drawBitmap(carImage, null, rect, bmpPaint);
			}
			y += imgH;
			if (!carLabel.isEmpty()) {
				paint.setStyle(Paint.Style.FILL);
				paint.setTextAlign(Paint.Align.CENTER);
				paint.setColor(DuoUi.COLOR_TEXT);
				paint.setTextSize(nameSize);
				canvas.drawText(carLabel, cx, y + nameSize * 1.15f, paint);
			}
			y += nameSize * 1.6f + spare;

			// Speed (the HUD's italic digits), km/h, and the gear in its box on the left.
			float baseline = y + digitH * 1.05f;
			String sp = hasTelemetry ? String.valueOf(Math.round(Math.max(0, speed))) : "0";
			float digitsRight = cx + digitH * 1.35f;
			bigDigits(canvas, sp, digitsRight, baseline, digitH, 0xFFFFFFFF);
			if (hud.kmh != null) {
				float kmhH = digitH * 0.3f;
				float kmhW = kmhH * hud.kmh.getWidth() / hud.kmh.getHeight();
				rect.set(digitsRight + digitH * 0.08f, baseline - kmhH, digitsRight + digitH * 0.08f + kmhW, baseline);
				canvas.drawBitmap(hud.kmh, null, rect, bmpPaint);
			}
			float box = digitH * 0.8f;
			float boxL = Math.max(l, cx - digitH * 1.45f - box);
			RectF gearBox = new RectF(boxL, baseline - digitH * 0.5f - box / 2, boxL + box, baseline - digitH * 0.5f + box / 2);
			paint.setStyle(Paint.Style.FILL);
			paint.setColor(0xFF0B0F14);
			canvas.drawRoundRect(gearBox, box * 0.12f, box * 0.12f, paint);
			paint.setStyle(Paint.Style.STROKE);
			paint.setStrokeWidth(DuoUi.dp(ctx, 2));
			boolean shift = hasTelemetry && redStart > 0 && rpm >= redStart;
			paint.setColor(shift ? RED : 0xFFD9E2EC);
			canvas.drawRoundRect(gearBox, box * 0.12f, box * 0.12f, paint);
			if (hasTelemetry && gear != 0) {
				gearGlyph(canvas, gear, gearBox, shift ? RED : 0xFFFFFFFF);
			}
			y = baseline + digitH * 0.1f + spare;

			// Tachometer: a bar of segments like the HUD's, red past the car's red line, numbers above.
			float numY = y + numH * 0.8f;
			float tachTop = numY + numH * 0.8f;
			int segs = (int)(shownRpmMax / 250);
			float gap = Math.max(2, w * 0.004f);
			float segW = (w - gap * (segs - 1)) / segs;
			for (int i = 0; i < segs; i++) {
				float segRpm = (i + 0.5f) * 250;
				boolean lit = hasTelemetry && segRpm <= rpm;
				boolean redZone = redStart > 0 && segRpm >= redStart;
				int c = lit ? (redZone ? RED : 0xFFFFFFFF) : (redZone ? 0x66E04848 : SEG_OFF);
				// Taller towards the top end, like the HUD.
				float sh = tachH * (0.55f + 0.45f * i / Math.max(1, segs - 1));
				float sx = l + i * (segW + gap);
				rect.set(sx, tachTop + tachH - sh, sx + segW, tachTop + tachH);
				paint.setStyle(Paint.Style.FILL);
				paint.setColor(c);
				canvas.drawRect(rect, paint);
			}
			int thousands = (int)(shownRpmMax / 1000);
			for (int k = 0; k <= thousands && k < 10; k++) {
				float nx = l + (w - segW) * k / thousands + segW / 2;
				nx = Math.max(l + numH * 0.35f, Math.min(r - numH * 0.35f, nx));
				boolean redNum = redStart > 0 && k * 1000 >= redStart;
				smallDigit(canvas, k, nx, numY, numH, redNum ? RED : 0xFFB8C4D0);
			}

			drawPedals(canvas, cx, w / 2, b, barH, barGap, GT_BLUE);
		}
		private void drawMapGame(Canvas canvas, float l, float t, float r, float b) {
			Context ctx = getContext();
			panel(canvas, l, t, r, b);
			float corner = DuoUi.dp(ctx, 10);
			float in = DuoUi.dp(ctx, 1.5f);

			// Banner: the track's photo, darkened, with its logo.
			float bannerH = 0;
			if (trackPhoto != null || trackLogo != null) {
				bannerH = Math.min((b - t) * 0.26f, (r - l) * 0.42f);
				canvas.save();
				path.reset();
				rect.set(l + in, t + in, r - in, t + bannerH);
				path.addRoundRect(rect, new float[] {corner, corner, corner, corner, 0, 0, 0, 0}, Path.Direction.CW);
				canvas.clipPath(path);
				if (trackPhoto != null) {
					// Cover the banner, cropped to its aspect.
					float bw = rect.width(), bh = rect.height();
					float pw = trackPhoto.getWidth(), ph = trackPhoto.getHeight();
					float s = Math.max(bw / pw, bh / ph);
					float cw = bw / s, ch = bh / s;
					src.set((int)((pw - cw) / 2), (int)((ph - ch) / 2), (int)((pw + cw) / 2), (int)((ph + ch) / 2));
					canvas.drawBitmap(trackPhoto, src, rect, bmpPaint);
				}
				paint.setStyle(Paint.Style.FILL);
				paint.setShader(new LinearGradient(0, t, 0, t + bannerH, 0x22000000, 0xCC141A22, Shader.TileMode.CLAMP));
				canvas.drawRect(rect, paint);
				paint.setShader(null);
				canvas.restore();
				if (trackLogo != null) {
					float lh = bannerH * 0.5f;
					float lw = lh * trackLogo.getWidth() / trackLogo.getHeight();
					if (lw > (r - l) * 0.8f) {
						lw = (r - l) * 0.8f;
						lh = lw * trackLogo.getHeight() / trackLogo.getWidth();
					}
					rect.set((l + r) / 2 - lw / 2, t + (bannerH - lh) / 2, (l + r) / 2 + lw / 2, t + (bannerH + lh) / 2);
					canvas.drawBitmap(trackLogo, null, rect, bmpPaint);
				}
			}

			float mt = t + bannerH;
			if (!fitMap(l, mt, r, b, DuoUi.dp(ctx, 22))) {
				paint.setStyle(Paint.Style.FILL);
				paint.setTextAlign(Paint.Align.CENTER);
				paint.setTextSize(DuoUi.dp(ctx, 14));
				paint.setColor(DuoUi.COLOR_TEXT_DIM);
				drawWrapped(canvas, ctx.getString(R.string.duo_gt_map_hint), (l + r) / 2, (mt + b) / 2, r - l - DuoUi.dp(ctx, 32));
				return;
			}
			paint.setStrokeJoin(Paint.Join.ROUND);
			paint.setStrokeCap(Paint.Cap.ROUND);
			if (edges != null) {
				// The road as the game draws it on its map: a white band with a dark edge, the
				// start line across it.
				float roadW = 0;
				for (int i = 0; i < edgeCount; i++) {
					int k = i * 4;
					roadW += (float)Math.hypot(edges[k] - edges[k + 2], edges[k + 1] - edges[k + 3]);
				}
				roadW = Math.max(DuoUi.dp(ctx, 4), Math.min(DuoUi.dp(ctx, 12), roadW / edgeCount * mapScale * 0.6f));
				centerline(path);
				paint.setStyle(Paint.Style.STROKE);
				paint.setStrokeWidth(roadW + DuoUi.dp(ctx, 5));
				paint.setColor(0xFF06090D);
				canvas.drawPath(path, paint);
				paint.setStrokeWidth(roadW);
				paint.setColor(0xFFF2F5F8);
				canvas.drawPath(path, paint);
				float ax = mapOx + edges[0] * mapScale, az = mapOz + edges[1] * mapScale;
				float bx = mapOx + edges[2] * mapScale, bz = mapOz + edges[3] * mapScale;
				float mx = (ax + bx) / 2, mz = (az + bz) / 2;
				float ux = bx - ax, uz = bz - az;
				float ul = (float)Math.hypot(ux, uz);
				if (ul > 0.01f) {
					ux /= ul;
					uz /= ul;
					float half = roadW * 0.9f;
					paint.setStrokeCap(Paint.Cap.BUTT);
					paint.setStrokeWidth(DuoUi.dp(ctx, 3));
					paint.setColor(RED);
					canvas.drawLine(mx - ux * half, mz - uz * half, mx + ux * half, mz + uz * half, paint);
					paint.setStrokeCap(Paint.Cap.ROUND);
				}
			} else {
				trail(path);
				paint.setStyle(Paint.Style.STROKE);
				paint.setStrokeWidth(DuoUi.dp(ctx, 9));
				paint.setColor(0xFF06090D);
				canvas.drawPath(path, paint);
				paint.setStrokeWidth(DuoUi.dp(ctx, 5));
				paint.setColor(0xFFF2F5F8);
				canvas.drawPath(path, paint);
			}
			drawRivals(canvas, DuoUi.dp(ctx, 5), GT_BLUE, 0xFFFFFFFF);
			drawArrow(canvas, DuoUi.dp(ctx, 12), RED, 0xFFFFFFFF);
		}
	}
}
