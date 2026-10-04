package org.ppsspp.ppsspp.duo.games;

import android.util.Log;

import org.ppsspp.ppsspp.duo.DuoModContext;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.Inflater;

// Gran Turismo PSP's GT.VOL, the archive with almost all of the game's files. Format from Nenkai's
// GTPSPVolTools (MIT):
// - header (0x40 bytes): magic 0x71D319F3, then fields scrambled with a byte substitution (SBOX):
//   +0x10 ToC block, +0x14 data block (after the ToC), +0x18 folder count, +0x1C ToC size. Blocks
//   are 0x800 bytes and counted from the end of the header block.
// - ToC (same substitution): u16 per folder giving its page (x 0x40) in the ToC, then the pages. A
//   page starts with u16 bits (bit 0: index page, bits 1-11: entry count) and 12-bit entry offsets,
//   then the entries: a flags byte (bit 0 folder, bit 1 compressed, bits 2-7 high bits of a folder's
//   page), the name (length-prefixed) and either the folder's low page byte or the file's offset
//   (x 0x40, from the data start) and sizes. Index pages hold [page hi][name][page lo] and point to
//   more pages of the same folder. Numbers are bijective base 128, most significant group first.
// - file data is XORed with SBOX[absolute offset & 0xFF]; compressed files start with C5 EE F7 FF,
//   then -size (s32), then raw deflate.
final class GtVolume {
	private static final String TAG = "PPSSPPDuo";
	static final String PATH = "disc0:/PSP_GAME/USRDIR/GT.VOL";
	private static final int MAGIC = 0x71D319F3;
	private static final int BLOCK = 0x800;
	private static final int CHUNK = 1024 * 1024;

	static final byte[] SBOX = bytes(
		0x00, 0x06, 0x86, 0x57, 0x97, 0x69, 0x6C, 0xB5, 0xBD, 0xD6, 0xBE, 0x34, 0xC2, 0x35, 0xCE, 0xFA,
		0x0E, 0x7E, 0x2F, 0xD0, 0x9A, 0x8E, 0xB4, 0x82, 0x25, 0x58, 0x1F, 0x6D, 0x90, 0xF5, 0x8B, 0xA5,
		0xE5, 0x96, 0x56, 0xFF, 0x3B, 0x2B, 0x6B, 0xAE, 0x98, 0x32, 0x2D, 0x60, 0x45, 0xFE, 0x81, 0xA7,
		0xEC, 0x1B, 0xDA, 0xC9, 0xDC, 0x3C, 0x52, 0xF9, 0x7B, 0x04, 0x63, 0xC6, 0xDB, 0xCA, 0x1C, 0x3D,
		0xD1, 0x7A, 0xFD, 0x6F, 0xF1, 0xCD, 0x9C, 0x4D, 0x78, 0x74, 0x0D, 0x40, 0x51, 0x8D, 0x64, 0x5C,
		0xCB, 0x49, 0xF8, 0x39, 0x24, 0x30, 0x3A, 0xE2, 0x22, 0x61, 0xA4, 0x89, 0x09, 0x65, 0xAD, 0x1D,
		0xEF, 0x4E, 0xC8, 0xB8, 0x10, 0xA2, 0xDF, 0x0F, 0xFB, 0x66, 0x54, 0xA6, 0x1E, 0x11, 0x73, 0x62,
		0x13, 0x21, 0x46, 0xB9, 0x33, 0x9D, 0x88, 0xB2, 0xE3, 0x37, 0x0C, 0x4F, 0x84, 0x3E, 0xF0, 0x16,
		0x70, 0x36, 0xDE, 0x8A, 0x1A, 0xEE, 0x28, 0xBC, 0x9F, 0x05, 0x80, 0x67, 0x4A, 0x7C, 0xE0, 0x53,
		0x2E, 0xE7, 0xA3, 0x6A, 0xFC, 0x03, 0x41, 0x6E, 0xD8, 0x14, 0x38, 0xBB, 0xF6, 0xEB, 0x19, 0xAC,
		0x48, 0xD4, 0x27, 0x44, 0xC4, 0x08, 0x95, 0x07, 0x43, 0xD5, 0x18, 0x26, 0xF4, 0x20, 0x75, 0x77,
		0xC0, 0xA1, 0x99, 0x0B, 0xC3, 0x85, 0xE1, 0xBA, 0x4C, 0xB3, 0x9B, 0x47, 0x23, 0xAF, 0x8C, 0x72,
		0x68, 0xA8, 0xCC, 0xC7, 0xAB, 0x5F, 0x5D, 0x93, 0x3F, 0x5E, 0x87, 0x9E, 0xCF, 0xD7, 0xB0, 0x4B,
		0x76, 0xD9, 0x71, 0x31, 0xB6, 0x7D, 0x50, 0x0A, 0x5B, 0xE8, 0x83, 0x2A, 0xB7, 0x7F, 0xDD, 0x59,
		0xC5, 0x79, 0x5A, 0xF7, 0xA9, 0xE9, 0xD2, 0xED, 0xE6, 0xBF, 0xF3, 0xB1, 0x29, 0x01, 0x12, 0xAA,
		0x91, 0x92, 0xF2, 0x15, 0xE4, 0xD3, 0x17, 0x42, 0x02, 0xA0, 0x8F, 0xC1, 0x2C, 0x94, 0x55, 0xEA);

