package com.pocketbuilder.app_wslenytvplayer1792356127271;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Locale;

/** Guarda las listas del usuario (enlace, archivo o demostración) y su contenido en caché. */
final class PlaylistStore {

    static final String KIND_DEMO = "demo";
    static final String KIND_URL = "url";
    static final String KIND_FILE = "file";

    private static final String PREFS = "leny_tv_lists";
    private static final String KEY_LISTS = "saved_lists";
    private static final String KEY_ACTIVE = "active_id";
    /** Varias listas activas a la vez (ids separados por coma). */
    private static final String KEY_ACTIVE_IDS = "active_ids";
    private static final String SEP = "\u0001";
    private static final String ROW = "\n";

    static final class Playlist {
        long id;
        String name;
        String kind;
        String ref;
        int channels;
        long updated;

        Playlist(long id, String name, String kind, String ref) {
            this.id = id;
            this.name = name == null ? "" : name;
            this.kind = kind;
            this.ref = ref == null ? "" : ref;
        }

        boolean isDemo() {
            return KIND_DEMO.equals(kind);
        }

        String kindLabel() {
            if (isDemo()) return "Demostración (incluida)";
            if (KIND_FILE.equals(kind)) return "Archivo del teléfono";
            return "Enlace web";
        }

        String summary() {
            StringBuilder sb = new StringBuilder();
            sb.append(kindLabel());
            if (channels > 0) sb.append(" · ").append(channels).append(" entradas");
            if (updated > 0) sb.append(" · actualizada ").append(android.text.format.DateFormat.format("dd/MM HH:mm", updated));
            return sb.toString();
        }
    }

    private PlaylistStore() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static ArrayList<Playlist> lists(Context c) {
        String raw = prefs(c).getString(KEY_LISTS, "");
        ArrayList<Playlist> out = new ArrayList<Playlist>();
        if (raw == null || raw.length() == 0) return out;
        String[] rows = raw.split(ROW);
        for (int i = 0; i < rows.length; i++) {
            String row = rows[i];
            if (row == null || row.trim().length() == 0) continue;
            String[] f = row.split(SEP, -1);
            if (f.length < 4) continue;
            try {
                Playlist p = new Playlist(Long.parseLong(f[0]), f[1], f[2], f[3]);
                if (f.length > 4) p.channels = parseInt(f[4]);
                if (f.length > 5) p.updated = parseLong(f[5]);
                out.add(p);
            } catch (Exception ignored) {
                // Fila dañada: se omite sin romper la lista completa.
            }
        }
        return out;
    }

