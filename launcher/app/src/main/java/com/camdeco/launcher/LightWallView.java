package com.camdeco.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.AnimationUtils;

/**
 * The hero for an app with nothing else to show: a wall of square lamps in the app's own light,
 * level with its banner and behind it, like the visualizer is for music. A slow sweep in the key
 * colour crosses a slower wave in the fill colour, and now and then a lamp flares white. Only lit
 * lamps show, so there is never a grid of dark ones, and the wall fades in from the left, so the
 * screen stays clear where text would go.
 */
public class LightWallView extends View {
    private static final int ROWS = 14;
    /** Gap between lamps, as a share of the pitch. */
    private static final float GAP = 0.22f;
    private static final long FRAME_MS = 33;

    private final Paint paint = new Paint();
    private Scene scene = Scene.promise();
    private boolean running;
    private long lastFrame;
    private float time;
    private float pitch;
    private float cell;
    private int columns;
    private float[] seeds = new float[0];

    public LightWallView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    void setScene(Scene newScene) {
        scene = newScene;
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

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        pitch = h / (ROWS - GAP);
        cell = pitch * (1 - GAP);
        columns = pitch > 0 ? (int) ((w + pitch * GAP) / pitch) : 0;
        seeds = new float[columns * ROWS];
        for (int i = 0; i < seeds.length; i++) {
            double s = Math.sin(i * 127.1 + 311.7) * 43758.5453;
            seeds[i] = (float) (s - Math.floor(s));
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        long now = AnimationUtils.currentAnimationTimeMillis();
        float dt = lastFrame == 0 ? 0f : Math.min(0.1f, (now - lastFrame) / 1000f);
        lastFrame = now;
        time += dt;
        float w = getWidth();
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < columns; c++) {
                float x = c * pitch;
                float edge = smooth(0.3f, 0.62f, x / w);
                if (edge <= 0f) continue;
                float key = (float) Math.pow(Math.max(0, Math.sin(c * 0.2 - r * 0.12 - time * 0.8)), 10) * 0.8f;
                float fill = (float) Math.pow(Math.max(0, Math.sin(r * 0.3 + c * 0.05 + time * 0.4)), 14) * 0.55f;
                float seed = seeds[r * columns + c];
                float flare = (float) Math.pow(Math.max(0, Math.sin(time * (0.6 + seed * 0.9) + seed * 40)), 40) * 0.7f;
                float v = Math.min(1f, (Math.max(key, fill) + flare) * edge);
                if (v < 0.14f) continue;
                int tone = key >= fill ? scene.key : scene.fill;
                int colour = Scene.mix(tone, scene.led, 0.3f * flare + 0.15f * v * v);
                paint.setColor(Scene.alpha(colour, 0.7f * v));
                float y = r * pitch;
                canvas.drawRect(x, y, x + cell, y + cell, paint);
            }
        }
        if (running) postInvalidateDelayed(FRAME_MS);
    }

    private static float smooth(float from, float to, float x) {
        float t = Math.max(0f, Math.min(1f, (x - from) / (to - from)));
        return t * t * (3 - 2 * t);
    }
}
