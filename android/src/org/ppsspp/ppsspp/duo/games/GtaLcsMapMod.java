package org.ppsspp.ppsspp.duo.games;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
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
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;

// GTA: Liberty City Stories: city map with the player's position and heading, the trail driven so
// far and the radar blips (mission targets and destinations).
//
// - The map is built once from the game's radar textures (see GtaRadar) and cached as a PNG.
// - The game data is located with signature scans rather than fixed addresses, so other releases
//   work without knowing their layout up front; the results are cached per game version. Found on
//   ULES00151 v3.00:
//   - player: pointer at 0x08B35EF8 to an RwMatrix (forward at +0x10, position at +0x30). It sits
//     at +0x38 in a block of camera/player settings matched by PLAYER_PATTERN.
//   - radar blips: 75 entries of 0x50 bytes at 0x08E4AAA0. The u32 at +0x34 is 0x0000FF00 in every
//     entry, used and unused, which is what BLIP_PATTERN matches.
public final class GtaLcsMapMod extends DuoMod {
	public static final String ID = "gta_lcs_map";
	private static final String TAG = "PPSSPPDuo";

	private static final String IMG_PATH = "disc0:/PSP_GAME/USRDIR/MODELS/GTA3PSPHR.IMG";
	private static final int READ_SIZE = 1024 * 1024;
	// Textures are about 10 KB, so consecutive reads overlap by this much to catch the ones on a seam.
	private static final int READ_OVERLAP = 32 * 1024;
	// Where to start looking for the radar textures (ULES00151 v3.00); the whole file is scanned if
	// they aren't all there.
	private static final int RADAR_HINT_OFFSET = 0x02080000;

	// The game's main module (loaded at the start of user memory) holds both structures.
	private static final int SCAN_START = 0x08804000;
	private static final int SCAN_END = 0x09800000;

	private static final int[] PLAYER_PATTERN = {
		0x0, 0x0, 0x1, 0x3fa66666, 0x0, 0x40000000, 0x3e4ccccd, 0x3f4ccccd, 0x3e8, 0x0, 0x3f800000, 0x190, 0x1};
	private static final int PLAYER_POINTER_OFFSET = 0x38;

	private static final int BLIP_COUNT = 75;
	private static final int BLIP_SIZE = 0x50;
	private static final int BLIP_MAGIC_OFFSET = 0x34;
	private static final int BLIP_MAGIC = 0x0000FF00;

	// Liberty City Stories: Europe (tested), USA, Japan. Unknown IDs are matched by title.
	private static final String[] GAME_IDS = {"ULES00151", "ULUS10041", "ULJM05255"};

	private static final String PREFS = "duo_gta_lcs";

	// Watch slots.
	private static final int W_POINTER = 0;
	private static final int W_BLIPS = 1;
	private static final int W_MATRIX = 2;

	private DuoModContext host;
	private MapView map;
	private TextView info;
	private TextView rotateButton;
	private final Handler handler = new Handler(Looper.getMainLooper());

	// Kept across tab switches (the mod object outlives its view).
	private final Trail trail = new Trail();
	private String trailGame = "";
	private boolean rotateMap;

	// Map extraction.
	private GtaRadar radar;
	private int scanOffset;
	private boolean fullScan;
	private boolean scanning;

	// Located game data, 0 = not (yet) found.
	private String layoutKey = "";
	private int pointerAddress;
	private int blipAddress;
	private boolean searching;
	private long lastSearch;
	private int matrixAddress;

	@Override
	public String getId() {
		return ID;
	}

	@Override
	public String getTitle(Context context) {
		return context.getString(R.string.duo_mod_gta_map);
	}

	@Override
	public String getDescription(Context context) {
		return context.getString(R.string.duo_mod_gta_map_desc);
	}

	static boolean isLcs(DuoStatus s) {
		for (String id : GAME_IDS) {
			if (id.equals(s.gameId)) {
				return true;
			}
		}
		String t = s.title.toLowerCase(Locale.ROOT);
		return t.contains("liberty city stories") || s.title.contains("リバティー・シティー") || s.title.contains("リバティーシティー");
	}

	@Override
	public int getPriority(DuoStatus status) {
		return isLcs(status) ? 100 : -1;
	}

