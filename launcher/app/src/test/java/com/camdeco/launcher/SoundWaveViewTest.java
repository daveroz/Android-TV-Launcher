package com.camdeco.launcher;

import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Looper;
import android.view.View;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.time.Duration;

/** The sound wave's line is split by pitch: bass on the left, voices in the middle, treble on the right. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SoundWaveViewTest {

    @Test
    public void bassMovesTheLeftOfTheLine() {
        Bitmap frame = play(60, 1, 1);
        int left = height(frame, 0.08, 0.30), middle = height(frame, 0.40, 0.60), right = height(frame, 0.72, 0.92);
        assertTrue("left " + left + " vs right " + right, left > 2 * right);
        assertTrue("left " + left + " vs middle " + middle, left > 2 * middle);
    }

    @Test
    public void voicesMoveTheMiddle() {
        Bitmap frame = play(2, 30, 1);
        int left = height(frame, 0.08, 0.30), middle = height(frame, 0.40, 0.60), right = height(frame, 0.72, 0.92);
        assertTrue("middle " + middle + " vs left " + left, middle > 1.5 * left);
        assertTrue("middle " + middle + " vs right " + right, middle > 1.5 * right);
    }

    @Test
    public void trebleMovesTheRight() {
        Bitmap frame = play(2, 2, 10);
        int left = height(frame, 0.08, 0.30), right = height(frame, 0.72, 0.92);
        assertTrue("right " + right + " vs left " + left, right > 1.4 * left);
    }

    /** Runs the line for a moment with music of this make-up, and returns its last frame. */
    private static Bitmap play(int bass, int voice, int treble) {
        Context app = RuntimeEnvironment.getApplication();
        SoundWave wave = new SoundWave(app);
        wave.onFftDataCapture(null, fft(bass, voice, treble), 44100000);
        SoundWaveView view = new SoundWaveView(app, null);
        view.setSource(wave);
        float dp = app.getResources().getDisplayMetrics().density;
        int w = Math.round(652 * dp), h = Math.round(56 * dp);
        view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, w, h);
        Bitmap frame = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        for (int i = 0; i < 45; i++) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16));
            frame.eraseColor(0);
            view.draw(new Canvas(frame));
        }
        return frame;
    }

    /** How many pixel rows the line covers between these fractions of its width. */
    private static int height(Bitmap frame, double from, double to) {
        int top = frame.getHeight(), bottom = -1;
        for (int x = (int) (from * frame.getWidth()); x < to * frame.getWidth(); x++) {
            for (int y = 0; y < frame.getHeight(); y++) {
                if ((frame.getPixel(x, y) >>> 24) > 40) {
                    top = Math.min(top, y);
                    bottom = Math.max(bottom, y);
                }
            }
        }
        return bottom < 0 ? 0 : bottom - top + 1;
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
