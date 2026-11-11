package com.camdeco.launcher;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;

import androidx.palette.graphics.Palette;

/**
 * The primary colour of a picture (an album cover, an app's banner or icon): the colour that
 * covers the most of it, among its real colours. Grey, white or black pictures have none.
 */
final class AppColors {
    /** No real colour: the picture is grey, white or black. */
    static final int NONE = 0;

    private AppColors() {
    }

    /** Slow-ish (draws and analyses a small copy): call off the main thread. */
    static int primary(Drawable drawable) {
        if (drawable == null) return NONE;
        Palette palette;
        try {
            Drawable copy = AppRepository.copy(drawable); // never touch the bounds of one on screen
            Bitmap small = Bitmap.createBitmap(96, 54, Bitmap.Config.ARGB_8888);
            copy.setBounds(0, 0, 96, 54);
            copy.draw(new Canvas(small));
            palette = Palette.from(small).maximumColorCount(16).generate();
            small.recycle();
        } catch (RuntimeException e) {
            return NONE; // an app's drawable that can't be drawn off screen
        }
        Palette.Swatch best = null;
        for (Palette.Swatch swatch : palette.getSwatches()) {
            if (colourful(swatch.getRgb()) && (best == null || swatch.getPopulation() > best.getPopulation())) {
                best = swatch;
            }
        }
        return best == null ? NONE : best.getRgb() | 0xFF000000;
    }

    /** A real colour, not grey, nor nearly white or black. */
    static boolean colourful(int colour) {
        float[] hsv = new float[3];
        Color.colorToHSV(colour, hsv);
        return hsv[1] >= 0.3f && hsv[2] >= 0.2f;
    }
}
