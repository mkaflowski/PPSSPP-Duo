package org.ppsspp.ppsspp.duo.games;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;

import org.ppsspp.ppsspp.R;
import org.ppsspp.ppsspp.duo.DuoMod;
import org.ppsspp.ppsspp.duo.DuoModContext;
import org.ppsspp.ppsspp.duo.DuoStatus;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

// Persona 3 Portable: the party's HP and SP, and each enemy's level, arcana, HP and affinities
// (what the Analyze screen shows), during battles. Drawn like the Analyze screen, with the game's
// element icons, labels and HUD faces (see Persona3Art).
//
// Found on ULES01523 v1.01 (two battles in Monad from a downloaded save, one with the hero alone,
// one with a full party). Everything but the battle objects is in the main module's data:
// - 0x08C3BDB8: 0xFFFFFFFF outside battles. In a battle, the u32 at 0x08C3BDC0 points 0xFFC past
//   the battle's participants: u32 party size, a u32 (3 in both battles), four pointers to the party
//   members' wrappers (u32 0x00010000, pointer to the character), a pointer to the enemy group and
//   a u32 (0x000100E3 in both, left unchecked as it may be a battle type).
// - Characters: the hero at 0x08E63BF0 (given name 0x12 bytes earlier, two bytes a letter, 0x80 then
//   the letter + 0x60), the others at 0x08E68BB8 + (id - 2) * 0x118. +2 u16 id (1 hero, 2 Yukari,
//   3 Aigis, 4 Mitsuru, 5 Junpei, 7 Akihiko, 8 Ken, 9 Shinjiro, 10 Koromaru), +8 HP, +0xA SP; the
//   level at +6 (hero) or +0x8C (u8, the others). Max HP and SP aren't stored: they're the row for
//   the level in the table at 0x08BB9148 (0x2C a level, u16 HP and SP per id).
// - Enemy group: +2 u16 count, then 0x3C-byte units from +0xC: +2 u16 enemy id, +6 level, +8 HP,
//   +0xA SP.
// - The battle tables (data/battle/UNIT.TBL, loaded at boot): stats at 0x08C45B50, 0x3E per enemy
//   (+2 u8 arcana, Fool = 1; +4 u16 max HP, +6 max SP); affinities at 0x08C4ACB0, 0x22 per enemy,
//   u16 each for slash, strike, pierce, fire, ice, elec, wind, almighty, light, dark, then the
//   ailments: 0x100 null, 0x200 repel, 0x400 drain, 0x800 weak, 0x1000 strong (checked against
//   the Analyze screen). Names: 19-byte records at 0x08C4FDF0, enemy n at record 624 + n.
public final class Persona3Mod extends DuoMod {
	public static final String ID = "persona3";
	private static final String TAG = "PPSSPPDuo";

	private static final String[] GAME_IDS = {"ULES01523", "ULUS10512"};

	private static final int BATTLE = 0x08C3BDB8;
	private static final int PARTICIPANTS_BACK = 0xFFC;
	private static final int HERO_BLOCK = 0x08E63BC0;  // the hero's names, then the hero
	private static final int HERO = 0x08E63BF0;
	private static final int MEMBERS = 0x08E68BB8;
	private static final int MEMBER_SIZE = 0x118;
	private static final int MEMBER_COUNT = 9;
	private static final int LEVELS = 0x08BB9148;
	private static final int LEVEL_ROW = 0x2C;
	private static final int UNITS = 0x08C45B50;
	private static final int UNIT_SIZE = 0x3E;
	private static final int AFFINITIES = 0x08C4ACB0;
	private static final int AFFINITY_SIZE = 0x22;
	private static final int NAMES = 0x08C4FDF0 + 624 * 19;
	private static final int NAME_SIZE = 19;
	private static final int ENEMY_COUNT = 336;
	private static final int GROUP_SIZE = 0xC + 5 * 0x3C;

	static final int NULL = 0x100, REPEL = 0x200, DRAIN = 0x400, WEAK = 0x800, STRONG = 0x1000;
	// Element columns in the Analyze screen's order (almighty, column 7, isn't shown).
	static final int[] ELEMENTS = {0, 1, 2, 3, 4, 5, 6, 8, 9};

