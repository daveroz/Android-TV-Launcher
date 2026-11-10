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
 * The music under the song in the hero: one line split by pitch, like a spectrum. The bass swells
 * the left part in slow, wide waves, voices ripple the middle, and the treble shimmers on the
 * right. The colour runs from the album cover's deep tone (bass) to its light tone (treble). Drum
 * hits and syllables give a short extra push, and the line goes flat when the music stops.
 */
public class SoundWaveView extends View {
    // Per zone (bass, voice, treble): where along the line it sits (0 to 1) and how wide it is,
    // how far it moves at full level (dp), how many waves would fit across, and how fast they travel.
    private static final float[] CENTRE = {0.17f, 0.5f, 0.83f};
    private static final float[] SPREAD = {0.13f, 0.12f, 0.12f};
    private static final float[] REACH = {24, 15, 8};
    private static final float[] WAVES = {2.2f, 7f, 22f};
    private static final float[] SPEED = {2.2f, -5.5f, 13f};
    /** The line fades in and out over this much of each end. */
    private static final float EDGE = 0.07f;

    private final float density;
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final float[] target = new float[SoundWave.BANDS];
    private final float[] average = new float[SoundWave.BANDS];
    private final float[] shown = new float[SoundWave.BANDS];
    private SoundWave source;
    private boolean running;
    private long lastFrame;
    private double time;
    private int deep;
    private int vivid;
    private int light;

    public SoundWaveView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = context.getResources().getDisplayMetrics().density;
        for (Paint p : new Paint[]{line, glow}) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
        }
        line.setStrokeWidth(3 * density);
        glow.setStrokeWidth(9 * density);
        glow.setAlpha(56);
        setColours(AppColors.worldly());
    }

    void setSource(SoundWave wave) {
        source = wave;
    }

    /** The line's colours, from the album cover: deep on the bass side, light on the treble side. */
    void setColours(AppColors cover) {
        deep = atLeastBrightness(mix(cover.vivid, cover.deep, 0.5f), 0.5f);
        vivid = cover.vivid;
        light = mix(cover.vivid, Color.WHITE, 0.5f);
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

    /** How far each zone moves right now, 0 when flat (for tests). */
    float amplitude(int zone) {
        return shown[zone];
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        updateShaders();
    }

    private void updateShaders() {
        int w = getWidth();
        if (w == 0) return;
        int[] colours = {deep & 0x00FFFFFF, deep, vivid, light, light & 0x00FFFFFF};
        float[] stops = {0f, 0.08f, 0.5f, 0.86f, 1f};
        line.setShader(new LinearGradient(0, 0, w, 0, colours, stops, Shader.TileMode.CLAMP));
        glow.setShader(new LinearGradient(0, 0, w, 0, colours, stops, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        long now = AnimationUtils.currentAnimationTimeMillis();
        float dt = lastFrame == 0 ? 0f : Math.min(0.05f, (now - lastFrame) / 1000f);
        lastFrame = now;
        time += dt;
        if (source != null) source.levels(time, target);
        float settle = (float) Math.pow(0.012, dt); // falls back over about half a second
        for (int z = 0; z < shown.length; z++) {
            // A hit (a kick, a syllable, a cymbal) is a jump above the recent average: push it.
            average[z] += (target[z] - average[z]) * Math.min(1f, dt * 2.5f);
            float hit = Math.max(0f, target[z] - average[z]) * 1.4f;
            float goal = Math.min(1f, 0.85f * target[z] + hit);
            shown[z] = goal > shown[z] ? shown[z] + (goal - shown[z]) * Math.min(1f, dt * 30)
                    : goal + (shown[z] - goal) * settle;
        }

        float w = getWidth();
        float cy = getHeight() / 2f;
        trace(w, cy, cy - 4.5f * density);
        canvas.drawPath(path, glow);
        canvas.drawPath(path, line);
        if (running) postInvalidateOnAnimation();
    }

    /** The line across the view: each zone's ripple where it belongs, fading only at the very ends. */
    private void trace(float w, float cy, float limit) {
        path.rewind();
        float step = 2.5f * density;
        for (float x = 0; x <= w; x += step) {
            float u = x / w;
            double y = 0;
            for (int z = 0; z < shown.length; z++) {
                double d = (u - CENTRE[z]) / SPREAD[z];
                double weight = Math.exp(-0.5 * d * d);
                y += weight * shown[z] * REACH[z] * density
                        * Math.sin(u * WAVES[z] * Math.PI * 2 + time * SPEED[z]);
            }
            float e = Math.min(1f, Math.min(u, 1f - u) / EDGE);
            y *= e * e * (3 - 2 * e);
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
