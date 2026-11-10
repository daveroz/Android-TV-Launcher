package com.camdeco.launcher;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;

import androidx.palette.graphics.Palette;

/**
 * An app's two most characteristic colours, from its banner or icon: a vivid one and a deep one.
 * They tint the hero for apps with nothing else to show. Grey or white logos fall back to
 * Worldly's amber and slate.
 */
final class AppColors {
    static final int WORLDLY_AMBER = 0xFFDF8B35;
    static final int WORLDLY_SLATE = 0xFF3A4560;

    final int vivid;
    final int deep;

    AppColors(int vivid, int deep) {
        this.vivid = vivid;
        this.deep = deep;
    }

    static AppColors worldly() {
        return new AppColors(WORLDLY_AMBER, WORLDLY_SLATE);
    }

    /** Slow-ish (draws and analyses a small copy): call off the main thread. */
    static AppColors from(Drawable drawable) {
        if (drawable == null) return worldly();
        Palette palette;
        try {
            Drawable copy = AppRepository.copy(drawable); // never touch the bounds of one on screen
            Bitmap small = Bitmap.createBitmap(96, 54, Bitmap.Config.ARGB_8888);
            copy.setBounds(0, 0, 96, 54);
            copy.draw(new Canvas(small));
            palette = Palette.from(small).maximumColorCount(16).generate();
            small.recycle();
        } catch (RuntimeException e) {
            return worldly(); // an app's drawable that can't be drawn off screen
        }

        Palette.Swatch vivid = first(palette.getVibrantSwatch(), palette.getLightVibrantSwatch(),
                colourful(palette.getDominantSwatch()), palette.getDarkVibrantSwatch());
        if (vivid == null) return worldly(); // a grey, white or black logo
        Palette.Swatch deep = first(palette.getDarkVibrantSwatch(), palette.getDarkMutedSwatch(),
                palette.getMutedSwatch());
        int vividColour = clampValue(vivid.getRgb(), 0.55f, 0.95f);
        int deepColour = deep != null && deep != vivid
                ? clampValue(deep.getRgb(), 0.25f, 0.55f)
                : clampValue(vivid.getRgb(), 0.25f, 0.40f); // a darker shade of the same colour
        return new AppColors(vividColour, deepColour);
    }

    private static Palette.Swatch first(Palette.Swatch... swatches) {
        for (Palette.Swatch s : swatches) if (s != null) return s;
        return null;
    }

    /** The dominant colour only if it is a real colour, not grey. */
    private static Palette.Swatch colourful(Palette.Swatch swatch) {
        if (swatch == null) return null;
        float[] hsv = new float[3];
        Color.colorToHSV(swatch.getRgb(), hsv);
        return hsv[1] > 0.3f && hsv[2] > 0.2f ? swatch : null;
    }

    /** Keeps a colour's brightness in range, so glows are never neon or muddy. */
    private static int clampValue(int colour, float min, float max) {
        float[] hsv = new float[3];
        Color.colorToHSV(colour, hsv);
        hsv[2] = Math.max(min, Math.min(max, hsv[2]));
        return Color.HSVToColor(hsv);
    }
}
