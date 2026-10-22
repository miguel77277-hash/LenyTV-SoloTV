package com.pocketbuilder.app_wslenytvplayer1792356127271;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Comprueba si una dirección sirve para el motor de LENY TV.
 *
 * El usuario pega una dirección y esta clase dice, en lenguaje normal, qué es
 * exactamente lo que hay ahí y si la app puede usarlo:
 *
 *   - catálogo JSON de títulos (lo que sí se puede añadir),
 *   - lista M3U/M3U8 de canales (va en la pantalla de TV, no es un catálogo),
 *   - índice de repositorio de CloudStream (repo.json),
 *   - lista de complementos de CloudStream (plugins.json con archivos .cs3),
 *   - complemento de Stremio,
 *   - página web o texto que no se reconoce.
 *
 * En los índices de CloudStream se leen los NOMBRES de sus proveedores y se
 * avisa de que sus archivos .cs3 son código compilado para CloudStream y no se
 * pueden ejecutar aquí. Eso es lo que sí se puede reutilizar de su arquitectura:
 * el índice y sus datos, no el complemento.
 */
final class RepoProbe {

    static final String KIND_CATALOG = "catalog";
    static final String KIND_CLOUDSTREAM_INDEX = "cs-index";
    static final String KIND_CLOUDSTREAM_PLUGINS = "cs-plugins";
    static final String KIND_STREMIO = "stremio";
    /** Enlace directo a un complemento compilado (.cs3 y compañía). */
    static final String KIND_PLUGIN = "plugin";
    static final String KIND_M3U = "m3u";
    /** Lista de Xtream Codes: se comprueba por su API, sin bajar la lista entera. */
    static final String KIND_XTREAM = "xtream";
    static final String KIND_WEB = "web";
    static final String KIND_UNKNOWN = "unknown";

    /** Máximo de proveedores que se listan de un índice de CloudStream. */
    private static final int MAX_PLUGINS = 40;

    static final class Plugin {
        final String name;
        final String detail;
        final String url;

        Plugin(String name, String detail, String url) {
            this.name = name == null ? "" : name;
            this.detail = detail == null ? "" : detail;
            this.url = url == null ? "" : url;
        }

        boolean cs3() {
            return url.toLowerCase(Locale.US).endsWith(".cs3");
        }
    }

    static final class Result {
        String kind = KIND_UNKNOWN;
        String title = "";
        String address = "";
        /** Verdadero solo si el contenido se puede añadir como catálogo de títulos. */
        boolean usable = false;
        int itemCount = 0;
        /** Frase corta de veredicto. */
        String headline = "";
        /** Explicación en lenguaje normal. */
        final ArrayList<String> lines = new ArrayList<String>();
        final ArrayList<Plugin> plugins = new ArrayList<Plugin>();
        /** Siguiente dirección que hay que leer (lista de complementos). */
        String nextUrl = "";
    }

    private RepoProbe() {
    }

    /** Descarga la dirección y la analiza. Se llama fuera del hilo principal. */
    static Result inspect(Context context, String rawUrl) {
        Result result = new Result();
        String url = rawUrl == null ? "" : rawUrl.trim();
        result.address = url;
        if (isPluginUrl(url)) {
            return pluginAnswer(result, url);
        }
        // Lista de Xtream Codes: se comprueba por la API (películas y series) en
        // vez de descargar la lista completa, que puede pesar muchos megas.
        XtreamClient.Parsed xtream = XtreamClient.xtreamFrom(url);
        if (xtream != null && xtream.ok()) {
            return XtreamClient.probe(context, xtream);
        }
        String text;
        try {
            text = CatalogLoader.fetchText(context, url);
        } catch (Throwable t) {
            String message = t.getMessage();
            result.kind = KIND_UNKNOWN;
            result.headline = "No se pudo descargar esa dirección.";
            result.lines.add(message == null || message.trim().length() == 0
                    ? "No se pudo descargar. " + Net.describe(t) : message);
            return result;
        }
        result = analyze(text);
        result.address = url;

        // Un índice de repositorio de CloudStream apunta a su lista de
        // complementos: se lee una vez para poder enseñar los nombres.
        if (KIND_CLOUDSTREAM_INDEX.equals(result.kind) && result.nextUrl.length() > 0) {
            String listUrl = result.nextUrl;
            result.nextUrl = "";
            Result list;
            try {
                list = analyze(CatalogLoader.fetchText(context, listUrl));
            } catch (Throwable t) {
                String message = t.getMessage();
                result.lines.add("No se pudo leer su lista de complementos. "
                        + (message == null ? Net.describe(t) : message));
                return result;
            }
            if (list.plugins.isEmpty()) {
                result.lines.add("Su lista de complementos no contiene proveedores legibles.");
            } else {
                result.plugins.addAll(list.plugins);
                result.lines.add("Su lista de complementos contiene " + list.plugins.size()
                        + (list.plugins.size() == 1 ? " proveedor." : " proveedores.")
                        + " Son archivos .cs3 de CloudStream: aquí solo se pueden ver sus nombres,"
                        + " no ejecutarlos.");
            }
        }
        return result;
    }

