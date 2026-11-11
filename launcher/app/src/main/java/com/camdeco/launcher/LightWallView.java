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
 * The hero for an app: a wall of square lamps in its own light, on the right where an album cover
 * would be. A slow sweep in the scene's key colour crosses a slower wave in its fill colour, and
 * now and then a lamp flares white. Each lit lamp is sharp, with a soft glow around it drawn small
 * and stretched, like light in fog; its bottom row shows faintly on the floor below. The wall fades
 * in from the left, so it never crowds the text.
 */
public class LightWallView extends View {
    private static final int ROWS = 10;
    private static final int COLUMNS = 22;
    /** Each lamp's size, as a share of the pitch. */
    private static final float LAMP = 0.7f;
    /** The glow layer is drawn at this fraction of the wall's size. */
    private static final float SOFT = 1 / 8f;
    private static final long FRAME_MS = 33;

    private final float density;
    private final Paint lamp = new Paint();
    private final Paint glowPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint softPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Rect softBounds = new Rect();
    private final RectF glowRect = new RectF();
    /** One round, soft glow, drawn in each lamp's colour (an alpha-only picture takes the paint's colour). */
    private final Bitmap glowSprite = sprite();
    private final float[] seeds = new float[ROWS * COLUMNS];
    /** This frame's strength, colour and flare for each lamp. */
    private final float[] values = new float[ROWS * COLUMNS];
    private final int[] hues = new int[ROWS * COLUMNS];
    private final float[] flares = new float[ROWS * COLUMNS];
    /** Steps between the fill and the key colour, worked out once per scene. */
    private final int[] palette = new int[33];
    private Bitmap soft;
    private Canvas softCanvas;
    private Scene scene = Scene.promise();
    private boolean running;
    private long lastFrame;
    private float time;
    /** Where the wall sits in the view, and the space its glow spreads into around it. */
    private float left, top, pitch, cell, margin;

    public LightWallView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = context.getResources().getDisplayMetrics().density;
        glowPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD)); // light adds up
        for (int i = 0; i < seeds.length; i++) {
            double s = Math.sin(i * 127.1 + 311.7) * 43758.5453;
            seeds[i] = (float) (s - Math.floor(s));
        }
        setScene(scene);
    }

    void setScene(Scene newScene) {
        scene = newScene;
        for (int i = 0; i < palette.length; i++) palette[i] = Scene.blend(newScene.fill, newScene.key, i / 32f);
        invalidate();
    }

    /** The key colour the wall glows with (for tests). */
    int keyColour() {
        return scene.key;
    }

    /** Animates while the wall is on screen and the launcher is in front. */
    void setRunning(boolean run) {
        if (running == run) return;
        running = run;
        lastFrame = 0;
        if (run) invalidate();
    }

    /** The wall's left, top, right and bottom in the view, around its lamps' spaces (for tests). */
    float[] wallBounds() {
        float gap = (pitch - cell) / 2;
        return new float[]{left - gap, top - gap, left - gap + COLUMNS * pitch, top - gap + ROWS * pitch};
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        // As tall as the cover would be, level with the text, its right edge at the screen margin.
        float height = getResources().getDimension(R.dimen.hero_card_height);
        pitch = height / ROWS;
        cell = pitch * LAMP;
        left = w - getResources().getDimension(R.dimen.overscan_h) - COLUMNS * pitch + (pitch - cell) / 2;
        top = getResources().getDimension(R.dimen.hero_top) + (pitch - cell) / 2;
        margin = 40 * density;
        softBounds.set(Math.round(left - margin), Math.round(top - margin),
                Math.round(left + COLUMNS * pitch + margin), Math.round(top + ROWS * pitch + margin));
        if (soft != null) soft.recycle();
        soft = Bitmap.createBitmap(Math.max(1, Math.round(softBounds.width() * SOFT)),
                Math.max(1, Math.round(softBounds.height() * SOFT)), Bitmap.Config.ARGB_8888);
        softCanvas = new Canvas(soft);
        softCanvas.scale(SOFT, SOFT);
        softCanvas.translate(-softBounds.left, -softBounds.top);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (soft == null) return;
        long now = AnimationUtils.currentAnimationTimeMillis();
        float dt = lastFrame == 0 ? 0f : Math.min(0.1f, (now - lastFrame) / 1000f);
        lastFrame = now;
        time += dt;

        soft.eraseColor(0);
        // The glows first, into the small layer, stretched behind the sharp lamps.
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLUMNS; c++) {
                int i = r * COLUMNS + c;
                float edge = Math.min(1f, c / 6f) * Math.min(1f, (COLUMNS - 1 - c) / 1.5f + 0.4f);
                float key = (float) Math.pow(Math.max(0, Math.sin(c * 0.34 - r * 0.22 - time * 0.9)), 6) * 0.8f * edge;
                float fill = (float) Math.pow(Math.max(0, Math.sin(r * 0.5 + c * 0.1 + time * 0.45)), 8) * 0.6f * edge;
                float seed = seeds[i];
                float flare = (float) Math.pow(Math.max(0, Math.sin(time * (0.6 + seed * 0.9) + seed * 40)), 24) * 0.7f * edge;
                float v = Math.min(1f, Math.max(key, fill) + flare);
                values[i] = v;
                if (v <= 0.06f) continue;
                float share = key + fill > 0 ? key / (key + fill) : 0.5f;
                hues[i] = palette[Math.round(share * 32)];
                flares[i] = flare;
                float x = left + c * pitch, y = top + r * pitch;
                glowPaint.setColor(Scene.alpha(hues[i], v * 0.6f));
                glowRect.set(x - 1.6f * cell, y - 1.6f * cell, x + 2.6f * cell, y + 2.6f * cell);
                softCanvas.drawBitmap(glowSprite, null, glowRect, glowPaint);
            }
        }
        canvas.drawBitmap(soft, null, softBounds, softPaint);
        float bottom = top + (ROWS - 1) * pitch + cell;
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLUMNS; c++) {
                int i = r * COLUMNS + c;
                float v = values[i];
                if (v <= 0.06f) continue;
                float x = left + c * pitch, y = top + r * pitch;
                int colour = Scene.mix(hues[i], scene.led, 0.25f * flares[i] + 0.15f * v * v);
                lamp.setColor(Scene.alpha(colour, 0.18f + 0.52f * v));
                canvas.drawRect(x, y, x + cell, y + cell, lamp);
                if (r == ROWS - 1) { // the bottom row, faintly on the floor
                    lamp.setColor(Scene.alpha(colour, 0.1f * v));
                    canvas.drawRect(x, bottom + 3 * density, x + cell, bottom + 3 * density + cell * 0.6f, lamp);
                }
            }
        }
        if (running) postInvalidateDelayed(FRAME_MS);
    }

    /** A soft round spot: full in the middle, fading to nothing at the edge. */
    private static Bitmap sprite() {
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
        return bitmap;
    }
}
