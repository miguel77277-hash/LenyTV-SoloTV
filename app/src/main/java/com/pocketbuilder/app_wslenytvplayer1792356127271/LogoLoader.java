package com.pocketbuilder.app_wslenytvplayer1792356127271;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Carga los logos de los canales con DOS cachés:
 *
 *  1. En memoria: los logos ya vistos se dibujan al instante al desplazarse.
 *  2. EN EL TELÉFONO (REVISIÓN 53): cada logo descargado se guarda como archivo
 *     dentro de la carpeta privada de la app. Antes solo existía la caché de
 *     memoria, así que al cerrar y volver a abrir LENY TV todos los iconos se
 *     tenían que descargar otra vez y la cuadrícula aparecía con letras durante
 *     un buen rato. Ahora, al entrar de nuevo, los iconos se leen del propio
 *     teléfono (sin Internet) y se ven al momento.
 *
 * Mientras no hay logo (o si falla) se dibuja un icono con la inicial del canal.
 */
final class LogoLoader {

    /** Caché en memoria, medida en bytes de imagen (unos 8 MB como mucho). */
    private static final LruCache<String, Bitmap> CACHE =
            new LruCache<String, Bitmap>(8 * 1024 * 1024) {
                @Override
                protected int sizeOf(String key, Bitmap value) {
                    return value == null ? 0 : value.getByteCount();
                }
            };

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService POOL = Executors.newFixedThreadPool(3);
    /** Descargas de logo en marcha a la vez como máximo. */
    private static final int MAX_PENDING = 24;
    private static final java.util.concurrent.atomic.AtomicInteger PENDING =
            new java.util.concurrent.atomic.AtomicInteger(0);

    /** Carpeta de la app donde se guardan los logos (sobrevive al cerrar la app). */
    private static final String FOLDER = "logos";
    /** Cuántos logos se guardan como máximo en el teléfono. */
    private static final int MAX_FILES = 1500;
    /** Cada cuántos logos guardados se revisa si hay demasiados archivos. */
    private static final int PRUNE_EVERY = 120;
    private static final java.util.concurrent.atomic.AtomicInteger WRITES =
            new java.util.concurrent.atomic.AtomicInteger(0);

    private LogoLoader() {
    }

    static void load(final Context context, final String logoUrl, final ImageView view, final String channelName) {
        final Drawable fallback = new LetterIcon(context, channelName);
        if (logoUrl == null || logoUrl.length() == 0) {
            view.setTag(null);
            view.setImageDrawable(fallback);
            return;
        }
        // La marca del logo se pone SIEMPRE, también cuando la imagen viene de la
        // caché. Antes, al salir por la caché, la vista conservaba la marca del
        // canal anterior y podía acabar enseñando el logo de otro canal.
        view.setTag(logoUrl);
        Bitmap cached = CACHE.get(logoUrl);
        if (cached != null) {
            view.setImageBitmap(cached);
            return;
        }
        view.setImageDrawable(fallback);
        // Con listas de más de mil canales, al desplazarse deprisa se pedirían
        // cientos de descargas a la vez: en un teléfono con poca memoria eso
        // saturaba el aparato mientras se dibujan las tarjetas. Si ya hay muchas
        // en marcha se deja el icono con la inicial y no se pide ninguna más.
        if (PENDING.get() >= MAX_PENDING) return;
        PENDING.incrementAndGet();
        final Context app = context.getApplicationContext() == null
                ? context : context.getApplicationContext();
        try {
            POOL.execute(new Runnable() {
                @Override
                public void run() {
                    Bitmap bitmap = null;
                    try {
                        // Primero el teléfono (rápido y sin Internet); solo si no
                        // está guardado se descarga.
                        bitmap = readFromDisk(app, logoUrl);
                        if (bitmap == null) {
                            bitmap = download(logoUrl);
                            if (bitmap != null) saveToDisk(app, logoUrl, bitmap);
                        }
                    } finally {
                        PENDING.decrementAndGet();
                    }
                    if (bitmap == null) return;
                    final Bitmap ready = bitmap;
                    MAIN.post(new Runnable() {
                        @Override
                        public void run() {
                            CACHE.put(logoUrl, ready);
                            if (logoUrl.equals(view.getTag())) {
                                view.setImageBitmap(ready);
                            }
                        }
                    });
                }
            });
        } catch (Throwable t) {
            PENDING.decrementAndGet();
        }
    }

    static Drawable letterIcon(Context context, String name) {
        return new LetterIcon(context, name);
    }

