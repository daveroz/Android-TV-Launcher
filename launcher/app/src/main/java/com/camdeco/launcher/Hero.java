package com.camdeco.launcher;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * The big area at the top of Home. It shows one of three things for what has focus:
 * an app (its banner on the right, in front of a wall of lamps in its own light), a streaming
 * app's shows (full-screen artwork that changes every few seconds, like the Apple TV home screen),
 * or what's playing (the cover as an LP with the visualizer beside it, or a video's thumbnail
 * full-screen). A small label on top says which: NOW PLAYING, RECENTLY PLAYED, or the app's name.
 */
final class Hero {

    /** Where show images come from; load() calls back on the main thread. */
    interface Images {
        Bitmap cached(String url);

        void load(String url, java.util.function.Consumer<Bitmap> done);
    }

    private static final long ROTATE_MS = 8000;
    private static final long PROGRESS_MS = 500;
    private static final java.util.regex.Pattern EPISODE = java.util.regex.Pattern.compile("S(\\d+) E(\\d+)");

    private final Activity activity;
    private final Handler main;
    private final View root;
    private final View text;
    /** A show's artwork across the screen, with the scrims that fade it into the night. */
    private final View photo;
    private final ImageView art;
    /** The card on the right: an app's banner. */
    private final ImageView cover;
    /** A wall of lamps in the app's own light, behind its banner card. */
    private final LightWallView wall;
    /** The music's waveform under the song. */
    private final SoundWaveView wave;
    /** What's playing, as an LP: the cover is the sleeve, and the record spins while it plays. */
    private final RecordView record;
    private final int cardHeight;
    private final View eyebrow;
    private final TextView label;
    private final View labelLine;
    private final TextView source;
    private final TextView title;
    private final TextView kind;
    private final TextView chip;
    private final TextView desc;
    private final LinearLayout dots;
    private final TextView credit;
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
    private List<ShowArt.Feature> features = new ArrayList<>();
    private int featureIndex;
    private Images images;
    private final Runnable rotate = this::nextFeature;
    /** The cover on screen, so play/pause updates don't fade the same picture again. */
    private String shownSong;
    private Bitmap shownArt;
    /** The song whose progress is showing, or null. */
    private NowPlaying.Track progressTrack;
    private final Runnable tick = this::updateProgress;
    private Scene scene = Scene.promise();

    Hero(Activity activity, Handler main) {
        this.activity = activity;
        this.main = main;
        root = activity.findViewById(R.id.hero);
        text = activity.findViewById(R.id.hero_text);
        photo = activity.findViewById(R.id.hero_photo);
        art = activity.findViewById(R.id.hero_art);
        cover = activity.findViewById(R.id.hero_cover);
        cover.setClipToOutline(true); // round the picture's corners to the card
        wall = activity.findViewById(R.id.hero_wall);
        wave = activity.findViewById(R.id.hero_wave);
        record = activity.findViewById(R.id.hero_record);
        cardHeight = activity.getResources().getDimensionPixelSize(R.dimen.hero_card_height);
        eyebrow = activity.findViewById(R.id.hero_eyebrow);
        label = activity.findViewById(R.id.hero_label);
        labelLine = activity.findViewById(R.id.hero_label_line);
        source = activity.findViewById(R.id.hero_source);
        title = activity.findViewById(R.id.hero_title);
        kind = activity.findViewById(R.id.hero_kind);
        chip = activity.findViewById(R.id.hero_version);
        desc = activity.findViewById(R.id.hero_desc);
        dots = activity.findViewById(R.id.hero_dots);
        credit = activity.findViewById(R.id.hero_credit);
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

    /** The scene's colours: the label, the visualizer, the wall, the sleeve's glow, the progress line. */
    void setScene(Scene newScene) {
        scene = newScene;
        label.setTextColor(newScene.label);
        wave.setScene(newScene);
        wall.setScene(newScene);
        record.setGlow(newScene.key);
        bar.setScene(newScene);
        if (Build.VERSION.SDK_INT >= 28) {
            cover.setOutlineAmbientShadowColor(newScene.key);
            cover.setOutlineSpotShadowColor(newScene.fill);
        }
    }

    Scene scene() {
        return scene;
    }

    // ---- What to show ----

    /** A plain app: just its name as the label and its banner (set with setAppArt), no other text. */
    void showApp(String appName) {
        stopFeatures();
        stopProgress();
        setEyebrow(appName, null);
        setText(null, null, null, null);
        showHoldHint(true);
    }

    /** A plain app: its banner (or icon) as a card on the right, in front of a wall of lamps. */
    void setAppArt(Drawable drawable, boolean isBanner) {
        shownSong = null;
        if (isBanner) {
            showCard(AppRepository.copy(drawable), cardHeight * 16 / 9, ImageView.ScaleType.CENTER_CROP, 0);
        } else {
            showCard(AppRepository.copy(drawable), cardHeight, ImageView.ScaleType.FIT_CENTER, (int) (40 * density));
        }
    }

    /**
     * Items that take turns in the hero: a streaming app's shows, or what was recently played in a
     * media app. creditText credits the source (TVmaze), or null for none.
     */
    void showFeatures(List<ShowArt.Feature> list, Images source, String creditText, String labelText, String sourceText) {
        if (list.isEmpty()) return;
        setEyebrow(labelText, sourceText);
        if (sameShows(list)) return; // already showing these, don't restart the turns
        stopProgress();
        features = new ArrayList<>(list);
        images = source;
        credit.setText(creditText);
        credit.setVisibility(creditText == null ? View.GONE : View.VISIBLE);
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
            // The same song. Players resend its cover as a new picture, or drop it for a moment:
            // a new copy just replaces the picture in place, and a missing one changes nothing.
            if (track.art != null && track.art != shownArt) {
                shownArt = track.art;
                refreshPicture(track.art, track.playing, song);
            }
            record.setPlaying(track.playing); // play or pause
            return;
        }
        shownSong = song;
        shownArt = track.art;
        if (track.art != null) {
            showPicture(track.art, track.playing, song);
        } else if (fallbackIcon != null) {
            swapCover(AppRepository.copy(fallbackIcon), track.playing, song);
        }
    }

