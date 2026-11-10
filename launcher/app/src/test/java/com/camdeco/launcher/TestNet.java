package com.camdeco.launcher;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** Answers TVmaze requests from real responses saved in src/test/resources/tvmaze. */
final class TestNet implements ShowArt.Fetcher {
    final List<String> requests = new ArrayList<>();
    boolean offline;

    @Override
    public synchronized InputStream open(String url) throws IOException {
        requests.add(url);
        if (offline) throw new IOException("offline");
        if (url.contains("/schedule/web?date=")) return resource("schedule.json");
        if (url.endsWith("/shows/68407/images")) return resource("images-many.json"); // Black Doves
        if (url.endsWith("/shows/46167/images")) return resource("images-wide-only.json"); // Love Is Blind
        if (url.contains("/images")) return new ByteArrayInputStream("[]".getBytes()); // no backgrounds
        if (url.startsWith("https://static.tvmaze.com/")) return resource("background.jpg");
        throw new IOException("unexpected request " + url);
    }

    synchronized int count(String part) {
        int n = 0;
        for (String r : requests) if (r.contains(part)) n++;
        return n;
    }

    private static InputStream resource(String name) throws IOException {
        InputStream in = TestNet.class.getResourceAsStream("/tvmaze/" + name);
        if (in == null) throw new IOException("missing test resource " + name);
        return in;
    }
}
