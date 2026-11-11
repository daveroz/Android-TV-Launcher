package com.camdeco.launcher;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.SystemClock;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * The big area at the top of Home: a light show right across it for every app (following the sound
 * while something plays, playing its patterns otherwise), with text on the left for what has
 * focus: an app's name, what was recently played in a music app (its covers taking turns, as an LP
 * in its sleeve on the right), or what's playing (with the cover as an LP on the right for music;
 * videos just get the light show). A small label on top says which: TV APP, RECENTLY PLAYED,
 * NOW PLAYING.
 */
final class Hero {

    /** Where covers come from; load() calls back on the main thread. */
    interface Images {
        Bitmap cached(String url);

        void load(String url, java.util.function.Consumer<Bitmap> done);
    }

    /** Something recently played, taking turns in the hero: a title, who, when, and its cover. */
    static final class Feature {
        final int id;
        final String title;
        final String detail;
        final String when;
        final String cover;

        Feature(int id, String title, String detail, String when, String cover) {
            this.id = id;
            this.title = title;
            this.detail = detail;
            this.when = when;
            this.cover = cover;
        }
    }

    private static final long ROTATE_MS = 8000;
    private static final long PROGRESS_MS = 500;
    /**
     * How long a new song may wait for its cover: players often send the next song's title a moment before its
     * picture. Until then the LP stays as it is (then it changes properly); only after this does the app's icon
     * stand in.
     */
    static final long COVER_WAIT_MS = 1500;

    private final Activity activity;
    private final Handler main;
    private final View root;
    private final View text;
    /** A small light show in the app's colour, on the right. */
    private final LightShowView lights;
    /** What's playing, as an LP: the cover is the sleeve, and the record spins while it plays. */
    private final RecordView record;
    private final View eyebrow;
    private final TextView label;
    private final View labelLine;
    private final TextView source;
    private final TextView title;
    private final TextView kind;
    private final TextView chip;
    private final TextView desc;
    private final LinearLayout dots;
    private final View holdKey;
    private final View holdLabel;
    private final View hints;
    private final View progress;
    private final ImageView playState;
    private final TextView elapsed;
    private final ProgressLineView bar;
    private final TextView duration;
    private final float density;

    private float scrollFade = 1f;
    private boolean visible = true;
    private boolean running = true;
    private List<Feature> features = new ArrayList<>();
    private int featureIndex;
    private Images images;
    private final Runnable rotate = this::nextFeature;
    /** The cover on screen, so play/pause updates don't fade the same picture again. */
    private String shownSong;
    private Bitmap shownArt;
    /** A new song waiting for its cover, with the app's icon to stand in if none comes. */
    private boolean waitingForCover;
    private Drawable waitingIcon;
    private boolean waitingPlaying;
    private final Runnable iconInstead = this::useIconInstead;
    /** The song whose progress is showing, or null. */
    private NowPlaying.Track progressTrack;
    private final Runnable tick = this::updateProgress;
    private Scene scene = Scene.promise();

    Hero(Activity activity, Handler main) {
        this.activity = activity;
        this.main = main;
        root = activity.findViewById(R.id.hero);
        text = activity.findViewById(R.id.hero_text);
        lights = activity.findViewById(R.id.hero_show);
        record = activity.findViewById(R.id.hero_record);
        eyebrow = activity.findViewById(R.id.hero_eyebrow);
        label = activity.findViewById(R.id.hero_label);
        labelLine = activity.findViewById(R.id.hero_label_line);
        source = activity.findViewById(R.id.hero_source);
        title = activity.findViewById(R.id.hero_title);
        kind = activity.findViewById(R.id.hero_kind);
        chip = activity.findViewById(R.id.hero_version);
        desc = activity.findViewById(R.id.hero_desc);
        dots = activity.findViewById(R.id.hero_dots);
        holdKey = activity.findViewById(R.id.hero_hold_key);
        holdLabel = activity.findViewById(R.id.hero_hold_label);
        hints = activity.findViewById(R.id.hero_hints);
        progress = activity.findViewById(R.id.hero_progress);
        playState = activity.findViewById(R.id.hero_play_state);
        elapsed = activity.findViewById(R.id.hero_elapsed);
        bar = activity.findViewById(R.id.hero_bar);
        duration = activity.findViewById(R.id.hero_duration);
        density = activity.getResources().getDisplayMetrics().density;
    }

