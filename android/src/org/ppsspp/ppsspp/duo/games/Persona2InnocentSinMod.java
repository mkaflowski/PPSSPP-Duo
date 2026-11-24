package org.ppsspp.ppsspp.duo.games;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import org.ppsspp.ppsspp.duo.DuoMod;
import org.ppsspp.ppsspp.duo.DuoModContext;
import org.ppsspp.ppsspp.duo.DuoStatus;

import java.util.Arrays;

public final class Persona2InnocentSinMod extends DuoMod {
    public static final String ID = "persona2-innocent-sin";
    private static final String[] GAME_IDS = {"ULUS10584"};

    // Verified persistent stat records and live battle records.
    private static final int STATS_BASE = 0x08E3CA7C;
    private static final int RECORDS_BASE = STATS_BASE - 0x10;
    private static final int BATTLE_BASE = 0x08E683C8;
    private static final int RECORD_SIZE = 0x54;
    private static final int RECORD_COUNT = 6;

    // Character record slots identified so far.
    // Slot 5 is currently unidentified; Jun appears in slot 6.
    // Michel is Eikichi and Ginko is Lisa.
    private static final String[] RECORD_NAMES = {
        "Tatsuya", "Michel", "Ginko", "Maya", null, "Jun"
    };
    private static final int ACTIVE_MARKER = 0x11;
    private static final long HIT_EFFECT_MS = 450;

    private DuoModContext host;
    private Persona2View view;

    @Override public String getId() { return ID; }
    @Override public String getTitle(Context context) { return "Persona 2: Innocent Sin"; }
    @Override public String getDescription(Context context) { return "Party status and character portraits."; }
    @Override public int getPriority(DuoStatus status) {
        return Arrays.asList(GAME_IDS).contains(status.gameId) ? 100 : -1;
    }
    @Override public long getStatusIntervalMs() { return 100; }

    @Override
    public View onCreateView(DuoModContext host) {
        this.host = host;
        view = new Persona2View(host.getContext());
        // Each record begins with the candidate availability marker.
        // Level is at +9 and HP/SP stats begin at +0x10 from that marker.
        // Watch six persistent records and six corresponding battle records.
        host.setMemoryWatches(
            new int[] {RECORDS_BASE, BATTLE_BASE},
            new int[] {RECORD_SIZE * RECORD_COUNT, RECORD_SIZE * RECORD_COUNT}
        );
        return view;
    }

    @Override
    public void onDestroyView() {
        if (view != null) view.release();
        view = null;
        host = null;
    }

    @Override
    public void onStatus(DuoStatus status) {
        if (view == null || host == null) return;
        if (!status.isInGame() && !status.isPauseMenu()) return;

        byte[] saved = host.readMemoryWatch(0);
        byte[] battle = host.readMemoryWatch(1);
        if (saved == null || saved.length < RECORD_SIZE * RECORD_COUNT) return;

        // Compact only marked, named characters into the visible lineup.
        // This skips the currently unidentified fifth record without leaving a blank card.
        java.util.ArrayList<CharacterStats> visible = new java.util.ArrayList<>();
        for (int i = 0; i < RECORD_COUNT; i++) {
            int recordOffset = i * RECORD_SIZE;
            String name = RECORD_NAMES[i];

            if (name == null || u8(saved, recordOffset) != ACTIVE_MARKER) continue;

            int statsOffset = recordOffset + 0x10;
            CharacterStats c = new CharacterStats();
            c.name = name;
            c.level = u8(saved, recordOffset + 9);

            c.hp = u16(saved, statsOffset);
            c.maxHp = u16(saved, statsOffset + 2);
            c.sp = u16(saved, statsOffset + 4);
            c.maxSp = u16(saved, statsOffset + 6);

            if (battle != null && battle.length >= RECORD_SIZE * RECORD_COUNT) {
                int battleOffset = i * RECORD_SIZE;
                int hpPacked = u32(battle, battleOffset);
                int spPacked = u32(battle, battleOffset + 4);
                int liveHp = hpPacked & 0xFFFF;
                int liveMaxHp = (hpPacked >>> 16) & 0xFFFF;
                int liveSp = spPacked & 0xFFFF;
                int liveMaxSp = (spPacked >>> 16) & 0xFFFF;
                if (liveMaxHp == c.maxHp && liveHp <= liveMaxHp) c.hp = liveHp;
                if (liveMaxSp == c.maxSp && liveSp <= liveMaxSp) c.sp = liveSp;
            }

            if (c.maxHp <= 0 || c.maxHp > 9999 || c.hp > c.maxHp ||
                c.maxSp <= 0 || c.maxSp > 9999 || c.sp > c.maxSp) {
                continue;
            }
            visible.add(c);
        }
        view.setCharacters(visible.toArray(new CharacterStats[0]));
    }

