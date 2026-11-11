package com.camdeco.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.AnimationUtils;

import java.util.Random;

/**
 * The room behind everything: a dark stage in haze. Two washes of colour drift slowly, the scene's
 * key behind the cover and its fill low on the left, and swell a little with the bass. Fog banks
 * drift through and never stop, even when the music does. On Home a row of small lamps far
 * overhead runs a slow chase, like a lighting rig seen through haze; on Apps three street lamps
 * recede into the fog beside a thin neon, their light streaking down a wet floor.
 *
 * Every light has two parts: a soft glow, drawn at 1/8 of the screen's size and stretched so it
 * spreads like light in fog, and a sharp core drawn at full detail. A fine grain over the haze
 * keeps its gradients smooth.
 */
public class StageView extends View {
    static final int HOME = 0;
    static final int APPS = 1;
    /** The stage is laid out on a 1280 × 720 grid: 4/3 of a dp on a 960 × 540 dp TV. */
    private static final float W = 1280f, H = 720f;
    private static final int SOFT_W = 160, SOFT_H = 90;
    private static final long FRAME_MS = 33;
    private static final float BLEND_SECONDS = 0.7f;
    private static final int GROUND = 0xFF05070B;
    private static final int FOG = 0xFFB4C8E1;
    private static final int RIG = 9;
    /** Street lights on Apps: x, y, core width, core height, halo radius, strength; the last is the neon. */
    private static final float[][] STREET = {
            {1004, 150, 12, 4, 170, 0.9f},
            {1112, 176, 8, 3, 120, 0.75f},
            {1182, 192, 5, 2, 80, 0.6f},
            {1150, 214, 46, 2, 110, 0.7f},
    };

    /** The lights of one scene: a soft glow for each colour, and the street lights' floor streaks. */
    private static final class Lights {
        final Scene scene;
        final Shader key;
        final Shader fill;
        final Shader led;
        final Shader[] streaks = new Shader[STREET.length];
        final int[] cores = new int[STREET.length];

        Lights(Scene scene) {
            this.scene = scene;
            key = unit(scene.key);
            fill = unit(scene.fill);
            led = unit(scene.led);
            for (int i = 0; i < STREET.length; i++) {
                int tone = i == STREET.length - 1 ? scene.fill : scene.key;
                int core = Scene.mix(tone, scene.led, 0.65f);
                cores[i] = core;
                float top = 300 + (STREET[i][1] - 150) * 1.2f;
                streaks[i] = new LinearGradient(0, top, 0, H,
                        new int[]{tone & 0x00FFFFFF, Scene.alpha(core, 0.12f), tone & 0x00FFFFFF},
                        new float[]{0f, 0.3f, 1f}, Shader.TileMode.CLAMP);
            }
        }

        Shader rigTone(int i) {
            return i % 2 == 1 ? led : i % 4 == 0 ? key : fill;
        }

        int rigColour(int i) {
            int tone = i % 2 == 1 ? scene.led : i % 4 == 0 ? scene.key : scene.fill;
            return Scene.mix(tone, scene.led, 0.6f);
        }
    }

    private final Bitmap soft = Bitmap.createBitmap(SOFT_W, SOFT_H, Bitmap.Config.ARGB_8888);
    private final Canvas softCanvas = new Canvas(soft);
    private final Paint softPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint glowPaint = new Paint(Paint.DITHER_FLAG);
    private final Paint corePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint streakPaint = new Paint();
    private final Paint grainPaint = new Paint();
    private final Matrix matrix = new Matrix();
    private final Rect screen = new Rect();
    private final Shader fog = unit(FOG);
    private Lights lights = new Lights(Scene.promise());
    private Lights previous;
    private float blend = 1f;
    private float mode;
    private int modeTarget = HOME;
    private boolean running;
    private long lastFrame;
    private float air;
    private float bass;
    private SoundWaveView levels;

