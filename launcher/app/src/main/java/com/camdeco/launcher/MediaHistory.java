package com.camdeco.launcher;

import android.content.Context;
import android.graphics.Bitmap;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * What was played in each media app (Spotify, YouTube...), with covers, so their cards can show
 * "Recently played" when nothing is playing, like streaming apps show their shows. Kept on the
 * TV only: a small JSON file and the covers, at most a few per app.
 */
final class MediaHistory {

    static final class Item {
        final String title;
        final String subtitle;
        final String album;
        /** Cover file name in the covers folder, or null. */
        final String image;
        final long time;

        Item(String title, String subtitle, String album, String image, long time) {
            this.title = title;
            this.subtitle = subtitle;
            this.album = album;
            this.image = image;
            this.time = time;
        }

        String key() {
            return title + "\u0000" + (subtitle == null ? "" : subtitle);
        }
    }

    static final int MAX_PER_APP = 8;
    private static final int MAX_COVER_WIDTH = 1280;
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor();
    private static final Object LOCK = new Object();
    /** Loaded once from disk, then kept in step with it. */
    private static Map<String, List<Item>> cache;

    private MediaHistory() {
    }

    /** Remembers a track or video and its cover. Returns at once; saving happens in the background. */
    static void record(Context context, NowPlaying.Track track) {
        if (track == null || track.title == null || track.packageName == null) return;
        Context app = context.getApplicationContext();
        long now = System.currentTimeMillis();
        WRITER.execute(() -> recordNow(app, track.packageName, track.title, track.artist, track.album, track.art, now));
    }

    static void recordNow(Context context, String pkg, String title, String subtitle, String album, Bitmap art, long time) {
        synchronized (LOCK) {
            Map<String, List<Item>> all = all(context);
            List<Item> list = all.computeIfAbsent(pkg, k -> new ArrayList<>());
            String key = new Item(title, subtitle, null, null, 0).key();
            Item existing = null;
            for (Item item : list) {
                if (item.key().equals(key)) existing = item;
            }
            String image = existing == null ? null : existing.image;
            boolean imageMissing = image == null || !new File(coversDir(context), image).exists();
            // Already the newest, with its cover: nothing to write (this is called often).
            if (existing != null && list.get(0) == existing && !(imageMissing && art != null)) return;
            if (imageMissing && art != null) image = saveCover(context, pkg, key, art);
            list.remove(existing);
            list.add(0, new Item(title, subtitle, album != null ? album : existing == null ? null : existing.album, image, time));
            while (list.size() > MAX_PER_APP) list.remove(list.size() - 1);
            deleteUnusedCovers(context, all);
            save(context, all);
        }
    }

    /** This app's items that have a cover, newest first. */
    static List<Item> withCovers(Context context, String pkg) {
        synchronized (LOCK) {
            List<Item> result = new ArrayList<>();
            List<Item> list = all(context).get(pkg);
            if (list == null) return result;
            for (Item item : list) {
                if (item.image != null && new File(coversDir(context), item.image).exists()) result.add(item);
            }
            return result;
        }
    }

    static File coverFile(Context context, Item item) {
        return new File(coversDir(context), item.image);
    }

    /** Forgets the copy in memory (tests start each with a fresh app folder). */
    static void resetCache() {
        synchronized (LOCK) {
            cache = null;
        }
    }

    // ---- Storage ----

    private static File coversDir(Context context) {
        File dir = new File(context.getFilesDir(), "covers");
        dir.mkdirs();
        return dir;
    }

    private static File file(Context context) {
        return new File(context.getFilesDir(), "media_history.json");
    }

    private static Map<String, List<Item>> all(Context context) {
        if (cache != null) return cache;
        cache = new HashMap<>();
        try {
            File f = file(context);
            if (!f.exists()) return cache;
            JSONObject root = new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            for (Iterator<String> it = root.keys(); it.hasNext(); ) {
                String pkg = it.next();
                JSONArray array = root.getJSONArray(pkg);
                List<Item> list = new ArrayList<>();
                for (int i = 0; i < array.length(); i++) {
                    JSONObject o = array.getJSONObject(i);
                    list.add(new Item(o.getString("title"), o.optString("subtitle", null), o.optString("album", null),
                            o.optString("image", null), o.optLong("time")));
                }
                cache.put(pkg, list);
            }
        } catch (IOException | JSONException | RuntimeException e) {
            cache.clear(); // unreadable: start over
        }
        return cache;
    }

    private static void save(Context context, Map<String, List<Item>> all) {
        try {
            JSONObject root = new JSONObject();
            for (Map.Entry<String, List<Item>> e : all.entrySet()) {
                JSONArray array = new JSONArray();
                for (Item item : e.getValue()) {
                    JSONObject o = new JSONObject().put("title", item.title).put("time", item.time);
                    if (item.subtitle != null) o.put("subtitle", item.subtitle);
                    if (item.album != null) o.put("album", item.album);
                    if (item.image != null) o.put("image", item.image);
                    array.put(o);
                }
                root.put(e.getKey(), array);
            }
            try (FileOutputStream out = new FileOutputStream(file(context))) {
                out.write(root.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException | JSONException ignored) {
            // Only a convenience.
        }
    }

    private static String saveCover(Context context, String pkg, String key, Bitmap art) {
        String name = hash(pkg + "\u0000" + key) + ".jpg";
        Bitmap scaled = art;
        if (art.getWidth() > MAX_COVER_WIDTH) {
            scaled = Bitmap.createScaledBitmap(art, MAX_COVER_WIDTH, art.getHeight() * MAX_COVER_WIDTH / art.getWidth(), true);
        }
        try (FileOutputStream out = new FileOutputStream(new File(coversDir(context), name))) {
            scaled.compress(Bitmap.CompressFormat.JPEG, 88, out);
            return name;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static void deleteUnusedCovers(Context context, Map<String, List<Item>> all) {
        Set<String> used = new HashSet<>();
        for (List<Item> list : all.values()) for (Item item : list) if (item.image != null) used.add(item.image);
        File[] files = coversDir(context).listFiles();
        if (files == null) return;
        for (File f : files) if (!used.contains(f.getName())) f.delete();
    }

    private static String hash(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder s = new StringBuilder();
            for (int i = 0; i < 10; i++) s.append(String.format("%02x", digest[i]));
            return s.toString();
        } catch (Exception e) {
            return Integer.toHexString(text.hashCode());
        }
    }
}
