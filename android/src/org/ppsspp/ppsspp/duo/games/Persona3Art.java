package org.ppsspp.ppsspp.duo.games;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import org.ppsspp.ppsspp.duo.DuoModContext;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

// Persona 3 Portable's battle art, read from the disc at runtime and cached as PNGs (nothing is
// shipped): the party's HUD faces with their outlines, and the menu atlases with the element icons and the Wk / Str /
// Nul / Rpl / Dm labels of the Analyze screen.
//
// Everything is in USRDIR/umd0.cpk, a CRI CPK archive: a "CPK " chunk whose @UTF table gives the
// TOC's offset, and a "TOC " chunk whose @UTF table lists the files (DirName, FileName, FileOffset
// from the TOC, FileSize, ExtractSize). Compressed files are CRILAYLA (described below).
// - data/battle/panel/btlpanel.bin: a pack (u32 count, then name[32], u32 size, data, 4-aligned) of
//   SPR0 sprite files; b_faceNN.spr's first texture is the HUD face of character NN, the second its
//   silhouette, which the HUD draws light blue under the face as an outline.
// - data/init_free.bin: a pack of name[252], u32 size, data (64-aligned); its init/camp.bin is a
//   pack like btlpanel's, whose c_main_01.spr holds the menu atlases c_main_01 (element icons)
//   and c_main_02 (Analyze labels).
// SPR0: 'SPR0' at +8, u16 texture count at +0x14, u32 texture table at +0x18 (8-byte entries, the
// offset from the SPR0 start second). TMX0: 'TMX0' at +8, then at +0x10 u8 palette count, u8,
// u16 width, u16 height, u8 pixel format (0x13 8 bpp, 0x14 4 bpp), and from +0x40 the RGBA palette
// (alpha 0..0x80; 8 bpp palettes in the PS2 order, blocks 8-15 and 16-23 of every 32 swapped)
// followed by unswizzled pixels.
final class Persona3Art {
	private static final String TAG = "PPSSPPDuo";
	private static final String CPK = "disc0:/PSP_GAME/USRDIR/umd0.cpk";
	private static final int MAX_READ = 1024 * 1024;
	static final int FACES = 11;

	final Bitmap icons;    // c_main_01, 256x256
	final Bitmap labels;   // c_main_02, 128x128
	final Bitmap[] faces;     // by character id, null where missing
	final Bitmap[] outlines;  // white silhouettes of the faces

	interface Callback {
		void onReady(Persona3Art art);  // null if the art can't be read
	}

	private Persona3Art(Bitmap icons, Bitmap labels, Bitmap[] faces, Bitmap[] outlines) {
		this.icons = icons;
		this.labels = labels;
		this.faces = faces;
		this.outlines = outlines;
	}

	static void load(DuoModContext host, File dir, Callback cb) {
		Persona3Art cached = fromCache(dir);
		if (cached != null) {
			cb.onReady(cached);
			return;
		}
		// The CPK header's table holds the TOC's offset and size.
		boolean ok = host.readGameFile(CPK, 0, 2048, head -> {
			Utf cpk = Utf.chunk(head, 0);
			if (cpk == null) {
				fail(cb, "no CPK header");
				return;
			}
			long tocOffset = cpk.getLong(0, "TocOffset");
			long tocSize = cpk.getLong(0, "TocSize");
			if (tocOffset <= 0 || tocSize <= 0 || tocSize > MAX_READ) {
				fail(cb, "bad TOC " + tocOffset + " " + tocSize);
				return;
			}
			boolean ok2 = host.readGameFile(CPK, (int)tocOffset, (int)tocSize, tocData -> {
				Utf toc = Utf.chunk(tocData, 0);
				if (toc == null) {
					fail(cb, "no TOC");
					return;
				}
				long[] panel = find(toc, "data/battle/panel", "btlpanel.bin", tocOffset);
				long[] init = find(toc, "data", "init_free.bin", tocOffset);
				if (panel == null || init == null) {
					fail(cb, "files not in the TOC");
					return;
				}
				readFile(host, panel, panelData -> {
					Bitmap[] faces = panelData != null ? faces(panelData, 0) : null;
					Bitmap[] outlines = panelData != null ? faces(panelData, 1) : null;
					readFile(host, init, initData -> {
						Bitmap[] atlases = initData != null ? atlases(initData) : null;
						if (faces == null || outlines == null || atlases == null) {
							fail(cb, "can't decode the art");
							return;
						}
						Persona3Art art = new Persona3Art(atlases[0], atlases[1], faces, outlines);
						art.save(dir);
						cb.onReady(art);
					});
				});
			});
			if (!ok2) {
				cb.onReady(null);
			}
		});
		if (!ok) {
			cb.onReady(null);
		}
	}

