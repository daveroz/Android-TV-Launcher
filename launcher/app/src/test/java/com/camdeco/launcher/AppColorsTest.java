package com.camdeco.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/** The primary colour of a picture: the real colour that covers the most of it. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class AppColorsTest {

    @Test
    public void aColourfulLogoGivesItsOwnColour() {
        assertHue(AppColors.primary(solid(0xFFE50914)), 357, 10); // a red logo
    }

    @Test
    public void theColourThatCoversTheMostWins() {
        // A navy banner with a small yellow logo on it: navy is its primary colour.
        Bitmap bitmap = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(0xFF12235A);
        Paint paint = new Paint();
        paint.setColor(0xFFFFC72C);
        new Canvas(bitmap).drawRect(130, 60, 190, 120, paint);
        assertHue(AppColors.primary(drawable(bitmap)), 225, 20);
    }

    @Test
    public void greyOrWhiteArtHasNoColour() {
        for (int grey : new int[]{0xFF808080, 0xFFFFFFFF, 0xFF202020}) {
            assertEquals(AppColors.NONE, AppColors.primary(solid(grey)));
        }
        assertEquals(AppColors.NONE, AppColors.primary(null));
    }

    private static Drawable solid(int colour) {
        Bitmap bitmap = Bitmap.createBitmap(160, 90, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(colour);
        return drawable(bitmap);
    }

    private static Drawable drawable(Bitmap bitmap) {
        return new BitmapDrawable(RuntimeEnvironment.getApplication().getResources(), bitmap);
    }

    private static float value(int colour) {
        float[] hsv = new float[3];
        Color.colorToHSV(colour, hsv);
        return hsv[2];
    }

    private static void assertHue(int colour, float expected, float tolerance) {
        float[] hsv = new float[3];
        Color.colorToHSV(colour, hsv);
        float diff = Math.abs(hsv[0] - expected);
        diff = Math.min(diff, 360 - diff);
        assertTrue(String.format("hue %.0f, expected %.0f (colour #%08X)", hsv[0], expected, colour),
                diff <= tolerance && hsv[1] > 0.3f);
    }
}
