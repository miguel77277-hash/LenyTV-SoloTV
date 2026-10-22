package com.pocketbuilder.app_wslenytvplayer1792356127271.movies;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

/**
 * REGISTRO DEL MÓDULO DE PELÍCULAS Y SERIES.
 *
 * Es un registro aparte del de la televisión, con su propio archivo
 * ("diagnostico-leny-peliculas.txt"). Así:
 *   - Los mensajes de películas no desplazan los de TV (que tiene su tope).
 *   - Un problema del módulo se puede leer sin ruido del reproductor de canales.
 *   - Guardar este registro no sobrescribe el de TV, ni al contrario.
 *
 * Nunca lanza excepciones: un registro estropeado no debe romper la app.
 */
public final class MoviesDiag {

    /** Identifica la compilación que generó el registro. */
    public static final String REVISION = "módulo de películas · revisión 59";

    private static final int MAX_LINES = 400;
    private static final int MAX_SAVED_LINES = 400;
    private static final String FILE_NAME = "diagnostico-leny-peliculas.txt";

    private static final Object LOCK = new Object();
    private static final ArrayList<String> LINES = new ArrayList<String>();
    private static final SimpleDateFormat STAMP = new SimpleDateFormat("HH:mm:ss", Locale.US);

    private static boolean loadedFromFile = false;
    private static Context appContext = null;

    private MoviesDiag() {
    }

    /** Contexto que se usa para guardar el archivo (se fija al abrir el módulo). */
    public static void init(Context context) {
        if (context == null) return;
        try {
            if (appContext == null) appContext = context.getApplicationContext();
        } catch (Throwable ignored) {
        }
        if (loadedFromFile) return;
        loadedFromFile = true;
        try {
            File file = file(appContext);
            if (file == null || !file.exists()) return;
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(new FileInputStream(file), "UTF-8"));
            ArrayList<String> all = new ArrayList<String>();
            String line;
            while ((line = reader.readLine()) != null) all.add(line);
            reader.close();
            int from = Math.max(0, all.size() - 120);
            synchronized (LOCK) {
                for (int i = from; i < all.size(); i++) LINES.add(all.get(i));
            }
        } catch (Throwable ignored) {
        }
    }

    /** Añade una línea al registro del módulo. */
    public static void add(String message) {
        try {
            String clean = message == null ? "" : message.trim();
            if (clean.length() == 0) return;
            if (clean.length() > 600) clean = clean.substring(0, 600) + "…";
            String line = STAMP.format(new Date()) + "  " + clean;
            synchronized (LOCK) {
                LINES.add(line);
                while (LINES.size() > MAX_LINES) LINES.remove(0);
            }
            android.util.Log.i("LenyPeliculas", line);
        } catch (Throwable ignored) {
        }
    }

    public static void clear() {
        synchronized (LOCK) {
            LINES.clear();
        }
    }

    public static boolean isEmpty() {
        synchronized (LOCK) {
            return LINES.isEmpty();
        }
    }

    /** Texto completo del registro, listo para mostrar o copiar. */
    public static String text() {
        StringBuilder out = new StringBuilder();
        out.append("LENY TV · ").append(REVISION).append('\n');
        out.append("----------------------------------------\n");
        out.append("Ajustes del módulo · cabeceras en uso: ");
        try {
            out.append(MoviesPrefs.describeHeaders(appContext));
        } catch (Throwable t) {
            out.append("(no disponible)");
        }
        out.append('\n');
        out.append("Catálogo de demostración: ");
        try {
            out.append(MoviesPrefs.showDemo(appContext) ? "visible" : "oculto");
        } catch (Throwable t) {
            out.append("(no disponible)");
        }
        out.append('\n');
        out.append("----------------------------------------\n");
        synchronized (LOCK) {
            if (LINES.isEmpty()) {
                out.append("(el registro del módulo está vacío todavía)\n");
            } else {
                for (int i = 0; i < LINES.size(); i++) out.append(LINES.get(i)).append('\n');
            }
        }
        return out.toString();
    }

    /** Guarda el registro en el archivo del módulo. */
    public static void save(Context context) {
        Context target = context == null ? appContext : context.getApplicationContext();
        if (target == null) return;
        try {
            File file = file(target);
            if (file == null) return;
            String[] lines = text().split("\n");
            StringBuilder out = new StringBuilder();
            int from = Math.max(0, lines.length - MAX_SAVED_LINES);
            for (int i = from; i < lines.length; i++) out.append(lines[i]).append('\n');
            FileOutputStream stream = new FileOutputStream(file, false);
            OutputStreamWriter writer = new OutputStreamWriter(stream, "UTF-8");
            writer.write(out.toString());
            writer.close();
        } catch (Throwable ignored) {
        }
    }

    /**
     * Descripción corta de una dirección para el registro: servidor y extensión,
     * sin usuario, clave ni el resto de la ruta.
     */
    public static String shortUrl(String url) {
        if (url == null || url.trim().length() == 0) return "(sin enlace)";
        String clean = url.trim();
        try {
            java.net.URL parsed = new java.net.URL(clean);
            String host = parsed.getHost();
            if (host == null || host.length() == 0) return "(enlace local)";
            String extension = "";
            String path = parsed.getPath() == null ? "" : parsed.getPath();
            int dot = path.lastIndexOf('.');
            if (dot >= 0 && path.length() - dot <= 9) extension = path.substring(dot);
            return parsed.getProtocol() + "://" + host + "/…" + extension;
        } catch (Throwable t) {
            return clean.length() <= 24 ? clean : clean.substring(0, 24) + "…";
        }
    }

    private static File file(Context context) {
        try {
            return new File(context.getFilesDir(), FILE_NAME);
        } catch (Throwable t) {
            return null;
        }
    }
}
