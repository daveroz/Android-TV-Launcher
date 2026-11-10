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
        int bass = litRows(40, 250), voice = litRows(250, 4000), treble = litRows(4000, 16000);
        assertTrue("bass " + bass + " voice " + voice + " treble " + treble, bass >= 5 && bass > 2 * voice && bass > 2 * treble);
    }

    @Test
    public void voicesLightTheMiddle() {
        play(2, 30, 1, 45);
        int bass = litRows(40, 250), voice = litRows(250, 4000), treble = litRows(4000, 16000);
        assertTrue("bass " + bass + " voice " + voice + " treble " + treble, voice >= 4 && voice > 2 * bass && voice > treble);
    }

    @Test
    public void trebleLightsTheRight() {
        play(6, 3, 3, 45);
        int bass = litRows(40, 250), treble = litRows(4000, 16000);
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
        int w = Math.round(652 * dp), h = Math.round(62 * dp);
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

    /** How many rows of cells are lit, at most, in the columns between these frequencies. */
    private int litRows(double fromHz, double toHz) {
        float panel = view.panelWidth();
        int from = SoundWave.columnOf(fromHz * 1.01), to = SoundWave.columnOf(toHz * 0.99);
        int top = frame.getHeight();
        for (int c = from; c <= to; c++) {
            int x = Math.round(panel * (c + 0.5f) / SoundWave.COLUMNS);
            for (int y = 0; y < frame.getHeight(); y++) {
                int p = frame.getPixel(x, y);
                boolean lit = Color.alpha(p) > 200 && (Color.red(p) + Color.green(p) + Color.blue(p)) / 3 > 110;
                if (lit) {
                    top = Math.min(top, y);
                    break;
                }
            }
        }
        if (top == frame.getHeight()) return 0;
        float pad = 5 * RuntimeEnvironment.getApplication().getResources().getDisplayMetrics().density;
        float pitch = (frame.getHeight() - 2 * pad) / (SoundWaveView.ROWS - 0.16f);
        return Math.round((frame.getHeight() - pad - top) / pitch);
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
