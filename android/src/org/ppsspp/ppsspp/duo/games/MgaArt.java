package org.ppsspp.ppsspp.duo.games;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.ppsspp.ppsspp.duo.DuoModContext;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.Inflater;

// Metal Gear Ac!d card illustrations, read from the disc at runtime (nothing from the game is
// shipped) and cached.
//
// They're in stage/init/_zar (u32 unpacked size, then zlib), an archive of named entries: name\0,
// padded to 4, then u32 size and 12 more bytes, then the data. Its resident.qar holds one TXP per
// card type, cd_illust{ch,it,sp,ac,wp}.txp. A QAR is its files back to back (each aligned to 128)
// followed by u32 count, count x (u32 hash, u32 size) and the names.
// TXP: +0x04 texture count, +0x0C sprite count, +0x10 sprite table; textures at +0x1C, 16 bytes
// each: width, log2 buffer width << 4, height, log2 buffer height << 4, u16 format (4 = CLUT4,
// 5 = CLUT8), u16 flags (2 = zlib), pixel offset, palette offset. The pixels are swizzled; the
// RGBA8888 palette starts 4 bytes before its stated offset (that's where the game loads it from).
// Sprites are 0x30 bytes: +0x00 name hash, +0x10 0x20 + 16 * texture index.
// A card's illustration is the sprite named cd_<type><number>_alp_ovl (number as %03d), with the
// type and number from its record in the card table (see MetalGearAcidMod). Names are hashed with
// Konami's 24-bit StrCode.
final class MgaArt {
	private static final String TAG = "PPSSPPDuo";
	private static final String INIT_ZAR = "disc0:/PSP_GAME/USRDIR/stage/init/_zar";
	static final String[] TYPES = {"ch", "it", "sp", "ac", "wp"};

	interface Callback {
		void onReady(MgaArt art);   // null if the art isn't available
	}

	private final byte[][] txp = new byte[TYPES.length][];
	private final Map<Integer, Integer>[] sprites;
	private final Map<Integer, Bitmap> bitmaps = new HashMap<>();

	@SuppressWarnings("unchecked")
	private MgaArt(byte[][] files) {
		sprites = new Map[TYPES.length];
		for (int t = 0; t < TYPES.length; t++) {
			txp[t] = files[t];
			sprites[t] = new HashMap<>();
			int n = u32(files[t], 0x0C), table = u32(files[t], 0x10);
			for (int i = 0; i < n && table + i * 0x30 + 0x14 <= files[t].length; i++) {
				int s = table + i * 0x30;
				sprites[t].put(u32(files[t], s), (u32(files[t], s + 0x10) - 0x20) / 16);
			}
		}
	}

	static void load(DuoModContext host, File dir, Callback cb) {
		byte[][] files = readCache(dir);
		if (files != null) {
			cb.onReady(new MgaArt(files));
			return;
		}
		Handler main = new Handler(Looper.getMainLooper());
		PataponArt.readRange(host, INIT_ZAR, 0, 8 * 1024 * 1024, data -> {
			if (data == null) {
				Log.w(TAG, "MGA: can't read " + INIT_ZAR);
				cb.onReady(null);
				return;
			}
			// Unpacking takes a moment: off the UI thread.
			new Thread(() -> {
				MgaArt art = null;
				try {
					byte[][] f = extract(data);
					writeCache(dir, f);
					art = new MgaArt(f);
					Log.i(TAG, "MGA: card art extracted from the disc");
				} catch (Exception e) {
					Log.w(TAG, "MGA: no card art: " + e);
				}
				MgaArt result = art;
				main.post(() -> cb.onReady(result));
			}, "MgaArt").start();
		});
	}

	// The card's illustration, or null.
	Bitmap illustration(int type, int number) {
		if (type < 0 || type >= TYPES.length) {
			return null;
		}
		int key = type << 16 | (number & 0xFFFF);
		if (bitmaps.containsKey(key)) {
			return bitmaps.get(key);
		}
		Bitmap bmp = null;
		Integer tex = sprites[type].get(strCode(String.format(Locale.US, "cd_%s%03d_alp_ovl", TYPES[type], Math.max(1, number))));
		if (tex != null) {
			try {
				bmp = decode(txp[type], tex);
			} catch (Exception e) {
				Log.w(TAG, "MGA: can't decode " + TYPES[type] + number + ": " + e);
			}
		}
		bitmaps.put(key, bmp);
		return bmp;
	}

