package org.ppsspp.ppsspp.duo.games;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.Log;
import android.util.SparseArray;

import org.ppsspp.ppsspp.duo.DuoModContext;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

// WipEout Pure's HUD lettering, read from the disc (FE.wad) at runtime and cached.
//
// WAD: u32 version, u32 file count, then 16-byte entries: u32 name hash, u32 offset, u32 size, u32
// size again. The HUD's numbers and labels are the 'FNT' file 0xD7B3DDA5 (27 px).
// FNT: '\x01FNT', u32 glyph count, u32 offset of the char codes, u32 offset of the glyph record
// offsets, u32 line height, u32, u32 texture offset. Glyph record: u16 char, u8 width, u8 height,
// u16 x0, x1, y0, y1, s8 advance, s8 x offset. Texture: u16 width, u16 height, 4 bpp, a 16-entry
// palette at +0x40 and swizzled pixels (16 bytes x 8 rows blocks) at +0x80. The game draws it white
// with alpha = the palette entry's grey level (checked against the CLUT the GE uses in a race).
final class WipeoutFont {
	private static final String TAG = "PPSSPPDuo";
	private static final String WAD = "disc0:/PSP_GAME/USRDIR/FE.wad";
	static final int HASH_LARGE = 0xD7B3DDA5;

	private static final class Glyph {
		final Rect src;
		final int advance, xOffset;

		Glyph(Rect src, int advance, int xOffset) {
			this.src = src;
			this.advance = advance;
			this.xOffset = xOffset;
		}
	}

	private final Bitmap atlas;
	private final SparseArray<Glyph> glyphs = new SparseArray<>();
	final int lineHeight;
	private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
	private final RectF dst = new RectF();
	private int tint;

	private WipeoutFont(byte[] fnt) {
		ByteBuffer b = ByteBuffer.wrap(fnt).order(ByteOrder.LITTLE_ENDIAN);
		if (b.getInt(0) != 0x544E4601) {
			throw new IllegalArgumentException("not a FNT");
		}
		int count = b.getInt(4);
		int offsets = b.getInt(12);
		lineHeight = b.getInt(16);
		int tex = b.getInt(24);
		for (int i = 0; i < count; i++) {
			int o = b.getInt(offsets + i * 4);
			if (o < 0 || o + 18 > fnt.length) {
				continue;
			}
			int c = b.getShort(o) & 0xFFFF;
			int x0 = b.getShort(o + 4) & 0xFFFF, x1 = b.getShort(o + 6) & 0xFFFF;
			int y0 = b.getShort(o + 8) & 0xFFFF, y1 = b.getShort(o + 10) & 0xFFFF;
			glyphs.put(c, new Glyph(new Rect(x0, y0, x1, y1), b.get(o + 12), b.get(o + 13)));
		}
		int w = b.getShort(tex) & 0xFFFF, h = b.getShort(tex + 2) & 0xFFFF;
		if (w <= 0 || h <= 0 || w > 1024 || h > 1024 || tex + 0x80 + w * h / 2 > fnt.length) {
			throw new IllegalArgumentException("bad FNT texture");
		}
		int[] alpha = new int[16];
		for (int i = 0; i < 16; i++) {
			alpha[i] = fnt[tex + 0x40 + i * 4] & 0xFF;
		}
		int row = w / 2;
		byte[] pix = new byte[row * h];
		int src = tex + 0x80;
		for (int by = 0; by < h; by += 8) {
			for (int bx = 0; bx < row; bx += 16) {
				for (int y = 0; y < 8; y++) {
					if (by + y < h) {
						System.arraycopy(fnt, src, pix, (by + y) * row + bx, 16);
					}
					src += 16;
				}
			}
		}
		int[] argb = new int[w * h];
		for (int i = 0; i < w * h; i++) {
			int v = pix[i / 2] & 0xFF;
			argb[i] = alpha[(i & 1) == 0 ? v & 0xF : v >> 4] << 24 | 0xFFFFFF;
		}
		atlas = Bitmap.createBitmap(argb, w, h, Bitmap.Config.ARGB_8888);
	}

	// Width of text drawn with the given cap height.
	float measure(String text, float height) {
		float scale = height / lineHeight;
		float w = 0;
		for (int i = 0; i < text.length(); i++) {
			Glyph g = glyph(text.charAt(i));
			if (g != null) {
				w += g.advance * scale;
			}
		}
		return w;
	}

