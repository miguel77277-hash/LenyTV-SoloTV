package com.pocketbuilder.app_wslenytvplayer1792356127271.movies;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Map;
import java.util.TreeMap;

/**
 * Ajustes del MÓDULO DE PELÍCULAS Y SERIES.
 *
 * Este archivo es independiente del de la televisión ("leny_tv_prefs"). Nada de
 * lo que se guarda aquí cambia cómo se ven los canales de TV, y nada de TV
 * cambia cómo se descargan los catálogos o las películas.
 *
 * Qué se guarda:
 *   - Cabeceras HTTP del módulo (User-Agent, Referer y cabeceras adicionales).
 *     Muchos servidores de películas exigen las suyas; antes se usaban las de TV.
 *   - Modo de imagen del reproductor del módulo (ajustar, llenar, recortar...).
 *   - Si se enseña o no el catálogo de demostración (oculto por defecto).
 *   - El último enlace reproducido por el módulo (solo como comodidad).
 */
public final class MoviesPrefs {

    /** Archivo de preferencias exclusivo del módulo de películas. */
    private static final String FILE = "leny_movies_prefs";

    private static final String K_UA = "movies_user_agent";
    private static final String K_REFERER = "movies_referer";
    private static final String K_EXTRA = "movies_extra_headers";
    private static final String K_ASPECT = "movies_aspect";
    private static final String K_DEMO = "movies_show_demo";
    private static final String K_SCREEN_ON = "movies_keep_screen_on";
    private static final String K_LAST_URL = "movies_last_url";

    /** User-Agent de partida si el usuario no ha escrito otro. */
    public static final String DEFAULT_UA =
            "Mozilla/5.0 (Linux; Android 13) LENY-TV-Peliculas/1.60";

    public static final String ASPECT_FIT = "fit";
    public static final String ASPECT_FILL = "fill";
    public static final String ASPECT_CROP = "crop";
    public static final String ASPECT_ORIGINAL = "original";

    private MoviesPrefs() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------- cabeceras

    public static String userAgent(Context context) {
        return prefs(context).getString(K_UA, "");
    }

    public static void setUserAgent(Context context, String value) {
        prefs(context).edit().putString(K_UA, value == null ? "" : value.trim()).apply();
    }

    public static String referer(Context context) {
        return prefs(context).getString(K_REFERER, "");
    }

    public static void setReferer(Context context, String value) {
        prefs(context).edit().putString(K_REFERER, value == null ? "" : value.trim()).apply();
    }

    public static String extraHeaders(Context context) {
        return prefs(context).getString(K_EXTRA, "");
    }

    public static void setExtraHeaders(Context context, String value) {
        prefs(context).edit().putString(K_EXTRA, value == null ? "" : value.trim()).apply();
    }

    /**
     * Cabeceras que el módulo envía al descargar un catálogo y al reproducir.
     * Nunca va vacía: si el usuario no ha escrito nada se envía un User-Agent de
     * navegador, que es lo que aceptan casi todos los servidores de películas.
     */
    public static Map<String, String> headers(Context context) {
        Map<String, String> map = new TreeMap<String, String>();
        map.putAll(parseExtraHeaders(extraHeaders(context)));
        String ua = userAgent(context);
        if (ua.length() == 0) ua = DEFAULT_UA;
        map.put("User-Agent", ua);
        String referer = referer(context);
        if (referer.length() > 0) map.put("Referer", referer);
        return map;
    }

    /** Convierte el texto "Nombre: valor" (una por línea) en cabeceras. */
    public static Map<String, String> parseExtraHeaders(String text) {
        Map<String, String> map = new TreeMap<String, String>();
        if (text == null || text.trim().length() == 0) return map;
        String[] lines = text.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i] == null ? "" : lines[i].trim();
            if (line.length() == 0 || line.charAt(0) == '#') continue;
            int cut = line.indexOf(':');
            if (cut <= 0) cut = line.indexOf('=');
            if (cut <= 0) continue;
            String name = line.substring(0, cut).trim();
            String value = line.substring(cut + 1).trim();
            if (name.length() == 0 || value.length() == 0) continue;
            map.put(name, value);
        }
        return map;
    }

    /** Resumen corto de las cabeceras en uso, para el registro y los ajustes. */
    public static String describeHeaders(Context context) {
        Map<String, String> map = headers(context);
        StringBuilder out = new StringBuilder();
        String[] names = {"User-Agent", "Referer", "Origin", "Cookie"};
        for (int i = 0; i < names.length; i++) {
            String value = map.get(names[i]);
            if (value == null || value.length() == 0) continue;
            if (out.length() > 0) out.append(" · ");
            out.append(names[i]);
        }
        return out.length() == 0 ? "(ninguna)" : out.toString();
    }

    // --------------------------------------------------------- modo de imagen

    public static String aspect(Context context) {
        return prefs(context).getString(K_ASPECT, ASPECT_FIT);
    }

    public static void setAspect(Context context, String key) {
        prefs(context).edit().putString(K_ASPECT, key == null ? ASPECT_FIT : key).apply();
    }

    public static String[] aspectKeys() {
        return new String[]{ASPECT_FIT, ASPECT_FILL, ASPECT_CROP, ASPECT_ORIGINAL};
    }

    public static String[] aspectLabels() {
        return new String[]{"Ajustar a la pantalla", "Llenar (estirar)", "Recortar (zoom)",
                "Tamaño original"};
    }

    public static String aspectLabel(String key) {
        String[] keys = aspectKeys();
        String[] labels = aspectLabels();
        for (int i = 0; i < keys.length; i++) {
            if (keys[i].equals(key)) return labels[i];
        }
        return labels[0];
    }

    // ---------------------------------------------------------- demostración

    /** El catálogo de prueba se enseña solo si el usuario lo pide. */
    public static boolean showDemo(Context context) {
        return prefs(context).getBoolean(K_DEMO, false);
    }

    public static void setShowDemo(Context context, boolean show) {
        prefs(context).edit().putBoolean(K_DEMO, show).apply();
    }

    // ------------------------------------------------------------------ varios

    public static boolean keepScreenOn(Context context) {
        return prefs(context).getBoolean(K_SCREEN_ON, true);
    }

    public static void setKeepScreenOn(Context context, boolean on) {
        prefs(context).edit().putBoolean(K_SCREEN_ON, on).apply();
    }

    public static String lastUrl(Context context) {
        return prefs(context).getString(K_LAST_URL, "");
    }

    public static void setLastUrl(Context context, String url) {
        prefs(context).edit().putString(K_LAST_URL, url == null ? "" : url).apply();
    }
}
