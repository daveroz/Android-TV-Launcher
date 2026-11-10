package com.camdeco.launcher;

import android.os.Build;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/** App cards, used both in the Home rows and in the Apps grid. */
final class CardAdapter extends RecyclerView.Adapter<CardAdapter.Holder> {

    interface Listener {
        void onOpen(AppEntry app);

        void onOptions(AppEntry app);

        void onCardFocused(View card, AppEntry app, boolean focused);
    }

    private static final float FOCUS_SCALE = 1.1f;

    private final List<AppEntry> apps = new ArrayList<>();
    private final Listener listener;
    /** Grid cells stretch to their column; row cards keep their fixed width. */
    private final boolean fillWidth;
    private Set<String> favourites = Collections.emptySet();
    private String movingPackage;

    CardAdapter(Listener listener, boolean fillWidth) {
        this.listener = listener;
        this.fillWidth = fillWidth;
        setHasStableIds(true); // lets the grid keep focus on the same app across reloads
    }

    void submit(List<AppEntry> list, Set<String> favourites) {
        apps.clear();
        apps.addAll(list);
        this.favourites = favourites;
        notifyDataSetChanged();
    }

    List<AppEntry> items() {
        return apps;
    }

    int indexOf(String packageName) {
        for (int i = 0; i < apps.size(); i++) {
            if (apps.get(i).packageName.equals(packageName)) return i;
        }
        return -1;
    }

    void move(int from, int to) {
        apps.add(to, apps.remove(from));
        notifyItemMoved(from, to);
    }

    /** Marks the card being moved, or none when packageName is null. */
    void setMoving(String packageName) {
        int old = indexOf(String.valueOf(movingPackage));
        movingPackage = packageName;
        if (old >= 0) notifyItemChanged(old);
        int now = indexOf(String.valueOf(packageName));
        if (now >= 0) notifyItemChanged(now);
    }

    @Override
    public int getItemCount() {
        return apps.size();
    }

    @Override
    public long getItemId(int position) {
        return apps.get(position).packageName.hashCode();
    }

    @Override
    public Holder onCreateViewHolder(ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_card, parent, false);
        if (fillWidth) view.getLayoutParams().width = ViewGroup.LayoutParams.MATCH_PARENT;
        Holder holder = new Holder(view);
        if (Build.VERSION.SDK_INT >= 28) {
            // A warm amber glow under the lifted card instead of a grey shadow.
            int amber = parent.getContext().getColor(R.color.amber);
            holder.card.setOutlineSpotShadowColor(amber);
            holder.card.setOutlineAmbientShadowColor(amber);
        }
        view.setOnClickListener(v -> {
            AppEntry app = holder.app();
            if (app != null) listener.onOpen(app);
        });
        view.setOnLongClickListener(v -> {
            AppEntry app = holder.app();
            if (app != null) listener.onOptions(app);
            return true;
        });
        float lift = parent.getResources().getDisplayMetrics().density * 14;
        view.setOnFocusChangeListener((v, focused) -> {
            holder.card.animate()
                    .scaleX(focused ? FOCUS_SCALE : 1f)
                    .scaleY(focused ? FOCUS_SCALE : 1f)
                    .translationZ(focused ? lift : 0f)
                    .setDuration(160)
                    .start();
            AppEntry app = holder.app();
            if (app != null) listener.onCardFocused(v, app, focused);
        });
        return holder;
    }

    @Override
    public void onBindViewHolder(Holder holder, int position) {
        AppEntry app = apps.get(position);
        View view = holder.itemView;
        view.setTag(R.id.tag_app, app);
        view.setContentDescription(app.label);
        boolean moving = app.packageName.equals(movingPackage);
        view.setActivated(moving);
        if (app.banner != null) {
            holder.banner.setImageDrawable(app.banner);
            holder.banner.setVisibility(View.VISIBLE);
            holder.icon.setVisibility(View.GONE);
        } else {
            holder.banner.setImageDrawable(null);
            holder.banner.setVisibility(View.GONE);
            holder.icon.setImageDrawable(app.icon);
            holder.icon.setVisibility(View.VISIBLE);
        }
        holder.title.setText(app.label);
        int subtitle = moving ? R.string.moving
                : favourites.contains(app.packageName) ? R.string.kind_favourite
                : app.leanback ? R.string.kind_tv : R.string.kind_phone;
        holder.subtitle.setText(subtitle);
        // A recycled view may still carry the focus lift of the app it showed before.
        boolean focused = view.isFocused();
        holder.card.setScaleX(focused ? FOCUS_SCALE : 1f);
        holder.card.setScaleY(focused ? FOCUS_SCALE : 1f);
    }

    final class Holder extends RecyclerView.ViewHolder {
        final View card;
        final ImageView banner;
        final ImageView icon;
        final TextView title;
        final TextView subtitle;

        Holder(View view) {
            super(view);
            card = view.findViewById(R.id.card);
            banner = view.findViewById(R.id.banner);
            icon = view.findViewById(R.id.icon);
            title = view.findViewById(R.id.title);
            subtitle = view.findViewById(R.id.subtitle);
        }

        AppEntry app() {
            int position = getBindingAdapterPosition();
            return position == RecyclerView.NO_POSITION ? null : apps.get(position);
        }
    }
}