    static void saveLists(Context c, ArrayList<Playlist> list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            Playlist p = list.get(i);
            if (i > 0) sb.append(ROW);
            sb.append(p.id).append(SEP)
                    .append(clean(p.name)).append(SEP)
                    .append(clean(p.kind)).append(SEP)
                    .append(clean(p.ref)).append(SEP)
                    .append(p.channels).append(SEP)
                    .append(p.updated);
        }
        prefs(c).edit().putString(KEY_LISTS, sb.toString()).apply();
    }

    static void add(Context c, Playlist p) {
        ArrayList<Playlist> all = lists(c);
        all.add(p);
        saveLists(c, all);
    }

    static void update(Context c, Playlist updated) {
        ArrayList<Playlist> all = lists(c);
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id == updated.id) {
                all.set(i, updated);
                break;
            }
        }
        saveLists(c, all);
    }

    static void remove(Context c, long id) {
        ArrayList<Playlist> all = lists(c);
        for (int i = all.size() - 1; i >= 0; i--) {
            if (all.get(i).id == id) all.remove(i);
        }
        saveLists(c, all);
        deleteCache(c, id);
        ArrayList<Long> act = activeIds(c);
        if (act.contains(id)) {
            act.remove(id);
            if (act.isEmpty() && !all.isEmpty()) act.add(all.get(0).id);
            setActiveIds(c, act);
        }
    }

    static long nextId(ArrayList<Playlist> list) {
        long max = 0;
        for (int i = 0; i < list.size(); i++) max = Math.max(max, list.get(i).id);
        return max + 1;
    }

    /** Primer id activo (compatibilidad). */
    static long activeId(Context c) {
        ArrayList<Long> ids = activeIds(c);
        return ids.isEmpty() ? prefs(c).getLong(KEY_ACTIVE, -1) : ids.get(0);
    }

    /** Todas las listas marcadas como activas (puede ser más de una). */
    static ArrayList<Long> activeIds(Context c) {
        ArrayList<Long> out = new ArrayList<Long>();
        String raw = prefs(c).getString(KEY_ACTIVE_IDS, null);
        if (raw == null || raw.length() == 0) {
            long legacy = prefs(c).getLong(KEY_ACTIVE, -1);
            if (legacy > 0) out.add(legacy);
            return out;
        }
        String[] parts = raw.split(",");
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i].trim();
            if (p.length() == 0) continue;
            try {
                long id = Long.parseLong(p);
                if (id > 0 && !out.contains(id)) out.add(id);
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    static boolean isActive(Context c, long id) {
        return activeIds(c).contains(id);
    }

    /** Sustituye el conjunto de listas activas. */
    static void setActiveIds(Context c, ArrayList<Long> ids) {
        StringBuilder sb = new StringBuilder();
        long first = -1;
        if (ids != null) {
            for (int i = 0; i < ids.size(); i++) {
                long id = ids.get(i);
                if (id <= 0) continue;
                if (sb.length() > 0) sb.append(',');
                sb.append(id);
                if (first < 0) first = id;
            }
        }
        prefs(c).edit()
                .putString(KEY_ACTIVE_IDS, sb.toString())
                .putLong(KEY_ACTIVE, first)
                .apply();
    }

    /** Una sola lista activa (compatibilidad con el código anterior). */
    static void setActiveId(Context c, long id) {
        ArrayList<Long> ids = new ArrayList<Long>();
        if (id > 0) ids.add(id);
        setActiveIds(c, ids);
    }

    /** Activa o desactiva una lista sin tocar las demás. */
    static void toggleActive(Context c, long id) {
        if (id <= 0) return;
        ArrayList<Long> ids = activeIds(c);
        if (ids.contains(id)) {
            ids.remove(id);
        } else {
            ids.add(id);
        }
        setActiveIds(c, ids);
    }

    static Playlist active(Context c) {
        long id = activeId(c);
        ArrayList<Playlist> all = lists(c);
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id == id) return all.get(i);
        }
        return all.isEmpty() ? null : all.get(0);
    }

    /** Todas las listas activas, en el orden del almacén. */
    static ArrayList<Playlist> activeLists(Context c) {
        ArrayList<Long> ids = activeIds(c);
        ArrayList<Playlist> all = lists(c);
        ArrayList<Playlist> out = new ArrayList<Playlist>();
        for (int i = 0; i < all.size(); i++) {
            if (ids.contains(all.get(i).id)) out.add(all.get(i));
        }
        // Si ninguna marcada, la primera (comportamiento antiguo)
        if (out.isEmpty() && !all.isEmpty()) out.add(all.get(0));
        return out;
    }

    static Playlist byId(Context c, long id) {
        ArrayList<Playlist> all = lists(c);
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id == id) return all.get(i);
        }
        return null;
    }

    /** Crea la lista de demostración si el usuario todavía no tiene ninguna. */
    static void seedDemoIfEmpty(Context c) {
        ArrayList<Playlist> all = lists(c);
        if (all.size() > 0) return;
        Playlist demo = new Playlist(nextId(all), DemoData.NAME, KIND_DEMO, "demo");
        demo.updated = System.currentTimeMillis();
        all.add(demo);
        saveLists(c, all);
        setActiveId(c, demo.id);
    }

    /**
     * Lista ya guardada con la misma referencia (mismo enlace o mismo archivo), o
     * null. Sirve para no crear entradas repetidas cuando se añade dos veces la
     * misma lista: antes cada intento creaba una lista nueva con su copia del
     * archivo y el usuario acababa con varias filas iguales.
     */
    static Playlist findByRef(Context c, String ref) {
        if (ref == null || ref.trim().length() == 0) return null;
        String wanted = ref.trim();
        ArrayList<Playlist> all = lists(c);
        for (int i = 0; i < all.size(); i++) {
            Playlist p = all.get(i);
            if (p.isDemo()) continue;
            if (p.ref != null && p.ref.trim().equals(wanted)) return p;
        }
        return null;
    }

    /** Devuelve la lista ya guardada con ese enlace o crea una nueva. */
    static Playlist createUrlPlaylist(Context c, String name, String url) {
        Playlist existing = findByRef(c, url);
        if (existing != null) {
            if (name != null && name.length() > 0) existing.name = name;
            existing.updated = System.currentTimeMillis();
            update(c, existing);
            return existing;
        }
        ArrayList<Playlist> all = lists(c);
        Playlist p = new Playlist(nextId(all), name, KIND_URL, url);
        p.updated = System.currentTimeMillis();
        all.add(p);
        saveLists(c, all);
        return p;
    }

    /** Devuelve la lista ya guardada con ese archivo o crea una nueva. */
    static Playlist createFilePlaylist(Context c, String name, String uri) {
        Playlist existing = findByRef(c, uri);
        if (existing != null) {
            if (name != null && name.length() > 0) existing.name = name;
            existing.updated = System.currentTimeMillis();
            update(c, existing);
            return existing;
        }
        ArrayList<Playlist> all = lists(c);
        Playlist p = new Playlist(nextId(all), name, KIND_FILE, uri);
        p.updated = System.currentTimeMillis();
        all.add(p);
        saveLists(c, all);
        return p;
    }

    /** Crea una entrada nueva para la lista de demostración incluida. */
    static Playlist createDemoPlaylist(Context c) {
        ArrayList<Playlist> all = lists(c);
        Playlist p = new Playlist(nextId(all), DemoData.NAME, KIND_DEMO, "demo");
        p.updated = System.currentTimeMillis();
        all.add(p);
        saveLists(c, all);
        return p;
    }

    // ---- Caché del contenido de la lista ------------------------------------

    private static File cacheFile(Context c, long id) {
        return new File(c.getFilesDir(), "list_" + id + ".m3u");
    }

    /** Contenido guardado de la lista, o null si todavía no hay nada copiado. */
    static String readCache(Context c, Playlist p) {
        if (p == null) return null;
        if (p.isDemo()) return DemoData.text();
        File f = cacheFile(c, p.id);
        if (!f.exists()) return null;
        try {
            FileInputStream in = new FileInputStream(f);
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line).append('\n');
            reader.close();
            return sb.length() == 0 ? null : sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    static void writeCache(Context c, Playlist p, String text) {
        if (p == null || text == null) return;
        try {
            FileOutputStream out = new FileOutputStream(cacheFile(c, p.id));
            OutputStreamWriter writer = new OutputStreamWriter(out, "UTF-8");
            writer.write(text);
            writer.close();
        } catch (Exception ignored) {
            // Sin caché la app sigue funcionando con la descarga en vivo.
        }
    }

    static void deleteCache(Context c, long id) {
        File f = cacheFile(c, id);
        if (f.exists()) f.delete();
    }

    static String nameFromUrl(String url) {
        if (url == null || url.length() == 0) return "Lista";
        try {
            java.net.URL u = new java.net.URL(url);
            String host = u.getHost() == null ? url : u.getHost();
            String path = u.getPath() == null ? "" : u.getPath();
            int slash = path.lastIndexOf('/');
            String file = slash >= 0 ? path.substring(slash + 1) : path;
            if (file.length() > 0) return host + " · " + file;
            return host;
        } catch (Exception e) {
            return url.length() > 40 ? url.substring(0, 40) : url;
        }
    }

    private static String clean(String v) {
        if (v == null) return "";
        return v.replace(SEP, " ").replace("\n", " ").replace("\r", " ").trim();
    }

    private static int parseInt(String v) {
        try {
            return Integer.parseInt(v.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private static long parseLong(String v) {
        try {
            return Long.parseLong(v.trim());
        } catch (Exception e) {
            return 0L;
        }
    }

    static String displayKind(String kind) {
        return kind == null ? "" : kind.toUpperCase(Locale.US);
    }
}
