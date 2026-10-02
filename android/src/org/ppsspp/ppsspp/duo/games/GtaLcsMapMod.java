package org.ppsspp.ppsspp.duo.games;

import android.content.Context;
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

// GTA: Liberty City Stories: full city map with the player's position, heading and trail.
//
// The map is built once from the game's radar textures (see GtaRadar) and cached as a PNG.
// The player's matrix is found through a pointer in the game's static data; that address is
// specific to each release, so the arrow only appears for the versions listed in PLAYER_POINTERS.
public final class GtaLcsMapMod extends DuoMod {
	public static final String ID = "gta_lcs_map";
	private static final String TAG = "PPSSPPDuo";

	private static final String IMG_PATH = "disc0:/PSP_GAME/USRDIR/MODELS/GTA3PSPHR.IMG";
	private static final int READ_SIZE = 1024 * 1024;
	// Textures are about 10 KB, so consecutive reads overlap by this much to catch the ones on a seam.
	private static final int READ_OVERLAP = 32 * 1024;

	// gameId + discVersion -> {address of the player matrix pointer, IMG offset of the radar textures}.
	// Found on ULES00151 3.00: the pointer leads to an RwMatrix (right, forward, up, position; 16 bytes
	// each) that follows the player on foot and in vehicles.
	private static final String[] KNOWN_KEYS = {"ULES00151 3.00"};
	private static final int[][] KNOWN_VALUES = {{0x08B35EF8, 0x02080000}};

	// Liberty City Stories: Europe (tested), USA. The map works with any release (it's scanned from
	// the disc); the player arrow only where the pointer is known.
	private static final String[] GAME_IDS = {"ULES00151", "ULUS10041"};

	private MapView map;
	private TextView info;
	private DuoModContext host;
	private final Handler handler = new Handler(Looper.getMainLooper());

	// Map extraction.
	private GtaRadar radar;
	private int scanOffset;
	private boolean fullScan;
	private boolean scanning;

	// Player tracking.
	private int pointerAddress;
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

	@Override
	public int getPriority(DuoStatus status) {
		for (String id : GAME_IDS) {
			if (id.equals(status.gameId)) {
				return 100;
			}
		}
		return -1;
	}

	@Override
	public long getStatusIntervalMs() {
		return 66;
	}

	private static int[] known(DuoStatus s) {
		String key = s.gameId + " " + s.discVersion;
		for (int i = 0; i < KNOWN_KEYS.length; i++) {
			if (KNOWN_KEYS[i].equals(key)) {
				return KNOWN_VALUES[i];
			}
		}
		return null;
	}

	@Override
	public View onCreateView(DuoModContext host) {
		this.host = host;
		Context ctx = host.getContext();
		FrameLayout root = new FrameLayout(ctx);
		map = new MapView(ctx);
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
		FrameLayout.LayoutParams btnLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END | Gravity.CENTER_VERTICAL);
		btnLp.setMargins(pad, pad, pad, pad);
		root.addView(buttons, btnLp);

