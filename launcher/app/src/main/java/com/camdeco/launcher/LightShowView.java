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
 * The hero for an app: a wall of square lamps in its colour, right across the screen from margin
 * to margin, as tall as an album cover, played like the LED panels on Justice's stage. Behind the
 * app's name the lamps are dimmer, so the text stays easy to read. The lamps stand in strips three wide,
 * split into an upper and a lower panel, and each panel runs its own pattern for a couple of
 * seconds before switching: filling down or up, a band running down it, a flickering pixel pattern,
 * a flash that fades, all on at once, or dark. Dark lamps keep a faint ember, so the panels still
 * read. Each lit lamp is sharp, white-hot at full strength, with a soft round glow, and lit panels
 * throw soft beams down into the haze. One colour only: the app's own.
 */
public class LightShowView extends View {
    private static final int ROWS = 10;
    /** Strips are this many lamps wide, with a dark column between them. */
    private static final int STRIP = 3;
    private static final int PANEL_ROWS = ROWS / 2;
    /** Each lamp's size, as a share of the pitch. */
    private static final float LAMP = 0.7f;
    /** How long a panel keeps one pattern, in seconds, and how long it takes to switch. */
    private static final float PATTERN_SECONDS = 2.2f, SWITCH_SECONDS = 0.12f;
    private static final int OFF = 0, FILL_DOWN = 1, FILL_UP = 2, CHASE = 3, PIXELS = 4, FLASH = 5, FULL = 6;
    /** How often each pattern comes up, out of 20. */
    private static final int[] PATTERN_WEIGHTS = {4, 3, 2, 3, 4, 2, 2};
    private static final float SOFT = 1 / 8f;
    private static final long FRAME_MS = 33;

    private final float density;
    private final Paint lamp = new Paint();
    private final Paint glowPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint softPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Bitmap sprite = Glow.sprite();
    private final Bitmap beam = beamSprite();
    private final RectF rect = new RectF();
    private final Rect softBounds = new Rect();
    /** This frame's strength for each lamp, and each panel's average. */
    private float[] values = new float[0];
    private float[] panelLight = new float[0];
    /** Per column: how strong its lamps may be (dimmer behind the text). */
    private float[] columnStrength = new float[0];
    /** As many columns as fit between the margins, and the strips of three they make. */
    private int columns, strips;
    private Bitmap soft;
    private Canvas softCanvas;
    private Scene scene = Scene.promise();
    private boolean running;
    private long lastFrame;
    private float time;
    /** The wall's top-left lamp space, and the pitch and size of the lamps. */
    private float left, top, pitch, cell;

