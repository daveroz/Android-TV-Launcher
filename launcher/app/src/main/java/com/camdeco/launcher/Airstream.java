package com.camdeco.launcher;

/**
 * The light show's own motion when it isn't following the sound: an airstream with turbulence, tuned in the Sound
 * Wall Simulator (the settings below are the ones saved there, run at {@link #PACE} times the simulator's own speed,
 * as it was watched there). Air enters on the left in streams, each a spray of
 * particles carried by a wind that gusts slowly; a turbulence field (the curl of 3D noise, so it swirls without
 * bunching up, carried along with the wind and slowly changing shape) bends and rolls them as they cross. Each
 * particle leaves light in the lamps it passes, which fades over the trail time; a lamp lights once enough air has
 * passed through it, and eases on and off.
 *
 * Coordinates are in lamps: across, every column of the wall (the gaps between strips count, and stay dark); down,
 * every row, the top one first. The same as Worldly's Sound widget (its {@code Airstream}), so the launcher and the
 * widget move alike. It keeps its own state (the particles and the light they left), moved on by
 * {@link #step}.
 */
final class Airstream {
    // The settings, as saved from the simulator. Speeds are in lamps a second.
    /** How fast it all runs: seconds of air for each second shown (the simulator's speed control, set to 2×). */
    static final float PACE = 2f;
    /** The wind's speed, how much it gusts (0 to 1) and how often (gusts a second), and a drift up (below 0: down). */
    static final float WIND = 5f, GUST = 0.48f, GUST_RATE = 0.02f, RISE = 0f;
    /** How many streams enter on the left, how wide each is (lamps) and how much its source wanders (0 to 1). */
    static final int LANES = 2;
    static final float SPREAD = 0f, WANDER = 0f;
    /** Bits of air a second in each stream, how long the light they leave lasts (s), and how much it fades along the way. */
    static final float DENSITY = 10f, TRAIL = 2f, FADE_ALONG = 0f;
    /** Turbulence: its strength, eddy size (lamps), how fast the eddies change, how much it builds towards the right
     * (0 to 1), and the share of small eddies riding on the big ones. */
    static final float TURB = 1.7f, EDDY = 12f, CHURN = 2f, DEVELOP = 0f, DETAIL = 0.05f;
    /** Lamps: how much air brightens them, how much they need before they light, how softly they come on, and how
     * quickly they follow (a second). */
    static final float GAIN = 3.05f, THRESHOLD = 0.74f, SOFTNESS = 0.02f, RESPONSE = 4f;
    /** Whether the top row lights wherever the air is strong in its column. */
    static final boolean TOP_ROW = false;
    /** The turbulence pattern (the simulator's "New turbulence"). */
    private static final int SEED = 7;
    /** At most this many bits of air at once. */
    private static final int MOST = 4000;
    /** The air moves on in steps of at most this long (s), as the simulator's are. */
    private static final float STEP = 1 / 12f;

    private static final int[] P = new int[512];

    static {
        int[] seed = {SEED};
        int[] p = new int[256];
        for (int i = 0; i < 256; i++) p[i] = i;
        for (int i = 255; i > 0; i--) {
            int j = (int) Math.floor(random(seed) * (i + 1));
            int t = p[i];
            p[i] = p[j];
            p[j] = t;
        }
        for (int i = 0; i < 512; i++) P[i] = p[i & 255];
    }

    private final int columns, rows;
    private final float[] ink, lamp;
    private float[] xs = new float[256], ys = new float[256];
    private int count;
    private final float[] acc = new float[LANES];
    private final int[] rng = {11};
    /**
     * Where the air is in the turbulence: how far the wind has carried it, and how far the gusts, the wandering
     * sources and the eddies' change of shape have gone. The noise repeats every 256, so each is kept within one
     * repeat (it runs for days, and large numbers lose their fine steps).
     */
    private float drift, gusts, wanders, churn;
    private float wind = WIND;
    private final float[] velocity = new float[2];

    Airstream(int columns, int rows) {
        this.columns = columns;
        this.rows = rows;
        ink = new float[columns * rows];
        lamp = new float[columns * rows];
        for (int k = 0; k < LANES; k++) acc[k] = (float) random(rng);
    }

