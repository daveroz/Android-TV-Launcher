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
 * The hero for an app: a small light show in its colour, on the right, after Justice's stage. Up
 * top, angled panels of small lamps hang in a fan and twinkle, with chases running down them. Below,
 * clusters of tall bar lights stand at different depths in the haze, each fading up and down in its
 * own time and now and then flashing; a few bright points pop on and fade. Every light has a sharp
 * core, white-hot when it's brightest, and a soft round glow, drawn small and stretched like light
 * in fog. One colour only: the app's own.
 */
public class LightShowView extends View {
    private static final long FRAME_MS = 33;
    /** The glow layer is drawn at this fraction of the show's size. */
    private static final float SOFT = 1 / 8f;

    /** Overhead panels: where (share of the width), and how far each one tilts. */
    private static final float[] PANEL_X = {0.16f, 0.39f, 0.62f, 0.86f};
    private static final float[] PANEL_TILT = {-24f, -9f, 9f, 24f};
    private static final int PANEL_COLS = 4, PANEL_ROWS = 7;
    /** Bar clusters: x and the bars' foot (shares of the area), nearness, how many bars, timing. */
    private static final float[][] CLUSTERS = {
            {0.08f, 0.60f, 0.70f, 3, 0.0f},
            {0.24f, 0.86f, 0.95f, 4, 1.7f},
            {0.40f, 0.52f, 0.60f, 2, 3.1f},
            {0.55f, 0.95f, 1.10f, 3, 4.6f},
            {0.70f, 0.66f, 0.75f, 4, 2.3f},
            {0.85f, 0.90f, 1.00f, 2, 5.4f},
            {0.96f, 0.56f, 0.65f, 3, 0.9f},
    };
    /** Points that pop on and fade: x, y (shares of the area), and timing. */
    private static final float[][] FLARES = {{0.78f, 0.30f, 0.3f}, {0.95f, 0.45f, 0.7f}, {0.60f, 0.24f, 0.1f}};

    private final float density;
    private final Paint core = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint softPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Bitmap sprite = Glow.sprite();
    private final RectF rect = new RectF();
    private final Rect softBounds = new Rect();
    private Bitmap soft;
    private Canvas softCanvas;
    private Scene scene = Scene.promise();
    private boolean running;
    private long lastFrame;
    private float time;
    /** The area the show fills, in the view. */
    private float left, top, right, bottom;

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

    /** The area the show fills: left, top, right, bottom in the view (for tests). */
    float[] showBounds() {
        return new float[]{left, top, right, bottom};
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        // Right of the text, from under the top bar to just above the rows, out to the screen margin.
        float margin = getResources().getDimension(R.dimen.overscan_h);
        left = Math.max(margin + 464 * density, w * 0.53f);
        right = w - margin;
        top = 80 * density;
        bottom = getResources().getDimension(R.dimen.hero_top) + getResources().getDimension(R.dimen.hero_card_height);
        int spread = Math.round(48 * density);
        softBounds.set(Math.round(left) - spread, Math.round(top) - spread, Math.round(right) + spread, Math.round(bottom) + spread);
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
        soft.eraseColor(0);
        float w = right - left, h = bottom - top;

        // A warm haze over the whole area, brighter while the clusters are up.
        float lit = 0f;
        for (float[] c : CLUSTERS) lit += cluster(c[4]) / CLUSTERS.length;
        glow(left + w * 0.55f, top + h * 0.6f, w * 0.7f, 0.10f + 0.12f * lit);

        // Pass one: the glows, into the small layer. Pass two: the sharp lights over them.
        for (int pass = 0; pass < 2; pass++) {
            if (pass == 1) canvas.drawBitmap(soft, null, softBounds, softPaint);
            drawPanels(canvas, pass == 0, w);
            drawClusters(canvas, pass == 0, w, h);
            drawFlares(canvas, pass == 0, w, h);
        }
        if (running) postInvalidateDelayed(FRAME_MS);
    }

    /** The overhead panels: small lamps that twinkle, with chases running down each panel. */
    private void drawPanels(Canvas canvas, boolean glows, float w) {
        float dot = 2.4f * density, pitch = 4.8f * density;
        for (int p = 0; p < PANEL_X.length; p++) {
            float px = left + w * PANEL_X[p], py = top;
            float envelope = 0.5f + 0.5f * (float) Math.sin(time * 0.37f + p * 1.7f);
            double angle = Math.toRadians(PANEL_TILT[p]);
            float cos = (float) Math.cos(angle), sin = (float) Math.sin(angle);
            for (int r = 0; r < PANEL_ROWS; r++) {
                float chase = (float) Math.pow(Math.max(0, Math.sin(r * 0.8 - time * 2.2 + p * 1.3)), 6) * envelope;
                for (int c = 0; c < PANEL_COLS; c++) {
                    float seed = seed(p * 97 + r * 13 + c);
                    float twinkle = (float) Math.pow(Math.max(0, Math.sin(time * (1.3 + seed * 2.1) + seed * 50)), 6);
                    float v = Math.min(1f, 0.85f * Math.max(twinkle, chase));
                    if (v < 0.08f) continue;
                    // The panel hangs from its top centre and tilts.
                    float lx = (c - (PANEL_COLS - 1) / 2f) * pitch, ly = r * pitch + pitch;
                    float x = px + lx * cos - ly * sin, y = py + lx * sin + ly * cos;
                    if (glows) {
                        glow(x, y, dot * 3f, v * 0.5f);
                    } else {
                        core.setColor(Scene.alpha(Scene.mix(scene.key, scene.led, 0.5f * v * v), 0.25f + 0.75f * v));
                        canvas.drawCircle(x, y, dot / 2, core);
                    }
                }
            }
        }
    }