	private static final String[] MEMBER_NAMES = {
		"", "", "Yukari", "Aigis", "Mitsuru", "Junpei", "Fuuka", "Akihiko", "Ken", "Shinjiro", "Koromaru"};
	static final String[] ARCANA = {
		"", "Fool", "Magician", "Priestess", "Empress", "Emperor", "Hierophant", "Lovers", "Chariot",
		"Justice", "Hermit", "Fortune", "Strength", "Hanged Man", "Death", "Temperance", "Devil",
		"Tower", "Star", "Moon", "Sun", "Judgement", "Aeon"};

	// The battle tables, read from RAM once per boot (they don't change).
	private static byte[] levels, units, affinities, names;
	private int tableChecks;
	private String lastReject;

	private static Persona3Art art;
	private static boolean artLoading;

	private DuoModContext host;
	private BattleView view;
	private boolean watchingBattle;
	private int participants, group;
	private final int[] wrappers = new int[4];
	private long lastSeen;

	static final class Member {
		int id, hp, sp, maxHp, maxSp;
		String name;
	}

	static final class Enemy {
		int id, level, hp, sp, maxHp, arcana;
		String name;
		final int[] affinity = new int[10];
	}

	static final class Battle {
		Member[] party;
		Enemy[] enemies;
	}

	@Override
	public String getId() {
		return ID;
	}

	@Override
	public String getTitle(Context context) {
		return context.getString(R.string.duo_mod_persona3);
	}

	@Override
	public String getDescription(Context context) {
		return context.getString(R.string.duo_mod_persona3_desc);
	}

	@Override
	public int getPriority(DuoStatus s) {
		return Arrays.asList(GAME_IDS).contains(s.gameId) ? 100 : -1;
	}

	@Override
	public long getStatusIntervalMs() {
		return 200;
	}

	@Override
	public View onCreateView(DuoModContext host) {
		this.host = host;
		view = new BattleView(host.getContext());
		participants = group = 0;
		Arrays.fill(wrappers, 0);
		watchingBattle = false;
		if (levels == null) {
			// The tables first, then the battle.
			host.setMemoryWatches(
				new int[] {LEVELS, UNITS, UNITS + 0x4000, AFFINITIES, NAMES},
				new int[] {LEVEL_ROW * 99, 0x4000, UNIT_SIZE * ENEMY_COUNT - 0x4000, AFFINITY_SIZE * ENEMY_COUNT, NAME_SIZE * ENEMY_COUNT});
		} else {
			watchBattle();
		}
		loadArt();
		return view;
	}

	private void loadArt() {
		if (art != null || artLoading) {
			return;
		}
		artLoading = true;
		DuoStatus s = host.getStatus();
		File dir = new File(host.getContext().getFilesDir(), "duo/persona3/" + s.gameId + "_" + s.discVersion + "_2");
		Persona3Art.load(host, dir, a -> {
			artLoading = false;
			art = a;
			if (view != null) {
				view.invalidate();
			}
		});
	}

	// Watch 0 is the battle flag; the heap objects of a battle follow, pointed at the flag until known.
	private void watchBattle() {
		watchingBattle = true;
		int[] addr = new int[9], size = new int[9];
		addr[0] = BATTLE;
		size[0] = 0x10;
		addr[1] = HERO_BLOCK;
		size[1] = HERO - HERO_BLOCK + 0x10;
		addr[2] = MEMBERS;
		size[2] = MEMBER_SIZE * MEMBER_COUNT;
		addr[3] = participants != 0 ? participants : BATTLE;
		size[3] = 0x20;
		for (int i = 0; i < 4; i++) {
			addr[4 + i] = wrappers[i] != 0 ? wrappers[i] : BATTLE;
			size[4 + i] = 8;
		}
		addr[8] = group != 0 ? group : BATTLE;
		size[8] = GROUP_SIZE;
		host.setMemoryWatches(addr, size);
	}

	private static boolean isPointer(int p) {
		return p >= 0x08800000 && p < 0x0A000000;
	}

