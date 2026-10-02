package org.ppsspp.ppsspp.duo.games;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import org.ppsspp.ppsspp.duo.DuoModContext;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.Inflater;

// The game's own PATA / PON / CHAKA / DON drum artwork, read from the disc at runtime (nothing is
// shipped with the app) and cached as a PNG.
//
// It's the 256x256 texture int_font00.gxt, nested in the game's archives:
//   DATA_CMN.BND :: loadinggroup/systemdata.bnd (gzip) :: loadinggroupcmn.bnd :: texturelist.bnd
//   :: system.texls :: int_font00.gxt
// The four drums are its quadrants: PATA top left, PON top right, CHAKA bottom left, DON bottom right.
//
// BND: 'BND\0', version, alignment, ?, name tree offset (+0x10), data offset (+0x14), counts
// (+0x20), then 16-byte entries from +0x28: hash, name node offset, data offset, size. Name tree
// nodes: [depth][prev size][size][u32][name\0]; the depth byte is negative (two's complement)
// for files, folder names end with '/'.
// GXT ('XXG.01.0'): pixel data size at +0x44, then a GE command list that gives the format,
// buffer width, pixel and palette offsets (offsets are relative to the file).
final class PataponArt {
	private static final String TAG = "PPSSPPDuo";
	private static final String DATA = "disc0:/PSP_GAME/USRDIR/DATA_CMN.BND";
	private static final String[] CHAIN = {
		"loadinggroup/systemdata.bnd", "loadinggroupcmn.bnd", "texturelist.bnd", "system.texls", "int_font00.gxt"};
	private static final int CHUNK = 1024 * 1024;

	interface Callback {
		// Four 128x128 bitmaps in drum order (PATA, PON, CHAKA, DON), or null if unavailable.
		void onArt(Bitmap[] drums);
	}

	private PataponArt() {}

	static void load(DuoModContext host, File cache, Callback cb) {
		if (cache.exists()) {
			Bitmap bmp = BitmapFactory.decodeFile(cache.getPath());
			if (bmp != null) {
				cb.onArt(split(bmp));
				return;
			}
		}
		// Header first: entries and the name tree come before the data offset.
		readRange(host, DATA, 0, 0x8000, header -> {
			try {
				int dataOff = u32(header, 0x14);
				if (dataOff > header.length) {
					readRange(host, DATA, 0, dataOff, full -> fromTop(host, full, cache, cb));
				} else {
					fromTop(host, header, cache, cb);
				}
			} catch (Exception e) {
				fail(cb, e);
			}
		});
	}

	private static void fromTop(DuoModContext host, byte[] header, File cache, Callback cb) {
		try {
			int[] entry = findEntry(header, CHAIN[0]);
			if (entry == null) {
				fail(cb, new IllegalStateException(CHAIN[0] + " not found"));
				return;
			}
			readRange(host, DATA, entry[0], entry[1], data -> {
				try {
					byte[] cur = gunzipIfNeeded(data);
					for (int i = 1; i < CHAIN.length; i++) {
						int[] e = findEntry(cur, CHAIN[i]);
						if (e == null) {
							throw new IllegalStateException(CHAIN[i] + " not found");
						}
						byte[] child = new byte[e[1]];
						System.arraycopy(cur, e[0], child, 0, e[1]);
						cur = gunzipIfNeeded(child);
					}
					Bitmap bmp = decodeGxt(cur);
					try (FileOutputStream out = new FileOutputStream(cache)) {
						bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
					}
					Log.i(TAG, "Patapon: drum art extracted from the disc");
					cb.onArt(split(bmp));
				} catch (Exception ex) {
					fail(cb, ex);
				}
			});
		} catch (Exception e) {
			fail(cb, e);
		}
	}

	private static void fail(Callback cb, Exception e) {
		Log.w(TAG, "Patapon: no drum art: " + e);
		cb.onArt(null);
	}

	private interface Bytes {
		void on(byte[] data);
	}

	// readGameFile in 1 MB pieces.
	private static void readRange(DuoModContext host, String path, int offset, int size, Bytes done) {
		ByteArrayOutputStream acc = new ByteArrayOutputStream(size);
		readNext(host, path, offset, size, acc, done);
	}

	private static void readNext(DuoModContext host, String path, int offset, int remaining, ByteArrayOutputStream acc, Bytes done) {
		int n = Math.min(CHUNK, remaining);
		boolean ok = host.readGameFile(path, offset, n, data -> {
			if (data == null) {
				done.on(null);
				return;
			}
			acc.write(data, 0, data.length);
			if (data.length < n || remaining - n <= 0) {
				done.on(acc.toByteArray());
			} else {
				readNext(host, path, offset + n, remaining - n, acc, done);
			}
		});
		if (!ok) {
			done.on(null);
		}
	}

	private static int u32(byte[] d, int o) {
		return (d[o] & 0xFF) | (d[o + 1] & 0xFF) << 8 | (d[o + 2] & 0xFF) << 16 | (d[o + 3] & 0xFF) << 24;
	}

