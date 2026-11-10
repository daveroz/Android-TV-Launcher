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

/** The colours the hero glows with for apps that have nothing else to show. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class AppColorsTest {

    @Test
    public void aColourfulLogoGivesItsOwnColour() {
        AppColors colours = AppColors.from(solid(0xFFE50914)); // a red logo
        assertHue(colours.vivid, 357, 10);
        assertHue(colours.deep, 357, 10);
        assertTrue("deep is darker than vivid", value(colours.deep) < value(colours.vivid));
    }

    @Test
    public void aTwoColourBannerGivesABrightAndADarkColour() {
        // A navy banner with a yellow logo on it.
        Bitmap bitmap = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(0xFF12235A);
        Paint paint = new Paint();
        paint.setColor(0xFFFFC72C);
        new Canvas(bitmap).drawRect(110, 50, 210, 130, paint);
        AppColors colours = AppColors.from(drawable(bitmap));
        assertHue(colours.vivid, 45, 15);
        assertHue(colours.deep, 225, 20);
    }

    @Test
    public void glowsAreNeverNeonOrMuddy() {
        AppColors bright = AppColors.from(solid(0xFF00FF00));
        assertTrue(value(bright.vivid) <= 0.95f + 0.01f);
        AppColors dark = AppColors.from(solid(0xFF0A3D12));
        assertTrue(value(dark.vivid) >= 0.55f - 0.01f);
        assertTrue(value(dark.deep) >= 0.25f - 0.01f);
    }

    @Test
    public void greyOrWhiteLogosUseWorldlysColours() {
        for (int grey : new int[]{0xFF808080, 0xFFFFFFFF, 0xFF202020}) {
            AppColors colours = AppColors.from(solid(grey));
            assertEquals(AppColors.WORLDLY_AMBER, colours.vivid);
            assertEquals(AppColors.WORLDLY_SLATE, colours.deep);
        }
        assertEquals(AppColors.WORLDLY_AMBER, AppColors.from(null).vivid);
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
