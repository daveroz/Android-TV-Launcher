package com.camdeco.launcher;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/**
 * The hero's background for apps with nothing else to show: soft glows of the app's own colours
 * over Worldly's graphite, with the slanted street-map grid of the Deco / Worldly / SAB icons drawn
 * faintly on top. Moving between apps blends one app's colours into the next.
 */
public class ColorFieldView extends View {
    private static final long BLEND_MS = 450;

    private final Paint wash = new Paint();
    private final Paint glow = new Paint();
    private final Paint thin = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thick = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private int vivid;
    private int deep;
    private boolean hasColours;
    private ValueAnimator blend;

    public ColorFieldView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = context.getResources().getDisplayMetrics().density;
        thin.setStyle(Paint.Style.STROKE);
        thin.setStrokeWidth(1 * density);
        thick.setStyle(Paint.Style.STROKE);
        thick.setStrokeWidth(2.5f * density);
    }

    /** Shows these colours, blending from the current ones. */
    void setColours(AppColors colours) {
        if (blend != null) blend.cancel();
        if (hasColours && vivid == colours.vivid && deep == colours.deep) return; // already showing
        if (!hasColours) {
            vivid = colours.vivid;
            deep = colours.deep;
            hasColours = true;
            rebuild();
            return;
        }
        int fromVivid = vivid;
        int fromDeep = deep;
        blend = ValueAnimator.ofFloat(0f, 1f).setDuration(BLEND_MS);
        blend.addUpdateListener(a -> {
            float f = a.getAnimatedFraction();
            vivid = mix(fromVivid, colours.vivid, f);
            deep = mix(fromDeep, colours.deep, f);
            rebuild();
        });
        blend.start();
    }

    int vividColour() {
        return vivid;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        rebuild();
    }

    /**
     * Makes the glows for the current size and colours. Done here, not in onDraw, so redraws (the
     * hero fading as the rows scroll) allocate nothing.
     */
    private void rebuild() {
        int w = getWidth();
        int h = getHeight();
        if (hasColours && w > 0 && h > 0) {
            // The app's deep colour washes in from the top; its vivid colour glows on the right,
            // where the card sits. The hero's scrims fade both into graphite behind the text and rows.
            wash.setShader(new RadialGradient(w * 0.42f, -h * 0.15f, w * 0.75f,
                    new int[]{alpha(deep, 0.85f), alpha(deep, 0.35f), Color.TRANSPARENT},
                    new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
            glow.setShader(new RadialGradient(w * 0.80f, h * 0.30f, w * 0.55f,
                    new int[]{alpha(vivid, 0.70f), alpha(vivid, 0.25f), Color.TRANSPARENT},
                    new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
            // The grid is tinted with a light version of the app's colour.
            int line = mix(vivid, Color.WHITE, 0.6f);
            thin.setColor(alpha(line, 0.07f));
            thick.setColor(alpha(line, 0.11f));
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!hasColours || wash.getShader() == null) return;
        int w = getWidth();
        int h = getHeight();
        canvas.drawRect(0, 0, w, h, wash);
        canvas.drawRect(0, 0, w, h, glow);

        // The icon family's slanted street-map grid: thin streets, every fourth one a main road.
        float step = 46 * density;
        canvas.save();
        canvas.rotate(-16f, w * 0.75f, h * 0.35f);
        float reach = Math.max(w, h) * 1.2f;
        int n = 0;
        for (float x = -reach; x < w + reach; x += step, n++) {
            canvas.drawLine(x, -reach, x, h + reach, n % 4 == 1 ? thick : thin);
        }
        n = 0;
        for (float y = -reach; y < h + reach; y += step, n++) {
            canvas.drawLine(-reach, y, w + reach, y, n % 4 == 2 ? thick : thin);
        }
        canvas.restore();
    }

    private static int alpha(int colour, float a) {
        return (Math.round(a * 255) << 24) | (colour & 0x00FFFFFF);
    }

    private static int mix(int from, int to, float f) {
        int r = Math.round(Color.red(from) + (Color.red(to) - Color.red(from)) * f);
        int g = Math.round(Color.green(from) + (Color.green(to) - Color.green(from)) * f);
        int b = Math.round(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * f);
        return Color.rgb(r, g, b);
    }
}