	static int strCode(String s) {
		int h = 0;
		for (int i = 0; i < s.length(); i++) {
			h = ((h >>> 19) | (h << 5)) & 0xFFFFFF;
			h = (h + s.charAt(i)) & 0xFFFFFF;
		}
		return h;
	}

	private static Bitmap decode(byte[] d, int index) throws Exception {
		if (index < 0 || index >= u32(d, 0x04)) {
			return null;
		}
		int e = 0x1C + index * 16;
		int w = d[e] & 0xFF, h = d[e + 2] & 0xFF;
		int format = (d[e + 4] & 0xFF) | (d[e + 5] & 0xFF) << 8;
		int flags = (d[e + 6] & 0xFF) | (d[e + 7] & 0xFF) << 8;
		int pix = u32(d, e + 8), clut = u32(d, e + 12) - 4;
		int bpp = format == 4 ? 4 : format == 5 ? 8 : 0;
		if (bpp == 0 || w == 0 || h == 0) {
			return null;
		}
		int rowBytes = w * bpp / 8;
		byte[] raw;
		if ((flags & 2) != 0) {
			raw = inflate(d, pix, clut + 4 - pix, rowBytes * ((h + 7) / 8 * 8));
		} else {
			raw = new byte[rowBytes * h];
			System.arraycopy(d, pix, raw, 0, Math.min(raw.length, d.length - pix));
		}
		byte[] px = unswizzle(raw, rowBytes, h);
		int colors = 1 << bpp;
		int[] pal = new int[colors];
		for (int i = 0; i < colors && clut + i * 4 + 4 <= d.length; i++) {
			int o = clut + i * 4;
			pal[i] = (d[o + 3] & 0xFF) << 24 | (d[o] & 0xFF) << 16 | (d[o + 1] & 0xFF) << 8 | (d[o + 2] & 0xFF);
		}
		int[] argb = new int[w * h];
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				int v;
				if (bpp == 8) {
					v = px[y * rowBytes + x] & 0xFF;
				} else {
					int b = px[y * rowBytes + x / 2] & 0xFF;
					v = (x & 1) != 0 ? b >> 4 : b & 15;
				}
				argb[y * w + x] = pal[v];
			}
		}
		return Bitmap.createBitmap(argb, w, h, Bitmap.Config.ARGB_8888);
	}

	// 16-byte x 8-row blocks, left to right, then top to bottom.
	private static byte[] unswizzle(byte[] raw, int rowBytes, int h) {
		byte[] out = new byte[rowBytes * h];
		int src = 0;
		int blocksX = Math.max(1, rowBytes / 16);
		for (int by = 0; by < (h + 7) / 8; by++) {
			for (int bx = 0; bx < blocksX; bx++) {
				for (int y = 0; y < 8; y++) {
					int row = by * 8 + y;
					if (row < h && src + 16 <= raw.length) {
						System.arraycopy(raw, src, out, row * rowBytes + bx * 16, 16);
					}
					src += 16;
				}
			}
		}
		return out;
	}

	private static byte[][] extract(byte[] zar) throws Exception {
		if (zar.length < 8) {
			throw new IllegalStateException("short _zar");
		}
		byte[] u = inflate(zar, 4, zar.length - 4, u32(zar, 0));
		byte[] qar = entry(u, "resident.qar");
		byte[][] files = new byte[TYPES.length][];
		Map<String, int[]> list = qarFiles(qar);
		for (int t = 0; t < TYPES.length; t++) {
			int[] f = list.get("cd_illust" + TYPES[t] + ".txp");
			if (f == null) {
				throw new IllegalStateException("cd_illust" + TYPES[t] + ".txp not found");
			}
			files[t] = new byte[f[1]];
			System.arraycopy(qar, f[0], files[t], 0, f[1]);
		}
		return files;
	}

	private static byte[] entry(byte[] u, String name) {
		byte[] key = (name + "\0").getBytes(StandardCharsets.US_ASCII);
		int i = indexOf(u, key);
		if (i < 0) {
			throw new IllegalStateException(name + " not found");
		}
		int p = (i + key.length + 3) & ~3;
		int size = u32(u, p);
		if (size <= 0 || p + 16 + size > u.length) {
			throw new IllegalStateException("bad entry " + name);
		}
		byte[] out = new byte[size];
		System.arraycopy(u, p + 16, out, 0, size);
		return out;
	}

	// name -> {offset, size}. The table is found from the end: a count whose sizes, aligned to 128,
	// add up to where the table starts.
	private static Map<String, int[]> qarFiles(byte[] q) {
		for (int p = (q.length - 8) & ~3; p > 0; p -= 4) {
			int n = u32(q, p);
			if (n <= 0 || n > 4096 || p + 4 + n * 8 >= q.length) {
				continue;
			}
			long total = 0;
			for (int i = 0; i < n; i++) {
				total += (u32(q, p + 8 + i * 8) + 127L) & ~127L;
			}
			if (Math.abs(total - p) >= 0x400) {
				continue;
			}
			Map<String, int[]> out = new HashMap<>();
			int name = p + 4 + n * 8, off = 0;
			for (int i = 0; i < n && name < q.length; i++) {
				int end = name;
				while (end < q.length && q[end] != 0) {
					end++;
				}
				int size = u32(q, p + 8 + i * 8);
				out.put(new String(q, name, end - name, StandardCharsets.US_ASCII), new int[] {off, Math.min(size, q.length - off)});
				off = (off + size + 127) & ~127;
				name = end + 1;
			}
			return out;
		}
		throw new IllegalStateException("no QAR table");
	}

	private static byte[] inflate(byte[] d, int off, int len, int expected) throws Exception {
		Inflater inf = new Inflater();
		try {
			inf.setInput(d, off, len);
			ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, expected));
			byte[] buf = new byte[65536];
			while (!inf.finished()) {
				int n = inf.inflate(buf);
				if (n == 0 && (inf.needsInput() || inf.needsDictionary())) {
					break;
				}
				out.write(buf, 0, n);
			}
			return out.toByteArray();
		} finally {
			inf.end();
		}
	}

	private static byte[][] readCache(File dir) {
		byte[][] files = new byte[TYPES.length][];
		for (int t = 0; t < TYPES.length; t++) {
			File f = new File(dir, "cd_illust" + TYPES[t] + ".txp");
			if (!f.isFile()) {
				return null;
			}
			try (FileInputStream in = new FileInputStream(f)) {
				byte[] b = new byte[(int)f.length()];
				int n = 0;
				while (n < b.length) {
					int r = in.read(b, n, b.length - n);
					if (r < 0) {
						return null;
					}
					n += r;
				}
				files[t] = b;
			} catch (Exception e) {
				return null;
			}
		}
		return files;
	}

	private static void writeCache(File dir, byte[][] files) {
		if (!dir.isDirectory() && !dir.mkdirs()) {
			return;
		}
		for (int t = 0; t < TYPES.length; t++) {
			File f = new File(dir, "cd_illust" + TYPES[t] + ".txp");
			File tmp = new File(dir, f.getName() + ".tmp");
			try (FileOutputStream out = new FileOutputStream(tmp)) {
				out.write(files[t]);
			} catch (Exception e) {
				Log.w(TAG, "MGA: can't cache the card art: " + e);
				return;
			}
			if (!tmp.renameTo(f)) {
				tmp.delete();
			}
		}
	}

	private static int indexOf(byte[] data, byte[] key) {
		outer:
		for (int i = 0; i + key.length <= data.length; i++) {
			for (int k = 0; k < key.length; k++) {
				if (data[i + k] != key[k]) {
					continue outer;
				}
			}
			return i;
		}
		return -1;
	}

	private static int u32(byte[] d, int o) {
		if (o < 0 || o + 4 > d.length) {
			return 0;
		}
		return (d[o] & 0xFF) | (d[o + 1] & 0xFF) << 8 | (d[o + 2] & 0xFF) << 16 | (d[o + 3] & 0xFF) << 24;
	}
}