	@Override
	public long getStatusIntervalMs() {
		return 66;
	}

	@Override
	public View onCreateView(DuoModContext host) {
		this.host = host;
		Context ctx = host.getContext();
		FrameLayout root = new FrameLayout(ctx);
		map = new MapView(ctx, trail);
		map.setRotateMode(rotateMap);
		root.addView(map, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

		info = DuoUi.text(ctx, "", 14, DuoUi.COLOR_TEXT);
		info.setBackground(DuoUi.rounded(ctx, 0xC0141A22, 10));
		int pad = DuoUi.dp(ctx, 8);
		info.setPadding(pad, pad / 2, pad, pad / 2);
		FrameLayout.LayoutParams infoLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
		infoLp.setMargins(pad, pad, pad, pad);
		root.addView(info, infoLp);

		LinearLayout buttons = new LinearLayout(ctx);
		buttons.setOrientation(LinearLayout.VERTICAL);
		buttons.addView(mapButton(ctx, "+", v -> map.zoomBy(1.5f)));
		buttons.addView(mapButton(ctx, "\u2212", v -> map.zoomBy(1 / 1.5f)));
		buttons.addView(mapButton(ctx, "\u25CE", v -> map.setFollow(true)));
		rotateButton = mapButton(ctx, "\u2B06", v -> {
			rotateMap = !rotateMap;
			v.setActivated(rotateMap);
			map.setRotateMode(rotateMap);
		});
		rotateButton.setActivated(rotateMap);
		rotateButton.setContentDescription(ctx.getString(R.string.duo_gta_rotate));
		buttons.addView(rotateButton);
		FrameLayout.LayoutParams btnLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END | Gravity.CENTER_VERTICAL);
		btnLp.setMargins(pad, pad, pad, pad);
		root.addView(buttons, btnLp);

		radar = null;
		scanning = false;
		searching = false;
		matrixAddress = 0;
		layoutKey = "";
		loadMap(host.getStatus());
		return root;
	}

	private TextView mapButton(Context ctx, String label, View.OnClickListener l) {
		TextView b = DuoUi.button(ctx, label);
		b.setTextSize(24);
		b.setOnClickListener(v -> {
			host.haptic(v);
			l.onClick(v);
		});
		int s = DuoUi.dp(ctx, 56);
		LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(s, s);
		lp.setMargins(0, DuoUi.dp(ctx, 4), 0, DuoUi.dp(ctx, 4));
		b.setLayoutParams(lp);
		return b;
	}

	// Map.

	private File cacheFile(DuoStatus s) {
		File dir = new File(host.getContext().getFilesDir(), "duo/maps");
		//noinspection ResultOfMethodCallIgnored
		dir.mkdirs();
		return new File(dir, s.gameId + "_" + s.discVersion + ".png");
	}

	private void loadMap(DuoStatus s) {
		if (!s.hasGame()) {
			return;
		}
		File cache = cacheFile(s);
		if (cache.exists()) {
			Bitmap bmp = BitmapFactory.decodeFile(cache.getPath());
			if (bmp != null) {
				map.setMap(bmp);
				return;
			}
		}
		radar = new GtaRadar();
		fullScan = false;
		scanOffset = RADAR_HINT_OFFSET;
		scanning = true;
		requestNextChunk();
	}

	private void requestNextChunk() {
		if (host == null) {
			return;
		}
		final int offset = scanOffset;
		if (!host.readGameFile(IMG_PATH, offset, READ_SIZE, data -> onChunk(offset, data))) {
			handler.postDelayed(this::requestNextChunk, 500);
		}
	}

	private void onChunk(int offset, byte[] data) {
		if (radar == null) {
			return;
		}
		if (data != null) {
			radar.scan(data);
		}
		int found = radar.tileCount();
		boolean endOfFile = data == null || data.length < READ_SIZE;
		// 63 of the 64 tiles exist (one corner is open sea).
		if (found >= 63 || (fullScan && endOfFile)) {
			finishScan();
			return;
		}
		if (!fullScan) {
			Log.w(TAG, "GTA map: " + found + " radar tiles at the usual offset, scanning the whole IMG");
			fullScan = true;
			scanOffset = 0;
		} else {
			scanOffset = offset + READ_SIZE - READ_OVERLAP;
		}
		map.setProgress(scanOffset);
		requestNextChunk();
	}

