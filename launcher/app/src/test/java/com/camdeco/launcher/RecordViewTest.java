package com.camdeco.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.view.View;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/** What's playing as an LP: the record comes out and spins while playing, and changes with the song. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class RecordViewTest {
    private Context app;
    private float dp;
    private Drawable blueCover;
    private Drawable pinkCover;

    @Before
    public void setUp() {
        app = RuntimeEnvironment.getApplication();
        dp = app.getResources().getDisplayMetrics().density;
        blueCover = cover(0xFF1E40AF);
        pinkCover = cover(0xFFDB2777);
    }

    @Test
    public void whilePlayingTheRecordComesOutThenSpinsAt33Rpm() {
        RecordView record = record();
        record.show(blueCover, true, "A");
        run(record, 0.3f);
        assertTrue("on its way out", record.slide() > 0.3f && record.slide() < 1f);
        assertEquals("doesn't spin until it's out", 0f, record.speed(), 0f);
        run(record, 2f);
        assertEquals(1f, record.slide(), 0f);
        assertTrue("up to speed", record.speed() > 0.95f);
        float before = record.angle();
        run(record, 1f);
        float turned = (record.angle() - before + 360f) % 360f;
        assertEquals("33⅓ rpm is 200° a second", 200f, turned, 12f);
    }

    @Test
    public void pausedItSlowsToAStopAndStaysOut() {
        RecordView record = record();
        record.show(blueCover, true, "A");
        run(record, 2.5f);
        record.setPlaying(false);
        run(record, 0.2f);
        assertTrue("slowing down, not stopping dead: " + record.speed(), record.speed() > 0.3f && record.speed() < 0.9f);
        run(record, 3f);
        assertEquals(0f, record.speed(), 0f);
        assertEquals("still out of its sleeve", 1f, record.slide(), 0f);
    }

    @Test
    public void aNewSongStopsTheRecordPutsItBackAndChangesTheCover() {
        RecordView record = record();
        record.show(blueCover, true, "A");
        run(record, 2.5f);
        record.show(pinkCover, true, "B");
        run(record, 0.1f);
        assertSame("the old cover until the record is back in", blueCover, record.shownCover());
        assertTrue("stopping first", record.speed() < 0.9f);
        run(record, 1.5f);
        assertSame("then the new cover", pinkCover, record.shownCover());
        run(record, 2.5f);
        assertEquals("and the new record comes out", 1f, record.slide(), 0f);
        assertTrue("and spins", record.speed() > 0.95f);
    }

    @Test
    public void theSameSongWithANewCopyOfItsCoverKeepsPlaying() {
        RecordView record = record();
        record.show(blueCover, true, "A");
        run(record, 2.5f);
        Drawable copy = cover(0xFF1E40AF); // players resend the cover as a new picture
        record.show(copy, true, "A");
        run(record, 0.2f);
        assertTrue("not a new song", !record.songChanging());
        assertSame("the copy is on the sleeve", copy, record.shownCover());
        assertEquals("still out", 1f, record.slide(), 0f);
        assertTrue("still spinning", record.speed() > 0.95f);
    }

    @Test
    public void aSongThatComesBackRightAwayDoesNotChangeTheRecord() {
        RecordView record = record();
        record.show(blueCover, true, "A");
        run(record, 2.5f);
        record.show(pinkCover, true, "B"); // a blip: another song for a split second...
        run(record, 0.05f);
        record.show(blueCover, true, "A"); // ...then the same one again
        run(record, 2f);
        assertTrue(!record.songChanging());
        assertSame(blueCover, record.shownCover());
        assertEquals(1f, record.slide(), 0f);
        assertTrue(record.speed() > 0.95f);
    }

    @Test
    public void aRecordOnlyChangesColourInItsSleeve() {
        RecordView record = record();
        record.show(blueCover, true, "A");
        record.setGlow(0xFFFF2020);
        run(record, 2.5f);
        assertEquals("out", 1f, record.slide(), 0f);
        assertEquals("pressed in its song's colour", 0xFFFF2020, record.tint());
        // A new song, in green: the red record goes back still red, and the next one comes out green.
        record.show(blueCover, true, "B");
        record.setGlow(0xFF20FF20);
        boolean redGoingBack = false;
        for (int i = 0; i < 300 && !(record.slide() > 0f && record.tint() == 0xFF20FF20); i++) {
            if (record.slide() > 0f && record.slide() < 1f && record.tint() == 0xFFFF2020) redGoingBack = true;
            record.step(1 / 60f);
        }
        assertTrue("the red record slides back still red", redGoingBack);
        run(record, 2.5f);
        assertEquals("the new record is out", 1f, record.slide(), 0f);
        assertEquals("and it's green: a different disc", 0xFF20FF20, record.tint());
        // A change of light while it's on show doesn't repaint it.
        record.setGlow(0xFFFF2020);
        run(record, 0.5f);
        assertEquals("stays green while on show", 0xFF20FF20, record.tint());
    }

    @Test
    public void recentlyPlayedStaysInItsSleeve() {
        RecordView record = record();
        record.show(blueCover, false, "A");
        run(record, 2f);
        assertEquals(0f, record.slide(), 0f);
        assertEquals(0f, record.speed(), 0f);
    }

    @Test
    public void theRecordShowsLeftOfTheSleeveInTheCoversColour() {
        RecordView playing = record();
        playing.show(blueCover, true, "A");
        run(playing, 2.5f);
        Bitmap out = draw(playing);
        int sleeveLeft = out.getWidth() - Math.round(176 * dp);
        int px = out.getPixel(sleeveLeft - Math.round(50 * dp), out.getHeight() / 2 + Math.round(20 * dp));
        float[] hsv = new float[3];
        Color.colorToHSV(px, hsv);
        assertTrue("blue vinyl for a blue cover, not " + Integer.toHexString(px),
                Color.alpha(px) > 200 && hsv[0] > 200 && hsv[0] < 250);

        RecordView paused = record();
        paused.show(blueCover, false, "A");
        run(paused, 1f);
        int empty = draw(paused).getPixel(sleeveLeft - Math.round(50 * dp), out.getHeight() / 2 + Math.round(20 * dp));
        assertTrue("nothing out of the sleeve", Color.alpha(empty) < 40);
    }

    private RecordView record() {
        RecordView record = new RecordView(app, null);
        int w = Math.round(260 * dp), h = Math.round(176 * dp);
        record.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        record.layout(0, 0, w, h);
        return record;
    }

    private static void run(RecordView record, float seconds) {
        for (float t = 0; t < seconds; t += 1 / 60f) record.step(1 / 60f);
    }

    private static Bitmap draw(RecordView record) {
        Bitmap frame = Bitmap.createBitmap(record.getWidth(), record.getHeight(), Bitmap.Config.ARGB_8888);
        record.draw(new Canvas(frame));
        return frame;
    }

    private Drawable cover(int colour) {
        Bitmap bitmap = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(colour);
        return new BitmapDrawable(app.getResources(), bitmap);
    }
}
