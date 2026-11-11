package com.camdeco.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.graphics.Color;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Each scene is a key light from what's in focus and a fill that answers it. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class SceneTest {

    @Test
    public void theFillAnswersTheKey() {
        assertEquals(225f, Scene.fillHue(5f), 0f); // red meets blue
        assertEquals(225f, Scene.fillHue(350f), 0f);
        assertEquals(174f, Scene.fillHue(35f), 0f); // amber meets teal
        assertEquals(257f, Scene.fillHue(140f), 0f); // green meets violet
        assertEquals(331f, Scene.fillHue(195f), 0f); // cyan meets magenta
        assertEquals(190f, Scene.fillHue(300f), 0f); // magenta meets cyan
    }

    @Test
    public void aDarkColourIsLiftedSoItReadsAsLight() {
        Scene scene = Scene.from(new AppColors(0xFF7A1010, 0xFF200505)); // a dark red logo
        float[] hsv = new float[3];
        Color.colorToHSV(scene.key, hsv);
        assertTrue("bright: " + hsv[2], hsv[2] >= 0.92f);
        assertTrue("still red: " + hsv[0], hsv[0] < 10 || hsv[0] > 350);
        Color.colorToHSV(scene.fill, hsv);
        assertEquals(225f, hsv[0], 2f);
    }

    @Test
    public void greyArtGetsTheDefaultLight() {
        assertTrue(Scene.promise().sameAs(Scene.from(new AppColors(0xFF888888, 0xFF333333))));
    }

    @Test
    public void labelsAreLighterThanTheKeyForReading() {
        Scene blue = Scene.from(new AppColors(0xFF2050F0, 0xFF101830));
        assertTrue(luminance(blue.label) > luminance(blue.key));
    }

    private static double luminance(int c) {
        return 0.2126 * Color.red(c) + 0.7152 * Color.green(c) + 0.0722 * Color.blue(c);
    }
}
