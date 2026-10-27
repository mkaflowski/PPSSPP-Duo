package org.ppsspp.ppsspp.duo.games;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import org.ppsspp.ppsspp.duo.DuoMod;
import org.ppsspp.ppsspp.duo.DuoModContext;
import org.ppsspp.ppsspp.duo.DuoStatus;

/**
 * First development milestone for Persona 1 PSP:
 * a touch-enabled second-screen UI shell.
 *
 * This version intentionally uses placeholder values. It does NOT read Persona 1
 * memory yet. We'll add verified memory addresses only after the UI builds and runs.
 */
public final class Persona1Mod extends DuoMod {
    public static final String ID = "persona1";
    private static final String GAME_ID = "ULUS10432";

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getTitle(Context context) {
        return "Persona 1 Companion";
    }

    @Override
    public String getDescription(Context context) {
        return "Dual-screen companion interface for Persona 1 PSP (USA).";
    }

    @Override
    public int getPriority(DuoStatus status) {
        return GAME_ID.equals(status.gameId) ? 100 : -1;
    }

    @Override
    public View onCreateView(DuoModContext host) {
        return new Persona1View(host.getContext());
    }

    private static final class Persona1View extends View {
        private static final int BG = 0xFF101820;
        private static final int PANEL = 0xFF1D2B36;
        private static final int PANEL_ALT = 0xFF263945;
        private static final int CYAN = 0xFF80E8F2;
        private static final int GOLD = 0xFFF3C969;
        private static final int WHITE = 0xFFF2F5F7;
        private static final int MUTED = 0xFF9DB1BC;

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final String[] tabs = {"MAP", "PARTY", "BATTLE", "MENU"};
        private int selectedTab = 0;

        Persona1View(Context context) {
            super(context);
            setFocusable(true);
            setBackgroundColor(BG);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth();
            float h = getHeight();
            if (w <= 0 || h <= 0) return;

            float scale = Math.min(w / 900f, h / 520f);
            float margin = 18f * scale;
            float headerH = 54f * scale;
            float tabH = 48f * scale;

            paint.setColor(BG);
            canvas.drawColor(BG);

            // Header
            roundRect(canvas, margin, margin, w - margin, margin + headerH, 12 * scale, PANEL);
            text(canvas, "PERSONA", margin + 18 * scale, margin + 23 * scale,
                    15 * scale, MUTED);
            text(canvas, "DUAL SCREEN", margin + 18 * scale, margin + 42 * scale,
                    19 * scale, CYAN);
            textRight(canvas, "USA  •  ULUS10432", w - margin - 16 * scale,
                    margin + 32 * scale, 14 * scale, GOLD);

            // Tabs
            float tabsTop = margin + headerH + 12 * scale;
            float gap = 7 * scale;
            float tabW = (w - 2 * margin - 3 * gap) / 4f;
            for (int i = 0; i < tabs.length; i++) {
                float left = margin + i * (tabW + gap);
                roundRect(canvas, left, tabsTop, left + tabW, tabsTop + tabH,
                        9 * scale, i == selectedTab ? CYAN : PANEL_ALT);
                textCentered(canvas, tabs[i], left + tabW / 2f,
                        tabsTop + tabH * 0.62f, 15 * scale,
                        i == selectedTab ? BG : WHITE);
            }

            float bodyTop = tabsTop + tabH + 14 * scale;
            float bodyBottom = h - margin;
            roundRect(canvas, margin, bodyTop, w - margin, bodyBottom,
                    12 * scale, PANEL);

            switch (selectedTab) {
                case 0: drawMap(canvas, margin, bodyTop, w, bodyBottom, scale); break;
                case 1: drawParty(canvas, margin, bodyTop, w, bodyBottom, scale); break;
                case 2: drawBattle(canvas, margin, bodyTop, w, bodyBottom, scale); break;
                default: drawMenu(canvas, margin, bodyTop, w, bodyBottom, scale); break;
            }
        }

        private void drawMap(Canvas c, float l, float t, float w, float b, float s) {
            text(c, "DUNGEON MAP", l + 20*s, t + 32*s, 18*s, CYAN);
            text(c, "Map data not connected yet", l + 20*s, t + 62*s, 14*s, MUTED);

            float cell = 28*s;
            float startX = l + 28*s;
            float startY = t + 88*s;
            int[][] layout = {
                {0,0,1,1,1,0,0},
                {0,0,1,0,1,0,0},
                {1,1,1,0,1,1,1},
                {1,0,0,0,0,0,1},
                {1,1,1,1,1,1,1}
            };
            for (int y = 0; y < layout.length; y++) {
                for (int x = 0; x < layout[y].length; x++) {
                    if (layout[y][x] == 0) continue;
                    float x1 = startX + x * (cell + 4*s);
                    float y1 = startY + y * (cell + 4*s);
                    roundRect(c, x1, y1, x1 + cell, y1 + cell, 4*s,
                            (x == 2 && y == 2) ? GOLD : PANEL_ALT);
                }
            }
            text(c, "Illustration only — not the game's actual map", startX,
                    startY + 5 * (cell + 4*s) + 20*s, 12*s, MUTED);
        }

