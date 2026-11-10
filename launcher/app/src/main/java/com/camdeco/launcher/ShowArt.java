package com.camdeco.launcher;

import android.content.Context;
import android.text.Html;
import android.util.JsonReader;
import android.util.JsonToken;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Show artwork for streaming apps, from TVmaze's free API (no key; data CC BY-SA, credited in the
 * hero). Netflix, Disney+ and the others only share their own recommendations with the system
 * launcher, so instead this takes the past week of new episodes on each service, keeps the most
 * popular shows and finds a landscape background for each.
 */
final class ShowArt {

    /** One show to feature in the hero. */
    static final class Feature {
        final int showId;
        final String show;
        final String channel;
        final String genres;
        final String summary;
        /** "S2 E5", or null. */
        final String episode;
        final int weight;
        /** Landscape image URL; null until looked up, "" when the show has none. */
        String background;

        Feature(int showId, String show, String channel, String genres, String summary,
                String episode, int weight, String background) {
            this.showId = showId;
            this.show = show;
            this.channel = channel;
            this.genres = genres;
            this.summary = summary;
            this.episode = episode;
            this.weight = weight;
            this.background = background;
        }
    }

    /** Opens a URL; swapped out in tests. */
    interface Fetcher {
        InputStream open(String url) throws IOException;
    }

    static final String API = "https://api.tvmaze.com";
    private static final long MAX_AGE_MS = 12 * 60 * 60 * 1000L;
    private static final int DAYS = 7;
    private static final int PER_CHANNEL = 8;
    /** Gap between requests: TVmaze allows about 20 every 10 seconds. Tests set it to 0. */
    static long requestGapMs = 500;

    /** App package to the service's name on TVmaze. */
    private static final Map<String, String> PACKAGES = new HashMap<>();
    /** App label, lower case letters and digits only, to the service's name on TVmaze. */
    private static final Map<String, String> LABELS = new HashMap<>();

    static {
        PACKAGES.put("com.netflix.ninja", "Netflix");
        PACKAGES.put("com.disney.disneyplus", "Disney+");
        PACKAGES.put("com.amazon.amazonvideo.livingroom", "Prime Video");
        PACKAGES.put("com.wbd.stream", "HBO Max");
        PACKAGES.put("com.hbo.hbonow", "HBO Max");
        PACKAGES.put("com.hulu.livingroomplus", "Hulu");
        PACKAGES.put("com.apple.atve.androidtv.appletv", "Apple TV");
        PACKAGES.put("com.cbs.ott", "Paramount+");
        PACKAGES.put("com.peacocktv.peacockandroid", "Peacock");
        PACKAGES.put("com.crunchyroll.crunchyroid", "Crunchyroll");
        String[][] labels = {
                {"netflix", "Netflix"}, {"disney", "Disney+"}, {"disneyplus", "Disney+"},
                {"primevideo", "Prime Video"}, {"amazonprimevideo", "Prime Video"},
                {"max", "HBO Max"}, {"hbomax", "HBO Max"}, {"hulu", "Hulu"},
                {"appletv", "Apple TV"}, {"paramount", "Paramount+"}, {"paramountplus", "Paramount+"},
                {"peacock", "Peacock"}, {"peacocktv", "Peacock"}, {"crunchyroll", "Crunchyroll"},
                {"discovery", "discovery+"}, {"discoveryplus", "discovery+"}, {"mgm", "MGM+"},
                {"mgmplus", "MGM+"}, {"starz", "Starz"}, {"britbox", "BritBox"}, {"viaplay", "Viaplay"},
                {"tubi", "Tubi"}, {"plutotv", "Pluto TV"}, {"skyshowtime", "SkyShowtime"},
        };
        for (String[] pair : labels) LABELS.put(pair[0], pair[1]);
    }

