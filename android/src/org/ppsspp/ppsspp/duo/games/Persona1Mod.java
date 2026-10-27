package org.ppsspp.ppsspp.duo.games;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;

import org.ppsspp.ppsspp.R;
import org.ppsspp.ppsspp.duo.DuoMod;
import org.ppsspp.ppsspp.duo.DuoModContext;
import org.ppsspp.ppsspp.duo.DuoStatus;

import java.util.Arrays;

// Persona (PSP): a card per party member and, in battles, per enemy, with the portrait (see
// Persona1Art), name, HP, SP and level, laid out like Shin Megami Tensei IV's battle cards.
//
// Found on ULUS10432 v1.00 (four battles in the Lost Forest, from the player's savestates). All of it
// is in the main module's data (0x08804000-0x08D18900), so the addresses are fixed:
// - 0x08B967BC: 8 on the field and on the result screen; in a battle 0, or 13 while talking to a
//   demon (Contact). A battle is shown while it isn't 8 and an enemy is alive.
// - Battle units at 0x08D03DC8 + n * 0x118 (n 0-4 the party in HUD order, 5-12 the enemies): u32 HP,
//   max HP, SP, max SP, the name at +0x40, u16 id at +0x3E (the character, 1 the hero to 9 Reiji, or
//   the demon) and u8 level at +0x6A (Analyze showed the same for two demons). A new battle clears
//   the unused enemy slots; the last battle's enemies stay (with 0 HP) after it. The HUD reads these
//   (writing HP moved the HUD).
// - The party at 0x08CC51F8 + n * 0x80, same fields. Up to date on the field, behind in a battle.
// - Names: big-endian u16 letters, 0xFFFF at the end; uppercase and digits ASCII + 0x9F, lowercase
//   ASCII + 0xA0, 0x8140 a space.
// - In a dungeon: f32 facing at 0x08CCD8CC (degrees, 0 north, 180 south, -90 east). The dungeon
//   object is at *0x08CCBD5C: u8 column at +0x2A and row at +0x2C (east and south), u16 dungeon at
//   +0x46 (0x11 in the Lost Forest, whose files are pack/dng/d11) and u16 floor at +0x48.
// - The cells walked through: 72-byte blocks of 24 rows by 3 bytes from 0x08CC689C, the most
//   significant bit first, one per floor; a cell is marked once it's left. The block is the u8 at
//   0x08A92AD4 + dungeon, plus the floor (read from the game's marking code at 0x0884C3DC).
public final class Persona1Mod extends DuoMod {
	public static final String ID = "persona1";
	private static final String TAG = "PPSSPPDuo";

	private static final String[] GAME_IDS = {"ULUS10432"};

	private static final int FLAG = 0x08B967BC;
	private static final int FIELD = 8;
	private static final int PARTY = 0x08CC51F8;
	private static final int PARTY_SIZE = 0x80;
	private static final int UNITS = 0x08D03DC8;
	private static final int UNIT_SIZE = 0x118;
	private static final int PARTY_COUNT = 5;
	private static final int ENEMY_COUNT = 8;
	private static final int ID_OFFSET = 0x3E;
	private static final int NAME = 0x40;
	private static final int NAME_LEN = 0x24;
	private static final int LEVEL = 0x6A;
	private static final int FACING = 0x08CCD8CC;
	private static final int DUNGEON_PTR = 0x08CCBD5C;
	private static final int DUNGEON_SIZE = 0x50;
	private static final int BLOCK_TABLE = 0x08A92AD4;
	private static final int BLOCK_TABLE_SIZE = 0x40;
	private static final int EXPLORED = 0x08CC689C;
	private static final int MAP_SIZE = 24;
	private static final int BLOCK = MAP_SIZE * 3;
	private static final int BLOCK_COUNT = 0xA8;  // the table's last entry (0x94) and its floors