	@Override
	public void onStatus(DuoStatus s) {
		if (!s.isInGame() && !s.isPauseMenu()) {
			return;
		}
		if (!watchingBattle) {
			loadTables();
			return;
		}
		Battle battle = readBattle();
		if (battle == null) {
			// Between the battle's end and the field, or while the objects are being rebuilt: keep the
			// last picture for a moment.
			if (SystemClock.uptimeMillis() - lastSeen > 1500) {
				view.setBattle(null);
			}
			return;
		}
		lastSeen = SystemClock.uptimeMillis();
		view.setBattle(battle);
	}

	private void loadTables() {
		byte[] l = host.readMemoryWatch(0), u1 = host.readMemoryWatch(1), u2 = host.readMemoryWatch(2);
		byte[] a = host.readMemoryWatch(3), n = host.readMemoryWatch(4);
		if (l == null || u1 == null || u2 == null || a == null || n == null) {
			return;
		}
		byte[] u = new byte[u1.length + u2.length];
		System.arraycopy(u1, 0, u, 0, u1.length);
		System.arraycopy(u2, 0, u, u1.length, u2.length);
		names = n;
		// The tables are loaded after the title screen; a different release would have them elsewhere.
		if (!"Cowardly Maya".equals(enemyName(1)) || !"Acheron Seeker".equals(enemyName(144))) {
			names = null;
			if (++tableChecks == 300) {
				Log.w(TAG, "Persona 3: no battle tables at the known addresses yet");
			}
			return;
		}
		levels = l;
		units = u;
		affinities = a;
		watchBattle();
	}

	private static String enemyName(int id) {
		if (names == null || id < 0 || id >= ENEMY_COUNT) {
			return "";
		}
		int o = id * NAME_SIZE, n = 0;
		while (n < NAME_SIZE && names[o + n] != 0) {
			n++;
		}
		return new String(names, o, n, StandardCharsets.ISO_8859_1);
	}

	private static ByteBuffer le(byte[] d) {
		return ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
	}

	private Battle readBattle() {
		byte[] flag = host.readMemoryWatch(0);
		if (flag == null) {
			return null;
		}
		ByteBuffer f = le(flag);
		int ptr = f.getInt(8);
		if (f.getInt(0) == -1 || !isPointer(ptr)) {
			return reject(null);
		}
		int part = ptr - PARTICIPANTS_BACK;
		if (part != participants) {
			participants = part;
			watchBattle();
			return null;
		}
		byte[] pd = host.readMemoryWatch(3);
		if (pd == null) {
			return null;
		}
		ByteBuffer p = le(pd);
		int count = p.getInt(0);
		if (count < 1 || count > 4) {
			return reject("party size " + count);
		}
		boolean changed = false;
		for (int i = 0; i < 4; i++) {
			int w = i < count ? p.getInt(8 + i * 4) : 0;
			if (w != 0 && !isPointer(w)) {
				return reject("party pointer " + Integer.toHexString(w));
			}
			if (w != wrappers[i]) {
				wrappers[i] = w;
				changed = true;
			}
		}
		int g = p.getInt(0x18);
		if (!isPointer(g)) {
			return reject("enemy group pointer " + Integer.toHexString(g));
		}
		if (g != group) {
			group = g;
			changed = true;
		}
		if (changed) {
			watchBattle();
			return null;
		}

		byte[] hero = host.readMemoryWatch(1), members = host.readMemoryWatch(2), gd = host.readMemoryWatch(8);
		if (hero == null || members == null || gd == null) {
			return null;
		}
		Battle battle = new Battle();
		battle.party = new Member[count];
		for (int i = 0; i < count; i++) {
			byte[] wd = host.readMemoryWatch(4 + i);
			if (wd == null) {
				return null;
			}
			if (le(wd).getInt(0) != 0x00010000) {
				return reject("party wrapper " + Integer.toHexString(le(wd).getInt(0)));
			}
			Member m = member(le(wd).getInt(4), hero, members);
			if (m == null) {
				return reject("party member at " + Integer.toHexString(le(wd).getInt(4)));
			}
			battle.party[i] = m;
		}

		ByteBuffer gb = le(gd);
		int enemies = gb.getShort(2) & 0xFFFF;
		if (enemies < 1 || enemies > 5) {
			return reject("enemy count " + enemies);
		}
		battle.enemies = new Enemy[enemies];
		for (int i = 0; i < enemies; i++) {
			int o = 0xC + i * 0x3C;
			Enemy e = new Enemy();
			e.id = gb.getShort(o + 2) & 0xFFFF;
			if (e.id <= 0 || e.id >= ENEMY_COUNT) {
				return reject("enemy id " + e.id);
			}
			e.level = gb.getShort(o + 6) & 0xFFFF;
			e.hp = gb.getShort(o + 8) & 0xFFFF;
			e.sp = gb.getShort(o + 0xA) & 0xFFFF;
			ByteBuffer ub = le(units);
			e.arcana = units[e.id * UNIT_SIZE + 2] & 0xFF;
			e.maxHp = ub.getShort(e.id * UNIT_SIZE + 4) & 0xFFFF;
			ByteBuffer ab = le(affinities);
			for (int k = 0; k < 10; k++) {
				e.affinity[k] = ab.getShort(e.id * AFFINITY_SIZE + k * 2) & 0xFFFF;
			}
			e.name = enemyName(e.id);
			battle.enemies[i] = e;
		}
		lastReject = null;
		return battle;
	}

