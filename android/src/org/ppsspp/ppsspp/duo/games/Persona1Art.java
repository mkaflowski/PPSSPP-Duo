package org.ppsspp.ppsspp.duo.games;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.util.Log;
import android.util.SparseArray;

import org.ppsspp.ppsspp.duo.DuoModContext;

import java.util.HashSet;
import java.util.Set;

// Persona's (PSP) portraits, read from the player's disc at runtime (nothing is shipped).
//
// pack/ch_menu.bin: u32 count, a u32 size per file, then the files from the next 16-byte boundary,
// each a GIM. Files 0-8 are the party's 64x72 faces (the battle HUD's), in the order Nao, Maki,
// Mark, Nanjo, Brown, Elly, Ayase, Yukino, Reiji.
// pack/DVLAN.BIN: u32 (start, end) pairs from +8, one per file; files are GIMs. Demon n is file 2n
// (90x138, the Analyze sprite; 2n - 1 is a 128x128 copy).
final class Persona1Art {
	private static final String TAG = "PPSSPPDuo";
	private static final String CH_MENU = "disc0:/PSP_GAME/USRDIR/pack/ch_menu.bin";
	private static final String DVLAN = "disc0:/PSP_GAME/USRDIR/pack/DVLAN.BIN";
	private static final int CH_MENU_READ = 0x20000;
	private static final int DVLAN_HEADER = 0x1000;
	private static final int FACE_COUNT = 9;
	// Face file per character id (1 the hero ... 9 Reiji, as in the name table).
	private static final int[] FACE_OF_ID = {-1, 0, 1, 4, 3, 2, 5, 6, 7, 8};

	interface Callback {
		void onReady();
	}

	private static Bitmap[] faces;
	private static boolean facesLoading;
	private static byte[] dvlanHeader;
	private static boolean headerLoading;
	private static final SparseArray<Bitmap> demons = new SparseArray<>();
	private static final Set<Integer> demonsLoading = new HashSet<>();
	private static final Set<Integer> demonsMissing = new HashSet<>();

	private Persona1Art() {}

	// The party member's face, or null until it's read.
	static Bitmap face(int id) {
		if (faces == null || id < 0 || id >= FACE_OF_ID.length || FACE_OF_ID[id] < 0) {
			return null;
		}
		return faces[FACE_OF_ID[id]];
	}

	// The demon's sprite, or null until it's read.
	static Bitmap demon(int id) {
		return demons.get(id);
	}

	static void loadFaces(DuoModContext host, Callback cb) {
		if (faces != null || facesLoading) {
			return;
		}
		facesLoading = true;
		boolean ok = host.readGameFile(CH_MENU, 0, CH_MENU_READ, d -> {
			Bitmap[] out = parseFaces(d);
			if (out == null) {
				Log.w(TAG, "Persona: can't read the faces in " + CH_MENU);
			} else {
				faces = out;
				cb.onReady();
			}
		});
		if (!ok) {
			facesLoading = false;
		}
	}

	private static Bitmap[] parseFaces(byte[] d) {
		if (d == null || d.length < 4) {
			return null;
		}
		int n = u32(d, 0);
		if (n < FACE_COUNT || n > 256) {
			return null;
		}
		int o = (4 + 4 * n + 15) / 16 * 16;
		Bitmap[] out = new Bitmap[FACE_COUNT];
		for (int i = 0; i < FACE_COUNT; i++) {
			int size = u32(d, 4 + 4 * i);
			if (size <= 0 || o + size > d.length) {
				return null;
			}
			out[i] = trim(GimImage.decode(slice(d, o, size)));
			if (out[i] == null) {
				return null;
			}
			o += size;
		}
		return out;
	}