	private DuoModContext host;
	private CardsView view;
	private long lastBattle;
	private boolean loggedBattle;
	private int dungeon;  // the dungeon object being watched, 0 until the pointer is read
	private int loggedBlock = -1;

	static final class MapState {
		final byte[] cells = new byte[BLOCK];
		int x, y;  // the player's column and row
		float angle;
		int[] walls, doors;  // per cell, the sides (Persona1Art.Dungeon), or null until the map is read

		boolean explored(int c, int r) {
			if (c < 0 || r < 0 || c >= MAP_SIZE || r >= MAP_SIZE) {
				return false;
			}
			// The cell the player stands on isn't marked until it's left.
			return (c == x && r == y) || (cells[r * 3 + c / 8] & (0x80 >> (c % 8))) != 0;
		}

		boolean same(MapState m) {
			return m != null && x == m.x && y == m.y && angle == m.angle && Arrays.equals(cells, m.cells)
				&& walls == m.walls;
		}
	}

	static final class Unit {
		String name;
		int id, level, hp, maxHp, sp, maxSp;
		boolean enemy;

		boolean same(Unit u) {
			return u != null && id == u.id && level == u.level && hp == u.hp && maxHp == u.maxHp && sp == u.sp
				&& maxSp == u.maxSp && name.equals(u.name);
		}

		Bitmap portrait() {
			return enemy ? Persona1Art.demon(id) : Persona1Art.face(id);
		}
	}

	@Override
	public String getId() {
		return ID;
	}

	@Override
	public String getTitle(Context context) {
		return context.getString(R.string.duo_mod_persona1);
	}

	@Override
	public String getDescription(Context context) {
		return context.getString(R.string.duo_mod_persona1_desc);
	}

	@Override
	public int getPriority(DuoStatus s) {
		return Arrays.asList(GAME_IDS).contains(s.gameId) ? 100 : -1;
	}

	@Override
	public long getStatusIntervalMs() {
		return 100;
	}

	@Override
	public View onCreateView(DuoModContext host) {
		this.host = host;
		view = new CardsView(host.getContext());
		dungeon = 0;
		watch();
		Persona1Art.loadFaces(host, this::artReady);
		return view;
	}

	// Watch 7 is the dungeon object, pointed at the pointer until that's read.
	private void watch() {
		host.setMemoryWatches(
			new int[] {FLAG, PARTY, UNITS, FACING, EXPLORED, DUNGEON_PTR, BLOCK_TABLE, dungeon != 0 ? dungeon : DUNGEON_PTR},
			new int[] {4, PARTY_SIZE * PARTY_COUNT, UNIT_SIZE * (PARTY_COUNT + ENEMY_COUNT), 4, BLOCK * BLOCK_COUNT, 4,
				BLOCK_TABLE_SIZE, DUNGEON_SIZE});
	}

	private void artReady() {
		if (view != null) {
			view.invalidate();
		}
	}

	@Override
	public void onDestroyView() {
		view = null;
		host = null;
	}

	@Override
	public void onStatus(DuoStatus s) {
		if (view == null || (!s.isInGame() && !s.isPauseMenu())) {
			return;
		}
		byte[] flag = host.readMemoryWatch(0), party = host.readMemoryWatch(1), units = host.readMemoryWatch(2);
		if (flag == null || party == null || units == null) {
			return;
		}
		Unit[] enemies = null;
		if (u32(flag, 0) != FIELD) {
			enemies = readUnits(units, PARTY_COUNT, ENEMY_COUNT, UNIT_SIZE, true);
			boolean alive = false;
			for (Unit e : enemies) {
				alive |= e.hp > 0;
			}
			if (!alive) {
				enemies = null;
			}
		}
		if (enemies != null) {
			lastBattle = SystemClock.uptimeMillis();
			if (!loggedBattle) {
				Log.i(TAG, "Persona: battle with " + enemies.length + " enemies, first " + enemies[0].name
					+ " (id " + enemies[0].id + ", Lv " + enemies[0].level + ")");
				loggedBattle = true;
			}
			for (Unit e : enemies) {
				Persona1Art.loadDemon(host, e.id, this::artReady);
			}
			view.set(readUnits(units, 0, PARTY_COUNT, UNIT_SIZE, false), enemies, null);
		} else if (SystemClock.uptimeMillis() - lastBattle > 1500) {
			// The last enemy's HP reaches 0 before the battle ends: keep its picture for a moment.
			loggedBattle = false;
			view.set(readUnits(party, 0, PARTY_COUNT, PARTY_SIZE, false), null, readMap());
		}
	}