	// Logs why a battle that the flag announces can't be read, once per distinct reason.
	private Battle reject(String why) {
		if (why != null && !why.equals(lastReject)) {
			Log.w(TAG, "Persona 3: battle not readable: " + why);
		}
		lastReject = why;
		return null;
	}

	private static Member member(int ptr, byte[] hero, byte[] members) {
		ByteBuffer b;
		int o, level;
		if (ptr == HERO) {
			b = le(hero);
			o = HERO - HERO_BLOCK;
			level = b.getShort(o + 6) & 0xFFFF;
		} else {
			int k = ptr - MEMBERS;
			if (k < 0 || k % MEMBER_SIZE != 0 || k / MEMBER_SIZE >= MEMBER_COUNT) {
				return null;
			}
			b = le(members);
			o = k;
			level = members[o + 0x8C] & 0xFF;
		}
		Member m = new Member();
		m.id = b.getShort(o + 2) & 0xFFFF;
		if (m.id < 1 || m.id > 10) {
			return null;
		}
		m.hp = b.getShort(o + 8) & 0xFFFF;
		m.sp = b.getShort(o + 0xA) & 0xFFFF;
		level = Math.max(1, Math.min(99, level));
		ByteBuffer l = le(levels);
		m.maxHp = l.getShort((level - 1) * LEVEL_ROW + m.id * 4) & 0xFFFF;
		m.maxSp = l.getShort((level - 1) * LEVEL_ROW + m.id * 4 + 2) & 0xFFFF;
		m.name = m.id == 1 ? heroName(hero) : MEMBER_NAMES[m.id];
		return m;
	}

	// Two bytes a letter: 0x80, then the letter + 0x60.
	private static String heroName(byte[] hero) {
		StringBuilder sb = new StringBuilder();
		for (int o = 0x1E; o + 1 < HERO - HERO_BLOCK && sb.length() < 9; o += 2) {
			int hi = hero[o] & 0xFF, lo = hero[o + 1] & 0xFF;
			if (hi != 0x80 || lo < 0x80) {
				break;
			}
			sb.append((char)(lo - 0x60));
		}
		return sb.length() > 0 ? sb.toString() : "Hero";
	}

	@Override
	public void onDestroyView() {
		view = null;
		host = null;
	}

	// The Analyze screen's look: dark grey panels, light blue strips with dark element icons and
	// labels, light blue text; the party's faces and HP / SP bars as on the battle HUD.
	private final class BattleView extends View {
		private static final int BACKGROUND = 0xFF1C1D1F;
		private static final int PANEL = 0xFF393939;
		private static final int CYAN = 0xFF86DAFB;
		private static final int DARK = 0xFF393939;
		private static final int WHITE = 0xFFF4F8FA;
		private static final int HP_COLOR = 0xFFF2A65C;
		private static final int SP_COLOR = 0xFF8C6CF0;
		private static final int BAR_BACK = 0xFF15171A;
		private static final int DIM = 0xFF7D8A93;
		private static final int KO = 0xFFE5534B;

