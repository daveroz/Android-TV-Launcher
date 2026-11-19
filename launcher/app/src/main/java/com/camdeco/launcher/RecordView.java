package com.camdeco.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.AnimationUtils;

/**
 * What's playing, as a vinyl LP: the album cover is the sleeve on the right, and while the song
 * plays its record slides out to the left and spins at 33⅓ rpm. On pause it slows to a stop. When
 * the song changes, the record stops, slides back into the sleeve, the sleeve changes to the new
 * cover, and the new record slides out and spins.
 *
 * It's lit as on Worldly's Sound widget. The record is pressed in the scene's colour, luminous like
 * lit coloured vinyl, with fine grooves, and has the cover art on its label, so the spin shows; the
 * label is lit just like the cover on the sleeve. Warm light catches in its grooves, in two soft
 * wedges that stay where the light is while the record turns (so it reads as vinyl), and a soft
 * luminous halo rings its edge, as if the disc gave off light into the haze. It only changes colour
 * while it's back in the sleeve, out of sight, so each song's record comes out as a different disc.
 *
 * The light comes from inside the sleeve, not from behind it: on a dark cover, its bright parts
 * shine as if lit from within (taking on a little of the light's colour, with a thin halo hugging
 * them; a bright cover has nothing to shine against, so it's left as it is), the sleeve is brightest
 * along its mouth, where a line of light comes out, and that light falls on the record as it comes
 * out, brightest nearest the sleeve. Around the square sleeve there's only a soft glow and a faint
 * rim; the stage's light falls softly on the cover, and the screen's film grain lies over it.
 */
public class RecordView extends View {
    static final float RPM = 100f / 3; // 33⅓
    /** Seconds the record takes to slide all the way out of the sleeve, or back in. */
    static final float SLIDE_SECONDS = 0.6f;
    private static final float FADE_SECONDS = 0.35f;
    /** The record's size, as a share of the sleeve's. */
    private static final float DISC = 0.94f;
    /** The label's size, as a share of the record's radius. */
    private static final float LABEL = 0.34f;
    /** The halo's reach beyond the record's edge, as a share of its radius. */
    private static final float HALO = 1.24f;
    /**
     * How much the light tints the cover art (on the sleeve and the label): its bright parts take on
     * this share of the light's colour, and a wash of the light this strong lies over it. Kept low,
     * so the art keeps its own colours.
     */
    private static final float ART_TINT = 0.25f, ART_WASH = 0.03f;
    /** How far the light from the sleeve's mouth reaches across the record, as a share of its radius. */
    private static final float MOUTH = 0.7f;

    private final float density;
    private final Paint sleevePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint discPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint haloPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    /** Light over light: screened, so it brightens and never darkens. */
    private final Paint screenPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint washPaint = new Paint();
    /** The light from inside the sleeve: on the record by its mouth, inside the cover by its mouth, and the mouth itself. */
    private final Paint mouthPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final Paint insidePaint = new Paint(Paint.DITHER_FLAG);
    private final Paint seamPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shinePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint labelLightPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final Matrix shift = new Matrix();
    /**
     * The record's vinyl, without its label, and the label: the light from the mouth falls on both,
     * near white on the vinyl and warmer on the label (matte paper, so a white light would only grey it).
     */
    private final Path vinylRing = new Path(), labelDisc = new Path();
    private final RectF sleeve = new RectF();
    private final RectF dst = new RectF();
    private final Rect bounds = new Rect();
    /** The scene's light: the sleeve glows in it, and its light inside the sleeve is this colour. */
    private Scene light = Scene.promise();
    /** The colour and sleeve the inside light and the seam were made for. */
    private String insideKey = "";