    /** How lit the lamp at row {@code r}, column {@code c} is, 0 to 1. */
    float at(int r, int c) {
        return lamp[r * columns + c];
    }

    /** Moves the air on by {@code dt} seconds shown: {@link #PACE} times as much air, in steps of {@link #STEP}. */
    void step(float dt) {
        float left = dt * PACE;
        while (left > 1e-4f) {
            float h = Math.min(left, STEP);
            once(h);
            left -= h;
        }
    }

    /** Moves the air on by one step of {@code dt} seconds. */
    private void once(float dt) {
        if (dt <= 0f) return;
        gusts = (gusts + dt * GUST_RATE) % 256f;
        wanders = (wanders + dt * 0.2f) % 256f;
        churn = (churn + dt * CHURN) % 256f;
        wind = WIND * Math.max(0.15f, 1 + GUST * 1.6f * noise(gusts, 3.7f, 1.3f));
        drift = (drift + wind * 0.9f * dt) % (256f * EDDY);
        // New air at each stream's source.
        for (int k = 0; k < LANES; k++) {
            acc[k] += DENSITY * dt;
            float base = (k + 0.5f) / LANES * rows + WANDER * noise(k * 3.1f + 0.5f, wanders, 5.5f) * rows * 0.18f;
            while (acc[k] >= 1f) {
                acc[k] -= 1f;
                add(-0.5f - (float) random(rng) * wind * dt, base + gauss() * SPREAD);
            }
        }
        // The light fades over the trail time.
        float keep = (float) Math.exp(-dt / Math.max(0.01f, TRAIL));
        for (int i = 0; i < ink.length; i++) ink[i] *= keep;
        // Each bit of air is carried through the flow, leaving light behind it.
        float fastest = wind + TURB * WIND * 1.2f + Math.abs(RISE);
        int steps = Math.max(1, (int) Math.ceil(fastest * dt / 0.45f));
        float h = dt / steps;
        int alive = 0;
        for (int n = 0; n < count; n++) {
            float x = xs[n], y = ys[n];
            for (int s = 0; s < steps; s++) {
                flow(x, y);
                x += velocity[0] * h;
                y += velocity[1] * h;
                deposit(x, y, h * (1 - FADE_ALONG * clamp(x / columns)));
            }
            if (x < columns + 1.5f && x > -4f && y > -2f && y < rows + 2f) {
                xs[alive] = x;
                ys[alive] = y;
                alive++;
            }
        }
        count = alive;
        // The lamps ease toward the light the air left.
        float ease = Math.min(1f, dt * RESPONSE);
        for (int c = 0; c < columns; c++) {
            float energy = 0f;
            boolean gap = LightShowView.gap(c);
            for (int r = 0; r < rows; r++) {
                int i = r * columns + c;
                float raw = 1f - (float) Math.exp(-ink[i] * GAIN);
                energy = Math.max(energy, raw);
                float target = gap ? 0f : smooth(THRESHOLD, THRESHOLD + SOFTNESS, raw);
                lamp[i] += (target - lamp[i]) * ease;
            }
            if (TOP_ROW && !gap && energy > 0.08f) lamp[c] = Math.max(lamp[c], 0.7f + 0.3f * energy);
        }
    }

    /** A new bit of air; the oldest give way past {@link #MOST}. */
    private void add(float x, float y) {
        if (count == MOST) {
            System.arraycopy(xs, 1, xs, 0, count - 1);
            System.arraycopy(ys, 1, ys, 0, count - 1);
            count--;
        }
        if (count == xs.length) {
            xs = java.util.Arrays.copyOf(xs, Math.min(MOST, count * 2));
            ys = java.util.Arrays.copyOf(ys, Math.min(MOST, count * 2));
        }
        xs[count] = x;
        ys[count] = y;
        count++;
    }

    /** The air's velocity at ({@code x}, {@code y}) into {@link #velocity}: the wind, plus the curl of the turbulence. */
    private void flow(float x, float y) {
        float e = 0.35f;
        float dY = (psi(x, y + e) - psi(x, y - e)) / (2 * e) * EDDY;
        float dX = (psi(x + e, y) - psi(x - e, y)) / (2 * e) * EDDY;
        float s = clamp(x / columns);
        float ramp = (1 - DEVELOP) + DEVELOP * s * s * (3 - 2 * s);
        float amount = TURB * ramp * WIND * 0.5f;
        velocity[0] = wind + amount * dY;
        velocity[1] = -amount * dX + RISE;
    }

