package org.ppsspp.ppsspp.duo.games;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import org.ppsspp.ppsspp.duo.DuoModContext;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

// Gran Turismo PSP artwork for the Telemetry mod, read from the game's GT.VOL at runtime (nothing
// from the game is shipped) and cached as PNGs:
// - the race HUD textures (digits, "km/h", car markers) from projects/gt5m/race/GB/OnboardMeterRoot.gpb,
// - the track's logo and photo (piece_gt5m/course_logo_S, course_image),
// - the car's picture (car/thumbnail_L/<label>.00).
// Tracks are identified by their race map (piece_gt5m/course_map_race/<name>_ps2.bin, which the
// game also keeps in memory while racing): its header (size, centre, scale) is unique per layout.
// Cars by name: the game keeps the display name ("MINI COOPER '02"), the files use labels
// (mini_cooper_02), so the closest label wins.
final class GtArt {
	private static final String TAG = "PPSSPPDuo";
	private static final String HUD_FILE = "projects/gt5m/race/GB/OnboardMeterRoot.gpb";
	private static final String[] HUD_TEXTURES = {"RaceFonts.png", "race_kmh.png", "point_owncar.png", "point_rivalcar.png"};
	private static final String MAP_DIR = "piece_gt5m/course_map_race/";

	static final class Hud {
		Bitmap font, kmh, ownCar, rivalCar;
	}

	interface Callback<T> {
		void on(T value);   // null if unavailable
	}

	private final DuoModContext host;
	private final File dir;
	private GtVolume volume;
	private boolean opening, volumeFailed;
	private final List<Runnable> waiting = new ArrayList<>();
	private final Map<String, String> trackNames = new HashMap<>();
	private boolean trackNamesLoaded;

	GtArt(DuoModContext host, File dir) {
		this.host = host;
		this.dir = dir;
		dir.mkdirs();
	}

	// Runs r once the volume is open (or failed: then volume stays null).
	private void withVolume(Runnable r) {
		if (volume != null || volumeFailed) {
			r.run();
			return;
		}
		waiting.add(r);
		if (opening) {
			return;
		}
		opening = true;
		GtVolume.open(host, v -> {
			opening = false;
			volume = v;
			volumeFailed = v == null;
			List<Runnable> rs = new ArrayList<>(waiting);
			waiting.clear();
			for (Runnable w : rs) {
				w.run();
			}
		});
	}

	void hud(Callback<Hud> cb) {
		Hud hud = new Hud();
		Bitmap[] cached = new Bitmap[HUD_TEXTURES.length];
		boolean all = true;
		for (int i = 0; i < HUD_TEXTURES.length; i++) {
			cached[i] = load("hud_" + HUD_TEXTURES[i]);
			all &= cached[i] != null;
		}
		if (all) {
			cb.on(fill(hud, cached));
			return;
		}
		withVolume(() -> {
			if (volume == null) {
				cb.on(null);
				return;
			}
			volume.read(HUD_FILE, data -> {
				if (data == null) {
					cb.on(null);
					return;
				}
				Bitmap[] found = new Bitmap[HUD_TEXTURES.length];
				for (Txs3.Texture t : Txs3.decodeAll(data)) {
					for (int i = 0; i < HUD_TEXTURES.length; i++) {
						if (found[i] == null && HUD_TEXTURES[i].equals(t.name)) {
							found[i] = t.bitmap;
							save("hud_" + t.name, t.bitmap);
						}
					}
				}
				if (found[0] == null) {
					Log.w(TAG, "GT: HUD textures not found");
					cb.on(null);
					return;
				}
				Log.i(TAG, "GT: HUD textures extracted from the disc");
				cb.on(fill(hud, found));
			});
		});
	}

	private static Hud fill(Hud hud, Bitmap[] b) {
		hud.font = b[0];
		hud.kmh = b[1];
		hud.ownCar = b[2];
		hud.rivalCar = b[3];
		return hud;
	}