    /**
     * Suelta los logos guardados en MEMORIA cuando Android avisa de que va
     * escasa. Los archivos del teléfono se conservan: al volver a dibujarlos se
     * leen de ahí sin descargarlos otra vez.
     */
    static void trimMemory() {
        try {
            CACHE.evictAll();
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------ caché en disco

    /** Nombre de archivo estable para un enlace de logo. */
    private static String fileNameOf(String url) {
        String hash;
        try {
            hash = Integer.toHexString(url.hashCode());
        } catch (Throwable t) {
            hash = "0";
        }
        return hash + "-" + url.length() + ".png";
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

    private static Bitmap readFromDisk(Context context, String url) {
        File file = new File(folder(context), fileNameOf(url));
        if (!file.exists()) return null;
        FileInputStream in = null;
        try {
            in = new FileInputStream(file);
            Bitmap bitmap = BitmapFactory.decodeStream(in);
            if (bitmap == null) {
                // Archivo dañado o a medio escribir: se borra y se vuelve a bajar.
                file.delete();
                return null;
            }
            return bitmap;
        } catch (Throwable t) {
            try {
                file.delete();
            } catch (Throwable ignored) {
            }
            return null;
        } finally {
            try {
                if (in != null) in.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static void saveToDisk(Context context, String url, Bitmap bitmap) {
        FileOutputStream out = null;
        try {
            File file = new File(folder(context), fileNameOf(url));
            out = new FileOutputStream(file);
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            out.flush();
        } catch (Throwable ignored) {
        } finally {
            try {
                if (out != null) out.close();
            } catch (Throwable ignored) {
            }
        }
        // De vez en cuando se quitan los logos más viejos, para que la carpeta no
        // crezca sin límite en listas de miles de canales.
        if (WRITES.incrementAndGet() % PRUNE_EVERY == 0) prune(context);
    }

    private static void prune(Context context) {
        try {
            File[] files = folder(context).listFiles();
            if (files == null || files.length <= MAX_FILES) return;
            java.util.Arrays.sort(files, new java.util.Comparator<File>() {
                @Override
                public int compare(File a, File b) {
                    long one = a == null ? 0 : a.lastModified();
                    long other = b == null ? 0 : b.lastModified();
                    if (one < other) return -1;
                    return one > other ? 1 : 0;
                }
            });
            int extra = files.length - MAX_FILES + MAX_FILES / 20;
            for (int i = 0; i < extra && i < files.length; i++) {
                if (files[i] != null) files[i].delete();
            }
        } catch (Throwable ignored) {
        }
    }

    // ---------------------------------------------------------------- descarga

    private static Bitmap download(String url) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", Net.DEFAULT_UA);
            if (conn.getResponseCode() < 200 || conn.getResponseCode() >= 400) return null;
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(conn.getInputStream(), null, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            conn.disconnect();

            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", Net.DEFAULT_UA);
            if (conn.getResponseCode() < 200 || conn.getResponseCode() >= 400) return null;

            BitmapFactory.Options options = new BitmapFactory.Options();
            int largest = Math.max(bounds.outWidth, bounds.outHeight);
            options.inSampleSize = largest > 256 ? 4 : (largest > 128 ? 2 : 1);
            Bitmap decoded = BitmapFactory.decodeStream(conn.getInputStream(), null, options);
            if (decoded == null) return null;
            if (decoded.getWidth() > 160 || decoded.getHeight() > 160) {
                Bitmap scaled = Bitmap.createScaledBitmap(decoded, Math.max(1, decoded.getWidth() / 3), Math.max(1, decoded.getHeight() / 3), true);
                if (scaled != decoded) decoded.recycle();
                return scaled;
            }
            return decoded;
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** Icono de reserva: cuadro de color con la inicial del canal. */
    private static final class LetterIcon extends Drawable {
        private static final int[] PALETTE = {0xFF2D63E8, 0xFF7C1A11, 0xFF3EC46E, 0xFF142F6E, 0xFFE8A33D, 0xFFE13A2B};

        private final Paint background = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint letter = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float radius;
        private final String text;
        private int alpha = 255;

        LetterIcon(Context context, String name) {
            String clean = name == null ? "" : name.trim();
            int hash = clean.length() == 0 ? 0 : Math.abs(clean.hashCode());
            background.setColor(PALETTE[hash % PALETTE.length]);
            letter.setColor(0xFFF4F4F8);
            letter.setTextAlign(Paint.Align.CENTER);
            letter.setFakeBoldText(true);
            radius = Ui.dp(context, 8f);
            text = clean.length() == 0 ? "?" : clean.substring(0, 1).toUpperCase(Locale.US);
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            background.setAlpha(alpha);
            letter.setAlpha(alpha);
            canvas.drawRoundRect(new RectF(b.left, b.top, b.right, b.bottom), radius, radius, background);
            letter.setTextSize(Math.max(10f, (b.height()) * 0.48f));
            canvas.drawText(text, b.left + b.width() / 2f, b.top + b.height() / 2f - (letter.descent() + letter.ascent()) / 2f, letter);
        }

        @Override
        public void setAlpha(int value) {
            alpha = value;
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
            background.setColorFilter(cf);
            letter.setColorFilter(cf);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
