package com.camdeco.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
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
 * The record is tinted from the cover, with fine grooves, and has the cover art on its label, so
 * the spin shows; a fixed sheen of light across it makes it read as vinyl.
 */
public class RecordView extends View {
    static final float RPM = 100f / 3; // 33⅓
    /** Seconds the record takes to slide all the way out of the sleeve, or back in. */
    static final float SLIDE_SECONDS = 0.6f;
    private static final float FADE_SECONDS = 0.35f;
    /** The record's size, as a share of the sleeve's. */
    private static final float DISC = 0.94f;

    private final float density;
    private final Paint sleevePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint discPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint sheenPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF sleeve = new RectF();
    private final Rect bounds = new Rect();
    private final Path sleeveClip = new Path();

    /** The cover on the sleeve, the one fading out, and the one waiting for the record to go back in. */
    private Drawable cover;
    private Drawable previous;
    private Drawable pending;
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

    public RecordView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = context.getResources().getDisplayMetrics().density;
        sleevePaint.setColor(0xFF1C2230);
        sleevePaint.setShadowLayer(18 * density, 0, 8 * density, 0x99000000);
        edgePaint.setStyle(Paint.Style.STROKE);
        edgePaint.setStrokeWidth(density);
        edgePaint.setColor(0x1AFFFFFF);
    }

    /** Puts this cover on the sleeve (the record comes out while {@code nowPlaying}). */
    void show(Drawable art, boolean nowPlaying) {
        playing = nowPlaying;
        if (cover == null) {
            setCoverNow(art);
        } else if (!sameArt(art, pending != null ? pending : cover)) {
            pending = sameArt(art, cover) ? null : art; // a new song: change once the record is back in
        }
        start();
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
                    setCoverNow(pending);
                    pending = null;
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
        sleeve.set(w - s, 0, w, s);
        sleeveClip.rewind();
        sleeveClip.addRoundRect(sleeve, 6 * density, 6 * density, Path.Direction.CW);
        // A fixed sheen: two soft bands of light across the record, opposite each other.
        sheenPaint.setShader(new SweepGradient(0, 0,
                new int[]{0x00FFFFFF, 0x24FFFFFF, 0x00FFFFFF, 0x00FFFFFF, 0x1AFFFFFF, 0x00FFFFFF, 0x00FFFFFF},
                new float[]{0f, 0.12f, 0.2f, 0.54f, 0.62f, 0.7f, 1f}));
        buildDisc();
    }

    private void buildDisc() {
        int h = getHeight();
        if (disc != null) disc.recycle();
        disc = null;
        if (h == 0 || cover == null) return;
        float r = h * DISC / 2;
        int size = Math.round(r * 2);
        disc = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(disc);
        float cx = size / 2f;
        int vinyl = vinylColour(cover);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        // Coloured vinyl, a little darker toward the middle.
        p.setShader(new RadialGradient(cx, cx, r, new int[]{darker(vinyl, 0.55f), darker(vinyl, 0.8f), vinyl},
                new float[]{0f, 0.6f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cx, r, p);
        p.setShader(null);
        // Grooves, and two wider gaps between tracks.
        float label = r * 0.34f;
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(0.6f * density);
        boolean dark = true;
        for (float g = label + 3 * density; g < r - 2 * density; g += 1.6f * density) {
            p.setColor(dark ? 0x22000000 : 0x0AFFFFFF);
            c.drawCircle(cx, cx, g, p);
            dark = !dark;
        }
        p.setStrokeWidth(1.2f * density);
        p.setColor(0x14FFFFFF);
        c.drawCircle(cx, cx, r * 0.62f, p);
        c.drawCircle(cx, cx, r * 0.81f, p);
        p.setStrokeWidth(density);
        p.setColor(0x1FFFFFFF);
        c.drawCircle(cx, cx, r - 0.5f * density, p); // the rim
        // The label: the cover art, cut round.
        c.save();
        Path round = new Path();
        round.addCircle(cx, cx, label, Path.Direction.CW);
        c.clipPath(round);
        drawArt(c, cover, new RectF(cx - label, cx - label, cx + label, cx + label), 255);
        c.restore();
        p.setColor(0x40000000);
        c.drawCircle(cx, cx, label, p);
        // The spindle hole.
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFF0B1322);
        c.drawCircle(cx, cx, Math.max(2 * density, r * 0.03f), p);
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
            canvas.drawCircle(0, 0, r, sheenPaint);
            canvas.restore();
        }

        // The sleeve, with its shadow, on top of the record.
        canvas.drawRoundRect(sleeve, 6 * density, 6 * density, sleevePaint);
        canvas.save();
        canvas.clipPath(sleeveClip);
        if (previous != null && fade < 1f) drawArt(canvas, previous, sleeve, Math.round(255 * (1 - fade)));
        if (cover != null) drawArt(canvas, cover, sleeve, Math.round(255 * fade));
        canvas.restore();
        canvas.drawRoundRect(sleeve, 6 * density, 6 * density, edgePaint);

        if (moving() && getVisibility() == VISIBLE) {
            postInvalidateOnAnimation();
        } else {
            running = false;
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

    private static int darker(int colour, float f) {
        return Color.argb(Color.alpha(colour), Math.round(Color.red(colour) * f),
                Math.round(Color.green(colour) * f), Math.round(Color.blue(colour) * f));
    }
}
