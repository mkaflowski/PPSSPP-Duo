package org.ppsspp.ppsspp.duo.games;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;

import java.nio.charset.StandardCharsets;

// Builds the Liberty City map from the game's own radar textures ("radar00".."radar63" in
// GTA3PSPHR.IMG), so nothing copyrighted ships with the app.
//
// Each texture is a relocatable "xet" chunk holding a 128x128 4bpp swizzled raster with a 16-entry
// RGBA palette right after the pixels. Tile n covers column n % 8 (west to east) and row n / 8
// (north to south) of an 8x8 grid spanning the world from -2000 to 2000 on both axes.
final class GtaRadar {
	static final int TILE = 128;
	static final int GRID = 8;
	static final int SIZE = TILE * GRID;
	static final float WORLD_MIN = -2000.0f;
	static final float WORLD_SIZE = 4000.0f;
	static final int SEA_COLOR = Color.rgb(38, 66, 104);

	private static final byte[] CHUNK_MAGIC = {'x', 'e', 't', 0};
	private static final byte[] NAME = "radar".getBytes(StandardCharsets.US_ASCII);

	private final int[][] tiles = new int[GRID * GRID][];

	int tileCount() {
		int n = 0;
		for (int[] t : tiles) {
			if (t != null) {
				n++;
			}
		}
		return n;
	}

	// Decodes every radar texture found in data. Textures cut off at either end are skipped (a later
	// overlapping read will contain them).
	void scan(byte[] data) {
		for (int i = indexOf(data, NAME, 0); i >= 0; i = indexOf(data, NAME, i + 1)) {
			if (i + 8 > data.length || !isDigit(data[i + 5]) || !isDigit(data[i + 6]) || data[i + 7] != 0) {
				continue;
			}
			int n = (data[i + 5] - '0') * 10 + (data[i + 6] - '0');
			if (n >= tiles.length || tiles[n] != null) {
				continue;
			}
			int chunk = lastIndexOf(data, CHUNK_MAGIC, i);
			if (chunk < 0 || i - chunk > 0x200) {
				continue;
			}
			int[] tile = decodeTile(data, chunk);
			if (tile != null) {
				tiles[n] = tile;
			}
		}
	}

	private static int[] decodeTile(byte[] d, int chunk) {
		if (chunk + 16 > d.length) {
			return null;
		}
		int end = chunk + u32(d, chunk + 8);
		if (end > d.length || end <= chunk) {
			return null;
		}
		// Raster header: palette size, log2 width, log2 height, depth, mip count, ...
		int hdr = -1;
		for (int k = chunk + 0x20; k + 6 <= Math.min(end, chunk + 0x200); k++) {
			if (d[k + 1] == 0 && d[k + 2] == 7 && d[k + 3] == 7 && d[k + 4] == 4 && d[k + 5] == 1) {
				hdr = k;
				break;
			}
		}
		if (hdr < 4) {
			return null;
		}
		int pixels = chunk + u32(d, hdr - 4);
		int pixelBytes = TILE * TILE / 2;
		int palette = pixels + pixelBytes;
		if (pixels < chunk || palette + 64 > end) {
			return null;
		}
		int[] pal = new int[16];
		for (int p = 0; p < 16; p++) {
			int o = palette + p * 4;
			int r = d[o] & 0xFF, g = d[o + 1] & 0xFF, b = d[o + 2] & 0xFF, a = d[o + 3] & 0xFF;
			// Blend onto the sea color so transparent water looks like water.
			int sr = Color.red(SEA_COLOR), sg = Color.green(SEA_COLOR), sb = Color.blue(SEA_COLOR);
			pal[p] = Color.rgb((r * a + sr * (255 - a)) / 255, (g * a + sg * (255 - a)) / 255, (b * a + sb * (255 - a)) / 255);
		}
		// PSP swizzle: 16-byte x 8-row blocks.
		int[] out = new int[TILE * TILE];
		int rowBytes = TILE / 2;
		int src = pixels;
		for (int by = 0; by < TILE / 8; by++) {
			for (int bx = 0; bx < rowBytes / 16; bx++) {
				for (int y = 0; y < 8; y++) {
					int dstRow = (by * 8 + y) * TILE;
					for (int x = 0; x < 16; x++) {
						int v = d[src++] & 0xFF;
						int px = (bx * 16 + x) * 2;
						out[dstRow + px] = pal[v & 0xF];
						out[dstRow + px + 1] = pal[v >> 4];
					}
				}
			}
		}
		return out;
	}

	Bitmap toBitmap() {
		Bitmap bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
		new Canvas(bmp).drawColor(SEA_COLOR);
		for (int n = 0; n < tiles.length; n++) {
			if (tiles[n] != null) {
				bmp.setPixels(tiles[n], 0, TILE, (n % GRID) * TILE, (n / GRID) * TILE, TILE, TILE);
			}
		}
		return bmp;
	}

	static float worldToMapX(float x) {
		return (x - WORLD_MIN) / WORLD_SIZE * SIZE;
	}

	static float worldToMapY(float y) {
		return (-y - WORLD_MIN) / WORLD_SIZE * SIZE;
	}

	private static boolean isDigit(byte b) {
		return b >= '0' && b <= '9';
	}

	private static int u32(byte[] d, int o) {
		return (d[o] & 0xFF) | (d[o + 1] & 0xFF) << 8 | (d[o + 2] & 0xFF) << 16 | (d[o + 3] & 0xFF) << 24;
	}

	private static int indexOf(byte[] d, byte[] needle, int from) {
		outer:
		for (int i = Math.max(0, from); i + needle.length <= d.length; i++) {
			for (int k = 0; k < needle.length; k++) {
				if (d[i + k] != needle[k]) {
					continue outer;
				}
			}
			return i;
		}
		return -1;
	}

	private static int lastIndexOf(byte[] d, byte[] needle, int before) {
		outer:
		for (int i = Math.min(before - needle.length, d.length - needle.length); i >= 0; i--) {
			for (int k = 0; k < needle.length; k++) {
				if (d[i + k] != needle[k]) {
					continue outer;
				}
			}
			return i;
		}
		return -1;
	}
}
