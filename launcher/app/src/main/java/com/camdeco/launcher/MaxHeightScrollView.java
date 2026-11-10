package com.camdeco.launcher;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ScrollView;

/** A ScrollView that never grows past about half the screen, so long lists scroll inside a sheet. */
public class MaxHeightScrollView extends ScrollView {
    public MaxHeightScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
        // Focus belongs on the rows inside, never on the scroll container itself.
        setFocusable(false);
        setDescendantFocusability(FOCUS_AFTER_DESCENDANTS);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int max = (int) (getResources().getDisplayMetrics().heightPixels * 0.55f);
        int size = View.MeasureSpec.getSize(heightMeasureSpec);
        int limit = View.MeasureSpec.getMode(heightMeasureSpec) == View.MeasureSpec.UNSPECIFIED
                ? max : Math.min(size, max);
        super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(limit, View.MeasureSpec.AT_MOST));
    }
}