	// Draws text with its top at y.
	float draw(Canvas canvas, String text, float x, float y, float height, int color) {
		if (color != tint) {
			tint = color;
			paint.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
		}
		float scale = height / lineHeight;
		for (int i = 0; i < text.length(); i++) {
			Glyph g = glyph(text.charAt(i));
			if (g == null) {
				continue;
			}
			if (g.src.width() > 0) {
				// The record's y0 is where the glyph starts in its line.
				float gx = x + g.xOffset * scale;
				dst.set(gx, y, gx + g.src.width() * scale, y + g.src.height() * scale);
				canvas.drawBitmap(atlas, g.src, dst, paint);
			}
			x += g.advance * scale;
		}
		return x;
	}

	private Glyph glyph(char c) {
		Glyph g = glyphs.get(c);
		if (g == null) {
			g = glyphs.get(Character.toUpperCase(c));
		}
		return g;
	}

	// Loads the fonts with these hashes, from the cache or else from the disc.
	static void load(DuoModContext host, File dir, int[] hashes, FontsCallback cb) {
		WipeoutFont[] out = new WipeoutFont[hashes.length];
		byte[][] cached = new byte[hashes.length][];
		boolean all = true;
		for (int i = 0; i < hashes.length; i++) {
			cached[i] = readFile(new File(dir, String.format("%08x.fnt", hashes[i])));
			all &= cached[i] != null;
		}
		if (all) {
			for (int i = 0; i < hashes.length; i++) {
				out[i] = parse(cached[i]);
			}
			cb.onFonts(out);
			return;
		}
		// The WAD's table first (157 files on UCUS98612; 4 KB covers 250).
		boolean queued = host.readGameFile(WAD, 0, 4096, table -> {
			if (table == null || table.length < 8) {
				cb.onFonts(out);
				return;
			}
			ByteBuffer t = ByteBuffer.wrap(table).order(ByteOrder.LITTLE_ENDIAN);
			int files = t.getInt(4);
			int[][] entries = new int[hashes.length][];
			for (int i = 0; i < files && 8 + i * 16 + 16 <= table.length; i++) {
				int hash = t.getInt(8 + i * 16);
				for (int k = 0; k < hashes.length; k++) {
					if (hash == hashes[k]) {
						entries[k] = new int[] {t.getInt(8 + i * 16 + 4), t.getInt(8 + i * 16 + 8)};
					}
				}
			}
			readNext(host, dir, hashes, entries, out, 0, cb);
		});
		if (!queued) {
			cb.onFonts(out);
		}
	}

	interface FontsCallback {
		// One font per hash, null where it couldn't be read. Called on the UI thread.
		void onFonts(WipeoutFont[] fonts);
	}

	private static void readNext(DuoModContext host, File dir, int[] hashes, int[][] entries, WipeoutFont[] out, int k, FontsCallback cb) {
		if (k >= hashes.length) {
			cb.onFonts(out);
			return;
		}
		if (entries[k] == null || entries[k][1] <= 0 || entries[k][1] > 1024 * 1024) {
			readNext(host, dir, hashes, entries, out, k + 1, cb);
			return;
		}
		boolean queued = host.readGameFile(WAD, entries[k][0], entries[k][1], data -> {
			out[k] = parse(data);
			if (out[k] != null) {
				//noinspection ResultOfMethodCallIgnored
				dir.mkdirs();
				try (FileOutputStream f = new FileOutputStream(new File(dir, String.format("%08x.fnt", hashes[k])))) {
					f.write(data);
				} catch (Exception e) {
					Log.w(TAG, "WipEout: can't cache the font", e);
				}
			}
			readNext(host, dir, hashes, entries, out, k + 1, cb);
		});
		if (!queued) {
			cb.onFonts(out);
		}
	}

	private static WipeoutFont parse(byte[] data) {
		if (data == null) {
			return null;
		}
		try {
			return new WipeoutFont(data);
		} catch (RuntimeException e) {
			Log.w(TAG, "WipEout: bad font: " + e);
			return null;
		}
	}

	private static byte[] readFile(File f) {
		if (!f.isFile() || f.length() <= 0 || f.length() > 1024 * 1024) {
			return null;
		}
		byte[] data = new byte[(int)f.length()];
		try (FileInputStream in = new FileInputStream(f)) {
			int n = 0;
			while (n < data.length) {
				int r = in.read(data, n, data.length - n);
				if (r < 0) {
					return null;
				}
				n += r;
			}
			return data;
		} catch (Exception e) {
			return null;
		}
	}
}
