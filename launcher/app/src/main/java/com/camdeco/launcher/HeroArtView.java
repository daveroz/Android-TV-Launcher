package com.camdeco.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.widget.ImageView;

/**
 * The hero's app art. Its left and bottom edges fade out softly, so the art melts into the
 * background like the artwork on the Apple TV home screen instead of ending in a hard box.
 */
public class HeroArtView extends ImageView {
    private final Paint leftFade = new Paint();
    private final Paint bottomFade = new Paint();

    public HeroArtView(Context context, AttributeSet attrs) {
        super(context, attrs);
        PorterDuffXfermode keepWhereOpaque = new PorterDuffXfermode(PorterDuff.Mode.DST_IN);
        leftFade.setXfermode(keepWhereOpaque);
        bottomFade.setXfermode(keepWhereOpaque);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        leftFade.setShader(new LinearGradient(0, 0, w * 0.5f, 0,
                0x00000000, 0xFF000000, Shader.TileMode.CLAMP));
        bottomFade.setShader(new LinearGradient(0, h * 0.45f, 0, h,
                0xFF000000, 0x00000000, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        int layer = canvas.saveLayer(0, 0, w, h, null);
        super.onDraw(canvas);
        canvas.drawRect(0, 0, w, h, leftFade);
        canvas.drawRect(0, 0, w, h, bottomFade);
        canvas.restoreToCount(layer);
    }
}
