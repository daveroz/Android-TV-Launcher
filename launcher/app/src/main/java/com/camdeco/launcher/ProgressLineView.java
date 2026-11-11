package com.camdeco.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/**
 * How far through the song: a hairline, lit from the left by an LED line in the scene's colour,
 * brightening toward where the song is, with a soft glow around it.
 */
public class ProgressLineView extends View {
    private final float density;
    private final Paint track = new Paint();
    /** A dark band under the line, so it reads over the visualizer's lights. */
    private final Paint shade = new Paint();
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Scene scene = Scene.promise();
    private float progress;

    public ProgressLineView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = context.getResources().getDisplayMetrics().density;
        track.setColor(context.getColor(R.color.hairline_strong));
        shade.setColor(0x9905070B);
    }

    void setScene(Scene newScene) {
        scene = newScene;
        updateShader();
        invalidate();
    }

    /** 0 at the start of the song, 1 at the end. */
    void setProgress(float fraction) {
        float value = Math.max(0f, Math.min(1f, fraction));
        if (Math.abs(value - progress) < 0.0005f) return;
        progress = value;
        invalidate();
    }

    float progress() {
        return progress;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        updateShader();
    }

    private void updateShader() {
        line.setShader(new LinearGradient(0, 0, Math.max(1, getWidth()), 0, Scene.alpha(scene.key, 0.5f), scene.key, Shader.TileMode.CLAMP));
        glow.setColor(Scene.alpha(scene.key, 0.22f));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float mid = getHeight() / 2f;
        canvas.drawRect(0, mid - 3 * density, getWidth(), mid + 3 * density, shade);
        canvas.drawRect(0, mid - density / 2, getWidth(), mid + density / 2, track);
        float end = getWidth() * progress;
        if (end <= 0) return;
        canvas.drawRect(0, mid - 3 * density, end, mid + 3 * density, glow);
        canvas.drawRect(0, mid - density, end, mid + density, line);
    }
}
