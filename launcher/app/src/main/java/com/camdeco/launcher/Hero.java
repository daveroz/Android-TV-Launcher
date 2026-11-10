package com.camdeco.launcher;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * The big area at the top of Home. It shows one of three things for what has focus:
 * an app (just its banner on the right), a streaming app's shows (full-screen artwork that
 * changes every few seconds, like the Apple TV home screen), or what's playing (the cover, or a
 * video's thumbnail full-screen).
 */
final class Hero {

    /** Where show images come from; load() calls back on the main thread. */
    interface Images {
        Bitmap cached(String url);

        void load(String url, java.util.function.Consumer<Bitmap> done);
    }

    private static final long ROTATE_MS = 8000;
    private static final java.util.regex.Pattern EPISODE = java.util.regex.Pattern.compile("S(\\d+) E(\\d+)");

    private final Activity activity;
    private final Handler main;
    private final View root;
    private final View text;
    private final ImageView art;
    /** The rounded card on the right: an album cover, or an app's banner. */
    private final ImageView cover;
    /** The app's colours, behind its banner card. */
    private final ColorFieldView field;
    /** The music's waveform under the song. */
    private final SoundWaveView wave;
    /** What's playing, as an LP: the cover is the sleeve, and the record spins while it plays. */
    private final RecordView record;
    private final int cardHeight;
    private final ImageView ambient;
    private final TextView title;
    private final TextView kind;
    private final TextView chip;
    private final TextView desc;
    private final LinearLayout dots;
    private final TextView credit;
    private final View holdKey;
    private final View holdLabel;
    private final int artWidth;
    private final int artHeight;
    private final float density;

    private float scrollFade = 1f;
    private boolean visible = true;
    private boolean running = true;
    private List<ShowArt.Feature> features = new ArrayList<>();
    private int featureIndex;
    private Images images;
    private final Runnable rotate = this::nextFeature;
    /** The cover on screen, so play/pause updates don't fade the same picture again. */
    private Object shownCover;

    Hero(Activity activity, Handler main) {
        this.activity = activity;
        this.main = main;
        root = activity.findViewById(R.id.hero);
        text = activity.findViewById(R.id.hero_text);
        art = activity.findViewById(R.id.hero_art);
        cover = activity.findViewById(R.id.hero_cover);
        cover.setClipToOutline(true); // round the picture's corners to the card
        field = activity.findViewById(R.id.hero_field);
        wave = activity.findViewById(R.id.hero_wave);
        record = activity.findViewById(R.id.hero_record);
        cardHeight = activity.getResources().getDimensionPixelSize(R.dimen.hero_card_height);
        ambient = activity.findViewById(R.id.hero_ambient);
        title = activity.findViewById(R.id.hero_title);
        kind = activity.findViewById(R.id.hero_kind);
        chip = activity.findViewById(R.id.hero_version);
        desc = activity.findViewById(R.id.hero_desc);
        dots = activity.findViewById(R.id.hero_dots);
        credit = activity.findViewById(R.id.hero_credit);
        holdKey = activity.findViewById(R.id.hero_hold_key);
        holdLabel = activity.findViewById(R.id.hero_hold_label);
        artWidth = activity.getResources().getDimensionPixelSize(R.dimen.hero_art_width);
        artHeight = activity.getResources().getDimensionPixelSize(R.dimen.hero_art_height);
        density = activity.getResources().getDisplayMetrics().density;
    }

    // ---- What to show ----

    /** A plain app: just its banner (set with setAppArt), no name or version text. */
    void showApp() {
        stopFeatures();
        setText(null, null, null, null);
        showHoldHint(true);
    }

    /**
     * A plain app: its banner (or icon) as a card on the right, over soft glows of its own colours
     * and the icons' street-map grid.
     */
    void setAppArt(Drawable drawable, boolean isBanner, AppColors colours) {
        shownCover = null;
        field.setColours(colours);
        if (isBanner) {
            showCard(AppRepository.copy(drawable), cardHeight * 16 / 9, ImageView.ScaleType.CENTER_CROP, 0, null);
        } else {
            showCard(AppRepository.copy(drawable), cardHeight, ImageView.ScaleType.FIT_CENTER, (int) (40 * density), null);
        }
    }

    /**
     * Items that take turns in the hero: a streaming app's shows, or what was recently played in a
     * media app. creditText credits the source (TVmaze), or null for none.
     */
    void showFeatures(List<ShowArt.Feature> list, Images source, String creditText) {
        if (list.isEmpty()) return;
        if (sameShows(list)) return; // already showing these, don't restart the turns
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
        String detail = track.album != null ? track.album + " · " + track.app : track.app;
        setText(track.title, track.artist, null, detail);
        showHoldHint(false);
        Object picture = track.art != null ? track.art : track.packageName;
        if (picture.equals(shownCover)) {
            record.setPlaying(track.playing); // same song: play or pause
            return;
        }
        if (track.art != null) {
            showPicture(track.art, track.playing);
        } else if (fallbackIcon != null) {
            swapCover(AppRepository.copy(fallbackIcon), AppRepository.copy(fallbackIcon), track.playing);
        }
        shownCover = picture;
    }

