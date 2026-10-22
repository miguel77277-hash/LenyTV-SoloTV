package com.pocketbuilder.app_wslenytvplayer1792356127271;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Map;
import java.util.TreeMap;

/** Preferencias de la app: cabeceras HTTP, tipo de contenido, imagen y controles. */
final class AppPrefs {

    private static final String FILE = "leny_tv_prefs";

    private static final String K_UA = "http_user_agent";
    private static final String K_REFERER = "http_referer";
    private static final String K_TYPE = "content_type";
    private static final String K_ASPECT = "aspect_mode";
    private static final String K_HIDE = "hide_delay_seconds";
    private static final String K_SCREEN_ON = "keep_screen_on";
    private static final String K_LAST_URL = "last_url";
    private static final String K_AUTO_UA = "auto_user_agent";
    private static final String K_AUTO_REFERER = "auto_referer";
    private static final String K_EXTRA = "http_extra_headers";

    static final String ASPECT_FIT = "fit";
    static final String ASPECT_FILL = "fill";
    static final String ASPECT_CROP = "crop";
    static final String ASPECT_ORIGINAL = "original";

    private AppPrefs() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    static String userAgent(Context c) {
        return prefs(c).getString(K_UA, "");
    }

    static void setUserAgent(Context c, String v) {
        prefs(c).edit().putString(K_UA, v == null ? "" : v.trim()).apply();
    }

    static String referer(Context c) {
        return prefs(c).getString(K_REFERER, "");
    }

    static void setReferer(Context c, String v) {
        prefs(c).edit().putString(K_REFERER, v == null ? "" : v.trim()).apply();
    }

    static String contentType(Context c) {
        return prefs(c).getString(K_TYPE, FormatDetector.AUTO);
    }

    static void setContentType(Context c, String v) {
        prefs(c).edit().putString(K_TYPE, v == null ? FormatDetector.AUTO : v).apply();
    }

    /**
     * Cabeceras HTTP que se envían al cargar listas y al reproducir.
     *
     * Nunca van vacías: si el usuario no ha escrito nada se envía un User-Agent
     * de reproductor (VLC), que es lo que aceptan casi todos los proveedores
     * IPTV. Si al cargar la lista funcionó otro juego de cabeceras, se reutiliza
     * ese automáticamente para que los canales también se reproduzcan.
     */
    static Map<String, String> headers(Context c) {
        String ua = userAgent(c);
        if (ua.length() == 0) ua = autoUserAgent(c);
        if (ua.length() == 0) ua = Net.PLAYER_UA;
        String ref = referer(c);
        if (ref.length() == 0) ref = autoReferer(c);
        Map<String, String> map = new TreeMap<String, String>();
        // Cabeceras adicionales escritas por el usuario (una por línea, "Nombre: valor").
        map.putAll(parseExtraHeaders(extraHeaders(c)));
        map.put("User-Agent", ua);
        if (ref.length() > 0) map.put("Referer", ref);
        return map;
    }

    /** Cabeceras adicionales tal y como las escribió el usuario. */
    static String extraHeaders(Context c) {
        return prefs(c).getString(K_EXTRA, "");
    }

    static void setExtraHeaders(Context c, String value) {
        prefs(c).edit().putString(K_EXTRA, value == null ? "" : value.trim()).apply();
    }

    /**
     * Convierte lo escrito por el usuario en cabeceras HTTP. Se admite una por
     * línea con el formato "Nombre: valor" (o "Nombre=valor"). Las líneas vacías y
     * las que empiezan por # se ignoran, y nunca lanza excepción.
     */
    static Map<String, String> parseExtraHeaders(String text) {
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

    /** Cabeceras automáticas guardadas cuando una lista solo se pudo cargar con ellas. */
    static void setAutoHeaders(Context c, String userAgent, String referer) {
        SharedPreferences.Editor editor = prefs(c).edit();
        editor.putString(K_AUTO_UA, userAgent == null ? "" : userAgent.trim());
        editor.putString(K_AUTO_REFERER, referer == null ? "" : referer.trim());
        editor.apply();
    }

    static String autoUserAgent(Context c) {
        return prefs(c).getString(K_AUTO_UA, "");
    }

    static String autoReferer(Context c) {
        return prefs(c).getString(K_AUTO_REFERER, "");
    }

    /** true si se está usando una cabecera guardada automáticamente. */
    static boolean usingAutoHeaders(Context c) {
        return userAgent(c).length() == 0 && autoUserAgent(c).length() > 0;
    }

    static String aspect(Context c) {
        return prefs(c).getString(K_ASPECT, ASPECT_FIT);
    }

    static void setAspect(Context c, String key) {
        prefs(c).edit().putString(K_ASPECT, key).apply();
    }

    static String[] aspectKeys() {
        return new String[]{ASPECT_FIT, ASPECT_FILL, ASPECT_CROP, ASPECT_ORIGINAL};
    }

    static String[] aspectLabels() {
        return new String[]{"Ajustar a la pantalla", "Llenar (estirar)", "Recortar (zoom)", "Tamaño original"};
    }

    static String aspectLabel(String key) {
        String[] keys = aspectKeys();
        String[] labels = aspectLabels();
        for (int i = 0; i < keys.length; i++) {
            if (keys[i].equals(key)) return labels[i];
        }
        return labels[0];
    }

    static int aspectIndex(String key) {
        String[] keys = aspectKeys();
        for (int i = 0; i < keys.length; i++) {
            if (keys[i].equals(key)) return i;
        }
        return 0;
    }

    static int hideDelaySeconds(Context c) {
        return prefs(c).getInt(K_HIDE, 5);
    }

    static void setHideDelaySeconds(Context c, int seconds) {
        prefs(c).edit().putInt(K_HIDE, seconds).apply();
    }

    static boolean keepScreenOn(Context c) {
        return prefs(c).getBoolean(K_SCREEN_ON, true);
    }

    static void setKeepScreenOn(Context c, boolean on) {
        prefs(c).edit().putBoolean(K_SCREEN_ON, on).apply();
    }

    static String lastUrl(Context c) {
        return prefs(c).getString(K_LAST_URL, "");
    }

    static void setLastUrl(Context c, String url) {
        prefs(c).edit().putString(K_LAST_URL, url == null ? "" : url).apply();
    }
}
