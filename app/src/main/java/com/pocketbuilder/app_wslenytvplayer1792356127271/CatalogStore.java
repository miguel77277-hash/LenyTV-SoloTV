package com.pocketbuilder.app_wslenytvplayer1792356127271;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Guarda en el teléfono la lista de repositorios de películas y series.
 *
 * Para cada repositorio se conserva el último catálogo descargado, de forma que
 * la interfaz de PELÍCULAS se puede ver al instante (también sin Internet) y solo
 * se vuelve a descargar cuando se pulsa ACTUALIZAR o cuando el catálogo guardado
 * es viejo.
 *
 * No hay servidores propios ni cuentas: el usuario pega la dirección del
 * repositorio y la app la lee. Por eso la app no incluye contenido de terceros.
 */
final class CatalogStore {

    private static final String PREFS = "leny_movies_repos";
    private static final String KEY = "repos";

    /** Etiqueta del catálogo de demostración incluido en la app. */
    static final String DEMO_ID = "demo";
    static final String DEMO_NAME = "Catálogo de demostración";
    /** Catálogo guardado que se considera viejo y se vuelve a pedir al abrir. */
    private static final long STALE_MS = 6L * 60L * 60L * 1000L;

    private CatalogStore() {
    }

    /** Fuente que devuelve un catálogo JSON (formato LENY). */
    static final String KIND_JSON = "json";
    /** Fuente que se lee con la API de Xtream Codes (servidor + usuario + clave). */
    static final String KIND_XTREAM = "xtream";

    static final class Repo {
        String id = "";
        String url = "";
        String name = "";
        boolean enabled = true;
        String status = "";
        String itemsJson = "";
        int itemCount = 0;
        long updated = 0L;
        boolean demo = false;
        /** Proveedor que viene creado dentro de la app (no se puede borrar). */
        boolean preset = false;
        /** Tipo de fuente: {@link #KIND_JSON} (dirección de catálogo) o Xtream. */
        String kind = KIND_JSON;
        /** Servidor de la lista Xtream, ya normalizado (sin barra final). */
        String server = "";
        String user = "";
        String pass = "";

        boolean hasItems() {
            return itemsJson != null && itemsJson.trim().length() > 2;
        }

        /** Verdadero si esta fuente es una lista de Xtream Codes. */
        boolean isXtream() {
            return KIND_XTREAM.equals(kind);
        }

        /** Un proveedor integrado (o lista Xtream) al que le faltan sus datos. */
        boolean needsUrl() {
            if (isXtream()) {
                return server == null || server.trim().length() == 0
                        || user == null || user.trim().length() == 0
                        || pass == null || pass.length() == 0;
            }
            return url == null || url.trim().length() == 0;
        }

        String title() {
            if (name != null && name.length() > 0) return name;
            if (isXtream() && server != null && server.length() > 0) {
                return "Lista Xtream · " + hostOf(server);
            }
            return url;
        }

        /** Solo el nombre del servidor, para títulos y resúmenes. */
        static String hostOf(String address) {
            if (address == null) return "";
            String clean = address.trim();
            int scheme = clean.indexOf("://");
            if (scheme >= 0) clean = clean.substring(scheme + 3);
            int slash = clean.indexOf('/');
            if (slash > 0) clean = clean.substring(0, slash);
            return clean;
        }

        /** Texto de estado: activo/desactivado, último resultado y número de títulos. */
        String statusLine() {
            StringBuilder out = new StringBuilder();
            out.append(enabled ? "Activado" : "Desactivado");
            if (itemCount > 0) {
                out.append(" · ").append(itemCount).append(itemCount == 1 ? " título" : " títulos");
            }
            if (updated > 0L) {
                out.append(" · actualizado ").append(dateText(updated));
            }
            if (status != null && status.length() > 0) {
                out.append("\n").append(status);
            }
            return out.toString();
        }

        boolean stale() {
            return updated <= 0L || System.currentTimeMillis() - updated > STALE_MS;
        }
    }

