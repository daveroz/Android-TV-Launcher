package com.camdeco.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.AnimationUtils;

/**
 * The light show at the top of Home, for every app: a wall of square lamps right across the screen
 * from margin to margin, as tall as an album cover, in one colour (the focused app's, or the album
 * cover's while a song shows), like the LED panels on Justice's stage. The lamps stand in strips
 * three wide, split into an upper and a lower panel.
 *
 * While something plays, the wall follows the sound: each lamp column is a part of the music, bass
 * on the left and treble on the right, filling up from the bottom with it, rising quickly and
 * settling back gently, its top lamp fading in and out. With an LP on screen, the music's columns
 * end where the record starts and the ones behind it echo the treble. When nothing plays, or it's
 * paused, an airstream with turbulence crosses the wall ({@link Airstream}, the same as Worldly's
 * Sound widget): two streams of air flowing in from the left on a slowly gusting wind, bent and
 * rolled by large eddies, lighting the lamps they pass. The two blend into each other.
 *
 * The lamps are lit as on Worldly's Sound widget: light sources in a dark, hazy room, not a brighter
 * picture. Each lit lamp has a warm core (white-hot only at full strength) with a soft, diffused
 * edge; its light blooms out around it (a tight bloom filling the gaps between lamps, and a wider
 * glow), scatters in the air over each lit strip (volumetric haze) and in a fog lit from below by
 * the wall, and lit panels throw soft beams down into it. Dark lamps keep a faint ember, caught by
 * the light of the lamps next to them, so the panels still read. Behind the text on the left the
 * lamps are dimmer, so it stays easy to read.
 */
public class LightShowView extends View {
    private static final int ROWS = 10;
    /** Strips are this many lamps wide, with a dark column between them. */
    private static final int STRIP = 3;
    private static final int PANEL_ROWS = ROWS / 2;
    /** Each lamp's size, as a share of the pitch. */
    private static final float LAMP = 0.7f;
    /** The haze and wide glows are drawn at this share of the view's size, the tight bloom at {@link #BLOOM}. */
    private static final float SOFT = 1 / 8f;
    private static final float BLOOM = 1 / 4f;
    private static final long FRAME_MS = 33;
    private static final int BANDS = SoundWave.COLUMNS;
    /** Following the sound: the top lamp of each column, and the one under it, are a little softer. */
    private static final float[] TIP = {0.6f, 0.85f};
    /** How long the wall takes to go from the airstream to the sound, or back. */
    private static final float REACT_SECONDS = 0.6f;

    private final float density;
    private final Paint lamp = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** Dark lamps are too faint for their rounded corners to show: plain squares, quicker to draw. */
    private final Paint emberPaint = new Paint();
    private final Paint glowPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint softPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    /** The tight bloom is light on light: screened, so it brightens and never darkens. */
    private final Paint bloomPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint bloomLamp = new Paint();
    private final Paint fogPaint = new Paint(Paint.DITHER_FLAG);
    private final Bitmap sprite = Glow.sprite();
    private final Bitmap beam = beamSprite();
    private final RectF rect = new RectF();
    private final Rect softBounds = new Rect(), bloomBounds = new Rect();
    /** This frame's strength for each lamp, and each panel's average. */
    private float[] values = new float[0];
    private float[] panelLight = new float[0];
    /** Each strip's light (all its lamps, 0 to 1) and how high it sits (the light-weighted middle, in pixels). */
    private float[] stripLight = new float[0], stripY = new float[0];
    /** The colour the fog was made for. */
    private int fogKey;
    /** Per column: how strong its lamps may be (dimmer behind the text). */
    private float[] columnStrength = new float[0];
    /** As many columns as fit between the margins, and the strips of three they make. */
    private int columns, strips;
    private Bitmap soft, bloom;
    private Canvas softCanvas, bloomCanvas;
    private Scene scene = Scene.promise();
    private boolean running;
    private long lastFrame;
    /** The airstream (the wall's own motion when it isn't following the sound), and each lamp's own warmth. */
    private Airstream air;
    private float[] tone = new float[0];
    /** The wall's top-left lamp space, and the pitch and size of the lamps. */
    private float left, top, pitch, cell;
    /** The sound: where it comes from, the levels it's heading for, and the levels on show. */
    private SoundWave source;
    private final float[] target = new float[BANDS];
    private final float[] shown = new float[BANDS];
    private double soundTime;
    /** Whether the wall follows the sound, and how far it has gone over to it (0 patterns, 1 sound). */
    private boolean reactive;
    private float react;
    /** Whether an LP is on screen, so the music's columns end where the record starts. */
    private boolean recordShown;
    /** Following the sound, per column: which part of the music (-1 for a gap), and how strong. */
    private int[] columnBand = new int[0];
    private float[] soundStrength = new float[0];

