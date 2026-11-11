package com.camdeco.launcher;

import java.util.Random;

/**
 * The light show's own motion when it isn't following the sound: "Steam Pixel Wave", inspired by the LED rig of
 * Justice's Iris tour (lighting design: Vincent Lerisson). Wisps of light like steam in a beam drift across the wall
 * from left to right and a little upward, curling and folding as they go, while a slow, wide swell of energy travels
 * left to right with them, so some stretches glow hot and others fade to just the top row.
 *
 * How: a steam field of domain-warped fractal Perlin noise (two warp layers bend the coordinates into swirls, and
 * time inside the warps keeps them churning); its ridges (1 - |noise|) light up only above 1 - WISP_WIDTH, giving
 * thin filaments; the travelling energy envelope scales them; the top row stays lit wherever the energy is above 8%.
 * Coordinates are in lamps: across, every column of the wall (the gaps between strips count, as on the rig); down,
 * every row.
 */
final class Steam {
    private Steam() {}

    /** Overall speed (1 = the original). */
    static final float SPEED = 0.5f;
    /** Thickness of the lit filaments (0.05 thin to 0.6 thick). */
    static final float WISP_WIDTH = 0.13f;
    /** How strongly the wisps curl and fold (0 to 1). */
    static final float SWIRL = 0.30f;
    /** How fast the swirls change shape (0 frozen to 1 boiling). */
    static final float CHURN = 1.0f;
    /** Size of the swirls (1 = base, higher = wider loops). */
    static final float SWIRL_SIZE = 1.4f;
    /** How quickly each lamp eases toward its target, per second (fast, so edges stay crisp). */
    static final float EASE = 26 * 0.6f;

    private static final int[] P = new int[512];

    static {
        int[] p = new int[256];
        for (int i = 0; i < 256; i++) p[i] = i;
        Random random = new Random(1234);
        for (int i = 255; i > 0; i--) {
            int j = random.nextInt(i + 1);
            int t = p[i];
            p[i] = p[j];
            p[j] = t;
        }
        for (int i = 0; i < 512; i++) P[i] = p[i & 255];
    }

    private static float grad(int h, float a, float b) {
        int k = h & 7;
        float u = k < 4 ? a : b, v = k < 4 ? b : a;
        return ((k & 1) != 0 ? -u : u) + ((k & 2) != 0 ? -2 * v : 2 * v);
    }

    private static float fade(float t) {
        return t * t * t * (t * (t * 6 - 15) + 10);
    }

    static float noise(float px, float py) {
        float fx = (float) Math.floor(px), fy = (float) Math.floor(py);
        int X = ((int) fx) & 255, Y = ((int) fy) & 255;
        px -= fx;
        py -= fy;
        float u = fade(px), v = fade(py);
        int a = P[X] + Y, b = P[X + 1] + Y;
        float l1 = grad(P[a], px, py) + (grad(P[b], px - 1, py) - grad(P[a], px, py)) * u;
        float l2 = grad(P[a + 1], px, py - 1) + (grad(P[b + 1], px - 1, py - 1) - grad(P[a + 1], px, py - 1)) * u;
        return (l1 + (l2 - l1) * v) * 0.35f;
    }

    static float fbm(float px, float py) {
        float s = 0, a = 0.6f, f = 1;
        for (int o = 0; o < 3; o++) {
            s += a * noise(px * f, py * f);
            f *= 2;
            a *= 0.5f;
        }
        return s;
    }

    /** The steam field at lamp (gx, gy) at time T: its ridges are near 1. */
    static float steam(float gx, float gy, float T) {
        float c = CHURN * 1.2f, k = 1 / SWIRL_SIZE;
        float sx = gx * 0.09f - T * 0.35f;
        float sy = gy * 0.11f + T * 0.12f + (float) Math.sin(gx * 0.05f + T * 0.6f) * 0.25f * CHURN;
        float wx = sx * k, wy = sy * k;
        float q1 = fbm(wx * 0.8f + T * 0.45f * c * k, wy * 0.8f - T * 0.3f * c * k);
        float q2 = fbm(wx * 0.8f - T * 0.35f * c * k + 5.2f, wy * 0.8f + T * 0.4f * c * k + 1.3f);
        float r1 = fbm(wx + q1 * 2.5f * SWIRL + T * 0.25f * c * k + 1.7f, wy + q2 * 2.5f * SWIRL - T * 0.2f * c * k + 9.2f);
        float r2 = fbm(wx + q1 * 2.5f * SWIRL - T * 0.2f * c * k + 8.3f, wy + q2 * 2.5f * SWIRL + T * 0.3f * c * k + 2.8f);
        float ax = sx + r1 * 3 * SWIRL * SWIRL_SIZE, ay = sy + r2 * 3 * SWIRL * SWIRL_SIZE;
        float m = 0.5f + 0.5f * (float) Math.sin(T * 0.4f * c);
        float n = fbm(ax + T * 0.3f * c, ay - T * 0.22f * c) * m + fbm(ax - T * 0.25f * c + 3.1f, ay + T * 0.28f * c + 7.7f) * (1 - m);
        return 1 - Math.abs(n * 2.6f);
    }

    /** The energy at column gx at time T, 0 to 1: a slow, wide swell travelling left to right. */
    static float energy(float gx, float T) {
        float e = 0.5f + 0.3f * (float) Math.sin(gx * 0.07f - T * 0.5f) + fbm(gx * 0.04f - T * 0.2f, T * 0.08f) * 0.9f;
        return Math.max(0f, Math.min(1f, e));
    }

    /** How lit a lamp should be, 0 to 1: a filament of steam scaled by the energy; the top row lit by it alone. */
    static float target(float gx, float gy, float T, float energy, boolean topRow) {
        float th = 1 - WISP_WIDTH;
        float r = steam(gx, gy, T);
        float t = 0;
        if (r > th) t = Math.min(1f, (0.75f + 0.25f * (r - th) / WISP_WIDTH) * (0.45f + 0.65f * energy));
        if (topRow && energy > 0.08f) t = Math.max(t, 0.5f + 0.5f * energy);
        return t;
    }
}