    /** The streaming service an app belongs to, or null for apps that aren't one. */
    static String channelFor(AppEntry app) {
        String channel = PACKAGES.get(app.packageName);
        if (channel != null) return channel;
        return LABELS.get(app.label.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", ""));
    }

    private final File file;
    private final Fetcher fetcher;
    private final Map<String, List<Feature>> channels = new HashMap<>();
    private long fetchedAt;

    ShowArt(Context context, Fetcher fetcher) {
        this.file = new File(context.getFilesDir(), "showart.json");
        this.fetcher = fetcher;
        load();
    }

    /** This service's shows that have a background, most popular first. */
    synchronized List<Feature> ready(String channel) {
        List<Feature> result = new ArrayList<>();
        List<Feature> all = channels.get(channel);
        if (all == null) return result;
        for (Feature f : all) {
            if (f.background != null && !f.background.isEmpty()) result.add(f);
        }
        return result;
    }

    /**
     * Refreshes the week's schedule when it's older than 12 hours, then looks up backgrounds for
     * this service's shows. Slow (network): call off the main thread. True if anything changed.
     */
    boolean update(String channel, long now) {
        boolean changed = false;
        if (now - fetchedAt > MAX_AGE_MS) {
            Map<String, List<Feature>> fresh = fetchSchedule(LocalDate.now());
            if (fresh != null) {
                synchronized (this) {
                    // Keep backgrounds already found for shows that are still featured.
                    Map<Integer, String> known = new HashMap<>();
                    for (List<Feature> list : channels.values()) {
                        for (Feature f : list) if (f.background != null) known.put(f.showId, f.background);
                    }
                    for (List<Feature> list : fresh.values()) {
                        for (Feature f : list) f.background = known.get(f.showId);
                    }
                    channels.clear();
                    channels.putAll(fresh);
                    fetchedAt = now;
                }
                changed = true;
            }
        }
        List<Feature> list;
        synchronized (this) {
            list = channels.get(channel) == null ? new ArrayList<>() : new ArrayList<>(channels.get(channel));
        }
        for (Feature f : list) {
            if (f.background != null) continue;
            String background = findBackground(f.showId);
            if (background == null) continue; // network trouble: try again next time
            synchronized (this) {
                f.background = background;
            }
            changed = true;
            pause(); // TVmaze allows about 20 requests every 10 seconds
        }
        if (changed) save();
        return changed;
    }

    // ---- TVmaze ----

    /** New streaming episodes over the past week, grouped by service; null if offline. */
    private Map<String, List<Feature>> fetchSchedule(LocalDate today) {
        Map<String, Map<Integer, Feature>> byChannel = new HashMap<>();
        int ok = 0;
        for (int i = DAYS - 1; i >= 0; i--) { // oldest first, so each show ends on its latest episode
            try (InputStream in = fetcher.open(API + "/schedule/web?date=" + today.minusDays(i))) {
                parseDay(in, byChannel);
                ok++;
            } catch (IOException | RuntimeException ignored) {
                // A missing day just means fewer shows.
            }
            pause();
        }
        if (ok == 0) return null;
        Map<String, List<Feature>> result = new HashMap<>();
        for (Map.Entry<String, Map<Integer, Feature>> e : byChannel.entrySet()) {
            List<Feature> list = new ArrayList<>(e.getValue().values());
            list.sort((a, b) -> Integer.compare(b.weight, a.weight));
            result.put(e.getKey(), new ArrayList<>(list.subList(0, Math.min(PER_CHANNEL, list.size()))));
        }
        return result;
    }

    /** Reads one day of /schedule/web, a streamed array of episodes with their show embedded. */
    static void parseDay(InputStream in, Map<String, Map<Integer, Feature>> out) throws IOException {
        JsonReader r = new JsonReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        r.beginArray();
        while (r.hasNext()) {
            int season = 0;
            int number = 0;
            Feature show = null;
            r.beginObject();
            while (r.hasNext()) {
                switch (r.nextName()) {
                    case "season":
                        season = readInt(r);
                        break;
                    case "number":
                        number = readInt(r);
                        break;
                    case "_embedded":
                        show = readEmbeddedShow(r);
                        break;
                    default:
                        r.skipValue();
                }
            }
            r.endObject();
            if (show == null || show.channel == null || !LABELS.containsValue(show.channel)) continue;
            String episode = season > 0 && number > 0 ? "S" + season + " E" + number : null;
            out.computeIfAbsent(show.channel, k -> new LinkedHashMap<>()).put(show.showId,
                    new Feature(show.showId, show.show, show.channel, show.genres, show.summary,
                            episode, show.weight, null));
        }
        r.endArray();
    }

    private static Feature readEmbeddedShow(JsonReader r) throws IOException {
        if (r.peek() == JsonToken.NULL) {
            r.nextNull();
            return null;
        }
        Feature show = null;
        r.beginObject();
        while (r.hasNext()) {
            if (r.nextName().equals("show") && r.peek() == JsonToken.BEGIN_OBJECT) show = readShow(r);
            else r.skipValue();
        }
        r.endObject();
        return show;
    }

    private static Feature readShow(JsonReader r) throws IOException {
        int id = 0;
        int weight = 0;
        String name = null;
        String summary = null;
        String channel = null;
        List<String> genres = new ArrayList<>();
        r.beginObject();
        while (r.hasNext()) {
            String key = r.nextName();
            if (r.peek() == JsonToken.NULL) {
                r.nextNull();
                continue;
            }
            switch (key) {
                case "id":
                    id = r.nextInt();
                    break;
                case "name":
                    name = r.nextString();
                    break;
                case "weight":
                    weight = r.nextInt();
                    break;
                case "summary":
                    summary = plainText(r.nextString());
                    break;
                case "genres":
                    r.beginArray();
                    while (r.hasNext()) genres.add(r.nextString());
                    r.endArray();
                    break;
                case "webChannel":
                    r.beginObject();
                    while (r.hasNext()) {
                        if (r.nextName().equals("name") && r.peek() == JsonToken.STRING) channel = r.nextString();
                        else r.skipValue();
                    }
                    r.endObject();
                    break;
                default:
                    r.skipValue();
            }
        }
        r.endObject();
        if (id == 0 || name == null) return null;
        String genreText = genres.isEmpty() ? null : String.join(" · ", genres.subList(0, Math.min(3, genres.size())));
        return new Feature(id, name, channel, genreText, summary, null, weight, null);
    }

    /** The show's best landscape image: about 16:9 and as large as possible. "" if none, null if offline. */
    String findBackground(int showId) {
        try (InputStream in = fetcher.open(API + "/shows/" + showId + "/images")) {
            JSONArray images = new JSONArray(readAll(in));
            String best = "";
            double bestScore = -1;
            for (int i = 0; i < images.length(); i++) {
                JSONObject image = images.getJSONObject(i);
                if (!"background".equals(image.optString("type"))) continue;
                JSONObject original = image.optJSONObject("resolutions") == null ? null
                        : image.getJSONObject("resolutions").optJSONObject("original");
                if (original == null || original.optString("url").isEmpty()) continue;
                int w = original.optInt("width", 0);
                int h = original.optInt("height", 0);
                double ratio = h > 0 ? (double) w / h : 0;
                boolean wide = ratio > 1.6 && ratio < 1.9; // crops nicely to the screen
                double score = (wide ? 100000 : 0) + Math.min(w, 3000);
                if (score > bestScore) {
                    bestScore = score;
                    best = original.getString("url");
                }
            }
            return best;
        } catch (IOException | JSONException | RuntimeException e) {
            return null;
        }
    }

    // ---- Helpers ----

    private static int readInt(JsonReader r) throws IOException {
        if (r.peek() == JsonToken.NUMBER) return r.nextInt();
        r.skipValue();
        return 0;
    }

    /** TVmaze summaries are HTML; keep the text, at most a couple of lines' worth. */
    static String plainText(String html) {
        String text = Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).toString().replaceAll("\\s+", " ").trim();
        if (text.length() <= 240) return text;
        int cut = text.lastIndexOf(' ', 237);
        return text.substring(0, cut > 160 ? cut : 237) + "…";
    }

