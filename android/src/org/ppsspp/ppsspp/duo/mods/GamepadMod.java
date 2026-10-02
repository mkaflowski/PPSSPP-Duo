package org.ppsspp.ppsspp.duo.mods;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.SparseArray;
import android.view.MotionEvent;
import android.view.View;

import org.ppsspp.ppsspp.R;
import org.ppsspp.ppsspp.duo.DuoMod;
import org.ppsspp.ppsspp.duo.DuoModContext;
import org.ppsspp.ppsspp.duo.DuoNative;
import org.ppsspp.ppsspp.duo.DuoStatus;
import org.ppsspp.ppsspp.duo.DuoUi;

// Full PSP touch gamepad on the second screen, multi-touch.
public final class GamepadMod extends DuoMod {
	public static final String ID = "gamepad";

	private GamepadView view;

	@Override
	public String getId() {
		return ID;
	}

	@Override
	public String getTitle(Context context) {
		return context.getString(R.string.duo_mod_gamepad);
	}

	@Override
	public String getDescription(Context context) {
		return context.getString(R.string.duo_mod_gamepad_desc);
	}

	@Override
	public long getStatusIntervalMs() {
		return 500;
	}

	@Override
	public View onCreateView(DuoModContext host) {
		view = new GamepadView(host.getContext(), host);
		return view;
	}

