package com.camdeco.launcher;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.audiofx.Visualizer;
import android.os.SystemClock;

/**
 * How loud the music is right now in three ranges, for the three zones of the wave line in the hero:
 * bass (20 to 250 Hz), voices (250 Hz to 4 kHz) and treble (4 to 16 kHz). The ranges share one
 * volume scale, so a bassy song shows mostly bass and a sung part mostly voice.
 *
 * It listens to what the TV plays through Android's audio visualizer. That needs the "record
 * audio" permission, but it only reads the TV's own sound (at low detail) and never opens a
 * microphone. When it may not listen, or the TV only ever gives it silence, it makes up levels
 * that move like music while something plays, and still while paused.
 */
final class SoundWave implements Visualizer.OnDataCaptureListener {
    static final int BANDS = 3;
    static final int BASS = 0, VOICE = 1, TREBLE = 2;
    /** Nothing heard after this much playing time means the TV keeps its sound private. */
    static final long SILENT_AFTER_MS = 5000;
    /** Range edges in Hz: bass, voices, treble. */
    private static final int[] EDGES = {20, 250, 4000, 16000};
    /**
     * Music carries far more energy in the bass than higher up (about 3 times per frequency step
     * at voice pitch and 8 times at cymbal pitch), so the higher ranges are lifted by that much
     * before they share the volume scale. Without it, every song would look like bass only.
     */
    private static final float[] LIFT = {1f, 3f, 8f};

    enum Status {
        /** Listening, but nothing has played yet. */
        WAITING,
        /** The line follows the real song. */
        FOLLOWING,
        /** Deco isn't allowed to hear the TV's sound: the line moves on its own. */
        NO_PERMISSION,
        /** The TV only gives silence: the line moves on its own. */
        TV_SILENT,
        /** The TV has no audio visualizer for apps: the line moves on its own. */
        UNAVAILABLE,
    }

    private final Context context;
    private Visualizer visualizer;
    private boolean unavailable;
    private final float[] heard = new float[BANDS];
    private final float[] lifted = new float[BANDS];
    /** The loudest recent range, falling slowly, so quiet and loud songs both fill the line. */
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

    /** Fills {@code out} with the three levels (0 to 1: bass, voice, treble) to draw at {@code t} seconds. */
    void levels(double t, float[] out) {
        if (everHeard) {
            System.arraycopy(heard, 0, out, 0, BANDS);
        } else if (playing) {
            madeUp(t, out);
        } else {
            java.util.Arrays.fill(out, 0f);
        }
    }

    /** Levels that move like a song at 112 BPM: a kick for the bass, a melody, hi-hats. */
    static void madeUp(double t, float[] out) {
        double beat = 60.0 / 112;
        double pos = t % beat;
        long beatNo = (long) (t / beat);
        double kick = Math.exp(-pos / 0.12);
        double snare = beatNo % 2 == 1 ? Math.exp(-pos / 0.16) : 0;
        double hat = Math.exp(-(t % (beat / 2)) / 0.05);
        double melody = Math.pow(0.5 + 0.5 * Math.sin(t * Math.PI * 2 * 1.9), 1.5);
        out[BASS] = (float) Math.min(1, 0.25 + 0.7 * kick);
        out[VOICE] = (float) Math.min(1, 0.2 + 0.35 * melody + 0.25 * snare);
        out[TREBLE] = (float) Math.min(1, 0.12 + 0.5 * hat);
    }

    @Override
    public void onWaveFormDataCapture(Visualizer v, byte[] waveform, int samplingRate) {
        // Only the frequency bands are used.
    }

    @Override
    public void onFftDataCapture(Visualizer v, byte[] fft, int samplingRateMilliHz) {
        if (fft == null || fft.length < 8 || samplingRateMilliHz <= 0) return;
        int bins = fft.length / 2;
        double binHz = samplingRateMilliHz / 1000.0 / fft.length;
        float total = 0;
        float loudest = 0;
        for (int b = 0; b < BANDS; b++) {
            int from = Math.max(1, (int) (EDGES[b] / binHz) + (b > 0 ? 1 : 0));
            int to = Math.min(bins - 1, Math.max(from, (int) (EDGES[b + 1] / binHz)));
            double sum = 0;
            for (int k = from; k <= to; k++) {
                sum += Math.hypot(fft[2 * k], fft[2 * k + 1]);
            }
            float magnitude = (float) (sum / (to - from + 1));
            total += magnitude;
            lifted[b] = magnitude * LIFT[b];
            loudest = Math.max(loudest, lifted[b]);
        }
        // One shared scale: the loudest recent range moves the line fully, the others in proportion.
        peak = Math.max(Math.max(loudest, 3f), peak * 0.995f);
        for (int b = 0; b < BANDS; b++) {
            heard[b] = (float) Math.pow(Math.min(1f, lifted[b] / peak), 0.8);
        }
        if (total > 1.5f) everHeard = true;
    }

    /** The visualizer in use (for tests), or null. */
    Visualizer visualizerForTests() {
        return visualizer;
    }
}