    /** Analiza el texto descargado sin usar Internet (también sirve para probar). */
    static Result analyze(String text) {
        Result result = new Result();
        String clean = text == null ? "" : text.trim();
        if (clean.length() == 0) {
            result.kind = KIND_UNKNOWN;
            result.headline = "La dirección respondió vacía.";
            result.lines.add("El servidor no devolvió ningún contenido. Revisa la dirección.");
            return result;
        }
        String head = clean.substring(0, Math.min(clean.length(), 2000)).toLowerCase(Locale.US);
        if (head.startsWith("#extm3u") || head.contains("#extinf")) {
            // Lista de IPTV: se lee para ver si trae películas y series (VOD).
            M3uVod.Report report = M3uVod.parse(clean, "");
            result.kind = KIND_M3U;
            if (!report.items.isEmpty()) {
                result.usable = true;
                result.itemCount = report.items.size();
                result.headline = "Compatible: lista IPTV con " + report.movies
                        + (report.movies == 1 ? " película" : " películas") + " y "
                        + report.series + (report.series == 1 ? " serie" : " series") + ".";
                result.lines.add("Se añaden solo sus películas y series (VOD). "
                        + report.note() + ".");
                result.lines.add("Los canales en directo de la lista no entran aquí: se ven en la "
                        + "pantalla de TV de la app.");
            } else {
                result.headline = "Es una lista M3U, pero no tiene películas ni series.";
                result.lines.add(report.emptyMessage());
                result.lines.add("Las listas solo de canales se abren en la pantalla de TV: ajustes "
                        + "de lista (M3U) de la barra superior.");
            }
            return result;
        }
        if (head.startsWith("<!doctype") || head.startsWith("<html") || head.startsWith("<?")) {
            result.kind = KIND_WEB;
            result.headline = "Es una página web, no un archivo de catálogo.";
            result.lines.add("La app necesita el enlace directo al archivo de títulos (normalmente "
                    + "termina en .json). Abre la página, busca el enlace del catálogo y pégalo aquí.");
            return result;
        }

        Object root;
        try {
            root = Json.parse(clean);
        } catch (Throwable t) {
            result.kind = KIND_UNKNOWN;
            result.headline = "El texto no es un catálogo válido.";
            result.lines.add("No se pudo leer como JSON (" + Net.describe(t) + "). La app solo "
                    + "entiende catálogos en JSON o índices de CloudStream/Stremio.");
            return result;
        }

        if (root instanceof List) {
            List<Object> rows = Json.array(root);
            if (looksLikePlugins(rows)) {
                readPlugins(result, rows);
                return result;
            }
            return asCatalog(result, rows);
        }

        Map<String, Object> map = Json.object(root);
        String name = Json.clean(firstValue(map, new String[]{"name", "nombre", "title", "titulo"}));

        // 1) Catálogo de títulos con la forma que ya usa la app.
        List<Object> rows = catalogRows(map);
        if (!rows.isEmpty()) {
            return asCatalog(result, rows);
        }

        // 2) Índice de repositorio de CloudStream: nombre + pluginLists.
        List<Object> pluginLists = Json.array(firstValue(map,
                new String[]{"pluginLists", "pluginListsUrls", "pluginlists"}));
        if (!pluginLists.isEmpty()) {
            result.kind = KIND_CLOUDSTREAM_INDEX;
            result.title = name;
            result.headline = "Es el índice de un repositorio de CloudStream"
                    + (name.length() > 0 ? " («" + name + "»)" : "") + ".";
            result.lines.add("Los proveedores de un repositorio de CloudStream son complementos "
                    + ".cs3: código compilado que solo funciona dentro de CloudStream. LENY TV no "
                    + "puede ejecutarlos, así que de esta dirección no pueden salir títulos.");
            result.lines.add("Lo que sí se aprovecha es su estructura: aquí se leen sus proveedores "
                    + "y, si alguno publica un catálogo en JSON, se añade con esa dirección.");
            result.nextUrl = Json.clean(pluginLists.get(0));
            return result;
        }

        // 3) Lista de complementos suelta (objeto con la clave "plugins").
        List<Object> plugins = Json.array(firstValue(map, new String[]{"plugins", "providers",
                "complementos"}));
        if (looksLikePlugins(plugins)) {
            readPlugins(result, plugins);
            return result;
        }

        // 4) Complemento de Stremio: su catálogo se pide por API, no es un archivo.
        if (map.containsKey("resources") || map.containsKey("catalogs")) {
            result.kind = KIND_STREMIO;
            result.title = name;
            result.headline = "Es un complemento de Stremio, no un catálogo de títulos.";
            result.lines.add("Stremio pide su catálogo a un servidor con su propio protocolo y "
                    + "necesita el reproductor Stremio. LENY TV solo entiende archivos JSON con la "
                    + "lista de títulos.");
            return result;
        }

        // 5) Objeto con datos anidados en "data".
        Map<String, Object> data = Json.object(map.get("data"));
        if (!data.isEmpty()) {
            List<Object> nested = catalogRows(data);
            if (!nested.isEmpty()) return asCatalog(result, nested);
        }

        result.kind = KIND_UNKNOWN;
        result.title = name;
        result.headline = "No se reconoce como catálogo de títulos.";
        result.lines.add("La dirección responde con datos, pero no con una lista de películas o "
                + "series. Debe ser un JSON con una lista de títulos (clave \\\"items\\\") o un índice "
                + "de CloudStream/Stremio.");
        return result;
    }

