package com.camdeco.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.AnimationUtils;

/**
 * The music beside the song in the hero, as LED panels like an app's light show: the same square
 * lamps, in strips three wide with a dark column between them, level with the album cover (same top
 * and bottom) and behind the song's text. Each lamp column is a part of the music, bass on the left
 * and treble on the right, and fills up from the bottom with it, rising quickly and settling back
 * gently; the top lamp fades in and out, so heights change smoothly. Unlit lamps keep a faint
 * ember, lit ones glow, whiter toward the bottom, and the strips throw soft beams down into the haze
 * as loud as they are. The last strip trails off as it passes behind the record. Behind the text
 * the lamps are a little dimmer, so it stays easy to read. One colour: the album cover's.
 */
public class SoundWaveView extends View {
    private static final int BANDS = SoundWave.COLUMNS;
    private static final int ROWS = 10;
    /** Strips are this many lamps wide, with a dark column after each. */
    private static final int STRIP = 3;
    /** Each lamp's size, as a share of the pitch: the light show's squares. */
    private static final float LAMP = 0.7f;
    /** The top lamp of each column, and the one under it, are a little softer. */
    private static final float[] TIP = {0.6f, 0.85f};
    private static final float SOFT = 1 / 8f;

    private final float density;
    private final Paint lamp = new Paint();
    private final Paint glowPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint softPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Bitmap sprite = Glow.sprite();
    private final Bitmap beam = beamSprite();
    private final RectF rect = new RectF();
    private final Rect softBounds = new Rect();
    private final float[] target = new float[BANDS];
    private final float[] shown = new float[BANDS];
    private Bitmap soft;
    private Canvas softCanvas;
    private SoundWave source;
    private boolean running;
    private long lastFrame;
    private double time;
    private Scene scene = Scene.promise();
    private int columns;
    private float pitch;
    private float cell;
    /** Per column: which part of the music it shows (a band, or -1 for a dark gap), and how strong it may be. */
    private int[] columnBand = new int[0];
    private float[] columnStrength = new float[0];
    private boolean[] tail = new boolean[0];
    private float[] level = new float[0];