	private static void fail(Callback cb, String why) {
		Log.w(TAG, "Persona 3: " + why);
		cb.onReady(null);
	}

	// {offset, stored size, extracted size} of a file, or null.
	private static long[] find(Utf toc, String dirName, String fileName, long base) {
		for (int r = 0; r < toc.rows; r++) {
			if (fileName.equals(toc.getString(r, "FileName")) && dirName.equals(toc.getString(r, "DirName"))) {
				return new long[] {base + toc.getLong(r, "FileOffset"), toc.getLong(r, "FileSize"), toc.getLong(r, "ExtractSize")};
			}
		}
		return null;
	}

	private static void readFile(DuoModContext host, long[] entry, DuoModContext.GameFileCallback cb) {
		if (entry[1] <= 0 || entry[1] > MAX_READ || entry[0] > Integer.MAX_VALUE) {
			cb.onGameFile(null);
			return;
		}
		boolean ok = host.readGameFile(CPK, (int)entry[0], (int)entry[1], data -> {
			if (data == null || data.length != entry[1]) {
				cb.onGameFile(null);
				return;
			}
			cb.onGameFile(isCrilayla(data) ? crilayla(data) : data);
		});
		if (!ok) {
			cb.onGameFile(null);
		}
	}

	private static Bitmap[] faces(byte[] panel, int texture) {
		Bitmap[] out = new Bitmap[FACES];
		boolean any = false;
		for (int[] e : pack(panel)) {
			String name = cString(panel, e[0], 32);
			// b_face01.spr .. b_face10.spr
			if (name.startsWith("b_face") && name.endsWith(".spr") && name.length() >= 12) {
				int id;
				try {
					id = Integer.parseInt(name.substring(6, 8));
				} catch (NumberFormatException ex) {
					continue;
				}
				if (id > 0 && id < FACES) {
					out[id] = sprTexture(panel, e[1], texture);
					any |= out[id] != null;
				}
			}
		}
		return any ? out : null;
	}

	private static Bitmap[] atlases(byte[] init) {
		ByteBuffer b = ByteBuffer.wrap(init).order(ByteOrder.LITTLE_ENDIAN);
		int p = 0;
		while (p + 256 <= init.length) {
			String name = cString(init, p, 252);
			int size = b.getInt(p + 252);
			if (name.isEmpty() || size < 0 || p + 256 + size > init.length) {
				break;
			}
			if (name.equals("init/camp.bin")) {
				byte[] camp = new byte[size];
				System.arraycopy(init, p + 256, camp, 0, size);
				for (int[] e : pack(camp)) {
					if (cString(camp, e[0], 32).equals("c_main_01.spr")) {
						Bitmap icons = sprTexture(camp, e[1], 0);
						Bitmap labels = sprTexture(camp, e[1], 1);
						return icons != null && labels != null ? new Bitmap[] {icons, labels} : null;
					}
				}
				return null;
			}
			p = (p + 256 + size + 63) & ~63;
		}
		return null;
	}

	// Entries of a name[32] + size pack: {name offset, data offset}.
	private static java.util.List<int[]> pack(byte[] d) {
		java.util.List<int[]> out = new java.util.ArrayList<>();
		ByteBuffer b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
		if (d.length < 4) {
			return out;
		}
		int count = b.getInt(0);
		int p = 4;
		for (int i = 0; i < count && p + 36 <= d.length; i++) {
			int size = b.getInt(p + 32);
			if (size < 0 || p + 36 + size > d.length) {
				break;
			}
			out.add(new int[] {p, p + 36});
			p = (p + 36 + size + 3) & ~3;
		}
		return out;
	}

	private static Bitmap sprTexture(byte[] d, int spr, int index) {
		try {
			ByteBuffer b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
			if (b.getInt(spr + 8) != 0x30525053) {  // SPR0
				return null;
			}
			int count = b.getShort(spr + 0x14) & 0xFFFF;
			if (index >= count) {
				return null;
			}
			int table = b.getInt(spr + 0x18);
			return tmx(d, spr + b.getInt(spr + table + index * 8 + 4));
		} catch (IndexOutOfBoundsException e) {
			return null;
		}
	}

