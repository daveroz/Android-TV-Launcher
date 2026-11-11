package com.camdeco.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/** An app's light show: a wall of square lamps played in strips, like LED panels on a stage. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "w960dp-h540dp-land-television-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class LightShowViewTest {
    private LightShowView view;
    private Bitmap frame;

    @Before
    public void setUp() {
        Context app = RuntimeEnvironment.getApplication();
        view = new LightShowView(app, null);
        float dp = app.getResources().getDisplayMetrics().density;
        int w = Math.round(960 * dp), h = Math.round(540 * dp);
        view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, w, h);
        frame = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
    }

    private void draw() {
        frame.eraseColor(0);
        view.draw(new Canvas(frame));
    }

    @Test
    public void theLampsAreTheSizeOfTheWallsSquares() {
        float dp = RuntimeEnvironment.getApplication().getResources().getDisplayMetrics().density;
        float[] b = view.showBounds();
        assertEquals("22 columns of 17.6dp", 22 * 17.6f, (b[2] - b[0]) / dp, 0.5f);
        assertEquals("10 rows, as tall as a cover", 176f, (b[3] - b[1]) / dp, 0.5f);
    }

    @Test
    public void panelsPlayPatternsThatChange() {
        int lit = 0;
        String before = null;
        boolean changed = false;
        for (int i = 0; i < 40; i++) {
            view.advanceForTests(0.5f);
            draw();
            StringBuilder state = new StringBuilder();
            for (int r = 0; r < 10; r++) {
                for (int c = 0; c < 22; c++) {
                    float v = view.lampForTests(c, r);
                    if (v > 0.5f) lit++;
                    state.append(v > 0.5f ? '#' : '.');
                    if (c % 4 == 3) assertEquals("the column between strips stays dark", 0f, v, 0f);
                }
            }
            if (before != null && !before.equals(state.toString())) changed = true;
            before = state.toString();
        }
        assertTrue("lamps light up: " + lit, lit > 100);
        assertTrue("the patterns move on", changed);
    }

    @Test
    public void itsLitInTheScenesColour() {
        view.setScene(new Scene(0xFFE50914));
        assertEquals(new Scene(0xFFE50914).key, view.keyColour());
        assertNotEquals(Scene.promise().key, view.keyColour());
    }
}