	// Reads the demon's sprite unless it's read or being read already; cb runs when it's ready.
	static void loadDemon(DuoModContext host, int id, Callback cb) {
		if (id <= 0 || demons.get(id) != null || demonsLoading.contains(id) || demonsMissing.contains(id)) {
			return;
		}
		if (dvlanHeader == null) {
			if (headerLoading) {
				return;
			}
			headerLoading = true;
			boolean ok = host.readGameFile(DVLAN, 0, DVLAN_HEADER, d -> {
				headerLoading = false;
				if (d == null || d.length < DVLAN_HEADER) {
					Log.w(TAG, "Persona: can't read " + DVLAN);
					return;
				}
				dvlanHeader = d;
				loadDemon(host, id, cb);
			});
			if (!ok) {
				headerLoading = false;
			}
			return;
		}
		int entry = 8 + 8 * (2 * id);
		if (entry + 8 > dvlanHeader.length) {
			demonsMissing.add(id);
			return;
		}
		int start = u32(dvlanHeader, entry), end = u32(dvlanHeader, entry + 4);
		if (start < DVLAN_HEADER || end <= start || end - start > 0x100000) {
			demonsMissing.add(id);
			return;
		}
		demonsLoading.add(id);
		boolean ok = host.readGameFile(DVLAN, start, end - start, d -> {
			demonsLoading.remove(id);
			Bitmap b = d == null ? null : trim(GimImage.decode(d));
			if (b == null) {
				// A read that failed may work next time; a file that isn't a GIM won't.
				if (d != null) {
					demonsMissing.add(id);
				}
				Log.w(TAG, "Persona: no sprite for demon " + id);
				return;
			}
			demons.put(id, b);
			cb.onReady();
		});
		if (!ok) {
			demonsLoading.remove(id);
		}
	}

	// A dungeon's floors from pack/dng/dXY/dXYm.bin (XY the dungeon number in hex): u32 section count,
	// a u32 size per section, the sections from the next 16-byte boundary. Section 1 holds 12-byte
	// cell type records from +0x14 (type t at (t - 1) * 12): bytes 4-7 are the wall pieces on the
	// north, south, east and west sides (0 = none; rooms have none and are closed by the cells around
	// them), and a door type has 4 or 5 in the low half of byte 3 and its side in the high half of
	// byte 2 (1 north, 2 west, 4 east, 8 south). Each floor is a 0x4AC-byte section: a 0x28-byte
	// header and 24x24 u16 cell types (0 = no floor). Checked against Lost Forest 1F's minimap.
	static final class Dungeon {
		static final int N = 1, E = 2, S = 4, W = 8;
		final int[][] walls;  // per floor, per cell (row by row), the sides with walls
		final int[][] doors;  // per floor, per cell, the sides with doors

		Dungeon(int[][] walls, int[][] doors) {
			this.walls = walls;
			this.doors = doors;
		}
	}

	private static final SparseArray<Dungeon> dungeons = new SparseArray<>();
	private static final Set<Integer> dungeonsLoading = new HashSet<>();
	private static final Set<Integer> dungeonsMissing = new HashSet<>();

	// The dungeon's walls and doors, or null until they're read.
	static Dungeon dungeon(int id) {
		return dungeons.get(id);
	}

	static void loadDungeon(DuoModContext host, int id, Callback cb) {
		if (id <= 0 || id > 0xFF || dungeons.get(id) != null || dungeonsLoading.contains(id) || dungeonsMissing.contains(id)) {
			return;
		}
		String name = String.format("d%02x", id);
		String path = "disc0:/PSP_GAME/USRDIR/pack/dng/" + name + "/" + name + "m.bin";
		dungeonsLoading.add(id);
		boolean ok = host.readGameFile(path, 0, 0x10000, d -> {
			dungeonsLoading.remove(id);
			Dungeon dg = null;
			try {
				dg = d == null ? null : parseDungeon(d);
			} catch (IndexOutOfBoundsException e) {
				// Not the layout above.
			}
			if (dg == null) {
				if (d != null) {
					dungeonsMissing.add(id);
				}
				Log.w(TAG, "Persona: can't read " + path);
				return;
			}
			dungeons.put(id, dg);
			cb.onReady();
		});
		if (!ok) {
			dungeonsLoading.remove(id);
		}
	}

