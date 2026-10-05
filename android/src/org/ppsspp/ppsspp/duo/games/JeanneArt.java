package org.ppsspp.ppsspp.duo.games;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.Log;

import org.ppsspp.ppsspp.duo.DuoModContext;

// Jeanne d'Arc's battle HUD pieces, cut from the game's own GUI textures (read from the disc at
// runtime, nothing is shipped).
//
// DATA/GUI/MENU04.GIM (256x256) holds the HUD's lettering: two rows of serif digits (the big one,
// with the slash, is what the status window's HP and MP use) and the HP, MP, TURN, Lv, Exp, PLAYER,
// ENEMY and GUEST labels. DATA/GUI/MENU01.GIM (256x128) holds the window parts; the beige strip at
// the top right is the status window's name plate. The rectangles below are the glyphs' bounds
// with a pixel of margin where there's room: filtering samples a pixel beyond the rectangle, so
// one touching a neighbor shows a sliver of it.
final class JeanneArt {
	private static final String TAG = "PPSSPPDuo";
	private static final String MENU04 = "disc0:/PSP_GAME/USRDIR/DATA/GUI/MENU04.GIM";
	private static final String MENU01 = "disc0:/PSP_GAME/USRDIR/DATA/GUI/MENU01.GIM";
	private static final int READ_SIZE = 128 * 1024;

	// Big serif digits 0-9 (cream), and the slash before them.
	private static final Rect[] BIG = rects(42, 55,
		137, 148, 149, 159, 161, 171, 173, 184, 184, 196, 197, 208, 209, 220, 221, 232, 232, 244, 245, 255);
	static final Rect BIG_SLASH = new Rect(126, 42, 135, 55);
	// Small serif digits 0-9 (cream), as after Lv.
	private static final Rect[] SMALL = rects(0, 11,
		156, 165, 167, 175, 176, 185, 186, 195, 196, 206, 206, 216, 216, 226, 226, 236, 236, 246, 246, 256);

	static final Rect LV = new Rect(137, 0, 153, 11);
	static final Rect EXP = new Rect(138, 15, 157, 27);
	static final Rect HP = new Rect(136, 80, 155, 91);
	static final Rect MP = new Rect(155, 80, 176, 91);
	static final Rect TURN = new Rect(177, 80, 205, 91);
	static final Rect PLAYER = new Rect(180, 232, 219, 244);
	static final Rect ENEMY = new Rect(180, 244, 215, 255);
	static final Rect GUEST = new Rect(140, 244, 176, 256);
	// In MENU01.
	static final Rect NAME_PLATE = new Rect(116, 2, 255, 19);

	interface Callback {
		void onReady(JeanneArt art);   // null if the textures can't be read
	}

	private final Bitmap glyphs, window;
	private final RectF dst = new RectF();

	private JeanneArt(Bitmap glyphs, Bitmap window) {
		this.glyphs = glyphs;
		this.window = window;
	}

	static void load(DuoModContext host, Callback cb) {
		boolean ok = host.readGameFile(MENU04, 0, READ_SIZE, a -> {
			Bitmap glyphs = GimImage.decode(a);
			if (glyphs == null || glyphs.getWidth() != 256 || glyphs.getHeight() != 256) {
				Log.w(TAG, "Jeanne: can't read " + MENU04);
				cb.onReady(null);
				return;
			}
			boolean ok2 = host.readGameFile(MENU01, 0, READ_SIZE, b -> {
				Bitmap window = GimImage.decode(b);
				if (window == null || window.getWidth() != 256 || window.getHeight() != 128) {
					Log.w(TAG, "Jeanne: can't read " + MENU01);
					cb.onReady(null);
					return;
				}
				cb.onReady(new JeanneArt(glyphs, window));
			});
			if (!ok2) {
				cb.onReady(null);
			}
		});
		if (!ok) {
			cb.onReady(null);
		}
	}

	private static Rect[] rects(int top, int bottom, int... xs) {
		Rect[] out = new Rect[xs.length / 2];
		for (int i = 0; i < out.length; i++) {
			out[i] = new Rect(xs[i * 2], top, xs[i * 2 + 1], bottom);
		}
		return out;
	}

	// Glyphs touch at their margins, so each advances by its width less one pixel.
	private static float advance(Rect r, float scale) {
		return (r.width() - 1) * scale;
	}

	// Width of a label drawn h tall.
	static float width(Rect r, float h) {
		return advance(r, h / r.height());
	}

	// Draws a label h tall with its top left at (x, top). Returns its width.
	float label(Canvas canvas, Rect r, float x, float top, float h, Paint paint) {
		dst.set(x, top, x + r.width() * h / r.height(), top + h);
		canvas.drawBitmap(glyphs, r, dst, paint);
		return width(r, h);
	}

	float numberWidth(int value, boolean big, float h) {
		Rect[] set = big ? BIG : SMALL;
		float scale = h / set[0].height();
		String s = Integer.toString(Math.max(0, value));
		float w = 0;
		for (int i = 0; i < s.length(); i++) {
			w += advance(set[s.charAt(i) - '0'], scale);
		}
		return w;
	}

	// Draws a number h tall, its right edge at right. Returns its width.
	float numberRight(Canvas canvas, int value, boolean big, float right, float top, float h, Paint paint) {
		float w = numberWidth(value, big, h);
		number(canvas, value, big, right - w, top, h, paint);
		return w;
	}

	// Draws a number h tall from x. Returns its width.
	float number(Canvas canvas, int value, boolean big, float x, float top, float h, Paint paint) {
		Rect[] set = big ? BIG : SMALL;
		float scale = h / set[0].height();
		String s = Integer.toString(Math.max(0, value));
		float start = x;
		for (int i = 0; i < s.length(); i++) {
			Rect r = set[s.charAt(i) - '0'];
			dst.set(x, top, x + r.width() * scale, top + h);
			canvas.drawBitmap(glyphs, r, dst, paint);
			x += advance(r, scale);
		}
		return x - start;
	}

	void namePlate(Canvas canvas, RectF where, Paint paint) {
		canvas.drawBitmap(window, NAME_PLATE, where, paint);
	}
}