	// Track name (e.g. "apricot_ps2") for a race map header, from the cache or by reading every map.
	void trackName(String key, Callback<String> cb) {
		loadTrackNames();
		if (trackNames.containsKey(key)) {
			cb.on(trackNames.get(key));
			return;
		}
		withVolume(() -> {
			if (volume == null) {
				cb.on(null);
				return;
			}
			List<String> maps = new ArrayList<>();
			for (String p : volume.paths()) {
				if (p.startsWith(MAP_DIR) && p.endsWith("_ps2.bin")) {
					maps.add(p);
				}
			}
			readMaps(maps, 0, () -> {
				saveTrackNames();
				cb.on(trackNames.get(key));
			});
		});
	}

	private void readMaps(List<String> maps, int i, Runnable done) {
		if (i >= maps.size()) {
			done.run();
			return;
		}
		String path = maps.get(i);
		volume.read(path, data -> {
			if (data != null && data.length >= 0x24) {
				String name = path.substring(MAP_DIR.length(), path.length() - 4);
				trackNames.put(mapKey(data, 0), name);
			}
			readMaps(maps, i + 1, done);
		});
	}

	// Image size, centre and scale of a race map ('GTCM' header bytes 0x10-0x23).
	static String mapKey(byte[] d, int off) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0x10; i < 0x24; i++) {
			sb.append(String.format(Locale.US, "%02x", d[off + i] & 0xFF));
		}
		return sb.toString();
	}

	private void loadTrackNames() {
		if (trackNamesLoaded) {
			return;
		}
		trackNamesLoaded = true;
		File f = new File(dir, "tracks.txt");
		if (!f.exists()) {
			return;
		}
		try {
			for (String line : readText(f).split("\n")) {
				String[] kv = line.trim().split(" ");
				if (kv.length == 2) {
					trackNames.put(kv[0], kv[1]);
				}
			}
		} catch (Exception e) {
			Log.w(TAG, "GT: " + e);
		}
	}

	private void saveTrackNames() {
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<String, String> e : trackNames.entrySet()) {
			sb.append(e.getKey()).append(' ').append(e.getValue()).append('\n');
		}
		try (FileOutputStream out = new FileOutputStream(new File(dir, "tracks.txt"))) {
			out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
		} catch (Exception e) {
			Log.w(TAG, "GT: " + e);
		}
	}

	// The logo or photo of a track: the map name without "_ps2" and the "r_" of reverse layouts,
	// shortened from the end until a file exists (grandvalley_east -> grandvalley).
	void trackImage(String mapName, boolean photo, Callback<Bitmap> cb) {
		String kind = photo ? "photo_" : "logo_";
		Bitmap cached = load(kind + mapName + ".png");
		if (cached != null || new File(dir, kind + mapName + ".none").exists()) {
			cb.on(cached);
			return;
		}
		withVolume(() -> {
			String folder = photo ? "piece_gt5m/course_image/" : "piece_gt5m/course_logo_S/";
			String path = null;
			if (volume != null) {
				String base = mapName.endsWith("_ps2") ? mapName.substring(0, mapName.length() - 4) : mapName;
				if (base.startsWith("r_")) {
					base = base.substring(2);
				}
				while (path == null && !base.isEmpty()) {
					if (volume.has(folder + base + ".img")) {
						path = folder + base + ".img";
					} else {
						int cut = base.lastIndexOf('_');
						base = cut > 0 ? base.substring(0, cut) : "";
					}
				}
			}
			readImage(path, kind + mapName, !photo, cb);
		});
	}

	// The car's picture, by display name.
	void car(String displayName, Callback<Bitmap> cb) {
		String key = "car_" + displayName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
		Bitmap cached = load(key + ".png");
		if (cached != null || new File(dir, key + ".none").exists()) {
			cb.on(cached);
			return;
		}
		withVolume(() -> {
			String path = null;
			if (volume != null) {
				String label = matchCar(displayName);
				if (label != null) {
					path = "car/thumbnail_L/" + label + ".00";
					if (!volume.has(path)) {
						path = null;
					}
				}
				Log.i(TAG, "GT: car '" + displayName + "' is " + label);
			}
			readImage(path, key, true, cb);
		});
	}

	private void readImage(String path, String key, boolean trim, Callback<Bitmap> cb) {
		if (path == null || volume == null) {
			if (volume != null) {
				mark(key);   // not on this disc: don't look again
			}
			cb.on(null);
			return;
		}
		volume.read(path, data -> {
			Bitmap bmp = null;
			if (data != null) {
				List<Txs3.Texture> ts = Txs3.decodeAll(data);
				if (!ts.isEmpty()) {
					bmp = trim ? trim(ts.get(0).bitmap) : ts.get(0).bitmap;
				}
			}
			if (bmp != null) {
				save(key + ".png", bmp);
			} else if (data != null) {
				mark(key);
			}
			cb.on(bmp);
		});
	}

	private String matchCar(String name) {
		List<String> nameTokens = tokens(name);
		String best = null;
		double bestScore = 0.35;
		for (String p : volume.paths()) {
			if (!p.startsWith("car/thumbnail_L/") || !p.endsWith(".00")) {
				continue;
			}
			String label = p.substring("car/thumbnail_L/".length(), p.length() - 3);
			double s = carScore(nameTokens, label);
			if (s > bestScore) {
				bestScore = s;
				best = label;
			}
		}
		return best;
	}

	private static List<String> tokens(String s) {
		List<String> out = new ArrayList<>();
		for (String t : s.toLowerCase(Locale.ROOT).replace("'", "").split("[^a-z0-9]+")) {
			if (!t.isEmpty()) {
				out.add(t);
			}
		}
		return out;
	}

	private static boolean like(String a, String b) {
		return a.equals(b) || (a.length() >= 2 && b.length() >= 2 && (a.startsWith(b) || b.startsWith(a)));
	}

	// How much of the label is in the name and of the name in the label (by characters); the
	// model year at the end of the label has to be in the name.
	static double carScore(List<String> name, String label) {
		String[] lt = label.split("_");
		String year = lt[lt.length - 1];
		if (!name.contains(year)) {
			return 0;
		}
		int lAll = 0, lHit = 0;
		for (String t : lt) {
			lAll += t.length();
			for (String u : name) {
				if (like(t, u)) {
					lHit += t.length();
					break;
				}
			}
		}
		int nAll = 0, nHit = 0;
		for (String u : name) {
			nAll += u.length();
			for (String t : lt) {
				if (like(t, u)) {
					nHit += u.length();
					break;
				}
			}
		}
		if (lAll == 0 || nAll == 0) {
			return 0;
		}
		return (double)lHit / lAll * Math.sqrt((double)nHit / nAll);
	}

	private static String readText(File f) throws Exception {
		try (FileInputStream in = new FileInputStream(f)) {
			ByteArrayOutputStream acc = new ByteArrayOutputStream();
			byte[] buf = new byte[4096];
			int n;
			while ((n = in.read(buf)) > 0) {
				acc.write(buf, 0, n);
			}
			return new String(acc.toByteArray(), StandardCharsets.UTF_8);
		}
	}

	// Without the transparent border (logos and car pictures sit in larger textures).
	private static Bitmap trim(Bitmap b) {
		int w = b.getWidth(), h = b.getHeight();
		int[] px = new int[w * h];
		b.getPixels(px, 0, w, 0, 0, w, h);
		int x0 = w, y0 = h, x1 = -1, y1 = -1;
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				if ((px[y * w + x] >>> 24) > 16) {
					x0 = Math.min(x0, x);
					x1 = Math.max(x1, x);
					y0 = Math.min(y0, y);
					y1 = Math.max(y1, y);
				}
			}
		}
		if (x1 < x0 || (x0 == 0 && y0 == 0 && x1 == w - 1 && y1 == h - 1)) {
			return b;
		}
		return Bitmap.createBitmap(b, x0, y0, x1 - x0 + 1, y1 - y0 + 1);
	}

	private Bitmap load(String name) {
		File f = new File(dir, name);
		return f.exists() ? BitmapFactory.decodeFile(f.getPath()) : null;
	}

	private void save(String name, Bitmap bmp) {
		try (FileOutputStream out = new FileOutputStream(new File(dir, name))) {
			bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
		} catch (Exception e) {
			Log.w(TAG, "GT: " + e);
		}
	}

	private void mark(String key) {
		try {
			new File(dir, key + ".none").createNewFile();
		} catch (Exception e) {
			// Only a cache.
		}
	}
}