	private void finishScan() {
		scanning = false;
		Bitmap bmp = radar.toBitmap();
		Log.i(TAG, "GTA map: built from " + radar.tileCount() + " radar tiles");
		radar = null;
		map.setMap(bmp);
		try (FileOutputStream out = new FileOutputStream(cacheFile(host.getStatus()))) {
			bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
		} catch (Exception e) {
			Log.w(TAG, "GTA map: couldn't cache the map: " + e);
		}
	}

	// Locating the game data.

	private SharedPreferences prefs() {
		return host.getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
	}

	private void locate(DuoStatus s) {
		String key = s.gameId + "_" + s.discVersion;
		if (!key.equals(layoutKey)) {
			layoutKey = key;
			pointerAddress = prefs().getInt(key + ".player", 0);
			blipAddress = prefs().getInt(key + ".blips", 0);
			matrixAddress = 0;
			applyWatches();
		}
		if (pointerAddress != 0 && blipAddress != 0) {
			return;
		}
		// Not found yet, or the game hasn't set things up yet (title screen): retry now and then.
		long now = android.os.SystemClock.uptimeMillis();
		if (searching || now - lastSearch < 3000 || !s.isInGame()) {
			return;
		}
		searching = true;
		lastSearch = now;
		final String searchKey = key;
		if (pointerAddress == 0) {
			int[] offsets = new int[PLAYER_PATTERN.length];
			for (int i = 0; i < offsets.length; i++) {
				offsets[i] = i * 4;
			}
			boolean queued = host.findMemory(SCAN_START, SCAN_END, offsets, PLAYER_PATTERN, addr -> {
				if (addr != 0 && searchKey.equals(layoutKey)) {
					pointerAddress = addr + PLAYER_POINTER_OFFSET;
					prefs().edit().putInt(searchKey + ".player", pointerAddress).apply();
					Log.i(TAG, "GTA map: player pointer at " + Integer.toHexString(pointerAddress));
					applyWatches();
				}
				if (blipAddress != 0) {
					searching = false;
				}
			});
			if (!queued) {
				searching = false;
				return;
			}
		}
		if (blipAddress == 0) {
			int[] offsets = new int[BLIP_COUNT];
			int[] values = new int[BLIP_COUNT];
			for (int i = 0; i < BLIP_COUNT; i++) {
				offsets[i] = i * BLIP_SIZE + BLIP_MAGIC_OFFSET;
				values[i] = BLIP_MAGIC;
			}
			boolean queued = host.findMemory(SCAN_START, SCAN_END, offsets, values, addr -> {
				if (addr != 0 && searchKey.equals(layoutKey)) {
					blipAddress = addr;
					prefs().edit().putInt(searchKey + ".blips", blipAddress).apply();
					Log.i(TAG, "GTA map: radar blips at " + Integer.toHexString(blipAddress));
					applyWatches();
				}
				searching = false;
			});
			if (!queued) {
				searching = false;
			}
		}
	}

	private void applyWatches() {
		int[] addresses = {pointerAddress != 0 ? pointerAddress : SCAN_START, blipAddress != 0 ? blipAddress : SCAN_START, matrixAddress != 0 ? matrixAddress : SCAN_START};
		int[] sizes = {4, BLIP_COUNT * BLIP_SIZE, 0x40};
		host.setMemoryWatches(addresses, sizes);
	}

	// Per-frame updates.

	@Override
	public void onStatus(DuoStatus s) {
		if (!s.hasGame()) {
			info.setText("");
			map.setPlayer(false, 0, 0, 0, 0);
			return;
		}
		if (!s.path.equals(trailGame)) {
			trailGame = s.path;
			trail.clear();
		}
		if (map.mapBitmap == null && !scanning) {
			loadMap(s);
		}
		locate(s);
		updatePlayer();
		updateBlips();
	}