    /** The bar clusters: each fades up and down in its own time, flickers, and now and then flashes. */
    private void drawClusters(Canvas canvas, boolean glows, float w, float h) {
        for (int k = 0; k < CLUSTERS.length; k++) {
            float[] c = CLUSTERS[k];
            float near = c[2];
            int bars = (int) c[3];
            float barW = 6 * density * near, gap = 3.5f * density * near;
            float width = bars * barW + (bars - 1) * gap;
            float x0 = left + w * c[0] - width / 2, foot = top + h * c[1];
            float envelope = cluster(c[4]);
            float flash = (float) Math.pow(Math.max(0, Math.sin(time * 7.3 + c[4] * 3)), 30) * (1 - envelope);
            float tallest = 0f;
            for (int b = 0; b < bars; b++) tallest = Math.max(tallest, barHeight(k, b, near));
            if (glows) {
                float v = Math.min(1f, envelope + 0.6f * flash);
                glow(x0 + width / 2, foot - tallest / 2, 70 * density * near, 0.6f * v);
            }
            for (int b = 0; b < bars; b++) {
                float seed = seed(k * 31 + b);
                float flicker = 0.85f + 0.15f * (float) Math.sin(time * 13 + seed * 20);
                float v = Math.min(1f, envelope * flicker + 0.6f * flash);
                if (v < 0.05f) continue;
                float bh = barHeight(k, b, near);
                float x = x0 + b * (barW + gap);
                if (glows) {
                    glow(x + barW / 2, foot - bh / 2, bh * 0.9f, 0.4f * v);
                    continue;
                }
                core.setColor(Scene.alpha(Scene.mix(scene.key, scene.led, 0.35f * v), 0.3f + 0.7f * v));
                rect.set(x, foot - bh, x + barW, foot);
                canvas.drawRect(rect, core);
                // A white-hot line down the middle while it's bright.
                core.setColor(Scene.alpha(scene.led, 0.8f * v * v));
                float inset = barW * 0.3f;
                rect.set(x + inset, foot - bh + inset, x + barW - inset, foot - inset);
                canvas.drawRect(rect, core);
            }
        }
    }

    /** Bright points that pop on, then fade. */
    private void drawFlares(Canvas canvas, boolean glows, float w, float h) {
        for (float[] f : FLARES) {
            float period = 2.8f + f[2] * 2f;
            float local = (time + f[2] * 7) % period;
            float v = local < 0.08f ? local / 0.08f : (float) Math.exp(-(local - 0.08f) * 2.2f);
            if (v < 0.05f) continue;
            float x = left + w * f[0], y = top + h * f[1];
            for (int i = 0; i < 2; i++) { // they come in pairs
                float px = x + (i - 0.5f) * 7 * density;
                if (glows) {
                    glow(px, y, 12 * density, 0.6f * v);
                } else {
                    core.setColor(Scene.alpha(Scene.mix(scene.key, scene.led, 0.6f * v), v));
                    canvas.drawCircle(px, y, 2.2f * density, core);
                }
            }
        }
    }

    /** How lit a cluster is now: up for a while, down for a while, fading between. */
    private float cluster(float phase) {
        float s = 0.5f + 0.5f * (float) Math.sin(time * 0.55f + phase);
        float t = Math.max(0f, Math.min(1f, (s - 0.05f) / 0.35f));
        return t * t * (3 - 2 * t);
    }

    private float barHeight(int cluster, int bar, float near) {
        return 32 * density * near * (0.75f + 0.5f * seed(cluster * 31 + bar + 7));
    }

    /** A soft round glow around a point, into the small layer. */
    private void glow(float x, float y, float radius, float strength) {
        if (strength < 0.01f) return;
        glowPaint.setColor(Scene.alpha(scene.key, Math.min(1f, strength)));
        rect.set(x - radius, y - radius, x + radius, y + radius);
        softCanvas.drawBitmap(sprite, null, rect, glowPaint);
    }

    /** A fixed pseudo-random number per light, so no two flicker in step. */
    private static float seed(int i) {
        double s = Math.sin(i * 127.1 + 311.7) * 43758.5453;
        return (float) (s - Math.floor(s));
    }
}
