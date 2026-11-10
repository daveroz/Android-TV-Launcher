package com.camdeco.launcher;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.audiofx.Visualizer;
import android.os.SystemClock;

/**
 * How loud the music is right now across {@link #COLUMNS} columns, bass on the left (40 Hz) to
 * treble on the right (16 kHz), for the light panel under the song in the hero. The columns share
 * one volume scale, so a bassy song lights mostly the left and a sung part mostly the middle.
 *
 * It listens to what the TV plays through Android's audio visualizer. That needs the "record
 * audio" permission, but it only reads the TV's own sound (at low detail) and never opens a
 * microphone. When it may not listen, or the TV only ever gives it silence, it makes up levels
 * that move like music while something plays, and stay dark while paused.
 */
final class SoundWave implements Visualizer.OnDataCaptureListener {
    static final int COLUMNS = 48;
    static final double LOWEST_HZ = 40, HIGHEST_HZ = 16000;
    /** Nothing heard after this much playing time means the TV keeps its sound private. */
    static final long SILENT_AFTER_MS = 5000;

    enum Status {
        /** Listening, but nothing has played yet. */
        WAITING,
        /** The panel follows the real song. */
        FOLLOWING,
        /** Deco isn't allowed to hear the TV's sound: the panel moves on its own. */
        NO_PERMISSION,
        /** The TV only gives silence: the panel moves on its own. */
        TV_SILENT,
        /** The TV has no audio visualizer for apps: the panel moves on its own. */
        UNAVAILABLE,
    }

    private final Context context;
    private Visualizer visualizer;
    private boolean unavailable;
    private final float[] heard = new float[COLUMNS];
    private final float[] lifted = new float[COLUMNS];
    /** The loudest recent column, falling slowly, so quiet and loud songs both fill the panel. */
    private float peak;
    private boolean everHeard;
    /** What the last listening session found out, kept while not listening (for the menu). */
    private Status verdict;
    private boolean playing;
    private long playingSince = -1;
    private long playedMs;

    SoundWave(Context context) {
        this.context = context.getApplicationContext();
    }

    /** The frequency (Hz) where a column starts; column {@code COLUMNS} is the top edge. */
    static double edgeHz(int column) {
        return LOWEST_HZ * Math.pow(HIGHEST_HZ / LOWEST_HZ, column / (double) COLUMNS);
    }

    /** The column a frequency falls in. */
    static int columnOf(double hz) {
        double c = Math.log(hz / LOWEST_HZ) / Math.log(HIGHEST_HZ / LOWEST_HZ) * COLUMNS;
        return (int) Math.max(0, Math.min(COLUMNS - 1, c));
    }

    /**
     * Music carries far more energy in the bass than higher up, about the square root of the pitch
     * ratio (3 times at voice pitch, 8 times at cymbal pitch), so higher columns are lifted by that
     * much before they share the volume scale. Without it, every song would light only the left.
     */
    private static float lift(double hz) {
        return (float) Math.max(1, Math.sqrt(hz / 120));
    }