	@Override
	public void onStatus(DuoStatus status) {
		view.setGameRunning(status.hasGame());
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

	private static final class GamepadView extends View {
		private static final int FACE_TRIANGLE = 0xFF3FD3A5;
		private static final int FACE_CIRCLE = 0xFFF2637E;
		private static final int FACE_CROSS = 0xFF7FA8F5;
		private static final int FACE_SQUARE = 0xFFE58AD8;

		private final DuoModContext host;
		private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
		private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
		private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
		private final Path path = new Path();
		private final RectF rect = new RectF();

		// Geometry, computed in onSizeChanged.
		private float dpadX, dpadY, dpadR;
		private float faceX, faceY, faceR, faceButtonR;
		private float stickX, stickY, stickR;
		private final RectF lRect = new RectF();
		private final RectF rRect = new RectF();
		private final RectF selectRect = new RectF();
		private final RectF startRect = new RectF();

		// Per pointer: the buttons it holds, or that it owns the stick.
		private final SparseArray<Integer> pointerButtons = new SparseArray<>();
		private int stickPointer = -1;
		private float stickDx, stickDy;  // -1..1, screen coordinates
		private int heldMask;
		private boolean gameRunning = true;

		GamepadView(Context context, DuoModContext host) {
			super(context);
			this.host = host;
			stroke.setStyle(Paint.Style.STROKE);
			label.setTextAlign(Paint.Align.CENTER);
			label.setTypeface(Typeface.DEFAULT_BOLD);
			setBackgroundColor(DuoUi.COLOR_BACKGROUND);
		}

		void setGameRunning(boolean running) {
			if (running != gameRunning) {
				gameRunning = running;
				if (!running) {
					releaseAll();
				}
				invalidate();
			}
		}

		@Override
		protected void onSizeChanged(int w, int h, int oldw, int oldh) {
			// Top row: L, SELECT, START, R. Middle: d-pad and face buttons. Bottom center: stick.
			float unit = Math.min(w / 3.2f, h / 2.2f);
			float m = DuoUi.dp(getContext(), 12);

			float shoulderW = w * 0.26f;
			float shoulderH = Math.max(unit * 0.32f, DuoUi.dp(getContext(), 44));
			lRect.set(m, m, m + shoulderW, m + shoulderH);
			rRect.set(w - m - shoulderW, m, w - m, m + shoulderH);

			float gap = rRect.left - lRect.right;
			float smallW = Math.min(gap * 0.4f, Math.max(unit * 0.55f, DuoUi.dp(getContext(), 72)));
			float smallH = shoulderH * 0.8f;
			float cy = m + shoulderH / 2;
			float cx = w * 0.5f;
			selectRect.set(cx - smallW - m * 0.5f, cy - smallH / 2, cx - m * 0.5f, cy + smallH / 2);
			startRect.set(cx + m * 0.5f, cy - smallH / 2, cx + smallW + m * 0.5f, cy + smallH / 2);

			float top = m + shoulderH;
			dpadR = unit * 0.55f;
			dpadX = m + dpadR + w * 0.03f;
			dpadY = top + (h - top) * 0.45f;

			faceR = dpadR;
			faceX = w - m - faceR - w * 0.03f;
			faceY = dpadY;
			faceButtonR = faceR * 0.38f;

			stickR = unit * 0.42f;
			stickX = w * 0.5f;
			stickY = h - m - stickR;
		}

		// Buttons under a point. The d-pad and face areas resolve diagonals to two buttons.
		private int hitTest(float x, float y) {
			if (lRect.contains(x, y)) {
				return DuoNative.CTRL_LTRIGGER;
			}
			if (rRect.contains(x, y)) {
				return DuoNative.CTRL_RTRIGGER;
			}
			if (selectRect.contains(x, y)) {
				return DuoNative.CTRL_SELECT;
			}
			if (startRect.contains(x, y)) {
				return DuoNative.CTRL_START;
			}
			float dx = x - dpadX;
			float dy = y - dpadY;
			float dist = (float)Math.hypot(dx, dy);
			if (dist < dpadR * 1.25f && dist > dpadR * 0.18f) {
				double angle = Math.toDegrees(Math.atan2(dy, dx));  // 0 = right, 90 = down
				int mask = 0;
				if (angle > -67.5 && angle < 67.5) mask |= DuoNative.CTRL_RIGHT;
				if (angle > 22.5 && angle < 157.5) mask |= DuoNative.CTRL_DOWN;
				if (angle > 112.5 || angle < -112.5) mask |= DuoNative.CTRL_LEFT;
				if (angle > -157.5 && angle < -22.5) mask |= DuoNative.CTRL_UP;
				return mask;
			}
			dx = x - faceX;
			dy = y - faceY;
			if (Math.hypot(dx, dy) < faceR * 1.3f) {
				// Closest face button, generous radius so thumbs between two buttons press both.
				int mask = 0;
				float reach = faceButtonR * 1.45f;
				if (Math.hypot(x - faceX, y - (faceY - faceR + faceButtonR)) < reach) mask |= DuoNative.CTRL_TRIANGLE;
				if (Math.hypot(x - (faceX + faceR - faceButtonR), y - faceY) < reach) mask |= DuoNative.CTRL_CIRCLE;
				if (Math.hypot(x - faceX, y - (faceY + faceR - faceButtonR)) < reach) mask |= DuoNative.CTRL_CROSS;
				if (Math.hypot(x - (faceX - faceR + faceButtonR), y - faceY) < reach) mask |= DuoNative.CTRL_SQUARE;
				return mask;
			}
			return 0;
		}

		@Override
		public boolean onTouchEvent(MotionEvent ev) {
			int action = ev.getActionMasked();
			int index = ev.getActionIndex();
			switch (action) {
			case MotionEvent.ACTION_DOWN:
			case MotionEvent.ACTION_POINTER_DOWN: {
				int id = ev.getPointerId(index);
				float x = ev.getX(index);
				float y = ev.getY(index);
				if (stickPointer < 0 && Math.hypot(x - stickX, y - stickY) < stickR * 1.3f) {
					stickPointer = id;
					updateStick(x, y);
				} else {
					pointerButtons.put(id, hitTest(x, y));
				}
				break;
			}
			case MotionEvent.ACTION_MOVE:
				for (int i = 0; i < ev.getPointerCount(); i++) {
					int id = ev.getPointerId(i);
					if (id == stickPointer) {
						updateStick(ev.getX(i), ev.getY(i));
					} else if (pointerButtons.indexOfKey(id) >= 0) {
						pointerButtons.put(id, hitTest(ev.getX(i), ev.getY(i)));
					}
				}
				break;
			case MotionEvent.ACTION_UP:
			case MotionEvent.ACTION_POINTER_UP: {
				int id = ev.getPointerId(index);
				if (id == stickPointer) {
					stickPointer = -1;
					stickDx = 0;
					stickDy = 0;
					host.setAnalog(DuoNative.STICK_LEFT, 0, 0);
				} else {
					pointerButtons.remove(id);
				}
				break;
			}
			case MotionEvent.ACTION_CANCEL:
				releaseAll();
				return true;
			default:
				return true;
			}
			applyButtons();
			invalidate();
			return true;
		}

		private void updateStick(float x, float y) {
			float dx = (x - stickX) / stickR;
			float dy = (y - stickY) / stickR;
			float len = (float)Math.hypot(dx, dy);
			if (len > 1.0f) {
				dx /= len;
				dy /= len;
			}
			stickDx = dx;
			stickDy = dy;
			host.setAnalog(DuoNative.STICK_LEFT, dx, -dy);
		}

		private void applyButtons() {
			int mask = 0;
			for (int i = 0; i < pointerButtons.size(); i++) {
				mask |= pointerButtons.valueAt(i);
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
		}

		void releaseAll() {
			pointerButtons.clear();
			if (heldMask != 0) {
				host.pressButton(heldMask, false);
				heldMask = 0;
			}
			if (stickPointer >= 0 || stickDx != 0 || stickDy != 0) {
				host.setAnalog(DuoNative.STICK_LEFT, 0, 0);
			}
			stickPointer = -1;
			stickDx = 0;
			stickDy = 0;
			invalidate();
		}

		@Override
		protected void onDraw(Canvas canvas) {
			int alpha = gameRunning ? 255 : 90;
			stroke.setStrokeWidth(DuoUi.dp(getContext(), 2));

			drawRectButton(canvas, lRect, "L", DuoNative.CTRL_LTRIGGER, alpha);
			drawRectButton(canvas, rRect, "R", DuoNative.CTRL_RTRIGGER, alpha);
			drawRectButton(canvas, selectRect, "SELECT", DuoNative.CTRL_SELECT, alpha);
			drawRectButton(canvas, startRect, "START", DuoNative.CTRL_START, alpha);

			drawDpad(canvas, alpha);

			drawFace(canvas, faceX, faceY - faceR + faceButtonR, DuoNative.CTRL_TRIANGLE, FACE_TRIANGLE, 0, alpha);
			drawFace(canvas, faceX + faceR - faceButtonR, faceY, DuoNative.CTRL_CIRCLE, FACE_CIRCLE, 1, alpha);
			drawFace(canvas, faceX, faceY + faceR - faceButtonR, DuoNative.CTRL_CROSS, FACE_CROSS, 2, alpha);
			drawFace(canvas, faceX - faceR + faceButtonR, faceY, DuoNative.CTRL_SQUARE, FACE_SQUARE, 3, alpha);

			// Stick.
			fill.setColor(DuoUi.COLOR_SURFACE);
			fill.setAlpha(alpha);
			canvas.drawCircle(stickX, stickY, stickR, fill);
			fill.setColor(stickPointer >= 0 ? DuoUi.COLOR_ACCENT : DuoUi.COLOR_SURFACE_PRESSED);
			fill.setAlpha(alpha);
			canvas.drawCircle(stickX + stickDx * stickR * 0.6f, stickY + stickDy * stickR * 0.6f, stickR * 0.45f, fill);
		}

		private void drawRectButton(Canvas canvas, RectF r, String text, int bit, int alpha) {
			boolean down = (heldMask & bit) != 0;
			fill.setColor(down ? DuoUi.COLOR_ACCENT : DuoUi.COLOR_SURFACE);
			fill.setAlpha(alpha);
			float radius = r.height() / 2;
			canvas.drawRoundRect(r, radius, radius, fill);
			label.setColor(DuoUi.COLOR_TEXT);
			label.setAlpha(alpha);
			label.setTextSize(Math.min(r.height() * 0.45f, r.width() / (text.length() * 0.75f)));
			canvas.drawText(text, r.centerX(), r.centerY() - (label.descent() + label.ascent()) / 2, label);
		}

		private void drawDpad(Canvas canvas, int alpha) {
			float arm = dpadR * 0.36f;
			int[] bits = {DuoNative.CTRL_UP, DuoNative.CTRL_RIGHT, DuoNative.CTRL_DOWN, DuoNative.CTRL_LEFT};
			for (int i = 0; i < 4; i++) {
				canvas.save();
				canvas.rotate(i * 90, dpadX, dpadY);
				rect.set(dpadX - arm, dpadY - dpadR, dpadX + arm, dpadY - arm * 0.6f);
				boolean down = (heldMask & bits[i]) != 0;
				fill.setColor(down ? DuoUi.COLOR_ACCENT : DuoUi.COLOR_SURFACE);
				fill.setAlpha(alpha);
				canvas.drawRoundRect(rect, arm * 0.4f, arm * 0.4f, fill);
				// Arrow.
				path.reset();
				float ay = dpadY - dpadR + arm * 0.9f;
				path.moveTo(dpadX, ay - arm * 0.45f);
				path.lineTo(dpadX - arm * 0.45f, ay + arm * 0.2f);
				path.lineTo(dpadX + arm * 0.45f, ay + arm * 0.2f);
				path.close();
				fill.setColor(DuoUi.COLOR_TEXT_DIM);
				fill.setAlpha(alpha);
				canvas.drawPath(path, fill);
				canvas.restore();
			}
			fill.setColor(DuoUi.COLOR_SURFACE);
			fill.setAlpha(alpha);
			canvas.drawRect(dpadX - arm, dpadY - arm, dpadX + arm, dpadY + arm, fill);
		}

		// symbol: 0 triangle, 1 circle, 2 cross, 3 square.
		private void drawFace(Canvas canvas, float x, float y, int bit, int color, int symbol, int alpha) {
			boolean down = (heldMask & bit) != 0;
			fill.setColor(down ? color : DuoUi.COLOR_SURFACE);
			fill.setAlpha(alpha);
			canvas.drawCircle(x, y, faceButtonR, fill);
			stroke.setColor(down ? Color.WHITE : color);
			stroke.setAlpha(alpha);
			stroke.setStrokeWidth(faceButtonR * 0.11f);
			float s = faceButtonR * 0.42f;
			switch (symbol) {
			case 0:
				path.reset();
				path.moveTo(x, y - s);
				path.lineTo(x + s * 0.95f, y + s * 0.65f);
				path.lineTo(x - s * 0.95f, y + s * 0.65f);
				path.close();
				canvas.drawPath(path, stroke);
				break;
			case 1:
				canvas.drawCircle(x, y, s * 0.9f, stroke);
				break;
			case 2:
				canvas.drawLine(x - s * 0.8f, y - s * 0.8f, x + s * 0.8f, y + s * 0.8f, stroke);
				canvas.drawLine(x - s * 0.8f, y + s * 0.8f, x + s * 0.8f, y - s * 0.8f, stroke);
				break;
			default:
				canvas.drawRect(x - s * 0.75f, y - s * 0.75f, x + s * 0.75f, y + s * 0.75f, stroke);
				break;
			}
		}
	}
}
