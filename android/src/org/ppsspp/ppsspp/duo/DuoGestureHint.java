package org.ppsspp.ppsspp.duo;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.ppsspp.ppsspp.R;

// Shown over the mod the first time the tab bar is hidden: the parts of the side edges the back
// gesture works from are marked, and two bubbles slide in from them.
final class DuoGestureHint extends FrameLayout {
	private static final float BUBBLE_DP = 44;
	private static final float TRAVEL_DP = 70;
	private static final float TEXT_WIDTH_DP = 300;

	private final View leftBubble;
	private final View rightBubble;
	private final ValueAnimator animator;

	DuoGestureHint(Context ctx, float zoneDp, View.OnClickListener onDismiss) {
		super(ctx);
		setBackgroundColor(0xE6000000);
		// Taps don't reach the mod underneath.
		setClickable(true);

		zoneMarker(ctx, zoneDp, Gravity.START);
		zoneMarker(ctx, zoneDp, Gravity.END);

		leftBubble = bubble(ctx, "\u2039", Gravity.START);
		rightBubble = bubble(ctx, "\u203A", Gravity.END);

		LinearLayout column = new LinearLayout(ctx);
		column.setOrientation(LinearLayout.VERTICAL);
		column.setGravity(Gravity.CENTER_HORIZONTAL);

		TextView title = DuoUi.text(ctx, ctx.getString(R.string.duo_gesture_hint_title), 22, DuoUi.COLOR_TEXT);
		title.setTypeface(Typeface.DEFAULT_BOLD);
		title.setGravity(Gravity.CENTER);
		title.setMaxWidth(DuoUi.dp(ctx, TEXT_WIDTH_DP));
		column.addView(title);

		TextView text = DuoUi.text(ctx, ctx.getString(R.string.duo_gesture_hint_text), 16, DuoUi.COLOR_TEXT_DIM);
		text.setGravity(Gravity.CENTER);
		text.setMaxWidth(DuoUi.dp(ctx, TEXT_WIDTH_DP));
		text.setPadding(0, DuoUi.dp(ctx, 10), 0, DuoUi.dp(ctx, 20));
		column.addView(text);

		TextView ok = DuoUi.button(ctx, ctx.getString(R.string.duo_gesture_hint_ok));
		ok.setActivated(true);
		int padH = DuoUi.dp(ctx, 32);
		ok.setPadding(padH, ok.getPaddingTop(), padH, ok.getPaddingBottom());
		ok.setOnClickListener(onDismiss);
		column.addView(ok, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, DuoUi.dp(ctx, 48)));

		addView(column, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

		float travel = DuoUi.dp(ctx, TRAVEL_DP);
		animator = ValueAnimator.ofFloat(0.0f, 1.0f);
		animator.setDuration(1600);
		animator.setRepeatCount(ValueAnimator.INFINITE);
		animator.setInterpolator(new AccelerateDecelerateInterpolator());
		animator.addUpdateListener(a -> {
			float t = (float)a.getAnimatedValue();
			// Fade in at the edge, fade out at the end of the swipe.
			float alpha = Math.min(1.0f, Math.min(t / 0.15f, (1.0f - t) / 0.2f));
			leftBubble.setTranslationX(travel * t);
			leftBubble.setAlpha(alpha);
			rightBubble.setTranslationX(-travel * t);
			rightBubble.setAlpha(alpha);
		});
	}

	private void zoneMarker(Context ctx, float zoneDp, int edge) {
		View marker = new View(ctx);
		marker.setBackground(DuoUi.rounded(ctx, DuoUi.COLOR_ACCENT & 0x80FFFFFF, 3));
		addView(marker, new LayoutParams(DuoUi.dp(ctx, 6), DuoUi.dp(ctx, zoneDp), edge | Gravity.CENTER_VERTICAL));
	}

	private View bubble(Context ctx, String arrow, int edge) {
		GradientDrawable circle = new GradientDrawable();
		circle.setShape(GradientDrawable.OVAL);
		circle.setColor(DuoUi.COLOR_ACCENT);
		TextView tv = DuoUi.text(ctx, arrow, 26, DuoUi.COLOR_TEXT);
		tv.setGravity(Gravity.CENTER);
		tv.setTypeface(Typeface.DEFAULT_BOLD);
		tv.setBackground(circle);
		int size = DuoUi.dp(ctx, BUBBLE_DP);
		addView(tv, new LayoutParams(size, size, edge | Gravity.CENTER_VERTICAL));
		return tv;
	}

	@Override
	protected void onAttachedToWindow() {
		super.onAttachedToWindow();
		animator.start();
	}

	@Override
	protected void onDetachedFromWindow() {
		animator.cancel();
		super.onDetachedFromWindow();
	}
}