    boolean hasPermission() {
        return context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    /** Starts listening, if allowed. Safe to call again. */
    void start() {
        if (visualizer != null || unavailable || !hasPermission()) return;
        try {
            Visualizer v = new Visualizer(0); // 0 = everything the TV plays
            v.setEnabled(false);
            // The largest capture gives the finest bass; older TVs only take smaller ones.
            for (int size : new int[]{1024, 512, 256}) {
                if (v.setCaptureSize(size) == Visualizer.SUCCESS) break;
            }
            int result = v.setDataCaptureListener(this, 20000, false, true); // 20 a second
            if (result != Visualizer.SUCCESS) result = v.setDataCaptureListener(this, 10000, false, true);
            if (result != Visualizer.SUCCESS || v.setEnabled(true) != Visualizer.SUCCESS) {
                v.release();
                unavailable = true;
                return;
            }
            visualizer = v;
        } catch (RuntimeException e) {
            unavailable = true; // no visualizer for apps on this TV, or it's busy
        }
    }

    void stop() {
        Status now = status();
        if (now == Status.FOLLOWING || now == Status.TV_SILENT) verdict = now;
        if (visualizer != null) {
            visualizer.setEnabled(false);
            visualizer.release();
            visualizer = null;
        }
        setPlaying(false);
        playedMs = 0;
        everHeard = false;
        java.util.Arrays.fill(heard, 0f);
    }

    /** Forgets what it found out, for a fresh try (after the permission is granted). */
    void reset() {
        stop();
        unavailable = false;
        verdict = null;
    }

    void setPlaying(boolean nowPlaying) {
        long now = SystemClock.uptimeMillis();
        if (nowPlaying && playingSince < 0) playingSince = now;
        if (!nowPlaying && playingSince >= 0) {
            playedMs += now - playingSince;
            playingSince = -1;
        }
        playing = nowPlaying;
    }

    private long playedMs() {
        return playedMs + (playingSince >= 0 ? SystemClock.uptimeMillis() - playingSince : 0);
    }

    Status status() {
        if (everHeard) return Status.FOLLOWING;
        if (!hasPermission()) return Status.NO_PERMISSION;
        if (unavailable) return Status.UNAVAILABLE;
        if (visualizer != null && playedMs() > SILENT_AFTER_MS) return Status.TV_SILENT;
        if (visualizer == null && verdict != null) return verdict;
        return Status.WAITING;
    }

    /** Fills {@code out} with each column's level (0 to 1) to show at {@code t} seconds. */
    void levels(double t, float[] out) {
        if (everHeard) {
            System.arraycopy(heard, 0, out, 0, COLUMNS);
        } else if (playing) {
            madeUp(t, out);
        } else {
            java.util.Arrays.fill(out, 0f);
        }
    }

    /** Levels that move like a song at 112 BPM: a kick in the bass, a melody, hi-hats on top. */
    static void madeUp(double t, float[] out) {
        double beat = 60.0 / 112;
        double pos = t % beat;
        long beatNo = (long) (t / beat);
        double kick = Math.exp(-pos / 0.12);
        double snare = beatNo % 2 == 1 ? Math.exp(-pos / 0.16) : 0;
        double hat = Math.exp(-(t % (beat / 2)) / 0.05);
        double melody = Math.pow(0.5 + 0.5 * Math.sin(t * Math.PI * 2 * 1.9), 1.5);
        double bass = 0.25 + 0.65 * kick, voice = 0.2 + 0.35 * melody + 0.25 * snare, treble = 0.1 + 0.5 * hat;
        for (int c = 0; c < COLUMNS; c++) {
            double u = c / (double) (COLUMNS - 1);
            double v = bass * Math.exp(-u * u / 0.03)
                    + voice * Math.exp(-(u - 0.5) * (u - 0.5) / 0.03)
                    + treble * Math.exp(-(u - 0.86) * (u - 0.86) / 0.02);
            double wobble = 0.85 + 0.15 * Math.sin(t * (2.3 + c * 0.17) + c * 1.7);
            out[c] = (float) Math.min(1, v * wobble);
        }
    }

    @Override
    public void onWaveFormDataCapture(Visualizer v, byte[] waveform, int samplingRate) {
        // Only the frequencies are used.
    }

    @Override
    public void onFftDataCapture(Visualizer v, byte[] fft, int samplingRateMilliHz) {
        if (fft == null || fft.length < 8 || samplingRateMilliHz <= 0) return;
        int bins = fft.length / 2;
        double binHz = samplingRateMilliHz / 1000.0 / fft.length;
        float total = 0;
        float loudest = 0;
        for (int c = 0; c < COLUMNS; c++) {
            double from = edgeHz(c) / binHz, to = edgeHz(c + 1) / binHz;
            float magnitude;
            if (to - from < 1) {
                // A bass column narrower than one frequency step: read between the two nearest.
                magnitude = magnitudeAt(fft, (from + to) / 2, bins);
            } else {
                int a = Math.max(1, (int) Math.ceil(from)), b = Math.min(bins - 1, (int) Math.floor(to));
                double sum = 0;
                for (int k = a; k <= b; k++) sum += magnitude(fft, k);
                magnitude = b >= a ? (float) (sum / (b - a + 1)) : magnitudeAt(fft, (from + to) / 2, bins);
            }
            total += magnitude;
            lifted[c] = magnitude * lift(Math.sqrt(edgeHz(c) * edgeHz(c + 1)));
            loudest = Math.max(loudest, lifted[c]);
        }
        // One shared scale: the loudest recent column lights fully, the others in proportion.
        peak = Math.max(Math.max(loudest, 3f), peak * 0.995f);
        for (int c = 0; c < COLUMNS; c++) {
            heard[c] = (float) Math.pow(Math.min(1f, lifted[c] / peak), 0.75);
        }
        if (total > 1.5f) everHeard = true;
    }

    private static float magnitude(byte[] fft, int k) {
        return (float) Math.hypot(fft[2 * k], fft[2 * k + 1]);
    }

    private static float magnitudeAt(byte[] fft, double bin, int bins) {
        double b = Math.max(1, Math.min(bins - 2, bin));
        int k = (int) b;
        double f = b - k;
        return (float) (magnitude(fft, k) * (1 - f) + magnitude(fft, k + 1) * f);
    }

    /** The visualizer in use (for tests), or null. */
    Visualizer visualizerForTests() {
        return visualizer;
    }
}
