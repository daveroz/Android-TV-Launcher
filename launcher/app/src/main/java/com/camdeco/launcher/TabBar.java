package com.camdeco.launcher;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.LinearLayout;

import java.util.ArrayList;

/**
 * The pill of tabs at the top. When focus comes up from the cards it always lands on the
 * current tab, not on whichever tab happens to be closest, so it never switches pages by accident.
 */
public class TabBar extends LinearLayout {
    private View selectedTab;

    public TabBar(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    void setSelectedTab(View tab) {
        selectedTab = tab;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            child.setSelected(child == tab);
        }
    }

    @Override
    public void addFocusables(ArrayList<View> views, int direction, int focusableMode) {
        if (!hasFocus() && selectedTab != null && selectedTab.getVisibility() == VISIBLE
                && direction != FOCUS_FORWARD && direction != FOCUS_BACKWARD) {
            views.add(selectedTab);
            return;
        }
        super.addFocusables(views, direction, focusableMode);
    }
}
