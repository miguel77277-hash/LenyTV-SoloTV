package com.pocketbuilder.app_wslenytvplayer1792356127271;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;

import java.io.InputStream;

/** Carga las imágenes generadas guardadas en assets/generated. */
final class Art {

    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(8);

    private Art() {
    }

    static Bitmap generated(Context context, String fileName) {
        Bitmap cached = CACHE.get(fileName);
        if (cached != null) return cached;
        InputStream in = null;
        try {
            in = context.getAssets().open("generated/" + fileName);
            Bitmap bitmap = BitmapFactory.decodeStream(in);
            if (bitmap != null) CACHE.put(fileName, bitmap);
            return bitmap;
        } catch (Exception e) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignored) {
                }
            }
        }
    }
}
