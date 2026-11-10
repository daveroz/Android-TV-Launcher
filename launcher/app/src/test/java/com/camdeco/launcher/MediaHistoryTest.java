package com.camdeco.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** "Recently played" memory for Spotify, YouTube and other media apps. */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 35)
public class MediaHistoryTest {
    private static final String SPOTIFY = "com.spotify.tv.android";
    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        MediaHistory.resetCache();
    }

    @Test
    public void remembersTracksNewestFirstWithoutRepeats() {
        record("Song A", "Artist", 1);
        record("Song B", "Artist", 2);
        record("Song A", "Artist", 3); // played again: moves to the front
        assertEquals(List.of("Song A", "Song B"), titles(MediaHistory.withCovers(context, SPOTIFY)));
        assertTrue(MediaHistory.withCovers(context, "com.google.android.youtube.tv").isEmpty()); // per app
    }

    @Test
    public void keepsTheLastEightAndDeletesOldCovers() {
        for (int i = 1; i <= 10; i++) record("Song " + i, "Artist", i);
        List<MediaHistory.Item> items = MediaHistory.withCovers(context, SPOTIFY);
        assertEquals(MediaHistory.MAX_PER_APP, items.size());
        assertEquals("Song 10", items.get(0).title);
        assertEquals("Song 3", items.get(7).title);
        File[] covers = new File(context.getFilesDir(), "covers").listFiles();
        assertEquals(MediaHistory.MAX_PER_APP, covers.length); // covers of songs 1 and 2 are gone
    }

    @Test
    public void survivesARestart() {
        record("Song A", "Artist", 1);
        MediaHistory.resetCache(); // as after a restart: read back from the file
        List<MediaHistory.Item> items = MediaHistory.withCovers(context, SPOTIFY);
        assertEquals("Song A", items.get(0).title);
        assertEquals("Artist", items.get(0).subtitle);
        assertTrue(MediaHistory.coverFile(context, items.get(0)).exists());
    }

    @Test
    public void tracksWithoutCoversAreNotShown() {
        MediaHistory.recordNow(context, SPOTIFY, "No cover", "Artist", null, null, 1);
        assertTrue(MediaHistory.withCovers(context, SPOTIFY).isEmpty());
        // A later sighting with a cover fills it in.
        record("No cover", "Artist", 2);
        assertEquals("No cover", MediaHistory.withCovers(context, SPOTIFY).get(0).title);
    }

    private void record(String title, String artist, long time) {
        Bitmap cover = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
        cover.eraseColor(0xFF000000 | (int) (time * 0x101010));
        MediaHistory.recordNow(context, SPOTIFY, title, artist, "Album", cover, time);
    }

    private static List<String> titles(List<MediaHistory.Item> items) {
        List<String> titles = new ArrayList<>();
        for (MediaHistory.Item item : items) titles.add(item.title);
        return titles;
    }
}
