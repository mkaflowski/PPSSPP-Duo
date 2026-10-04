package org.ppsspp.ppsspp.duo.games;

import android.graphics.Bitmap;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

// Polyphony's TXS3 texture sets, PSP flavour ('3SXT', little endian), as used by Gran Turismo PSP.
// Layout from Nenkai's PDTools TextureSet3 / PGLUGETextureInfo (MIT). Offsets inside are absolute
// for the set's original load address (+0x08), so subtract it.
// - +0x14 texture count (u16), +0x16 buffer count, +0x18 textures, +0x1C buffers, +0x26 palette
//   count, +0x2C palettes.
// - buffer (0x20): data offset, size, ?, GE pixel format, ?, ?, width, height.
// - palette (0x0C): ?, GE palette format, colour count, data offset.
// - texture (0x98): ?, ?, four UV floats, then the GE texture registers as raw commands from +0x18
//   (TMODE at +0x20: bit 0 = swizzled), ..., +0x8A palette index (s16), +0x8E buffer index, +0x94
//   name offset.
// Rows are padded to the power of two above the width (the GE buffer width), DXT ones aren't.
final class Txs3 {
	static final class Texture {
		final String name;
		final Bitmap bitmap;

		Texture(String name, Bitmap bitmap) {
			this.name = name;
			this.bitmap = bitmap;
		}
	}

	private Txs3() {}

	// Every texture set inside d (a bare TXS3 or a file that embeds several).
	static List<Texture> decodeAll(byte[] d) {
		List<Texture> out = new ArrayList<>();
		for (int i = 0; i + 0x40 <= d.length; i += 4) {
			if (d[i] == '3' && d[i + 1] == 'S' && d[i + 2] == 'X' && d[i + 3] == 'T') {
				try {
					decode(d, i, out);
				} catch (RuntimeException e) {
					// Not a texture set after all, or a format we don't decode.
				}
			}
		}
		return out;
	}

	static void decode(byte[] d, int base, List<Texture> out) {
		int reloc = u32(d, base + 0x08);
		int ntex = u16(d, base + 0x14);
		int texOff = u32(d, base + 0x18) - reloc + base;
		int bufOff = u32(d, base + 0x1C) - reloc + base;
		int nclut = u16(d, base + 0x26);
		int clutOff = u32(d, base + 0x2C) - reloc + base;
		for (int i = 0; i < ntex; i++) {
			int t = texOff + i * 0x98;
			int tmode = u32(d, t + 0x20);
			int clutIdx = (short)u16(d, t + 0x8A);
			int bufIdx = u16(d, t + 0x8E);
			int nameOff = u32(d, t + 0x94);
			String name = "";
			if (nameOff != 0) {
				int q = nameOff - reloc + base;
				int e = q;
				while (e < d.length && d[e] != 0) {
					e++;
				}
				name = new String(d, q, e - q, StandardCharsets.US_ASCII);
			}
			int b = bufOff + bufIdx * 0x20;
			int pix = u32(d, b) - reloc + base;
			int size = u32(d, b + 4);
			int fmt = d[b + 9] & 0xFF;
			int w = u16(d, b + 12), h = u16(d, b + 14);
			int[] clut = null;
			if (clutIdx >= 0 && clutIdx < nclut) {
				int c = clutOff + clutIdx * 0x0C;
				clut = palette(d, u32(d, c + 4) - reloc + base, d[c + 1] & 0xFF, u16(d, c + 2));
			}
			Bitmap bmp = pixels(d, pix, size, fmt, w, h, (tmode & 1) != 0, clut);
			if (bmp != null) {
				out.add(new Texture(name, bmp));
			}
		}
	}

	private static int[] palette(byte[] d, int off, int type, int n) {
		int[] c = new int[Math.max(n, 1)];
		for (int i = 0; i < n; i++) {
			c[i] = type == 3 ? abgr8888(u32(d, off + i * 4)) : color16(u16(d, off + i * 2), type);
		}
		return c;
	}

	private static Bitmap pixels(byte[] d, int off, int size, int fmt, int w, int h, boolean swizzled, int[] clut) {
		if (w <= 0 || h <= 0 || w > 2048 || h > 2048) {
			return null;
		}
		if (fmt == 8 || fmt == 9 || fmt == 10) {
			return dxt(d, off, size, fmt, w, h);
		}
		int bpp;
		switch (fmt) {
			case 0: case 1: case 2: bpp = 16; break;
			case 3: bpp = 32; break;
			case 4: bpp = 4; break;
			case 5: bpp = 8; break;
			default: return null;
		}
		if ((fmt == 4 || fmt == 5) && clut == null) {
			return null;
		}
		int pw = 1;
		while (pw < w) {
			pw <<= 1;
		}
		int rowBytes = Math.max(16, pw * bpp / 8);
		int ph = (h + 7) / 8 * 8;
		byte[] raw = new byte[rowBytes * ph];
		System.arraycopy(d, off, raw, 0, Math.min(raw.length, Math.min(size, d.length - off)));
		if (swizzled) {
			raw = unswizzle(raw, rowBytes, ph);
		}
		int[] px = new int[w * h];
		for (int y = 0; y < h; y++) {
			int row = y * rowBytes;
			for (int x = 0; x < w; x++) {
				int c;
				switch (fmt) {
					case 4: {
						int v = raw[row + x / 2] & 0xFF;
						c = clut[((x & 1) != 0 ? v >> 4 : v & 15) % clut.length];
						break;
					}
					case 5:
						c = clut[(raw[row + x] & 0xFF) % clut.length];
						break;
					case 3:
						c = abgr8888(u32(raw, row + x * 4));
						break;
					default:
						c = color16(u16(raw, row + x * 2), fmt);
						break;
				}
				px[y * w + x] = c;
			}
		}
		return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
	}