    /** The cover on the sleeve, the one fading out, and the one waiting for the record to go back in. */
    private Drawable cover;
    private Drawable previous;
    private Drawable pending;
    /** Which song is on the sleeve, and which one is waiting (see {@link NowPlaying.Track#song()}). */
    private String song;
    private String pendingSong;
    private boolean playing;
    private boolean running;
    private long lastFrame;
    /** 0 = in the sleeve, 1 = all the way out. */
    private float slide;
    /** 0 = stopped, 1 = at full speed. */
    private float speed;
    private float angle;
    private float fade = 1f;
    /** The record (vinyl, grooves and label) for the current cover, drawn once per cover and size. */
    private Bitmap disc;
    /** The light caught in the grooves, drawn still over the turning record. */
    private Bitmap glint;
    /**
     * The cover's light, made once per cover: where it shines (its bright parts, sharp) and its thin
     * halo (the same, a little blurred), as masks drawn in the light's colour. App icons don't shine.
     */
    private Bitmap shine, shineHalo;
    private Drawable shineFrom;
    /** How much the cover shines, 0 to 1: fully on a dark cover, not at all on a bright one. */
    private float shineStrength;
    /** The colour the record is pressed in (the scene's), or 0 to take it from the cover. */
    private int tint;
    /** The colour the next record out will be pressed in: it only changes while the record is out of sight. */
    private int wantTint;