	private static Bitmap tmx(byte[] d, int o) {
		ByteBuffer b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
		if (b.getInt(o + 8) != 0x30584D54) {  // TMX0
			return null;
		}
		int w = b.getShort(o + 0x12) & 0xFFFF, h = b.getShort(o + 0x14) & 0xFFFF;
		int format = d[o + 0x16] & 0xFF;
		if (w <= 0 || h <= 0 || w > 1024 || h > 1024 || (format != 0x13 && format != 0x14)) {
			return null;
		}
		int colors = format == 0x13 ? 256 : 16;
		int[] pal = new int[colors];
		int p = o + 0x40;
		for (int i = 0; i < colors; i++) {
			// PS2 CSM1 order for 256-colour palettes.
			int j = colors == 256 ? (i & 0xE7) | ((i & 0x08) << 1) | ((i & 0x10) >> 1) : i;
			int q = p + j * 4;
			int a = Math.min(255, (d[q + 3] & 0xFF) * 2);
			pal[i] = a << 24 | (d[q] & 0xFF) << 16 | (d[q + 1] & 0xFF) << 8 | (d[q + 2] & 0xFF);
		}
		p += colors * 4;
		int[] argb = new int[w * h];
		for (int i = 0; i < w * h; i++) {
			int v;
			if (colors == 256) {
				v = d[p + i] & 0xFF;
			} else {
				int bt = d[p + i / 2] & 0xFF;
				v = (i & 1) == 0 ? bt & 0xF : bt >> 4;
			}
			argb[i] = pal[v];
		}
		return Bitmap.createBitmap(argb, w, h, Bitmap.Config.ARGB_8888);
	}

	private static String cString(byte[] d, int o, int max) {
		int n = 0;
		while (n < max && o + n < d.length && d[o + n] != 0) {
			n++;
		}
		return new String(d, o, n, StandardCharsets.ISO_8859_1);
	}

	private static boolean isCrilayla(byte[] d) {
		return d.length >= 16 && new String(d, 0, 8, StandardCharsets.ISO_8859_1).equals("CRILAYLA");
	}

	// CRILAYLA: "CRILAYLA", u32 uncompressed size, u32 size of the compressed part, the compressed
	// part, then the file's first 0x100 bytes stored raw. The output is written backwards from its
	// end, reading the compressed part's bits backwards (most significant first): 1 = a copy (13-bit
	// distance + 3, length 3 + a sum of 2, 3, 5, then 8-bit chunks while each is all ones), 0 = a
	// literal byte.
	static byte[] crilayla(byte[] src) {
		ByteBuffer b = ByteBuffer.wrap(src).order(ByteOrder.LITTLE_ENDIAN);
		int size = b.getInt(8), header = b.getInt(12);
		if (size < 0 || header < 0 || 16 + header + 0x100 > src.length) {
			return null;
		}
		byte[] out = new byte[size + 0x100];
		System.arraycopy(src, 16 + header, out, 0, 0x100);
		Bits bits = new Bits(src, 16 + header - 1, 16);
		int w = out.length - 1;
		final int[] lens = {2, 3, 5, 8};
		while (w >= 0x100) {
			if (bits.get(1) != 0) {
				int from = w + bits.get(13) + 3;
				int len = 3;
				for (int k = 0; ; k++) {
					int n = lens[Math.min(k, 3)];
					int v = bits.get(n);
					len += v;
					if (v != (1 << n) - 1) {
						break;
					}
				}
				for (int i = 0; i < len && w >= 0x100; i++) {
					out[w--] = out[from--];
				}
			} else {
				out[w--] = (byte)bits.get(8);
			}
		}
		return out;
	}

	private static final class Bits {
		private final byte[] d;
		private final int start;
		private int pos, pool, avail;

		Bits(byte[] d, int pos, int start) {
			this.d = d;
			this.pos = pos;
			this.start = start;
		}

		int get(int n) {
			int v = 0;
			while (n > 0) {
				if (avail == 0) {
					pool = pos >= start ? d[pos--] & 0xFF : 0;
					avail = 8;
				}
				int take = Math.min(avail, n);
				v = v << take | (pool >> (avail - take)) & ((1 << take) - 1);
				avail -= take;
				n -= take;
			}
			return v;
		}
	}

	// A CRI @UTF table: rows of typed columns, constant or per row.
	private static final class Utf {
		final byte[] d;
		final int base, rowsOffset, stringsOffset, dataOffset, rowWidth, rows;
		final String[] names;
		final int[] types, constOffsets, rowOffsets;

		// A chunk at o: 4-char tag, u32, u32 size, u32, then the @UTF table.
		static Utf chunk(byte[] d, int o) {
			if (d == null || d.length < o + 16 + 32) {
				return null;
			}
			try {
				return new Utf(d, o + 16);
			} catch (RuntimeException e) {
				return null;
			}
		}

