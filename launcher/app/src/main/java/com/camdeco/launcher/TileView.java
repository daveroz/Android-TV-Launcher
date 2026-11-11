package com.camdeco.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.StateSet;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;

/**
 * A tile that is always 16:9, the shape of Android TV app banners. Focused, it shows a thin edge
 * in the scene's key colour (its lifted shadow glows in the scene's colours, see CardAdapter).
 * While its app is being moved, the edge is dashed instead.
 */
public class TileView extends FrameLayout {
    private static final int[] FOCUSED = {android.R.attr.state_focused};
    private static final int[] ACTIVATED = {android.R.attr.state_activated};

    private final float density;
    private final float radius;
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dashed = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    public TileView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = context.getResources().getDisplayMetrics().density;
        radius = context.getResources().getDimension(R.dimen.card_radius);
        setWillNotDraw(false);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(density);
        dashed.setStyle(Paint.Style.STROKE);
        dashed.setStrokeWidth(2 * density);
        dashed.setPathEffect(new DashPathEffect(new float[]{8 * density, 5 * density}, 0));
        // The banner is rounded on its own, so the edge can be drawn over it.
        setOutlineProvider(rounded(radius));
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        View banner = findViewById(R.id.banner);
        if (banner != null) {
            banner.setOutlineProvider(rounded(radius));
            banner.setClipToOutline(true);
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        super.onMeasure(
                MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(width * 9 / 16, MeasureSpec.EXACTLY));
    }

    @Override
    protected void drawableStateChanged() {
        super.drawableStateChanged();
        invalidate();
    }

    /** Whether the card shows its focus edge (it takes its focus from the card around it). */
    boolean showsFocus() {
        return StateSet.stateSetMatches(FOCUSED, getDrawableState()) && !moving();
    }

    private boolean moving() {
        return StateSet.stateSetMatches(ACTIVATED, getDrawableState()) || isActivated();
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        Scene scene = getContext() instanceof Scene.Holder ? ((Scene.Holder) getContext()).scene() : Scene.promise();
        float w = getWidth(), h = getHeight();
        if (moving()) {
            float inset = dashed.getStrokeWidth() / 2;
            rect.set(inset, inset, w - inset, h - inset);
            dashed.setColor(scene.label);
            canvas.drawRoundRect(rect, radius, radius, dashed);
            return;
        }
        if (!showsFocus()) return;
        float inset = density / 2;
        rect.set(inset, inset, w - inset, h - inset);
        edge.setColor(Scene.alpha(scene.key, 0.7f));
        canvas.drawRoundRect(rect, radius, radius, edge);
    }

    private static ViewOutlineProvider rounded(float radius) {
        return new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        };
    }
}