    public LightShowView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = context.getResources().getDisplayMetrics().density;
        glowPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD)); // light adds up
    }

    void setScene(Scene newScene) {
        scene = newScene;
        invalidate();
    }

    /** The colour the show is lit in (for tests). */
    int keyColour() {
        return scene.key;
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
        time += seconds;
    }

    /** How lit a lamp is right now, 0 to 1, after the last frame drawn (for tests). */
    float lampForTests(int column, int row) {
        return values[row * columns + column];
    }

    /** How many columns the wall has (for tests). */
    int columnsForTests() {
        return columns;
    }

    /** Whether a column is a dark gap between strips: the first of every four. */
    private static boolean gap(int column) {
        return column % (STRIP + 1) == 0;
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
        panelLight = new float[strips * 2];
        // Behind the text (the left 440dp) the lamps are dimmer, brightening just past it.
        float textRight = margin + 440 * density;
        columnStrength = new float[columns];
        for (int c = 0; c < columns; c++) {
            float x = left + (c + 0.5f) * pitch;
            float t = Math.max(0f, Math.min(1f, (x - (textRight - 20 * density)) / (100 * density)));
            columnStrength[c] = 0.45f + 0.55f * t * t * (3 - 2 * t);
        }
        // The glow spreads around the wall, and the beams fall well below it, behind the rows.
        softBounds.set(Math.round(left - 64 * density), Math.round(top - 40 * density),
                Math.round(left + columns * pitch + 40 * density), Math.round(top + height + 150 * density));
        if (soft != null) soft.recycle();
        soft = null;
        if (softBounds.width() > 0 && softBounds.height() > 0) {
            soft = Bitmap.createBitmap(Math.max(1, Math.round(softBounds.width() * SOFT)),
                    Math.max(1, Math.round(softBounds.height() * SOFT)), Bitmap.Config.ARGB_8888);
            softCanvas = new Canvas(soft);
            softCanvas.scale(SOFT, SOFT);
            softCanvas.translate(-softBounds.left, -softBounds.top);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (soft == null) return;
        long now = AnimationUtils.currentAnimationTimeMillis();
        float dt = lastFrame == 0 ? 0f : Math.min(0.1f, (now - lastFrame) / 1000f);
        lastFrame = now;
        time += dt;
        light();

        soft.eraseColor(0);
        float gap = (pitch - cell) / 2;
        // Beams from the lit panels, falling down and a little to the left into the haze.
        for (int s = 0; s < strips; s++) {
            for (int p = 0; p < 2; p++) {
                float b = panelLight[s * 2 + p];
                if (b < 0.15f) continue;
                int first = s * (STRIP + 1) + 1;
                int width = Math.min(STRIP, columns - first);
                if (width <= 0) continue;
                float x = left + (first + width / 2f) * pitch;
                float y = top + (p + 1) * PANEL_ROWS * pitch - gap;
                drawBeam(x, y, width * pitch * 2.4f, 140 * density, 0.4f * b);
            }
        }
        // A soft round glow around every lit lamp.
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < columns; c++) {
                float v = values[r * columns + c];
                if (v < 0.08f) continue;
                float x = left + c * pitch + gap, y = top + r * pitch + gap;
                glowPaint.setColor(Scene.alpha(scene.key, v * 0.5f));
                rect.set(x - 1.6f * cell, y - 1.6f * cell, x + 2.6f * cell, y + 2.6f * cell);
                softCanvas.drawBitmap(sprite, null, rect, glowPaint);
            }
        }
        canvas.drawBitmap(soft, null, softBounds, softPaint);

        // The lamps: a faint ember when dark, white-hot at full strength.
        int ember = Scene.mix(scene.key, 0xFF000000, 0.35f);
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < columns; c++) {
                if (gap(c)) continue; // the dark column between strips
                float v = values[r * columns + c];
                int colour = v < 0.02f ? ember : Scene.mix(scene.key, scene.led, 0.5f * v * v);
                lamp.setColor(Scene.alpha(colour, 0.06f + 0.84f * v));
                float x = left + c * pitch + gap, y = top + r * pitch + gap;
                canvas.drawRect(x, y, x + cell, y + cell, lamp);
            }
        }
        if (running) postInvalidateDelayed(FRAME_MS);
    }

    /** Works out every lamp's strength for now, panel by panel, easing from one pattern to the next. */
    private void light() {
        for (int s = 0; s < strips; s++) {
            for (int p = 0; p < 2; p++) {
                int panel = s * 2 + p;
                float clock = time / PATTERN_SECONDS + seed(panel * 7 + 3) * 3;
                int turn = (int) Math.floor(clock);
                float local = (clock - turn) * PATTERN_SECONDS;
                int now = pattern(panel, turn), before = pattern(panel, turn - 1);
                float blend = Math.min(1f, local / SWITCH_SECONDS);
                float sum = 0f;
                int count = 0;
                for (int r = 0; r < PANEL_ROWS; r++) {
                    for (int k = 0; k < STRIP; k++) {
                        int c = s * (STRIP + 1) + 1 + k;
                        if (c >= columns) continue;
                        float v = lamp(now, local, r, k, panel, turn);
                        if (blend < 1f) {
                            float old = lamp(before, local + PATTERN_SECONDS, r, k, panel, turn - 1);
                            v = old + (v - old) * blend;
                        }
                        v *= columnStrength[c];
                        values[(p * PANEL_ROWS + r) * columns + c] = v;
                        sum += v;
                        count++;
                    }
                }
                panelLight[panel] = count == 0 ? 0f : sum / count;
            }
        }
    }

    /** Which pattern a panel plays on a given turn: picked by chance, the same every time. */
    private static int pattern(int panel, int turn) {
        float pick = seed(panel * 131 + turn * 17) * 20;
        for (int i = 0; i < PATTERN_WEIGHTS.length; i++) {
            pick -= PATTERN_WEIGHTS[i];
            if (pick < 0) return i;
        }
        return OFF;
    }

    /** One lamp's strength in a pattern, {@code local} seconds into it; row and column within the panel. */
    private float lamp(int pattern, float local, int row, int column, int panel, int turn) {
        float progress = Math.min(1f, local / (PATTERN_SECONDS * 0.6f));
        float eased = progress * progress * (3 - 2 * progress);
        switch (pattern) {
            case FILL_DOWN:
                return row < eased * (PANEL_ROWS + 0.99f) ? flicker(row, column, panel) : 0f;
            case FILL_UP:
                return PANEL_ROWS - 1 - row < eased * (PANEL_ROWS + 0.99f) ? flicker(row, column, panel) : 0f;
            case CHASE: {
                float head = (local * 4f) % (PANEL_ROWS + 2) - 1;
                float d = Math.abs(row - head);
                return d < 1.5f ? 1f - d / 1.5f : 0f;
            }
            case PIXELS: {
                int frame = (int) (local / 0.18f);
                return seed(panel * 59 + turn * 211 + frame * 31 + row * 7 + column) > 0.5f ? 0.9f : 0f;
            }
            case FLASH:
                return (float) Math.exp(-local * 2.5f);
            case FULL:
                return flicker(row, column, panel);
            default:
                return 0f;
        }
    }

    /** Lit lamps shimmer a little, each in its own time. */
    private float flicker(int row, int column, int panel) {
        float s = seed(panel * 37 + row * 5 + column);
        return 0.82f + 0.18f * (float) Math.sin(time * (9 + s * 6) + s * 30);
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

    /** A fixed pseudo-random number, so the patterns never repeat in step. */
    private static float seed(int i) {
        double s = Math.sin(i * 127.1 + 311.7) * 43758.5453;
        return (float) (s - Math.floor(s));
    }
}
