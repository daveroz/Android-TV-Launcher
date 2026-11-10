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

    private final Activity activity;
    private final Handler main;
    private final View root;
    private final View text;
    private final ImageView art;
    private final ImageView ambient;
    private final TextView title;
    private final TextView kind;
    private final TextView chip;
    private final TextView desc;
    private final LinearLayout dots;
    private final View credit;
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

    void setAppArt(Drawable drawable, boolean isBanner) {
        shownCover = null;
        int pad = isBanner ? 0 : (int) (72 * density);
        swapArt(false, AppRepository.copy(drawable), AppRepository.copy(drawable),
                isBanner ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.FIT_CENTER, pad);
    }

    /** A streaming app's shows; the first one shows now and the rest take turns. */
    void showFeatures(List<ShowArt.Feature> list, Images source) {
        if (list.isEmpty()) return;
        if (sameShows(list)) return; // already showing these, don't restart the turns
        features = new ArrayList<>(list);
        images = source;
        credit.setVisibility(View.VISIBLE);
        buildDots();
        showFeature(0);
    }

    void showTrack(NowPlaying.Track track, Drawable fallbackIcon) {
        stopFeatures();
        String detail = track.album != null ? track.album + " · " + track.app : track.app;
        setText(track.title, track.artist, null, detail);
        showHoldHint(false);
        Object picture = track.art != null ? track.art : track.packageName;
        if (picture.equals(shownCover)) return; // same picture: only the text changed
        shownCover = picture;
        if (track.art != null && track.art.getWidth() > track.art.getHeight() * 1.4f) {
            // Wide art (a YouTube video's thumbnail) fills the screen, like a show.
            swapArt(true, new BitmapDrawable(activity.getResources(), track.art), null, ImageView.ScaleType.CENTER_CROP, 0);
            return;
        }
        // A square cover (Spotify) sits on the right, its colours tinting the whole screen.
        Drawable cover = track.art != null ? new BitmapDrawable(activity.getResources(), track.art) : fallbackIcon;
        if (cover != null) {
            swapArt(false, AppRepository.copy(cover), AppRepository.copy(cover),
                    ImageView.ScaleType.FIT_CENTER, (int) (24 * density));
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
        setText(f.show, f.genres != null ? f.genres + " · " + f.channel : f.channel, f.episode, f.summary);
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

    private void nextFeature() {
        if (features.size() > 1) showFeature((featureIndex + 1) % features.size());
    }

    private void setFeatureArt(Bitmap bitmap) {
        shownCover = null;
        swapArt(true, new BitmapDrawable(activity.getResources(), bitmap), null, ImageView.ScaleType.CENTER_CROP, 0);
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
}
