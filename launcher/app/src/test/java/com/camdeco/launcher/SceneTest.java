package com.camdeco.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.graphics.Color;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Each scene is one colour: the primary colour of what's in focus, lifted so it reads as light. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class SceneTest {

    @Test
    public void aDarkColourIsLiftedSoItReadsAsLightAndKeepsItsHue() {
        Scene scene = Scene.of(0xFF7A1010); // a dark red
        float[] hsv = new float[3];
        Color.colorToHSV(scene.key, hsv);
        assertTrue("bright: " + hsv[2], hsv[2] >= 0.92f);
        assertTrue("still red: " + hsv[0], hsv[0] < 10 || hsv[0] > 350);
    }

    @Test
    public void greyBlackAndWhiteGetACoolWhiteLight() {
        for (int colour : new int[]{0xFF888888, 0xFF000000, 0xFFFFFFFF, 0xFF525252, AppColors.NONE}) {
            assertTrue(Scene.neutral().sameAs(Scene.of(colour)));
        }
    }

    @Test
    public void theLedCoreIsTheColourNearlyWhite() {
        Scene green = Scene.of(0xFF1ED760);
        assertTrue(Scene.luminance(green.led) > 0.9f);
        assertTrue("a touch of the colour stays", Color.green(green.led) > Color.red(green.led));
    }

    @Test
    public void labelsAreLighterThanTheKeyForReading() {
        Scene blue = Scene.of(0xFF2050F0);
        assertTrue(Scene.luminance(blue.label) > Scene.luminance(blue.key));
        assertEquals(blue.key, Scene.of(0xFF2050F0).key);
    }
}