		// In c_main_01: the boxed element icons, 14x14, every 17 px from x = 2.
		private static final int ICON_TOP = 224, ICON_SIZE = 14;
		// In c_main_02: the Analyze labels.
		private final Rect wk = new Rect(54, 92, 72, 104), str = new Rect(76, 92, 93, 104), nul = new Rect(97, 92, 114, 104);
		private final Rect dm = new Rect(75, 74, 94, 88), rpl = new Rect(97, 74, 114, 88);

		private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
		private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
		private final Paint bitmap = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
		private final Paint darkTint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
		private final Paint cyanTint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
		private final Paint grey = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
		private final RectF rect = new RectF();
		private final Rect src = new Rect();
		private Battle battle;

		BattleView(Context context) {
			super(context);
			setBackgroundColor(BACKGROUND);
			text.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
			darkTint.setColorFilter(new PorterDuffColorFilter(DARK, PorterDuff.Mode.SRC_IN));
			cyanTint.setColorFilter(new PorterDuffColorFilter(CYAN, PorterDuff.Mode.SRC_IN));
			ColorMatrix cm = new ColorMatrix();
			cm.setSaturation(0);
			grey.setColorFilter(new ColorMatrixColorFilter(cm));
			grey.setAlpha(150);
		}

		void setBattle(Battle battle) {
			this.battle = battle;
			invalidate();
		}

		private float textAt(Canvas canvas, String s, float x, float baseline, float size, int color, Paint.Align align) {
			text.setColor(color);
			text.setTextSize(size);
			text.setTextAlign(align);
			canvas.drawText(s, x, baseline, text);
			return text.measureText(s);
		}

		// Shrinks the text to fit the width.
		private void fitText(Canvas canvas, String s, float x, float baseline, float size, float maxW, int color) {
			text.setTextSize(size);
			float w = text.measureText(s);
			textAt(canvas, s, x, baseline, w > maxW ? size * maxW / w : size, color, Paint.Align.LEFT);
		}

		@Override
		protected void onDraw(Canvas canvas) {
			float w = getWidth(), h = getHeight();
			float u = Math.min(w / 1240f, h / 1080f);
			if (battle == null) {
				textAt(canvas, getContext().getString(R.string.duo_persona3_idle), w / 2, h / 2, 30 * u, DIM, Paint.Align.CENTER);
				return;
			}
			float m = 24 * u, gap = 18 * u;
			float partyW = 390 * u;
			// Both columns centered vertically.
			float slotH = (h - 2 * m - 3 * gap) / 4;
			int members = battle.party.length;
			float partyTop = (h - members * slotH - (members - 1) * gap) / 2;
			for (int i = 0; i < members; i++) {
				float t = partyTop + i * (slotH + gap);
				drawMember(canvas, battle.party[i], m, t, m + partyW, t + slotH, u);
			}
			int n = battle.enemies.length;
			float el = m + partyW + gap * 1.5f, er = w - m;
			float cardH = Math.min(260 * u, (h - 2 * m - (n - 1) * gap) / n);
			float enemyTop = (h - n * cardH - (n - 1) * gap) / 2;
			for (int i = 0; i < n; i++) {
				float t = enemyTop + i * (cardH + gap);
				drawEnemy(canvas, battle.enemies[i], el, t, er, t + cardH, u);
			}
		}