    private static String dateText(long millis) {
        try {
            java.text.SimpleDateFormat format =
                    new java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.US);
            return format.format(new java.util.Date(millis));
        } catch (Throwable t) {
            return "hace un momento";
        }
    }

    // ------------------------------------------------------------------ lectura

    static ArrayList<Repo> list(Context context) {
        ArrayList<Repo> repos = new ArrayList<Repo>();
        if (context == null) return repos;
        String raw = prefs(context).getString(KEY, "");
        if (raw == null || raw.length() == 0) return repos;
        try {
            List<Object> array = Json.array(Json.parse(raw));
            for (int i = 0; i < array.size(); i++) {
                Map<String, Object> row = Json.object(array.get(i));
                Repo repo = new Repo();
                repo.id = Json.clean(row.get("id"));
                repo.url = Json.clean(row.get("url"));
                repo.name = Json.clean(row.get("name"));
                repo.enabled = !row.containsKey("enabled") || Json.bool(row.get("enabled"));
                repo.status = Json.clean(row.get("status"));
                repo.itemsJson = Json.text(row.get("items"));
                repo.itemCount = Json.integer(row.get("count"));
                repo.updated = (long) Json.number(row.get("updated"));
                repo.preset = Json.bool(row.get("preset")) || Providers.isPresetRepo(repo.id);
                String kind = Json.clean(row.get("kind"));
                repo.kind = KIND_XTREAM.equalsIgnoreCase(kind) ? KIND_XTREAM : KIND_JSON;
                repo.server = Json.clean(row.get("server"));
                repo.user = Json.clean(row.get("user"));
                repo.pass = Json.text(row.get("pass"));
                if (repo.isXtream()) {
                    if (repo.server.length() == 0 && repo.url != null
                            && repo.url.trim().length() > 0) {
                        repo.server = repo.url.trim();
                    }
                    if (repo.url == null || repo.url.trim().length() == 0) {
                        repo.url = repo.server;
                    }
                }
                if (repo.id.length() == 0) repo.id = "repo" + i;
                repos.add(repo);
            }
        } catch (Throwable t) {
            // Un texto dañado no debe romper la pantalla: se empieza de cero.
            Diag.add("Películas · no se pudo leer la lista de repositorios: " + Net.describe(t));
        }
        return repos;
    }

    static void save(Context context, ArrayList<Repo> repos) {
        StringBuilder out = new StringBuilder("[");
        if (repos != null) {
            for (int i = 0; i < repos.size(); i++) {
                Repo repo = repos.get(i);
                if (repo == null) continue;
                if (out.length() > 1) out.append(',');
                out.append('{');
                out.append("\"id\":\"").append(escape(repo.id)).append("\",");
                out.append("\"url\":\"").append(escape(repo.url)).append("\",");
                out.append("\"name\":\"").append(escape(repo.name)).append("\",");
                out.append("\"enabled\":").append(repo.enabled).append(',');
                out.append("\"preset\":").append(repo.preset).append(',');
                out.append("\"kind\":\"").append(escape(repo.kind)).append("\",");
                out.append("\"server\":\"").append(escape(repo.server)).append("\",");
                out.append("\"user\":\"").append(escape(repo.user)).append("\",");
                out.append("\"pass\":\"").append(escape(repo.pass)).append("\",");
                out.append("\"status\":\"").append(escape(repo.status)).append("\",");
                out.append("\"count\":").append(repo.itemCount).append(',');
                out.append("\"updated\":").append(repo.updated).append(',');
                out.append("\"items\":\"").append(escape(repo.itemsJson)).append('"');
                out.append('}');
            }
        }
        out.append(']');
        try {
            prefs(context).edit().putString(KEY, out.toString()).apply();
        } catch (Throwable t) {
            Diag.add("Películas · no se pudo guardar la lista de repositorios: " + Net.describe(t));
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static Repo find(Context context, String id) {
        ArrayList<Repo> repos = list(context);
        for (int i = 0; i < repos.size(); i++) {
            if (repos.get(i).id.equals(id)) return repos.get(i);
        }
        return null;
    }

    /**
     * Añade un repositorio nuevo. Si esa dirección ya estaba guardada devuelve la
     * que ya existía (no se duplica) con el mismo id.
     */
    static Repo add(Context context, String url) {
        ArrayList<Repo> repos = list(context);
        String clean = url == null ? "" : url.trim();
        for (int i = 0; i < repos.size(); i++) {
            Repo existing = repos.get(i);
            if (existing.isXtream()) continue;
            if (existing.url.equalsIgnoreCase(clean)) return existing;
        }
        Repo repo = new Repo();
        repo.url = clean;
        repo.id = "r" + Long.toString(System.currentTimeMillis(), 36);
        repo.enabled = true;
        repos.add(repo);
        save(context, repos);
        return repo;
    }

    /**
     * Añade una dirección escrita por el usuario, sea del tipo que sea.
     *
     * Si la dirección es de Xtream Codes (…/get.php?username=…&password=… o
     * …/player_api.php?…), se guarda como LISTA XTREAM: sus datos se separan y las
     * siguientes actualizaciones se piden a la API, que devuelve solo las películas
     * y las series (no la lista completa, que puede pesar muchos megas). Cualquier
     * otra dirección se guarda tal cual.
     */
    static Repo addAny(Context context, String url) {
        String clean = url == null ? "" : url.trim();
        try {
            XtreamClient.Parsed parsed = XtreamClient.xtreamFrom(clean);
            if (parsed != null && parsed.ok()) {
                return addXtream(context, parsed.server, parsed.user, parsed.pass);
            }
        } catch (Throwable ignored) {
            // Si no se puede reconocer, se guarda como dirección normal.
        }
        return add(context, clean);
    }

    /**
     * Añade una lista de Xtream Codes (servidor + usuario + clave).
     *
     * Si esa misma lista ya estaba guardada (mismo servidor y mismo usuario) se
     * devuelve la que había, con la clave actualizada, en vez de duplicarla. Las
     * listas Xtream NO se mezclan con las direcciones de catálogo JSON: cada una
     * guarda su tipo en {@link Repo#kind}.
     */
    static Repo addXtream(Context context, String server, String user, String pass) {
        ArrayList<Repo> repos = list(context);
        String cleanServer = server == null ? "" : server.trim();
        String cleanUser = user == null ? "" : user.trim();
        String cleanPass = pass == null ? "" : pass.trim();
        for (int i = 0; i < repos.size(); i++) {
            Repo existing = repos.get(i);
            if (existing == null || !existing.isXtream()) continue;
            if (!existing.server.equalsIgnoreCase(cleanServer)) continue;
            if (!existing.user.equalsIgnoreCase(cleanUser)) continue;
            existing.pass = cleanPass;
            existing.url = cleanServer;
            existing.enabled = true;
            save(context, repos);
            return existing;
        }
        Repo repo = new Repo();
        repo.kind = KIND_XTREAM;
        repo.server = cleanServer;
        repo.url = cleanServer;
        repo.user = cleanUser;
        repo.pass = cleanPass;
        repo.name = "Xtream · " + Repo.hostOf(cleanServer) + " · " + cleanUser;
        repo.id = "x" + Long.toString(System.currentTimeMillis(), 36);
        repo.enabled = true;
        repos.add(repo);
        save(context, repos);
        return repo;
    }

    /** Cambia los datos de una lista Xtream ya guardada. */
    static Repo setXtream(Context context, String id, String server, String user, String pass) {
        ArrayList<Repo> repos = list(context);
        Repo found = null;
        String cleanServer = server == null ? "" : server.trim();
        String cleanUser = user == null ? "" : user.trim();
        String cleanPass = pass == null ? "" : pass.trim();
        for (int i = 0; i < repos.size(); i++) {
            Repo repo = repos.get(i);
            if (repo == null || !repo.id.equals(id)) continue;
            repo.kind = KIND_XTREAM;
            repo.server = cleanServer;
            repo.url = cleanServer;
            repo.user = cleanUser;
            repo.pass = cleanPass;
            repo.name = "Xtream · " + Repo.hostOf(cleanServer) + " · " + cleanUser;
            repo.status = "";
            found = repo;
        }
        save(context, repos);
        return found;
    }

    /** Todas las listas Xtream guardadas. */
    static ArrayList<Repo> xtreamRepos(Context context) {
        ArrayList<Repo> out = new ArrayList<Repo>();
        ArrayList<Repo> repos = list(context);
        for (int i = 0; i < repos.size(); i++) {
            Repo repo = repos.get(i);
            if (repo != null && repo.isXtream()) out.add(repo);
        }
        return out;
    }

    /** Direcciones de catálogo JSON guardadas (sin las listas Xtream). */
    static ArrayList<Repo> jsonRepos(Context context) {
        ArrayList<Repo> out = new ArrayList<Repo>();
        ArrayList<Repo> repos = list(context);
        for (int i = 0; i < repos.size(); i++) {
            Repo repo = repos.get(i);
            if (repo != null && !repo.isXtream()) out.add(repo);
        }
        return out;
    }

    static void remove(Context context, String id) {
        ArrayList<Repo> repos = list(context);
        for (int i = repos.size() - 1; i >= 0; i--) {
            if (repos.get(i).id.equals(id)) repos.remove(i);
        }
        save(context, repos);
    }

    static void setEnabled(Context context, String id, boolean enabled) {
        ArrayList<Repo> repos = list(context);
        for (int i = 0; i < repos.size(); i++) {
            if (repos.get(i).id.equals(id)) repos.get(i).enabled = enabled;
        }
        save(context, repos);
    }

    static void setName(Context context, String id, String name) {
        ArrayList<Repo> repos = list(context);
        for (int i = 0; i < repos.size(); i++) {
            if (repos.get(i).id.equals(id)) repos.get(i).name = name == null ? "" : name;
        }
        save(context, repos);
    }

    static void setStatus(Context context, String id, String status) {
        ArrayList<Repo> repos = list(context);
        for (int i = 0; i < repos.size(); i++) {
            if (repos.get(i).id.equals(id)) repos.get(i).status = status == null ? "" : status;
        }
        save(context, repos);
    }

    /** Guarda el resultado de una descarga correcta. */
    static void setLoaded(Context context, String id, String name, String itemsJson, int count) {
        ArrayList<Repo> repos = list(context);
        for (int i = 0; i < repos.size(); i++) {
            Repo repo = repos.get(i);
            if (!repo.id.equals(id)) continue;
            if (name != null && name.length() > 0) repo.name = name;
            repo.itemsJson = itemsJson == null ? "" : itemsJson;
            repo.itemCount = count;
            repo.updated = System.currentTimeMillis();
            repo.status = count == 0
                    ? "El catálogo respondió pero no contiene títulos."
                    : "Catálogo actualizado correctamente.";
        }
        save(context, repos);
    }

    /**
     * Crea los proveedores que vienen dentro de la app (Pelispedia, Flixlatam,
     * Latanime…). Se llama al abrir las pantallas de películas y de repositorios:
     * así aparecen siempre, ya creados, sin que el usuario tenga que añadirlos a
     * mano, y se recuperan aunque se hayan restablecido.
     */
    static void seedPresets(Context context) {
        if (context == null) return;
        ArrayList<Repo> repos = list(context);
        boolean changed = false;
        ArrayList<Providers.Preset> presets = Providers.all();
        for (int p = 0; p < presets.size(); p++) {
            Providers.Preset preset = presets.get(p);
            String id = preset.repoId();
            boolean found = false;
            for (int i = 0; i < repos.size(); i++) {
                Repo repo = repos.get(i);
                if (repo != null && repo.id.equals(id)) {
                    found = true;
                    if (!repo.preset) {
                        repo.preset = true;
                        changed = true;
                    }
                    if ((repo.name == null || repo.name.length() == 0)) {
                        repo.name = preset.name;
                        changed = true;
                    }
                    break;
                }
            }
            if (found) continue;
            Repo repo = new Repo();
            repo.id = id;
            repo.name = preset.name;
            repo.url = preset.defaultUrl;
            repo.enabled = preset.defaultUrl.length() > 0;
            repo.preset = true;
            repo.status = preset.defaultUrl.length() > 0 ? ""
                    : "Falta la dirección: pégala en este proveedor y pulsa GUARDAR Y DESCARGAR.";
            repos.add(repo);
            changed = true;
        }
        // Limpieza: fichas de proveedores que ya no existen en la lista actual y
        // que nunca llegaron a tener contenido (por ejemplo, si la lista de
        // proveedores integrados cambia entre versiones). No se toca nada que
        // tenga títulos guardados.
        for (int i = repos.size() - 1; i >= 0; i--) {
            Repo repo = repos.get(i);
            if (repo == null || !repo.preset) continue;
            if (!Providers.isOrphan(repo.id)) continue;
            if (repo.hasItems()) continue;
            repos.remove(i);
            changed = true;
        }
        if (changed) save(context, repos);
    }

    /** Guarda la dirección de un proveedor integrado o de cualquier repositorio. */
    static Repo setUrl(Context context, String id, String url) {
        ArrayList<Repo> repos = list(context);
        Repo found = null;
        String clean = url == null ? "" : url.trim();
        for (int i = 0; i < repos.size(); i++) {
            Repo repo = repos.get(i);
            if (repo == null || !repo.id.equals(id)) continue;
            repo.url = clean;
            repo.status = clean.length() > 0 ? "" : "Falta la dirección: pégala y pulsa GUARDAR.";
            found = repo;
        }
        save(context, repos);
        return found;
    }

    /**
     * Restablece un proveedor integrado: borra su dirección y el catálogo
     * guardado, pero la ficha sigue en la app para poder configurarla otra vez.
     */
    static void resetPreset(Context context, String id) {
        ArrayList<Repo> repos = list(context);
        for (int i = 0; i < repos.size(); i++) {
            Repo repo = repos.get(i);
            if (repo == null || !repo.id.equals(id)) continue;
            repo.url = "";
            repo.itemsJson = "";
            repo.itemCount = 0;
            repo.updated = 0L;
            repo.enabled = false;
            repo.status = "Restablecido. Pega otra vez la dirección de este proveedor.";
        }
        save(context, repos);
    }

    private static String escape(String value) {
        if (value == null) return "";
        StringBuilder out = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':
                    out.append("\\\"");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        String hex = Integer.toHexString(c);
                        out.append("\\u");
                        for (int pad = hex.length(); pad < 4; pad++) out.append('0');
                        out.append(hex);
                    } else {
                        out.append(c);
                    }
                    break;
            }
        }
        return out.toString();
    }
}