    public RecordView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = context.getResources().getDisplayMetrics().density;
        sleevePaint.setColor(0xFF0B1220);
        edgePaint.setStyle(Paint.Style.STROKE);
        edgePaint.setStrokeWidth(density);
        screenPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SCREEN));
        washPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SCREEN));
        insidePaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SCREEN));
        mouthPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SCREEN));
        labelLightPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SCREEN));
        setLight(Scene.promise());
    }

    /**
     * Shows this song's cover on the sleeve; the record comes out while {@code nowPlaying}. Only a
     * different song puts the record back and changes the sleeve. The same song with a new copy of
     * its cover (players resend it) just updates the sleeve in place.
     */
    void show(Drawable art, boolean nowPlaying, String songKey) {
        playing = nowPlaying;
        if (cover == null) {
            song = songKey;
            setCoverNow(art);
        } else if (songKey.equals(pendingSong)) {
            pending = art; // a newer picture for the song that's on its way in
        } else if (songKey.equals(song)) {
            pending = null; // the same song (back from a blip, or a new copy of its cover)
            pendingSong = null;
            if (!sameArt(art, cover)) replaceCover(art);
        } else {
            pending = art; // a new song: change once the record is back in
            pendingSong = songKey;
        }
        start();
    }

    /**
     * The scene's colour: the sleeve glows in it, the light inside the sleeve is this colour, and the
     * record is pressed in it, so the LP shares the cover's primary colour with the light. Until it's
     * known, the record takes the cover's most colourful tone. The sleeve's light changes at once, but
     * a record only changes colour while it's back in its sleeve, out of sight: the next one comes out
     * in its own colour, like a different disc, rather than the one on show changing colour.
     */
    void setGlow(int colour) {
        setLight(new Scene(colour));
        wantTint = colour;
        if (slide == 0f) pressIn(colour);
        invalidate();
    }

    private void setLight(Scene scene) {
        light = scene;
        sleevePaint.setShadowLayer(18 * density, 0, 0, Scene.alpha(scene.deep, 0.45f));
        insideKey = "";
    }

    private void pressIn(int colour) {
        if (tint == colour) return;
        tint = colour;
        buildDisc();
    }

    /** The colour the record on show is pressed in (for tests). */
    int tint() {
        return tint;
    }

    /** Play or pause: the record spins, or slows to a stop. */
    void setPlaying(boolean nowPlaying) {
        if (playing == nowPlaying) return;
        playing = nowPlaying;
        start();
    }

    // ---- For tests ----

    boolean isPlaying() {
        return playing;
    }

    float slide() {
        return slide;
    }

    float speed() {
        return speed;
    }

    float angle() {
        return angle;
    }

    Drawable shownCover() {
        return cover;
    }

    boolean songChanging() {
        return pending != null;
    }

    // ---- Animation ----

    /** Moves the record on by {@code dt} seconds. */
    void step(float dt) {
        if (pending != null) {
            // A new song: stop, slide back into the sleeve, then change the cover.
            speed = approach(speed, 0f, dt, 0.25f);
            if (speed < 0.05f) {
                speed = 0f;
                slide = Math.max(0f, slide - dt / SLIDE_SECONDS);
                if (slide == 0f) {
                    song = pendingSong;
                    setCoverNow(pending);
                    pending = null;
                    pendingSong = null;
                }
            }
        } else if (playing) {
            // Out of the sleeve once the new cover has settled, then spin up.
            if (fade >= 1f && slide < 1f) slide = Math.min(1f, slide + dt / SLIDE_SECONDS);
            if (slide >= 1f) speed = approach(speed, 1f, dt, 0.5f);
        } else {
            speed = approach(speed, 0f, dt, 0.45f);
            if (speed < 0.002f) speed = 0f;
        }
        fade = Math.min(1f, fade + dt / FADE_SECONDS);
        angle = (angle + speed * RPM * 6 * dt) % 360f; // rpm × 360° / 60 s
        if (slide == 0f) pressIn(wantTint); // in its sleeve, out of sight: the next one out is in the new colour
    }

    private boolean moving() {
        return pending != null || fade < 1f || speed > 0f || (playing && slide < 1f);
    }

    private void start() {
        if (!running) {
            running = true;
            lastFrame = 0;
        }
        postInvalidateOnAnimation();
    }

    private static float approach(float value, float target, float dt, float seconds) {
        return target + (value - target) * (float) Math.exp(-dt / seconds);
    }

    private void setCoverNow(Drawable art) {
        previous = cover;
        cover = art;
        fade = previous == null ? 1f : 0f;
        buildDisc();
        invalidate();
    }

    /** The same song with a new copy of its cover: swap it in place, without any animation. */
    private void replaceCover(Drawable art) {
        cover = art;
        buildDisc();
        invalidate();
    }

    private static boolean sameArt(Drawable a, Drawable b) {
        if (a == b) return true;
        if (a instanceof BitmapDrawable && b instanceof BitmapDrawable) {
            return ((BitmapDrawable) a).getBitmap() == ((BitmapDrawable) b).getBitmap();
        }
        return false;
    }

    // ---- Drawing ----

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        float s = h;
        sleeve.set(w - s, 0, w, s); // square corners, like a real LP sleeve
        insideKey = "";
        buildDisc();
    }

    /**
     * The record (vinyl, grooves and label) for the current cover, its glint (the light caught in its
     * grooves), its halo and the light from the sleeve's mouth on it, made once per cover, size and colour.
     */
    private void buildDisc() {
        int h = getHeight();
        if (disc != null) disc.recycle();
        if (glint != null) glint.recycle();
        disc = glint = null;
        if (h == 0 || cover == null) return;
        float r = h * DISC / 2;
        int size = Math.round(r * 2);
        if (size <= 0) return;
        r = size / 2f;
        float dp = density;
        disc = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(disc);
        float cx = r;
        Scene pressed = new Scene(tint != 0 ? tint : vinylColour(cover));
        int vinyl = vinylFrom(pressed.deep);
        int lustre = Scene.mix(vinyl, pressed.hot, 0.6f);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        // Coloured vinyl, lit: deeper toward the middle, brightest near the edge.
        p.setShader(new RadialGradient(cx, cx, r,
                new int[]{darker(vinyl, 0.5f), darker(vinyl, 0.72f), vinyl, Scene.alpha(lustre, 0.95f)},
                new float[]{0f, 0.5f, 0.92f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cx, r, p);
        p.setShader(null);
        // Grooves: fine dark lines, each with a warm glint beside it, and bands where the pressing catches more light.
        float label = r * LABEL;
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(0.6f * dp);
        boolean dark = true;
        int ring = 0;
        for (float g = label + 3 * dp; g < r - 2 * dp; g += 1.6f * dp) {
            float band = 0.5f + 0.5f * (float) Math.sin(ring++ * 0.55f);
            p.setColor(dark ? 0x26000000 : Scene.alpha(lustre, 0.05f + 0.1f * band));
            c.drawCircle(cx, cx, g, p);
            dark = !dark;
        }
        // Two wider gaps between tracks, dark with a lit edge.
        for (float gap : new float[]{0.62f, 0.81f}) {
            p.setStrokeWidth(1.4f * dp);
            p.setColor(0x30000000);
            c.drawCircle(cx, cx, r * gap, p);
            p.setStrokeWidth(0.6f * dp);
            p.setColor(Scene.alpha(lustre, 0.3f));
            c.drawCircle(cx, cx, r * gap + 1.1f * dp, p);
        }
        p.setStrokeWidth(dp);
        p.setColor(Scene.alpha(lustre, 0.6f));
        c.drawCircle(cx, cx, r - 0.5f * dp, p); // the rim
        // The label: the cover art, cut round, lit like the cover on the sleeve: on a dark cover its bright parts
        // shine as if lit from within (a thin halo, then the light's colour), and the stage's light falls softly on it.
        c.save();
        Path round = new Path();
        round.addCircle(cx, cx, label, Path.Direction.CW);
        c.clipPath(round);
        RectF face = new RectF(cx - label, cx - label, cx + label, cx + label);
        drawArt(c, cover, face, 255);
        if (shineFrom != cover) makeShine();
        if (shine != null && shineStrength > 0f) {
            Paint lit = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            lit.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SCREEN));
            lit.setColor(Scene.alpha(pressed.deep, 0.45f * shineStrength));
            drawMask(c, shineHalo, face, lit);
            lit.setXfermode(null);
            lit.setColor(Scene.alpha(Scene.gain(pressed.deep, 1.3f), ART_TINT * shineStrength));
            drawMask(c, shine, face, lit);
        }
        Paint warm = new Paint();
        warm.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SCREEN));
        warm.setColor(Scene.alpha(pressed.deep, ART_WASH));
        c.drawRect(face, warm);
        c.restore();
        p.setColor(0x40000000);
        c.drawCircle(cx, cx, label, p);
        // The spindle hole.
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFF05070B);
        c.drawCircle(cx, cx, Math.max(2 * dp, r * 0.03f), p);

        // The glint: warm light caught in the grooves, in two soft wedges (the key light from above, and the wall's
        // light from below, on the side out of the sleeve), plus a broad soft sheen in the same places, over the label too.
        glint = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas g = new Canvas(glint);
        Paint q = new Paint(Paint.ANTI_ALIAS_FLAG);
        q.setStyle(Paint.Style.FILL);
        q.setColor(Scene.alpha(pressed.hot, 0.12f));
        g.drawCircle(cx, cx, r - 0.5f * dp, q);
        q.setStyle(Paint.Style.STROKE);
        q.setStrokeWidth(0.7f * dp);
        boolean bright = true;
        for (float at = label + 3.8f * dp; at < r - 1.5f * dp; at += 1.6f * dp) {
            q.setColor(Scene.alpha(pressed.hot, bright ? 0.6f : 0.3f));
            g.drawCircle(cx, cx, at, q);
            bright = !bright;
        }
        q.setStyle(Paint.Style.FILL);
        q.setShader(new SweepGradient(cx, cx,
                new int[]{0x00000000, 0x55000000, 0x00000000, 0x00000000, 0xCC000000, 0x00000000, 0x00000000,
                        0xFF000000, 0x00000000, 0x00000000},
                new float[]{0f, 0.08f, 0.16f, 0.27f, 0.37f, 0.47f, 0.52f, 0.6f, 0.7f, 1f}));
        q.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        g.drawRect(0, 0, size, size, q);

        // The halo: a bright rim of light at the record's edge, fading quickly out into the haze around it.
        float reach = r * HALO;
        haloPaint.setShader(new RadialGradient(0, 0, reach,
                new int[]{0, 0, Scene.alpha(pressed.hot, 0.4f), pressed.hot, Scene.alpha(pressed.deep, 0.7f),
                        Scene.alpha(pressed.deep, 0.18f), pressed.deep & 0x00FFFFFF},
                new float[]{0f, 0.9f / HALO, 0.975f / HALO, 1f / HALO, 1.025f / HALO, 1.09f / HALO, 1f},
                Shader.TileMode.CLAMP));
        // The light from the sleeve's mouth, across the record's vinyl: from the mouth (x = 0 here, moved there as
        // it's drawn) out to the left, near white where it comes out.
        vinylRing.rewind();
        vinylRing.setFillType(Path.FillType.EVEN_ODD);
        vinylRing.addCircle(0, 0, r - 0.5f * dp, Path.Direction.CW);
        vinylRing.addCircle(0, 0, label, Path.Direction.CW);
        labelDisc.rewind();
        labelDisc.addCircle(0, 0, label, Path.Direction.CW);
        labelLightPaint.setShader(new LinearGradient(0, 0, -r * MOUTH, 0,
                new int[]{Scene.alpha(pressed.deep, 0.65f), Scene.alpha(pressed.deep, 0.25f),
                        Scene.alpha(pressed.deep, 0.07f), pressed.deep & 0x00FFFFFF},
                new float[]{0f, 0.22f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        int white = Scene.mix(pressed.hot, pressed.led, 0.55f);
        mouthPaint.setShader(new LinearGradient(0, 0, -r * MOUTH, 0,
                new int[]{white, Scene.alpha(pressed.hot, 0.55f), Scene.alpha(pressed.hot, 0.15f),
                        pressed.hot & 0x00FFFFFF},
                new float[]{0f, 0.22f, 0.55f, 1f}, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        long now = AnimationUtils.currentAnimationTimeMillis();
        float dt = lastFrame == 0 ? 0f : Math.min(0.05f, (now - lastFrame) / 1000f);
        lastFrame = now;
        step(dt);

        float s = sleeve.height();
        float r = s * DISC / 2;
        if (disc != null && slide > 0f) {
            float eased = slide * slide * (3 - 2 * slide);
            float cx = sleeve.centerX() + (sleeve.left - sleeve.centerX()) * eased;
            float cy = sleeve.centerY();
            canvas.save();
            canvas.translate(cx, cy);
            canvas.save();
            canvas.rotate(angle);
            canvas.drawBitmap(disc, -disc.getWidth() / 2f, -disc.getHeight() / 2f, discPaint);
            canvas.restore();
            // The light in the grooves stays where the light is while the record turns.
            canvas.drawBitmap(glint, -glint.getWidth() / 2f, -glint.getHeight() / 2f, screenPaint);
            // The light from inside the sleeve falls on the record where it comes out, brightest nearest the mouth.
            shift.setTranslate(sleeve.left - cx, 0);
            mouthPaint.getShader().setLocalMatrix(shift);
            canvas.drawPath(vinylRing, mouthPaint);
            labelLightPaint.getShader().setLocalMatrix(shift);
            canvas.drawPath(labelDisc, labelLightPaint);
            // The halo around its edge, out in the haze as it comes out of the sleeve.
            haloPaint.setAlpha(Math.round(255 * eased));
            canvas.drawCircle(0, 0, r * HALO, haloPaint);
            canvas.restore();
        }

        // The sleeve on top of the record, with only a soft glow around it: its light is inside.
        insideLight();
        canvas.drawRect(sleeve, sleevePaint);
        canvas.save();
        canvas.clipRect(sleeve);
        if (previous != null && fade < 1f) drawArt(canvas, previous, sleeve, Math.round(255 * (1 - fade)));
        if (cover != null) {
            drawArt(canvas, cover, sleeve, Math.round(255 * fade));
            // A dark cover lit from within: its bright parts shine in the light's colour, with a thin halo hugging them.
            if (shineFrom != cover) makeShine();
            if (shine != null && shineStrength > 0f) {
                screenPaint.setColor(Scene.alpha(light.deep, 0.45f * shineStrength * fade));
                drawMask(canvas, shineHalo, sleeve, screenPaint);
                screenPaint.setColor(0xFFFFFFFF);
                shinePaint.setColor(Scene.alpha(Scene.gain(light.deep, 1.3f), ART_TINT * shineStrength * fade));
                drawMask(canvas, shine, sleeve, shinePaint);
            }
        }
        // The stage's light falls softly on the cover, and the light inside shows most by the mouth.
        washPaint.setColor(Scene.alpha(light.deep, ART_WASH * fade));
        canvas.drawRect(sleeve, washPaint);
        canvas.drawRect(sleeve, insidePaint);
        canvas.restore();
        // A faint rim around the sleeve, and a line of light along its mouth, where the light comes out.
        edgePaint.setColor(Scene.alpha(Scene.mix(light.deep, light.hot, 0.3f), 0.28f));
        canvas.drawRect(sleeve, edgePaint);
        seamPaint.setAlpha(Math.round(255 * (0.55f + 0.45f * (disc != null ? slide : 0f))));
        canvas.drawRect(sleeve.left - 0.6f * density, sleeve.top, sleeve.left + 0.6f * density, sleeve.bottom, seamPaint);

        if (moving() && getVisibility() == VISIBLE) {
            postInvalidateOnAnimation();
        } else {
            running = false;
        }
    }

    /** The light inside the sleeve, by its mouth: on the cover, and the seam of light along the mouth. */
    private void insideLight() {
        String key = sleeve + "|" + light.key;
        if (key.equals(insideKey)) return;
        insideKey = key;
        float s = sleeve.width();
        insidePaint.setShader(new LinearGradient(sleeve.left, 0, sleeve.left + s * 0.28f, 0,
                new int[]{Scene.alpha(light.deep, 0.7f), Scene.alpha(light.deep, 0.24f), Scene.alpha(light.deep, 0.06f),
                        light.deep & 0x00FFFFFF},
                new float[]{0f, 0.12f, 0.4f, 1f}, Shader.TileMode.CLAMP));
        int bright = Scene.mix(light.hot, light.led, 0.35f);
        seamPaint.setShader(new LinearGradient(0, sleeve.top, 0, sleeve.bottom,
                new int[]{Scene.alpha(light.hot, 0.35f), bright, Scene.alpha(light.hot, 0.35f)}, null,
                Shader.TileMode.CLAMP));
    }

    /**
     * The cover's light: its bright parts (a sharp mask) and their thin halo (the mask a little
     * blurred). Only a picture shines; an app icon standing in for a cover doesn't.
     */
    private void makeShine() {
        shineFrom = cover;
        shine = shineHalo = null;
        shineStrength = 0f;
        if (!(cover instanceof BitmapDrawable)) return;
        try {
            Bitmap art = ((BitmapDrawable) cover).getBitmap();
            if (art == null) return;
            if (art.getConfig() == Bitmap.Config.HARDWARE) art = art.copy(Bitmap.Config.ARGB_8888, false);
            int w = art.getWidth(), h = art.getHeight();
            if (w <= 0 || h <= 0) return;
            float f = Math.min(1f, 256f / Math.max(w, h));
            if (f < 1f) {
                art = Bitmap.createScaledBitmap(art, Math.max(1, Math.round(w * f)), Math.max(1, Math.round(h * f)), true);
                w = art.getWidth();
                h = art.getHeight();
            }
            int[] px = new int[w * h];
            art.getPixels(px, 0, w, 0, 0, w, h);
            float[] lit = new float[w * h];
            int[] out = new int[w * h];
            float mean = 0f;
            for (int i = 0; i < px.length; i++) {
                int c = px[i];
                float l = (0.3f * ((c >> 16) & 0xFF) + 0.59f * ((c >> 8) & 0xFF) + 0.11f * (c & 0xFF)) / 255f;
                mean += l;
                lit[i] = Math.max(0f, Math.min(1f, (l - 0.22f) / 0.3f));
                out[i] = Math.round(lit[i] * 255) << 24;
            }
            mean /= px.length;
            shineStrength = Math.max(0f, Math.min(1f, (0.42f - mean) / 0.25f));
            shine = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8);
            shine.setPixels(out, 0, w, 0, 0, w, h);
            int radius = Math.max(1, Math.round(Math.max(w, h) * 0.008f));
            for (int pass = 0; pass < 3; pass++) {
                blur(lit, w, h, radius, true);
                blur(lit, w, h, radius, false);
            }
            for (int i = 0; i < lit.length; i++) out[i] = Math.round(Math.min(1f, lit[i] * 1.3f) * 255) << 24;
            shineHalo = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8);
            shineHalo.setPixels(out, 0, w, 0, 0, w, h);
        } catch (RuntimeException ignored) {
            shine = shineHalo = null;
            shineStrength = 0f;
        }
    }

    /** One pass of a box blur, {@code 2 radius + 1} wide, along the rows ({@code across}) or the columns of a w × h picture. */
    private static void blur(float[] v, int w, int h, int radius, boolean across) {
        int lines = across ? h : w, length = across ? w : h;
        float[] line = new float[length];
        float n = 2 * radius + 1;
        for (int a = 0; a < lines; a++) {
            for (int i = 0; i < length; i++) line[i] = v[across ? a * w + i : i * w + a];
            float sum = 0f;
            for (int k = -radius; k <= radius; k++) sum += line[Math.max(0, Math.min(length - 1, k))];
            for (int i = 0; i < length; i++) {
                v[across ? a * w + i : i * w + a] = sum / n;
                sum += line[Math.min(length - 1, i + radius + 1)] - line[Math.max(0, i - radius)];
            }
        }
    }

    /** Draws a cover into a square: a picture fills it (cropped), an app icon sits in the middle. */
    private void drawArt(Canvas canvas, Drawable art, RectF into, int alpha) {
        int iw = art.getIntrinsicWidth(), ih = art.getIntrinsicHeight();
        if (art instanceof BitmapDrawable && iw > 0 && ih > 0) {
            float scale = Math.max(into.width() / iw, into.height() / ih);
            float w = iw * scale, h = ih * scale;
            bounds.set(Math.round(into.centerX() - w / 2), Math.round(into.centerY() - h / 2),
                    Math.round(into.centerX() + w / 2), Math.round(into.centerY() + h / 2));
        } else {
            float inset = into.width() * 0.22f;
            bounds.set(Math.round(into.left + inset), Math.round(into.top + inset),
                    Math.round(into.right - inset), Math.round(into.bottom - inset));
        }
        art.setBounds(bounds);
        art.setAlpha(alpha);
        art.draw(canvas);
        art.setAlpha(255);
    }

    /** Draws one of the cover's light masks over it, cropped just as the cover is. */
    private void drawMask(Canvas canvas, Bitmap mask, RectF into, Paint paint) {
        int iw = mask.getWidth(), ih = mask.getHeight();
        if (iw <= 0 || ih <= 0) return;
        float scale = Math.max(into.width() / iw, into.height() / ih);
        float w = iw * scale, h = ih * scale;
        dst.set(into.centerX() - w / 2, into.centerY() - h / 2, into.centerX() + w / 2, into.centerY() + h / 2);
        canvas.drawBitmap(mask, null, dst, paint);
    }

    /** The record's colour: the cover's most colourful tone, as a deep coloured vinyl. */
    private static int vinylColour(Drawable art) {
        Bitmap small = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888);
        Drawable copy = AppRepository.copy(art);
        copy.setBounds(0, 0, 16, 16);
        copy.draw(new Canvas(small));
        float[] hsv = new float[3];
        float best = -1f;
        int pick = 0xFF3A4560; // Worldly's slate for grey covers
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                int px = small.getPixel(x, y);
                if (Color.alpha(px) < 200) continue;
                Color.colorToHSV(px, hsv);
                float score = hsv[1] * (0.3f + hsv[2]);
                if (hsv[1] > 0.25f && hsv[2] > 0.15f && score > best) {
                    best = score;
                    pick = px;
                }
            }
        }
        small.recycle();
        Color.colorToHSV(pick, hsv);
        hsv[1] = Math.min(1f, Math.max(0.45f, hsv[1]));
        hsv[2] = 0.55f;
        return Color.HSVToColor(0xF2, hsv); // slightly see-through, like coloured vinyl
    }

    /** A rich coloured vinyl in this colour's hue, bright as if lit from within (silver for a light with no colour). */
    private static int vinylFrom(int colour) {
        float[] hsv = new float[3];
        Color.colorToHSV(colour, hsv);
        boolean none = hsv[1] < 0.3f;
        hsv[1] = none ? hsv[1] : Math.min(0.95f, Math.max(0.88f, hsv[1]));
        hsv[2] = none ? 0.72f : 0.94f;
        return Color.HSVToColor(0xF2, hsv); // slightly see-through, like coloured vinyl
    }

    private static int darker(int colour, float f) {
        return Color.argb(Color.alpha(colour), Math.round(Color.red(colour) * f),
                Math.round(Color.green(colour) * f), Math.round(Color.blue(colour) * f));
    }
}
