package com.camdeco.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.StateListDrawable;
import android.view.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * The launcher's chrome as LotoLab draws it (Fuji-App's crystal style on Worldly's stage), lit by Deco's own stage.
 *
 * {@link Pane}: liquid glass, kept quiet and a little solid: charcoal that only just lets the stage through, a touch
 * lighter at the top left, deeper at the bottom, a faint sheen, film grain, and a hairline rim that's brightest at
 * the corners that catch the light. It picks up the stage's light behind it, in the colour of what's in focus (where
 * the stage's key light is on the screen, the glass glows a little in it) and its rim warms where the light falls.
 * Its tones: a pane (the tab pill, menus), a lighter lens (the current tab, a remote key), and a round bubble.
 *
 * {@link Lit}: focus in the top bar and menus, LotoLab's "something that's on": a wash of the house cyan with the
 * light bleeding in from a glowing edge, all within its bounds, so the focused tab is never bigger than the others.
 * {@link Lamp}: a status light, a hot core blooming into the haze.
 */
final class Glass {
    private Glass() {}

    /** The house accent: focus in the chrome. */
    static final int ACCENT = 0xFF3FC6E8;

    /** A rectangle with rounded corners of {@code radius} (reduced to fit): the one shape the chrome uses. */
    static void rounded(Path path, RectF r, float radius) {
        path.reset();
        if (r.width() <= 0 || r.height() <= 0) return;
        float rad = Math.max(0, Math.min(radius, Math.min(r.width(), r.height()) / 2));
        path.addRoundRect(r, rad, rad, Path.Direction.CW);
    }

    // ---------------------------------------------------------------- the stage's light, as glass picks it up

    /** Every pane that takes the stage's light, to redraw when that light changes colour. */
    private static final Set<Pane> PANES = Collections.newSetFromMap(new WeakHashMap<>());
    /** The stage's light: the colour of what's in focus (the house cyan to start with). */
    private static int light = Scene.promise().key;

    /** The stage is now lit in this colour: every pane takes it. */
    static void setLight(int colour) {
        if ((colour | 0xFF000000) == (light | 0xFF000000)) return;
        light = colour | 0xFF000000;
        for (Pane p : new ArrayList<>(PANES)) p.relight();
    }

    /** The view a drawable is drawn for, through any state list around it. */
    static View hostOf(Drawable d) {
        Object cb = d.getCallback();
        while (cb instanceof Drawable) cb = ((Drawable) cb).getCallback();
        return cb instanceof View ? (View) cb : null;
    }

    /**
     * The key light's strength at a point of the window (0 to about 0.16), where Deco's stage lays it down: its strong
     * wash high on the right, behind the cover.
     */
    static float keyAt(float x, float y, int w, int h) {
        float r = w * 0.48f;
        float d = (float) Math.hypot(x - w * 0.86f, y - h * 0.32f);
        return d >= r ? 0 : 0.16f * (1 - d / r);
    }

    private static int alpha(int colour, int a) {
        return (a << 24) | (colour & 0x00FFFFFF);
    }

    // ---------------------------------------------------------------- glass

    enum Tone { PANE, LENS, BUBBLE }

    /** A pane of glass; see the class notes. {@code radius} in pixels (a pill: anything at least half its height). */
    static final class Pane extends Drawable {
        private final float density, radius;
        private final Tone tone;
        private final RectF rect = new RectF(), rimRect = new RectF();
        private final Path shape = new Path(), rimShape = new Path();
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        private final Paint depth = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        private final Paint grain = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Matrix m = new Matrix();
        private final int[] loc = new int[2];
        /** The stage's key light and its fill, as round glows of radius 1, placed where they are at each draw. */
        private Shader keyLight, fillLight;
        private float rimWarmth = -1;

        Pane(Context c, float radius, Tone tone) {
            density = c.getResources().getDisplayMetrics().density;
            this.radius = radius;
            this.tone = tone;
            grain.setShader(new BitmapShader(StageView.filmGrain(), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
            grain.setAlpha(90);
            rim.setStyle(Paint.Style.STROKE);
            rim.setStrokeWidth(Math.max(1, density * 0.6f));
            relight();
            if (tone != Tone.LENS) PANES.add(this);
        }

        /** Takes the stage's light in its current colour. */
        void relight() {
            keyLight = new RadialGradient(0, 0, 1, new int[]{alpha(light, 0x24), alpha(light, 0x0E), alpha(light, 0)},
                    new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP);
            fillLight = new RadialGradient(0, 0, 1, alpha(light, 0x10), alpha(light, 0), Shader.TileMode.CLAMP);
            if (tone == Tone.LENS && !rect.isEmpty()) lensLight();
            rimWarmth = -1;
            invalidateSelf();
        }

        private void lensLight() {
            float w = rect.width(), h = rect.height();
            glow.setShader(new RadialGradient(rect.centerX(), rect.bottom + h * 0.2f, Math.max(1, Math.max(w, h) * 0.7f),
                    alpha(light, 0x10), alpha(light, 0), Shader.TileMode.CLAMP));
        }

        @Override
        protected void onBoundsChange(Rect b) {
            rect.set(b);
            float inset = rim.getStrokeWidth() / 2;
            rimRect.set(rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset);
            rounded(shape, rect, corner(rect));
            rounded(rimShape, rimRect, Math.max(0, corner(rimRect) - inset));
            float w = rect.width(), h = rect.height();
            int top, mid, bottom;
            switch (tone) {
                case LENS: top = 0x29FFFFFF; mid = 0x21FFFFFF; bottom = 0x1CFFFFFF; break;
                case BUBBLE: top = 0xF21B1C20; mid = 0xF3161719; bottom = 0xF5121316; break;
                default: top = 0xDE191A1E; mid = 0xE3131417; bottom = 0xEB0F1013;
            }
            // charcoal, barely cool: lighter at the top left, deepest at the bottom right
            fill.setShader(new LinearGradient(rect.left, rect.top, rect.left + w * 0.35f, rect.bottom, new int[]{top, mid, bottom},
                    new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
            sheen.setShader(new RadialGradient(rect.left + w * 0.15f, rect.top, Math.max(1, Math.max(w, h) * 0.75f),
                    tone == Tone.LENS ? 0x0DFFFFFF : 0x08FFFFFF, 0, Shader.TileMode.CLAMP));
            depth.setShader(new LinearGradient(0, rect.bottom - Math.min(h * 0.4f, 36 * density), 0, rect.bottom, 0, 0x1F000000,
                    Shader.TileMode.CLAMP));
            if (tone == Tone.LENS) lensLight();
            rimWarmth = -1;
        }

        private float corner(RectF r) {
            if (tone == Tone.BUBBLE) return Math.min(r.width(), r.height()) * 0.27f;
            return radius;
        }

        @Override
        public void draw(Canvas canvas) {
            if (rect.isEmpty()) return;
            canvas.drawPath(shape, fill);
            float warm = 0;
            View host = tone != Tone.LENS ? hostOf(this) : null;
            if (host != null && host.getRootView() != null) {
                // the stage's light behind this glass: the key high on the right, the fill low on the left
                host.getLocationInWindow(loc);
                View root = host.getRootView();
                int w = root.getWidth(), h = root.getHeight();
                if (w > 0 && h > 0) {
                    m.setScale(w * 0.48f, w * 0.48f);
                    m.postTranslate(w * 0.86f - loc[0], h * 0.32f - loc[1]);
                    keyLight.setLocalMatrix(m);
                    glow.setShader(keyLight);
                    canvas.drawPath(shape, glow);
                    m.setScale(w * 0.47f, w * 0.47f);
                    m.postTranslate(w * 0.2f - loc[0], h * 0.89f - loc[1]);
                    fillLight.setLocalMatrix(m);
                    glow.setShader(fillLight);
                    canvas.drawPath(shape, glow);
                    warm = Math.min(0.25f, 2 * keyAt(loc[0] + rect.right - 12 * density, loc[1] + rect.top + 12 * density, w, h));
                }
            } else if (tone == Tone.LENS) {
                canvas.drawPath(shape, glow);
            }
            canvas.drawPath(shape, sheen);
            canvas.drawPath(shape, depth);
            canvas.drawPath(shape, grain);
            if (Math.abs(warm - rimWarmth) > 0.03f) {
                rimWarmth = warm;
                rim.setShader(rimShader(warm));
            }
            canvas.drawPath(rimShape, rim);
        }

        /** A hairline, brightest at the corners that catch the light; at the top right it takes the light's colour. */
        private Shader rimShader(float warm) {
            int strong = tone == Tone.BUBBLE ? 0x33 : tone == Tone.LENS ? 0x40 : 0x36;
            int soft = tone == Tone.BUBBLE ? 0x21 : 0x1A;
            int edge = (strong << 24) | 0xFFFFFF, faint = (soft << 24) | 0xFFFFFF, mid = (((strong + soft) / 2) << 24) | 0xFFFFFF;
            int corner = faint;
            if (warm > 0.01f) {
                int a = Math.max(0, Math.min(255, Math.round(soft + (0x48 - soft) * warm / 0.6f)));
                corner = alpha(Scene.mix(light, 0xFFFFFFFF, 0.6f), a);
            }
            // clockwise from the right: bottom right, bottom left and top left catch the light; the top right takes its colour
            return new SweepGradient(rimRect.centerX(), rimRect.centerY(),
                    new int[]{faint, mid, faint, mid, faint, edge, faint, corner, faint},
                    new float[]{0f, 0.125f, 0.25f, 0.375f, 0.5f, 0.625f, 0.75f, 0.875f, 1f});
        }

        @Override public void setAlpha(int alpha) { }
        @Override public void setColorFilter(ColorFilter cf) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    // ---------------------------------------------------------------- lit

    /**
     * Focus: a wash of the house cyan on the glass, the light bleeding in from a glowing edge (Worldly's bloom, kept
     * inside), and the edge itself. It fills its bounds exactly. Drawn once into a picture per size, since a blur can't
     * be drawn live everywhere.
     */
    static final class Lit extends Drawable {
        private final float radius, line;
        private Bitmap picture;

        Lit(Context c, float radius) {
            float d = c.getResources().getDisplayMetrics().density;
            this.radius = radius;
            line = Math.max(1, 1.2f * d);
        }

        @Override
        protected void onBoundsChange(Rect b) {
            picture = null;
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            if (b.width() <= 0 || b.height() <= 0) return;
            if (picture == null) {
                picture = Bitmap.createBitmap(b.width(), b.height(), Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(picture);
                RectF r = new RectF(0, 0, b.width(), b.height());
                Path shape = new Path();
                rounded(shape, r, radius);
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
                p.setColor(alpha(ACCENT, 0x24));
                c.drawPath(shape, p);
                // the light bleeding in from the edge, a deeper cyan, as light seen through haze
                c.save();
                c.clipPath(shape);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(line * 6);
                p.setColor(alpha(Scene.mix(ACCENT, 0xFF0A4A66, 0.35f), 0x55));
                p.setMaskFilter(new BlurMaskFilter(line * 4, BlurMaskFilter.Blur.NORMAL));
                c.drawPath(shape, p);
                p.setMaskFilter(null);
                // and a glint low in the middle, where the light pools
                p.setStyle(Paint.Style.FILL);
                p.setShader(new RadialGradient(r.centerX(), r.bottom, Math.max(1, Math.max(r.width(), r.height()) * 0.6f),
                        alpha(Scene.mix(ACCENT, 0xFFFFFFFF, 0.4f), 0x1F), 0, Shader.TileMode.CLAMP));
                c.drawRect(r, p);
                p.setShader(null);
                c.restore();
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(line);
                p.setColor(alpha(ACCENT, 0xB3));
                float h = line / 2;
                Path edge = new Path();
                rounded(edge, new RectF(h, h, r.right - h, r.bottom - h), Math.max(0, radius - h));
                c.drawPath(edge, p);
            }
            canvas.drawBitmap(picture, b.left, b.top, null);
        }

        @Override public void setAlpha(int alpha) { }
        @Override public void setColorFilter(ColorFilter cf) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    // ---------------------------------------------------------------- a lamp

    /** A status lamp: a near-white core in its colour, blooming into the haze. Give the view about three times the core. */
    static final class Lamp extends Drawable {
        private final int color;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);

        Lamp(int color) {
            this.color = color;
        }

        @Override
        protected void onBoundsChange(Rect b) {
            float r = Math.min(b.width(), b.height()) / 2f;
            if (r <= 0) return;
            int rgb = color & 0x00FFFFFF;
            p.setShader(new RadialGradient(b.exactCenterX(), b.exactCenterY(), r,
                    new int[]{0xFFFFFFFF, color | 0xFF000000, color | 0xFF000000, (0x73 << 24) | rgb, (0x1A << 24) | rgb, rgb},
                    new float[]{0f, 0.12f, 0.3f, 0.38f, 0.66f, 1f}, Shader.TileMode.CLAMP));
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            canvas.drawCircle(b.exactCenterX(), b.exactCenterY(), Math.min(b.width(), b.height()) / 2f, p);
        }

        @Override public void setAlpha(int alpha) { }
        @Override public void setColorFilter(ColorFilter cf) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    // ---------------------------------------------------------------- the chrome's states

    /**
     * A control in the top bar or a menu: lit when focused, a lens of glass when it's the current one (the current
     * tab), otherwise nothing, so the glass behind it shows. {@code radius} in pixels.
     */
    static Drawable control(Context c, float radius, boolean lensWhenSelected) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, new Lit(c, radius));
        if (lensWhenSelected) s.addState(new int[]{android.R.attr.state_selected}, new Pane(c, radius, Tone.LENS));
        s.addState(new int[]{}, new ColorDrawable(0));
        return s;
    }
}
