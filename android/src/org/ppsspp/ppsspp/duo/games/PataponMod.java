package org.ppsspp.ppsspp.duo.games;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.util.SparseIntArray;
import android.view.MotionEvent;
import android.view.View;

import org.ppsspp.ppsspp.R;
import org.ppsspp.ppsspp.duo.DuoButtonPress;
import org.ppsspp.ppsspp.duo.DuoMod;
import org.ppsspp.ppsspp.duo.DuoModContext;
import org.ppsspp.ppsspp.duo.DuoStatus;
import org.ppsspp.ppsspp.duo.DuoUi;
import org.ppsspp.ppsspp.duo.PspSymbols;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

// Patapon (the first game): big drum pads and a song book on the second screen.
//
// The pads press the face buttons like the PSP does (PATA = square, PON = circle, CHAKA = triangle,
// DON = cross). Every drum hit is shown, including ones from the physical buttons, and the song book
// lights up the songs the current sequence can still become.
//
// Nothing here reads game memory, so it works with any version of the game. A beat indicator would
// need the game's rhythm timer, which hasn't been located yet.
public final class PataponMod extends DuoMod {
	public static final String ID = "patapon";

	// Patapon 1: Europe (tested), USA, Japan.
	private static final Set<String> GAME_IDS = new HashSet<>(Arrays.asList(
		"UCES00995", "UCUS98711", "UCJS10077"));

	// Drums, indexing the pads. The order matches the pad layout below.
	static final int PATA = 0;
	static final int PON = 1;
	static final int CHAKA = 2;
	static final int DON = 3;

	private static final int[] DRUM_SYMBOL = {PspSymbols.SQUARE, PspSymbols.CIRCLE, PspSymbols.TRIANGLE, PspSymbols.CROSS};
	private static final String[] DRUM_NAME = {"PATA", "PON", "CHAKA", "DON"};

	private static final class Song {
		final int nameRes;
		final int[] drums;

		Song(int nameRes, int... drums) {
			this.nameRes = nameRes;
			this.drums = drums;
		}
	}

	// In the order the game teaches them.
	private static final Song[] SONGS = {
		new Song(R.string.duo_patapon_march, PATA, PATA, PATA, PON),
		new Song(R.string.duo_patapon_attack, PON, PON, PATA, PON),
		new Song(R.string.duo_patapon_defend, CHAKA, CHAKA, PATA, PON),
		new Song(R.string.duo_patapon_charge, PON, PON, CHAKA, CHAKA),
		new Song(R.string.duo_patapon_retreat, PON, PATA, PON, PATA),
		new Song(R.string.duo_patapon_jump, DON, DON, CHAKA, CHAKA),
		new Song(R.string.duo_patapon_party, PATA, PON, DON, DON),
		new Song(R.string.duo_patapon_miracle, DON, DON, DON, DON, DON),
	};

	// A gap this long between hits starts a new sequence (one beat is about half a second).
	private static final int SEQUENCE_GAP_MS = 1200;
	private static final long FLASH_MS = 160;
	private static final long SONG_SHOWN_MS = 2000;
	private static final long MISS_SHOWN_MS = 500;

	private DrumView view;

	@Override
	public String getId() {
		return ID;
	}

	@Override
	public String getTitle(Context context) {
		return context.getString(R.string.duo_mod_patapon);
	}

	@Override
	public String getDescription(Context context) {
		return context.getString(R.string.duo_mod_patapon_desc);
	}

	@Override
	public int getPriority(DuoStatus status) {
		// Picked automatically for Patapon, hidden otherwise. Releases not in the list are recognized
		// by title (the sequels are "PATAPON 2" / "パタポン2", so the exact match leaves them out).
		String title = status.title.trim();
		boolean patapon = GAME_IDS.contains(status.gameId) || title.equalsIgnoreCase("PATAPON") || title.equals("パタポン");
		return patapon ? 100 : -1;
	}

	@Override
	public long getStatusIntervalMs() {
		// Fast enough that hits from the physical buttons show up in time with the music.
		return 33;
	}

	@Override
	public View onCreateView(DuoModContext host) {
		view = new DrumView(host.getContext(), host);
		return view;
	}