    public LightShowView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = context.getResources().getDisplayMetrics().density;
        glowPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD)); // light adds up
        bloomPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SCREEN));
    }

    void setScene(Scene newScene) {
        scene = newScene;
        invalidate();
    }

    /** The colour the show is lit in (for tests). */
    int keyColour() {
        return scene.key;
    }

    /** Where the sound comes from. */
    void setSource(SoundWave wave) {
        source = wave;
    }

    /** Follow the sound (something is playing), or show the airstream (paused, or nothing playing). */
    void setReactive(boolean on) {
        if (reactive == on) return;
        reactive = on;
        invalidate();
    }

    boolean isReactive() {
        return reactive;
    }

    /** An LP is on the right: the music's columns end where its record starts. */
    void setRecordShown(boolean shown) {
        if (recordShown == shown) return;
        recordShown = shown;
        mapBands();
        invalidate();
    }

    /** How loud the bass is on the wall right now, 0 to 1: the stage's washes swell with it. */
    float bass() {
        float sum = 0f;
        for (int b = 0; b < 8; b++) sum += shown[b];
        return react * sum / 8;
    }

    /** How lit a part of the music is right now (for tests). */
    float level(int band) {
        return shown[band];
    }

    /** Sets a part of the music's level straight away, and goes all the way over to the sound (for tests). */
    void showForTests(int band, float value) {
        shown[band] = value;
        react = reactive ? 1f : 0f;
    }

    /** Which part of the music a column shows while following the sound, or -1 (for tests). */
    int bandOfColumn(int column) {
        return columnBand[column];
    }

    /** Animates while the show is on screen and the launcher is in front. */
    void setRunning(boolean run) {
        if (running == run) return;
        running = run;
        lastFrame = 0;
        if (run) invalidate();
    }

    /** The wall's left, top, right and bottom in the view, around its lamps' spaces (for tests). */
    float[] showBounds() {
        return new float[]{left, top, left + columns * pitch, top + ROWS * pitch};
    }

    /** Moves the show on by this many seconds (for tests). */
    void advanceForTests(float seconds) {
        for (float t = 0f; t < seconds && air != null; t += 1 / 12f) air.step(1 / 12f);
    }

    /** How lit a lamp is right now, 0 to 1, after the last frame drawn (for tests). */
    float lampForTests(int column, int row) {
        return values[row * columns + column];
    }

    /** The distance from one lamp to the next (for tests). */
    float pitch() {
        return pitch;
    }

    /** How many columns the wall has (for tests). */
    int columnsForTests() {
        return columns;
    }

    /** Whether a column is a dark gap between strips: the first of every four. */
    static boolean gap(int column) {
        return column % (STRIP + 1) == 0;
    }

    /** A strip's first column. */
    private static int firstOf(int strip) {
        return strip * (STRIP + 1) + 1;
    }

    /** How many columns a strip has: three, or fewer for the last one at the margin. */
    private int widthOf(int strip) {
        return Math.max(0, Math.min(STRIP, columns - firstOf(strip)));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        // As tall as the cover would be, level with the text, its right edge at the screen margin.
        float height = getResources().getDimension(R.dimen.hero_card_height);
        float margin = getResources().getDimension(R.dimen.overscan_h);
        pitch = height / ROWS;
        cell = pitch * LAMP;
        // From margin to margin: as many columns as fit, right edge at the margin like the clock.
        columns = Math.max(0, (int) ((w - 2 * margin) / pitch));
        strips = columns <= 1 ? 0 : (columns - 2) / (STRIP + 1) + 1;
        left = w - margin - columns * pitch;
        top = getResources().getDimension(R.dimen.hero_top);
        values = new float[ROWS * columns];
        tone = new float[ROWS * columns];
        for (int i = 0; i < tone.length; i++) tone[i] = 0.82f + 0.18f * seed(i * 13 + 7);
        air = new Airstream(columns, ROWS);
        for (int i = 0; i < 60; i++) air.step(1 / 12f); // the wall starts already in motion
        panelLight = new float[strips * 2];
        stripLight = new float[strips];
        stripY = new float[strips];
        fogKey = 0;
        // Behind the text (the left 440dp) the lamps are dimmer, brightening just past it.
        float textRight = margin + 440 * density;
        columnStrength = new float[columns];
        soundStrength = new float[columns];
        columnBand = new int[columns];
        for (int c = 0; c < columns; c++) {
            float x = left + (c + 0.5f) * pitch;
            float t = Math.max(0f, Math.min(1f, (x - (textRight - 20 * density)) / (100 * density)));
            float ease = t * t * (3 - 2 * t);
            columnStrength[c] = 0.45f + 0.55f * ease;
            soundStrength[c] = 0.7f + 0.3f * ease; // the music's bass is behind the text: dim it less
        }
        mapBands();
        // The haze, glows and beams spread over the whole screen (the fog reaches up to its top, the beams fall behind
        // the rows); the tight bloom stays close around the wall.
        softBounds.set(0, 0, w, h);
        bloomBounds.set(Math.round(left - 2 * pitch), Math.round(top - 2 * pitch),
                Math.round(left + (columns + 2) * pitch), Math.round(top + (ROWS + 2) * pitch));
        if (soft != null) soft.recycle();
        if (bloom != null) bloom.recycle();
        soft = bloom = null;
        if (w > 0 && h > 0 && columns > 0) {
            soft = Bitmap.createBitmap(Math.max(1, Math.round(w * SOFT)), Math.max(1, Math.round(h * SOFT)),
                    Bitmap.Config.ARGB_8888);
            softCanvas = new Canvas(soft);
            softCanvas.scale(SOFT, SOFT);
            bloom = Bitmap.createBitmap(Math.max(1, Math.round(bloomBounds.width() * BLOOM)),
                    Math.max(1, Math.round(bloomBounds.height() * BLOOM)), Bitmap.Config.ARGB_8888);
            bloomCanvas = new Canvas(bloom);
            bloomCanvas.scale(BLOOM, BLOOM);
            bloomCanvas.translate(-bloomBounds.left, -bloomBounds.top);
        }
    }

    /**
     * Shares the music out between the lamp columns, bass first: across the whole wall, or up to
     * where the record starts, the columns behind it echoing the treble more and more faintly.
     */
    private void mapBands() {
        if (columns == 0) return;
        float margin = getResources().getDimension(R.dimen.overscan_h);
        float card = getResources().getDimension(R.dimen.hero_card_height);
        float edge = recordShown ? getWidth() - margin - card * 1.47f - 8 * density : Float.MAX_VALUE;
        int music = 0;
        for (int c = 0; c < columns; c++) if (!gap(c) && left + (c + 1) * pitch <= edge) music++;
        music = Math.max(2, music);
        int index = 0;
        for (int c = 0; c < columns; c++) {
            if (gap(c)) {
                columnBand[c] = -1;
                continue;
            }
            columnBand[c] = index < music ? Math.round(index * (BANDS - 1) / (float) (music - 1))
                    : Math.max(0, BANDS - 1 - (index - music) * 2);
            soundStrength[c] = Math.abs(soundStrength[c]) * (index < music ? 1f : -1f); // negative: an echo
            index++;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (soft == null) return;
        long now = AnimationUtils.currentAnimationTimeMillis();
        float dt = lastFrame == 0 ? 0f : Math.min(0.1f, (now - lastFrame) / 1000f);
        lastFrame = now;
        if (react < 1f) air.step(dt); // the air moves on while it shows
        listen(dt);
        light();
        if (react > 0f) followSound();
        measurePanels();

        soft.eraseColor(0);
        float gap = (pitch - cell) / 2;
        haze();
        // Beams from the lit panels, falling down and a little to the left into the haze.
        for (int s = 0; s < strips; s++) {
            int width = widthOf(s);
            if (width <= 0) continue;
            for (int p = 0; p < 2; p++) {
                float b = panelLight[s * 2 + p];
                if (b < 0.15f) continue;
                float x = left + (firstOf(s) + width / 2f) * pitch;
                float y = top + (p + 1) * PANEL_ROWS * pitch - gap;
                drawBeam(x, y, width * pitch * 2.4f, 140 * density, 0.35f * b);
            }
        }
        // A wide, soft glow around every lit lamp.
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < columns; c++) {
                float v = values[r * columns + c];
                if (v < 0.08f) continue;
                float x = left + c * pitch + gap, y = top + r * pitch + gap;
                glowPaint.setColor(Scene.alpha(scene.deep, v * 0.3f));
                rect.set(x - 1.8f * cell, y - 1.8f * cell, x + 2.8f * cell, y + 2.8f * cell);
                softCanvas.drawBitmap(sprite, null, rect, glowPaint);
            }
        }
        canvas.drawBitmap(soft, null, softBounds, softPaint);

        // The bloom: the lit lamps again, blurred (drawn small, spread out as it's scaled back up) and screened onto
        // the dark around them, so their light bleeds softly into the gaps between them without washing out their cores.
        float spread = cell * 0.12f;
        boolean anyLit = false;
        bloom.eraseColor(0);
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < columns; c++) {
                float v = values[r * columns + c];
                if (v < 0.08f) continue;
                float x = left + c * pitch + gap, y = top + r * pitch + gap;
                bloomLamp.setColor(Scene.alpha(scene.deep, 0.95f * v));
                bloomCanvas.drawRect(x - spread, y - spread, x + cell + spread, y + cell + spread, bloomLamp);
                anyLit = true;
            }
        }
        if (anyLit) canvas.drawBitmap(bloom, null, bloomBounds, bloomPaint);

        // The lamps. Lit: a warm core (deeper when dim, hot when bright, white-hot only at the very top), its edge
        // softened by a faint skirt of its light. Dark: a faint ember, catching the light of the lamps around it.
        int ember = Scene.mix(scene.deep, 0xFF000000, 0.3f);
        float round = cell * 0.18f, skirt = cell * 0.16f;
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < columns; c++) {
                if (gap(c)) continue; // the dark column between strips
                float v = values[r * columns + c];
                float x = left + c * pitch + gap, y = top + r * pitch + gap;
                if (v < 0.02f) {
                    emberPaint.setColor(Scene.alpha(ember, 0.03f + 0.2f * spill(r, c)));
                    canvas.drawRect(x, y, x + cell, y + cell, emberPaint);
                    continue;
                }
                lamp.setColor(Scene.alpha(scene.deep, 0.35f * v));
                rect.set(x - skirt, y - skirt, x + cell + skirt, y + cell + skirt);
                canvas.drawRoundRect(rect, round + skirt, round + skirt, lamp);
                int core = Scene.mix(Scene.mix(scene.deep, scene.hot, v), scene.led, 0.06f * v * v * v * v);
                lamp.setColor(Scene.alpha(core, Math.min(1f, 0.15f + v)));
                rect.set(x, y, x + cell, y + cell);
                canvas.drawRoundRect(rect, round, round, lamp);
            }
        }
        if (running) {
            if (reactive || react > 0f) postInvalidateOnAnimation(); // the sound needs every frame
            else postInvalidateDelayed(FRAME_MS);
        }
    }

    /**
     * The light the wall scatters into the air: a fog lit from below by the whole wall (strongest at its foot, fading
     * up to the top of the screen and down behind the rows, so the dark around it is never quite black while it's
     * lit), and a haze around each lit strip's light, reaching up past its top into the dark.
     */
    private void haze() {
        float all = 0f;
        for (int s = 0; s < strips; s++) all += stripLight[s];
        all /= Math.max(1, strips);
        if (fogKey != scene.key) {
            fogKey = scene.key;
            float wallBottom = top + ROWS * pitch, below = wallBottom + 200 * density;
            fogPaint.setShader(new LinearGradient(0, 0, 0, below,
                    new int[]{Scene.alpha(scene.deep, 0.12f), Scene.alpha(scene.deep, 0.45f), scene.deep,
                            scene.deep & 0x00FFFFFF},
                    new float[]{0f, top / below, wallBottom / below, 1f}, Shader.TileMode.CLAMP));
        }
        fogPaint.setAlpha(Math.round(255 * Math.min(0.24f, 0.08f + 0.4f * all)));
        softCanvas.drawRect(softBounds, fogPaint);
        for (int s = 0; s < strips; s++) {
            float light = stripLight[s];
            int width = widthOf(s);
            if (light < 0.03f || width <= 0) continue;
            float x = left + (firstOf(s) + width / 2f) * pitch;
            float rx = STRIP * pitch * 2.2f, ry = Math.max(ROWS * pitch * 0.6f, 5 * pitch);
            glowPaint.setColor(Scene.alpha(scene.deep, Math.min(0.6f, 0.8f * light)));
            rect.set(x - rx, stripY[s] - ry, x + rx, stripY[s] + ry);
            softCanvas.drawBitmap(sprite, null, rect, glowPaint);
        }
    }

    /** How much light a dark lamp catches from the lamps around it, 0 to 1. */
    private float spill(int r, int c) {
        float s = 0.6f * Math.max(at(r - 1, c), at(r + 1, c));
        s = Math.max(s, 0.45f * Math.max(at(r, c - 1), at(r, c + 1)));
        s = Math.max(s, 0.3f * Math.max(at(r - 2, c), at(r + 2, c)));
        s = Math.max(s, 0.3f * Math.max(Math.max(at(r - 1, c - 1), at(r - 1, c + 1)), Math.max(at(r + 1, c - 1), at(r + 1, c + 1))));
        return s;
    }

    private float at(int r, int c) {
        return r < 0 || r >= ROWS || c < 0 || c >= columns ? 0f : values[r * columns + c];
    }

    /** Moves the sound's levels on, and the blend between the patterns and the sound. */
    private void listen(float dt) {
        float step = dt / REACT_SECONDS;
        react = reactive ? Math.min(1f, react + step) : Math.max(0f, react - step);
        soundTime += dt;
        if (source != null && (reactive || react > 0f)) source.levels(soundTime, target);
        else java.util.Arrays.fill(target, 0f);
        // Rise quickly but eased; settle back over about a second.
        float rise = Math.min(1f, dt * 18);
        float settle = (float) Math.pow(0.06, dt);
        for (int b = 0; b < BANDS; b++) {
            float l = target[Math.max(0, b - 1)], r = target[Math.min(BANDS - 1, b + 1)];
            float goal = 0.2f * l + 0.6f * target[b] + 0.2f * r;
            shown[b] = goal > shown[b] ? shown[b] + (goal - shown[b]) * rise : goal + (shown[b] - goal) * settle;
        }
    }

    /** Blends each lamp toward the sound: columns filling up from the bottom with their part of the music. */
    private void followSound() {
        float blend = react * react * (3 - 2 * react);
        for (int c = 0; c < columns; c++) {
            if (columnBand[c] < 0) continue;
            boolean echo = soundStrength[c] < 0;
            float strength = Math.abs(soundStrength[c]) * (echo ? 0.5f : 1f);
            float lit = shown[columnBand[c]] * ROWS;
            int full = (int) lit;
            int topRow = full < ROWS && lit - full >= 0.03f ? full : full - 1;
            for (int r = 0; r < ROWS; r++) {
                int fromBottom = ROWS - 1 - r;
                float v = fromBottom < full ? 1f : fromBottom == full ? lit - full : 0f;
                int fromTop = topRow - fromBottom;
                if (v > 0f && fromTop >= 0 && fromTop < TIP.length) v *= TIP[fromTop];
                int i = r * columns + c;
                values[i] += (v * strength - values[i]) * blend;
            }
        }
    }

    /** Each panel's average light, for its beam, and each strip's light and its middle, for the haze. */
    private void measurePanels() {
        for (int s = 0; s < strips; s++) {
            int width = widthOf(s);
            float all = 0f, at = 0f;
            for (int r = 0; r < ROWS; r++) {
                for (int k = 0; k < width; k++) {
                    float v = values[r * columns + firstOf(s) + k];
                    all += v;
                    at += v * r;
                }
            }
            stripLight[s] = width == 0 ? 0f : all / (width * ROWS);
            stripY[s] = top + (all > 0f ? at / all + 0.5f : ROWS) * pitch;
            for (int p = 0; p < 2; p++) {
                float sum = 0f;
                int count = 0;
                for (int r = p * PANEL_ROWS; r < (p + 1) * PANEL_ROWS; r++) {
                    for (int k = 0; k < STRIP; k++) {
                        int c = s * (STRIP + 1) + 1 + k;
                        if (c >= columns) continue;
                        sum += values[r * columns + c];
                        count++;
                    }
                }
                panelLight[s * 2 + p] = count == 0 ? 0f : sum / count;
            }
        }
    }

    /** Every lamp's strength for now from the airstream: each takes its own warmth, and is dimmer behind the text. */
    private void light() {
        for (int c = 0; c < columns; c++) {
            if (gap(c)) continue;
            for (int r = 0; r < ROWS; r++) {
                int i = r * columns + c;
                values[i] = air.at(r, c) * tone[i] * columnStrength[c];
            }
        }
    }

    /** A soft beam from a point, falling down and a little to the left, widening as it fades. */
    private void drawBeam(float x, float y, float width, float length, float strength) {
        glowPaint.setColor(Scene.alpha(scene.key, Math.min(1f, strength)));
        softCanvas.save();
        softCanvas.rotate(12f, x, y);
        rect.set(x - width / 2, y, x + width / 2, y + length);
        softCanvas.drawBitmap(beam, null, rect, glowPaint);
        softCanvas.restore();
    }

    /** A beam's shape: narrow and strong at the top, wider and fading to nothing at the bottom. */
    private static Bitmap beamSprite() {
        int w = 32, h = 96;
        Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8);
        int[] pixels = new int[w * h];
        for (int y = 0; y < h; y++) {
            float t = y / (float) (h - 1);
            float half = 0.3f + 0.7f * t;
            for (int x = 0; x < w; x++) {
                float across = Math.abs((x + 0.5f) / w * 2 - 1) / half;
                float a = across >= 1f ? 0f : (float) Math.pow(1 - t, 1.4) * (1 - across * across);
                pixels[y * w + x] = Math.round(a * 255) << 24;
            }
        }
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h);
        return bitmap;
    }

    /** A fixed pseudo-random number. */
    private static float seed(int i) {
        double s = Math.sin(i * 127.1 + 311.7) * 43758.5453;
        return (float) (s - Math.floor(s));
    }
}
