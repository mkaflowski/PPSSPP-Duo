package org.ppsspp.ppsspp.duo.games;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Loads card-optimized and full-size official Persona 2 portraits. */
public final class Persona2InnocentSinArt {
    private static final String[] CHARACTERS = {
        "Tatsuya", "Lisa", "Eikichi", "Maya", "Yukino", "Jun"
    };

    private final Map<String, Bitmap> cardPortraits = new HashMap<>();
    private final Map<String, Bitmap> fullPortraits = new HashMap<>();

    public Persona2InnocentSinArt(Context context) {
        for (String character : CHARACTERS) {
            String key = character.toLowerCase(Locale.ROOT);
            String fullFile = "Persona2InnocentSin" + character + ".png";
            String cardFile = "UpperHalfPersona2InnocentSin" + character + ".png";

            Bitmap full = load(context, fullFile);
            Bitmap card = load(context, cardFile);

            if (full != null) fullPortraits.put(key, full);
            if (card != null) cardPortraits.put(key, card);

            // Keep the mod tolerant of a missing optimized image during setup.
            // The full image remains available as a fallback for the card.
            if (card == null && full != null) {
                cardPortraits.put(key, full);
            }
        }
    }

    private static Bitmap load(Context context, String file) {
        try (InputStream input = context.getAssets().open(file)) {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            return BitmapFactory.decodeStream(input, null, options);
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    private static String normalize(String character) {
        if (character == null) return null;
        String key = character.toLowerCase(Locale.ROOT);
        // Display names used by the current battle-slot code.
        if (key.equals("michel")) key = "eikichi";
        if (key.equals("ginko")) key = "lisa";
        return key;
    }

    /** Returns the dedicated upper-half image used inside the character card. */
    public Bitmap getCard(String character) {
        String key = normalize(character);
        return key == null ? null : cardPortraits.get(key);
    }

    /** Returns the original full-body image used by the full-portrait screen. */
    public Bitmap getFull(String character) {
        String key = normalize(character);
        return key == null ? null : fullPortraits.get(key);
    }

    /** Backwards-compatible alias for callers that used the original getter. */
    public Bitmap get(String character) {
        return getFull(character);
    }

    public void recycle() {
        for (Bitmap bitmap : cardPortraits.values()) {
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
        }
        for (Bitmap bitmap : fullPortraits.values()) {
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
        }
        cardPortraits.clear();
        fullPortraits.clear();
    }
}