	private void updatePlayer() {
		if (pointerAddress == 0) {
			info.setText(searching ? "" : host.getContext().getString(R.string.duo_gta_no_player));
			map.setPlayer(false, 0, 0, 0, 0);
			return;
		}
		byte[] ptr = host.readMemoryWatch(W_POINTER);
		if (ptr == null) {
			return;
		}
		int target = ByteBuffer.wrap(ptr).order(ByteOrder.LITTLE_ENDIAN).getInt();
		if (target < 0x08800000 || target >= 0x0A000000 - 0x40) {
			map.setPlayer(false, 0, 0, 0, 0);
			return;
		}
		if (target != matrixAddress) {
			// The new matrix shows up in the watch on the next frame.
			matrixAddress = target;
			applyWatches();
			return;
		}
		byte[] m = host.readMemoryWatch(W_MATRIX);
		if (m == null) {
			return;
		}
		ByteBuffer b = ByteBuffer.wrap(m).order(ByteOrder.LITTLE_ENDIAN);
		float fx = b.getFloat(0x10), fy = b.getFloat(0x14);
		float x = b.getFloat(0x30), y = b.getFloat(0x34), z = b.getFloat(0x38);
		if (Float.isNaN(x) || Float.isNaN(y) || Math.abs(x) > 5000 || Math.abs(y) > 5000) {
			map.setPlayer(false, 0, 0, 0, 0);
			return;
		}
		map.setPlayer(true, x, y, fx, fy);
		info.setText(String.format(Locale.US, "X %.0f  Y %.0f  Z %.0f", x, y, z));
	}

	private void updateBlips() {
		if (blipAddress == 0) {
			map.setBlips(null, 0);
			return;
		}
		byte[] data = host.readMemoryWatch(W_BLIPS);
		if (data == null) {
			map.setBlips(null, 0);
			return;
		}
		ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
		float[] out = map.blipBuffer(BLIP_COUNT);
		int n = 0;
		for (int i = 0; i < BLIP_COUNT; i++) {
			int e = i * BLIP_SIZE;
			int type = b.getInt(e + 4);
			boolean inUse = data[e + 0x33] != 0;
			int display = b.getShort(e + 0x3E) & 0xFFFF;
			if (type == 0 || !inUse || display == 0) {
				continue;
			}
			float x = b.getFloat(e + 0x0C), y = b.getFloat(e + 0x10);
			if (Float.isNaN(x) || Float.isNaN(y) || Math.abs(x) > 5000 || Math.abs(y) > 5000 || (x == 0 && y == 0)) {
				continue;
			}
			out[n * 4] = GtaRadar.worldToMapX(x);
			out[n * 4 + 1] = GtaRadar.worldToMapY(y);
			out[n * 4 + 2] = blipColor(b.getInt(e));
			out[n * 4 + 3] = type;
			n++;
		}
		map.setBlips(out, n);
	}

	// The game's blip color indices, or RGBA for custom colors.
	private static int blipColor(int c) {
		switch (c) {
		case 0: return 0xFFE04848;  // red
		case 1: return 0xFF5CD15C;  // green
		case 2: return 0xFF5E8BF2;  // blue
		case 3: return 0xFFF2F2F2;  // white
		case 4: return 0xFFF5D547;  // yellow
		case 5: return 0xFFD45CD8;  // purple
		case 6: return 0xFF4CD9E6;  // cyan
		default:
			return 0xFF000000 | ((c >>> 24) << 16) | (((c >>> 16) & 0xFF) << 8) | ((c >>> 8) & 0xFF);
		}
	}

	@Override
	public void onDestroyView() {
		handler.removeCallbacksAndMessages(null);
		radar = null;
		scanning = false;
		searching = false;
		map = null;
		info = null;
		rotateButton = null;
		host = null;
	}

	// The player's path, in map pixels. Survives tab switches.
	static final class Trail {
		static final int POINTS = 900;
		static final float MIN_STEP = 3.0f;
		final float[] xy = new float[POINTS * 2];
		int start, count;

		void clear() {
			start = 0;
			count = 0;
		}

