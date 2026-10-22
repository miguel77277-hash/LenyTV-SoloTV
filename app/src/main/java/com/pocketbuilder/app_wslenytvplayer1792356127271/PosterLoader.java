package com.pocketbuilder.app_wslenytvplayer1792356127271;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.widget.ImageView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Carga los carteles de películas y series.
 *
 * Acepta tres tipos de dirección en el campo "poster" de un repositorio:
 *
 *   - https://servidor/cartel.jpg ......... se descarga y se guarda en el teléfono.
 *   - generated/cartel.png ................ imagen incluida en la app.
 *   - sheet:0 ............................. recorte del cartel de demostración.
 *
 * Mientras no hay imagen (o si falla) se dibuja un recuadro con la inicial del
 * título, igual que los logos de los canales.
 */
final class PosterLoader {

    private static final android.util.LruCache<String, Bitmap> CACHE =
            new android.util.LruCache<String, Bitmap>(16 * 1024 * 1024) {
                @Override
                protected int sizeOf(String key, Bitmap value) {
                    return value == null ? 0 : value.getByteCount();
                }
            };

    private static final android.os.Handler MAIN =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private static final ExecutorService POOL = Executors.newFixedThreadPool(3);
    private static final String FOLDER = "posters";
    /** Ancho al que se reduce el cartel: suficiente para la cuadrícula y la ficha. */
    private static final int TARGET_WIDTH = 420;

    private PosterLoader() {
    }

    static void load(final Context context, final String spec, final ImageView view,
                     final String title) {
        final String key = spec == null ? "" : spec.trim();
        if (key.length() == 0) {
            view.setTag("");
            view.setImageDrawable(LogoLoader.letterIcon(context, title));
            return;
        }
        view.setTag(key);
        Bitmap cached = CACHE.get(key);
        if (cached != null) {
            view.setImageBitmap(cached);
            return;
        }
        view.setImageDrawable(LogoLoader.letterIcon(context, title));
        final Context app = context.getApplicationContext() == null
                ? context : context.getApplicationContext();
        try {
            POOL.execute(new Runnable() {
                @Override
                public void run() {
                    Bitmap bitmap = decode(app, key);
                    if (bitmap == null) return;
                    final Bitmap ready = bitmap;
                    MAIN.post(new Runnable() {
                        @Override
                        public void run() {
                            CACHE.put(key, ready);
                            if (key.equals(view.getTag())) view.setImageBitmap(ready);
                        }
                    });
                }
            });
        } catch (Throwable ignored) {
        }
    }

    static void trimMemory() {
        try {
            CACHE.evictAll();
        } catch (Throwable ignored) {
        }
    }

    private static Bitmap decode(Context context, String spec) {
        String lower = spec.toLowerCase(Locale.US);
        try {
            if (lower.startsWith("sheet:")) {
                return decodeSheet(context, spec.substring(6).trim());
            }
            if (lower.startsWith("http://") || lower.startsWith("https://")) {
                Bitmap fromDisk = readDisk(context, spec);
                if (fromDisk != null) return fromDisk;
                Bitmap downloaded = download(spec);
                if (downloaded != null) saveDisk(context, spec, downloaded);
                return downloaded;
            }
            String asset = spec;
            if (lower.startsWith("assets/")) asset = spec.substring(7);
            if (asset.toLowerCase(Locale.US).startsWith("generated/")) {
                return decodeAsset(context, asset);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** Recorte del cartel de demostración: 3 columnas x 2 filas. */
    private static Bitmap decodeSheet(Context context, String indexText) {
        int index;
        try {
            index = Integer.parseInt(indexText);
        } catch (Throwable t) {
            return null;
        }
        Bitmap sheet = decodeAsset(context, "generated/posters_sheet.png");
        if (sheet == null) return null;
        int columns = 3;
        int rows = 2;
        int column = index % columns;
        int row = index / columns;
        if (row < 0 || row >= rows) return null;
        int left = Math.round(sheet.getWidth() * column / (float) columns);
        int top = Math.round(sheet.getHeight() * row / (float) rows);
        int right = Math.round(sheet.getWidth() * (column + 1) / (float) columns);
        int bottom = Math.round(sheet.getHeight() * (row + 1) / (float) rows);
        int width = Math.max(1, right - left);
        int height = Math.max(1, bottom - top);
        try {
            return Bitmap.createBitmap(sheet, left, top, width, height);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Bitmap decodeAsset(Context context, String path) {
        InputStream in = null;
        try {
            in = context.getAssets().open(path);
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = 1;
            return BitmapFactory.decodeStream(in, null, options);
        } catch (Throwable t) {
            return null;
        } finally {
            try {
                if (in != null) in.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static Bitmap download(String url) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(9000);
            conn.setReadTimeout(9000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", Net.DEFAULT_UA);
            if (conn.getResponseCode() < 200 || conn.getResponseCode() >= 400) return null;
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(conn.getInputStream(), null, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            conn.disconnect();

            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(9000);
            conn.setReadTimeout(9000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", Net.DEFAULT_UA);
            if (conn.getResponseCode() < 200 || conn.getResponseCode() >= 400) return null;

            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = 1;
            int largest = Math.max(bounds.outWidth, bounds.outHeight);
            if (largest > TARGET_WIDTH * 3) options.inSampleSize = 4;
            else if (largest > TARGET_WIDTH * 2) options.inSampleSize = 2;
            Bitmap decoded = BitmapFactory.decodeStream(conn.getInputStream(), null, options);
            if (decoded == null) return null;
            if (decoded.getWidth() > TARGET_WIDTH) {
                int height = Math.max(1,
                        Math.round(decoded.getHeight() * (TARGET_WIDTH / (float) decoded.getWidth())));
                Bitmap scaled = Bitmap.createScaledBitmap(decoded, TARGET_WIDTH, height, true);
                if (scaled != decoded) decoded.recycle();
                return scaled;
            }
            return decoded;
        } catch (Throwable t) {
            return null;
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static File folder(Context context) {
        File dir = new File(context.getFilesDir(), FOLDER);
        if (!dir.exists()) {
            try {
                dir.mkdirs();
            } catch (Throwable ignored) {
            }
        }
        return dir;
    }

    private static String fileNameOf(String url) {
        return Integer.toHexString(url.hashCode()) + "-" + url.length() + ".png";
    }

    private static Bitmap readDisk(Context context, String url) {
        File file = new File(folder(context), fileNameOf(url));
        if (!file.exists()) return null;
        FileInputStream in = null;
        try {
            in = new FileInputStream(file);
            return BitmapFactory.decodeStream(in);
        } catch (Throwable t) {
            return null;
        } finally {
            try {
                if (in != null) in.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static void saveDisk(Context context, String url, Bitmap bitmap) {
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(new File(folder(context), fileNameOf(url)));
            bitmap.compress(Bitmap.CompressFormat.PNG, 92, out);
            out.flush();
        } catch (Throwable ignored) {
        } finally {
            try {
                if (out != null) out.close();
            } catch (Throwable ignored) {
            }
        }
    }
}
