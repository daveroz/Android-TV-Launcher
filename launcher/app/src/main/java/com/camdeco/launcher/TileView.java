package com.camdeco.launcher;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.FrameLayout;

/** A tile that is always 16:9, the shape of Android TV app banners. */
public class TileView extends FrameLayout {
    public TileView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setClipToOutline(true); // round the banner's corners to match the background
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        super.onMeasure(
                MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(width * 9 / 16, MeasureSpec.EXACTLY));
    }
}
