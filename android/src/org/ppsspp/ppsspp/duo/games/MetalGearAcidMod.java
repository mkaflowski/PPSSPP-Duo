package org.ppsspp.ppsspp.duo.games;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.ppsspp.ppsspp.R;
import org.ppsspp.ppsspp.duo.DuoMod;
import org.ppsspp.ppsspp.duo.DuoModContext;
import org.ppsspp.ppsspp.duo.DuoNative;
import org.ppsspp.ppsspp.duo.DuoStatus;
import org.ppsspp.ppsspp.duo.DuoUi;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.Inflater;

// Metal Gear Ac!d: your hand of cards, big and readable, with the full text of the card under the
// cursor, and Snake's life, deck, cost and turn. Tapping a card moves the game's cursor to it.
// Two looks: the game's own cards (with their illustrations from the disc, see MgaArt) or plain.
//
// Found on ULUS10006 v1.00 (by driving the first mission in headless). The game objects live on its
// heap, so they're found by the code pointers they hold (all in the main module):
// - hand: +0x04 = 0x0881A048. +0x98 card count (u16), +0x9C pointer to the cards, 4 bytes each
//   (u16 card number, u16).
// - unit: +0x04 = 0x088AA284, +0x14 = 0x088AA5B4. Snake's is the one whose +0x50 points to the
//   hand. +0x54 life, +0x56 max life (u16).
// - status panel: +0x04 = 0x0885ED04, +0x14 = 0x0885F200. +0x78 cards left in the deck, +0x84 cost,
//   +0x88 turn (u32).
// - hand list on screen: +0x04 = 0x08859EAC. +0x48 index of the card under the cursor.
// Card records are a static table at 0x089C9984, 0x28 bytes per card (the function at 0x08816790
// indexes it): +0x00 card number (-1 if unused), +0x12 cost (u16), +0x20 type (0 character, 1 item,
// 2 support, 3 action, 4 weapon), +0x24 illustration number (cd_<type><number>_alp_ovl, built at
// 0x0885BDCC).
// The battle phase is a static u32 at 0x089E1764: 16 while you pick a card from the hand (left and
// right move the cursor), 17 while you pick a block on the map, 22 during a codec call.
// Card names and texts are in the string table of stage/com/_zar (u32 size, then zlib): a table of
// u32 0x80000000 | offset (from the end of the table), with the name of card N at 8 + N, its full
// text at 259 + N and the short text printed on the card at 510 + N.
public final class MetalGearAcidMod extends DuoMod {
	public static final String ID = "metal_gear_acid";
	private static final String TAG = "PPSSPPDuo";
	private static final String PREFS = "duo_metal_gear_acid";
	private static final String PREF_GAME_LOOK = "game_look";

	private static final String[] GAME_IDS = {"ULUS10006"};

	private static final int SCAN_START = 0x08804000;
	private static final int SCAN_END = 0x0A000000;

	private static final int[] HAND_OFFSETS = {0x04};
	private static final int[] HAND_VALUES = {0x0881A048};
	private static final int[] UNIT_OFFSETS = {0x04, 0x14};
	private static final int[] UNIT_VALUES = {0x088AA284, 0x088AA5B4};
	private static final int[] STATUS_OFFSETS = {0x04, 0x14};
	private static final int[] STATUS_VALUES = {0x0885ED04, 0x0885F200};
	private static final int[] LIST_OFFSETS = {0x04};
	private static final int[] LIST_VALUES = {0x08859EAC};

	private static final int CARD_TABLE = 0x089C9984;
	private static final int CARD_RECORD = 0x28;
	private static final int CARD_COUNT = 251;
	private static final int MAX_HAND = 32;

	private static final int PHASE = 0x089E1764;
	private static final int PHASE_HAND = 16;
	// How long a button stays down, and how long to wait for the cursor after letting go.
	private static final long PRESS_MS = 60;
	private static final long SETTLE_MS = 120;
	private static final int MAX_STALLS = 4;

	private static final String TEXT_FILE = "disc0:/PSP_GAME/USRDIR/stage/com/_zar";
	private static final int TEXT_NAME = 8;
	private static final int TEXT_FULL = 259;
	private static final int TEXT_SHORT = 510;

	private static final int W_HAND = 0;
	private static final int W_CARDS = 1;
	private static final int W_UNIT = 2;
	private static final int W_STATUS = 3;
	private static final int W_LIST = 4;
	private static final int W_TABLE = 5;
	private static final int W_PHASE = 6;

	// The game's card colors.
	private static final int CARD_FACE_TOP = 0xFFF2F2EE;
	private static final int CARD_FACE_BOTTOM = 0xFFD6D6D0;
	private static final int CARD_EDGE = 0xFF5A5C60;
	private static final int CARD_INK = 0xFF141414;
	private static final int CARD_BANNER = 0xFF2E2F31;
	private static final int CARD_BANNER_TEXT = 0xFFE4E4E0;
	private static final int GAME_BACKGROUND = 0xFF0A0C0B;
	private static final int GAME_PANEL = 0xFF17191A;

	// Kept across tab switches: the texts take a moment to read and unpack.
	private static String[] texts;
	private static boolean textsLoading;
	private static MgaArt art;
	private static String artGame = "";
	private static boolean artLoading;

	private DuoModContext host;
	private HandView handView;
	private CardTopView cardTop;
	private LinearLayout root;
	private LinearLayout detailPanel;
	private TextView detailTitle, detailText;
	private boolean gameLook;
	private Typeface condensed;

	private int hand, unit, status, list;
	// Watch #1 follows the hand's card array.
	private int cardsAddr, cardsCap;
	private int unitSearchFrom;
	private int searching;  // bit per object being searched for
	private long lastSearch;
	private int[] costs, types, illustrations;

	// The card shown in the detail panel: the game's cursor, a tapped card until the cursor moves,
	// or the card the cursor is being moved to.
	private int gameCursor = -1;
	private int tapped = -1;
	private int phase = -1;

	// Moving the game's cursor to a tapped card, one left/right press at a time.
	private int moveTarget = -1;
	private int heldButton;
	private int pressedAt = -1;   // the cursor when the last press was made
	private long releasedAt;
	private int stalls;

	@Override
	public String getId() {
		return ID;
	}

	@Override
	public String getTitle(Context context) {
		return context.getString(R.string.duo_mod_mga);
	}

	@Override
	public String getDescription(Context context) {
		return context.getString(R.string.duo_mod_mga_desc);
	}

	@Override
	public int getPriority(DuoStatus s) {
		return Arrays.asList(GAME_IDS).contains(s.gameId) ? 100 : -1;
	}

	@Override
	public long getStatusIntervalMs() {
		return moveTarget >= 0 ? 50 : 150;
	}