	@Override
	public void onStatus(DuoStatus status) {
		view.setGameRunning(status.isInGame());
		view.onPresses(view.host.pollButtonPresses());
		view.tick();
	}

	@Override
	public void onHostPause() {
		view.releaseAll();
	}

	@Override
	public void onDestroyView() {
		view.releaseAll();
		view = null;
	}

	private static final class DrumView extends View {
		final DuoModContext host;

		private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
		private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
		private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
		private final RectF rect = new RectF();
		private final String[] songNames = new String[SONGS.length];

		// Layout.
		private final float[] padX = new float[4];
		private final float[] padY = new float[4];
		private float padR;
		private final RectF bookRect = new RectF();
		private final RectF trailRect = new RectF();

		// Touch: pointer id -> pad index.
		private final SparseIntArray pointerPad = new SparseIntArray();
		private int heldMask;

		// Rhythm state.
		private final List<Integer> sequence = new ArrayList<>();
		private int lastHitMs;
		private long lastHitUptime;
		private final long[] padFlashUntil = new long[4];
		private int shownSong = -1;
		private long shownSongUntil;
		private final List<Integer> missed = new ArrayList<>();
		private long missedUntil;
		private boolean gameRunning = true;

		DrumView(Context context, DuoModContext host) {
			super(context);
			this.host = host;
			setBackgroundColor(DuoUi.COLOR_BACKGROUND);
			text.setTypeface(Typeface.DEFAULT_BOLD);
			for (int i = 0; i < SONGS.length; i++) {
				songNames[i] = context.getString(SONGS[i].nameRes);
			}
		}

		void setGameRunning(boolean running) {
			if (running != gameRunning) {
				gameRunning = running;
				invalidate();
			}
		}

		@Override
		protected void onSizeChanged(int w, int h, int oldw, int oldh) {
			float m = DuoUi.dp(getContext(), 10);
			bookRect.set(m, m, w * 0.40f, h - m);

			float left = w * 0.40f + m;
			trailRect.set(left, m, w - m, m + h * 0.17f);

			float areaTop = trailRect.bottom + m;
			float areaW = w - m - left;
			float areaH = h - m - areaTop;
			float cx = left + areaW / 2;
			float cy = areaTop + areaH / 2;
			// Diamond like the PSP face buttons: PATA left, PON right, CHAKA top, DON bottom.
			padR = Math.min(areaW, areaH) / 5.4f;
			float dx = Math.min(areaW / 2 - padR, padR * 2.0f);
			float dy = Math.min(areaH / 2 - padR, padR * 2.0f);
			padX[PATA] = cx - dx; padY[PATA] = cy;
			padX[PON] = cx + dx; padY[PON] = cy;
			padX[CHAKA] = cx; padY[CHAKA] = cy - dy;
			padX[DON] = cx; padY[DON] = cy + dy;
		}

		// Drum hits from any source, including our own pads.
		void onPresses(List<DuoButtonPress> presses) {
			for (DuoButtonPress p : presses) {
				for (int drum = 0; drum < 4; drum++) {
					if ((p.buttons & PspSymbols.buttonBit(DRUM_SYMBOL[drum])) != 0) {
						onHit(drum, p.timeMs);
					}
				}
			}
		}

		private void onHit(int drum, int timeMs) {
			long now = SystemClock.uptimeMillis();
			padFlashUntil[drum] = now + FLASH_MS;
			if (!sequence.isEmpty() && timeMs - lastHitMs > SEQUENCE_GAP_MS) {
				sequence.clear();
			}
			lastHitMs = timeMs;
			lastHitUptime = now;
			sequence.add(drum);

			int song = completedSong();
			if (song >= 0) {
				shownSong = song;
				shownSongUntil = now + SONG_SHOWN_MS;
				sequence.clear();
			} else if (!isPrefixOfAnySong(sequence)) {
				missed.clear();
				missed.addAll(sequence);
				missedUntil = now + MISS_SHOWN_MS;
				sequence.clear();
				// The wrong hit may be the start of the next song.
				sequence.add(drum);
				if (!isPrefixOfAnySong(sequence)) {
					sequence.clear();
				}
			}
			invalidate();
		}

