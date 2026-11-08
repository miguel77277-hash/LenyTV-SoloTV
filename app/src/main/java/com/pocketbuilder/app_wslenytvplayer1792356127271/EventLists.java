package com.pocketbuilder.app_wslenytvplayer1792356127271;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;

/** Listas M3U del módulo Eventos (independiente de TV y Películas). */
final class EventLists {

    private static final String PREFS = "leny_events_lists";
    private static final String KEY = "urls";
    private static final String SEEDED = "seeded_v2_named";

    static final String[] DEFAULTS = new String[]{
            "https://wild-base-c86a.miguel77277.workers.dev",
            "https://raw.githubusercontent.com/MMonterrosaSV/LIVESPORTS-LISTS-AUTOUPDATER/refs/heads/main/THETVAPP-SOCCER.m3u",
            "https://raw.githubusercontent.com/MMonterrosaSV/LIVESPORTS-LISTS-AUTOUPDATER/refs/heads/main/FCTV33-FOOTBALL.m3u"
    };

    static final String PLACEHOLDER_LOGO =
            "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcQSQ6IzHpiDC2pxVcul1ciAKkN4Zy5egDVVeSXqJnB39Q&s=10";

    private EventLists() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static void seedDefaults(Context c) {
        SharedPreferences p = prefs(c);
        if (p.getBoolean(SEEDED, false)) return;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < DEFAULTS.length; i++) {
            if (i > 0) sb.append('\n');
            sb.append(DEFAULTS[i]);
        }
        p.edit().putString(KEY, sb.toString()).putBoolean(SEEDED, true).apply();
    }

    static ArrayList<String> urls(Context c) {
        seedDefaults(c);
        ArrayList<String> out = new ArrayList<String>();
        String raw = prefs(c).getString(KEY, "");
        if (raw == null || raw.length() == 0) return out;
        String[] lines = raw.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String u = lines[i] == null ? "" : lines[i].trim();
            if (u.length() > 0) out.add(u);
        }
        return out;
    }

    static void saveUrls(Context c, ArrayList<String> list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            String u = list.get(i);
            if (u == null || u.trim().length() == 0) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(u.trim());
        }
        prefs(c).edit().putString(KEY, sb.toString()).apply();
    }

    static void addUrl(Context c, String url) {
        if (url == null) return;
        String u = url.trim();
        if (u.length() == 0) return;
        ArrayList<String> list = urls(c);
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).equalsIgnoreCase(u)) return;
        }
        list.add(u);
        saveUrls(c, list);
    }

    static void removeUrl(Context c, String url) {
        ArrayList<String> list = urls(c);
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i).equalsIgnoreCase(url)) list.remove(i);
        }
        saveUrls(c, list);
    }

    static String serverLabel(String url) {
        return displayName(url);
    }

    /** Nombre fijo por lista para no mezclar hosts iguales (GitHub). */
    static String displayName(String url) {
        if (url == null) return "Eventos";
        String u = url.toLowerCase(java.util.Locale.US);
        if (u.indexOf("workers.dev") >= 0 || u.indexOf("wild-base") >= 0) {
            return "Roxy eventos";
        }
        if (u.indexOf("thetvapp") >= 0) return "Thetvapp eventos";
        if (u.indexOf("fctv33") >= 0) return "Fctv33 eventos";
        try {
            String host = url;
            int scheme = host.indexOf("://");
            if (scheme >= 0) host = host.substring(scheme + 3);
            int slash = host.indexOf('/');
            if (slash >= 0) host = host.substring(0, slash);
            return host.length() == 0 ? "Eventos" : host;
        } catch (Throwable t) {
            return "Eventos";
        }
    }
}
