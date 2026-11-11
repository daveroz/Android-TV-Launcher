package com.camdeco.launcher;

import android.os.Build;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * App cards, used both in the Home rows and in the Apps grid. The focused card lifts and brightens
 * with its brackets on; the others step back to half strength, so focus is the brightest thing on
 * screen.
 */
final class CardAdapter extends RecyclerView.Adapter<CardAdapter.Holder> {

    interface Listener {
        void onOpen(AppEntry app);

        void onOptions(AppEntry app);

        void onCardFocused(View card, AppEntry app, boolean focused);
    }

    private static final float FOCUS_SCALE = 1.06f;
    /** How strong the cards without focus are: Home's rows, and the Apps grid with its names. */
    private static final float ROW_DIM = 0.55f, GRID_DIM = 0.65f;

    private final List<AppEntry> apps = new ArrayList<>();
    private final Listener listener;
    /** Grid cells stretch to their column; row cards keep their fixed width. */
    private final boolean fillWidth;
    private String movingPackage;

    CardAdapter(Listener listener, boolean fillWidth) {
        this.listener = listener;
        this.fillWidth = fillWidth;
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
        view.setOnClickListener(v -> {
            AppEntry app = holder.app();
            if (app != null) listener.onOpen(app);
        });
        view.setOnLongClickListener(v -> {
            AppEntry app = holder.app();
            if (app != null) listener.onOptions(app);
            return true;
        });
        float density = parent.getResources().getDisplayMetrics().density;
        float lift = density * 12, rise = density * 4;
        float dim = fillWidth ? GRID_DIM : ROW_DIM;
        view.setAlpha(dim);
        view.setOnFocusChangeListener((v, focused) -> {
            if (focused) glow(holder.card);
            holder.card.animate()
                    .scaleX(focused ? FOCUS_SCALE : 1f)
                    .scaleY(focused ? FOCUS_SCALE : 1f)
                    .translationY(focused ? -rise : 0f)
                    .translationZ(focused ? lift : 0f)
                    .setDuration(150)
                    .start();
            v.animate().alpha(focused ? 1f : dim).setDuration(150).start();
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
        // Home's rows show just the cards. The Apps page names each app, and says when one is moving.
        holder.title.setVisibility(fillWidth ? View.VISIBLE : View.GONE);
        holder.title.setText(app.label);
        holder.subtitle.setVisibility(moving ? View.VISIBLE : View.GONE);
        holder.subtitle.setText(R.string.moving);
        // A recycled view may still carry the focus lift of the app it showed before.
        boolean focused = view.isFocused();
        float density = view.getResources().getDisplayMetrics().density;
        holder.card.setScaleX(focused ? FOCUS_SCALE : 1f);
        holder.card.setScaleY(focused ? FOCUS_SCALE : 1f);
        holder.card.setTranslationY(focused ? -4 * density : 0f);
        view.setAlpha(focused ? 1f : fillWidth ? GRID_DIM : ROW_DIM);
        // The focused card can be given a different app when the rows refresh (hiding an
        // app, say). Focus doesn't move, so tell the listener; else the hero shows the old app.
        if (focused) listener.onCardFocused(view, app, true);
    }

    /**
     * The lifted card's shadow becomes a glow in the scene's colours: the key all around, the fill
     * where the shadow falls, below it.
     */
    static void glow(View card) {
        if (Build.VERSION.SDK_INT < 28 || !(card.getContext() instanceof Scene.Holder)) return;
        Scene scene = ((Scene.Holder) card.getContext()).scene();
        card.setOutlineAmbientShadowColor(scene.key);
        card.setOutlineSpotShadowColor(scene.fill);
        card.invalidate();
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