		private int completedSong() {
			for (int i = 0; i < SONGS.length; i++) {
				if (matchedPrefix(SONGS[i], sequence) == SONGS[i].drums.length) {
					return i;
				}
			}
			return -1;
		}

		private static int matchedPrefix(Song song, List<Integer> seq) {
			if (seq.size() > song.drums.length) {
				return -1;
			}
			for (int i = 0; i < seq.size(); i++) {
				if (song.drums[i] != seq.get(i)) {
					return -1;
				}
			}
			return seq.size();
		}

		private static boolean isPrefixOfAnySong(List<Integer> seq) {
			for (Song s : SONGS) {
				if (matchedPrefix(s, seq) >= 0) {
					return true;
				}
			}
			return false;
		}

		void tick() {
			long now = SystemClock.uptimeMillis();
			if (!sequence.isEmpty() && now - lastHitUptime > SEQUENCE_GAP_MS) {
				sequence.clear();
				invalidate();
			}
			boolean animating = shownSongUntil > now || missedUntil > now;
			for (long t : padFlashUntil) {
				animating |= t > now;
			}
			if (animating) {
				invalidate();
			}
		}

		private int padAt(float x, float y) {
			int best = -1;
			double bestDist = padR * 1.4;
			for (int i = 0; i < 4; i++) {
				double dist = Math.hypot(x - padX[i], y - padY[i]);
				if (dist < bestDist) {
					best = i;
					bestDist = dist;
				}
			}
			return best;
		}

		@Override
		public boolean onTouchEvent(MotionEvent ev) {
			int action = ev.getActionMasked();
			int index = ev.getActionIndex();
			switch (action) {
			case MotionEvent.ACTION_DOWN:
			case MotionEvent.ACTION_POINTER_DOWN:
				pointerPad.put(ev.getPointerId(index), padAt(ev.getX(index), ev.getY(index)));
				break;
			case MotionEvent.ACTION_MOVE:
				// A drum is a hit, not a slide: a finger keeps the pad it went down on.
				return true;
			case MotionEvent.ACTION_UP:
			case MotionEvent.ACTION_POINTER_UP:
				pointerPad.delete(ev.getPointerId(index));
				break;
			case MotionEvent.ACTION_CANCEL:
				releaseAll();
				return true;
			default:
				return true;
			}
			applyButtons();
			return true;
		}

		private void applyButtons() {
			int mask = 0;
			for (int i = 0; i < pointerPad.size(); i++) {
				int pad = pointerPad.valueAt(i);
				if (pad >= 0) {
					mask |= PspSymbols.buttonBit(DRUM_SYMBOL[pad]);
				}
			}
			int pressed = mask & ~heldMask;
			int released = heldMask & ~mask;
			if (released != 0) {
				host.pressButton(released, false);
			}
			if (pressed != 0) {
				host.pressButton(pressed, true);
				host.haptic(this);
			}
			heldMask = mask;
			invalidate();
		}

		void releaseAll() {
			pointerPad.clear();
			if (heldMask != 0) {
				host.pressButton(heldMask, false);
				heldMask = 0;
			}
			invalidate();
		}

		@Override
		protected void onDraw(Canvas canvas) {
			long now = SystemClock.uptimeMillis();
			drawBook(canvas, now);
			drawTrail(canvas, now);
			for (int i = 0; i < 4; i++) {
				drawPad(canvas, i, now);
			}
		}

