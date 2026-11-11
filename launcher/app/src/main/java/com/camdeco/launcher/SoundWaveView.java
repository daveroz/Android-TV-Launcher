package com.camdeco.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.AnimationUtils;

/**
 * The music under the song in the hero, as light cells like a hi-fi spectrum display: one column
 * per part of the music, bass on the left and treble on the right, level with the album cover
 * (same top and bottom) and behind the song's text, trailing off as it reaches the cover. Only
 * cells with sound light up, see-through so the cover's glow shows through, fading more toward the
 * top so the text stays easy to read, and each bar's top is a little softer still.
 *
 * Each column rises with its part of the music and settles back gently; the top cell of a column
 * fades in and out, so heights change smoothly rather than a whole cell at a time. The cells take
 * the scene's colours, from the album cover: bands of its key and fill colour drift slowly across
 * the panel, blending round the colour wheel between them, and every cell is a little whiter
 * toward the bottom. The cells have no glow of their own, so they never blow out over the haze,
 * and the grid goes dark when the music stops.
 */
public class SoundWaveView extends View {
    private static final int COLUMNS = SoundWave.COLUMNS;
    /**
     * Extra columns after the music's own, reaching to the cover: they echo the treble columns
     * and fade out as they get there, so the grid trails off into the cover (mostly behind the
     * record) without the treble itself fading.
     */
    private static final int TAIL = 8;
    private static final int ALL = COLUMNS + TAIL;
    /** The top cell of each bar, and the one under it, are a little more see-through. */
    private static final float[] TIP = {0.6f, 0.85f};
    /** Gap between cells, as a share of the cell pitch. */
    private static final float GAP = 0.16f;
    /** How see-through the lit cells are: opacity at the bottom row and at the top row. */
    private static final float BOTTOM_OPACITY = 0.62f, TOP_OPACITY = 0.3f;

    private final Paint cellPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float[] target = new float[COLUMNS];
    private final float[] shown = new float[COLUMNS];
    private SoundWave source;
    private boolean running;
    private long lastFrame;
    private double time;
    private Scene scene = Scene.promise();
    /** Steps from the fill to the key colour (33 of them), each per row (bottom first). */
    private static final int STEPS = 32;
    private int[] palette = new int[0];
    /** How long one band takes to drift across, in seconds, and how many bands fit across. */
    private static final float BAND_SECONDS = 9f, BANDS = 1.1f;
    private float[] rowOpacity = new float[0];
    /** Per column: 1, then fading to nothing across the tail. */
    private final float[] columnFade = new float[ALL];
    private int rows;
    private float pitch;
    private float cell;
    private float radius;

    public SoundWaveView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    void setSource(SoundWave wave) {
        source = wave;
    }

    /** The cells' colours: bands of the scene's key and fill colours. */
    void setScene(Scene newScene) {
        scene = newScene;
        updateRows();
        invalidate();
    }

    /** The colour of a lit cell right now (for tests). */
    int cellColour(int column, int row) {
        return palette[step(column) * rows + row];
    }

    /** Moves the bands on by this many seconds (for tests). */
    void advanceForTests(float seconds) {
        time += seconds;
    }

    /** Which step between fill and key this column wears now: bands drifting left to right. */
    private int step(int column) {
        double phase = 2 * Math.PI * (column / (double) ALL * BANDS - time / BAND_SECONDS);
        return (int) Math.round((0.5 + 0.5 * Math.sin(phase)) * STEPS);
    }

    /** How loud the bass is right now, 0 to 1: the stage's washes swell with it. */
    float bass() {
        float sum = 0f;
        for (int c = 0; c < 8; c++) sum += shown[c];
        return sum / 8;
    }

    /** Starts or stops the animation (it only runs while the grid is on screen). */
    void setRunning(boolean run) {
        if (running == run) return;
        running = run;
        lastFrame = 0;
        if (run) postInvalidateOnAnimation();
    }

    /** How lit a column is right now, 0 to 1 (for tests). */
    float level(int column) {
        return shown[column];
    }

    /** Sets a column's level straight away, without the rise and settle (for tests). */
    void showForTests(int column, float level) {
        shown[column] = level;
    }

    /** How many rows of cells fit (for tests). */
    int rows() {
        return rows;
    }

    /** The distance from one cell to the next, in pixels (for tests). */
    float pitch() {
        return pitch;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        // The columns fill the width; as many square rows as fit stack up from the bottom.
        pitch = w / (ALL - GAP); // ALL cells and ALL - 1 gaps fill the width
        for (int c = 0; c < ALL; c++) {
            float t = c < COLUMNS ? 0f : (c - COLUMNS + 1) / (float) (TAIL + 1);
            columnFade[c] = 1f - t * t * (3 - 2 * t);
        }
        cell = pitch * (1 - GAP);
        radius = cell * 0.08f;
        rows = pitch > 0 ? Math.max(1, (int) ((h + pitch * GAP) / pitch)) : 0;
        palette = new int[(STEPS + 1) * rows];
        rowOpacity = new float[rows];
        updateRows();
    }

    private void updateRows() {
        for (int r = 0; r < rows; r++) {
            float f = rows > 1 ? r / (float) (rows - 1) : 0f;
            rowOpacity[r] = BOTTOM_OPACITY + (TOP_OPACITY - BOTTOM_OPACITY) * f;
        }
        for (int s = 0; s <= STEPS; s++) {
            int hue = Scene.blend(scene.fill, scene.key, s / (float) STEPS);
            for (int r = 0; r < rows; r++) {
                float f = rows > 1 ? r / (float) (rows - 1) : 0f;
                palette[s * rows + r] = Scene.mix(hue, scene.led, 0.22f * (1 - f));
            }
        }
    }

    /** The cell at this column and row (row 0 at the bottom), grown by {@code grow} px on each side. */
    private void cellRect(int col, int row, float grow) {
        float x = col * pitch;
        float y = getHeight() - cell - row * pitch;
        rect.set(x - grow, y - grow, x + cell + grow, y + cell + grow);
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
        for (int c = 0; c < COLUMNS; c++) {
            // A touch of the neighbours keeps the outline readable without hiding detail.
            float left = target[Math.max(0, c - 1)], right = target[Math.min(COLUMNS - 1, c + 1)];
            float goal = 0.2f * left + 0.6f * target[c] + 0.2f * right;
            shown[c] = goal > shown[c] ? shown[c] + (goal - shown[c]) * rise : goal + (shown[c] - goal) * settle;
        }

        for (int c = 0; c < ALL; c++) {
            int colours = step(c) * rows;
            // The tail echoes the treble columns, last first.
            float level = c < COLUMNS ? shown[c] : shown[Math.max(0, COLUMNS - 2 - (c - COLUMNS))];
            float lit = level * rows;
            int full = (int) lit;
            int top = full < rows && lit - full >= 0.03f ? full : full - 1; // the bar's top cell
            for (int r = 0; r < rows && r <= full; r++) {
                float strength = r < full ? 1f : lit - full; // the top cell fades in and out
                if (strength < 0.03f) break;
                int fromTop = top - r;
                float tip = fromTop < TIP.length ? TIP[fromTop] : 1f;
                float opacity = rowOpacity[r] * strength * tip * columnFade[c];
                if (opacity < 0.01f) continue;
                cellRect(c, r, 0);
                cellPaint.setColor(palette[colours + r]);
                cellPaint.setAlpha(Math.round(255 * opacity));
                canvas.drawRoundRect(rect, radius, radius, cellPaint);
            }
        }
        if (running) postInvalidateOnAnimation();
    }
}