    static String readAll(InputStream in) throws IOException {
        return new String(readBytes(in, 4 * 1024 * 1024), StandardCharsets.UTF_8);
    }

    /** Reads a whole stream, refusing anything over the limit. */
    static byte[] readBytes(InputStream in, int limit) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int n;
        while ((n = in.read(buffer)) != -1) {
            out.write(buffer, 0, n);
            if (out.size() > limit) throw new IOException("response too large");
        }
        return out.toByteArray();
    }

    private static void pause() {
        if (requestGapMs <= 0) return;
        try {
            Thread.sleep(requestGapMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---- Saved copy, so artwork shows instantly after a restart ----

    private synchronized void load() {
        try {
            if (!file.exists()) return;
            JSONObject root = new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            fetchedAt = root.optLong("fetchedAt");
            JSONObject all = root.getJSONObject("channels");
            for (java.util.Iterator<String> it = all.keys(); it.hasNext(); ) {
                String channel = it.next();
                JSONArray array = all.getJSONArray(channel);
                List<Feature> list = new ArrayList<>();
                for (int i = 0; i < array.length(); i++) {
                    JSONObject o = array.getJSONObject(i);
                    list.add(new Feature(o.getInt("id"), o.getString("show"), channel,
                            o.optString("genres", null), o.optString("summary", null),
                            o.optString("episode", null), o.optInt("weight"),
                            o.has("background") ? o.getString("background") : null));
                }
                channels.put(channel, list);
            }
        } catch (IOException | JSONException | RuntimeException e) {
            channels.clear();
            fetchedAt = 0;
        }
    }

    private synchronized void save() {
        try {
            JSONObject all = new JSONObject();
            for (Map.Entry<String, List<Feature>> e : channels.entrySet()) {
                JSONArray array = new JSONArray();
                for (Feature f : e.getValue()) {
                    JSONObject o = new JSONObject().put("id", f.showId).put("show", f.show).put("weight", f.weight);
                    if (f.genres != null) o.put("genres", f.genres);
                    if (f.summary != null) o.put("summary", f.summary);
                    if (f.episode != null) o.put("episode", f.episode);
                    if (f.background != null) o.put("background", f.background);
                    array.put(o);
                }
                all.put(e.getKey(), array);
            }
            JSONObject root = new JSONObject().put("fetchedAt", fetchedAt).put("channels", all);
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(root.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException | JSONException ignored) {
            // Only a cache.
        }
    }
}