		radar = null;
		scanning = false;
		pointerAddress = 0;
		matrixAddress = 0;
		loadMap(host.getStatus());
		return root;
	}

	private View mapButton(Context ctx, String label, View.OnClickListener l) {
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
		// Start with the known location of the radar textures, if any, then fall back to the whole file.
		radar = new GtaRadar();
		int[] k = known(s);
		fullScan = k == null;
		scanOffset = k != null ? k[1] : 0;
		scanning = true;
		requestNextChunk();
	}

	private void requestNextChunk() {
		final int offset = scanOffset;
		boolean ok = host.readGameFile(IMG_PATH, offset, READ_SIZE, data -> onChunk(offset, data));
		if (!ok) {
			// Queue full or no game; try again shortly.
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
			// The known location didn't have them all (different data?), scan everything.
			Log.w(TAG, "GTA map: only " + found + " radar tiles at the known offset, scanning the whole IMG");
			fullScan = true;
			scanOffset = 0;
		} else {
			scanOffset = offset + READ_SIZE - READ_OVERLAP;
		}
		map.setProgress(fullScan ? scanOffset : -1);
		requestNextChunk();
	}

	private void finishScan() {
		scanning = false;
		Bitmap bmp = radar.toBitmap();
		Log.i(TAG, "GTA map: built from " + radar.tileCount() + " radar tiles");
		radar = null;
		map.setMap(bmp);
		File cache = cacheFile(host.getStatus());
		try (FileOutputStream out = new FileOutputStream(cache)) {
			bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
		} catch (Exception e) {
			Log.w(TAG, "GTA map: couldn't cache the map: " + e);
		}
	}

	@Override
	public void onStatus(DuoStatus s) {
		if (map.mapBitmap == null && !scanning && s.hasGame()) {
			loadMap(s);
		}
		trackPlayer(s);
	}

	private void trackPlayer(DuoStatus s) {
		int[] k = known(s);
		if (k == null || !s.hasGame()) {
			info.setText(s.hasGame() ? host.getContext().getString(R.string.duo_gta_no_player) : "");
			map.setPlayer(false, 0, 0, 0, 0);
			return;
		}
		if (pointerAddress != k[0]) {
			pointerAddress = k[0];
			matrixAddress = 0;
			host.setMemoryWatches(new int[] {pointerAddress}, new int[] {4});
			return;
		}
		byte[] ptr = host.readMemoryWatch(0);
		if (ptr == null) {
			return;
		}
		int target = ByteBuffer.wrap(ptr).order(ByteOrder.LITTLE_ENDIAN).getInt();
		boolean valid = target >= 0x08800000 && target < 0x0A000000 - 0x40;
		if (!valid) {
			map.setPlayer(false, 0, 0, 0, 0);
			return;
		}
		if (target != matrixAddress) {
			matrixAddress = target;
			host.setMemoryWatches(new int[] {pointerAddress, matrixAddress}, new int[] {4, 0x40});
			return;
		}
		byte[] m = host.readMemoryWatch(1);
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
		info.setText(String.format(java.util.Locale.US, "X %.0f  Y %.0f  Z %.0f", x, y, z));
	}

	@Override
	public void onDestroyView() {
		handler.removeCallbacksAndMessages(null);
		radar = null;
		scanning = false;
		map = null;
		info = null;
		host = null;
	}

	private static final class MapView extends View {
		private static final int TRAIL_POINTS = 900;
		private static final float TRAIL_MIN_STEP = 3.0f;  // map pixels

		Bitmap mapBitmap;
		private final Matrix matrix = new Matrix();
		private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
		private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
		private final Path arrow = new Path();
		private final float[] trail = new float[TRAIL_POINTS * 2];
		private int trailStart, trailCount;

		// View state: map pixel at the view center, and view pixels per map pixel.
		private float centerX = GtaRadar.SIZE / 2f, centerY = GtaRadar.SIZE / 2f;
		private float scale = 0;
		private boolean follow = true;
		private int progress = -1;

		private boolean hasPlayer;
		private float playerX, playerY, dirX, dirY;

		private final ScaleGestureDetector scaleDetector;
		private final GestureDetector gestureDetector;

		MapView(Context context) {
			super(context);
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
					centerX += dx / scale;
					centerY += dy / scale;
					clampCenter();
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

		void zoomBy(float factor) {
			scale = Math.max(minScale(), Math.min(scale * factor, minScale() * 16));
			clampCenter();
			invalidate();
		}

		private float minScale() {
			return Math.min(getWidth(), getHeight()) / (float)GtaRadar.SIZE;
		}

		private void clampCenter() {
			centerX = Math.max(0, Math.min(GtaRadar.SIZE, centerX));
			centerY = Math.max(0, Math.min(GtaRadar.SIZE, centerY));
		}

		void setPlayer(boolean has, float x, float y, float fx, float fy) {
			hasPlayer = has;
			if (!has) {
				invalidate();
				return;
			}
			float mx = GtaRadar.worldToMapX(x);
			float my = GtaRadar.worldToMapY(y);
			float len = (float)Math.hypot(fx, fy);
			if (len > 0.01f) {
				dirX = fx / len;
				dirY = -fy / len;  // world north is up
			}
			addTrail(mx, my);
			playerX = mx;
			playerY = my;
			if (follow) {
				centerX = mx;
				centerY = my;
			}
			invalidate();
		}

		private void addTrail(float mx, float my) {
			if (trailCount > 0) {
				int last = (trailStart + trailCount - 1) % TRAIL_POINTS;
				float lx = trail[last * 2], ly = trail[last * 2 + 1];
				float d = (float)Math.hypot(mx - lx, my - ly);
				if (d < TRAIL_MIN_STEP) {
					return;
				}
				if (d > 60) {
					// Teleport (respawn, loading a save): start over.
					trailCount = 0;
				}
			}
			int slot = (trailStart + trailCount) % TRAIL_POINTS;
			trail[slot * 2] = mx;
			trail[slot * 2 + 1] = my;
			if (trailCount < TRAIL_POINTS) {
				trailCount++;
			} else {
				trailStart = (trailStart + 1) % TRAIL_POINTS;
			}
		}

		@Override
		protected void onSizeChanged(int w, int h, int oldw, int oldh) {
			if (scale == 0) {
				// Start zoomed in on the player's surroundings.
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

		@Override
		protected void onDraw(Canvas canvas) {
			float w = getWidth(), h = getHeight();
			if (mapBitmap == null) {
				paint.setColor(DuoUi.COLOR_TEXT);
				paint.setTextAlign(Paint.Align.CENTER);
				paint.setTextSize(DuoUi.dp(getContext(), 20));
				String text = getContext().getString(R.string.duo_gta_building_map);
				if (progress >= 0) {
					text += String.format(java.util.Locale.US, " (%d MB)", progress >> 20);
				}
				canvas.drawText(text, w / 2, h / 2, paint);
				return;
			}
			matrix.reset();
			matrix.postTranslate(-centerX, -centerY);
			matrix.postScale(scale, scale);
			matrix.postTranslate(w / 2, h / 2);
			canvas.drawBitmap(mapBitmap, matrix, bitmapPaint);

			// Trail.
			paint.setStyle(Paint.Style.STROKE);
			paint.setStrokeCap(Paint.Cap.ROUND);
			paint.setStrokeWidth(DuoUi.dp(getContext(), 3));
			paint.setColor(0xB0FFC940);
			float px = 0, py = 0;
			for (int i = 0; i < trailCount; i++) {
				int slot = (trailStart + i) % TRAIL_POINTS;
				float sx = (trail[slot * 2] - centerX) * scale + w / 2;
				float sy = (trail[slot * 2 + 1] - centerY) * scale + h / 2;
				if (i > 0) {
					canvas.drawLine(px, py, sx, sy, paint);
				}
				px = sx;
				py = sy;
			}

			if (hasPlayer) {
				float sx = (playerX - centerX) * scale + w / 2;
				float sy = (playerY - centerY) * scale + h / 2;
				float r = DuoUi.dp(getContext(), 14);
				// Arrow pointing along (dirX, dirY).
				float nx = -dirY, ny = dirX;
				arrow.reset();
				arrow.moveTo(sx + dirX * r * 1.3f, sy + dirY * r * 1.3f);
				arrow.lineTo(sx - dirX * r * 0.8f + nx * r * 0.9f, sy - dirY * r * 0.8f + ny * r * 0.9f);
				arrow.lineTo(sx - dirX * r * 0.35f, sy - dirY * r * 0.35f);
				arrow.lineTo(sx - dirX * r * 0.8f - nx * r * 0.9f, sy - dirY * r * 0.8f - ny * r * 0.9f);
				arrow.close();
				paint.setStyle(Paint.Style.FILL);
				paint.setColor(0xFFFFFFFF);
				canvas.drawPath(arrow, paint);
				paint.setStyle(Paint.Style.STROKE);
				paint.setStrokeWidth(DuoUi.dp(getContext(), 2.5f));
				paint.setColor(0xFFE0402A);
				canvas.drawPath(arrow, paint);
			}

			// North marker.
			paint.setStyle(Paint.Style.FILL);
			paint.setTypeface(Typeface.DEFAULT_BOLD);
			paint.setTextAlign(Paint.Align.CENTER);
			paint.setTextSize(DuoUi.dp(getContext(), 16));
			paint.setColor(DuoUi.COLOR_TEXT);
			canvas.drawText("N", w / 2, DuoUi.dp(getContext(), 24), paint);
		}
	}
}