		private void drawMember(Canvas canvas, Member mb, float l, float t, float r, float b, float u) {
			boolean down = mb.hp == 0;
			paint.setColor(PANEL);
			rect.set(l, t, r, b);
			canvas.drawRoundRect(rect, 10 * u, 10 * u, paint);
			// Name on a light blue strip, as the Analyze screen's blocks.
			float stripH = 46 * u;
			paint.setColor(CYAN);
			rect.set(l, t, r, t + stripH);
			canvas.drawRoundRect(rect, 10 * u, 10 * u, paint);
			rect.set(l, t + stripH / 2, r, t + stripH);
			canvas.drawRect(rect, paint);
			fitText(canvas, mb.name, l + 16 * u, t + stripH * 0.72f, 30 * u, r - l - 32 * u, DARK);

			float top = t + stripH + 10 * u;
			float face = Math.min(b - top - 10 * u, 150 * u);
			Bitmap fb = art != null && mb.id < art.faces.length ? art.faces[mb.id] : null;
			if (fb != null) {
				rect.set(l + 8 * u, top, l + 8 * u + face, top + face);
				// The silhouette in light blue under the face makes the HUD's outline.
				if (art.outlines[mb.id] != null) {
					canvas.drawBitmap(art.outlines[mb.id], null, rect, down ? grey : cyanTint);
				}
				canvas.drawBitmap(fb, null, rect, down ? grey : bitmap);
			}
			float bl = l + 8 * u + face + 14 * u, br = r - 16 * u;
			float rowH = (b - top - 10 * u) / 2;
			drawStat(canvas, "HP", mb.hp, mb.maxHp, HP_COLOR, down, bl, top, br, top + rowH, u);
			drawStat(canvas, "SP", mb.sp, mb.maxSp, SP_COLOR, false, bl, top + rowH, br, top + 2 * rowH, u);
		}

		// Label and value on a line, the HUD's bar under them.
		private void drawStat(Canvas canvas, String label, int value, int max, int color, boolean alarm, float l, float t, float r, float b, float u) {
			float size = Math.min(30 * u, (b - t) * 0.42f);
			float baseline = t + size * 1.05f;
			textAt(canvas, label, l, baseline, size * 0.8f, WHITE, Paint.Align.LEFT);
			String v = max > 0 ? value + "/" + max : String.valueOf(value);
			textAt(canvas, v, r, baseline, size, alarm ? KO : CYAN, Paint.Align.RIGHT);
			float barT = baseline + 8 * u, barH = Math.min(16 * u, b - barT - 4 * u);
			drawBar(canvas, l, barT, r, barT + barH, max > 0 ? (float)value / max : 0, color, u);
		}

		// A black bar with a light blue rim, like the HUD's.
		private void drawBar(Canvas canvas, float l, float t, float r, float b, float frac, int color, float u) {
			paint.setColor(CYAN);
			rect.set(l - 2 * u, t - 2 * u, r + 2 * u, b + 2 * u);
			canvas.drawRoundRect(rect, 4 * u, 4 * u, paint);
			paint.setColor(BAR_BACK);
			rect.set(l, t, r, b);
			canvas.drawRoundRect(rect, 3 * u, 3 * u, paint);
			frac = Math.max(0, Math.min(1, frac));
			if (frac > 0) {
				paint.setColor(color);
				float in = 3 * u;
				rect.set(l + in, t + in, l + in + (r - l - 2 * in) * frac, b - in);
				canvas.drawRect(rect, paint);
			}
		}

