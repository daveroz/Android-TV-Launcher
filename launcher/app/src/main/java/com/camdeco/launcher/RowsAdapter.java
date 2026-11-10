package com.camdeco.launcher;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.leanback.widget.BaseGridView;
import androidx.leanback.widget.HorizontalGridView;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/** The Home page: titled rows, each a sideways-scrolling line of app cards. */
final class RowsAdapter extends RecyclerView.Adapter<RowsAdapter.Holder> {

    static final class Row {
        final String key;
        final String title;
        final List<AppEntry> apps;

        Row(String key, String title, List<AppEntry> apps) {
            this.key = key;
            this.title = title;
            this.apps = apps;
        }
    }

    private final List<Row> rows = new ArrayList<>();
    private final CardAdapter.Listener listener;
    private final RecyclerView.RecycledViewPool cardPool = new RecyclerView.RecycledViewPool();

    RowsAdapter(CardAdapter.Listener listener) {
        this.listener = listener;
        setHasStableIds(true);
    }

    void submit(List<Row> list) {
        rows.clear();
        rows.addAll(list);
        notifyDataSetChanged();
    }

    List<Row> rows() {
        return rows;
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
    public Holder onCreateViewHolder(ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_row, parent, false);
        return new Holder(view, listener, cardPool);
    }

    @Override
    public void onBindViewHolder(Holder holder, int position) {
        Row row = rows.get(position);
        holder.title.setText(row.title);
        holder.cards.submit(row.apps);
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
}