    /** The same song's picture again, as a new copy: swap it in without any animation. */
    private void refreshPicture(Bitmap bitmap, boolean playing, String song) {
        if (isWide(bitmap)) {
            art.setImageDrawable(new BitmapDrawable(activity.getResources(), bitmap));
        } else {
            record.show(new BitmapDrawable(activity.getResources(), bitmap), playing, song);
        }
    }

    private static boolean isWide(Bitmap bitmap) {
        return bitmap.getWidth() > bitmap.getHeight() * 1.4f;
    }

    /**
     * A wide picture (a show, a YouTube thumbnail) fills the screen. A square one (an album
     * cover) becomes an LP on the right, lit by the stage in its colours; its record comes out and
     * spins while {@code playing}.
     */
    private void showPicture(Bitmap bitmap, boolean playing, String song) {
        if (isWide(bitmap)) {
            swapArt(new BitmapDrawable(activity.getResources(), bitmap));
        } else {
            swapCover(new BitmapDrawable(activity.getResources(), bitmap), playing, song);
        }
    }

    /** Which show is on screen (for tests), or null. */
    ShowArt.Feature currentFeature() {
        return features.isEmpty() ? null : features.get(featureIndex);
    }

    // ---- Carousel ----

    private void showFeature(int index) {
        featureIndex = index;
        ShowArt.Feature f = features.get(index);
        setText(f.show, f.genres != null ? f.genres + " · " + f.channel : f.channel, episodeLabel(f.episode), f.summary);
        // The current show's dash is long and lit. (Plain views need real widths: a
        // wrap_content View would stretch to fill the row.)
        for (int i = 0; i < dots.getChildCount(); i++) {
            View dot = dots.getChildAt(i);
            dot.setActivated(i == index);
            dot.getLayoutParams().width = (int) ((i == index ? 18 : 6) * density);
        }
        dots.requestLayout();
        Bitmap bitmap = images.cached(f.background);
        if (bitmap != null) {
            setFeatureArt(bitmap);
        } else {
            images.load(f.background, loaded -> {
                if (features.size() > featureIndex && features.get(featureIndex) == f) setFeatureArt(loaded);
            });
        }
        main.removeCallbacks(rotate);
        if (running && features.size() > 1) main.postDelayed(rotate, ROTATE_MS);
    }

    /**
     * The episode in the TV's language: "S2 E5" in English, "T2 E5" in Spanish. Shows keep the
     * English form in their cache, so a language change shows up straight away.
     */
    private String episodeLabel(String episode) {
        if (episode == null) return null;
        java.util.regex.Matcher m = EPISODE.matcher(episode);
        if (!m.matches()) return episode;
        return activity.getString(R.string.episode_label, Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
    }

    private void nextFeature() {
        if (features.size() > 1) showFeature((featureIndex + 1) % features.size());
    }

    private void setFeatureArt(Bitmap bitmap) {
        shownSong = null;
        // Recently played: the record stays in its sleeve.
        showPicture(bitmap, false, "feature\n" + features.get(featureIndex).showId);
    }

    private void stopFeatures() {
        main.removeCallbacks(rotate);
        features = new ArrayList<>();
        dots.removeAllViews();
        dots.setVisibility(View.GONE);
        credit.setVisibility(View.GONE);
    }

    private boolean sameShows(List<ShowArt.Feature> list) {
        if (list.size() != features.size()) return false;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).showId != features.get(i).showId) return false;
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

    /** Stops the carousel, the wall and the clock while the launcher is in the background. */
    void pause() {
        running = false;
        main.removeCallbacks(rotate);
        main.removeCallbacks(tick);
        wall.setRunning(false);
    }