		private void drawEnemy(Canvas canvas, Enemy e, float l, float t, float r, float b, float u) {
			boolean down = e.hp == 0;
			int saved = down ? canvas.saveLayerAlpha(l, t, r, b, 90) : -1;
			paint.setColor(PANEL);
			rect.set(l, t, r, b);
			canvas.drawRoundRect(rect, 10 * u, 10 * u, paint);

			float h = b - t;
			float topH = h * 0.44f;
			// The light blue block with the level and arcana.
			float blockW = 190 * u;
			paint.setColor(CYAN);
			rect.set(l, t, l + blockW, t + topH);
			canvas.drawRoundRect(rect, 10 * u, 10 * u, paint);
			float pillH = Math.min(36 * u, topH * 0.42f);
			float pillT = t + topH * 0.12f;
			paint.setColor(DARK);
			rect.set(l + 16 * u, pillT, l + blockW - 16 * u, pillT + pillH);
			canvas.drawRoundRect(rect, pillH / 2, pillH / 2, paint);
			textAt(canvas, "LV " + e.level, l + blockW / 2, pillT + pillH * 0.78f, pillH * 0.78f, CYAN, Paint.Align.CENTER);
			String arcana = e.arcana > 0 && e.arcana < ARCANA.length ? ARCANA[e.arcana] : "";
			textAt(canvas, arcana, l + blockW / 2, t + topH * 0.86f, Math.min(28 * u, topH * 0.3f), DARK, Paint.Align.CENTER);

			// Name and HP.
			float nl = l + blockW + 18 * u, nr = r - 18 * u;
			float nameSize = Math.min(36 * u, topH * 0.4f);
			fitText(canvas, e.name, nl, t + nameSize * 1.15f, nameSize, nr - nl, CYAN);
			float hpSize = Math.min(26 * u, topH * 0.3f);
			float hpBase = t + topH - 8 * u;
			float hw = textAt(canvas, e.hp + "/" + e.maxHp, nr, hpBase, hpSize, down ? KO : WHITE, Paint.Align.RIGHT);
			textAt(canvas, "HP", nl, hpBase, hpSize * 0.8f, WHITE, Paint.Align.LEFT);
			float barL = nl + hpSize * 1.8f, barR = nr - hw - 16 * u;
			float barH = Math.min(14 * u, hpSize * 0.55f);
			if (barR > barL) {
				drawBar(canvas, barL, hpBase - barH - hpSize * 0.1f, barR, hpBase - hpSize * 0.1f, e.maxHp > 0 ? (float)e.hp / e.maxHp : 0, HP_COLOR, u);
			}

			// The affinity strip: one box per element, the label under it.
			float st = t + topH + 10 * u, sb = b - 10 * u;
			float sl = l + 10 * u, sr = r - 10 * u;
			paint.setColor(CYAN);
			rect.set(sl, st, sr, sb);
			canvas.drawRoundRect(rect, 8 * u, 8 * u, paint);
			float cell = (sr - sl - 16 * u) / ELEMENTS.length;
			float icon = Math.min(cell * 0.78f, (sb - st) * 0.55f);
			float labelH = Math.min(icon * 0.5f, (sb - st) - icon - 18 * u);
			float iconT = st + ((sb - st) - icon - labelH - 6 * u) / 2;
			for (int k = 0; k < ELEMENTS.length; k++) {
				float cx = sl + 8 * u + cell * (k + 0.5f);
				drawIcon(canvas, ELEMENTS[k], cx - icon / 2, iconT, icon);
				drawLabel(canvas, e.affinity[ELEMENTS[k]], cx, iconT + icon + 6 * u, labelH);
			}
			if (saved >= 0) {
				canvas.restoreToCount(saved);
			}
		}

		private void drawIcon(Canvas canvas, int element, float x, float y, float size) {
			rect.set(x, y, x + size, y + size);
			if (art != null) {
				int sx = 2 + element * 17;
				src.set(sx, ICON_TOP, sx + ICON_SIZE, ICON_TOP + ICON_SIZE);
				canvas.drawBitmap(art.icons, src, rect, darkTint);
				return;
			}
			paint.setColor(DARK);
			canvas.drawRoundRect(rect, size * 0.15f, size * 0.15f, paint);
			String[] abbr = {"Sl", "St", "Pi", "Fi", "Ic", "El", "Wi", "Al", "Li", "Da"};
			textAt(canvas, abbr[element], x + size / 2, y + size * 0.68f, size * 0.45f, CYAN, Paint.Align.CENTER);
		}

		// The strongest effect wins: repel, drain, null, then weak or strong.
		private void drawLabel(Canvas canvas, int affinity, float cx, float top, float h) {
			Rect r;
			String s;
			if ((affinity & REPEL) != 0) {
				r = rpl;
				s = "Rpl";
			} else if ((affinity & DRAIN) != 0) {
				r = dm;
				s = "Dm";
			} else if ((affinity & NULL) != 0) {
				r = nul;
				s = "Nul";
			} else if ((affinity & WEAK) != 0) {
				r = wk;
				s = "Wk";
			} else if ((affinity & STRONG) != 0) {
				r = str;
				s = "Str";
			} else {
				return;
			}
			if (art != null) {
				// Cap height is 10 px in the atlas; Dm and Rpl have a descender below it.
				float scale = h / 12f;
				float w = r.width() * scale;
				rect.set(cx - w / 2, top, cx + w / 2, top + r.height() * scale);
				canvas.drawBitmap(art.labels, r, rect, darkTint);
				return;
			}
			textAt(canvas, s, cx, top + h * 0.8f, h * 0.9f, DARK, Paint.Align.CENTER);
		}
	}
}