    // ---- Light ----

    /** The scene's colour: the label, the visualizer, the light show, the sleeve's glow, the progress line. */
    void setScene(Scene newScene) {
        scene = newScene;
        label.setTextColor(newScene.label);
        lights.setScene(newScene);
        record.setGlow(newScene.key);
        bar.setScene(newScene);
    }

    Scene scene() {
        return scene;
    }

    // ---- What to show ----

    /** An app: its name, what kind of app it is above it, and its light show on the right. */
    void showApp(String kindLabel, String appName) {
        stopFeatures();
        stopProgress();
        stopWaiting();
        shownSong = null;
        setEyebrow(kindLabel, null);
        setText(appName, null, null, null);
        showHoldHint(true);
        hideRecord();
    }

    /** What was recently played in a music app, taking turns: each cover as an LP in its sleeve. */
    void showFeatures(List<Feature> list, Images source, String labelText, String sourceText) {
        if (list.isEmpty()) return;
        stopWaiting();
        setEyebrow(labelText, sourceText);
        if (sameFeatures(list)) return; // already showing these, don't restart the turns
        stopProgress();
        features = new ArrayList<>(list);
        images = source;
        showHoldHint(true);
        buildDots();
        showFeature(0);
    }

    void showTrack(NowPlaying.Track track, Drawable fallbackIcon) {
        stopFeatures();
        setEyebrow(activity.getString(R.string.now_playing_title), track.app);
        setText(track.title, track.artist, null, track.album);
        showProgress(track);
        String song = track.song();
        if (song.equals(shownSong)) {
            if (waitingForCover) {
                waitingPlaying = track.playing;
                if (track.art != null) { // the new song's cover has come: now the LP changes, properly
                    stopWaiting();
                    shownArt = track.art;
                    showPicture(track.art, track.playing, song);
                }
                return;
            }
            // The same song. Players resend its cover as a new picture, or drop it for a moment:
            // a new copy just replaces the picture in place, and a missing one changes nothing.
            if (track.art != null && track.art != shownArt) {
                shownArt = track.art;
                refreshPicture(track.art, track.playing, song);
            }
            record.setPlaying(track.playing); // play or pause
            return;
        }
        stopWaiting();
        shownSong = song;
        shownArt = track.art;
        if (track.art != null) {
            showPicture(track.art, track.playing, song);
        } else if (showsRecord() && record.shownCover() != null) {
            // The next song's title came before its cover: keep the LP as it is for a moment, rather than putting
            // the app's icon in the sleeve and swapping it straight out again.
            waitingForCover = true;
            waitingIcon = fallbackIcon;
            waitingPlaying = track.playing;
            record.postDelayed(iconInstead, COVER_WAIT_MS);
        } else if (fallbackIcon != null) {
            swapCover(AppRepository.copy(fallbackIcon), track.playing, song);
        }
    }

    /**
     * No cover came for the new song: the app's icon stands in, or with no icon either, the LP goes and the light
     * show carries on alone (as for a video), rather than the last song's cover staying.
     */
    private void useIconInstead() {
        if (!waitingForCover) return;
        waitingForCover = false;
        if (waitingIcon != null) swapCover(AppRepository.copy(waitingIcon), waitingPlaying, shownSong);
        else hideRecord();
        waitingIcon = null;
    }

    private void stopWaiting() {
        waitingForCover = false;
        waitingIcon = null;
        record.removeCallbacks(iconInstead);
    }

    /** Whether a new song is waiting for its cover (for tests). */
    boolean waitingForCover() {
        return waitingForCover;
    }

    /** The same song's picture again, as a new copy: swap it in without any animation. */
    private void refreshPicture(Bitmap bitmap, boolean playing, String song) {
        if (!isWide(bitmap)) record.show(new BitmapDrawable(activity.getResources(), bitmap), playing, song);
    }

    static boolean isWide(int width, int height) {
        return width > height * 1.4f;
    }

    private static boolean isWide(Bitmap bitmap) {
        return isWide(bitmap.getWidth(), bitmap.getHeight());
    }

