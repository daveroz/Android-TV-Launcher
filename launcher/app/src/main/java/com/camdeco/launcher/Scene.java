package com.camdeco.launcher;

import android.graphics.Color;

/**
 * The light on the stage: a key colour taken from what is in focus (an album cover, an app's own
 * colour), a fill colour that answers it from the opposite corner, and the near-white of an LED's
 * core. Two colours and white, never more.
 */
final class Scene {
    /** Anything that can say which scene is on, for views that draw in its colours. */
    interface Holder {
        Scene scene();
    }

    final int key;
    final int fill;
    final int led;
    /** The key, a little lighter, for text: labels stay easy to read on the night background. */
    final int label;

    Scene(int key, int fill, int led) {
        this.key = key;
        this.fill = fill;
        this.led = led;
        this.label = mix(key, Color.WHITE, 0.25f);
    }

    /** Cyan with a magenta fill: the default, and the colours of cool album art. */
    static Scene promise() {
        return new Scene(0xFF3FC6E8, 0xFFFF4FA3, 0xFFECF6FF);
    }

    /** Amber street light with a teal neon: the house light of the Apps screen. */
    static Scene house() {
        return new Scene(0xFFFFB04A, 0xFF2EC4B6, 0xFFFFF3DE);
    }

    /**
     * The scene for an app's or a cover's colours: the vivid one becomes the key, lifted so it
     * reads as light, and the fill is the colour that answers it. Grey art gets the default.
     */
    static Scene from(AppColors colours) {
        float[] hsv = new float[3];
        Color.colorToHSV(colours.vivid, hsv);
        if (hsv[1] < 0.2f) return promise();
        float hue = hsv[0];
        hsv[1] = Math.max(0.55f, Math.min(0.85f, hsv[1]));
        hsv[2] = Math.max(0.92f, hsv[2]);
        int key = Color.HSVToColor(hsv);
        int fill = Color.HSVToColor(new float[]{fillHue(hue), 0.72f, 0.96f});
        return new Scene(key, fill, mix(key, Color.WHITE, 0.88f));
    }

    /** The fill's hue for a key's: red meets blue, amber teal, green violet, cyan magenta. */
    static float fillHue(float keyHue) {
        if (keyHue >= 345f || keyHue < 25f) return 225f;
        if (keyHue < 70f) return 174f;
        if (keyHue < 165f) return 257f;
        if (keyHue < 250f) return 331f;
        return 190f; // violet and magenta keys get a cyan fill
    }

    boolean sameAs(Scene other) {
        return other != null && key == other.key && fill == other.fill && led == other.led;
    }

    /**
     * A colour between two lights, going round the colour wheel the short way, so the blend between
     * two answering colours stays a colour (magenta to cyan through violet) instead of turning grey.
     */
    static int blend(int from, int to, float f) {
        float[] a = new float[3], b = new float[3];
        Color.colorToHSV(from, a);
        Color.colorToHSV(to, b);
        float turn = ((b[0] - a[0]) % 360f + 540f) % 360f - 180f;
        float hue = ((a[0] + turn * f) % 360f + 360f) % 360f;
        return Color.HSVToColor(new float[]{hue, a[1] + (b[1] - a[1]) * f, a[2] + (b[2] - a[2]) * f});
    }

    static int alpha(int colour, float a) {
        int value = Math.max(0, Math.min(255, Math.round(a * 255)));
        return (value << 24) | (colour & 0x00FFFFFF);
    }

    static int mix(int from, int to, float f) {
        int r = Math.round(Color.red(from) + (Color.red(to) - Color.red(from)) * f);
        int g = Math.round(Color.green(from) + (Color.green(to) - Color.green(from)) * f);
        int b = Math.round(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * f);
        return Color.rgb(r, g, b);
    }
}
