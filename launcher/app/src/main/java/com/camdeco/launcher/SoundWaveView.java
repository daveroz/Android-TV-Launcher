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
 * The music under the song in the hero, as three strands in the album cover's colours: a thick,
 * slow one for the bass, a medium one for voices and a thin, fast one for the treble. Each swells
 * with its own range, drum hits and syllables give a short extra push, and all three go flat when
 * the music stops.
 */
public class SoundWaveView extends View {
    // Per strand (bass, voice, treble): how far it moves at full level (dp), how many waves fit
    // across, how fast they travel, where they start, and how thick the line and its glow are (dp).
    private static final float[] REACH = {22, 14, 7};
    private static final float[] WAVES = {1.6f, 5f, 17f};
    private static final float[] SPEED = {2.0f, -5.2f, 12f};
    private static final float[] PHASE = {0f, 1.2f, 2.1f};
    private static final float[] WIDTH = {4.5f, 2.6f, 1.4f};
    private static final float[] GLOW = {12f, 8f, 5f};
    /** The line fades in and out over this much of each end. */
    private static final float EDGE = 0.07f;

    private final float density;
    private final Paint[] line = new Paint[SoundWave.BANDS];
    private final Paint[] glow = new Paint[SoundWave.BANDS];
    private final Path[] paths = new Path[SoundWave.BANDS];
    private final int[] colours = new int[SoundWave.BANDS];
    private final float[] target = new float[SoundWave.BANDS];
    private final float[] average = new float[SoundWave.BANDS];
    private final float[] shown = new float[SoundWave.BANDS];
    private SoundWave source;
    private boolean running;
    private long lastFrame;
    private double time;

    public SoundWaveView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = context.getResources().getDisplayMetrics().density;
        for (int s = 0; s < SoundWave.BANDS; s++) {
            line[s] = strokePaint(WIDTH[s], 255);
            glow[s] = strokePaint(GLOW[s], 56);
            paths[s] = new Path();
        }
        setColours(AppColors.worldly());
    }

    private Paint strokePaint(float widthDp, int alpha) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setStrokeWidth(widthDp * density);
        p.setAlpha(alpha);
        return p;
    }

    void setSource(SoundWave wave) {
        source = wave;
    }

    /** The strands' colours, from the album cover: darker for the bass, light for the treble. */
    void setColours(AppColors cover) {
        colours[SoundWave.BASS] = atLeastBrightness(mix(cover.vivid, cover.deep, 0.5f), 0.5f);
        colours[SoundWave.VOICE] = cover.vivid;
        colours[SoundWave.TREBLE] = mix(cover.vivid, Color.WHITE, 0.5f);
        updateShaders();
        invalidate();
    }

    /** Starts or stops the animation (it only runs while the strands are on screen). */
    void setRunning(boolean run) {
        if (running == run) return;
        running = run;
        lastFrame = 0;
        if (run) postInvalidateOnAnimation();
    }

    /** How far each strand moves right now, 0 when flat (for tests). */
    float amplitude(int strand) {
        return shown[strand];
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        updateShaders();
    }

    private void updateShaders() {
        int w = getWidth();
        if (w == 0) return;
        float[] stops = {0f, 0.12f, 0.88f, 1f};
        for (int s = 0; s < SoundWave.BANDS; s++) {
            int c = colours[s];
            int[] fade = {c & 0x00FFFFFF, c, c, c & 0x00FFFFFF};
            line[s].setShader(new LinearGradient(0, 0, w, 0, fade, stops, Shader.TileMode.CLAMP));
            glow[s].setShader(new LinearGradient(0, 0, w, 0, fade, stops, Shader.TileMode.CLAMP));
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        long now = AnimationUtils.currentAnimationTimeMillis();
        float dt = lastFrame == 0 ? 0f : Math.min(0.05f, (now - lastFrame) / 1000f);
        lastFrame = now;
        time += dt;
        if (source != null) source.levels(time, target);
        float settle = (float) Math.pow(0.012, dt); // falls back over about half a second
        for (int s = 0; s < shown.length; s++) {
            // A hit (a kick, a syllable, a cymbal) is a jump above the recent average: push it.
            average[s] += (target[s] - average[s]) * Math.min(1f, dt * 2.5f);
            float hit = Math.max(0f, target[s] - average[s]) * 1.4f;
            float goal = Math.min(1f, 0.85f * target[s] + hit);
            shown[s] = goal > shown[s] ? shown[s] + (goal - shown[s]) * Math.min(1f, dt * 30)
                    : goal + (shown[s] - goal) * settle;
        }

        float w = getWidth();
        float cy = getHeight() / 2f;
        // Bass underneath, treble on top.
        for (int s = 0; s < SoundWave.BANDS; s++) {
            float limit = cy - (GLOW[s] / 2) * density;
            trace(paths[s], s, w, cy, limit);
            canvas.drawPath(paths[s], glow[s]);
            canvas.drawPath(paths[s], line[s]);
        }
        if (running) postInvalidateOnAnimation();
    }

    /** One strand across the view, fading to nothing over the last few percent at each end. */
    private void trace(Path path, int s, float w, float cy, float limit) {
        path.rewind();
        float step = 3 * density;
        float reach = shown[s] * REACH[s] * density;
        for (float x = 0; x <= w; x += step) {
            float u = x / w;
            float e = Math.min(1f, Math.min(u, 1f - u) / EDGE);
            float edge = e * e * (3 - 2 * e);
            double y = reach * Math.sin(u * WAVES[s] * Math.PI * 2 + time * SPEED[s] + PHASE[s]) * edge;
            float py = (float) Math.max(-limit, Math.min(limit, y));
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

    /** Lifts a colour's brightness to at least {@code min}, so a dark cover tone still shows on graphite. */
    private static int atLeastBrightness(int colour, float min) {
        float[] hsv = new float[3];
        Color.colorToHSV(colour, hsv);
        hsv[2] = Math.max(hsv[2], min);
        return Color.HSVToColor(hsv);
    }
}