    /**
     * A square picture (an album cover) becomes an LP on the right; its record comes out and spins
     * while {@code playing}. A wide one (a video's thumbnail) isn't shown: videos just get the light
     * show.
     */
    private void showPicture(Bitmap bitmap, boolean playing, String song) {
        if (isWide(bitmap)) {
            hideRecord();
        } else {
            swapCover(new BitmapDrawable(activity.getResources(), bitmap), playing, song);
        }
    }

    /** Which recently played item is on screen (for tests), or null. */
    Feature currentFeature() {
        return features.isEmpty() ? null : features.get(featureIndex);
    }

    // ---- Recently played, taking turns ----

    private void showFeature(int index) {
        featureIndex = index;
        Feature f = features.get(index);
        setText(f.title, f.detail, f.when, null);
        // The current item's dash is long and lit. (Plain views need real widths: a
        // wrap_content View would stretch to fill the row.)
        for (int i = 0; i < dots.getChildCount(); i++) {
            View dot = dots.getChildAt(i);
            dot.setActivated(i == index);
            dot.getLayoutParams().width = (int) ((i == index ? 18 : 6) * density);
        }
        dots.requestLayout();
        Bitmap bitmap = images.cached(f.cover);
        if (bitmap != null) {
            setFeatureArt(bitmap);
        } else {
            images.load(f.cover, loaded -> {
                if (features.size() > featureIndex && features.get(featureIndex) == f) setFeatureArt(loaded);
            });
        }
        main.removeCallbacks(rotate);
        if (running && features.size() > 1) main.postDelayed(rotate, ROTATE_MS);
    }

    private void nextFeature() {
        if (features.size() > 1) showFeature((featureIndex + 1) % features.size());
    }

    private void setFeatureArt(Bitmap bitmap) {
        shownSong = null;
        // Recently played: the record stays in its sleeve.
        swapCover(new BitmapDrawable(activity.getResources(), bitmap), false, "feature\n" + features.get(featureIndex).id);
    }

    private void stopFeatures() {
        main.removeCallbacks(rotate);
        features = new ArrayList<>();
        dots.removeAllViews();
        dots.setVisibility(View.GONE);
    }