    // ------------------------------------------------------------------ ayudas

    /** Verdadero si la dirección apunta a un complemento compilado, no a un catálogo. */
    static boolean isPluginUrl(String url) {
        String clean = url == null ? "" : url.trim().toLowerCase(Locale.US);
        int cut = clean.indexOf('?');
        if (cut >= 0) clean = clean.substring(0, cut);
        cut = clean.indexOf('#');
        if (cut >= 0) clean = clean.substring(0, cut);
        return clean.endsWith(".cs3") || clean.endsWith(".jar") || clean.endsWith(".apk")
                || clean.endsWith(".dex");
    }

    /**
     * Respuesta para un enlace que apunta directamente a un complemento compilado.
     * Se contesta sin descargarlo: no es un catálogo y no se puede ejecutar aquí.
     */
    private static Result pluginAnswer(Result result, String url) {
        result.kind = KIND_PLUGIN;
        String name = url == null ? "" : url.trim();
        int slash = name.lastIndexOf('/');
        if (slash >= 0 && slash + 1 < name.length()) name = name.substring(slash + 1);
        result.title = name;
        result.headline = "Es un complemento compilado de CloudStream, no un catálogo.";
        result.lines.add("«" + name + "» es un archivo .cs3: código ya compilado que solo "
                + "funciona dentro de CloudStream, con su motor y su API de extensiones. LENY TV "
                + "no puede ejecutarlo.");
        result.lines.add("De un complemento no salen títulos: es un programa, no una lista. En "
                + "LENY TV sus proveedores ya vienen creados; lo que hay que pegar es la "
                + "dirección de su catálogo en JSON (o la de su índice de repositorio, para ver "
                + "los nombres de sus proveedores).");
        return result;
    }

    private static Result asCatalog(Result result, List<Object> rows) {
        int withTitle = 0;
        for (int i = 0; i < rows.size(); i++) {
            Object entry = rows.get(i);
            if (!(entry instanceof Map)) continue;
            Map<String, Object> row = Json.object(entry);
            String title = Json.clean(firstValue(row, new String[]{"title", "titulo", "título",
                    "name", "nombre"}));
            if (title.length() > 0) withTitle++;
        }
        result.kind = KIND_CATALOG;
        result.itemCount = withTitle;
        if (withTitle > 0) {
            result.usable = true;
            result.headline = "Compatible: catálogo con " + withTitle
                    + (withTitle == 1 ? " título." : " títulos.");
            result.lines.add("Se puede añadir tal cual: aparecerá en la cuadrícula con sus carteles, "
                    + "sinopsis, temporadas y episodios cuando el archivo los traiga.");
        } else {
            result.headline = "Es un JSON, pero no tiene títulos.";
            result.lines.add("Dentro del archivo no hay ninguna lista de títulos con nombre. Revisa "
                    + "que sea el catálogo y no un índice de repositorios.");
        }
        return result;
    }