	private static Dungeon parseDungeon(byte[] d) {
		int n = u32(d, 0);
		if (n < 3 || n > 64) {
			return null;
		}
		int o = (4 + 4 * n + 15) / 16 * 16;
		int types = 0, typesSize = 0;
		java.util.List<int[]> grids = new java.util.ArrayList<>();
		for (int i = 0; i < n; i++) {
			int size = u32(d, 4 + 4 * i);
			if (i == 1) {
				types = o;
				typesSize = size;
			} else if (size == 0x4AC) {
				int[] g = new int[24 * 24];
				for (int k = 0; k < g.length; k++) {
					g[k] = (d[o + 0x28 + 2 * k] & 0xFF) | (d[o + 0x29 + 2 * k] & 0xFF) << 8;
				}
				grids.add(g);
			}
			o += size;
		}
		if (grids.isEmpty() || o > d.length) {
			return null;
		}
		int[][] walls = new int[grids.size()][], doors = new int[grids.size()][];
		for (int f = 0; f < grids.size(); f++) {
			int[] g = grids.get(f);
			walls[f] = new int[g.length];
			doors[f] = new int[g.length];
			for (int k = 0; k < g.length; k++) {
				int t = g[k];
				if (t == 0) {
					continue;
				}
				int c = k % 24, r = k / 24;
				int w = (empty(g, c, r - 1) ? Dungeon.N : 0) | (empty(g, c + 1, r) ? Dungeon.E : 0)
					| (empty(g, c, r + 1) ? Dungeon.S : 0) | (empty(g, c - 1, r) ? Dungeon.W : 0);
				int rec = types + 0x14 + (t - 1) * 12;
				if (rec + 8 <= types + typesSize) {
					int b2 = d[rec + 2] & 0xFF, b3 = d[rec + 3] & 0xFF;
					int side = b2 >> 4;
					if ((b3 & 0xC0) == 0 && ((b3 & 0x0F) == 4 || (b3 & 0x0F) == 5) && side != 0) {
						doors[f][k] = ((side & 1) != 0 ? Dungeon.N : 0) | ((side & 2) != 0 ? Dungeon.W : 0)
							| ((side & 4) != 0 ? Dungeon.E : 0) | ((side & 8) != 0 ? Dungeon.S : 0);
					} else {
						// A door cell's pieces are the door's (on both of its sides), not walls. A room's
						// entrance cell (two room cells or more around it) has a frame, not walls,
						// towards the room.
						boolean rn = room(d, types, typesSize, g, c, r - 1), rs = room(d, types, typesSize, g, c, r + 1);
						boolean re = room(d, types, typesSize, g, c + 1, r), rw = room(d, types, typesSize, g, c - 1, r);
						boolean inRoom = (rn ? 1 : 0) + (rs ? 1 : 0) + (re ? 1 : 0) + (rw ? 1 : 0) >= 2;
						w |= (d[rec + 4] != 0 && !(inRoom && rn) ? Dungeon.N : 0)
							| (d[rec + 5] != 0 && !(inRoom && rs) ? Dungeon.S : 0)
							| (d[rec + 6] != 0 && !(inRoom && re) ? Dungeon.E : 0)
							| (d[rec + 7] != 0 && !(inRoom && rw) ? Dungeon.W : 0);
					}
				}
				walls[f][k] = w;
			}
		}
		return new Dungeon(walls, doors);
	}

	// A room cell: floor whose type has no wall pieces at all.
	private static boolean room(byte[] d, int types, int typesSize, int[] g, int c, int r) {
		if (empty(g, c, r)) {
			return false;
		}
		int rec = types + 0x14 + (g[r * 24 + c] - 1) * 12;
		return rec + 8 <= types + typesSize && d[rec + 4] == 0 && d[rec + 5] == 0 && d[rec + 6] == 0 && d[rec + 7] == 0;
	}

	private static boolean empty(int[] g, int c, int r) {
		return c < 0 || r < 0 || c >= 24 || r >= 24 || g[r * 24 + c] == 0;
	}

	// Cuts the transparent border off.
	private static Bitmap trim(Bitmap b) {
		if (b == null) {
			return null;
		}
		int w = b.getWidth(), h = b.getHeight();
		int[] px = new int[w * h];
		b.getPixels(px, 0, w, 0, 0, w, h);
		int l = w, t = h, r = -1, btm = -1;
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				if (Color.alpha(px[y * w + x]) > 8) {
					l = Math.min(l, x);
					r = Math.max(r, x);
					t = Math.min(t, y);
					btm = Math.max(btm, y);
				}
			}
		}
		if (r < 0) {
			return b;
		}
		return Bitmap.createBitmap(b, l, t, r - l + 1, btm - t + 1);
	}

	private static byte[] slice(byte[] d, int o, int n) {
		byte[] out = new byte[n];
		System.arraycopy(d, o, out, 0, n);
		return out;
	}

	private static int u32(byte[] b, int o) {
		return (b[o] & 0xFF) | (b[o + 1] & 0xFF) << 8 | (b[o + 2] & 0xFF) << 16 | (b[o + 3] & 0xFF) << 24;
	}
}