	// PSP DXT blocks (as in PPSSPP's decoder): u8 lines[4], u16 color1, u16 color2 (RGB565), then
	// DXT3: u16 alphaLines[4]; DXT5: u32 alpha bits low, u16 alpha bits high, u8 alpha1, u8 alpha2.
	private static Bitmap dxt(byte[] d, int off, int size, int fmt, int w, int h) {
		int bh = (h + 3) / 4;
		int blockBytes = fmt == 8 ? 8 : 16;
		int bw = size / blockBytes / bh;
		if (bw <= 0) {
			return null;
		}
		int[] px = new int[w * h];
		int[] pal = new int[4];
		int[] ap = new int[8];
		for (int by = 0; by < bh; by++) {
			for (int bx = 0; bx < bw; bx++) {
				int o = off + (by * bw + bx) * blockBytes;
				if (o + blockBytes > d.length) {
					break;
				}
				int c0 = u16(d, o + 4), c1 = u16(d, o + 6);
				int r0 = (c0 >> 11) * 255 / 31, g0 = (c0 >> 5 & 63) * 255 / 63, b0 = (c0 & 31) * 255 / 31;
				int r1 = (c1 >> 11) * 255 / 31, g1 = (c1 >> 5 & 63) * 255 / 63, b1 = (c1 & 31) * 255 / 31;
				pal[0] = rgb(r0, g0, b0);
				pal[1] = rgb(r1, g1, b1);
				boolean four = fmt != 8 || c0 > c1;
				if (four) {
					pal[2] = rgb((2 * r0 + r1) / 3, (2 * g0 + g1) / 3, (2 * b0 + b1) / 3);
					pal[3] = rgb((r0 + 2 * r1) / 3, (g0 + 2 * g1) / 3, (b0 + 2 * b1) / 3);
				} else {
					pal[2] = rgb((r0 + r1) / 2, (g0 + g1) / 2, (b0 + b1) / 2);
					pal[3] = 0;
				}
				long alphaBits = 0;
				if (fmt == 10) {
					long lo = u32(d, o + 8) & 0xFFFFFFFFL;
					long hi = u16(d, o + 12);
					alphaBits = hi << 32 | lo;
					int a0 = d[o + 14] & 0xFF, a1 = d[o + 15] & 0xFF;
					ap[0] = a0;
					ap[1] = a1;
					if (a0 > a1) {
						for (int k = 0; k < 6; k++) {
							ap[2 + k] = ((6 - k) * a0 + (k + 1) * a1) / 7;
						}
					} else {
						for (int k = 0; k < 4; k++) {
							ap[2 + k] = ((4 - k) * a0 + (k + 1) * a1) / 5;
						}
						ap[6] = 0;
						ap[7] = 255;
					}
				}
				for (int k = 0; k < 16; k++) {
					int xx = k & 3, yy = k >> 2;
					int x = bx * 4 + xx, y = by * 4 + yy;
					if (x >= w || y >= h) {
						continue;
					}
					int c = pal[((d[o + yy] & 0xFF) >> (2 * xx)) & 3];
					int a;
					if (fmt == 10) {
						a = ap[(int)((alphaBits >> (3 * k)) & 7)];
					} else if (fmt == 9) {
						a = ((u16(d, o + 8 + yy * 2) >> (4 * xx)) & 15) * 17;
					} else {
						a = c == 0 ? 0 : 255;
					}
					px[y * w + x] = (c & 0xFFFFFF) | a << 24;
				}
			}
		}
		return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
	}

	private static byte[] unswizzle(byte[] raw, int rowBytes, int h) {
		byte[] out = new byte[raw.length];
		int rowBlocks = rowBytes / 16;
		int o = 0;
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < rowBytes; x++) {
				int bx = x / 16, by = y / 8;
				int src = (bx + by * rowBlocks) * 128 + (x - bx * 16) + (y - by * 8) * 16;
				out[o++] = src < raw.length ? raw[src] : 0;
			}
		}
		return out;
	}

	private static int rgb(int r, int g, int b) {
		return 0xFF000000 | r << 16 | g << 8 | b;
	}

	// GE 8888 is R, G, B, A in memory.
	private static int abgr8888(int v) {
		int r = v & 0xFF, g = v >> 8 & 0xFF, b = v >> 16 & 0xFF, a = v >>> 24;
		return a << 24 | r << 16 | g << 8 | b;
	}

	// GE 565 / 5551 / 4444 with red in the low bits.
	private static int color16(int v, int type) {
		int r, g, b, a;
		switch (type) {
			case 2:
				r = (v & 15) * 17; g = (v >> 4 & 15) * 17; b = (v >> 8 & 15) * 17; a = (v >> 12) * 17;
				break;
			case 1:
				r = (v & 31) * 255 / 31; g = (v >> 5 & 31) * 255 / 31; b = (v >> 10 & 31) * 255 / 31; a = (v >> 15) != 0 ? 255 : 0;
				break;
			default:
				r = (v & 31) * 255 / 31; g = (v >> 5 & 63) * 255 / 63; b = (v >> 11) * 255 / 31; a = 255;
				break;
		}
		return a << 24 | r << 16 | g << 8 | b;
	}

	private static int u16(byte[] d, int o) {
		return (d[o] & 0xFF) | (d[o + 1] & 0xFF) << 8;
	}

	private static int u32(byte[] d, int o) {
		return (d[o] & 0xFF) | (d[o + 1] & 0xFF) << 8 | (d[o + 2] & 0xFF) << 16 | (d[o + 3] & 0xFF) << 24;
	}
}
