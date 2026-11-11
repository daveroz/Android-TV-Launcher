package com.camdeco.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;

import java.io.File;

/**
 * Recently played covers saved on the TV, decoded once and kept in memory while they take turns
 * in the hero. Nothing comes from the internet.
 */
final class ImageCache {
    private final LruCache<String, Bitmap> memory = new LruCache<String, Bitmap>(8 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap bitmap) {
            return bitmap.getByteCount();
        }
    };

    ImageCache(Context context) {
        // Show artwork from TVmaze used to be kept here; it's gone, so free the space it took.
        File old = new File(context.getCacheDir(), "showart");
        File[] files = old.listFiles();
        if (files != null) for (File f : files) f.delete();
        old.delete();
    }

    /** Already in memory, or null. Fast. */
    Bitmap cached(String url) {
        return memory.get(url);
    }

    /** A saved cover ("file:" and its path); null if it can't be read. Slow: call off the main thread. */
    Bitmap load(String url) {
        Bitmap bitmap = memory.get(url);
        if (bitmap != null || !url.startsWith("file:")) return bitmap;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        bitmap = BitmapFactory.decodeFile(url.substring("file:".length()), options);
        if (bitmap != null) memory.put(url, bitmap);
        return bitmap;
    }

    /** Whether a saved picture is square enough to be an album cover (not a video's thumbnail). */
    static boolean isCover(File file) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), bounds);
        return bounds.outWidth > 0 && !Hero.isWide(bounds.outWidth, bounds.outHeight);
    }
}