		private Utf(byte[] d, int o) {
			this.d = d;
			ByteBuffer b = ByteBuffer.wrap(d).order(ByteOrder.BIG_ENDIAN);
			if (b.getInt(o) != 0x40555446) {  // @UTF
				throw new IllegalArgumentException("not @UTF");
			}
			base = o + 8;
			rowsOffset = base + b.getInt(o + 8);
			stringsOffset = base + b.getInt(o + 12);
			dataOffset = base + b.getInt(o + 16);
			int columns = b.getShort(o + 24) & 0xFFFF;
			rowWidth = b.getShort(o + 26) & 0xFFFF;
			rows = b.getInt(o + 28);
			names = new String[columns];
			types = new int[columns];
			constOffsets = new int[columns];
			rowOffsets = new int[columns];
			int p = o + 32, inRow = 0;
			for (int c = 0; c < columns; c++) {
				int flags = d[p] & 0xFF;
				names[c] = string(b.getInt(p + 1));
				p += 5;
				types[c] = flags;
				constOffsets[c] = -1;
				rowOffsets[c] = -1;
				int storage = flags & 0xF0, size = size(flags & 0x0F);
				if (storage == 0x30) {
					constOffsets[c] = p;
					p += size;
				} else if (storage == 0x50) {
					rowOffsets[c] = inRow;
					inRow += size;
				}
			}
		}

		private static int size(int type) {
			switch (type) {
			case 0: case 1: return 1;
			case 2: case 3: return 2;
			case 4: case 5: case 8: case 0xA: return 4;
			case 6: case 7: case 0xB: return 8;
			default: throw new IllegalArgumentException("type " + type);
			}
		}

		private String string(int offset) {
			return cString(d, stringsOffset + offset, 256);
		}

		private int where(int row, String name) {
			for (int c = 0; c < names.length; c++) {
				if (names[c].equals(name)) {
					if (constOffsets[c] >= 0) {
						return constOffsets[c];
					}
					if (rowOffsets[c] >= 0) {
						return rowsOffset + row * rowWidth + rowOffsets[c];
					}
				}
			}
			return -1;
		}

		private int type(String name) {
			for (int c = 0; c < names.length; c++) {
				if (names[c].equals(name)) {
					return types[c] & 0x0F;
				}
			}
			return -1;
		}

		long getLong(int row, String name) {
			int p = where(row, name);
			if (p < 0) {
				return -1;
			}
			ByteBuffer b = ByteBuffer.wrap(d).order(ByteOrder.BIG_ENDIAN);
			switch (type(name)) {
			case 0: case 1: return d[p] & 0xFF;
			case 2: case 3: return b.getShort(p) & 0xFFFF;
			case 4: case 5: return b.getInt(p) & 0xFFFFFFFFL;
			case 6: case 7: return b.getLong(p);
			default: return -1;
			}
		}

		String getString(int row, String name) {
			int p = where(row, name);
			if (p < 0 || type(name) != 0xA) {
				return null;
			}
			return string(ByteBuffer.wrap(d).order(ByteOrder.BIG_ENDIAN).getInt(p));
		}
	}

	private static Persona3Art fromCache(File dir) {
		Bitmap icons = BitmapFactory.decodeFile(new File(dir, "icons.png").getPath());
		Bitmap labels = BitmapFactory.decodeFile(new File(dir, "labels.png").getPath());
		if (icons == null || labels == null) {
			return null;
		}
		Bitmap[] faces = new Bitmap[FACES], outlines = new Bitmap[FACES];
		for (int i = 1; i < FACES; i++) {
			faces[i] = cached(dir, "face" + i + ".png");
			outlines[i] = cached(dir, "outline" + i + ".png");
		}
		return new Persona3Art(icons, labels, faces, outlines);
	}

	private static Bitmap cached(File dir, String name) {
		File f = new File(dir, name);
		return f.isFile() ? BitmapFactory.decodeFile(f.getPath()) : null;
	}

	private void save(File dir) {
		//noinspection ResultOfMethodCallIgnored
		dir.mkdirs();
		for (int i = 1; i < FACES; i++) {
			if (faces[i] != null) {
				write(faces[i], new File(dir, "face" + i + ".png"));
			}
			if (outlines[i] != null) {
				write(outlines[i], new File(dir, "outline" + i + ".png"));
			}
		}
		// Last, as the cache counts as complete once these two are there.
		write(labels, new File(dir, "labels.png"));
		write(icons, new File(dir, "icons.png"));
	}

	private static void write(Bitmap bitmap, File f) {
		try (FileOutputStream out = new FileOutputStream(f)) {
			bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
		} catch (Exception e) {
			Log.w(TAG, "Persona 3: can't cache " + f.getName(), e);
		}
	}
}
