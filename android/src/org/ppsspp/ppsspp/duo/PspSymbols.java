package org.ppsspp.ppsspp.duo;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;

// Draws the PSP face button symbols, for mods that show buttons.
public final class PspSymbols {
	private PspSymbols() {}

	public static final int TRIANGLE = 0;
	public static final int CIRCLE = 1;
	public static final int CROSS = 2;
	public static final int SQUARE = 3;

	public static final int COLOR_TRIANGLE = 0xFF3FD3A5;
	public static final int COLOR_CIRCLE = 0xFFF2637E;
	public static final int COLOR_CROSS = 0xFF7FA8F5;
	public static final int COLOR_SQUARE = 0xFFE58AD8;

	private static final Path path = new Path();

	public static int color(int symbol) {
		switch (symbol) {
		case TRIANGLE: return COLOR_TRIANGLE;
		case CIRCLE: return COLOR_CIRCLE;
		case CROSS: return COLOR_CROSS;
		default: return COLOR_SQUARE;
		}
	}

	public static int buttonBit(int symbol) {
		switch (symbol) {
		case TRIANGLE: return DuoNative.CTRL_TRIANGLE;
		case CIRCLE: return DuoNative.CTRL_CIRCLE;
		case CROSS: return DuoNative.CTRL_CROSS;
		default: return DuoNative.CTRL_SQUARE;
		}
	}

	// Outline of the symbol centered on (x, y). size is roughly the half-width. Uses the paint's
	// color; sets its style and stroke width. UI thread only (shares a Path).
	public static void draw(Canvas canvas, Paint paint, int symbol, float x, float y, float size) {
		paint.setStyle(Paint.Style.STROKE);
		paint.setStrokeWidth(size * 0.26f);
		paint.setStrokeJoin(Paint.Join.ROUND);
		paint.setStrokeCap(Paint.Cap.ROUND);
		switch (symbol) {
		case TRIANGLE:
			path.reset();
			path.moveTo(x, y - size);
			path.lineTo(x + size * 0.95f, y + size * 0.65f);
			path.lineTo(x - size * 0.95f, y + size * 0.65f);
			path.close();
			canvas.drawPath(path, paint);
			break;
		case CIRCLE:
			canvas.drawCircle(x, y, size * 0.9f, paint);
			break;
		case CROSS:
			canvas.drawLine(x - size * 0.8f, y - size * 0.8f, x + size * 0.8f, y + size * 0.8f, paint);
			canvas.drawLine(x - size * 0.8f, y + size * 0.8f, x + size * 0.8f, y - size * 0.8f, paint);
			break;
		default:
			canvas.drawRect(x - size * 0.75f, y - size * 0.75f, x + size * 0.75f, y + size * 0.75f, paint);
			break;
		}
	}
}
