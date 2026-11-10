package com.camdeco.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.AnimationUtils;

/**
 * The music's waveform under the song in the hero: one smooth line in the album cover's colours
 * that ripples with the bass and treble and goes flat when the music stops. A fainter echo line
 * trails it, and a wide soft stroke gives it a glow.
 */
public class SoundWaveView extends View {
    /** Each band's ripple: how far it moves the line (dp at full level), how many waves fit, how fast they travel. */
    private static final float[] REACH = {11, 8, 6, 4, 3, 2};
    private static final float[] WAVES = {1, 2.1f, 3.7f, 5.9f, 8.8f, 12.7f};
    private static final float[] SPEED = {2.1f, -3.3f, 4.7f, -6.1f, 8.3f, -11f};

    private final float density;
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint echo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path main = new Path();
    private final Path trail = new Path();
    private final float[] target = new float[SoundWave.BANDS];
    private final float[] shown = new float[SoundWave.BANDS];
    private SoundWave source;
    private boolean running;
    private long lastFrame;
    private double time;
    private int light = AppColors.WORLDLY_AMBER;
    private int vivid = AppColors.WORLDLY_AMBER;
    private int deep = AppColors.WORLDLY_SLATE;

    public SoundWaveView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = context.getResources().getDisplayMetrics().density;
        for (Paint p : new Paint[]{line, glow, echo}) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
        }
        line.setStrokeWidth(3 * density);
        glow.setStrokeWidth(9 * density);
        glow.setAlpha(60);
        echo.setStrokeWidth(1.4f * density);
        echo.setAlpha(90);
    }

    void setSource(SoundWave wave) {
        source = wave;
    }

    /** The line's colours, from the album cover: light at the start, the cover's colour in the middle. */
    void setColours(AppColors colours) {
        light = mix(colours.vivid, Color.WHITE, 0.45f);
        vivid = colours.vivid;
        deep = mix(colours.vivid, colours.deep, 0.45f);
        updateShaders();
        invalidate();
    }

    /** Starts or stops the animation (it only runs while the line is on screen). */
    void setRunning(boolean run) {
        if (running == run) return;
        running = run;
        lastFrame = 0;
        if (run) postInvalidateOnAnimation();
    }

    /** How far the line moves right now, 0 when it's flat (for tests). */
    float amplitude() {
        float max = 0;
        for (float v : shown) max = Math.max(max, v);
        return max;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        updateShaders();
    }

    private void updateShaders() {
        int w = getWidth();
        if (w == 0) return;
        int[] colours = {Color.TRANSPARENT, light, vivid, deep, Color.TRANSPARENT};
        float[] stops = {0f, 0.15f, 0.55f, 0.9f, 1f};
        line.setShader(new LinearGradient(0, 0, w, 0, colours, stops, Shader.TileMode.CLAMP));
        glow.setShader(new LinearGradient(0, 0, w, 0, colours, stops, Shader.TileMode.CLAMP));
        echo.setShader(new LinearGradient(0, 0, w, 0, colours, stops, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        long now = AnimationUtils.currentAnimationTimeMillis();
        float dt = lastFrame == 0 ? 0f : Math.min(0.05f, (now - lastFrame) / 1000f);
        lastFrame = now;
        time += dt;
        if (source != null) source.levels(time, target);
        // Like a real visualizer: jump up quickly, fall back slowly.
        float release = (float) Math.pow(0.0009, dt);
        for (int b = 0; b < shown.length; b++) {
            float goal = target[b];
            shown[b] = goal > shown[b] ? shown[b] + (goal - shown[b]) * Math.min(1f, dt * 30)
                    : goal + (shown[b] - goal) * release;
        }

        float w = getWidth();
        float cy = getHeight() / 2f;
        float limit = cy - 2 * density;
        trace(main, w, cy, limit, 0, 1.15f);
        trace(trail, w, cy, limit, 1.7f, 0.7f);
        canvas.drawPath(trail, echo);
        canvas.drawPath(main, glow);
        canvas.drawPath(main, line);
        if (running) postInvalidateOnAnimation();
    }

    /** The line across the view: the six ripples added up, tapering to nothing at both ends. */
    private void trace(Path path, float w, float cy, float limit, float phase, float scale) {
        path.rewind();
        float step = 4 * density;
        for (float x = 0; x <= w; x += step) {
            double u = x / w;
            double y = 0;
            for (int b = 0; b < shown.length; b++) {
                double s = Math.sin(u * WAVES[b] * Math.PI * 2 + time * SPEED[b] + (b < 2 ? phase : 0));
                y += shown[b] * REACH[b] * density * s;
            }
            float py = (float) Math.max(-limit, Math.min(limit, y * Math.sin(Math.PI * u) * scale));
            if (x == 0) path.moveTo(x, cy + py);
            else path.lineTo(x, cy + py);
        }
    }

    private static int mix(int from, int to, float f) {
        int r = Math.round(Color.red(from) + (Color.red(to) - Color.red(from)) * f);
        int g = Math.round(Color.green(from) + (Color.green(to) - Color.green(from)) * f);
        int b = Math.round(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * f);
        return Color.rgb(r, g, b);
    }
}