		// Returns true if a point was added.
		boolean add(float mx, float my) {
			if (count > 0) {
				int last = (start + count - 1) % POINTS;
				float d = (float)Math.hypot(mx - xy[last * 2], my - xy[last * 2 + 1]);
				if (d < MIN_STEP) {
					return false;
				}
				if (d > 60) {
					// Teleport (respawn, loading a save): start over.
					clear();
				}
			}
			int slot = (start + count) % POINTS;
			xy[slot * 2] = mx;
			xy[slot * 2 + 1] = my;
			if (count < POINTS) {
				count++;
			} else {
				start = (start + 1) % POINTS;
			}
			return true;
		}
	}

	private static final class MapView extends View {
		Bitmap mapBitmap;
		private final Trail trail;
		private final Matrix matrix = new Matrix();
		private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
		private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
		private final Path path = new Path();
		private final float[] pt = new float[2];

		// View state: map pixel at the view center, view pixels per map pixel, rotation in degrees.
		private float centerX = GtaRadar.SIZE / 2f, centerY = GtaRadar.SIZE / 2f;
		private float scale = 0;
		private float rotation = 0;
		private boolean rotateMode;
		private boolean follow = true;
		private int progress = -1;

		private boolean hasPlayer;
		private float playerX, playerY, dirX = 0, dirY = -1;

		private float[] blips = new float[0];
		private int blipCount;

		private final ScaleGestureDetector scaleDetector;
		private final GestureDetector gestureDetector;