	static final class Entry {
		final int offset;      // from the data start
		final int stored;      // size in the volume
		final int size;        // uncompressed
		final boolean compressed;

		Entry(int offset, int stored, int size, boolean compressed) {
			this.offset = offset;
			this.stored = stored;
			this.size = size;
			this.compressed = compressed;
		}
	}

	interface OpenCallback {
		void onOpen(GtVolume volume);   // null if the volume couldn't be read
	}

	interface FileCallback {
		void onFile(byte[] data);       // null if missing or unreadable
	}

	private final DuoModContext host;
	private final Map<String, Entry> files = new HashMap<>();
	private int dataOffset;

	private GtVolume(DuoModContext host) {
		this.host = host;
	}

	static void open(DuoModContext host, OpenCallback cb) {
		GtVolume v = new GtVolume(host);
		boolean ok = host.readGameFile(PATH, 0, 0x40, header -> {
			try {
				if (header == null || header.length < 0x40 || u32(header, 0) != MAGIC) {
					throw new IllegalStateException("not a GT PSP volume");
				}
				byte[] h = unscramble(header);
				int tocBlock = u32(h, 0x10);
				int dataBlock = u32(h, 0x14);
				int folders = u32(h, 0x18);
				int tocSize = u32(h, 0x1C);
				int tocOffset = (1 + tocBlock) * BLOCK;
				v.dataOffset = (1 + tocBlock + dataBlock) * BLOCK;
				readRange(host, tocOffset, tocSize, toc -> {
					try {
						if (toc == null) {
							throw new IllegalStateException("can't read the ToC");
						}
						v.parse(unscramble(toc), folders);
						Log.i(TAG, "GT: volume has " + v.files.size() + " files");
						cb.onOpen(v);
					} catch (Exception e) {
						Log.w(TAG, "GT: volume: " + e);
						cb.onOpen(null);
					}
				});
			} catch (Exception e) {
				Log.w(TAG, "GT: volume: " + e);
				cb.onOpen(null);
			}
		});
		if (!ok) {
			cb.onOpen(null);
		}
	}

	boolean has(String path) {
		return files.containsKey(path);
	}

	Iterable<String> paths() {
		return files.keySet();
	}

	void read(String path, FileCallback cb) {
		Entry e = files.get(path);
		if (e == null) {
			cb.onFile(null);
			return;
		}
		int abs = dataOffset + e.offset;
		readRange(host, abs, e.stored, raw -> {
			if (raw == null || raw.length < e.stored) {
				cb.onFile(null);
				return;
			}
			try {
				for (int i = 0; i < raw.length; i++) {
					raw[i] ^= SBOX[(abs + i) & 0xFF];
				}
				cb.onFile(e.compressed ? inflate(raw, e.size) : raw);
			} catch (Exception ex) {
				Log.w(TAG, "GT: " + path + ": " + ex);
				cb.onFile(null);
			}
		});
	}

	private void parse(byte[] toc, int folders) {
		int[] pages = new int[folders + 1];
		for (int i = 0; i <= folders; i++) {
			pages[i] = (toc[i * 2] & 0xFF) | (toc[i * 2 + 1] & 0xFF) << 8;
		}
		folder(toc, pages, pages[0] * 0x40, "", 0);
	}

	private int pos;

	private int varint(byte[] t) {
		int b = t[pos++] & 0xFF;
		int v = b & 0x7F;
		while ((b & 0x80) != 0) {
			v = (v + 1) << 7;
			b = t[pos++] & 0xFF;
			v += b & 0x7F;
		}
		return v;
	}