    /** The turbulence's stream function: noise carried along with the wind, changing shape as it goes. */
    private float psi(float x, float y) {
        float k = 1f / EDDY, px = (x - drift) * k, py = y * k, pz = churn;
        float v = noise(px, py, pz);
        if (DETAIL > 0f) v += DETAIL * 0.5f * noise(px * 2.3f + 17.1f, py * 2.3f - 4.7f, pz * 1.7f + 3.3f);
        return v;
    }

    /** Leaves {@code a} of light at ({@code x}, {@code y}), shared between the four lamps around it. */
    private void deposit(float x, float y, float a) {
        float fx = x - 0.5f, fy = y - 0.5f;
        int i0 = (int) Math.floor(fx), j0 = (int) Math.floor(fy);
        float tx = fx - i0, ty = fy - j0;
        put(i0, j0, a * (1 - tx) * (1 - ty));
        put(i0 + 1, j0, a * tx * (1 - ty));
        put(i0, j0 + 1, a * (1 - tx) * ty);
        put(i0 + 1, j0 + 1, a * tx * ty);
    }

    private void put(int i, int j, float v) {
        if (i >= 0 && i < columns && j >= 0 && j < rows) ink[j * columns + i] += v;
    }

    /** A normally distributed number (Box-Muller), from the same random sequence the simulator uses. */
    private float gauss() {
        double u = 0, v = 0;
        while (u == 0) u = random(rng);
        while (v == 0) v = random(rng);
        return (float) (Math.sqrt(-2 * Math.log(u)) * Math.cos(6.2831853 * v));
    }

    /** mulberry32: a small random sequence, the same as the simulator's, from {@code state[0]}. */
    private static double random(int[] state) {
        int a = state[0] += 0x6D2B79F5;
        int t = (a ^ (a >>> 15)) * (1 | a);
        t = (t + ((t ^ (t >>> 7)) * (61 | t))) ^ t;
        return ((t ^ (t >>> 14)) & 0xFFFFFFFFL) / 4294967296.0;
    }

    private static float clamp(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private static float smooth(float a, float b, float x) {
        if (x <= a) return 0f;
        if (x >= b) return 1f;
        float s = (x - a) / (b - a);
        return s * s * (3 - 2 * s);
    }

    private static float fade(float t) {
        return t * t * t * (t * (t * 6 - 15) + 10);
    }

    private static float lerp(float t, float a, float b) {
        return a + t * (b - a);
    }

    private static float grad(int h, float x, float y, float z) {
        int k = h & 15;
        float u = k < 8 ? x : y, v = k < 4 ? y : (k == 12 || k == 14 ? x : z);
        return ((k & 1) != 0 ? -u : u) + ((k & 2) != 0 ? -v : v);
    }

    /** 3D improved Perlin noise, about -1 to 1. */
    static float noise(float x, float y, float z) {
        float fx = (float) Math.floor(x), fy = (float) Math.floor(y), fz = (float) Math.floor(z);
        int X = ((int) fx) & 255, Y = ((int) fy) & 255, Z = ((int) fz) & 255;
        x -= fx;
        y -= fy;
        z -= fz;
        float u = fade(x), v = fade(y), w = fade(z);
        int A = P[X] + Y, AA = P[A] + Z, AB = P[A + 1] + Z, B = P[X + 1] + Y, BA = P[B] + Z, BB = P[B + 1] + Z;
        return lerp(w,
                lerp(v, lerp(u, grad(P[AA], x, y, z), grad(P[BA], x - 1, y, z)),
                        lerp(u, grad(P[AB], x, y - 1, z), grad(P[BB], x - 1, y - 1, z))),
                lerp(v, lerp(u, grad(P[AA + 1], x, y, z - 1), grad(P[BA + 1], x - 1, y, z - 1)),
                        lerp(u, grad(P[AB + 1], x, y - 1, z - 1), grad(P[BB + 1], x - 1, y - 1, z - 1))));
    }
}