		MapView(Context context, Trail trail) {
			super(context);
			this.trail = trail;
			setBackgroundColor(GtaRadar.SEA_COLOR);
			scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
				@Override
				public boolean onScale(ScaleGestureDetector d) {
					zoomBy(d.getScaleFactor());
					return true;
				}
			});
			gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
				@Override
				public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
					follow = false;
					// Screen deltas back into map space.
					double r = Math.toRadians(-rotation);
					float mdx = (float)(dx * Math.cos(r) - dy * Math.sin(r));
					float mdy = (float)(dx * Math.sin(r) + dy * Math.cos(r));
					centerX = Math.max(0, Math.min(GtaRadar.SIZE, centerX + mdx / scale));
					centerY = Math.max(0, Math.min(GtaRadar.SIZE, centerY + mdy / scale));
					invalidate();
					return true;
				}

				@Override
				public boolean onDoubleTap(MotionEvent e) {
					setFollow(true);
					return true;
				}
			});
		}

		void setMap(Bitmap bmp) {
			mapBitmap = bmp;
			progress = -1;
			invalidate();
		}

		void setProgress(int offset) {
			progress = offset;
			invalidate();
		}

		void setFollow(boolean f) {
			follow = f;
			if (f && hasPlayer) {
				centerX = playerX;
				centerY = playerY;
			}
			invalidate();
		}

		// Heading up: the map turns so the player always points to the top of the screen.
		void setRotateMode(boolean on) {
			rotateMode = on;
			if (on) {
				setFollow(true);
				rotation = targetRotation();
			} else {
				rotation = 0;
			}
			invalidate();
		}

		private float targetRotation() {
			// Angle of the heading from screen-up, clockwise, in degrees; the map turns the other way.
			return (float)-Math.toDegrees(Math.atan2(dirX, -dirY));
		}

		void zoomBy(float factor) {
			scale = Math.max(minScale(), Math.min(scale * factor, minScale() * 16));
			invalidate();
		}

		private float minScale() {
			return Math.min(getWidth(), getHeight()) / (float)GtaRadar.SIZE;
		}

		void setPlayer(boolean has, float x, float y, float fx, float fy) {
			if (!has) {
				if (hasPlayer) {
					hasPlayer = false;
					invalidate();
				}
				return;
			}
			float mx = GtaRadar.worldToMapX(x);
			float my = GtaRadar.worldToMapY(y);
			float len = (float)Math.hypot(fx, fy);
			float ndx = dirX, ndy = dirY;
			if (len > 0.01f) {
				ndx = fx / len;
				ndy = -fy / len;  // world north is up
			}
			boolean changed = !hasPlayer || Math.abs(mx - playerX) > 0.05f || Math.abs(my - playerY) > 0.05f
				|| Math.abs(ndx - dirX) > 0.01f || Math.abs(ndy - dirY) > 0.01f;
			hasPlayer = true;
			dirX = ndx;
			dirY = ndy;
			trail.add(mx, my);
			playerX = mx;
			playerY = my;
			if (follow) {
				centerX = mx;
				centerY = my;
			}
			if (rotateMode) {
				// Ease towards the heading so the map doesn't jitter.
				float target = targetRotation();
				float diff = ((target - rotation) % 360 + 540) % 360 - 180;
				if (Math.abs(diff) > 0.2f) {
					rotation += diff * 0.35f;
					changed = true;
				}
			}
			// Only redraw when something moved, to save power while standing still.
			if (changed) {
				invalidate();
			}
		}

		float[] blipBuffer(int max) {
			if (blips.length < max * 4) {
				blips = new float[max * 4];
			}
			return blips;
		}

		private float blipChecksum;

		void setBlips(float[] data, int count) {
			int n = data == null ? 0 : count;
			float sum = 0;
			for (int i = 0; i < n * 4; i++) {
				sum = sum * 31 + blips[i];
			}
			if (n != blipCount || sum != blipChecksum) {
				blipCount = n;
				blipChecksum = sum;
				invalidate();
			}
		}

		@Override
		protected void onSizeChanged(int w, int h, int oldw, int oldh) {
			if (scale == 0) {
				scale = minScale() * 4;
			}
			scale = Math.max(scale, minScale());
		}

		@Override
		public boolean onTouchEvent(MotionEvent ev) {
			scaleDetector.onTouchEvent(ev);
			gestureDetector.onTouchEvent(ev);
			return true;
		}

		private void toScreen(float mx, float my) {
			pt[0] = mx;
			pt[1] = my;
			matrix.mapPoints(pt);
		}

		@Override
		protected void onDraw(Canvas canvas) {
			float w = getWidth(), h = getHeight();
			Context ctx = getContext();
			if (mapBitmap == null) {
				paint.setStyle(Paint.Style.FILL);
				paint.setColor(DuoUi.COLOR_TEXT);
				paint.setTextAlign(Paint.Align.CENTER);
				paint.setTextSize(DuoUi.dp(ctx, 20));
				String text = ctx.getString(R.string.duo_gta_building_map);
				if (progress >= 0) {
					text += String.format(Locale.US, " (%d MB)", progress >> 20);
				}
				canvas.drawText(text, w / 2, h / 2, paint);
				return;
			}
			matrix.reset();
			matrix.postTranslate(-centerX, -centerY);
			matrix.postScale(scale, scale);
			matrix.postRotate(rotation);
			matrix.postTranslate(w / 2, h / 2);
			canvas.drawBitmap(mapBitmap, matrix, bitmapPaint);

			// Trail.
			paint.setStyle(Paint.Style.STROKE);
			paint.setStrokeCap(Paint.Cap.ROUND);
			paint.setStrokeJoin(Paint.Join.ROUND);
			paint.setStrokeWidth(DuoUi.dp(ctx, 3));
			paint.setColor(0xB0FFC940);
			path.reset();
			for (int i = 0; i < trail.count; i++) {
				int slot = (trail.start + i) % Trail.POINTS;
				toScreen(trail.xy[slot * 2], trail.xy[slot * 2 + 1]);
				if (i == 0) {
					path.moveTo(pt[0], pt[1]);
				} else {
					path.lineTo(pt[0], pt[1]);
				}
			}
			canvas.drawPath(path, paint);

			drawBlips(canvas, w, h);

			if (hasPlayer) {
				toScreen(playerX, playerY);
				drawArrow(canvas, pt[0], pt[1]);
			}
			drawNorth(canvas, w, h);
		}

		private void drawBlips(Canvas canvas, float w, float h) {
			float r = DuoUi.dp(getContext(), 9);
			float margin = DuoUi.dp(getContext(), 18);
			for (int i = 0; i < blipCount; i++) {
				toScreen(blips[i * 4], blips[i * 4 + 1]);
				float sx = pt[0], sy = pt[1];
				int color = (int)blips[i * 4 + 2];
				boolean coord = blips[i * 4 + 3] == 4;
				boolean outside = sx < margin || sy < margin || sx > w - margin || sy > h - margin;
				if (outside) {
					// Clamp to the edge and point at it, like the in-game radar.
					float cx = w / 2, cy = h / 2;
					float dx = sx - cx, dy = sy - cy;
					float t = Math.min(Math.abs((w / 2 - margin) / (dx == 0 ? 1e-3f : dx)), Math.abs((h / 2 - margin) / (dy == 0 ? 1e-3f : dy)));
					float ex = cx + dx * t, ey = cy + dy * t;
					float len = (float)Math.hypot(dx, dy);
					float ux = dx / len, uy = dy / len;
					path.reset();
					path.moveTo(ex + ux * r * 1.2f, ey + uy * r * 1.2f);
					path.lineTo(ex - ux * r * 0.6f - uy * r, ey - uy * r * 0.6f + ux * r);
					path.lineTo(ex - ux * r * 0.6f + uy * r, ey - uy * r * 0.6f - ux * r);
					path.close();
					paint.setStyle(Paint.Style.FILL);
					paint.setColor(color);
					canvas.drawPath(path, paint);
					paint.setStyle(Paint.Style.STROKE);
					paint.setStrokeWidth(DuoUi.dp(getContext(), 1.5f));
					paint.setColor(0xFF101010);
					canvas.drawPath(path, paint);
					continue;
				}
				paint.setStyle(Paint.Style.FILL);
				paint.setColor(color);
				canvas.drawCircle(sx, sy, r, paint);
				paint.setStyle(Paint.Style.STROKE);
				paint.setStrokeWidth(DuoUi.dp(getContext(), 2));
				paint.setColor(0xFF101010);
				canvas.drawCircle(sx, sy, r, paint);
				if (coord) {
					// Destination: a ring around the dot.
					paint.setColor(color);
					paint.setStrokeWidth(DuoUi.dp(getContext(), 2.5f));
					canvas.drawCircle(sx, sy, r * 1.9f, paint);
				}
			}
		}

		private void drawArrow(Canvas canvas, float sx, float sy) {
			float r = DuoUi.dp(getContext(), 14);
			// Heading on screen: the map direction turned by the view rotation.
			double rad = Math.toRadians(rotation);
			float ax = (float)(dirX * Math.cos(rad) - dirY * Math.sin(rad));
			float ay = (float)(dirX * Math.sin(rad) + dirY * Math.cos(rad));
			float nx = -ay, ny = ax;
			path.reset();
			path.moveTo(sx + ax * r * 1.3f, sy + ay * r * 1.3f);
			path.lineTo(sx - ax * r * 0.8f + nx * r * 0.9f, sy - ay * r * 0.8f + ny * r * 0.9f);
			path.lineTo(sx - ax * r * 0.35f, sy - ay * r * 0.35f);
			path.lineTo(sx - ax * r * 0.8f - nx * r * 0.9f, sy - ay * r * 0.8f - ny * r * 0.9f);
			path.close();
			paint.setStyle(Paint.Style.FILL);
			paint.setColor(0xFFFFFFFF);
			canvas.drawPath(path, paint);
			paint.setStyle(Paint.Style.STROKE);
			paint.setStrokeWidth(DuoUi.dp(getContext(), 2.5f));
			paint.setColor(0xFFE0402A);
			canvas.drawPath(path, paint);
		}

		// "N" on a circle around the center, in the direction of north.
		private void drawNorth(Canvas canvas, float w, float h) {
			Context ctx = getContext();
			double rad = Math.toRadians(rotation);
			float ux = (float)Math.sin(rad), uy = (float)-Math.cos(rad);
			float radius = Math.min(w, h) / 2 - DuoUi.dp(ctx, 26);
			float x = w / 2 + ux * radius, y = h / 2 + uy * radius;
			paint.setStyle(Paint.Style.FILL);
			paint.setColor(0xC0141A22);
			canvas.drawCircle(x, y, DuoUi.dp(ctx, 15), paint);
			paint.setTypeface(Typeface.DEFAULT_BOLD);
			paint.setTextAlign(Paint.Align.CENTER);
			paint.setTextSize(DuoUi.dp(ctx, 16));
			paint.setColor(rotateMode ? 0xFFE0402A : DuoUi.COLOR_TEXT);
			canvas.drawText("N", x, y - (paint.descent() + paint.ascent()) / 2, paint);
		}
	}
}