	@Override
	public View onCreateView(DuoModContext host) {
		this.host = host;
		Context ctx = host.getContext();
		hand = unit = status = list = 0;
		cardsAddr = cardsCap = 0;
		unitSearchFrom = SCAN_START;
		searching = 0;
		costs = types = illustrations = null;
		gameCursor = tapped = phase = -1;
		moveTarget = -1;
		heldButton = 0;
		condensed = Typeface.create("sans-serif-condensed", Typeface.BOLD);
		SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
		gameLook = prefs.getBoolean(PREF_GAME_LOOK, true);

		root = new LinearLayout(ctx);
		root.setOrientation(LinearLayout.HORIZONTAL);
		int pad = DuoUi.dp(ctx, 24);
		root.setPadding(pad, DuoUi.dp(ctx, 12), pad, pad);

		handView = new HandView(ctx);
		root.addView(handView, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.6f));

		LinearLayout detail = new LinearLayout(ctx);
		detail.setOrientation(LinearLayout.VERTICAL);
		int dp = DuoUi.dp(ctx, 16);
		detail.setPadding(dp, dp, dp, dp);
		cardTop = new CardTopView(ctx);
		detail.addView(cardTop, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
		detailTitle = DuoUi.text(ctx, "", 19, DuoUi.COLOR_TEXT);
		detailTitle.setTypeface(Typeface.DEFAULT_BOLD);
		detail.addView(detailTitle);
		ScrollView scroll = new ScrollView(ctx);
		// Fills the panel, so the hint can be centered in it.
		scroll.setFillViewport(true);
		detailText = DuoUi.text(ctx, "", 14, DuoUi.COLOR_TEXT);
		detailText.setLineSpacing(0, 1.15f);
		detailText.setPadding(0, DuoUi.dp(ctx, 10), 0, 0);
		scroll.addView(detailText, new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.MATCH_PARENT));
		detail.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
		LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.4f);
		lp.leftMargin = DuoUi.dp(ctx, 16);
		lp.topMargin = DuoUi.dp(ctx, 12);
		root.addView(detail, lp);
		detailPanel = detail;
		detail.setVisibility(View.GONE);
		applyLook();

		watch();
		loadTexts();
		return root;
	}

	private void setGameLook(boolean on) {
		gameLook = on;
		host.getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(PREF_GAME_LOOK, on).apply();
		applyLook();
		handView.invalidate();
	}

	private void applyLook() {
		Context ctx = host.getContext();
		root.setBackgroundColor(gameLook ? GAME_BACKGROUND : DuoUi.COLOR_BACKGROUND);
		if (gameLook) {
			int dp = DuoUi.dp(ctx, 12);
			detailPanel.setPadding(dp, dp, dp + DuoUi.dp(ctx, 10), dp);
		} else {
			int dp = DuoUi.dp(ctx, 16);
			detailPanel.setPadding(dp, dp, dp, dp);
			detailPanel.setBackground(DuoUi.rounded(ctx, DuoUi.COLOR_SURFACE, 14));
		}
		cardTop.setVisibility(gameLook ? View.VISIBLE : View.GONE);
		detailText.setTextSize(gameLook ? 15 : 14);
		detailText.setTypeface(gameLook ? Typeface.create("sans-serif-condensed", Typeface.NORMAL) : Typeface.DEFAULT);
		shownDetail = -2;
		showDetail(currentDetail);
	}

	private void watch() {
		int fallback = SCAN_START;
		host.setMemoryWatches(
			new int[] {or(hand, fallback), or(cardsAddr, fallback), or(unit, fallback), or(status, fallback), or(list, fallback), CARD_TABLE, PHASE},
			new int[] {0xA0, Math.max(4, cardsCap * 4), 0x60, 0x90, 0x50, CARD_RECORD * CARD_COUNT, 4});
	}

	private static int or(int addr, int fallback) {
		return addr != 0 ? addr : fallback;
	}

	private void loadTexts() {
		if (texts != null || textsLoading) {
			return;
		}
		textsLoading = true;
		boolean queued = host.readGameFile(TEXT_FILE, 0, 1024 * 1024, data -> {
			textsLoading = false;
			String[] t = parseTexts(data);
			if (t != null) {
				texts = t;
				if (handView != null) {
					handView.invalidate();
				}
			}
		});
		if (!queued) {
			textsLoading = false;
		}
	}

	private void loadArt(DuoStatus s) {
		String key = s.gameId + "_" + s.discVersion;
		if (artLoading || (art != null && key.equals(artGame))) {
			return;
		}
		artLoading = true;
		File dir = new File(host.getContext().getFilesDir(), "duo/mga/" + key);
		MgaArt.load(host, dir, a -> {
			artLoading = false;
			art = a;
			artGame = key;
			if (handView != null) {
				handView.invalidate();
				cardTop.invalidate();
			}
		});
	}

	// Signature scans, one object at a time.
	private void search() {
		long now = SystemClock.uptimeMillis();
		if (searching != 0 || now - lastSearch < 1000) {
			return;
		}
		lastSearch = now;
		if (hand == 0) {
			find(1, SCAN_START, HAND_OFFSETS, HAND_VALUES, addr -> {
				hand = addr;
				unit = 0;
				unitSearchFrom = SCAN_START;
				if (addr != 0) {
					Log.i(TAG, "MGA: hand at " + Integer.toHexString(addr));
				}
				watch();
			});
		} else if (unit == 0) {
			// Every unit has the same class; Snake's is the one holding the hand.
			find(2, unitSearchFrom, UNIT_OFFSETS, UNIT_VALUES, addr -> {
				if (addr == 0) {
					unitSearchFrom = SCAN_START;
					return;
				}
				unitSearchFrom = addr + 4;
				unit = addr;
				watch();
			});
		} else if (status == 0) {
			find(4, SCAN_START, STATUS_OFFSETS, STATUS_VALUES, addr -> {
				status = addr;
				watch();
			});
		} else if (list == 0) {
			find(8, SCAN_START, LIST_OFFSETS, LIST_VALUES, addr -> {
				list = addr;
				watch();
			});
		}
	}

	private void find(int bit, int start, int[] offsets, int[] values, DuoModContext.FindCallback cb) {
		searching |= bit;
		boolean queued = host.findMemory(start, SCAN_END, offsets, values, addr -> {
			searching &= ~bit;
			cb.onFound(addr);
		});
		if (!queued) {
			searching &= ~bit;
		}
	}

	private static ByteBuffer le(byte[] data) {
		return ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
	}

	private static boolean matches(ByteBuffer b, int[] offsets, int[] values) {
		for (int i = 0; i < offsets.length; i++) {
			if (b.limit() < offsets[i] + 4 || b.getInt(offsets[i]) != values[i]) {
				return false;
			}
		}
		return true;
	}

	private static boolean isPointer(int p) {
		return p >= 0x08800000 && p < 0x0A000000;
	}

	@Override
	public void onStatus(DuoStatus s) {
		if (!s.isInGame() && !s.isPauseMenu()) {
			stopMove();
			return;
		}
		if (texts == null && s.isInGame()) {
			loadTexts();
		}
		loadArt(s);
		if (costs == null) {
			readTable();
		}
		byte[] phaseData = host.readMemoryWatch(W_PHASE);
		int newPhase = phaseData != null && phaseData.length >= 4 ? le(phaseData).getInt(0) : -1;
		if (newPhase != phase && moveTarget < 0) {
			tapped = -1;   // a card read in another phase isn't the game's choice: follow its cursor again
		}
		phase = newPhase;

		// Objects that went away (end of the mission) are searched for again. A watch reads null
		// until the frame after it's set, which doesn't mean anything.
		byte[] handData = host.readMemoryWatch(W_HAND);
		if (hand != 0 && handData != null && !matches(le(handData), HAND_OFFSETS, HAND_VALUES)) {
			hand = 0;
			watch();
		}
		byte[] unitData = host.readMemoryWatch(W_UNIT);
		if (unit != 0 && unitData != null) {
			if (!matches(le(unitData), UNIT_OFFSETS, UNIT_VALUES)) {
				unit = 0;
				unitSearchFrom = SCAN_START;
				watch();
			} else if (le(unitData).getInt(0x50) != hand) {
				// Another unit (an enemy or a partner): keep looking after it.
				unit = 0;
				watch();
			}
		}
		byte[] statusData = host.readMemoryWatch(W_STATUS);
		if (status != 0 && statusData != null && !matches(le(statusData), STATUS_OFFSETS, STATUS_VALUES)) {
			status = 0;
			watch();
		}
		byte[] listData = host.readMemoryWatch(W_LIST);
		if (list != 0 && listData != null && !matches(le(listData), LIST_OFFSETS, LIST_VALUES)) {
			list = 0;
			watch();
		}
		if (hand == 0 || unit == 0 || status == 0 || list == 0) {
			search();
		}
		if (hand == 0) {
			stopMove();
			handView.setData(null, null, -1);
			showDetail(-1);
			return;
		}
		if (handData == null || !matches(le(handData), HAND_OFFSETS, HAND_VALUES)) {
			return;
		}

		ByteBuffer hb = le(handData);
		int count = hb.getShort(0x98) & 0xFFFF;
		int addr = hb.getInt(0x9C);
		if (count > MAX_HAND || (count > 0 && !isPointer(addr))) {
			return;
		}
		int[] cards = new int[count];
		if (count > 0) {
			if (addr != cardsAddr || count > cardsCap) {
				cardsAddr = addr;
				cardsCap = Math.max(count, 8);
				watch();
				return;
			}
			byte[] cardData = host.readMemoryWatch(W_CARDS);
			if (cardData == null || cardData.length < count * 4) {
				return;
			}
			ByteBuffer cb = le(cardData);
			for (int i = 0; i < count; i++) {
				int id = cb.getShort(i * 4) & 0xFFFF;
				if (id >= CARD_COUNT) {
					return;  // mid-update
				}
				cards[i] = id;
			}
		}

		int[] stats = null;
		if (unit != 0 && unitData != null && statusData != null && status != 0
				&& matches(le(unitData), UNIT_OFFSETS, UNIT_VALUES) && matches(le(statusData), STATUS_OFFSETS, STATUS_VALUES)) {
			ByteBuffer ub = le(unitData), sb = le(statusData);
			stats = new int[] {ub.getShort(0x54) & 0xFFFF, ub.getShort(0x56) & 0xFFFF, sb.getInt(0x78), sb.getInt(0x84), sb.getInt(0x88)};
		}

		int cursor = -1;
		if (list != 0 && listData != null && matches(le(listData), LIST_OFFSETS, LIST_VALUES)) {
			cursor = le(listData).getInt(0x48);
		}
		if (cursor != gameCursor) {
			gameCursor = cursor;
			if (moveTarget < 0) {
				tapped = -1;
			}
		}
		if (s.isInGame()) {
			driveCursor(cursor, count);
		} else {
			stopMove();   // never press buttons into the pause menu
		}
		int selected = moveTarget >= 0 ? moveTarget : tapped >= 0 ? tapped : cursor;
		if (selected < 0 || selected >= count) {
			selected = -1;
		}
		handView.setData(cards, stats, selected);
		showDetail(selected >= 0 ? cards[selected] : -1);
	}

	// Presses left or right until the game's cursor is on the tapped card. Only while the game is
	// waiting for a card from the hand, and only as long as each press moves the cursor.
	private void driveCursor(int cursor, int count) {
		if (moveTarget < 0) {
			return;
		}
		if (phase != PHASE_HAND || cursor < 0 || moveTarget >= count || cursor == moveTarget) {
			stopMove();
			return;
		}
		if (heldButton != 0) {
			return;
		}
		if (cursor == pressedAt) {
			// The last press hasn't shown up yet, or the game ignored it.
			if (SystemClock.uptimeMillis() - releasedAt < SETTLE_MS) {
				return;
			}
			if (++stalls >= MAX_STALLS) {
				Log.i(TAG, "MGA: the cursor doesn't move, giving up");
				stopMove();
				return;
			}
		} else {
			stalls = 0;
		}
		int button = moveTarget > cursor ? DuoNative.CTRL_RIGHT : DuoNative.CTRL_LEFT;
		pressedAt = cursor;
		heldButton = button;
		host.pressButton(button, true);
		handView.postDelayed(() -> {
			if (heldButton == button && host != null) {
				host.pressButton(button, false);
				heldButton = 0;
				releasedAt = SystemClock.uptimeMillis();
			}
		}, PRESS_MS);
	}

	private void startMove(int target) {
		if (phase != PHASE_HAND || gameCursor < 0 || target == gameCursor) {
			return;
		}
		moveTarget = target;
		pressedAt = -1;
		stalls = 0;
	}

	private void stopMove() {
		if (heldButton != 0 && host != null) {
			host.pressButton(heldButton, false);
		}
		heldButton = 0;
		if (moveTarget >= 0) {
			tapped = moveTarget == gameCursor ? -1 : moveTarget;
		}
		moveTarget = -1;
	}

	private void readTable() {
		byte[] data = host.readMemoryWatch(W_TABLE);
		if (data == null || data.length < CARD_RECORD * CARD_COUNT) {
			return;
		}
		ByteBuffer b = le(data);
		int[] c = new int[CARD_COUNT], t = new int[CARD_COUNT], n = new int[CARD_COUNT];
		for (int i = 0; i < CARD_COUNT; i++) {
			int r = i * CARD_RECORD;
			int id = b.getInt(r);
			c[i] = b.getShort(r + 0x12) & 0xFFFF;
			t[i] = id == i ? b.getInt(r + 0x20) : -1;
			n[i] = b.getInt(r + 0x24);
		}
		// SOCOM, the first card, costs 5: anything else means this isn't the table.
		if (t[0] != 4 || c[0] != 5) {
			return;
		}
		costs = c;
		types = t;
		illustrations = n;
	}

	private Bitmap illustration(int id) {
		if (art == null || types == null || illustrations == null || id < 0 || id >= CARD_COUNT) {
			return null;
		}
		return art.illustration(types[id], illustrations[id]);
	}

	private int shownDetail = -2;
	private int currentDetail = -1;

	private void showDetail(int id) {
		currentDetail = id;
		// Nothing selected shows a hint only while there are cards to tap.
		int key = id >= 0 ? id : handView.hasCards() ? -1 : -3;
		if (key == shownDetail) {
			return;
		}
		shownDetail = key;
		Context ctx = host.getContext();
		// Without cards the panel goes away and the hand view's message takes the whole width.
		detailPanel.setVisibility(handView.hasCards() ? View.VISIBLE : View.GONE);
		int type = id >= 0 && types != null ? types[id] : -1;
		if (gameLook) {
			detailPanel.setBackground(new CardFace(ctx, id >= 0 ? typeColor(type) : CARD_FACE_BOTTOM, id >= 0));
		}
		cardTop.setCard(id);
		cardTop.setVisibility(gameLook && id >= 0 ? View.VISIBLE : View.GONE);
		if (id < 0) {
			detailTitle.setVisibility(View.GONE);
			detailText.setText(handView.hasCards() ? ctx.getString(R.string.duo_mga_pick) : "");
			detailText.setTextColor(gameLook ? 0xFF55585C : DuoUi.COLOR_TEXT_DIM);
			detailText.setGravity(Gravity.CENTER);
			return;
		}
		detailTitle.setVisibility(View.VISIBLE);
		detailText.setGravity(Gravity.START | Gravity.TOP);
		if (gameLook) {
			detailTitle.setText(ctx.getString(R.string.duo_mga_information));
			detailTitle.setTextSize(13);
			detailTitle.setTypeface(condensed);
			detailTitle.setTextColor(CARD_INK);
		} else {
			detailTitle.setText(cardName(id));
			detailTitle.setTextSize(19);
			detailTitle.setTypeface(Typeface.DEFAULT_BOLD);
			detailTitle.setTextColor(typeColor(type));
		}
		String full = text(TEXT_FULL + id);
		detailText.setText(full != null ? reflow(full) : "");
		detailText.setTextColor(gameLook ? CARD_INK : DuoUi.COLOR_TEXT);
	}

	static String text(int index) {
		String[] t = texts;
		return t != null && index >= 0 && index < t.length ? t[index] : null;
	}

	static String cardName(int id) {
		String name = text(TEXT_NAME + id);
		return name != null ? name.trim() : "#" + id;
	}

	static int typeColor(int type) {
		switch (type) {
		case 0: return 0xFFB8BEC6;  // character
		case 1: return 0xFF3D8FE0;  // item
		case 2: return 0xFFB5CC2E;  // support
		case 3: return 0xFFE8963A;  // action
		case 4: return 0xFFD8327E;  // weapon
		default: return DuoUi.COLOR_TEXT;
		}
	}

	static String typeName(int type) {
		switch (type) {
		case 0: return "Character";
		case 1: return "Item";
		case 2: return "Support";
		case 3: return "Action";
		case 4: return "Weapon";
		default: return "";
		}
	}

	// stage/com/_zar: u32 unpacked size, then a zlib stream. The string table is found from the first
	// card's name, so a shifted layout still works.
	static String[] parseTexts(byte[] file) {
		if (file == null || file.length < 8) {
			return null;
		}
		byte[] u;
		try {
			int size = le(file).getInt(0);
			if (size <= 0 || size > 16 * 1024 * 1024) {
				return null;
			}
			Inflater inf = new Inflater();
			inf.setInput(file, 4, file.length - 4);
			ByteArrayOutputStream out = new ByteArrayOutputStream(size);
			byte[] buf = new byte[65536];
			while (!inf.finished()) {
				int n = inf.inflate(buf);
				if (n == 0 && (inf.needsInput() || inf.needsDictionary())) {
					break;
				}
				out.write(buf, 0, n);
			}
			inf.end();
			u = out.toByteArray();
		} catch (Exception e) {
			Log.w(TAG, "MGA: can't unpack the texts", e);
			return null;
		}
		byte[] key = "SOCOM\n\0FAMAS\n\0".getBytes(StandardCharsets.US_ASCII);
		int p = indexOf(u, key);
		if (p < 0) {
			return null;
		}
		ByteBuffer b = le(u);
		// The table ends where the strings start; its entries are 0x80000000 | offset.
		for (int end = p & ~3; end >= 4 && end > p - 0x20000; end -= 4) {
			if ((b.getInt(end - 4) >>> 24) != 0x80) {
				continue;
			}
			int start = end - 4;
			while (start >= 4 && (b.getInt(start - 4) >>> 24) == 0x80) {
				start -= 4;
			}
			int n = (end - start) / 4;
			if (n <= TEXT_SHORT + CARD_COUNT || (b.getInt(start + TEXT_NAME * 4) & 0xFFFFFF) != p - end) {
				return null;
			}
			String[] t = new String[n];
			for (int i = 0; i < n; i++) {
				int o = end + (b.getInt(start + i * 4) & 0xFFFFFF);
				t[i] = o < u.length ? decode(u, o) : "";
			}
			return t;
		}
		return null;
	}

	private static int indexOf(byte[] data, byte[] key) {
		outer:
		for (int i = 0; i + key.length <= data.length; i++) {
			for (int k = 0; k < key.length; k++) {
				if (data[i + k] != key[k]) {
					continue outer;
				}
			}
			return i;
		}
		return -1;
	}

	// ASCII with two-byte icons. [d:...] is a value the game fills in.
	private static String decode(byte[] u, int o) {
		StringBuilder sb = new StringBuilder();
		int depth = 0;
		for (int i = o; i < u.length && u[i] != 0; i++) {
			int c = u[i] & 0xFF;
			if (c >= 0x80) {
				int c2 = i + 1 < u.length ? u[i + 1] & 0xFF : 0;
				i++;
				if (depth > 0) {
					continue;
				}
				int code = (c << 8) | c2;
				switch (code) {
				case 0x8304: sb.append('□'); break;
				case 0x8C01: sb.append('■'); break;
				case 0x8C02: sb.append('◆'); break;
				case 0x8C03: sb.append('●'); break;
				case 0x8305: sb.append('×'); break;
				case 0x8C12: sb.append("lower "); break;
				case 0x8C13: sb.append("upper "); break;
				case 0x8C14: sb.append('→'); break;
				default: break;
				}
				continue;
			}
			if (c == '[' && i + 2 < u.length && u[i + 1] == 'd' && u[i + 2] == ':') {
				depth++;
				sb.append('X');
				i += 2;
				continue;
			}
			if (depth > 0) {
				if (c == ']') {
					depth--;
				}
				continue;
			}
			sb.append((char)c);
		}
		return sb.toString().trim();
	}

	// The full texts are broken into lines for the PSP's card window, with '|' between pages and a
	// '~' line before a quote. Lines that carry on a sentence are joined; stat lines and attack
	// area grids keep their breaks.
	static String reflow(String text) {
		StringBuilder out = new StringBuilder();
		String[] pages = text.split("\\|");
		for (int p = 0; p < pages.length; p++) {
			String page = pages[p].trim();
			if (page.isEmpty()) {
				continue;
			}
			if (out.length() > 0) {
				// A page that starts in lowercase finishes the previous sentence.
				out.append(Character.isLowerCase(page.charAt(0)) ? " " : "\n\n");
			}
			String[] lines = page.split("\n");
			for (int i = 0; i < lines.length; i++) {
				String line = lines[i].trim();
				if (line.equals("~")) {
					out.append("\n");
					continue;
				}
				out.append(line);
				if (i + 1 < lines.length) {
					out.append(joins(line, lines[i + 1].trim()) ? " " : "\n");
				}
			}
		}
		return out.toString();
	}

	private static boolean joins(String line, String next) {
		if (line.isEmpty() || next.isEmpty() || next.equals("~") || isGrid(line) || isGrid(next) || isLabel(next)) {
			return false;
		}
		char last = line.charAt(line.length() - 1);
		return Character.isLowerCase(last) || last == ',' || Character.isLowerCase(next.charAt(0));
	}

	// "ATK:10", "Notes: ...", "HIT % decrease at: ..." start a stat line of their own.
	private static boolean isLabel(String line) {
		return line.matches("^[A-Za-z][A-Za-z %.]*:.*");
	}

	private static boolean isGrid(String line) {
		return line.indexOf('□') >= 0 || line.indexOf('■') >= 0 || line.indexOf('●') >= 0;
	}

	@Override
	public void onDestroyView() {
		stopMove();
		handView = null;
		cardTop = null;
		root = null;
		detailPanel = null;
		detailTitle = detailText = null;
		host = null;
		shownDetail = -2;
	}

	// Draws the art scaled to cover the rectangle, cropped to it.
	private static void drawCover(Canvas canvas, Bitmap bmp, RectF dst, Paint paint) {
		float sw = bmp.getWidth(), sh = bmp.getHeight();
		float scale = Math.max(dst.width() / sw, dst.height() / sh);
		float cw = dst.width() / scale, ch = dst.height() / scale;
		Rect src = new Rect(Math.round((sw - cw) / 2), Math.round((sh - ch) / 2), Math.round((sw + cw) / 2), Math.round((sh + ch) / 2));
		paint.setFilterBitmap(true);
		canvas.drawBitmap(bmp, src, dst, paint);
	}

	// The face of a card in the game: light, with its type's color down the right edge.
	private static final class CardFace extends android.graphics.drawable.Drawable {
		private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
		private final RectF rect = new RectF();
		private final float radius, band;
		private final int color;
		private final boolean showBand;

		CardFace(Context ctx, int color, boolean showBand) {
			radius = DuoUi.dp(ctx, 6);
			band = DuoUi.dp(ctx, 10);
			this.color = color;
			this.showBand = showBand;
		}

		@Override
		public void draw(Canvas canvas) {
			rect.set(getBounds());
			drawFace(canvas, rect, paint, radius, showBand ? band : 0, color);
		}

		@Override
		public void setAlpha(int alpha) {}

		@Override
		public void setColorFilter(android.graphics.ColorFilter filter) {}

		@Override
		public int getOpacity() {
			return android.graphics.PixelFormat.TRANSLUCENT;
		}
	}

	private static void drawFace(Canvas canvas, RectF r, Paint paint, float radius, float band, int color) {
		paint.setStyle(Paint.Style.FILL);
		paint.setShader(new LinearGradient(0, r.top, 0, r.bottom, CARD_FACE_TOP, CARD_FACE_BOTTOM, Shader.TileMode.CLAMP));
		canvas.drawRoundRect(r, radius, radius, paint);
		paint.setShader(null);
		if (band > 0) {
			canvas.save();
			canvas.clipRect(r.right - band, r.top, r.right, r.bottom);
			paint.setColor(color);
			canvas.drawRoundRect(r, radius, radius, paint);
			canvas.restore();
		}
		paint.setStyle(Paint.Style.STROKE);
		paint.setStrokeWidth(Math.max(1, radius / 4));
		paint.setColor(CARD_EDGE);
		canvas.drawRoundRect(r, radius, radius, paint);
		paint.setStyle(Paint.Style.FILL);
	}

	// Letter-spaced name on the dark strip under the art, as on the game's cards.
	private void drawBanner(Canvas canvas, Paint paint, RectF r, String name) {
		paint.setColor(CARD_BANNER);
		canvas.drawRect(r, paint);
		paint.setTypeface(condensed);
		paint.setColor(CARD_BANNER_TEXT);
		paint.setTextAlign(Paint.Align.CENTER);
		paint.setTextSize(r.height() * 0.66f);
		paint.setLetterSpacing(0.08f);
		canvas.drawText(fit(paint, name, r.width() * 0.94f), r.centerX(), r.centerY() + paint.getTextSize() * 0.36f, paint);
		paint.setLetterSpacing(0);
	}

	private static String fit(Paint paint, String text, float max) {
		if (paint.measureText(text) <= max) {
			return text;
		}
		int n = text.length();
		while (n > 1 && paint.measureText(text, 0, n) + paint.measureText("…") > max) {
			n--;
		}
		return text.substring(0, n) + "…";
	}

	// The top of the big card in the detail panel: type, cost, illustration and name.
	private final class CardTopView extends View {
		private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
		private final RectF rect = new RectF();
		private int card = -1;

		CardTopView(Context context) {
			super(context);
		}

		void setCard(int id) {
			if (id != card) {
				card = id;
				invalidate();
			}
		}

		@Override
		protected void onMeasure(int widthSpec, int heightSpec) {
			int w = MeasureSpec.getSize(widthSpec);
			// Header, art at the illustrations' 112:72, name strip.
			int h = Math.round(w * 0.12f + w * 72f / 112f + w * 0.11f + DuoUi.dp(getContext(), 4));
			setMeasuredDimension(w, h);
		}

		@Override
		protected void onDraw(Canvas canvas) {
			if (card < 0) {
				return;
			}
			float w = getWidth();
			int type = types != null ? types[card] : -1;
			float headH = w * 0.12f;
			paint.setTypeface(condensed);
			paint.setColor(CARD_INK);
			paint.setTextAlign(Paint.Align.LEFT);
			paint.setTextSize(headH * 0.72f);
			canvas.drawText(typeName(type), 0, headH * 0.74f, paint);

			float artTop = headH;
			float artH = w * 72f / 112f;
			rect.set(0, artTop, w, artTop + artH);
			Bitmap bmp = illustration(card);
			if (bmp != null) {
				drawCover(canvas, bmp, rect, paint);
			} else {
				paint.setColor(0xFF9A9C9E);
				canvas.drawRect(rect, paint);
			}
			paint.setStyle(Paint.Style.STROKE);
			paint.setStrokeWidth(DuoUi.dp(getContext(), 1));
			paint.setColor(CARD_EDGE);
			canvas.drawRect(rect, paint);
			paint.setStyle(Paint.Style.FILL);

			// The cost, big, over the art's top right corner.
			if (costs != null) {
				String cost = String.valueOf(costs[card]);
				paint.setTextAlign(Paint.Align.RIGHT);
				paint.setTextSize(headH * 1.9f);
				paint.setStyle(Paint.Style.STROKE);
				paint.setStrokeWidth(headH * 0.16f);
				paint.setColor(CARD_FACE_TOP);
				canvas.drawText(cost, w - headH * 0.1f, headH * 1.55f, paint);
				paint.setStyle(Paint.Style.FILL);
				paint.setColor(CARD_INK);
				canvas.drawText(cost, w - headH * 0.1f, headH * 1.55f, paint);
			}

			RectF banner = new RectF(0, artTop + artH, w, artTop + artH + w * 0.11f);
			drawBanner(canvas, paint, banner, cardName(card));
		}
	}

	private final class HandView extends View {
		private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
		private final RectF rect = new RectF();
		private final RectF styleRect = new RectF();
		private final Path path = new Path();
		private int[] cards;
		private int[] stats;  // life, max life, deck, cost, turn
		private int selected = -1;
		private String signature = "";
		private RectF[] cardRects = new RectF[0];

		HandView(Context context) {
			super(context);
		}

		boolean hasCards() {
			return cards != null && cards.length > 0;
		}

		void setData(int[] cards, int[] stats, int selected) {
			String sig = Arrays.toString(cards) + Arrays.toString(stats) + selected + (texts != null) + (costs != null) + (art != null);
			if (sig.equals(signature)) {
				return;
			}
			signature = sig;
			this.cards = cards;
			this.stats = stats;
			this.selected = selected;
			invalidate();
		}

		@Override
		public boolean onTouchEvent(MotionEvent e) {
			if (e.getActionMasked() != MotionEvent.ACTION_UP) {
				return e.getActionMasked() == MotionEvent.ACTION_DOWN;
			}
			if (hasCards() && styleRect.contains(e.getX(), e.getY())) {
				host.haptic(this);
				setGameLook(!gameLook);
				return true;
			}
			for (int i = 0; i < cardRects.length && cards != null && i < cards.length; i++) {
				if (cardRects[i] != null && cardRects[i].contains(e.getX(), e.getY())) {
					tapped = i;
					selected = i;
					host.haptic(this);
					startMove(i);
					showDetail(cards[i]);
					invalidate();
					return true;
				}
			}
			return true;
		}

		@Override
		protected void onDraw(Canvas canvas) {
			Context ctx = getContext();
			float w = getWidth(), h = getHeight();
			paint.setTypeface(gameLook ? condensed : Typeface.DEFAULT_BOLD);
			float top = DuoUi.dp(ctx, 12);

			if (stats != null) {
				top = gameLook ? drawGameStatus(canvas, w, top) : drawStatus(canvas, w, top);
			}
			if (cards == null) {
				paint.setColor(DuoUi.COLOR_TEXT_DIM);
				paint.setTextAlign(Paint.Align.CENTER);
				paint.setTextSize(DuoUi.dp(ctx, 18));
				canvas.drawText(ctx.getString(R.string.duo_mga_idle), w / 2, h / 2, paint);
				cardRects = new RectF[0];
				styleRect.setEmpty();
				return;
			}

			paint.setTextAlign(Paint.Align.LEFT);
			paint.setTextSize(DuoUi.dp(ctx, 14));
			paint.setColor(DuoUi.COLOR_TEXT_DIM);
			float lineH = DuoUi.dp(ctx, 32);
			canvas.drawText(ctx.getString(R.string.duo_mga_hand, cards.length), 0, top + lineH / 2 + DuoUi.dp(ctx, 5), paint);
			drawStyleButton(canvas, w, top, lineH);
			top += lineH + DuoUi.dp(ctx, 8);

			// Two or three columns of cards, as many rows as needed.
			int n = cards.length;
			int cols = n > 8 ? 3 : 2;
			if (gameLook && n > 4) {
				cols = 3;
			}
			int rows = Math.max(1, (n + cols - 1) / cols);
			float gap = DuoUi.dp(ctx, 10);
			float cw = (w - gap * (cols - 1)) / cols;
			float maxH = gameLook ? cw * 1.3f : DuoUi.dp(ctx, 150);
			// Room above the cards for the selected one to rise.
			float lift = gameLook ? DuoUi.dp(ctx, 8) : 0;
			float ch = Math.min(maxH, (h - top - lift - gap * (rows - 1)) / rows);
			top += lift;
			cardRects = new RectF[n];
			for (int i = 0; i < n; i++) {
				float x = (i % cols) * (cw + gap);
				float y = top + (i / cols) * (ch + gap);
				cardRects[i] = new RectF(x, y, x + cw, y + ch);
				if (i != selected) {
					drawAnyCard(canvas, cards[i], cardRects[i], false);
				}
			}
			// The selected card last, over its neighbours.
			if (selected >= 0 && selected < n) {
				drawAnyCard(canvas, cards[selected], cardRects[selected], true);
			}
		}

		private void drawAnyCard(Canvas canvas, int id, RectF r, boolean sel) {
			if (gameLook) {
				drawGameCard(canvas, id, r, sel);
			} else {
				drawCard(canvas, id, r, sel);
			}
		}

		private void drawStyleButton(Canvas canvas, float w, float top, float lineH) {
			Context ctx = getContext();
			String label = ctx.getString(gameLook ? R.string.duo_mga_style_classic : R.string.duo_mga_style_game);
			paint.setTypeface(Typeface.DEFAULT_BOLD);
			paint.setTextSize(DuoUi.dp(ctx, 13));
			float pad = DuoUi.dp(ctx, 12);
			float bw = paint.measureText(label) + 2 * pad;
			styleRect.set(w - bw, top, w, top + lineH);
			paint.setColor(DuoUi.COLOR_SURFACE);
			canvas.drawRoundRect(styleRect, lineH / 2, lineH / 2, paint);
			paint.setColor(DuoUi.COLOR_TEXT);
			paint.setTextAlign(Paint.Align.CENTER);
			canvas.drawText(label, styleRect.centerX(), styleRect.centerY() + paint.getTextSize() * 0.36f, paint);
			paint.setTypeface(gameLook ? condensed : Typeface.DEFAULT_BOLD);
		}

		private float drawStatus(Canvas canvas, float w, float top) {
			Context ctx = getContext();
			int life = stats[0], max = stats[1];
			paint.setTextAlign(Paint.Align.LEFT);
			paint.setTextSize(DuoUi.dp(ctx, 15));
			paint.setColor(DuoUi.COLOR_TEXT_DIM);
			canvas.drawText("SNAKE", 0, top + DuoUi.dp(ctx, 15), paint);
			paint.setTextAlign(Paint.Align.RIGHT);
			paint.setColor(DuoUi.COLOR_TEXT);
			paint.setTextSize(DuoUi.dp(ctx, 20));
			canvas.drawText(life + " / " + max, w, top + DuoUi.dp(ctx, 17), paint);
			float barTop = top + DuoUi.dp(ctx, 26);
			float barH = DuoUi.dp(ctx, 12);
			rect.set(0, barTop, w, barTop + barH);
			paint.setColor(DuoUi.COLOR_SURFACE);
			canvas.drawRoundRect(rect, barH / 2, barH / 2, paint);
			float frac = max > 0 ? Math.max(0, Math.min(1, life / (float)max)) : 0;
			if (frac > 0) {
				rect.set(0, barTop, Math.max(barH, w * frac), barTop + barH);
				paint.setColor(frac > 0.5f ? DuoUi.COLOR_GOOD : frac > 0.25f ? DuoUi.COLOR_WARNING : 0xFFE04848);
				canvas.drawRoundRect(rect, barH / 2, barH / 2, paint);
			}

			// Deck, cost, turn.
			float chipTop = barTop + barH + DuoUi.dp(ctx, 12);
			float chipH = DuoUi.dp(ctx, 48);
			float chipGap = DuoUi.dp(ctx, 10);
			float chipW = (w - 2 * chipGap) / 3;
			String[] labels = {ctx.getString(R.string.duo_mga_deck), ctx.getString(R.string.duo_mga_cost), ctx.getString(R.string.duo_mga_turn)};
			int[] values = {stats[2], stats[3], stats[4]};
			for (int i = 0; i < 3; i++) {
				float x = i * (chipW + chipGap);
				rect.set(x, chipTop, x + chipW, chipTop + chipH);
				paint.setColor(DuoUi.COLOR_SURFACE);
				canvas.drawRoundRect(rect, DuoUi.dp(ctx, 10), DuoUi.dp(ctx, 10), paint);
				paint.setTextAlign(Paint.Align.LEFT);
				paint.setTextSize(DuoUi.dp(ctx, 13));
				paint.setColor(DuoUi.COLOR_TEXT_DIM);
				canvas.drawText(labels[i], x + DuoUi.dp(ctx, 12), chipTop + chipH / 2 + DuoUi.dp(ctx, 5), paint);
				paint.setTextAlign(Paint.Align.RIGHT);
				paint.setTextSize(DuoUi.dp(ctx, 22));
				paint.setColor(i == 1 && values[i] > 0 ? DuoUi.COLOR_WARNING : DuoUi.COLOR_TEXT);
				canvas.drawText(String.valueOf(values[i]), x + chipW - DuoUi.dp(ctx, 12), chipTop + chipH / 2 + DuoUi.dp(ctx, 8), paint);
			}
			return chipTop + chipH + DuoUi.dp(ctx, 14);
		}

		// As the game's HUD: "SNAKE ◆ life/max" over a thin white bar, and the black panel with the
		// deck, cost (a stopwatch) and turn.
		private float drawGameStatus(Canvas canvas, float w, float top) {
			Context ctx = getContext();
			int life = stats[0], max = stats[1];
			float panelW = Math.min(w * 0.42f, DuoUi.dp(ctx, 230));
			float leftW = w - panelW - DuoUi.dp(ctx, 14);
			paint.setTextAlign(Paint.Align.LEFT);
			paint.setTextSize(DuoUi.dp(ctx, 17));
			paint.setColor(0xFFF0F0F0);
			canvas.drawText("SNAKE", 0, top + DuoUi.dp(ctx, 17), paint);
			paint.setTextAlign(Paint.Align.RIGHT);
			paint.setTextSize(DuoUi.dp(ctx, 22));
			canvas.drawText("◆ " + life + "/" + max, leftW, top + DuoUi.dp(ctx, 19), paint);
			float barTop = top + DuoUi.dp(ctx, 28);
			float barH = DuoUi.dp(ctx, 7);
			rect.set(0, barTop, leftW, barTop + barH);
			paint.setColor(0xFF3A3C3E);
			canvas.drawRect(rect, paint);
			float frac = max > 0 ? Math.max(0, Math.min(1, life / (float)max)) : 0;
			rect.set(0, barTop, leftW * frac, barTop + barH);
			paint.setColor(frac > 0.25f ? 0xFFF2F2F2 : 0xFFE04848);
			canvas.drawRect(rect, paint);

			float rowH = DuoUi.dp(ctx, 26);
			float px = w - panelW;
			rect.set(px, top, w, top + rowH * 3 + DuoUi.dp(ctx, 8));
			paint.setColor(0xFF000000);
			canvas.drawRect(rect, paint);
			paint.setColor(0xFFE8E8E8);
			canvas.drawRect(px, top, w, top + DuoUi.dp(ctx, 3), paint);
			int[] values = {stats[2], stats[3], stats[4]};
			for (int i = 0; i < 3; i++) {
				float cy = top + DuoUi.dp(ctx, 6) + rowH * i + rowH / 2;
				int color = i == 1 && values[i] > 0 ? 0xFFF09A2A : 0xFFF0F0F0;
				drawIcon(canvas, i, px + DuoUi.dp(ctx, 18), cy, rowH * 0.32f, color);
				paint.setTextAlign(Paint.Align.RIGHT);
				paint.setTextSize(rowH * 0.85f);
				paint.setColor(color);
				canvas.drawText(String.valueOf(values[i]), w - DuoUi.dp(ctx, 12), cy + rowH * 0.3f, paint);
			}
			return top + rowH * 3 + DuoUi.dp(ctx, 20);
		}

		// 0 deck (a stack of cards), 1 cost (a stopwatch), 2 turn (a play arrow).
		private void drawIcon(Canvas canvas, int which, float cx, float cy, float r, int color) {
			paint.setColor(color);
			if (which == 0) {
				paint.setStyle(Paint.Style.STROKE);
				paint.setStrokeWidth(r * 0.25f);
				rect.set(cx - r * 0.5f, cy - r * 0.9f, cx + r * 0.7f, cy + r * 0.6f);
				canvas.drawRect(rect, paint);
				paint.setStyle(Paint.Style.FILL);
				rect.set(cx - r * 0.8f, cy - r * 0.6f, cx + r * 0.4f, cy + r * 0.9f);
				canvas.drawRect(rect, paint);
			} else if (which == 1) {
				paint.setStyle(Paint.Style.STROKE);
				paint.setStrokeWidth(r * 0.25f);
				canvas.drawCircle(cx, cy + r * 0.1f, r * 0.8f, paint);
				canvas.drawLine(cx, cy + r * 0.1f, cx, cy - r * 0.45f, paint);
				canvas.drawLine(cx - r * 0.3f, cy - r, cx + r * 0.3f, cy - r, paint);
				paint.setStyle(Paint.Style.FILL);
			} else {
				path.reset();
				path.moveTo(cx - r * 0.6f, cy - r * 0.85f);
				path.lineTo(cx + r * 0.8f, cy);
				path.lineTo(cx - r * 0.6f, cy + r * 0.85f);
				path.close();
				canvas.drawPath(path, paint);
			}
		}

		private void drawCard(Canvas canvas, int id, RectF r, boolean sel) {
			Context ctx = getContext();
			int type = types != null ? types[id] : -1;
			int color = typeColor(type);
			float radius = DuoUi.dp(ctx, 10);
			paint.setStyle(Paint.Style.FILL);
			paint.setColor(DuoUi.COLOR_SURFACE);
			canvas.drawRoundRect(r, radius, radius, paint);

			// Header strip in the type's color, like the game's cards.
			float headH = Math.min(DuoUi.dp(ctx, 26), r.height() * 0.22f);
			canvas.save();
			canvas.clipRect(r.left, r.top, r.right, r.top + headH);
			paint.setColor(color);
			canvas.drawRoundRect(r, radius, radius, paint);
			canvas.restore();
			float inner = DuoUi.dp(ctx, 10);
			paint.setColor(0xFF10141A);
			paint.setTextAlign(Paint.Align.LEFT);
			paint.setTextSize(headH * 0.6f);
			canvas.drawText(typeName(type), r.left + inner, r.top + headH * 0.72f, paint);
			if (costs != null) {
				paint.setTextAlign(Paint.Align.RIGHT);
				paint.setTextSize(headH * 0.85f);
				canvas.drawText(String.valueOf(costs[id]), r.right - inner, r.top + headH * 0.82f, paint);
			}

			// Name, then the card's own short text.
			float y = r.top + headH + DuoUi.dp(ctx, 4);
			float nameSize = Math.min(DuoUi.dp(ctx, 18), r.height() * 0.15f);
			paint.setTextAlign(Paint.Align.LEFT);
			paint.setTextSize(nameSize);
			paint.setColor(DuoUi.COLOR_TEXT);
			y += nameSize;
			canvas.drawText(fit(paint, cardName(id), r.width() - 2 * inner), r.left + inner, y, paint);
			String info = text(TEXT_SHORT + id);
			if (info != null) {
				paint.setTypeface(Typeface.DEFAULT);
				float lineSize = Math.min(DuoUi.dp(ctx, 14), r.height() * 0.11f);
				paint.setTextSize(lineSize);
				paint.setColor(DuoUi.COLOR_TEXT_DIM);
				for (String line : info.split("\n")) {
					y += lineSize * 1.25f;
					if (y > r.bottom - inner / 2) {
						break;
					}
					canvas.drawText(fit(paint, line, r.width() - 2 * inner), r.left + inner, y, paint);
				}
				paint.setTypeface(Typeface.DEFAULT_BOLD);
			}

			if (sel) {
				paint.setStyle(Paint.Style.STROKE);
				float stroke = DuoUi.dp(ctx, 3);
				paint.setStrokeWidth(stroke);
				paint.setColor(DuoUi.COLOR_TEXT);
				// Inside the card, so the edge of the view doesn't cut it off.
				rect.set(r);
				rect.inset(stroke / 2, stroke / 2);
				canvas.drawRoundRect(rect, radius, radius, paint);
				paint.setStyle(Paint.Style.FILL);
			}
		}

		// A card as the game draws it: type and cost on top, the illustration, the name on a dark
		// strip, then the short text; the selected one rises, with the game's cursor over it.
		private void drawGameCard(Canvas canvas, int id, RectF r0, boolean sel) {
			Context ctx = getContext();
			int type = types != null ? types[id] : -1;
			float lift = sel ? DuoUi.dp(ctx, 8) : 0;
			RectF r = new RectF(r0.left, r0.top - lift, r0.right, r0.bottom - lift);
			float radius = DuoUi.dp(ctx, 5);
			float band = Math.max(DuoUi.dp(ctx, 5), r.width() * 0.05f);
			drawFace(canvas, r, paint, radius, band, typeColor(type));

			float inner = DuoUi.dp(ctx, 5);
			float left = r.left + inner, right = r.right - band - inner / 2;
			float iw = right - left;
			float headH = Math.min(r.height() * 0.12f, iw * 0.15f);
			paint.setTypeface(condensed);
			paint.setColor(CARD_INK);
			paint.setTextAlign(Paint.Align.LEFT);
			paint.setTextSize(headH * 0.8f);
			canvas.drawText(typeName(type), left, r.top + inner + headH * 0.78f, paint);

			float artTop = r.top + inner + headH + DuoUi.dp(ctx, 2);
			float artH = Math.min(iw * 72f / 112f, r.height() * 0.5f);
			rect.set(left, artTop, right, artTop + artH);
			Bitmap bmp = illustration(id);
			if (bmp != null) {
				drawCover(canvas, bmp, rect, paint);
			} else {
				paint.setColor(0xFF9A9C9E);
				canvas.drawRect(rect, paint);
			}
			if (costs != null) {
				String cost = String.valueOf(costs[id]);
				paint.setTextAlign(Paint.Align.RIGHT);
				paint.setTextSize(headH * 1.7f);
				float cy = r.top + inner + headH * 1.35f;
				paint.setStyle(Paint.Style.STROKE);
				paint.setStrokeWidth(headH * 0.18f);
				paint.setColor(CARD_FACE_TOP);
				canvas.drawText(cost, right, cy, paint);
				paint.setStyle(Paint.Style.FILL);
				paint.setColor(CARD_INK);
				canvas.drawText(cost, right, cy, paint);
			}

			float bannerH = Math.max(headH * 1.05f, DuoUi.dp(ctx, 14));
			RectF banner = new RectF(left, artTop + artH, right, artTop + artH + bannerH);
			drawBanner(canvas, paint, banner, cardName(id));

			String info = text(TEXT_SHORT + id);
			if (info != null) {
				paint.setTypeface(Typeface.create("sans-serif-condensed", Typeface.NORMAL));
				float lineSize = Math.min(DuoUi.dp(ctx, 14), headH * 0.95f);
				paint.setTextSize(lineSize);
				paint.setColor(CARD_INK);
				paint.setTextAlign(Paint.Align.LEFT);
				float y = banner.bottom + DuoUi.dp(ctx, 2);
				for (String line : info.split("\n")) {
					y += lineSize * 1.2f;
					if (y > r.bottom - inner / 2) {
						break;
					}
					canvas.drawText(fit(paint, line, iw), left, y, paint);
				}
				paint.setTypeface(condensed);
			}

			if (sel) {
				paint.setStyle(Paint.Style.STROKE);
				float stroke = DuoUi.dp(ctx, 3);
				paint.setStrokeWidth(stroke);
				paint.setColor(0xFFFFFFFF);
				rect.set(r);
				rect.inset(stroke / 2, stroke / 2);
				canvas.drawRoundRect(rect, radius, radius, paint);
				paint.setStyle(Paint.Style.FILL);
				// The game's cursor: a triangle pointing down at the card.
				float t = DuoUi.dp(ctx, 9);
				float cx = r.centerX();
				path.reset();
				path.moveTo(cx - t, r.top - t * 1.4f);
				path.lineTo(cx + t, r.top - t * 1.4f);
				path.lineTo(cx, r.top - t * 0.2f);
				path.close();
				paint.setColor(0xFFFFFFFF);
				canvas.drawPath(path, paint);
			}
		}
	}
}