    private boolean sameFeatures(List<Feature> list) {
        if (list.size() != features.size()) return false;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id != features.get(i).id) return false;
        }
        return true;
    }

    private void buildDots() {
        dots.removeAllViews();
        dots.setVisibility(features.size() > 1 ? View.VISIBLE : View.GONE);
        int size = (int) (6 * density);
        for (int i = 0; i < features.size(); i++) {
            View dot = new View(activity);
            dot.setBackgroundResource(R.drawable.hero_dot);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, (int) (2 * density));
            lp.setMarginEnd((int) (4 * density));
            dots.addView(dot, lp);
        }
    }

    // ---- Lifecycle, paging, scrolling ----

    /** Stops the turns, the light show and the clock while the launcher is in the background. */
    void pause() {
        running = false;
        main.removeCallbacks(rotate);
        main.removeCallbacks(tick);
        lights.setRunning(false);
    }

    void resume() {
        running = true;
        main.removeCallbacks(rotate);
        if (features.size() > 1) main.postDelayed(rotate, ROTATE_MS);
        lights.setRunning(visible);
        if (progressTrack != null) updateProgress();
    }

    void setVisible(boolean show) {
        visible = show;
        lights.setRunning(show && running); // no need to animate it off screen
        root.animate().alpha(show ? scrollFade : 0f).setDuration(200).start();
    }

    /** Fade and lift the hero away as the rows scroll up over it; 0 = not scrolled, 1 = gone. */
    void setScrollFraction(float fraction) {
        scrollFade = 1f - fraction;
        // The text goes first, so it never sits under the row that scrolls up over it.
        text.setAlpha(Math.max(0f, 1f - fraction * 2f));
        text.setTranslationY(-fraction * 48 * density);
        if (visible) {
            root.animate().cancel();
            root.setAlpha(scrollFade);
        }
    }

    // ---- Text ----

    private void showHoldHint(boolean show) {
        hints.setVisibility(View.VISIBLE);
        holdKey.setVisibility(show ? View.VISIBLE : View.GONE);
        holdLabel.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    /** The small label on top: what this is, a hairline, and where it's from. */
    private void setEyebrow(String labelText, String sourceText) {
        eyebrow.setVisibility(labelText == null ? View.GONE : View.VISIBLE);
        label.setText(labelText);
        boolean hasSource = sourceText != null && !sourceText.isEmpty();
        source.setText(sourceText);
        source.setVisibility(hasSource ? View.VISIBLE : View.GONE);
        labelLine.setVisibility(hasSource ? View.VISIBLE : View.GONE);
    }

    /** What the label says right now (for tests). */
    String eyebrowText() {
        if (eyebrow.getVisibility() != View.VISIBLE) return null;
        return source.getVisibility() == View.VISIBLE ? label.getText() + " · " + source.getText() : label.getText().toString();
    }

    private void setText(String titleText, String kindText, String chipText, String descText) {
        title.setText(titleText);
        title.setVisibility(titleText == null ? View.GONE : View.VISIBLE);
        kind.setText(kindText);
        kind.setVisibility(kindText == null ? View.GONE : View.VISIBLE);
        chip.setText(chipText);
        chip.setVisibility(chipText == null || chipText.isEmpty() ? View.GONE : View.VISIBLE);
        desc.setText(descText);
        desc.setVisibility(descText == null || descText.isEmpty() ? View.GONE : View.VISIBLE);
    }

    // ---- How far through the song ----

    /** A song: its progress takes the place of the remote's hints, if the player says how long it is. */
    private void showProgress(NowPlaying.Track track) {
        hints.setVisibility(View.GONE); // OK just opens the player; nothing to explain
        if (track.duration <= 0) {
            stopProgress();
            return;
        }
        progressTrack = track;
        progress.setVisibility(View.VISIBLE);
        playState.setImageResource(track.playing ? R.drawable.ic_pause : R.drawable.ic_play);
        duration.setText(clock(track.duration));
        updateProgress();
    }

    private void stopProgress() {
        progressTrack = null;
        main.removeCallbacks(tick);
        progress.setVisibility(View.GONE);
    }

    private void updateProgress() {
        main.removeCallbacks(tick);
        NowPlaying.Track track = progressTrack;
        if (track == null) return;
        long at = track.positionAt(SystemClock.elapsedRealtime());
        elapsed.setText(clock(at));
        bar.setProgress(at / (float) track.duration);
        if (running && track.playing) main.postDelayed(tick, PROGRESS_MS);
    }

    /** 3:58, or 1:02:07 for an hour or more. */
    static String clock(long ms) {
        long seconds = Math.max(0, ms / 1000);
        long h = seconds / 3600, m = seconds / 60 % 60, s = seconds % 60;
        return h > 0 ? String.format(java.util.Locale.ROOT, "%d:%02d:%02d", h, m, s)
                : String.format(java.util.Locale.ROOT, "%d:%02d", m, s);
    }

    /** The song's time as shown (for tests). */
    String elapsedText() {
        return progress.getVisibility() == View.VISIBLE ? elapsed.getText().toString() : null;
    }

    // ---- Pictures ----

    /** Shows an album cover as an LP on the right; the light show's music ends where its record starts. */
    private void swapCover(Drawable picture, boolean playing, String song) {
        record.animate().cancel();
        record.setVisibility(View.VISIBLE);
        record.animate().alpha(1f).setDuration(280).start();
        record.show(picture, playing, song);
        lights.setRecordShown(true);
    }

    private void hideRecord() {
        lights.setRecordShown(false);
        record.animate().cancel();
        if (record.getVisibility() != View.VISIBLE) return;
        record.animate().alpha(0f).setDuration(160).withEndAction(() -> record.setVisibility(View.GONE)).start();
    }

    // ---- Sound ----

    void setWaveSource(SoundWave source) {
        lights.setSource(source);
    }

    /** The light show follows the sound while something plays; otherwise it plays its patterns. */
    void showWave(boolean follow) {
        lights.setReactive(follow);
    }

    /** Whether the light show is on screen (for tests): it always is, on Home. */
    boolean showsLightShow() {
        return lights.getVisibility() == View.VISIBLE && lights.getAlpha() > 0.5f;
    }

    /** Whether the light show is following the sound (for tests). */
    boolean followsSound() {
        return lights.isReactive();
    }

    /** The colour the light show is lit in (for tests). */
    int showColour() {
        return lights.keyColour();
    }

    /** Whether the LP is on screen (for tests). */
    boolean showsRecord() {
        return record.getVisibility() == View.VISIBLE && record.getAlpha() > 0.5f;
    }

    RecordView recordForTests() {
        return record;
    }
}