    public StageView(Context context, AttributeSet attrs) {
        super(context, attrs);
        softCanvas.scale(SOFT_W / W, SOFT_H / H); // the glows are placed on the stage's grid
        glowPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD)); // light adds up
        grainPaint.setShader(new BitmapShader(grain(), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
    }

    /** Changes the light to this scene, blending over a moment. */
    void setScene(Scene scene) {
        if (scene.sameAs(lights.scene)) return;
        previous = lights;
        lights = new Lights(scene);
        blend = 0f;
        invalidate();
    }

    Scene scene() {
        return lights.scene;
    }

    /** {@link #HOME}'s overhead rig or {@link #APPS}'s street, fading from one to the other. */
    void setMode(int newMode) {
        modeTarget = newMode;
        invalidate();
    }

    int mode() {
        return modeTarget;
    }

    /** The visualizer, whose bass makes the washes swell while it shows. */
    void setLevelSource(SoundWaveView view) {
        levels = view;
    }

    /** Animates while the launcher is on screen; otherwise it holds still. */
    void setRunning(boolean run) {
        if (running == run) return;
        running = run;
        lastFrame = 0;
        if (run) invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        long now = AnimationUtils.currentAnimationTimeMillis();
        float dt = lastFrame == 0 ? 0f : Math.min(0.1f, (now - lastFrame) / 1000f);
        lastFrame = now;
        air += dt;
        if (blend < 1f) {
            blend = Math.min(1f, blend + dt / BLEND_SECONDS);
            if (blend >= 1f) previous = null;
        }
        float step = dt / BLEND_SECONDS;
        mode = modeTarget == APPS ? Math.min(1f, mode + step) : Math.max(0f, mode - step);
        float level = levels != null && levels.isShown() ? levels.bass() : 0f;
        bass += (level - bass) * Math.min(1f, dt * 6);

        float f = blend * blend * (3 - 2 * blend);
        soft.eraseColor(0);
        if (previous != null) paintSoft(previous, 1 - f);
        paintSoft(lights, f);
        for (int i = 0; i < 2; i++) {
            float x = ((air * (7 + i * 4) + i * 760) % 2000) - 360;
            glow(fog, x, 250 + i * 190, 170 * 3.4f, 170, 0.05f);
        }

        canvas.drawColor(GROUND);
        screen.set(0, 0, getWidth(), getHeight());
        canvas.drawBitmap(soft, null, screen, softPaint);
        canvas.save();
        canvas.scale(getWidth() / W, getHeight() / H);
        if (previous != null) paintSharp(canvas, previous, 1 - f);
        paintSharp(canvas, lights, f);
        canvas.restore();
        canvas.drawRect(screen, grainPaint);

        if (running) postInvalidateDelayed(FRAME_MS);
    }

    /** The glows of one scene, at this strength. */
    private void paintSoft(Lights l, float weight) {
        float home = (1 - mode) * weight, apps = mode * weight;
        float keyWash = (0.15f + 0.06f * bass) * (1 - mode) + 0.1f * mode;
        float fillWash = (0.11f + 0.05f * bass) * (1 - mode) + 0.1f * mode;
        glow(l.key, 1100 + 30 * (float) Math.sin(air * 0.07), 230 + 20 * (float) Math.cos(air * 0.05), 620, 620, keyWash * weight);
        glow(l.fill, 260 + 50 * (float) Math.sin(air * 0.06 + 1), 640, 600, 600, fillWash * weight);
        if (home > 0.01f) {
            for (int i = 0; i < RIG; i++) glow(l.rigTone(i), 100 + i * 135, 33, 120, 120, rigBrightness(i) * 0.3f * home);
        }
        if (apps > 0.01f) {
            for (int i = 0; i < STREET.length; i++) {
                float[] s = STREET[i];
                float a = s[5] * stutter(i) * apps;
                Shader tone = i == STREET.length - 1 ? l.fill : l.key;
                glow(tone, s[0], s[1], s[4], s[4], 0.4f * a);
                glow(l.led, s[0], s[1], s[4] * 0.3f, s[4] * 0.3f, 0.22f * a);
                glow(tone, s[0], 470 + (s[1] - 150) * 1.2f, 260 * (s[2] > 20 ? 0.4f : 0.16f), 260, 0.16f * a);
            }
        }
    }

    /** The sharp cores of one scene's lights, at this strength. */
    private void paintSharp(Canvas canvas, Lights l, float weight) {
        float home = (1 - mode) * weight, apps = mode * weight;
        if (home > 0.01f) {
            for (int i = 0; i < RIG; i++) {
                corePaint.setColor(Scene.alpha(l.rigColour(i), Math.min(1f, rigBrightness(i) + 0.2f) * home));
                float x = 100 + i * 135;
                canvas.drawRect(x - 3, 8, x + 3, 10, corePaint);
            }
        }
        if (apps > 0.01f) {
            for (int i = 0; i < STREET.length; i++) {
                float[] s = STREET[i];
                float a = s[5] * stutter(i) * apps;
                corePaint.setColor(Scene.alpha(l.cores[i], a));
                canvas.drawRect(s[0] - s[2] / 2, s[1] - s[3] / 2, s[0] + s[2] / 2, s[1] + s[3] / 2, corePaint);
                float top = 300 + (s[1] - 150) * 1.2f;
                float width = s[2] > 20 ? s[2] * 0.6f : Math.max(1, s[2] * 0.3f);
                streakPaint.setShader(l.streaks[i]);
                streakPaint.setAlpha(Math.round(255 * a));
                canvas.drawRect(s[0] - width / 2, top, s[0] + width / 2, H, streakPaint);
            }
        }
    }

    /** A rig lamp's brightness: a slow chase runs along the row, and the bass lifts them all. */
    private float rigBrightness(int i) {
        float chase = (float) Math.pow(Math.max(0, Math.cos(Math.PI * 2 * (i / (float) RIG - air / 7))), 4);
        return 0.22f + 0.5f * chase + 0.12f * bass;
    }

    /** The farthest street lamp stutters now and then, like an old sodium lamp. */
    private float stutter(int i) {
        if (i != 2 || air % 9 < 8.4f) return 1f;
        return 0.35f + 0.65f * Math.abs((float) Math.sin(air * 40));
    }

    /** A soft glow on the small canvas, in stage units: centre, radii and strength. */
    private void glow(Shader shader, float x, float y, float rx, float ry, float strength) {
        if (strength < 0.004f) return;
        matrix.setScale(rx, ry);
        matrix.postTranslate(x, y);
        shader.setLocalMatrix(matrix);
        glowPaint.setShader(shader);
        glowPaint.setAlpha(Math.min(255, Math.round(strength * 255)));
        softCanvas.drawRect(x - rx, y - ry, x + rx, y + ry, glowPaint);
    }

    /** A round glow of radius 1 around the origin, from the colour to nothing. */
    private static Shader unit(int colour) {
        return new RadialGradient(0, 0, 1, colour | 0xFF000000, colour & 0x00FFFFFF, Shader.TileMode.CLAMP);
    }

    /** A tile of faint white specks, like film grain. */
    private static Bitmap grain() {
        int size = 128;
        int[] pixels = new int[size * size];
        Random random = new Random(7);
        for (int i = 0; i < pixels.length; i++) {
            float v = random.nextFloat();
            int a = v > 0.5f ? Math.round((v - 0.5f) * 2 * 14) : 0;
            pixels[i] = (a << 24) | 0x00FFFFFF;
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888);
    }
}
