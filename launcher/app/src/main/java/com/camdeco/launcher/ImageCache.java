package com.camdeco.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * Downloads hero backgrounds once, shrinks them to TV size and keeps them on disk, plus the few
 * most recent in memory. TVmaze's originals are up to 4000 pixels wide and several megabytes.
 */
final class ImageCache {
    private static final int MAX_WIDTH = 1920;
    private static final long MAX_DISK_BYTES = 40L * 1024 * 1024;

    private final File dir;
    private final ShowArt.Fetcher fetcher;
    private final LruCache<String, Bitmap> memory = new LruCache<String, Bitmap>(16 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap bitmap) {
            return bitmap.getByteCount();
        }
    };

    ImageCache(Context context, ShowArt.Fetcher fetcher) {
        this.dir = new File(context.getCacheDir(), "showart");
        this.fetcher = fetcher;
        dir.mkdirs();
    }

    /** Already in memory, or null. Fast. */
    Bitmap cached(String url) {
        return memory.get(url);
    }

    /** From memory, disk or the network; null on failure. Slow: call off the main thread. */
    Bitmap load(String url) {
        Bitmap bitmap = memory.get(url);
        if (bitmap != null) return bitmap;
        File file = new File(dir, fileName(url));
        try {
            if (file.exists()) {
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inPreferredConfig = Bitmap.Config.RGB_565;
                bitmap = BitmapFactory.decodeFile(file.getPath(), options);
                file.setLastModified(System.currentTimeMillis());
            } else {
                byte[] bytes;
                try (InputStream in = fetcher.open(url)) {
                    bytes = ShowArt.readBytes(in, 24 * 1024 * 1024);
                }
                bitmap = decode(bytes);
                if (bitmap != null) {
                    try (FileOutputStream out = new FileOutputStream(file)) {
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out);
                    }
                    trimDisk();
                }
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
        if (bitmap != null) memory.put(url, bitmap);
        return bitmap;
    }

    /** Decodes at no more than full-HD width, in the compact 16-bit format. */
    static Bitmap decode(byte[] bytes) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= MAX_WIDTH) sample *= 2;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (bitmap != null && bitmap.getWidth() > MAX_WIDTH) {
            Bitmap scaled = Bitmap.createScaledBitmap(bitmap, MAX_WIDTH,
                    bitmap.getHeight() * MAX_WIDTH / bitmap.getWidth(), true);
            bitmap.recycle();
            bitmap = scaled;
        }
        return bitmap;
    }

    /** Keeps the disk copy under its size limit by dropping the least recently used images. */
    private void trimDisk() {
        File[] files = dir.listFiles();
        if (files == null) return;
        long total = 0;
        for (File f : files) total += f.length();
        if (total <= MAX_DISK_BYTES) return;
        Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (File f : files) {
            if (total <= MAX_DISK_BYTES * 3 / 4) break;
            total -= f.length();
            f.delete();
        }
    }

    private static String fileName(String url) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-1").digest(url.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder name = new StringBuilder();
            for (byte b : hash) name.append(String.format("%02x", b));
            return name.append(".jpg").toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(url.hashCode()) + ".jpg";
        }
    }

    /** The real network: plain HTTPS GET with a generic client name. */
    static InputStream httpOpen(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(20000);
        connection.setRequestProperty("User-Agent", "DecoLauncher (Android TV launcher)");
        int code = connection.getResponseCode();
        if (code != HttpURLConnection.HTTP_OK) {
            connection.disconnect();
            throw new IOException("HTTP " + code + " for " + url);
        }
        return new BufferedInputStream(connection.getInputStream());
    }
}