    private static void readPlugins(Result result, List<Object> rows) {
        result.kind = KIND_CLOUDSTREAM_PLUGINS;
        for (int i = 0; i < rows.size() && result.plugins.size() < MAX_PLUGINS; i++) {
            Object entry = rows.get(i);
            if (!(entry instanceof Map)) continue;
            Map<String, Object> row = Json.object(entry);
            String internal = Json.clean(firstValue(row, new String[]{"internalName", "name",
                    "nombre"}));
            String url = Json.clean(firstValue(row, new String[]{"url", "file", "downloadUrl"}));
            String description = Json.clean(firstValue(row, new String[]{"description",
                    "descripcion", "authors"}));
            String version = Json.clean(row.get("version"));
            if (internal.length() == 0 && url.length() == 0) continue;
            String pretty = prettyPluginName(internal);
            StringBuilder detail = new StringBuilder();
            if (version.length() > 0) detail.append("versión ").append(version);
            if (description.length() > 0) {
                if (detail.length() > 0) detail.append(" · ");
                detail.append(description);
            }
            if (url.toLowerCase(Locale.US).endsWith(".cs3")) {
                if (detail.length() > 0) detail.append(" · ");
                detail.append(".cs3 no ejecutable aquí");
            }
            result.plugins.add(new Plugin(pretty, detail.toString(), url));
        }
        int count = result.plugins.size();
        result.headline = "Es una lista de complementos de CloudStream ("
                + count + (count == 1 ? " proveedor)." : " proveedores).");
        if (count == 0) {
            result.headline = "Es una lista de complementos de CloudStream, sin proveedores legibles.";
        }
        result.lines.add("Los complementos .cs3 son código compilado para el motor de CloudStream: "
                + "no se pueden ejecutar dentro de LENY TV. Abajo puedes ver sus nombres y, si "
                + "alguno publica su catálogo en JSON, añadirlo con esa dirección.");
    }

    private static boolean looksLikePlugins(List<Object> rows) {
        int checked = 0;
        for (int i = 0; i < rows.size() && checked < 5; i++) {
            Object entry = rows.get(i);
            if (!(entry instanceof Map)) continue;
            Map<String, Object> row = Json.object(entry);
            checked++;
            boolean hasName = row.containsKey("internalName");
            String url = Json.clean(firstValue(row, new String[]{"url", "file", "downloadUrl"}));
            boolean cs3 = url.toLowerCase(Locale.US).endsWith(".cs3");
            if (hasName || (cs3 && !row.containsKey("title"))) return true;
        }
        return false;
    }

    private static List<Object> catalogRows(Map<String, Object> map) {
        String[] keys = {"items", "catalogo", "catalog", "results", "titles", "titulos", "movies",
                "peliculas", "series", "list", "lista", "content", "entries"};
        for (int i = 0; i < keys.length; i++) {
            List<Object> rows = Json.array(map.get(keys[i]));
            if (!rows.isEmpty()) return rows;
        }
        return new ArrayList<Object>();
    }

    private static String prettyPluginName(String internal) {
        String name = internal == null ? "" : internal.trim();
        String[] suffixes = {".cs3", "PluginProvider", "Provider", "Plugin", "Extension"};
        for (int i = 0; i < suffixes.length; i++) {
            if (name.endsWith(suffixes[i]) && name.length() > suffixes[i].length()) {
                name = name.substring(0, name.length() - suffixes[i].length());
                break;
            }
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (i > 0 && Character.isUpperCase(c) && name.charAt(i - 1) != ' ') out.append(' ');
            out.append(c);
        }
        String pretty = out.toString().trim();
        return pretty.length() == 0 ? (internal == null ? "" : internal) : pretty;
    }

    private static Object firstValue(Map<String, Object> map, String[] keys) {
        if (map == null || keys == null) return null;
        for (int i = 0; i < keys.length; i++) {
            if (map.containsKey(keys[i])) {
                Object value = map.get(keys[i]);
                if (value != null) return value;
            }
        }
        return null;
    }
}