	// {data offset, size} of a named file in a BND, or null.
	static int[] findEntry(byte[] d, String name) {
		if (d == null || d.length < 0x28 || d[0] != 'B' || d[1] != 'N' || d[2] != 'D' || d[3] != 0) {
			return null;
		}
		int treeOff = u32(d, 0x10);
		int dataOff = u32(d, 0x14);
		int nents = u32(d, 0x24);
		Map<Integer, String> names = new HashMap<>();
		if (treeOff != 0) {
			int p = treeOff;
			java.util.ArrayList<String> stack = new java.util.ArrayList<>();
			while (p + 3 <= Math.min(dataOff, d.length) && d[p + 2] != 0) {
				int t = d[p] & 0xFF;
				int s = d[p + 2] & 0xFF;
				int end = p + 7;
				while (end < p + s - 1 && d[end] != 0) {
					end++;
				}
				String n = new String(d, p + 7, Math.max(0, end - (p + 7)), StandardCharsets.ISO_8859_1);
				int depth = t < 0x80 ? t : 256 - t;
				while (stack.size() > Math.max(0, depth - 1)) {
					stack.remove(stack.size() - 1);
				}
				StringBuilder full = new StringBuilder();
				for (String part : stack) {
					full.append(part);
				}
				full.append(n);
				names.put(p, full.toString());
				if (n.endsWith("/")) {
					stack.add(n);
				}
				p += s;
			}
		}
		for (int i = 0; i < nents && 0x28 + i * 16 + 16 <= d.length; i++) {
			int base = 0x28 + i * 16;
			int nameOff = u32(d, base + 4), off = u32(d, base + 8), size = u32(d, base + 12);
			// Bounds are the caller's business: for the top archive only the header is in memory.
			if (name.equals(names.get(nameOff)) && off >= 0 && size > 0) {
				return new int[] {off, size};
			}
		}
		return null;
	}

	// The game's gzip: a standard gzip member followed by 8 extra bytes, so parse it by hand.
	static byte[] gunzipIfNeeded(byte[] d) throws Exception {
		if (d.length < 18 || (d[0] & 0xFF) != 0x1F || (d[1] & 0xFF) != 0x8B) {
			return d;
		}
		int flg = d[3] & 0xFF;
		int p = 10;
		if ((flg & 4) != 0) {
			p += 2 + ((d[p] & 0xFF) | (d[p + 1] & 0xFF) << 8);
		}
		if ((flg & 8) != 0) {
			while (d[p] != 0) p++;
			p++;
		}
		if ((flg & 16) != 0) {
			while (d[p] != 0) p++;
			p++;
		}
		if ((flg & 2) != 0) {
			p += 2;
		}
		Inflater inf = new Inflater(true);
		inf.setInput(d, p, d.length - p);
		ByteArrayOutputStream out = new ByteArrayOutputStream(d.length * 6);
		byte[] buf = new byte[65536];
		while (!inf.finished()) {
			int n = inf.inflate(buf);
			if (n == 0 && (inf.needsInput() || inf.needsDictionary())) {
				break;
			}
			out.write(buf, 0, n);
		}
		inf.end();
		return out.toByteArray();
	}

