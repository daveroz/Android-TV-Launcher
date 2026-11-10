package com.camdeco.launcher;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/** Binds apps to tiles in the home grid. */
final class AppAdapter extends RecyclerView.Adapter<AppAdapter.Holder> {

    interface Listener {
        void onOpen(AppEntry app);

        void onOptions(AppEntry app);
    }

    private static final float FOCUS_SCALE = 1.1f;

    private final List<AppEntry> apps = new ArrayList<>();
    private final Listener listener;
    private String movingPackage;

    AppAdapter(Listener listener) {
        this.listener = listener;
        setHasStableIds(true); // lets the grid keep focus on the same app across reloads
    }

    void submit(List<AppEntry> list) {
        apps.clear();
        apps.addAll(list);
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

    /** Marks the tile being moved, or none when packageName is null. */
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
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_app, parent, false);
        Holder holder = new Holder(view);
        view.setOnClickListener(v -> {
            AppEntry app = holder.app();
            if (app != null) listener.onOpen(app);
        });
        view.setOnLongClickListener(v -> {
            AppEntry app = holder.app();
            if (app != null) listener.onOptions(app);
            return true;
        });
        float lift = parent.getResources().getDisplayMetrics().density * 12;
        view.setOnFocusChangeListener((v, focused) -> v.animate()
                .scaleX(focused ? FOCUS_SCALE : 1f)
                .scaleY(focused ? FOCUS_SCALE : 1f)
                .translationZ(focused ? lift : 0f)
                .setDuration(150)
                .start());
        return holder;
    }

    @Override
    public void onBindViewHolder(Holder holder, int position) {
        AppEntry app = apps.get(position);
        holder.itemView.setContentDescription(app.label);
        holder.itemView.setActivated(app.packageName.equals(movingPackage));
        if (app.banner != null) {
            holder.banner.setImageDrawable(app.banner);
            holder.banner.setVisibility(View.VISIBLE);
            holder.iconGroup.setVisibility(View.GONE);
        } else {
            holder.banner.setImageDrawable(null);
            holder.banner.setVisibility(View.GONE);
            holder.icon.setImageDrawable(app.icon);
            holder.label.setText(app.label);
            holder.iconGroup.setVisibility(View.VISIBLE);
        }
        // A recycled view may still carry the focus zoom of the app it showed before.
        boolean focused = holder.itemView.isFocused();
        holder.itemView.setScaleX(focused ? FOCUS_SCALE : 1f);
        holder.itemView.setScaleY(focused ? FOCUS_SCALE : 1f);
    }

    final class Holder extends RecyclerView.ViewHolder {
        final ImageView banner;
        final View iconGroup;
        final ImageView icon;
        final TextView label;

        Holder(View view) {
            super(view);
            banner = view.findViewById(R.id.banner);
            iconGroup = view.findViewById(R.id.icon_group);
            icon = view.findViewById(R.id.icon);
            label = view.findViewById(R.id.label);
        }

        AppEntry app() {
            int position = getBindingAdapterPosition();
            return position == RecyclerView.NO_POSITION ? null : apps.get(position);
        }
    }
}
