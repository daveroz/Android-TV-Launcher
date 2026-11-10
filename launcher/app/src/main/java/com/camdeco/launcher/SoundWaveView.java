package com.camdeco.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.AnimationUtils;

/**
 * The music under the song in the hero, as a grid of light cells like a hi-fi spectrum display:
 * one column per part of the music, bass on the left and treble on the right, spanning the width
 * under the song and rising behind its text. The cells are see-through, so the cover's glow shows
 * through them, and fade more toward the top so the song's text stays easy to read.
 *
 * Each column rises with its part of the music and settles back gently; the top cell of a column
 * fades in and out, so heights change smoothly rather than a whole cell at a time. Cells take the
 * album cover's colours, whiter at the bottom, and the grid goes dark when the music stops.
 */
public class SoundWaveView extends View {
    private static final int COLUMNS = SoundWave.COLUMNS;
    /** Gap between cells, as a share of the cell pitch. */
    private static final float GAP = 0.16f;
    /** How see-through the lit cells are: opacity at the bottom row and at the top row. */
    private static final float BOTTOM_OPACITY = 0.62f, TOP_OPACITY = 0.3f;

    private final float density;
    private final Paint cellPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float[] target = new float[COLUMNS];
    private final float[] shown = new float[COLUMNS];
    private SoundWave source;
    private boolean running;
    private long lastFrame;
    private double time;
    private int topColour;
    private int bottomColour;
    /** Per row, bottom first: the lit colour and its opacity. Sized when the view gets its size. */
    private int[] rowColours = new int[0];
    private float[] rowOpacity = new float[0];
    /** The faint grid of unlit cells, drawn once per size. */
    private Bitmap grid;
    private int rows;
    private float pitch;
    private float cell;
    private float radius;

    public SoundWaveView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = context.getResources().getDisplayMetrics().density;
        setColours(AppColors.worldly());
    }

    void setSource(SoundWave wave) {
        source = wave;
    }

    /** The cells' colours, from the album cover: nearly white at the bottom, the cover's colour on top. */
    void setColours(AppColors cover) {
        topColour = atLeastBrightness(cover.vivid, 0.85f);
        bottomColour = mix(topColour, Color.WHITE, 0.6f);
        updateRows();
        invalidate();
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
        pitch = w / (COLUMNS - GAP); // COLUMNS cells and COLUMNS - 1 gaps
        cell = pitch * (1 - GAP);
        radius = cell * 0.22f;
        rows = pitch > 0 ? Math.max(1, (int) ((h + pitch * GAP) / pitch)) : 0;
        rowColours = new int[rows];
        rowOpacity = new float[rows];
        updateRows();
        buildGrid(w, h);
    }

    private void updateRows() {
        for (int r = 0; r < rows; r++) {
            float f = rows > 1 ? r / (float) (rows - 1) : 0f;
            rowColours[r] = mix(bottomColour, topColour, f);
            rowOpacity[r] = BOTTOM_OPACITY + (TOP_OPACITY - BOTTOM_OPACITY) * f;
        }
    }

    private void buildGrid(int w, int h) {
        if (grid != null) grid.recycle();
        grid = null;
        if (w <= 0 || h <= 0 || rows == 0) return;
        grid = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(grid);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0x09FFFFFF); // unlit cells: just a hint of the grid
        for (int col = 0; col < COLUMNS; col++) {
            for (int row = 0; row < rows; row++) {
                cellRect(col, row, 0);
                c.drawRoundRect(rect, radius, radius, p);
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

        if (grid != null) canvas.drawBitmap(grid, 0, 0, null);
        float glow = 2 * density;
        for (int c = 0; c < COLUMNS; c++) {
            float lit = shown[c] * rows;
            int full = (int) lit;
            for (int r = 0; r < rows && r <= full; r++) {
                float strength = r < full ? 1f : lit - full; // the top cell fades in and out
                if (strength < 0.03f) break;
                float opacity = rowOpacity[r] * strength;
                int colour = rowColours[r];
                cellRect(c, r, glow);
                glowPaint.setColor(colour);
                glowPaint.setAlpha(Math.round(70 * opacity));
                canvas.drawRoundRect(rect, radius + glow, radius + glow, glowPaint);
                cellRect(c, r, 0);
                cellPaint.setColor(colour);
                cellPaint.setAlpha(Math.round(255 * opacity));
                canvas.drawRoundRect(rect, radius, radius, cellPaint);
            }
        }
        if (running) postInvalidateOnAnimation();
    }

    private static int mix(int from, int to, float f) {
        int r = Math.round(Color.red(from) + (Color.red(to) - Color.red(from)) * f);
        int g = Math.round(Color.green(from) + (Color.green(to) - Color.green(from)) * f);
        int b = Math.round(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * f);
        return Color.rgb(r, g, b);
    }

    /** Lifts a colour's brightness to at least {@code min}, so dark cover tones still glow. */
    private static int atLeastBrightness(int colour, float min) {
        float[] hsv = new float[3];
        Color.colorToHSV(colour, hsv);
        hsv[2] = Math.max(hsv[2], min);
        return Color.HSVToColor(hsv);
    }
}
