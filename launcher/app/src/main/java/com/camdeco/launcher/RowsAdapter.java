package com.camdeco.launcher;

import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.leanback.widget.BaseGridView;
import androidx.leanback.widget.HorizontalGridView;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * The Home page: an optional Now playing row, then titled rows, each a sideways-scrolling line
 * of app cards.
 */
final class RowsAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    static final class Row {
        final String key;
        final String title;
        final List<AppEntry> apps;
        final boolean nowPlaying;

        Row(String key, String title, List<AppEntry> apps) {
            this(key, title, apps, false);
        }

        private Row(String key, String title, List<AppEntry> apps, boolean nowPlaying) {
            this.key = key;
            this.title = title;
            this.apps = apps;
            this.nowPlaying = nowPlaying;
        }

        static Row nowPlaying(String title) {
            return new Row("now_playing", title, Collections.emptyList(), true);
        }
    }

    /** What the Now playing row's buttons do. */
    interface NowPlayingListener {
        int OPEN = 0;
        int PREVIOUS = 1;
        int PLAY_PAUSE = 2;
        int NEXT = 3;

        void onNowPlayingAction(int action);

        void onNowPlayingFocused(View view, boolean focused);

        /** The player app's icon, for tracks without cover art. */
        Drawable appIcon(String packageName);
    }

    private static final int TYPE_CARDS = 0;
    private static final int TYPE_NOW_PLAYING = 1;
    private static final Object TRACK_CHANGED = new Object();

    private final List<Row> rows = new ArrayList<>();
    private final CardAdapter.Listener listener;
    private final NowPlayingListener nowPlayingListener;
    private final RecyclerView.RecycledViewPool cardPool = new RecyclerView.RecycledViewPool();
    private Set<String> favourites = Collections.emptySet();
    private NowPlaying.Track track;

    RowsAdapter(CardAdapter.Listener listener, NowPlayingListener nowPlayingListener) {
        this.listener = listener;
        this.nowPlayingListener = nowPlayingListener;
        setHasStableIds(true);
    }

    void submit(List<Row> list, Set<String> favourites) {
        rows.clear();
        rows.addAll(list);
        this.favourites = favourites;
        notifyDataSetChanged();
    }

    List<Row> rows() {
        return rows;
    }

    boolean hasNowPlaying() {
        return !rows.isEmpty() && rows.get(0).nowPlaying;
    }

    /** New track details: updates the Now playing row in place, so focus stays put. */
    void setTrack(NowPlaying.Track newTrack) {
        track = newTrack;
        if (hasNowPlaying()) notifyItemChanged(0, TRACK_CHANGED);
    }

    NowPlaying.Track track() {
        return track;
    }

    /** {row, column} of an app on the Home page, or null if it isn't shown. */
    int[] find(String packageName) {
        for (int r = 0; r < rows.size(); r++) {
            List<AppEntry> apps = rows.get(r).apps;
            for (int c = 0; c < apps.size(); c++) {
                if (apps.get(c).packageName.equals(packageName)) return new int[]{r, c};
            }
        }
        return null;
    }

    @Override
    public int getItemCount() {
        return rows.size();
    }

    @Override
    public long getItemId(int position) {
        return rows.get(position).key.hashCode();
    }

    @Override
    public int getItemViewType(int position) {
        return rows.get(position).nowPlaying ? TYPE_NOW_PLAYING : TYPE_CARDS;
    }

    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_NOW_PLAYING) {
            return new NowPlayingHolder(inflater.inflate(R.layout.item_now_playing, parent, false), nowPlayingListener);
        }
        return new Holder(inflater.inflate(R.layout.item_row, parent, false), listener, cardPool);
    }

    @Override
    public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
        if (holder instanceof NowPlayingHolder) {
            ((NowPlayingHolder) holder).bind(track);
            return;
        }
        Row row = rows.get(position);
        Holder cards = (Holder) holder;
        cards.title.setText(row.title);
        cards.cards.submit(row.apps, favourites);
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final TextView title;
        final HorizontalGridView grid;
        final CardAdapter cards;

        Holder(View view, CardAdapter.Listener listener, RecyclerView.RecycledViewPool pool) {
            super(view);
            title = view.findViewById(R.id.row_title);
            grid = view.findViewById(R.id.row_grid);
            int gap = view.getResources().getDimensionPixelSize(R.dimen.card_gap);
            int start = view.getResources().getDimensionPixelSize(R.dimen.overscan_h);
            grid.setRowHeight(ViewGroup.LayoutParams.WRAP_CONTENT);
            grid.setHorizontalSpacing(gap);
            // The focused card settles at the left margin, so focus always lands in the same spot.
            grid.setWindowAlignment(BaseGridView.WINDOW_ALIGN_BOTH_EDGE);
            grid.setWindowAlignmentOffsetPercent(BaseGridView.WINDOW_ALIGN_OFFSET_PERCENT_DISABLED);
            grid.setWindowAlignmentOffset(start);
            grid.setItemAlignmentOffsetPercent(0);
            grid.setRecycledViewPool(pool);
            cards = new CardAdapter(listener, false);
            grid.setAdapter(cards);
        }
    }

    static final class NowPlayingHolder extends RecyclerView.ViewHolder {
        final View open;
        final ImageView art;
        final TextView title;
        final TextView artist;
        final TextView app;
        final ImageButton play;
        final ProgressBar progress;
        private final NowPlayingListener listener;
        private NowPlaying.Track track;

        NowPlayingHolder(View view, NowPlayingListener listener) {
            super(view);
            this.listener = listener;
            open = view.findViewById(R.id.np_open);
            art = view.findViewById(R.id.np_art);
            art.setClipToOutline(true);
            title = view.findViewById(R.id.np_title);
            artist = view.findViewById(R.id.np_artist);
            app = view.findViewById(R.id.np_app);
            play = view.findViewById(R.id.np_play);
            progress = view.findViewById(R.id.np_progress);
            View previous = view.findViewById(R.id.np_previous);
            View next = view.findViewById(R.id.np_next);
            open.setOnClickListener(v -> listener.onNowPlayingAction(NowPlayingListener.OPEN));
            previous.setOnClickListener(v -> listener.onNowPlayingAction(NowPlayingListener.PREVIOUS));
            play.setOnClickListener(v -> listener.onNowPlayingAction(NowPlayingListener.PLAY_PAUSE));
            next.setOnClickListener(v -> listener.onNowPlayingAction(NowPlayingListener.NEXT));
            View.OnFocusChangeListener focus = (v, focused) -> {
                view.findViewById(R.id.row_title).setActivated(focused);
                listener.onNowPlayingFocused(v, focused);
            };
            for (View v : new View[]{open, previous, play, next}) v.setOnFocusChangeListener(focus);
        }

        void bind(NowPlaying.Track t) {
            track = t;
            if (t == null) return;
            title.setText(t.title);
            artist.setText(t.artist);
            artist.setVisibility(t.artist == null ? View.GONE : View.VISIBLE);
            app.setText(t.app);
            if (t.art != null) art.setImageBitmap(t.art);
            else art.setImageDrawable(listener.appIcon(t.packageName));
            play.setImageResource(t.playing ? R.drawable.ic_pause : R.drawable.ic_play);
            showProgress(NowPlaying.now());
        }

        /** Moves the progress bar on; called every second while playing. */
        void showProgress(long elapsedRealtime) {
            if (track == null || track.duration <= 0) {
                progress.setVisibility(View.INVISIBLE);
                return;
            }
            progress.setVisibility(View.VISIBLE);
            progress.setProgress((int) (1000 * track.positionAt(elapsedRealtime) / track.duration));
        }
    }
}