    public SoundWaveView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = context.getResources().getDisplayMetrics().density;
        glowPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD)); // light adds up
    }

    void setSource(SoundWave wave) {
        source = wave;
    }

    void setScene(Scene newScene) {
        scene = newScene;
        invalidate();
    }

    /** How loud the bass is right now, 0 to 1: the stage's washes swell with it. */
    float bass() {
        float sum = 0f;
        for (int c = 0; c < 8; c++) sum += shown[c];
        return sum / 8;
    }

    /** Starts or stops the animation (it only runs while the panel is on screen). */
    void setRunning(boolean run) {
        if (running == run) return;
        running = run;
        lastFrame = 0;
        if (run) postInvalidateOnAnimation();
    }

    /** How lit a part of the music is right now, 0 to 1 (for tests). */
    float level(int band) {
        return shown[band];
    }

    /** Sets a band's level straight away, without the rise and settle (for tests). */
    void showForTests(int band, float value) {
        shown[band] = value;
    }

    int rows() {
        return ROWS;
    }

    /** How many lamp columns fit (for tests). */
    int columns() {
        return columns;
    }

    /** Which part of the music a column shows, or -1 for a dark gap (for tests). */
    int bandOfColumn(int column) {
        return columnBand[column];
    }

    /** Whether a column is in the last strip, echoing the treble as it fades (for tests). */
    boolean tailColumn(int column) {
        return tail[column];
    }


    float pitch() {
        return pitch;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        pitch = h / (float) ROWS;
        cell = pitch * LAMP;
        columns = pitch > 0 ? (int) (w / pitch) : 0;
        columnBand = new int[columns];
        columnStrength = new float[columns];
        tail = new boolean[columns];
        level = new float[columns];
        // Lamp columns share the music out between them, bass first; the last strip, passing
        // behind the record, echoes the treble and fades.
        int lampColumns = 0;
        for (int c = 0; c < columns; c++) if (c % (STRIP + 1) != STRIP) lampColumns++;
        int music = Math.max(1, lampColumns - STRIP);
        int index = 0;
        float textRight = 440 * density;
        for (int c = 0; c < columns; c++) {
            if (c % (STRIP + 1) == STRIP) {
                columnBand[c] = -1;
                continue;
            }
            columnBand[c] = index < music ? Math.round(index * (BANDS - 1) / (float) Math.max(1, music - 1))
                    : BANDS - 1 - (index - music) * 2;
            tail[c] = index >= music;
            float fade = index < music ? 1f : 0.55f - 0.2f * (index - music);
            float x = (c + 0.5f) * pitch;
            float t = Math.max(0f, Math.min(1f, (x - (textRight - 20 * density)) / (100 * density)));
            columnStrength[c] = fade * (0.7f + 0.3f * t * t * (3 - 2 * t));
            index++;
        }
        int spread = Math.round(40 * density);
        softBounds.set(-spread, -spread, w + spread, h + Math.round(140 * density));
        if (soft != null) soft.recycle();
        soft = null;
        if (w > 0 && h > 0) {
            soft = Bitmap.createBitmap(Math.max(1, Math.round(softBounds.width() * SOFT)),
                    Math.max(1, Math.round(softBounds.height() * SOFT)), Bitmap.Config.ARGB_8888);
            softCanvas = new Canvas(soft);
            softCanvas.scale(SOFT, SOFT);
            softCanvas.translate(spread, spread);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        long now = AnimationUtils.currentAnimationTimeMillis();
        float dt = lastFrame == 0 ? 0f : Math.min(0.05f, (now - lastFrame) / 1000f);
        lastFrame = now;
        time += dt;
        if (source != null) source.levels(time, target);
        // Rise quickly but eased; settle back over about a second.
        float rise = Math.min(1f, dt * 18);
        float settle = (float) Math.pow(0.06, dt);
        for (int b = 0; b < BANDS; b++) {
            float left = target[Math.max(0, b - 1)], right = target[Math.min(BANDS - 1, b + 1)];
            float goal = 0.2f * left + 0.6f * target[b] + 0.2f * right;
            shown[b] = goal > shown[b] ? shown[b] + (goal - shown[b]) * rise : goal + (shown[b] - goal) * settle;
        }
        if (soft == null) return;
        for (int c = 0; c < columns; c++) level[c] = columnBand[c] < 0 ? 0f : shown[columnBand[c]];

        soft.eraseColor(0);
        float gap = (pitch - cell) / 2, h = getHeight();
        // Beams down from each strip into the haze, as loud as it is.
        for (int c = 0; c + STRIP <= columns; c += STRIP + 1) {
            float loud = 0f;
            for (int k = 0; k < STRIP; k++) loud += level[c + k] * columnStrength[c + k] / STRIP;
            if (loud < 0.12f) continue;
            float x = (c + STRIP / 2f) * pitch;
            drawBeam(x, h - gap, STRIP * pitch * 2.4f, 120 * density, 0.35f * loud);
        }
        // Glows, then the lamps.
        for (int pass = 0; pass < 2; pass++) {
            if (pass == 1) canvas.drawBitmap(soft, null, softBounds, softPaint);
            int ember = Scene.mix(scene.key, 0xFF000000, 0.35f);
            for (int c = 0; c < columns; c++) {
                if (columnBand[c] < 0) continue;
                float lit = level[c] * ROWS;
                int full = (int) lit;
                int top = full < ROWS && lit - full >= 0.03f ? full : full - 1;
                for (int r = 0; r < ROWS; r++) {
                    float strength = r < full ? 1f : r == full ? lit - full : 0f;
                    int fromTop = top - r;
                    if (strength > 0f && fromTop >= 0 && fromTop < TIP.length) strength *= TIP[fromTop];
                    float v = strength * columnStrength[c];
                    float x = c * pitch + gap, y = h - (r + 1) * pitch + gap;
                    if (pass == 0) {
                        if (v < 0.08f) continue;
                        glowPaint.setColor(Scene.alpha(scene.key, v * 0.45f));
                        rect.set(x - 1.6f * cell, y - 1.6f * cell, x + 2.6f * cell, y + 2.6f * cell);
                        softCanvas.drawBitmap(sprite, null, rect, glowPaint);
                        continue;
                    }
                    float f = r / (float) (ROWS - 1);
                    int colour = v < 0.02f ? ember
                            : Scene.mix(scene.key, scene.led, 0.45f * v * v + 0.2f * (1 - f) * v);
                    lamp.setColor(Scene.alpha(colour, v < 0.02f ? 0.06f : 0.06f + 0.84f * v * (1 - 0.25f * f)));
                    canvas.drawRect(x, y, x + cell, y + cell, lamp);
                }
            }
        }
        if (running) postInvalidateOnAnimation();
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
}