	// The map around the player, or null outside dungeons (or before anything there is marked).
	private MapState readMap() {
		byte[] facing = host.readMemoryWatch(3), explored = host.readMemoryWatch(4), ptr = host.readMemoryWatch(5);
		byte[] table = host.readMemoryWatch(6), d = host.readMemoryWatch(7);
		if (ptr == null) {
			return null;
		}
		int p = u32(ptr, 0);
		if (p != dungeon) {
			// A new dungeon object (or none): watch it from the next frame.
			dungeon = p >= 0x08800000 && p < 0x0A000000 - DUNGEON_SIZE ? p : 0;
			watch();
			return null;
		}
		if (dungeon == 0 || facing == null || explored == null || table == null || d == null) {
			return null;
		}
		int id = u16(d, 0x46), floor = u16(d, 0x48);
		if (id >= BLOCK_TABLE_SIZE || floor > 15) {
			return null;
		}
		int block = (table[id] & 0xFF) + floor;
		MapState m = new MapState();
		m.angle = Float.intBitsToFloat(u32(facing, 0));
		m.x = d[0x2A] & 0xFF;
		m.y = d[0x2C] & 0xFF;
		if (block >= BLOCK_COUNT || m.x >= MAP_SIZE || m.y >= MAP_SIZE || Float.isNaN(m.angle)) {
			return null;
		}
		if (block != loggedBlock) {
			Log.i(TAG, "Persona: dungeon " + Integer.toHexString(id) + " floor " + floor + ", map block " + block);
			loggedBlock = block;
		}
		System.arraycopy(explored, block * BLOCK, m.cells, 0, BLOCK);
		Persona1Art.Dungeon dg = Persona1Art.dungeon(id);
		if (dg == null) {
			Persona1Art.loadDungeon(host, id, this::artReady);
		} else if (floor < dg.walls.length) {
			m.walls = dg.walls[floor];
			m.doors = dg.doors[floor];
		}
		return m;
	}

	private static int u16(byte[] b, int o) {
		return (b[o] & 0xFF) | (b[o + 1] & 0xFF) << 8;
	}

	private static Unit[] readUnits(byte[] b, int first, int count, int size, boolean enemy) {
		Unit[] tmp = new Unit[count];
		int n = 0;
		for (int i = 0; i < count; i++) {
			int o = (first + i) * size;
			Unit u = new Unit();
			u.hp = u32(b, o);
			u.maxHp = u32(b, o + 4);
			u.sp = u32(b, o + 8);
			u.maxSp = u32(b, o + 0xC);
			if (u.maxHp <= 0 || u.maxHp > 9999 || u.hp < 0 || u.hp > u.maxHp) {
				continue;
			}
			u.id = (b[o + ID_OFFSET] & 0xFF) | (b[o + ID_OFFSET + 1] & 0xFF) << 8;
			u.level = b[o + LEVEL] & 0xFF;
			u.name = name(b, o + NAME);
			u.enemy = enemy;
			tmp[n++] = u;
		}
		return Arrays.copyOf(tmp, n);
	}

	private static int u32(byte[] b, int o) {
		return (b[o] & 0xFF) | (b[o + 1] & 0xFF) << 8 | (b[o + 2] & 0xFF) << 16 | (b[o + 3] & 0xFF) << 24;
	}