    private static int u8(byte[] data, int offset) {
        return data[offset] & 0xFF;
    }
    private static int u16(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }
    private static int u32(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8) |
            ((data[offset + 2] & 0xFF) << 16) | ((data[offset + 3] & 0xFF) << 24);
    }

    private static final class CharacterStats {
        String name;
        int hp, maxHp, sp, maxSp, level;
    }

    private static final class Persona2View extends View {
        private static final int BG = 0xFF17191B;
        private static final int PANEL = 0xFF85898A;
        private static final int PANEL_LIGHT = 0xFFA5A7A5;
        private static final int WHITE = 0xFFF3F1EA;
        private static final int GREEN = 0xFF9CFF20;
        private static final int HP_COLOR = 0xFFB7D83D;
        private static final int SP_COLOR = 0xFF50D2E7;
        private static final int RED = 0xFFB51224;
        private static final int RED_DARK = 0xFF4A090F;
        private static final int RED_HEADER = 0xFF8F101D;
        private static final int DIM_OVERLAY = 0x77565A5B;
        private static final float CARD_W = 58f;
        private static final float CARD_TOP = 5f;
        private static final float CARD_H = 168f;
        private static final float PANEL_TOP = 177f;
        private static final float PANEL_BOTTOM = 235f;
        private static final float GAP = 4f;
        private static final float MARGIN = 5f;

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint grayPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final RectF rect = new RectF();
        private final Typeface font;
        private final Typeface boldFont;
        private final Persona2InnocentSinArt art;
        private CharacterStats[] characters = new CharacterStats[0];
        private final long[] hitTimes = new long[6];
        private int selected;
        private float scrollX;
        private float viewScale = 1f;
        private float downX, downY, lastX;
        private boolean dragging, moved;
        private boolean showFullPortrait;

        // Full-portrait pinch zoom and one-finger panning.
        private final ScaleGestureDetector scaleDetector;
        private float portraitZoom = 1f;
        private float portraitOffsetX;
        private float portraitOffsetY;
        private float portraitLastX;
        private float portraitLastY;
        private int portraitPointerId = MotionEvent.INVALID_POINTER_ID;
        private boolean portraitGestureUsed;

        Persona2View(Context context) {
            super(context);
            Typeface loaded;
            try {
                loaded = Typeface.createFromAsset(context.getAssets(), "Fontsona4Golden.ttf");
            } catch (RuntimeException | Error ignored) {
                loaded = Typeface.create("sans-serif-condensed", Typeface.NORMAL);
            }
            font = loaded;
            boldFont = Typeface.create(loaded, Typeface.BOLD);
            art = new Persona2InnocentSinArt(context);
            scaleDetector = new ScaleGestureDetector(context,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(ScaleGestureDetector detector) {
                        float oldZoom = portraitZoom;
                        // Slightly damp tiny detector fluctuations so zoom feels steady.
                        float scaleFactor = (float) Math.pow(detector.getScaleFactor(), 0.90);
                        float newZoom = Math.max(1f, Math.min(5f, oldZoom * scaleFactor));
                        if (Math.abs(newZoom - oldZoom) < 0.0005f) return true;

                        float logicalFocusX = detector.getFocusX() / viewScale;
                        float logicalFocusY = detector.getFocusY() / viewScale;
                        float centerX = getWidth() / viewScale / 2f;
                        float centerY = 120f;

                        // Preserve the exact image point beneath the pinch midpoint.
                        float ratio = newZoom / oldZoom;
                        portraitOffsetX = logicalFocusX - centerX -
                            (logicalFocusX - centerX - portraitOffsetX) * ratio;
                        portraitOffsetY = logicalFocusY - centerY -
                            (logicalFocusY - centerY - portraitOffsetY) * ratio;
                        portraitZoom = newZoom;
                        if (portraitZoom <= 1.001f) {
                            portraitZoom = 1f;
                            portraitOffsetX = 0f;
                            portraitOffsetY = 0f;
                        } else {
                            clampPortraitOffsets();
                        }
                        portraitGestureUsed = true;
                        moved = true;
                        invalidate();
                        return true;
                    }
                });
            ColorMatrix matrix = new ColorMatrix();
            matrix.setSaturation(0);
            grayPaint.setColorFilter(new ColorMatrixColorFilter(matrix));
            setFocusable(true);
            setClickable(true);
        }

        void release() { art.recycle(); }

        void setCharacters(CharacterStats[] updated) {
            long now = SystemClock.uptimeMillis();
            if (updated != null) {
                for (int i = 0; i < Math.min(updated.length, characters.length); i++) {
                    CharacterStats old = characters[i];
                    CharacterStats next = updated[i];
                    if (old != null && next != null && next.hp < old.hp) hitTimes[i] = now;
                }
            }
            characters = updated == null ? new CharacterStats[0] : updated;
            if (selected >= characters.length) selected = Math.max(0, characters.length - 1);
            postInvalidateOnAnimation();
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float width = getWidth(), height = getHeight();
            if (width <= 0 || height <= 0) return;
            viewScale = height / 240f;
            canvas.save();
            canvas.scale(viewScale, viewScale);
            float logicalWidth = width / viewScale;
            canvas.drawColor(BG);
            drawBackground(canvas, logicalWidth);
            if (showFullPortrait) {
                drawFullPortrait(canvas, logicalWidth, 240f);
            } else {
                drawCards(canvas, logicalWidth);
                drawInfoPanel(canvas, logicalWidth);
            }
            canvas.restore();
            if (hasActiveHitEffect()) postInvalidateOnAnimation();
        }

        private boolean hasActiveHitEffect() {
            long now = SystemClock.uptimeMillis();
            for (long hit : hitTimes) if (hit > 0 && now - hit < HIT_EFFECT_MS) return true;
            return false;
        }

        private void drawBackground(Canvas canvas, float width) {
            paint.setColor(0xFF303435);
            paint.setStyle(Paint.Style.FILL);
            for (float y = 3; y < 240; y += 6) {
                for (float x = ((int) (y * 2)) % 11; x < width; x += 11) {
                    canvas.drawCircle(x, y, 0.28f, paint);
                }
            }
        }

        private void drawCards(Canvas canvas, float width) {
            float totalWidth = characters.length * CARD_W + Math.max(0, characters.length - 1) * GAP;
            float viewportWidth = width - MARGIN * 2;
            float maxScroll = Math.max(0, totalWidth - viewportWidth);
            scrollX = Math.max(0, Math.min(scrollX, maxScroll));
            long now = SystemClock.uptimeMillis();
            canvas.save();
            canvas.clipRect(MARGIN, 0, width - MARGIN, PANEL_TOP - 3);
            for (int i = 0; i < characters.length; i++) {
                float x = MARGIN + i * (CARD_W + GAP) - scrollX;
                long since = hitTimes[i] == 0 ? HIT_EFFECT_MS : now - hitTimes[i];
                float shake = shakeOffset(since);
                x += shake;
                if (characters[i] != null) drawCard(canvas, characters[i], x, CARD_TOP, CARD_W, CARD_H, i == selected);
                if (since >= 0 && since < HIT_EFFECT_MS) {
                    int alpha = (int) (110 * (1f - since / (float) HIT_EFFECT_MS));
                    fill(canvas, x, 25, x + CARD_W, 25 + CARD_H, (alpha << 24) | 0x00E5392F);
                }
            }
            canvas.restore();
            if (maxScroll > 0) {
                float trackWidth = viewportWidth;
                float thumbWidth = trackWidth * viewportWidth / totalWidth;
                float thumbX = MARGIN + (trackWidth - thumbWidth) * (scrollX / maxScroll);
                fill(canvas, MARGIN, 173.2f, width - MARGIN, 174.5f, 0xFF454A4B);
                fill(canvas, thumbX, 173.2f, thumbX + thumbWidth, 174.5f, 0xFFB9C0C0);
            }
        }

        private float shakeOffset(long since) {
            if (since < 0 || since >= HIT_EFFECT_MS) return 0;
            float fade = 1f - since / (float) HIT_EFFECT_MS;
            return (float) Math.sin(since * 0.09) * 5.5f * fade;
        }

        private void drawCard(Canvas canvas, CharacterStats c, float x, float y,
                              float width, float height, boolean active) {
            boolean down = c.hp == 0;
            float right = x + width, bottom = y + height;
            fill(canvas, x, y, right, bottom, 0xFF08090A);
            outline(canvas, x + 0.7f, y + 0.7f, right - 0.7f, bottom - 0.7f,
                active ? 0xFFFFE52E : 0xFF8E1722, active ? 1.1f : 0.8f);
            float left = x + 1.5f, innerR = right - 1.5f;
            fill(canvas, left, y + 1.5f, innerR, y + 17, active ? 0xFFB51A2A : RED_HEADER);
            fill(canvas, left, y + 16.5f, innerR, y + 18.5f, 0xFF4A090F);
            text(canvas, c.name, x + width / 2f, y + 12, 8, WHITE, true, Paint.Align.CENTER);
            drawStat(canvas, "HP", c.hp, c.maxHp, HP_COLOR, left, y + 18, innerR);
            drawStat(canvas, "SP", c.sp, c.maxSp, SP_COLOR, left, y + 37, innerR);

            float imageTop = y + 56, imageBottom = bottom - 1.5f;
            fill(canvas, left, imageTop, innerR, imageBottom, down ? 0xFF777777 : 0xFF31090E);
            Bitmap portrait = art.getCard(c.name);
            if (portrait != null && !portrait.isRecycled()) {
                canvas.save();
                canvas.clipRect(left, imageTop, innerR, imageBottom);
                // These dedicated upper-half assets are composed for the card frame.
                // Fit the complete image without cropping or changing its proportions.
                float targetW = innerR - left, targetH = imageBottom - imageTop;
                float scale = Math.min(targetW / portrait.getWidth(), targetH / portrait.getHeight());
                float drawW = portrait.getWidth() * scale;
                float drawH = portrait.getHeight() * scale;
                rect.set(left + (targetW - drawW) / 2f,
                    imageTop + (targetH - drawH) / 2f,
                    left + (targetW + drawW) / 2f,
                    imageTop + (targetH + drawH) / 2f);
                canvas.drawBitmap(portrait, null, rect, down ? grayPaint : paint);
                canvas.restore();
            } else {
                text(canvas, "ART MISSING", x + width / 2f, imageTop + 42, 6, WHITE, true, Paint.Align.CENTER);
            }

            fill(canvas, left, bottom - 14, left + 36, bottom, 0xD8101014);
            text(canvas, c.level > 0 ? "Lv." + c.level : "Lv.--",
                left + 3, bottom - 4, 8, WHITE, true, Paint.Align.LEFT);
            if (down) fill(canvas, x, y, right, bottom, 0x66707070);
            if (!active) fill(canvas, x, y, right, bottom, DIM_OVERLAY);
            else {
                outline(canvas, x - 1, y - 1, right + 1, bottom + 1, 0x339CFF20, 2.2f);
                outline(canvas, x - 0.2f, y - 0.2f, right + 0.2f, bottom + 0.2f, GREEN, 1.1f);
                outline(canvas, x + 1.2f, y + 1.2f, right - 1.2f, bottom - 1.2f, 0xFFCBFF85, 0.35f);
            }
        }

        private void drawStat(Canvas canvas, String label, int value, int max,
                              int color, float left, float top, float right) {
            fill(canvas, left, top, right, top + 19, 0xFF1C2021);
            text(canvas, label, left + 3, top + 8, 7, color, true, Paint.Align.LEFT);
            text(canvas, value + "/" + max, right - 3, top + 8, 6.5f, WHITE, true, Paint.Align.RIGHT);
            float ratio = max > 0 ? Math.max(0, Math.min(1, (float) value / max)) : 0;
            fill(canvas, left + 3, top + 12, right - 3, top + 16, 0xFF454A4B);
            fill(canvas, left + 3, top + 12, left + 3 + (right - left - 6) * ratio, top + 16, color);
        }

        private void drawInfoPanel(Canvas canvas, float width) {
            float top = PANEL_TOP, bottom = PANEL_BOTTOM;
            fill(canvas, 5, top, width - 5, bottom, 0xFF570B13);
            // Deep-red outer frame and a brighter inner edge echo the Innocent Sin menus.
            outline(canvas, 5, top, width - 5, bottom, 0xFFE12A3B, 0.9f);
            outline(canvas, 7, top + 2, width - 7, bottom - 2, 0xFF8E1826, 0.45f);
            // Subtle red micro-grid texture, kept low contrast so text remains readable.
            paint.setColor(0x22FF6572);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(0.35f);
            for (float gx = 10; gx < width - 8; gx += 5) {
                canvas.drawLine(gx, top + 3, gx, bottom - 3, paint);
            }
            for (float gy = top + 4; gy < bottom - 3; gy += 5) {
                canvas.drawLine(8, gy, width - 8, gy, paint);
            }
            paint.setStyle(Paint.Style.FILL);
            drawButterfly(canvas, width * 0.69f, top + 31f, 15f, 0x30FF3348);
            if (selected < 0 || selected >= characters.length || characters[selected] == null) {
                text(canvas, "PARTY", 10, top + 14, 9, WHITE, true, Paint.Align.LEFT);
                text(canvas, "Tap a card to view status", 10, top + 30, 7.5f, WHITE, false, Paint.Align.LEFT);
                return;
            }

            CharacterStats c = characters[selected];
            String heading = c.name.toUpperCase() + "    LV. " + (c.level > 0 ? String.valueOf(c.level) : "--");
            text(canvas, heading, 10, top + 13, 8.5f, WHITE, true, Paint.Align.LEFT);
            textSpaced(canvas, "HP  " + c.hp + " / " + c.maxHp, 10, top + 28, 7.8f, WHITE, Paint.Align.LEFT);
            float barW = Math.min(width - 20, 142);
            drawPanelBar(canvas, 10, top + 31, barW, 3.2f, c.hp, c.maxHp, HP_COLOR);
            textSpaced(canvas, "SP  " + c.sp + " / " + c.maxSp, 10, top + 44, 7.8f, WHITE, Paint.Align.LEFT);
            drawPanelBar(canvas, 10, top + 47, barW, 3.2f, c.sp, c.maxSp, SP_COLOR);

            float buttonW = 48f, buttonH = 17f;
            float buttonX = width - buttonW - 11f, buttonY = top + 19f;
            fill(canvas, buttonX, buttonY, buttonX + buttonW, buttonY + buttonH, 0xFF31070C);
            outline(canvas, buttonX, buttonY, buttonX + buttonW, buttonY + buttonH, 0xFFFFB0B7, 0.8f);
            outline(canvas, buttonX + 1.3f, buttonY + 1.3f, buttonX + buttonW - 1.3f,
                buttonY + buttonH - 1.3f, 0xFF8F1824, 0.45f);
            text(canvas, "Full portrait", buttonX + buttonW / 2f, buttonY + 11.5f, 6.8f, WHITE, true, Paint.Align.CENTER);
        }

        private void drawButterfly(Canvas canvas, float cx, float cy, float size, int color) {
            Paint.Style oldStyle = paint.getStyle();
            int oldColor = paint.getColor();
            float oldStroke = paint.getStrokeWidth();
            paint.setColor(color);
            paint.setStyle(Paint.Style.FILL);

            android.graphics.Path wings = new android.graphics.Path();
            // Four stylized wings, intentionally abstract rather than using unrelated artwork.
            wings.moveTo(cx, cy);
            wings.cubicTo(cx - size * 0.15f, cy - size * 0.95f,
                cx - size * 1.15f, cy - size * 1.05f, cx - size * 0.95f, cy - size * 0.12f);
            wings.cubicTo(cx - size * 0.88f, cy + size * 0.38f,
                cx - size * 0.28f, cy + size * 0.40f, cx, cy);
            wings.moveTo(cx, cy);
            wings.cubicTo(cx + size * 0.15f, cy - size * 0.95f,
                cx + size * 1.15f, cy - size * 1.05f, cx + size * 0.95f, cy - size * 0.12f);
            wings.cubicTo(cx + size * 0.88f, cy + size * 0.38f,
                cx + size * 0.28f, cy + size * 0.40f, cx, cy);
            wings.moveTo(cx, cy + size * 0.05f);
            wings.cubicTo(cx - size * 0.65f, cy + size * 0.20f,
                cx - size * 0.72f, cy + size * 0.85f, cx - size * 0.12f, cy + size * 0.58f);
            wings.cubicTo(cx - size * 0.03f, cy + size * 0.48f, cx, cy + size * 0.20f, cx, cy + size * 0.05f);
            wings.moveTo(cx, cy + size * 0.05f);
            wings.cubicTo(cx + size * 0.65f, cy + size * 0.20f,
                cx + size * 0.72f, cy + size * 0.85f, cx + size * 0.12f, cy + size * 0.58f);
            wings.cubicTo(cx + size * 0.03f, cy + size * 0.48f, cx, cy + size * 0.20f, cx, cy + size * 0.05f);
            canvas.drawPath(wings, paint);
            paint.setColor(color);
            paint.setStrokeWidth(1f);
            canvas.drawLine(cx, cy - size * 0.25f, cx, cy + size * 0.65f, paint);

            paint.setColor(oldColor);
            paint.setStrokeWidth(oldStroke);
            paint.setStyle(oldStyle);
        }

        private void drawPanelBar(Canvas canvas, float x, float y, float width, float height, int value, int max, int color) {
            fill(canvas, x, y, x + width, y + height, 0xFF777D7E);
            float fraction = max <= 0 ? 0 : Math.max(0, Math.min(1, value / (float) max));
            if (fraction > 0) fill(canvas, x, y, x + width * fraction, y + height, color);
        }

        private void textSpaced(Canvas canvas, String value, float x, float y, float size, int color, Paint.Align align) {
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(null);
            paint.setColor(color);
            paint.setAlpha(255);
            paint.setTypeface(font);
            paint.setTextSize(size);
            float total = 0;
            for (int i = 0; i < value.length(); i++) total += paint.measureText(value.substring(i, i + 1)) + 0.75f;
            float cursor = x;
            if (align == Paint.Align.RIGHT) cursor -= total;
            else if (align == Paint.Align.CENTER) cursor -= total / 2f;
            for (int i = 0; i < value.length(); i++) {
                String ch = value.substring(i, i + 1);
                canvas.drawText(ch, cursor, y, paint);
                cursor += paint.measureText(ch) + 0.75f;
            }
        }

        private void clampPortraitOffsets() {
            if (selected < 0 || selected >= characters.length || characters[selected] == null) return;
            Bitmap bitmap = art.getFull(characters[selected].name);
            if (bitmap == null || bitmap.isRecycled()) return;

            float width = getWidth() / viewScale;
            float height = 240f;
            float availableW = Math.max(1f, width - 12f);
            float availableH = Math.max(1f, height - 30f);
            float fitScale = Math.min(availableW / bitmap.getWidth(),
                availableH / bitmap.getHeight());
            float halfW = bitmap.getWidth() * fitScale * portraitZoom / 2f;
            float halfH = bitmap.getHeight() * fitScale * portraitZoom / 2f;
            float maxX = Math.max(0f, halfW - availableW / 2f);
            float maxY = Math.max(0f, halfH - availableH / 2f);
            portraitOffsetX = Math.max(-maxX, Math.min(maxX, portraitOffsetX));
            portraitOffsetY = Math.max(-maxY, Math.min(maxY, portraitOffsetY));
        }

        private void drawFullPortrait(Canvas canvas, float width, float height) {
            canvas.drawColor(0xFF101213);
            if (selected < 0 || selected >= characters.length || characters[selected] == null) {
                text(canvas, "NO CHARACTER SELECTED", width / 2f, height / 2f, 9, WHITE, true, Paint.Align.CENTER);
                return;
            }
            CharacterStats c = characters[selected];
            Bitmap bitmap = art.getFull(c.name);
            if (bitmap == null || bitmap.isRecycled()) {
                text(canvas, "PORTRAIT NOT FOUND", width / 2f, height / 2f, 9, WHITE, true, Paint.Align.CENTER);
                return;
            }
            float availableW = Math.max(1f, width - 12f);
            float availableH = Math.max(1f, height - 30f);
            float fitScale = Math.min(availableW / bitmap.getWidth(),
                availableH / bitmap.getHeight());
            float drawW = bitmap.getWidth() * fitScale;
            float drawH = bitmap.getHeight() * fitScale;

            canvas.save();
            canvas.translate(width / 2f + portraitOffsetX,
                height / 2f + portraitOffsetY);
            canvas.scale(portraitZoom, portraitZoom);
            rect.set(-drawW / 2f, -drawH / 2f, drawW / 2f, drawH / 2f);
            canvas.drawBitmap(bitmap, null, rect, c.hp == 0 ? grayPaint : paint);
            canvas.restore();

            text(canvas, "PINCH TO ZOOM  /  TAP TO RETURN",
                width / 2f, height - 7f, 6.2f, WHITE, true, Paint.Align.CENTER);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            float x = event.getX() / viewScale;
            float y = event.getY() / viewScale;

            if (showFullPortrait) {
                scaleDetector.onTouchEvent(event);

                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        portraitPointerId = event.getPointerId(0);
                        portraitLastX = x;
                        portraitLastY = y;
                        portraitGestureUsed = false;
                        dragging = true;
                        moved = false;
                        return true;

                    case MotionEvent.ACTION_POINTER_DOWN:
                        // Once a second finger touches, this is a zoom gesture, not a tap.
                        portraitGestureUsed = true;
                        moved = true;
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        if (!scaleDetector.isInProgress() && event.getPointerCount() == 1 &&
                            dragging && portraitZoom > 1f &&
                            portraitPointerId != MotionEvent.INVALID_POINTER_ID) {
                            int pointerIndex = event.findPointerIndex(portraitPointerId);
                            if (pointerIndex >= 0) {
                                float pointerX = event.getX(pointerIndex) / viewScale;
                                float pointerY = event.getY(pointerIndex) / viewScale;
                                float dx = pointerX - portraitLastX;
                                float dy = pointerY - portraitLastY;
                                portraitOffsetX += dx;
                                portraitOffsetY += dy;
                                clampPortraitOffsets();
                                if (Math.abs(dx) + Math.abs(dy) > 1f) {
                                    moved = true;
                                    portraitGestureUsed = true;
                                }
                                invalidate();
                                portraitLastX = pointerX;
                                portraitLastY = pointerY;
                            }
                        }
                        return true;

                    case MotionEvent.ACTION_POINTER_UP: {
                        // If the tracked finger lifts, transfer tracking to the remaining finger
                        // and reset its origin to prevent the sudden left/right jump.
                        int liftedIndex = event.getActionIndex();
                        int liftedId = event.getPointerId(liftedIndex);
                        if (liftedId == portraitPointerId) {
                            int replacementIndex = liftedIndex == 0 ? 1 : 0;
                            if (replacementIndex < event.getPointerCount()) {
                                portraitPointerId = event.getPointerId(replacementIndex);
                                portraitLastX = event.getX(replacementIndex) / viewScale;
                                portraitLastY = event.getY(replacementIndex) / viewScale;
                            } else {
                                portraitPointerId = MotionEvent.INVALID_POINTER_ID;
                            }
                        }
                        portraitGestureUsed = true;
                        moved = true;
                        return true;
                    }

                    case MotionEvent.ACTION_UP:
                        if (!portraitGestureUsed && !moved && !scaleDetector.isInProgress()) {
                            showFullPortrait = false;
                            portraitZoom = 1f;
                            portraitOffsetX = 0f;
                            portraitOffsetY = 0f;
                            dragging = false;
                            portraitPointerId = MotionEvent.INVALID_POINTER_ID;
                            performClick();
                            invalidate();
                            return true;
                        }
                        dragging = false;
                        portraitPointerId = MotionEvent.INVALID_POINTER_ID;
                        performClick();
                        invalidate();
                        return true;

                    case MotionEvent.ACTION_CANCEL:
                        dragging = false;
                        portraitPointerId = MotionEvent.INVALID_POINTER_ID;
                        return true;
                }
                return true;
            }

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = lastX = x;
                    downY = y;
                    dragging = true;
                    moved = false;
                    return true;

                case MotionEvent.ACTION_MOVE:
                    if (!dragging) return true;
                    float dx = lastX - x;
                    if (Math.abs(x - downX) > 5) moved = true;
                    if (moved && downY < PANEL_TOP - 2) {
                        float total = characters.length * CARD_W + Math.max(0, characters.length - 1) * GAP;
                        float viewport = getWidth() / viewScale - MARGIN * 2;
                        float maxScroll = Math.max(0, total - viewport);
                        scrollX = Math.max(0, Math.min(maxScroll, scrollX + dx));
                        invalidate();
                    }
                    lastX = x;
                    return true;

                case MotionEvent.ACTION_UP:
                    if (!moved && y >= CARD_TOP && y <= CARD_TOP + CARD_H) {
                        int index = (int) ((x - MARGIN + scrollX) / (CARD_W + GAP));
                        if (index >= 0 && index < characters.length && characters[index] != null) {
                            selected = index;
                            invalidate();
                        }
                    } else if (!moved && y >= PANEL_TOP && y <= PANEL_BOTTOM &&
                        selected >= 0 && selected < characters.length && characters[selected] != null) {
                        float logicalWidth = getWidth() / viewScale;
                        float buttonX = logicalWidth - 48f - 11f;
                        float buttonY = PANEL_TOP + 19f;
                        if (x >= buttonX && x <= buttonX + 48f &&
                            y >= buttonY && y <= buttonY + 17f) {
                            showFullPortrait = true;
                            portraitZoom = 1f;
                            portraitOffsetX = 0f;
                            portraitOffsetY = 0f;
                        }
                    }
                    dragging = false;
                    performClick();
                    invalidate();
                    return true;

                case MotionEvent.ACTION_CANCEL:
                    dragging = false;
                    return true;
            }
            return true;
        }

        @Override public boolean performClick() {
            super.performClick();
            return true;
        }

        private void text(Canvas canvas, String value, float x, float y, float size,
                          int color, boolean bold, Paint.Align align) {
            paint.setTypeface(bold ? boldFont : font);
            paint.setTextSize(size);
            paint.setColor(color);
            paint.setTextAlign(align);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawText(value, x, y, paint);
        }
        private void fill(Canvas canvas, float left, float top, float right, float bottom, int color) {
            paint.setColor(color);
            paint.setStyle(Paint.Style.FILL);
            rect.set(left, top, right, bottom);
            canvas.drawRect(rect, paint);
        }
        private void outline(Canvas canvas, float left, float top, float right, float bottom,
                             int color, float thickness) {
            paint.setColor(color);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(thickness);
            rect.set(left, top, right, bottom);
            canvas.drawRect(rect, paint);
            paint.setStyle(Paint.Style.FILL);
        }
    }
}