	static Bitmap decodeGxt(byte[] d) {
		if (d.length < 0x80 || !new String(d, 0, 8, StandardCharsets.ISO_8859_1).equals("XXG.01.0")) {
			throw new IllegalStateException("not a GXT");
		}
		ByteBuffer b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
		int pixSize = b.getInt(0x44);
		int words = d.length / 4;
		Map<Integer, Integer> cmd = new HashMap<>();
		for (int i = (0x80 + pixSize) / 4; i < words - 1; i++) {
			int w0 = b.getInt(i * 4), w1 = b.getInt(i * 4 + 4);
			if ((w0 >>> 24) == 0xC2 && (w1 >>> 24) == 0xC3) {
				for (int j = i; j < words && (b.getInt(j * 4) >>> 24) != 0x0B; j++) {
					int w = b.getInt(j * 4);
					if (!cmd.containsKey(w >>> 24)) {
						cmd.put(w >>> 24, w & 0xFFFFFF);
					}
				}
				break;
			}
		}
		if (cmd.isEmpty()) {
			throw new IllegalStateException("no GE commands");
		}
		int swizzle = cmd.get(0xC2) & 1;
		int fmt = cmd.get(0xC3) & 0xF;
		int bufw = cmd.get(0xA8) & 0x7FF;
		int pixOff = cmd.get(0xA0);
		int bpp = fmt == 4 ? 4 : fmt == 5 ? 8 : fmt == 3 ? 32 : 16;
		int h = pixSize * 8 / (bufw * bpp);
		int w = Math.min(bufw, 1 << (cmd.containsKey(0xB8) ? cmd.get(0xB8) & 0xF : 8));
		int palOff = (cmd.containsKey(0xB0) ? cmd.get(0xB0) : 0) | ((cmd.containsKey(0xB1) ? cmd.get(0xB1) : 0) & 0x0F0000) << 8;
		int palN = (cmd.containsKey(0xC4) ? cmd.get(0xC4) : 0) * 8;
		int palFmt = (cmd.containsKey(0xC5) ? cmd.get(0xC5) : 0) & 3;
		if (fmt != 4 && fmt != 5) {
			throw new IllegalStateException("unsupported GXT format " + fmt);
		}
		int rowBytes = bufw * bpp / 8;
		byte[] pix = new byte[pixSize];
		System.arraycopy(d, pixOff, pix, 0, pixSize);
		if (swizzle != 0) {
			pix = unswizzle(pix, rowBytes, h);
		}
		int[] pal = new int[Math.max(16, palN)];
		for (int i = 0; i < palN; i++) {
			int r, g, bl, a;
			if (palFmt == 3) {
				int o = palOff + i * 4;
				r = d[o] & 0xFF; g = d[o + 1] & 0xFF; bl = d[o + 2] & 0xFF; a = d[o + 3] & 0xFF;
			} else {
				int v = (d[palOff + i * 2] & 0xFF) | (d[palOff + i * 2 + 1] & 0xFF) << 8;
				if (palFmt == 0) {
					r = (v & 31) * 255 / 31; g = ((v >> 5) & 63) * 255 / 63; bl = ((v >> 11) & 31) * 255 / 31; a = 255;
				} else if (palFmt == 1) {
					r = (v & 31) * 255 / 31; g = ((v >> 5) & 31) * 255 / 31; bl = ((v >> 10) & 31) * 255 / 31; a = (v >> 15) * 255;
				} else {
					r = (v & 15) * 17; g = ((v >> 4) & 15) * 17; bl = ((v >> 8) & 15) * 17; a = ((v >> 12) & 15) * 17;
				}
			}
			pal[i] = a << 24 | r << 16 | g << 8 | bl;
		}
		int[] argb = new int[w * h];
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				int idx;
				if (fmt == 5) {
					idx = pix[y * rowBytes + x] & 0xFF;
				} else {
					int v = pix[y * rowBytes + x / 2] & 0xFF;
					idx = (x & 1) == 0 ? v & 0xF : v >> 4;
				}
				argb[y * w + x] = pal[idx];
			}
		}
		return Bitmap.createBitmap(argb, w, h, Bitmap.Config.ARGB_8888);
	}

	private static byte[] unswizzle(byte[] raw, int rowBytes, int h) {
		byte[] out = new byte[rowBytes * h];
		int src = 0;
		int blocksX = rowBytes / 16;
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

	// The drums aren't on an exact grid (the lower row starts around y = 110), so each half of the
	// texture is cut where its two drawings are separated by transparent rows, and each drawing is
	// cropped to its pixels and centered in a square.
	private static Bitmap[] split(Bitmap bmp) {
		int w = bmp.getWidth(), h = bmp.getHeight();
		int[] px = new int[w * h];
		bmp.getPixels(px, 0, w, 0, 0, w, h);
		Bitmap[] out = new Bitmap[4];
		for (int half = 0; half < 2; half++) {
			int x0 = half * w / 2, x1 = x0 + w / 2;
			boolean[] rowUsed = new boolean[h];
			for (int y = 0; y < h; y++) {
				for (int x = x0; x < x1 && !rowUsed[y]; x++) {
					rowUsed[y] = (px[y * w + x] >>> 24) > 16;
				}
			}
			// The longest transparent run between the first and last used rows splits the two drums.
			int first = 0, last = h - 1;
			while (first < h && !rowUsed[first]) first++;
			while (last > first && !rowUsed[last]) last--;
			int bestStart = -1, bestLen = 0;
			for (int y = first; y <= last; ) {
				if (rowUsed[y]) {
					y++;
					continue;
				}
				int s = y;
				while (y <= last && !rowUsed[y]) y++;
				if (y - s > bestLen) {
					bestLen = y - s;
					bestStart = s;
				}
			}
			int splitY = bestStart >= 0 ? bestStart + bestLen / 2 : h / 2;
			out[half] = crop(bmp, px, w, x0, 0, x1, splitY);          // PATA, PON
			out[2 + half] = crop(bmp, px, w, x0, splitY, x1, h);      // CHAKA, DON
		}
		return out;
	}

	private static Bitmap crop(Bitmap bmp, int[] px, int stride, int x0, int y0, int x1, int y1) {
		int minX = x1, minY = y1, maxX = x0 - 1, maxY = y0 - 1;
		for (int y = y0; y < y1; y++) {
			for (int x = x0; x < x1; x++) {
				if ((px[y * stride + x] >>> 24) > 16) {
					minX = Math.min(minX, x);
					maxX = Math.max(maxX, x);
					minY = Math.min(minY, y);
					maxY = Math.max(maxY, y);
				}
			}
		}
		if (maxX < minX) {
			return Bitmap.createBitmap(bmp, x0, y0, x1 - x0, y1 - y0);
		}
		int cw = maxX - minX + 1, ch = maxY - minY + 1;
		int size = Math.max(cw, ch);
		Bitmap sq = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
		new android.graphics.Canvas(sq).drawBitmap(Bitmap.createBitmap(bmp, minX, minY, cw, ch), (size - cw) / 2f, (size - ch) / 2f, null);
		return sq;
	}
}
