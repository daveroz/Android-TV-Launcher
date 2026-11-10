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
 * The music under the song in the hero, as a small light panel like a hi-fi spectrum display:
 * columns of glowing square cells on dark glass, bass on the left and treble on the right. Each
 * column rises with its part of the music and settles back gently; the top cell of a column fades
 * in and out, so heights change smoothly rather than a whole cell at a time. Cells take the album
 * cover's colours, whiter at the bottom, and the panel goes dark when the music stops.
 */
public class SoundWaveView extends View {
    static final int ROWS = 7;
    private static final int COLUMNS = SoundWave.COLUMNS;
    /** Gap between cells, as a share of the cell pitch. */
    private static final float GAP = 0.16f;

    private final float density;
    private final Paint cellPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final int[] rowColours = new int[ROWS];
    private final float[] target = new float[COLUMNS];
    private final float[] shown = new float[COLUMNS];
    private SoundWave source;
    private boolean running;
    private long lastFrame;
    private double time;
    /** The glass panel with its unlit cells, drawn once per size. */
    private Bitmap panel;
    private float pad;
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
        int top = atLeastBrightness(cover.vivid, 0.85f);
        int bottom = mix(top, Color.WHITE, 0.6f);
        for (int r = 0; r < ROWS; r++) rowColours[r] = mix(bottom, top, r / (float) (ROWS - 1));
        invalidate();
    }

    /** Starts or stops the animation (it only runs while the panel is on screen). */
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

    /** The panel's width for this view's height: every column gets a square cell. */
    float panelWidth() {
        return COLUMNS * pitch + 2 * pad - (pitch - cell);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        pad = 5 * density;
        pitch = (h - 2 * pad) / (ROWS - GAP); // ROWS cells and ROWS - 1 gaps fill the height
        cell = pitch * (1 - GAP);
        radius = cell * 0.22f;
        buildPanel(w, h);
    }

    private void buildPanel(int w, int h) {
        if (panel != null) panel.recycle();
        panel = null;
        int pw = Math.min(w, Math.round(panelWidth()));
        if (pw <= 0 || h <= 0) return;
        panel = Bitmap.createBitmap(pw, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(panel);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        // Dark glass with a faint edge.
        rect.set(0.5f * density, 0.5f * density, pw - 0.5f * density, h - 0.5f * density);
        p.setColor(0xB805080F);
        c.drawRoundRect(rect, 10 * density, 10 * density, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(density);
        p.setColor(0x14FFFFFF);
        c.drawRoundRect(rect, 10 * density, 10 * density, p);
        // Unlit cells, just visible, like the dark LEDs of a real panel.
        p.setStyle(Paint.Style.FILL);
        p.setColor(0x0FFFFFFF);
        for (int col = 0; col < COLUMNS; col++) {
            for (int row = 0; row < ROWS; row++) {
                cellRect(col, row, 0);
                c.drawRoundRect(rect, radius, radius, p);
            }
        }
    }

    /** The cell at this column and row (row 0 at the bottom), grown by {@code grow} px on each side. */
    private void cellRect(int col, int row, float grow) {
        float x = pad + col * pitch;
        float y = getHeight() - pad - cell - row * pitch;
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

        if (panel != null) canvas.drawBitmap(panel, 0, 0, null);
        float glow = 2 * density;
        for (int c = 0; c < COLUMNS; c++) {
            float lit = shown[c] * ROWS;
            int full = (int) lit;
            for (int r = 0; r < ROWS && r <= full; r++) {
                float strength = r < full ? 1f : lit - full; // the top cell fades in and out
                if (strength < 0.03f) break;
                int colour = rowColours[r];
                cellRect(c, r, glow);
                glowPaint.setColor(colour);
                glowPaint.setAlpha(Math.round(60 * strength));
                canvas.drawRoundRect(rect, radius + glow, radius + glow, glowPaint);
                cellRect(c, r, 0);
                cellPaint.setColor(colour);
                cellPaint.setAlpha(Math.round(255 * strength));
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
