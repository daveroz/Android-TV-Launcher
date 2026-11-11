package com.camdeco.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/** An app's light show: a wall of square lamps played in strips, like LED panels on a stage. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "w960dp-h540dp-land-television-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class LightShowViewTest {
    private LightShowView view;
    private Bitmap frame;

    @Before
    public void setUp() {
        Context app = RuntimeEnvironment.getApplication();
        view = new LightShowView(app, null);
        float dp = app.getResources().getDisplayMetrics().density;
        int w = Math.round(960 * dp), h = Math.round(540 * dp);
        view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, w, h);
        frame = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
    }

    private void draw() {
        frame.eraseColor(0);
        view.draw(new Canvas(frame));
    }

    @Test
    public void theSquaresStayTheSameSizeAndReachFromMarginToMargin() {
        float dp = RuntimeEnvironment.getApplication().getResources().getDisplayMetrics().density;
        float[] b = view.showBounds();
        assertEquals("columns of 17.6dp", 17.6f, (b[2] - b[0]) / dp / view.columnsForTests(), 0.05f);
        assertEquals("10 rows, as tall as a cover", 176f, (b[3] - b[1]) / dp, 0.5f);
        assertEquals("from the left margin", 48f, b[0] / dp, 17.6f);
        assertEquals("to the right margin", 960f - 48f, b[2] / dp, 0.5f);
    }

    @Test
    public void panelsPlayPatternsThatChange() {
        int lit = 0;
        String before = null;
        boolean changed = false;
        for (int i = 0; i < 40; i++) {
            view.advanceForTests(0.5f);
            draw();
            StringBuilder state = new StringBuilder();
            for (int r = 0; r < 10; r++) {
                for (int c = 0; c < view.columnsForTests(); c++) {
                    float v = view.lampForTests(c, r);
                    if (v > 0.5f) lit++;
                    state.append(v > 0.5f ? '#' : '.');
                    if (c % 4 == 0) assertEquals("the column between strips stays dark", 0f, v, 0f);
                }
            }
            if (before != null && !before.equals(state.toString())) changed = true;
            before = state.toString();
        }
        assertTrue("lamps light up: " + lit, lit > 100);
        assertTrue("the patterns move on", changed);
    }

    @Test
    public void lampsBehindTheTextAreDimmer() {
        float brightLeft = 0f, brightRight = 0f;
        int columns = view.columnsForTests();
        for (int i = 0; i < 60; i++) {
            view.advanceForTests(0.37f);
            draw();
            for (int r = 0; r < 10; r++) {
                for (int c = 1; c < 8; c++) brightLeft = Math.max(brightLeft, view.lampForTests(c, r));
                for (int c = columns - 8; c < columns; c++) brightRight = Math.max(brightRight, view.lampForTests(c, r));
            }
        }
        assertTrue("dimmer behind the text: " + brightLeft + " vs " + brightRight, brightLeft < 0.6f * brightRight);
        assertTrue("but still lit: " + brightLeft, brightLeft > 0.3f);
    }

    @Test
    public void itsLitInTheScenesColour() {
        view.setScene(new Scene(0xFFE50914));
        assertEquals(new Scene(0xFFE50914).key, view.keyColour());
        assertNotEquals(Scene.promise().key, view.keyColour());
    }

    // ---- Following the sound ----

    /** Plays this much bass (to 250 Hz), voice (to 4 kHz) and treble for a while, following the sound. */
    private void play(int bass, int voice, int treble, int frames) {
        SoundWave wave = new SoundWave(RuntimeEnvironment.getApplication());
        wave.onFftDataCapture(null, fft(bass, voice, treble), 44100000);
        view.setSource(wave);
        view.setReactive(true);
        for (int i = 0; i < frames; i++) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(16));
            draw();
        }
    }

    /** The tallest lit column (in lamps) among the music's columns for these frequencies. */
    private int tallest(double fromHz, double toHz) {
        int from = SoundWave.columnOf(fromHz * 1.01), to = SoundWave.columnOf(toHz * 0.99);
        int best = 0;
        for (int c = 0; c < view.columnsForTests(); c++) {
            int band = view.bandOfColumn(c);
            if (band < from || band > to) continue;
            int lit = 0;
            for (int r = 0; r < 10; r++) if (view.lampForTests(c, r) > 0.35f) lit++;
            best = Math.max(best, lit);
        }
        return best;
    }

    @Test
    public void whileMusicPlaysBassLightsTheLeftVoicesTheMiddleTrebleTheRight() {
        play(60, 1, 1, 60);
        assertTrue("bass: " + tallest(40, 200) + " vs voice " + tallest(400, 3000), tallest(40, 200) >= 5 && tallest(40, 200) > 2 * tallest(400, 3000));
        play(2, 30, 1, 60);
        assertTrue("voice: " + tallest(400, 3000) + " vs bass " + tallest(40, 200), tallest(400, 3000) >= 4 && tallest(400, 3000) > tallest(40, 200));
        play(6, 3, 3, 60);
        assertTrue("treble: " + tallest(5000, 16000) + " vs bass " + tallest(40, 200), tallest(5000, 16000) > tallest(40, 200));
        // Bass on the left edge, treble at the right edge.
        assertEquals(0, view.bandOfColumn(1));
        assertEquals(SoundWave.COLUMNS - 1, view.bandOfColumn(lastLampColumn()));
    }

    @Test
    public void withAnLpTheMusicEndsWhereTheRecordStarts() {
        view.setRecordShown(true);
        int lastMusic = -1;
        for (int c = 0; c < view.columnsForTests(); c++) if (view.bandOfColumn(c) == SoundWave.COLUMNS - 1) { lastMusic = c; break; }
        float dp = RuntimeEnvironment.getApplication().getResources().getDisplayMetrics().density;
        float x = view.showBounds()[0] + (lastMusic + 1) * view.pitch();
        assertTrue("the treble ends before the record: " + x / dp, x / dp < 960 - 48 - 176 * 1.47f);
        assertTrue("and the columns behind it echo the music", lastLampColumn() > lastMusic);
        assertTrue(view.bandOfColumn(lastLampColumn()) < SoundWave.COLUMNS - 1);
        view.setRecordShown(false);
        assertEquals(SoundWave.COLUMNS - 1, view.bandOfColumn(lastLampColumn()));
    }

    @Test
    public void columnsRiseQuicklyAndSettleGently() {
        int band = SoundWave.columnOf(100);
        play(60, 1, 1, 9); // about 0.15 s of a loud bass
        assertTrue("rises within a moment: " + view.level(band), view.level(band) > 0.6f);
        view.setSource(null); // silence
        for (int i = 0; i < 9; i++) draw16();
        assertTrue("still settling a moment later: " + view.level(band), view.level(band) > 0.3f);
        for (int i = 0; i < 150; i++) draw16();
        assertTrue("dark after a while: " + view.level(band), view.level(band) < 0.05f);
    }

    @Test
    public void eachColumnsTopLampIsALittleSofter() {
        view.setReactive(true);
        int a = 1, b = 5; // the first column of the first two strips: bands 0 and a little higher
        view.showForTests(view.bandOfColumn(a), 5f / 10);
        view.showForTests(view.bandOfColumn(b), 9f / 10);
        draw();
        // Row 5 from the top is the top lamp of the first column, the middle of the second.
        float tip = view.lampForTests(a, 5), middle = view.lampForTests(b, 5);
        assertTrue("softer at the top: " + tip + " vs " + middle, middle > 0.3f && tip > 0.45f * middle && tip < 0.8f * middle);
    }

    /** The rightmost column with lamps (the very last may be a dark gap). */
    private int lastLampColumn() {
        int c = view.columnsForTests() - 1;
        while (c > 0 && view.bandOfColumn(c) < 0) c--;
        return c;
    }

    @Test
    public void pausedItGoesBackToItsPatterns() {
        play(60, 1, 1, 60);
        assertTrue(view.bass() > 0.3f);
        view.setReactive(false);
        for (int i = 0; i < 60; i++) draw16();
        assertEquals("no sound on the wall", 0f, view.bass(), 0.01f);
    }

    private void draw16() {
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(16));
        draw();
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