	private void folder(byte[] t, int[] pages, int at, String parent, int depth) {
		if (depth > 32) {
			return;
		}
		int bits = (t[at] & 0xFF) | (t[at + 1] & 0xFF) << 8;
		boolean index = (bits & 1) != 0;
		int count = (bits >> 1) & 0x7FF;
		pos = at + (12 * count + 7) / 8;
		if (index) {
			int[] subs = new int[count];
			for (int i = 0; i < count; i++) {
				int hi = t[pos++] & 0xFF;
				int n = varint(t);   // the name (only used for lookups)
				pos += n;
				subs[i] = hi << 8 | (t[pos++] & 0xFF);
			}
			for (int s : subs) {
				folder(t, pages, pages[s] * 0x40, parent, depth + 1);
			}
			if (count > 0 && subs[count - 1] + 1 < count) {
				folder(t, pages, pages[subs[count - 1] + 1] * 0x40, parent, depth + 1);
			}
			return;
		}
		String[] dirNames = new String[count];
		int[] dirPages = new int[count];
		int dirs = 0;
		for (int i = 0; i < count; i++) {
			int flags = t[pos++] & 0xFF;
			int n = varint(t);
			String name = new String(t, pos, n, StandardCharsets.US_ASCII);
			pos += n;
			String full = parent.isEmpty() ? name : parent + "/" + name;
			if ((flags & 1) != 0) {
				dirNames[dirs] = full;
				dirPages[dirs++] = (flags >> 2) << 8 | (t[pos++] & 0xFF);
			} else {
				boolean compressed = (flags & 2) != 0;
				int off = varint(t) * 0x40;
				int stored, size;
				if (compressed) {
					stored = varint(t);
					size = varint(t);
				} else {
					size = stored = varint(t);
				}
				files.put(full, new Entry(off, stored, size, compressed));
			}
		}
		for (int i = 0; i < dirs; i++) {
			folder(t, pages, pages[dirPages[i]] * 0x40, dirNames[i], depth + 1);
		}
	}

	private static byte[] inflate(byte[] d, int size) throws Exception {
		if (u32(d, 0) != 0xFFF7EEC5) {
			throw new IllegalStateException("bad compressed header");
		}
		Inflater inf = new Inflater(true);
		try {
			inf.setInput(d, 8, d.length - 8);
			byte[] out = new byte[size];
			int n = 0;
			while (n < size && !inf.finished()) {
				int r = inf.inflate(out, n, size - n);
				if (r == 0 && (inf.needsInput() || inf.needsDictionary())) {
					break;
				}
				n += r;
			}
			if (n != size) {
				throw new IllegalStateException("inflated " + n + " of " + size);
			}
			return out;
		} finally {
			inf.end();
		}
	}

	private static byte[] unscramble(byte[] d) {
		byte[] out = new byte[d.length];
		for (int i = 0; i < d.length; i++) {
			out[i] = SBOX[d[i] & 0xFF];
		}
		return out;
	}

	private interface Bytes {
		void on(byte[] data);
	}

	// readGameFile in 1 MB pieces.
	private static void readRange(DuoModContext host, int offset, int size, Bytes done) {
		ByteArrayOutputStream acc = new ByteArrayOutputStream(size);
		readNext(host, offset, size, acc, done);
	}

	private static void readNext(DuoModContext host, int offset, int remaining, ByteArrayOutputStream acc, Bytes done) {
		int n = Math.min(CHUNK, remaining);
		boolean ok = host.readGameFile(PATH, offset, n, data -> {
			if (data == null) {
				done.on(null);
				return;
			}
			acc.write(data, 0, data.length);
			if (data.length < n || remaining - n <= 0) {
				done.on(acc.toByteArray());
			} else {
				readNext(host, offset + n, remaining - n, acc, done);
			}
		});
		if (!ok) {
			done.on(null);
		}
	}

	static int u32(byte[] d, int o) {
		return (d[o] & 0xFF) | (d[o + 1] & 0xFF) << 8 | (d[o + 2] & 0xFF) << 16 | (d[o + 3] & 0xFF) << 24;
	}

	private static byte[] bytes(int... v) {
		byte[] b = new byte[v.length];
		for (int i = 0; i < v.length; i++) {
			b[i] = (byte)v[i];
		}
		return b;
	}
}
