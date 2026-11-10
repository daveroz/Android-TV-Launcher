package com.camdeco.launcher;

import android.app.Activity;
import android.app.Dialog;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/** Menus and questions, drawn as Worldly's floating graphite card with amber focus. */
final class Sheet {
    interface OnPick {
        void onPick(int which);
    }

    interface OnConfirm {
        void onConfirm(boolean[] checked);
    }

    private Sheet() {
    }

    /** A list of choices; picking one closes the sheet. */
    static Dialog pick(Activity activity, CharSequence title, CharSequence message,
                       List<? extends CharSequence> items, OnPick onPick) {
        Dialog dialog = create(activity, title, message);
        LinearLayout rows = dialog.findViewById(R.id.sheet_rows);
        for (int i = 0; i < items.size(); i++) {
            int which = i;
            addRow(rows, items.get(i)).setOnClickListener(v -> {
                dialog.dismiss();
                onPick.onPick(which);
            });
        }
        show(dialog, rows);
        return dialog;
    }

    /** Tick any number of items, then confirm or cancel. */
    static Dialog checklist(Activity activity, CharSequence title, List<? extends CharSequence> items,
                            CharSequence confirm, OnConfirm onConfirm) {
        Dialog dialog = create(activity, title, null);
        LinearLayout rows = dialog.findViewById(R.id.sheet_rows);
        boolean[] checked = new boolean[items.size()];
        for (int i = 0; i < items.size(); i++) {
            int which = i;
            TextView row = addRow(rows, items.get(i));
            row.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.check, 0);
            row.setOnClickListener(v -> {
                checked[which] = !checked[which];
                v.setActivated(checked[which]);
            });
        }
        View divider = new View(activity);
        divider.setBackgroundResource(R.drawable.divider);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1);
        int margin = activity.getResources().getDimensionPixelSize(R.dimen.card_gap);
        lp.setMargins(margin, margin / 2, margin, margin / 2);
        rows.addView(divider, lp);
        addRow(rows, confirm).setOnClickListener(v -> {
            dialog.dismiss();
            onConfirm.onConfirm(checked);
        });
        addRow(rows, activity.getString(R.string.cancel)).setOnClickListener(v -> dialog.dismiss());
        show(dialog, rows);
        return dialog;
    }

    private static Dialog create(Activity activity, CharSequence title, CharSequence message) {
        Dialog dialog = new Dialog(activity, R.style.Theme_Launcher_Sheet);
        dialog.setContentView(R.layout.sheet);
        ((TextView) dialog.findViewById(R.id.sheet_title)).setText(title);
        if (message != null) {
            TextView text = dialog.findViewById(R.id.sheet_message);
            text.setText(message);
            text.setVisibility(View.VISIBLE);
        }
        return dialog;
    }

    private static TextView addRow(LinearLayout rows, CharSequence label) {
        TextView row = (TextView) LayoutInflater.from(rows.getContext())
                .inflate(R.layout.sheet_row, rows, false);
        row.setText(label);
        rows.addView(row);
        return row;
    }

    private static void show(Dialog dialog, LinearLayout rows) {
        if (rows.getChildCount() > 0) {
            View first = rows.getChildAt(0);
            first.setFocusedByDefault(true); // what the window focuses when it opens
            dialog.show();
            first.requestFocus();
            first.post(first::requestFocus);
        } else {
            dialog.show();
        }
    }
}