        private void drawParty(Canvas c, float l, float t, float w, float b, float s) {
            text(c, "PARTY STATUS", l + 20*s, t + 32*s, 18*s, CYAN);
            text(c, "Placeholder values — live memory reading comes later",
                    l + 20*s, t + 58*s, 12*s, MUTED);
            String[] names = {"HERO", "PARTY MEMBER 2", "PARTY MEMBER 3"};
            float top = t + 78*s;
            float cardH = Math.min(66*s, (b - top - 16*s) / 3f);
            for (int i = 0; i < names.length; i++) {
                float y = top + i * (cardH + 7*s);
                roundRect(c, l + 16*s, y, w - l - 16*s, y + cardH, 8*s, PANEL_ALT);
                text(c, names[i], l + 28*s, y + 22*s, 13*s, WHITE);
                text(c, "HP  ??? / ???", l + 28*s, y + 43*s, 12*s, GOLD);
                text(c, "SP  ??? / ???", l + 190*s, y + 43*s, 12*s, CYAN);
            }
        }

        private void drawBattle(Canvas c, float l, float t, float w, float b, float s) {
            text(c, "BATTLE COMPANION", l + 20*s, t + 32*s, 18*s, CYAN);
            text(c, "Battle detection and enemy data are not connected yet.",
                    l + 20*s, t + 60*s, 13*s, MUTED);
            roundRect(c, l + 20*s, t + 82*s, w - l - 20*s, t + 132*s,
                    8*s, PANEL_ALT);
            text(c, "ENCOUNTER STATUS: WAITING FOR DATA",
                    l + 34*s, t + 112*s, 13*s, GOLD);
            text(c, "Future: enemy HP, affinities, and party condition",
                    l + 20*s, t + 160*s, 12*s, WHITE);
        }

        private void drawMenu(Canvas c, float l, float t, float w, float b, float s) {
            text(c, "QUICK MENU", l + 20*s, t + 32*s, 18*s, CYAN);
            String[] items = {"Party status", "Dungeon map", "Battle info", "Game controls"};
            float top = t + 58*s;
            for (int i = 0; i < items.length; i++) {
                float y = top + i * 42*s;
                roundRect(c, l + 20*s, y, w - l - 20*s, y + 34*s, 7*s, PANEL_ALT);
                text(c, items[i], l + 34*s, y + 22*s, 13*s, WHITE);
                textRight(c, "COMING SOON", w - l - 32*s, y + 22*s, 10*s, MUTED);
            }
        }

        private void roundRect(Canvas c, float l, float t, float r, float b,
                               float radius, int color) {
            paint.setColor(color);
            rect.set(l, t, r, b);
            c.drawRoundRect(rect, radius, radius, paint);
        }

        private void text(Canvas c, String value, float x, float baseline,
                          float size, int color) {
            paint.setColor(color);
            paint.setTextSize(size);
            paint.setFakeBoldText(true);
            paint.setTextAlign(Paint.Align.LEFT);
            c.drawText(value, x, baseline, paint);
        }

        private void textRight(Canvas c, String value, float x, float baseline,
                               float size, int color) {
            paint.setColor(color);
            paint.setTextSize(size);
            paint.setFakeBoldText(true);
            paint.setTextAlign(Paint.Align.RIGHT);
            c.drawText(value, x, baseline, paint);
            paint.setTextAlign(Paint.Align.LEFT);
        }

        private void textCentered(Canvas c, String value, float x, float baseline,
                                  float size, int color) {
            paint.setColor(color);
            paint.setTextSize(size);
            paint.setFakeBoldText(true);
            paint.setTextAlign(Paint.Align.CENTER);
            c.drawText(value, x, baseline, paint);
            paint.setTextAlign(Paint.Align.LEFT);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (event.getAction() == MotionEvent.ACTION_UP) {
                float w = getWidth();
                float h = getHeight();
                float scale = Math.min(w / 900f, h / 520f);
                float margin = 18f * scale;
                float headerH = 54f * scale;
                float tabsTop = margin + headerH + 12 * scale;
                float tabH = 48f * scale;
                if (event.getY() >= tabsTop && event.getY() <= tabsTop + tabH
                        && event.getX() >= margin && event.getX() <= w - margin) {
                    float gap = 7 * scale;
                    float tabW = (w - 2 * margin - 3 * gap) / 4f;
                    int tab = (int)((event.getX() - margin) / (tabW + gap));
                    if (tab >= 0 && tab < tabs.length) {
                        selectedTab = tab;
                        invalidate();
                    }
                    return true;
                }
                return true;
            }
            return true;
        }
    }
}