    /**
     * A wide picture (a show, a YouTube thumbnail) fills the screen. A square one (an album
     * cover) becomes an LP on the right, with a soft blur of itself across the screen; its record
     * comes out and spins while {@code playing}.
     */
    private void showPicture(Bitmap bitmap, boolean playing) {
        if (bitmap.getWidth() > bitmap.getHeight() * 1.4f) {
            swapArt(true, new BitmapDrawable(activity.getResources(), bitmap), null, ImageView.ScaleType.CENTER_CROP, 0);
        } else {
            swapCover(new BitmapDrawable(activity.getResources(), bitmap),
                    new BitmapDrawable(activity.getResources(), bitmap), playing);
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
        // The current show's dot is a wider amber pill. (Plain views need real widths: a
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
        shownCover = null;
        showPicture(bitmap, false); // recently played: the record stays in its sleeve
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
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.setMarginEnd(size);
            dots.addView(dot, lp);
        }
    }

    // ---- Lifecycle, paging, scrolling ----

    /** Stops the carousel while the launcher is in the background. */
    void pause() {
        running = false;
        main.removeCallbacks(rotate);
    }

    void resume() {
        running = true;
        main.removeCallbacks(rotate);
        if (features.size() > 1) main.postDelayed(rotate, ROTATE_MS);
    }

    void setVisible(boolean show) {
        visible = show;
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
        holdKey.setVisibility(show ? View.VISIBLE : View.GONE);
        holdLabel.setVisibility(show ? View.VISIBLE : View.GONE);
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

    /**
     * Cross-fades to new art. Full-screen art (shows) fills the screen behind the text; app art
     * sits in the box on the right with a blurred copy tinting the screen.
     */
    private void swapArt(boolean fullScreen, Drawable sharp, Drawable blurred, ImageView.ScaleType scale, int pad) {
        hideRecord();
        cover.animate().cancel();
        cover.animate().alpha(0f).setDuration(120).start();
        field.animate().cancel();
        field.animate().alpha(0f).setDuration(250).start();
        art.animate().cancel();
        art.animate().alpha(0f).setDuration(120).withEndAction(() -> {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) art.getLayoutParams();
            int w = fullScreen ? ViewGroup.LayoutParams.MATCH_PARENT : artWidth;
            int h = fullScreen ? ViewGroup.LayoutParams.MATCH_PARENT : artHeight;
            if (lp.width != w || lp.height != h) {
                lp.width = w;
                lp.height = h;
                art.setLayoutParams(lp);
            }
            art.setScaleType(scale);
            art.setPadding(pad, pad, pad, pad);
            art.setImageDrawable(sharp);
            if (blurred != null) ambient.setImageDrawable(blurred);
            ambient.animate().alpha(fullScreen ? 0f : 0.4f).setDuration(250).start();
            art.animate().alpha(1f).setDuration(260).start();
        }).start();
    }

    /** Shows an album cover as an LP, with the cover blurred across the whole screen behind it. */
    private void swapCover(Drawable picture, Drawable blurred, boolean playing) {
        art.animate().cancel();
        art.animate().alpha(0f).setDuration(160).start();
        cover.animate().cancel();
        cover.animate().alpha(0f).setDuration(120).start();
        record.animate().cancel();
        record.setVisibility(View.VISIBLE);
        record.animate().alpha(1f).setDuration(280).start();
        record.show(picture, playing);
        ambient.setImageDrawable(blurred);
        ambient.animate().alpha(0.85f).setDuration(300).start();
        field.animate().alpha(0f).setDuration(300).start();
    }

    private void hideRecord() {
        record.animate().cancel();
        if (record.getVisibility() != View.VISIBLE) return;
        record.animate().alpha(0f).setDuration(160).withEndAction(() -> record.setVisibility(View.GONE)).start();
    }

    /**
     * Cross-fades the card on the right to a new picture. With a blurred copy, that blur fills the
     * screen (album covers); without one, the app's colour field does (app banners).
     */
    private void showCard(Drawable picture, int width, ImageView.ScaleType scale, int pad, Drawable blurred) {
        hideRecord();
        art.animate().cancel();
        art.animate().alpha(0f).setDuration(160).start();
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
            if (blurred != null) ambient.setImageDrawable(blurred);
            ambient.animate().alpha(blurred != null ? 0.85f : 0f).setDuration(300).start();
            field.animate().alpha(blurred != null ? 0f : 1f).setDuration(300).start();
            cover.animate().alpha(1f).setDuration(280).start();
        }).start();
    }

    // ---- Sound wave ----

    void setWaveSource(SoundWave source) {
        wave.setSource(source);
    }

    void setWaveColours(AppColors colours) {
        wave.setColours(colours);
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

    /** Whether the app's colour field is on screen (for tests). */
    boolean showsColourField() {
        return field.getAlpha() > 0.5f;
    }

    /** The colour the field is glowing with (for tests). */
    int fieldColour() {
        return field.vividColour();
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