	static String name(byte[] b, int o) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i + 1 < NAME_LEN && o + i + 1 < b.length; i += 2) {
			int c = (b[o + i] & 0xFF) << 8 | (b[o + i + 1] & 0xFF);
			if (c == 0 || c == 0xFFFF) {
				break;
			}
			if (c == 0x8140) {
				sb.append(' ');
			} else if (c >= 0x101 && c <= 0x11A) {
				sb.append((char) (c - 0xA0));
			} else if (c >= 0x9F + 0x20 && c < 0x100) {
				sb.append((char) (c - 0x9F));
			}
		}
		return sb.toString();
	}

	// SMT IV's battle cards: a dark name bar, HP and SP rows with slanted labels and a thin bar under
	// the number, the portrait below on a pale backdrop, and the level in the bottom left corner.
	private static final class CardsView extends View {
		private static final int BG = 0xFF000000;
		private static final int FRAME = 0xFF0A0A0A;
		private static final int FRAME_EDGE = 0xFF6E6E74;
		private static final int NAME_TOP = 0xFF4A4A50;
		private static final int NAME_BOTTOM = 0xFF26262A;
		private static final int ROW = 0xFF1C1C20;
		private static final int TEXT = 0xFFFFFFFF;
		private static final int DIM = 0xFF9A9AA2;
		private static final int HP = 0xFFE8D84A;
		private static final int SP = 0xFF55C8F5;  // light blue, as the game's SP bar
		private static final int BAR_BG = 0xFF3A3A40;
		private static final int LOW = 0xFFE5392F;
		private static final int PARTY_SKY = 0xFFDCE6F0;
		private static final int PARTY_GROUND = 0xFF8FA6BD;
		private static final int ENEMY_SKY = 0xFFE8DCD4;
		private static final int ENEMY_GROUND = 0xFF9C8478;
		private static final int MAP_FLOOR = 0xFF5A4318;
		private static final int MAP_EDGE = 0xFFE0A030;
		private static final int MAP_GRID = 0xFF141414;
		private static final int PLAYER = 0xFFFFE680;
		private static final int DOOR = 0xFFFFD84A;
		private static final int DOWN_SKY = 0xFF8A8A8E;
		private static final int DOWN_GROUND = 0xFF4A4A4E;
		private static final int[] SIDES = {
			Persona1Art.Dungeon.N, Persona1Art.Dungeon.E, Persona1Art.Dungeon.S, Persona1Art.Dungeon.W};
		private static final long SHAKE_MS = 450;

		private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
		private final Paint art = new Paint();
		private final Paint artDown = new Paint();
		private final RectF rect = new RectF();
		private final RectF dst = new RectF();
		private final Path path = new Path();
		private Unit[] party = new Unit[0];
		private Unit[] enemies;
		private MapState map;
		// When each card last lost HP, for the shake.
		private final long[] partyHit = new long[PARTY_COUNT];
		private final long[] enemyHit = new long[ENEMY_COUNT];

		CardsView(Context context) {
			super(context);
			setBackgroundColor(BG);
			paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
			// The portraits are small pixel art: scale them without blurring.
			art.setFilterBitmap(false);
			artDown.setFilterBitmap(false);
			ColorMatrix gray = new ColorMatrix();
			gray.setSaturation(0);
			artDown.setColorFilter(new ColorMatrixColorFilter(gray));
		}

		void set(Unit[] p, Unit[] e, MapState m) {
			if (same(party, p) && (e == null ? enemies == null : same(enemies, e))
				&& (m == null ? map == null : m.same(map))) {
				return;
			}
			long now = SystemClock.uptimeMillis();
			hits(party, p, partyHit, now);
			hits(enemies, e, enemyHit, now);
			party = p;
			enemies = e;
			map = m;
			invalidate();
		}

		// Marks the cards whose unit (same slot, same name) has less HP than before.
		private static void hits(Unit[] before, Unit[] after, long[] hit, long now) {
			if (before == null || after == null) {
				return;
			}
			for (int i = 0; i < Math.min(Math.min(before.length, after.length), hit.length); i++) {
				if (after[i].hp < before[i].hp && after[i].name.equals(before[i].name)) {
					hit[i] = now;
				}
			}
		}

		private static boolean same(Unit[] a, Unit[] b) {
			if (a == null || b == null || a.length != b.length) {
				return false;
			}
			for (int i = 0; i < a.length; i++) {
				if (!a[i].same(b[i])) {
					return false;
				}
			}
			return true;
		}

		@Override
		protected void onDraw(Canvas canvas) {
			float w = getWidth(), h = getHeight();
			float u = Math.min(w / 1240f, h / 1080f);
			float m = 16 * u;
			if (party.length == 0) {
				text(canvas, getContext().getString(R.string.duo_persona1_waiting), w / 2, h / 2, 30 * u, DIM, Paint.Align.CENTER);
				return;
			}
			boolean shaking;
			if (enemies == null && map != null) {
				float stripH = 150 * u;
				drawMap(canvas, map, m, m, w - m, h - stripH - 2 * m, u);
				shaking = drawStrip(canvas, party, m, h - stripH - m, w - m, h - m, u);
			} else if (enemies == null) {
				// One row, as tall as the cards' width allows.
				float cw = (w - m * (PARTY_COUNT + 1)) / PARTY_COUNT;
				float ch = Math.min(h - 2 * m, cw * 2.1f);
				shaking = drawRow(canvas, party, partyHit, PARTY_COUNT, m, (h - ch) / 2, w - m, ch, u);
			} else {
				float ch = (h - 3 * m) / 2;
				shaking = drawRow(canvas, party, partyHit, PARTY_COUNT, m, m, w - m, ch, u);
				shaking |= drawRow(canvas, enemies, enemyHit, Math.max(PARTY_COUNT, enemies.length), m, 2 * m + ch, w - m, ch, u);
			}
			if (shaking) {
				postInvalidateOnAnimation();
			}
		}

		// Horizontal offset of a card hit `since` ms ago, 0 once the shake is over.
		private static float shake(long since, float u) {
			if (since < 0 || since >= SHAKE_MS) {
				return 0;
			}
			float f = 1f - since / (float) SHAKE_MS;
			return (float) Math.sin(since * 0.09) * 14 * u * f;
		}

		// The cells walked through, the player as an arrow pointing where they face.
		private void drawMap(Canvas c, MapState mp, float l, float t, float r, float b, float u) {
			float size = Math.min(r - l, b - t);
			float cell = size / MAP_SIZE;
			float ox = (l + r - size) / 2, oy = t + (b - t - size) / 2;
			for (int y = 0; y < MAP_SIZE; y++) {
				for (int x = 0; x < MAP_SIZE; x++) {
					int cx = x, cy = y;
					float x0 = ox + x * cell, y0 = oy + y * cell;
					if (!mp.explored(cx, cy)) {
						fill(c, x0 + 1, y0 + 1, x0 + cell - 1, y0 + cell - 1, MAP_GRID);
						continue;
					}
					fill(c, x0, y0, x0 + cell, y0 + cell, MAP_FLOOR);
					float e = Math.max(2, 3 * u);
					for (int side = 0; side < 4; side++) {
						int bit = SIDES[side];
						int nx = cx + (bit == Persona1Art.Dungeon.E ? 1 : bit == Persona1Art.Dungeon.W ? -1 : 0);
						int ny = cy + (bit == Persona1Art.Dungeon.S ? 1 : bit == Persona1Art.Dungeon.N ? -1 : 0);
						boolean wall, door = false;
						if (mp.walls == null) {
							// Until the map is read: an edge where the walked cells end.
							wall = !mp.explored(nx, ny);
						} else {
							int k = cy * MAP_SIZE + cx, nk = ny * MAP_SIZE + nx;
							boolean inside = nx >= 0 && ny >= 0 && nx < MAP_SIZE && ny < MAP_SIZE;
							int opposite = SIDES[(side + 2) % 4];
							door = (mp.doors[k] & bit) != 0 || (inside && (mp.doors[nk] & opposite) != 0);
							wall = !door && ((mp.walls[k] & bit) != 0 || (inside && (mp.walls[nk] & opposite) != 0));
						}
						if (wall) {
							edge(c, x0, y0, cell, bit, 0, e, MAP_EDGE);
						} else if (door) {
							// A bar across the middle of the edge, as the game's minimap draws doors.
							edge(c, x0, y0, cell, bit, cell * 0.22f, e * 1.8f, DOOR);
						}
					}
				}
			}
			float px = ox + (mp.x + 0.5f) * cell, py = oy + (mp.y + 0.5f) * cell;
			c.save();
			// The game's angle turns counterclockwise from north (-90 is east).
			c.rotate(-mp.angle, px, py);
			path.reset();
			path.moveTo(px, py - cell * 0.42f);
			path.lineTo(px + cell * 0.34f, py + cell * 0.34f);
			path.lineTo(px, py + cell * 0.16f);
			path.lineTo(px - cell * 0.34f, py + cell * 0.34f);
			path.close();
			paint.setColor(PLAYER);
			c.drawPath(path, paint);
			c.restore();
		}

		// A strip along one side of the cell, inset from the corners, thick e.
		private void edge(Canvas c, float x0, float y0, float cell, int side, float inset, float e, int color) {
			float x1 = x0 + cell, y1 = y0 + cell;
			if (side == Persona1Art.Dungeon.N) {
				fill(c, x0 + inset, y0, x1 - inset, y0 + e, color);
			} else if (side == Persona1Art.Dungeon.S) {
				fill(c, x0 + inset, y1 - e, x1 - inset, y1, color);
			} else if (side == Persona1Art.Dungeon.W) {
				fill(c, x0, y0 + inset, x0 + e, y1 - inset, color);
			} else {
				fill(c, x1 - e, y0 + inset, x1, y1 - inset, color);
			}
		}

		// The party in one row: face, name, HP and SP. Returns whether a card is still shaking.
		private boolean drawStrip(Canvas c, Unit[] units, float l, float t, float r, float b, float u) {
			float gap = 8 * u;
			float cw = (r - l - gap * (PARTY_COUNT - 1)) / PARTY_COUNT;
			float h = b - t;
			float s = h / 150f;
			long now = SystemClock.uptimeMillis();
			boolean shaking = false;
			for (int i = 0; i < units.length; i++) {
				Unit x = units[i];
				float dx = i < partyHit.length ? shake(now - partyHit[i], u) : 0;
				shaking |= dx != 0;
				float x0 = l + i * (cw + gap) + dx, x1 = x0 + cw;
				boolean down = x.hp == 0;
				fill(c, x0, t, x1, b, FRAME);
				stroke(c, x0 + 1, t + 1, x1 - 1, b - 1, FRAME_EDGE, 2 * s);
				float faceW = h * 0.64f;
				Bitmap p = x.portrait();
				if (p != null) {
					c.save();
					c.clipRect(x0 + 3 * s, t + 3 * s, x0 + 3 * s + faceW, b - 3 * s);
					float sc = Math.max(faceW / p.getWidth(), (h - 6 * s) / p.getHeight());
					float dw = p.getWidth() * sc, dh = p.getHeight() * sc;
					dst.set(x0 + 3 * s + (faceW - dw) / 2, b - 3 * s - dh, x0 + 3 * s + (faceW + dw) / 2, b - 3 * s);
					c.drawBitmap(p, null, dst, down ? artDown : art);
					c.restore();
				}
				float tl = x0 + faceW + 10 * s, tr = x1 - 8 * s;
				fitText(c, x.name, tl, t + 34 * s, 26 * s, tr - tl, down ? DIM : TEXT, Paint.Align.LEFT);
				stat(c, "HP", x.hp, x.maxHp, HP, tl - 4 * s, t + 46 * s, tr + 4 * s, 44 * s, s * 0.9f);
				stat(c, "SP", x.sp, x.maxSp, SP, tl - 4 * s, t + 94 * s, tr + 4 * s, 44 * s, s * 0.9f);
			}
			return shaking;
		}

		// Cards of `slots` width, centred in the row.
		// Returns whether a card is still shaking.
		private boolean drawRow(Canvas c, Unit[] units, long[] hit, int slots, float l, float t, float r, float ch, float u) {
			float gap = 10 * u;
			float cw = Math.min((r - l - gap * (slots - 1)) / slots, ch * 0.62f);
			float total = units.length * cw + (units.length - 1) * gap;
			float x = (l + r - total) / 2;
			long now = SystemClock.uptimeMillis();
			boolean shaking = false;
			for (int i = 0; i < units.length; i++) {
				long since = i < hit.length ? now - hit[i] : SHAKE_MS;
				float dx = shake(since, u);
				shaking |= dx != 0;
				drawCard(c, units[i], x + dx, t, cw, ch, u);
				if (since >= 0 && since < SHAKE_MS) {
					// A red flash fading with the shake.
					int a = (int) (110 * (1f - since / (float) SHAKE_MS));
					fill(c, x + dx, t, x + dx + cw, t + ch, (a << 24) | 0xE5392F);
					shaking = true;
				}
				x += cw + gap;
			}
			return shaking;
		}

		private void drawCard(Canvas c, Unit x, float l, float t, float cw, float ch, float u) {
			boolean down = x.hp == 0;
			float r = l + cw, b = t + ch;
			float s = cw / 230f;  // the card's own scale, from its width
			float border = Math.max(2, 3 * s);

			fill(c, l, t, r, b, FRAME);
			stroke(c, l + border / 2, t + border / 2, r - border / 2, b - border / 2, FRAME_EDGE, border * 0.6f);
			float il = l + border, ir = r - border, it = t + border;

			// Name bar.
			float nameH = 40 * s;
			paint.setShader(new LinearGradient(0, it, 0, it + nameH, NAME_TOP, NAME_BOTTOM, Shader.TileMode.CLAMP));
			c.drawRect(il, it, ir, it + nameH, paint);
			paint.setShader(null);
			fitText(c, x.name, (il + ir) / 2, it + nameH * 0.72f, 26 * s, ir - il - 12 * s, down ? DIM : TEXT, Paint.Align.CENTER);

			// HP and SP rows.
			float rowH = 38 * s;
			float y = it + nameH;
			stat(c, "HP", x.hp, x.maxHp, HP, il, y, ir, rowH, s);
			y += rowH;
			stat(c, "SP", x.sp, x.maxSp, SP, il, y, ir, rowH, s);
			y += rowH;

			// Portrait on a pale backdrop.
			float pt = y + border * 0.5f, pb = b - border;
			// A knocked out unit: the portrait in black and white on a gray backdrop.
			paint.setShader(new LinearGradient(0, pt, 0, pb, down ? DOWN_SKY : x.enemy ? ENEMY_SKY : PARTY_SKY,
				down ? DOWN_GROUND : x.enemy ? ENEMY_GROUND : PARTY_GROUND, Shader.TileMode.CLAMP));
			c.drawRect(il, pt, ir, pb, paint);
			paint.setShader(null);
			Bitmap p = x.portrait();
			if (p != null) {
				c.save();
				c.clipRect(il, pt, ir, pb);
				float aw = ir - il, ah = pb - pt;
				float scale;
				if (x.enemy) {
					// The whole demon, in the middle of the backdrop.
					scale = Math.min(aw * 0.92f / p.getWidth(), ah * 0.9f / p.getHeight());
					float dw = p.getWidth() * scale, dh = p.getHeight() * scale;
					dst.set(il + (aw - dw) / 2, pt + (ah - dh) / 2, il + (aw + dw) / 2, pt + (ah + dh) / 2);
				} else {
					// The bust covering the backdrop, shoulders on the bottom edge.
					scale = Math.max(aw / p.getWidth(), ah / p.getHeight());
					float dw = p.getWidth() * scale, dh = p.getHeight() * scale;
					dst.set(il + (aw - dw) / 2, pb - dh, il + (aw + dw) / 2, pb);
				}
				c.drawBitmap(p, null, dst, down ? artDown : art);
				c.restore();
			}

			// Level in the bottom left corner.
			if (x.level > 0) {
				float lh = 34 * s;
				path.reset();
				path.moveTo(il, pb - lh);
				path.lineTo(il + 96 * s, pb - lh);
				path.lineTo(il + 80 * s, pb);
				path.lineTo(il, pb);
				path.close();
				paint.setColor(0xD8101014);
				c.drawPath(path, paint);
				text(c, "Lv.", il + 8 * s, pb - 9 * s, 16 * s, DIM, Paint.Align.LEFT);
				text(c, Integer.toString(x.level), il + 38 * s, pb - 7 * s, 28 * s, TEXT, Paint.Align.LEFT);
			}
		}

		// A row: slanted label, the value right-aligned, a thin bar along the bottom.
		private void stat(Canvas c, String label, int v, int max, int color, float l, float t, float r, float h, float s) {
			fill(c, l, t, r, t + h, ROW);
			float lw = 34 * s, lh = 16 * s, slant = 6 * s;
			float lt = t + 5 * s;
			path.reset();
			path.moveTo(l + 4 * s, lt);
			path.lineTo(l + 4 * s + lw + slant, lt);
			path.lineTo(l + 4 * s + lw, lt + lh);
			path.lineTo(l + 4 * s, lt + lh);
			path.close();
			paint.setColor(color);
			c.drawPath(path, paint);
			text(c, label, l + 8 * s, lt + lh - 3 * s, 13 * s, 0xFF101010, Paint.Align.LEFT);

			boolean low = max > 0 && v * 4 <= max && label.equals("HP");
			text(c, Integer.toString(v), r - 8 * s, t + h - 9 * s, 26 * s, low ? LOW : TEXT, Paint.Align.RIGHT);

			float bt = t + h - 6 * s, bb = t + h - 2 * s;
			fill(c, l + 4 * s, bt, r - 4 * s, bb, BAR_BG);
			float f = max > 0 ? Math.max(0f, Math.min(1f, v / (float) max)) : 0f;
			if (f > 0) {
				fill(c, l + 4 * s, bt, l + 4 * s + (r - l - 8 * s) * f, bb, low ? LOW : color);
			}
		}

		private void fill(Canvas c, float l, float t, float r, float b, int color) {
			paint.setStyle(Paint.Style.FILL);
			paint.setColor(color);
			c.drawRect(l, t, r, b, paint);
		}

		private void stroke(Canvas c, float l, float t, float r, float b, int color, float width) {
			paint.setStyle(Paint.Style.STROKE);
			paint.setStrokeWidth(width);
			paint.setColor(color);
			c.drawRect(l, t, r, b, paint);
			paint.setStyle(Paint.Style.FILL);
		}

		// Text shrunk to fit maxWidth.
		private void fitText(Canvas c, String s, float x, float y, float size, float maxWidth, int color, Paint.Align align) {
			paint.setTextSize(size);
			float tw = paint.measureText(s);
			if (tw > maxWidth) {
				size *= maxWidth / tw;
			}
			text(c, s, x, y, size, color, align);
		}

		private void text(Canvas c, String s, float x, float y, float size, int color, Paint.Align align) {
			paint.setStyle(Paint.Style.FILL);
			paint.setShader(null);
			paint.setColor(color);
			paint.setTextSize(size);
			paint.setTextAlign(align);
			c.drawText(s, x, y, paint);
			paint.setTextAlign(Paint.Align.LEFT);
		}
	}
}
