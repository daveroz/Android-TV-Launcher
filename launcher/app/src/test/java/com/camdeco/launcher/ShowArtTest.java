package com.camdeco.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.ComponentName;
import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

/** Show artwork from TVmaze, against real (trimmed) TVmaze responses. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class ShowArtTest {
    private static final long HOUR = 60 * 60 * 1000L;
    private Context context;
    private TestNet net;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        net = new TestNet();
        ShowArt.requestGapMs = 0;
    }

    @Test
    public void streamingAppsMapToTheirServiceOnTvmaze() {
        assertEquals("Netflix", ShowArt.channelFor(app("com.netflix.ninja", "Netflix")));
        assertEquals("Disney+", ShowArt.channelFor(app("com.disney.disneyplus", "Disney+")));
        assertEquals("Prime Video", ShowArt.channelFor(app("com.amazon.amazonvideo.livingroom", "Prime Video")));
        // Unknown package: matched by its name instead.
        assertEquals("HBO Max", ShowArt.channelFor(app("com.example.max", "Max")));
        assertEquals("Paramount+", ShowArt.channelFor(app("com.example.p", "Paramount+")));
        assertNull(ShowArt.channelFor(app("com.google.android.youtube.tv", "YouTube")));
        assertNull(ShowArt.channelFor(app("org.videolan.vlc", "VLC")));
    }

    @Test
    public void featuresTheMostPopularShowsThatHaveABackground() {
        ShowArt art = new ShowArt(context, net);
        assertTrue(art.update("Netflix", 100 * HOUR));
        List<ShowArt.Feature> netflix = art.ready("Netflix");
        assertEquals(List.of("Black Doves", "Love Is Blind"), names(netflix)); // WWE RAW has no background

        ShowArt.Feature doves = netflix.get(0);
        assertEquals("S9 E99", doves.episode); // the show's latest episode wins
        // The largest 16:9 background, not a banner or poster.
        assertEquals("https://static.tvmaze.com/uploads/images/original_untouched/643/1609138.jpg", doves.background);
        assertFalse(doves.summary.contains("<")); // HTML stripped
        assertTrue(doves.summary.length() <= 241);
        // A show whose only background is extra wide still gets it.
        assertEquals("https://static.tvmaze.com/uploads/images/original_untouched/607/1519297.jpg", netflix.get(1).background);

        assertTrue(art.ready("Hulu").isEmpty()); // only looked up when Hulu is focused
        assertTrue(art.ready("Tencent QQ").isEmpty()); // not a service any app maps to
        assertEquals(7, net.count("/schedule/web")); // one request per day of the week
    }

    @Test
    public void reusesItsSavedCopyAndOnlyRefreshesTwiceADay() {
        new ShowArt(context, net).update("Netflix", 100 * HOUR);
        int requests = net.requests.size();

        // After a restart, offline: the shows are still there, from the saved copy.
        TestNet offline = new TestNet();
        offline.offline = true;
        ShowArt restarted = new ShowArt(context, offline);
        assertEquals(List.of("Black Doves", "Love Is Blind"), names(restarted.ready("Netflix")));

        // Within 12 hours nothing is fetched again.
        ShowArt again = new ShowArt(context, net);
        again.update("Netflix", 105 * HOUR);
        assertEquals(requests, net.requests.size());
        // After 12 hours the schedule is fetched again.
        again.update("Netflix", 113 * HOUR);
        assertEquals(14, net.count("/schedule/web"));
    }

    @Test
    public void offlineMeansNoShowsAndNoCrash() {
        net.offline = true;
        ShowArt art = new ShowArt(context, net);
        assertFalse(art.update("Netflix", 100 * HOUR));
        assertTrue(art.ready("Netflix").isEmpty());
    }

    private static List<String> names(List<ShowArt.Feature> features) {
        List<String> names = new ArrayList<>();
        for (ShowArt.Feature f : features) names.add(f.show);
        return names;
    }

    private static AppEntry app(String pkg, String label) {
        return new AppEntry(new ComponentName(pkg, pkg + ".Main"), label, null, null, true, null, 0, null, 0);
    }
}
