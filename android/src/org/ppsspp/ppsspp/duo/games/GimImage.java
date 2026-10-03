package org.ppsspp.ppsspp.duo.games;

import android.graphics.Bitmap;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

// Decoder for GIM ("MIG.00.1PSP"), the PSP SDK's texture format.
//
// After the 16-byte file header comes a tree of blocks: u16 type, u16, u32 size, u32 offset of the
// next block, u32 offset of the block's data (all relative to the block). File (2) and picture (3)
// blocks hold the others; image (4) and palette (5) blocks start with a header: u16 header size,
// u16, u16 format, u16 pixel order (1 = swizzled), u16 width, u16 height, u16 bits per pixel,
// u16 pitch alignment, ..., and the pixels at +0x1C (relative to the header).
// Formats: 0 RGB565, 1 RGBA5551, 2 RGBA4444, 3 RGBA8888, 4 index4, 5 index8.
final class GimImage {
	private GimImage() {}

	private static final class Plane {
		int format, order, width, height, bpp, pitch, data;
	}

	// Returns null if the data isn't a GIM this decoder handles.
	static Bitmap decode(byte[] file) {
		if (file == null || file.length < 0x30 || file[0] != 'M' || file[1] != 'I' || file[2] != 'G') {
			return null;
		}
		ByteBuffer b = ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN);
		Plane[] planes = new Plane[2];
		try {
			walk(b, 0x10, file.length, planes, 0);
		} catch (IndexOutOfBoundsException e) {
			return null;
		}
		Plane img = planes[0], pal = planes[1];
		if (img == null || img.width <= 0 || img.height <= 0 || img.width > 1024 || img.height > 1024) {
			return null;
		}
		boolean indexed = img.format == 4 || img.format == 5;
		if (indexed && pal == null) {
			return null;
		}
		try {
			byte[] raw = new byte[img.pitch * img.height];
			b.position(img.data);
			b.get(raw);
			if (img.order == 1) {
				raw = unswizzle(raw, img.pitch, img.height);
			}
			int[] colors = new int[img.width * img.height];
			int[] palette = indexed ? palette(b, pal, img.format == 4 ? 16 : 256) : null;
			ByteBuffer rb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
			for (int y = 0; y < img.height; y++) {
				int row = y * img.pitch;
				for (int x = 0; x < img.width; x++) {
					int c;
					if (img.format == 5) {
						c = palette[raw[row + x] & 0xFF];
					} else if (img.format == 4) {
						int v = raw[row + x / 2] & 0xFF;
						c = palette[(x & 1) != 0 ? v >> 4 : v & 0xF];
					} else {
						c = color(rb, img.format, row + x * (img.bpp / 8));
					}
					colors[y * img.width + x] = c;
				}
			}
			return Bitmap.createBitmap(colors, img.width, img.height, Bitmap.Config.ARGB_8888);
		} catch (RuntimeException e) {
			return null;
		}
	}

	private static void walk(ByteBuffer b, int off, int end, Plane[] planes, int depth) {
		while (off + 0x10 <= end && depth < 4) {
			int type = b.getShort(off) & 0xFFFF;
			int size = b.getInt(off + 4);
			int dataOff = b.getInt(off + 12);
			if (size <= 0) {
				return;
			}
			if (type == 2 || type == 3) {
				walk(b, off + dataOff, off + size, planes, depth + 1);
			} else if ((type == 4 || type == 5) && planes[type - 4] == null) {
				int h = off + dataOff;
				Plane p = new Plane();
				p.format = b.getShort(h + 4) & 0xFFFF;
				p.order = b.getShort(h + 6) & 0xFFFF;
				p.width = b.getShort(h + 8) & 0xFFFF;
				p.height = b.getShort(h + 10) & 0xFFFF;
				p.bpp = b.getShort(h + 12) & 0xFFFF;
				int align = Math.max(1, b.getShort(h + 14) & 0xFFFF);
				p.pitch = ((p.width * p.bpp / 8) + align - 1) / align * align;
				p.data = h + b.getInt(h + 0x1C);
				planes[type - 4] = p;
			}
			off += size;
		}
	}

	private static int[] palette(ByteBuffer b, Plane pal, int count) {
		int[] out = new int[256];
		int n = Math.min(count, pal.width);
		int step = pal.format == 3 ? 4 : 2;
		for (int i = 0; i < n; i++) {
			out[i] = color(b, pal.format, pal.data + i * step);
		}
		return out;
	}

	// Returns ARGB.
	private static int color(ByteBuffer b, int format, int off) {
		int r, g, bl, a;
		if (format == 3) {
			int v = b.getInt(off);
			r = v & 0xFF;
			g = (v >> 8) & 0xFF;
			bl = (v >> 16) & 0xFF;
			a = (v >>> 24);
		} else {
			int v = b.getShort(off) & 0xFFFF;
			if (format == 0) {
				r = (v & 31) * 255 / 31;
				g = ((v >> 5) & 63) * 255 / 63;
				bl = ((v >> 11) & 31) * 255 / 31;
				a = 255;
			} else if (format == 1) {
				r = (v & 31) * 255 / 31;
				g = ((v >> 5) & 31) * 255 / 31;
				bl = ((v >> 10) & 31) * 255 / 31;
				a = (v >> 15) != 0 ? 255 : 0;
			} else {
				r = (v & 15) * 17;
				g = ((v >> 4) & 15) * 17;
				bl = ((v >> 8) & 15) * 17;
				a = (v >> 12) * 17;
			}
		}
		return (a << 24) | (r << 16) | (g << 8) | bl;
	}

	// PSP swizzle: 16-byte x 8-row blocks.
	private static byte[] unswizzle(byte[] raw, int pitch, int h) {
		byte[] out = new byte[raw.length];
		int i = 0;
		for (int by = 0; by + 8 <= h; by += 8) {
			for (int bx = 0; bx + 16 <= pitch; bx += 16) {
				for (int y = 0; y < 8; y++) {
					System.arraycopy(raw, i, out, (by + y) * pitch + bx, 16);
					i += 16;
				}
			}
		}
		return out;
	}
}