    void resume() {
        running = true;
        main.removeCallbacks(rotate);
        if (features.size() > 1) main.postDelayed(rotate, ROTATE_MS);
        wall.setRunning(wall.getAlpha() > 0f);
        if (progressTrack != null) updateProgress();
    }

    void setVisible(boolean show) {
        visible = show;
        wall.setRunning(show && running && wall.getAlpha() > 0f); // no need to animate it off screen
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

    // ---- Drawing ----

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

    // ---- How far through the song ----

    /** A song: its progress takes the place of the remote's hints, if the player says how long it is. */
    private void showProgress(NowPlaying.Track track) {
        if (track.duration <= 0) {
            stopProgress();
            hints.setVisibility(View.GONE); // OK just opens the player; nothing to explain
            return;
        }
        progressTrack = track;
        hints.setVisibility(View.GONE);
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

    /** Cross-fades to a show's artwork, across the whole screen behind the text. */
    private void swapArt(Drawable sharp) {
        hideRecord();
        hideWall();
        cover.animate().cancel();
        cover.animate().alpha(0f).setDuration(120).start();
        photo.animate().cancel();
        photo.animate().alpha(0f).setDuration(120).withEndAction(() -> {
            art.setImageDrawable(sharp);
            photo.animate().alpha(1f).setDuration(260).start();
        }).start();
    }

    /** Shows an album cover as an LP, lit by the stage in its colours. */
    private void swapCover(Drawable picture, boolean playing, String song) {
        photo.animate().cancel();
        photo.animate().alpha(0f).setDuration(160).start();
        cover.animate().cancel();
        cover.animate().alpha(0f).setDuration(120).start();
        record.animate().cancel();
        record.setVisibility(View.VISIBLE);
        record.animate().alpha(1f).setDuration(280).start();
        record.show(picture, playing, song);
        hideWall();
    }

    private void hideWall() {
        wall.animate().cancel();
        if (wall.getAlpha() == 0f) {
            wall.setRunning(false);
            return;
        }
        wall.animate().alpha(0f).setDuration(250).withEndAction(() -> wall.setRunning(false)).start();
    }

    private void showWall() {
        wall.animate().cancel();
        wall.setRunning(running);
        wall.animate().alpha(1f).setDuration(300).start();
    }

    private void hideRecord() {
        record.animate().cancel();
        if (record.getVisibility() != View.VISIBLE) return;
        record.animate().alpha(0f).setDuration(160).withEndAction(() -> record.setVisibility(View.GONE)).start();
    }

    /** Cross-fades the card on the right to an app's banner, with the wall of lamps behind it. */
    private void showCard(Drawable picture, int width, ImageView.ScaleType scale, int pad) {
        hideRecord();
        photo.animate().cancel();
        photo.animate().alpha(0f).setDuration(160).start();
        cover.animate().cancel();
        cover.animate().alpha(0f).setDuration(120).withEndAction(() -> {
            ViewGroup.LayoutParams lp = cover.getLayoutParams();
            if (lp.width != width || lp.height != cardHeight) {
                lp.width = width;
                lp.height = cardHeight;
                cover.setLayoutParams(lp);
            }
            cover.setScaleType(scale);
            cover.setPadding(pad, pad, pad, pad);
            cover.setImageDrawable(picture);
            showWall();
            cover.animate().alpha(1f).setDuration(280).start();
        }).start();
    }

    // ---- Sound wave ----

    void setWaveSource(SoundWave source) {
        wave.setSource(source);
    }

    /** Fades the waveform in under the song, or away. It only animates while it's showing. */
    void showWave(boolean show) {
        wave.animate().cancel();
        if (show) {
            wave.setVisibility(View.VISIBLE);
            wave.setRunning(true);
            wave.animate().alpha(1f).setDuration(300).start();
        } else if (wave.getVisibility() == View.VISIBLE) {
            wave.animate().alpha(0f).setDuration(200).withEndAction(() -> {
                wave.setRunning(false);
                wave.setVisibility(View.GONE);
            }).start();
        }
    }

    /** Whether the waveform is on screen (for tests). */
    boolean showsWave() {
        return wave.getVisibility() == View.VISIBLE && wave.getAlpha() > 0.5f;
    }

    /** Whether the wall of lamps is on screen (for tests). */
    boolean showsLightWall() {
        return wall.getAlpha() > 0.5f;
    }

    /** The colour the wall glows with (for tests). */
    int wallColour() {
        return wall.keyColour();
    }

    /** Whether a show's artwork fills the screen (for tests). */
    boolean showsPhoto() {
        return photo.getAlpha() > 0.5f;
    }

    /** Whether a cover is on screen, as a card or an LP (for tests). */
    boolean showsCover() {
        return (cover.getAlpha() > 0.5f && cover.getDrawable() != null) || showsRecord();
    }

    /** Whether the LP is on screen (for tests). */
    boolean showsRecord() {
        return record.getVisibility() == View.VISIBLE && record.getAlpha() > 0.5f;
    }

    RecordView recordForTests() {
        return record;
    }
}
