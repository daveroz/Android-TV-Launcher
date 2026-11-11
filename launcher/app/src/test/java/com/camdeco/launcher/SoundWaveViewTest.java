package com.camdeco.launcher;

import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Looper;
import android.view.View;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.time.Duration;

/** The audio visualizer's light panel: bass lights the left columns, voices the middle, treble the right. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SoundWaveViewTest {
    private SoundWave wave;
    private SoundWaveView view;
    private Bitmap frame;

    @Test
    public void bassLightsTheLeftColumns() {
        play(60, 1, 1, 45);
        int bass = litRows(40, 200), voice = litRows(400, 3000), treble = litRows(5000, 16000);
        assertTrue("bass " + bass + " voice " + voice + " treble " + treble, bass >= 5 && bass > 2 * voice && bass > 2 * treble);
    }

    @Test
    public void voicesLightTheMiddle() {
        play(2, 30, 1, 45);
        int bass = litRows(40, 200), voice = litRows(400, 3000), treble = litRows(5000, 16000);
        assertTrue("bass " + bass + " voice " + voice + " treble " + treble, voice >= 4 && voice > 2 * bass && voice > treble);
    }

    @Test
    public void trebleLightsTheRight() {
        play(6, 3, 3, 45);
        int bass = litRows(40, 200), treble = litRows(5000, 16000);
        assertTrue("bass " + bass + " treble " + treble, treble > bass);
    }

    @Test
    public void columnsRiseQuicklyAndSettleGently() {
        int column = SoundWave.columnOf(100);
        play(60, 1, 1, 9); // about 0.15 s of a loud bass
        float loud = view.level(column);
        assertTrue("rises within a moment: " + loud, loud > 0.6f);
        wave.onFftDataCapture(null, new byte[1024], 44100000); // then silence
        run(9);
        assertTrue("still settling a moment later: " + view.level(column), view.level(column) > 0.3f);
        run(150);
        assertTrue("dark after a while: " + view.level(column), view.level(column) < 0.05f);
    }

    /** Runs the panel for {@code frames} frames with music of this make-up. */
    private void play(int bass, int voice, int treble, int frames) {
        Context app = RuntimeEnvironment.getApplication();
        wave = new SoundWave(app);
        wave.onFftDataCapture(null, fft(bass, voice, treble), 44100000);
        view = new SoundWaveView(app, null);
        view.setSource(wave);
        float dp = app.getResources().getDisplayMetrics().density;
        int w = Math.round(688 * dp), h = Math.round(176 * dp);
        view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, w, h);
        frame = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        run(frames);
    }

    private void run(int frames) {
        for (int i = 0; i < frames; i++) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16));
            frame.eraseColor(0);
            view.draw(new Canvas(frame));
        }
    }

    /**
     * How many rows of lamps are lit, at most, in the music's columns between these frequencies.
     * The ranges the tests use stay clear of the 250 Hz and 4 kHz edges, where a column holds a bit
     * of both.
     */
    private int litRows(double fromHz, double toHz) {
        float pitch = view.pitch();
        int from = SoundWave.columnOf(fromHz * 1.01), to = SoundWave.columnOf(toHz * 0.99);
        int top = frame.getHeight();
        for (int c = 0; c < view.columns(); c++) {
            int band = view.bandOfColumn(c);
            if (band < from || band > to || view.tailColumn(c)) continue;
            int x = Math.round(c * pitch + pitch / 2); // the middle of the column's lamps
            for (int y = 0; y < frame.getHeight(); y++) {
                int p = frame.getPixel(x, y);
                // Lit lamps are bright; the unlit embers and the glow are far fainter.
                boolean lit = Color.alpha(p) > 60 && (Color.red(p) + Color.green(p) + Color.blue(p)) / 3 > 110;
                if (lit) {
                    top = Math.min(top, y);
                    break;
                }
            }
        }
        if (top == frame.getHeight()) return 0;
        return Math.round((frame.getHeight() - top) / pitch);
    }

    @Test
    public void theLampsAreTheLightShowsSquaresInStrips() {
        play(60, 30, 30, 45);
        float dp = RuntimeEnvironment.getApplication().getResources().getDisplayMetrics().density;
        assertTrue("10 rows, as tall as the cover", view.rows() == 10);
        assertTrue("squares of 17.6dp: " + view.pitch() / dp, Math.abs(view.pitch() / dp - 17.6f) < 0.1f);
        for (int c = 0; c < view.columns(); c++) {
            if (c % 4 == 3) assertTrue("a dark column between strips", view.bandOfColumn(c) < 0);
            else assertTrue(view.bandOfColumn(c) >= 0);
        }
        // Bass on the left, treble on the right.
        int first = view.bandOfColumn(0), lastMusic = 0;
        for (int c = 0; c < view.columns(); c++) if (view.bandOfColumn(c) >= 0 && !view.tailColumn(c)) lastMusic = view.bandOfColumn(c);
        assertTrue(first == 0 && lastMusic == SoundWave.COLUMNS - 1);
    }

    @Test
    public void theLastStripTrailsOffAsItReachesTheCover() {
        play(60, 30, 30, 45);
        int lastMusic = -1;
        java.util.List<Integer> tail = new java.util.ArrayList<>();
        for (int c = 0; c < view.columns(); c++) {
            if (view.bandOfColumn(c) < 0) continue;
            if (view.tailColumn(c)) tail.add(c);
            else lastMusic = c;
        }
        assertTrue("one strip of tail", tail.size() == 3);
        int music = brightest(lastMusic), t0 = brightest(tail.get(0)), t2 = brightest(tail.get(2));
        assertTrue("then fading: " + music + " > " + t0 + " > " + t2, t0 < music && t2 < t0);
        assertTrue("nearly just an ember at the cover: " + t2, t2 < 120);
    }

    @Test
    public void eachColumnsTopIsALittleSofter() {
        Context app = RuntimeEnvironment.getApplication();
        view = new SoundWaveView(app, null);
        float dp = app.getResources().getDisplayMetrics().density;
        int w = Math.round(688 * dp), h = Math.round(176 * dp);
        view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, w, h);
        // Two columns, both behind the text: one exactly 5 lamps tall, one 9. Row 4 is the top of
        // the first, the middle of the second.
        int a = 4, b = 8; // the first column of the second and third strips
        view.showForTests(view.bandOfColumn(a), 5f / view.rows());
        view.showForTests(view.bandOfColumn(b), 9f / view.rows());
        frame = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(frame));
        float pitch = view.pitch();
        int y = Math.round(h - pitch * 4 - pitch / 2);
        int tip = Color.alpha(frame.getPixel(Math.round(a * pitch + pitch / 2), y));
        int middle = Color.alpha(frame.getPixel(Math.round(b * pitch + pitch / 2), y));
        assertTrue("the same lamp is softer at the top of a column: " + tip + " vs " + middle,
                middle > 100 && tip > 0.45f * middle && tip < 0.8f * middle);
    }

    @Test
    public void unlitLampsKeepAFaintEmber() {
        Context app = RuntimeEnvironment.getApplication();
        view = new SoundWaveView(app, null);
        float dp = app.getResources().getDisplayMetrics().density;
        int w = Math.round(688 * dp), h = Math.round(176 * dp);
        view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, w, h);
        frame = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(frame)); // silence
        float pitch = view.pitch();
        int lampAlpha = Color.alpha(frame.getPixel(Math.round(pitch / 2), Math.round(h - pitch / 2)));
        int gapAlpha = Color.alpha(frame.getPixel(Math.round(3 * pitch + pitch / 2), Math.round(h - pitch / 2)));
        assertTrue("a faint ember: " + lampAlpha, lampAlpha > 5 && lampAlpha < 30);
        assertTrue("the gap between strips is dark: " + gapAlpha, gapAlpha < lampAlpha);
    }

    /** The strongest opacity anywhere in this column. */
    private int brightest(int column) {
        int x = Math.round(column * view.pitch() + view.pitch() * 0.42f);
        int best = 0;
        for (int y = 0; y < frame.getHeight(); y++) best = Math.max(best, Color.alpha(frame.getPixel(x, y)));
        return best;
    }

    /** Music as the visualizer sees it, with this much bass (to 250 Hz), voice (to 4 kHz) and treble. */
    private static byte[] fft(int bass, int voice, int treble) {
        byte[] fft = new byte[1024];
        double binHz = 44100.0 / fft.length;
        for (int k = 1; k < fft.length / 2; k++) {
            double hz = k * binHz;
            byte v = (byte) (hz <= 250 ? bass : hz <= 4000 ? voice : treble);
            fft[2 * k] = v;
            fft[2 * k + 1] = v;
        }
        return fft;
    }
}