		private void drawBook(Canvas canvas, long now) {
			float rowGap = DuoUi.dp(getContext(), 5);
			float rowH = (bookRect.height() - rowGap * (SONGS.length - 1)) / SONGS.length;
			float radius = DuoUi.dp(getContext(), 10);
			for (int i = 0; i < SONGS.length; i++) {
				Song song = SONGS[i];
				float top = bookRect.top + i * (rowH + rowGap);
				rect.set(bookRect.left, top, bookRect.right, top + rowH);

				int matched = sequence.isEmpty() ? 0 : Math.max(0, matchedPrefix(song, sequence));
				boolean justPlayed = shownSong == i && shownSongUntil > now;
				fill.setStyle(Paint.Style.FILL);
				fill.setColor(justPlayed ? DuoUi.COLOR_ACCENT : matched > 0 ? DuoUi.COLOR_SURFACE_PRESSED : DuoUi.COLOR_SURFACE);
				canvas.drawRoundRect(rect, radius, radius, fill);

				text.setTextAlign(Paint.Align.LEFT);
				text.setTextSize(rowH * 0.36f);
				text.setColor(matched > 0 || justPlayed ? DuoUi.COLOR_TEXT : DuoUi.COLOR_TEXT_DIM);
				canvas.drawText(songNames[i], rect.left + rowH * 0.3f, rect.centerY() - (text.descent() + text.ascent()) / 2, text);

				// Drum icons, right-aligned in the right half. Miracle has five.
				float iconSize = Math.min(rowH * 0.2f, rect.width() * 0.5f / (5 * 2.7f));
				float step = iconSize * 2.7f;
				float x = rect.right - rowH * 0.3f - iconSize;
				for (int d = song.drums.length - 1; d >= 0; d--) {
					int symbol = DRUM_SYMBOL[song.drums[d]];
					boolean lit = d < matched || justPlayed;
					stroke.setColor(lit ? PspSymbols.color(symbol) : DuoUi.COLOR_TEXT_DISABLED);
					PspSymbols.draw(canvas, stroke, symbol, x, rect.centerY(), iconSize);
					x -= step;
				}
			}
		}

		private void drawTrail(Canvas canvas, long now) {
			float radius = DuoUi.dp(getContext(), 12);
			fill.setStyle(Paint.Style.FILL);
			fill.setColor(DuoUi.COLOR_SURFACE);
			canvas.drawRoundRect(trailRect, radius, radius, fill);

			float cy = trailRect.centerY();
			if (shownSongUntil > now && shownSong >= 0) {
				text.setTextAlign(Paint.Align.CENTER);
				text.setTextSize(trailRect.height() * 0.5f);
				text.setColor(DuoUi.COLOR_GOOD);
				canvas.drawText(songNames[shownSong].toUpperCase(java.util.Locale.getDefault()) + "!", trailRect.centerX(), cy - (text.descent() + text.ascent()) / 2, text);
				return;
			}

			List<Integer> drums = missedUntil > now ? missed : sequence;
			boolean miss = missedUntil > now;
			float iconSize = trailRect.height() * 0.22f;
			float step = trailRect.width() / 5.0f;
			float x = trailRect.left + step / 2;
			for (int i = 0; i < 5; i++) {
				if (i < drums.size()) {
					int symbol = DRUM_SYMBOL[drums.get(i)];
					stroke.setColor(miss ? DuoUi.COLOR_WARNING : PspSymbols.color(symbol));
					PspSymbols.draw(canvas, stroke, symbol, x, cy, iconSize);
				} else {
					fill.setColor(DuoUi.COLOR_SURFACE_PRESSED);
					canvas.drawCircle(x, cy, iconSize * 0.25f, fill);
				}
				x += step;
			}
		}

		private void drawPad(Canvas canvas, int drum, long now) {
			int symbol = DRUM_SYMBOL[drum];
			int color = PspSymbols.color(symbol);
			boolean held = (heldMask & PspSymbols.buttonBit(symbol)) != 0;
			boolean lit = held || padFlashUntil[drum] > now;

			fill.setStyle(Paint.Style.FILL);
			fill.setColor(lit ? color : DuoUi.COLOR_SURFACE);
			fill.setAlpha(gameRunning ? 255 : 110);
			canvas.drawCircle(padX[drum], padY[drum], padR, fill);
			fill.setAlpha(255);

			stroke.setColor(lit ? DuoUi.COLOR_BACKGROUND : color);
			PspSymbols.draw(canvas, stroke, symbol, padX[drum], padY[drum] - padR * 0.18f, padR * 0.3f);

			text.setTextAlign(Paint.Align.CENTER);
			text.setTextSize(padR * 0.24f);
			text.setColor(lit ? DuoUi.COLOR_BACKGROUND : DuoUi.COLOR_TEXT);
			canvas.drawText(DRUM_NAME[drum], padX[drum], padY[drum] + padR * 0.52f, text);
		}
	}
}
