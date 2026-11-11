package com.camdeco.launcher;

import android.graphics.Bitmap;

/**
 * The soft round glow behind a sharp light (the wall's lamps, the visualizer's cells): full in the
 * middle and fading to nothing at the edge. It's alpha only, so drawing it takes the paint's
 * colour, and it's drawn into a small layer that's stretched over the screen, like light in fog.
 */
final class Glow {
    private static Bitmap sprite;

    private Glow() {
    }

    static synchronized Bitmap sprite() {
        if (sprite != null && !sprite.isRecycled()) return sprite;
        int size = 32;
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ALPHA_8);
        int[] pixels = new int[size * size];
        float r = size / 2f;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                float d = (float) Math.hypot(x + 0.5f - r, y + 0.5f - r) / r;
                float a = d >= 1f ? 0f : (1 - d) * (1 - d);
                pixels[y * size + x] = Math.round(a * 255) << 24;
            }
        }
        bitmap.setPixels(pixels, 0, size, 0, 0, size, size);
        sprite = bitmap;
        return bitmap;
    }
}
