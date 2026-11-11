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
     * How many rows of cells are lit, at most, in the columns between these frequencies. The ranges
     * the tests use stay clear of the 250 Hz and 4 kHz edges, where a column holds a bit of both.
     */
    private int litRows(double fromHz, double toHz) {
        float pitch = view.pitch();
        int from = SoundWave.columnOf(fromHz * 1.01), to = SoundWave.columnOf(toHz * 0.99);
        int top = frame.getHeight();
        for (int c = from; c <= to; c++) {
            int x = Math.round(c * pitch + pitch * 0.42f); // the middle of the column's cells
            for (int y = 0; y < frame.getHeight(); y++) {
                int p = frame.getPixel(x, y);
                // Lit cells are see-through but bright; the unlit grid and the glow are far fainter.
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
    public void theGridTrailsOffAsItReachesTheCover() {
        play(60, 30, 30, 45);
        float dp = RuntimeEnvironment.getApplication().getResources().getDisplayMetrics().density;
        assertTrue("about 14 rows, as tall as the cover: " + view.rows(), view.rows() >= 13);
        assertTrue("square cells of about 10dp: " + view.pitch() / dp, view.pitch() / dp > 11.5 && view.pitch() / dp < 13);
        int lastMusic = brightest(SoundWave.COLUMNS - 1);
        int tailMiddle = brightest(SoundWave.COLUMNS + 3);
        int tailEnd = brightest(SoundWave.COLUMNS + 7);
        assertTrue("the music's own columns are see-through, not solid: " + lastMusic, lastMusic > 60 && lastMusic < 200);
        assertTrue("then fading: " + lastMusic + " > " + tailMiddle + " > " + tailEnd, tailMiddle < lastMusic && tailEnd < tailMiddle);
        assertTrue("almost gone at the cover: " + tailEnd, tailEnd < 25);
    }

    @Test
    public void eachBarsTopIsALittleSofter() {
        Context app = RuntimeEnvironment.getApplication();
        view = new SoundWaveView(app, null);
        float dp = app.getResources().getDisplayMetrics().density;
        int w = Math.round(688 * dp), h = Math.round(176 * dp);
        view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, w, h);
        // Two bars: one exactly 5 cells tall, one 10. Row 4 is the top of the first, the middle of the second.
        view.showForTests(5, 5f / view.rows());
        view.showForTests(20, 10f / view.rows());
        frame = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(frame));
        float pitch = view.pitch();
        int y = Math.round(h - pitch * 4 - pitch * 0.42f);
        int tip = Color.alpha(frame.getPixel(Math.round(5 * pitch + pitch * 0.42f), y));
        int middle = Color.alpha(frame.getPixel(Math.round(20 * pitch + pitch * 0.42f), y));
        assertTrue("the same cell is softer at the top of a bar: " + tip + " vs " + middle,
                middle > 60 && tip > 0.5f * middle && tip < 0.7f * middle);
    }

    @Test
    public void bandsOfTheScenesColoursDriftAcross() {
        play(30, 30, 30, 2);
        Scene signal = new Scene(0xFFFF3B2F, 0xFF3A6BFF, 0xFFFFEAE4); // red key, blue fill
        view.setScene(signal);
        int top = view.rows() - 1;
        view.advanceForTests(2.25f); // a quarter of the way: the first column wears the fill
        int first = view.cellColour(0, top);
        assertTrue("blue now", Color.blue(first) > 200 && Color.red(first) < 120);
        int further = view.cellColour(SoundWave.COLUMNS / 3, top);
        assertTrue("further along, another colour", Color.red(further) > Color.red(first) + 60);
        view.advanceForTests(4.5f); // half a band later, the key has drifted over it
        int later = view.cellColour(0, top);
        assertTrue("red now", Color.red(later) > 220 && Color.blue(later) < 120);
        int base = view.cellColour(0, 0);
        assertTrue("whiter at the bottom", Color.green(base) > Color.green(later) + 20);
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
