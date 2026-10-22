package com.pocketbuilder.app_wslenytvplayer1792356127271;

import android.content.Context;

import com.pocketbuilder.app_wslenytvplayer1792356127271.movies.MoviesPrefs;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Descarga y entiende los repositorios de películas y series.
 *
 * FORMATO LENY (el que se puede pegar en ADMINISTRAR REPOSITORIOS)
 * ----------------------------------------------------------------
 * El enlace debe devolver un archivo JSON. Lo mínimo es una lista de títulos:
 *
 *   {"name": "Mi repositorio",
 *    "items": [
 *      {"title": "Una película", "year": 2021,
 *       "poster": "https://servidor/cartel.jpg",
 *       "genres": ["Acción", "Drama"],
 *       "synopsis": "Resumen...",
 *       "url": "https://servidor/pelicula.m3u8"},
 *
 *      {"title": "Una serie", "kind": "series",
 *       "seasons": [
 *         {"number": 1, "episodes": [
 *           {"number": 1, "title": "Piloto", "url": "https://servidor/c1e1.m3u8"}
 *         ]}
 *       ]}
 *    ]}
 *
 * También se aceptan "sources": [{"label": "HD", "url": "..."}] en lugar de "url",
 * y "episodes" suelto (sin temporadas) para series de una sola temporada.
 * Cualquier enlace HLS, DASH, MP4, WebM o TS que el reproductor interno sepa
 * abrir se puede usar aquí.
 */
final class CatalogLoader {

    /**
     * Tope de títulos por fuente. Las listas de IPTV traen miles de películas y
     * series, así que el tope se ha subido: por debajo de esto el catálogo se
     * guarda y se abre sin problemas en el teléfono.
     */
    static final int MAX_ITEMS = 1200;

    /** Tope de descarga de una fuente (las listas M3U pueden ser muy grandes). */
    private static final int MAX_DOWNLOAD = 24 * 1024 * 1024;

    /** Error con el texto que se le enseña al usuario. */
    static final class CatalogError extends Exception {
        CatalogError(String message) {
            super(message);
        }
    }

    static final class Loaded {
        String name = "";
        String itemsJson = "[]";
        int count = 0;
        /** Informe de lo leído (películas, series, canales ignorados…). */
        String note = "";
        final ArrayList<MovieItem> items = new ArrayList<MovieItem>();
    }

    private CatalogLoader() {
    }

    /** Comprueba que la dirección tenga forma de enlace web. */
    static String normalizeUrl(String raw) throws CatalogError {
        String url = raw == null ? "" : raw.trim();
        if (url.length() == 0) {
            throw new CatalogError("Escribe la dirección del repositorio (http:// o https://).");
        }
        String lower = url.toLowerCase(Locale.US);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            throw new CatalogError("La dirección debe empezar por http:// o https://");
        }
        if (url.indexOf(' ') >= 0) {
            throw new CatalogError("La dirección tiene espacios. Cópiala otra vez completa.");
        }
        return url;
    }

    /**
     * Descarga una fuente guardada, sea del tipo que sea.
     *
     * Es el punto único que usan las pantallas: si la fuente es una dirección de
     * catálogo JSON se lee como siempre; si es una lista de Xtream Codes se lee
     * con {@link XtreamClient}. El resultado tiene la misma forma en los dos
     * casos, así que la cuadrícula de películas no cambia.
     */
    static Loaded fetchRepo(Context context, CatalogStore.Repo repo) throws Exception {
        if (repo == null) {
            throw new CatalogError("Ese repositorio no existe.");
        }
        if (!repo.isXtream()) {
            return fetch(context, repo.url);
        }
        XtreamClient.Loaded xtream = XtreamClient.load(context, repo.server, repo.user, repo.pass);
        return fromXtream(xtream);
    }

    /**
     * Convierte lo leído con la API de Xtream en el mismo resultado que un
     * catálogo descargado: así la cuadrícula de PELÍCULAS no cambia.
     */
    static Loaded fromXtream(XtreamClient.Loaded xtream) {
        Loaded loaded = new Loaded();
        loaded.name = xtream.name;
        loaded.itemsJson = xtream.itemsJson;
        loaded.count = xtream.count;
        loaded.note = xtream.note();
        return loaded;
    }

    /**
     * Vuelve a escribir una lista de títulos como el JSON que lee la app. Se usa
     * también al leer una lista Xtream, para que lo descargado y lo guardado
     * tengan siempre exactamente el mismo formato.
     */
    static String serialize(ArrayList<MovieItem> items) {
        return itemsToJson(items == null ? new ArrayList<MovieItem>() : items);
    }

    /** Descarga el repositorio (usa la red, así que se llama fuera del hilo principal). */
    static Loaded fetch(Context context, String rawUrl) throws Exception {
        String url = normalizeUrl(rawUrl);
        if (!Net.online(context)) {
            throw new CatalogError("Sin conexión a Internet. Se muestra el catálogo guardado.");
        }
        // Si la dirección es de Xtream Codes (…/get.php?username=…&password=…), se
        // leen SOLO sus películas y series con la API: es mucho más ligero que
        // descargar la lista completa, que puede pesar muchos megas.
        XtreamClient.Parsed xtream = XtreamClient.xtreamFrom(url);
        if (xtream != null && xtream.ok()) {
            return fromXtream(XtreamClient.load(context, xtream.server, xtream.user,
                    xtream.pass));
        }
        String text = fetchText(context, url);
        if (text == null || text.trim().length() == 0) {
            throw new CatalogError("El servidor respondió vacío. Revisa la dirección.");
        }
        // Una lista de IPTV (M3U/M3U8) trae canales, películas y series: se lee
        // con M3uVod y solo se guarda el VOD (películas y series).
        if (M3uVod.looksLikeM3u(text)) {
            return fromM3u(url, text);
        }
        Loaded loaded = fromText(text, "");
        if (loaded.count == 0) {
            throw new CatalogError("El repositorio respondió pero no tiene títulos.");
        }
        return loaded;
    }

    /**
     * Convierte una lista M3U/M3U8 en el catálogo de PELÍCULAS: solo sus
     * películas y sus series. Los canales en directo se cuentan y se explican.
     */
    static Loaded fromM3u(String url, String text) throws CatalogError {
        M3uVod.Report report = M3uVod.parse(text, "");
        if (report.items.isEmpty()) {
            throw new CatalogError(report.emptyMessage());
        }
        Loaded loaded = new Loaded();
        loaded.name = report.name.length() > 0 ? report.name : "Lista M3U · " + hostOf(url);
        // Cada título guarda de qué lista viene (su «Origen» en la ficha).
        for (int i = 0; i < report.items.size(); i++) {
            MovieItem item = report.items.get(i);
            if (item != null) item.repoName = loaded.name;
        }
        loaded.items.addAll(report.items);
        loaded.count = report.items.size();
        loaded.note = report.note();
        loaded.itemsJson = itemsToJson(report.items);
        return loaded;
    }

    /** Nombre del servidor de una dirección, para títulos y resúmenes. */
    static String hostOf(String rawUrl) {
        String value = rawUrl == null ? "" : rawUrl.trim();
        int scheme = value.indexOf("://");
        if (scheme >= 0) value = value.substring(scheme + 3);
        int slash = value.indexOf('/');
        if (slash > 0) value = value.substring(0, slash);
        int query = value.indexOf('?');
        if (query > 0) value = value.substring(0, query);
        return value.length() == 0 ? "tu lista" : value;
    }

    /**
     * Descarga el texto del repositorio. Se hace aquí, y no con el lector de
     * listas M3U de la app, porque el catálogo es un archivo JSON: así el mensaje
     * de error dice exactamente qué ha pasado (404, permiso, servidor caído…).
     */
    static String fetchText(Context context, String url) throws CatalogError {
        java.net.HttpURLConnection conn = null;
        try {
            conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(20000);
            conn.setInstanceFollowRedirects(true);
            // Cabeceras del MÓDULO DE PELÍCULAS, no las de la televisión: así
            // cambiar el User-Agent de los canales no altera las descargas del
            // catálogo, y al contrario.
            Map<String, String> configured = MoviesPrefs.headers(context);
            boolean hasUserAgent = false;
            if (configured != null) {
                for (Map.Entry<String, String> entry : configured.entrySet()) {
                    if (entry.getKey() == null || entry.getValue() == null) continue;
                    if ("User-Agent".equalsIgnoreCase(entry.getKey())) hasUserAgent = true;
                    conn.setRequestProperty(entry.getKey(), entry.getValue());
                }
            }
            if (!hasUserAgent) conn.setRequestProperty("User-Agent", Net.DEFAULT_UA);
            int code = conn.getResponseCode();
            if (code == 404) {
                throw new CatalogError("La dirección no existe en el servidor (error 404). "
                        + "Copia otra vez el enlace del catálogo.");
            }
            if (code == 401 || code == 403) {
                throw new CatalogError("El servidor rechaza la descarga (error " + code + "). "
                        + "Ese repositorio puede necesitar una cuenta.");
            }
            if (code < 200 || code >= 300) {
                throw new CatalogError("El servidor respondió con el error " + code + ".");
            }
            java.io.InputStream in = conn.getInputStream();
            // Algunos servidores de listas comprimen la respuesta. Se descomprime
            // para poder leer el texto de la lista.
            String encoding = conn.getContentEncoding();
            if (encoding != null && encoding.toLowerCase(Locale.US).indexOf("gzip") >= 0) {
                in = new java.util.zip.GZIPInputStream(new java.io.BufferedInputStream(in, 8192));
            }
            try {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buffer = new byte[16384];
                int total = 0;
                int read = in.read(buffer);
                while (read > 0) {
                    total += read;
                    if (total > MAX_DOWNLOAD) {
                        throw new CatalogError("La fuente pesa demasiado (más de "
                                + (MAX_DOWNLOAD / (1024 * 1024)) + " MB). Prueba con una lista "
                                + "más pequeña.");
                    }
                    out.write(buffer, 0, read);
                    read = in.read(buffer);
                }
                return Net.decode(out.toByteArray(), conn.getContentType());
            } finally {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        } catch (CatalogError error) {
            throw error;
        } catch (Throwable t) {
            throw new CatalogError("No se pudo descargar el repositorio. " + Net.describe(t));
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** Convierte el texto JSON de un repositorio en títulos. */
    static Loaded fromText(String text, String fallbackName) throws CatalogError {
        String clean = text == null ? "" : text.trim();
        if (clean.length() == 0) {
            throw new CatalogError("El repositorio vino vacío.");
        }
        String lower = clean.substring(0, Math.min(clean.length(), 400)).toLowerCase(Locale.US);
        if (lower.startsWith("#extm3u") || lower.contains("#extinf")) {
            // Lista de IPTV: se lee con M3uVod y se queda solo con el VOD.
            return fromM3u("", text);
        }
        if (lower.startsWith("<!doctype") || lower.startsWith("<html")) {
            throw new CatalogError("La dirección devuelve una página web, no un catálogo. "
                    + "Pega el enlace directo al archivo .json.");
        }
        Object root;
        try {
            root = Json.parse(clean);
        } catch (Throwable t) {
            throw new CatalogError("El texto no es un catálogo válido ("
                    + Net.describe(t) + "). Debe ser un archivo JSON.");
        }

        Loaded loaded = new Loaded();
        loaded.name = fallbackName == null ? "" : fallbackName;
        List<Object> rows;
        if (root instanceof List) {
            rows = Json.array(root);
        } else {
            Map<String, Object> map = Json.object(root);
            String name = Json.clean(map.get("name"));
            if (name.length() == 0) name = Json.clean(map.get("nombre"));
            if (name.length() > 0) loaded.name = name;
            rows = catalogRows(map, 0);
        }
        if (rows.isEmpty()) {
            // Se explica qué es lo que hay en la dirección (lista M3U, índice de
            // CloudStream, complemento de Stremio, página web…) en vez de dar un
            // error genérico.
            RepoProbe.Result probe = RepoProbe.analyze(clean);
            StringBuilder message = new StringBuilder(probe.headline);
            if (!probe.lines.isEmpty()) {
                message.append(' ').append(probe.lines.get(0));
            }
            throw new CatalogError(message.toString());
        }

        ArrayList<MovieItem> parsed = new ArrayList<MovieItem>();
        for (int i = 0; i < rows.size() && parsed.size() < MAX_ITEMS; i++) {
            MovieItem item = parseItem(rows.get(i), loaded.name);
            if (item != null) parsed.add(item);
        }
        if (parsed.isEmpty()) {
            throw new CatalogError("Ningún título del catálogo tiene nombre. Revisa el archivo.");
        }
        loaded.items.addAll(parsed);
        loaded.count = parsed.size();
        loaded.itemsJson = itemsToJson(parsed);
        return loaded;
    }

    /** Títulos del catálogo guardado de un repositorio (sin usar Internet). */
    static ArrayList<MovieItem> fromRepo(CatalogStore.Repo repo) {
        ArrayList<MovieItem> items = new ArrayList<MovieItem>();
        if (repo == null || !repo.hasItems()) return items;
        try {
            List<Object> rows = Json.array(Json.parse(repo.itemsJson));
            for (int i = 0; i < rows.size(); i++) {
                MovieItem item = parseItem(rows.get(i), repo.title());
                if (item != null) items.add(item);
            }
        } catch (Throwable t) {
            Diag.add("Películas · catálogo guardado ilegible: " + Net.describe(t));
        }
        return items;
    }

    /** Catálogo de demostración incluido en la app (assets/movies/demo-catalog.json). */
    static ArrayList<MovieItem> demo(Context context) {
        ArrayList<MovieItem> items = new ArrayList<MovieItem>();
        if (context == null) return items;
        String text = null;
        try {
            java.io.InputStream in = context.getAssets().open("movies/demo-catalog.json");
            try {
                text = Net.decode(readAll(in), "application/json");
            } finally {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            Diag.add("Películas · no se pudo leer el catálogo de demostración: " + Net.describe(t));
            return items;
        }
        try {
            Loaded loaded = fromText(text, CatalogStore.DEMO_NAME);
            items.addAll(loaded.items);
        } catch (Throwable t) {
            Diag.add("Películas · catálogo de demostración inválido: " + Net.describe(t));
        }
        return items;
    }

    private static byte[] readAll(java.io.InputStream in) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read = in.read(buffer);
        while (read > 0) {
            out.write(buffer, 0, read);
            read = in.read(buffer);
        }
        return out.toByteArray();
    }

    // ------------------------------------------------------------------ lectura

    private static MovieItem parseItem(Object row, String repoName) {
        if (!(row instanceof Map)) return null;
        Map<String, Object> map = Json.object(row);
        MovieItem item = new MovieItem();
        item.title = firstOf(map, new String[]{"title", "titulo", "título", "name", "nombre"});
        item.synopsis = firstOf(map, new String[]{"synopsis", "description", "overview",
                "sinopsis", "plot", "resumen"});
        if (item.title.length() == 0) return null;
        item.repoName = repoName == null ? "" : repoName;
        item.poster = firstOf(map, new String[]{"poster", "posterUrl", "image", "imagen",
                "cover", "caratula"});
        item.year = firstOf(map, new String[]{"year", "año", "ano", "release_year"});
        if (item.year.length() > 4) item.year = item.year.substring(0, 4);
        item.rating = Json.number(firstValue(map, new String[]{"rating", "nota", "vote_average",
                "score"}));

        Object genres = firstValue(map, new String[]{"genres", "generos", "géneros", "genres_names"});
        if (genres instanceof List) {
            List<Object> list = Json.array(genres);
            for (int i = 0; i < list.size(); i++) {
                String genre = Json.clean(list.get(i));
                if (genre.length() > 0) item.genres.add(genre);
            }
        } else {
            String raw = Json.clean(genres);
            if (raw.length() > 0) {
                String[] parts = raw.split("[,/|]");
                for (int i = 0; i < parts.length; i++) {
                    String genre = parts[i].trim();
                    if (genre.length() > 0) item.genres.add(genre);
                }
            }
        }

        String kind = firstOf(map, new String[]{"kind", "type", "tipo", "category"});
        boolean series = kind.toLowerCase(Locale.US).contains("serie")
                || kind.toLowerCase(Locale.US).contains("series")
                || map.containsKey("seasons") || map.containsKey("temporadas")
                || map.containsKey("episodes");

        readSources(map, item.sources);

        Object seasonsValue = map.containsKey("seasons") ? map.get("seasons") : map.get("temporadas");
        List<Object> seasons = Json.array(seasonsValue);
        for (int i = 0; i < seasons.size(); i++) {
            Map<String, Object> row2 = Json.object(seasons.get(i));
            if (row2.isEmpty()) continue;
            int number = Json.integer(firstValue(row2, new String[]{"number", "season", "numero",
                    "número", "temporada"}));
            if (number <= 0) number = i + 1;
            String title = firstOf(row2, new String[]{"title", "name", "titulo", "nombre"});
            MovieItem.Season season = new MovieItem.Season(number, title);
            readEpisodes(row2, season, number);
            if (!season.episodes.isEmpty()) item.seasons.add(season);
        }

        Object episodesValue = map.containsKey("episodes") ? map.get("episodes") : map.get("episodios");
        List<Object> looseEpisodes = Json.array(episodesValue);
        if (item.seasons.isEmpty() && !looseEpisodes.isEmpty()) {
            MovieItem.Season season = new MovieItem.Season(1, "");
            Map<String, Object> holder = new java.util.LinkedHashMap<String, Object>();
            holder.put("episodes", looseEpisodes);
            readEpisodes(holder, season, 1);
            if (!season.episodes.isEmpty()) item.seasons.add(season);
        }

        item.kind = (!item.seasons.isEmpty() || series) ? MovieItem.KIND_SERIES : MovieItem.KIND_MOVIE;
        // Series de Xtream guardadas: la dirección para pedir sus episodios.
        item.resolveUrl = firstOf(map, new String[]{"resolve", "seriesApi"});
        item.id = firstOf(map, new String[]{"id", "identifier", "slug"});
        if (item.id.length() == 0) {
            item.id = Integer.toHexString((item.title + repoName).hashCode());
        }
        return item;
    }

    private static void readSources(Map<String, Object> map, ArrayList<MovieItem.Source> into) {
        Object sourcesValue = firstValue(map, new String[]{"sources", "fuentes", "streams", "links"});
        List<Object> sources = Json.array(sourcesValue);
        for (int i = 0; i < sources.size(); i++) {
            Object entry = sources.get(i);
            if (entry instanceof Map) {
                Map<String, Object> row = Json.object(entry);
                String url = firstOf(row, new String[]{"url", "link", "file", "enlace"});
                if (url.length() == 0) continue;
                String label = firstOf(row, new String[]{"label", "name", "quality", "calidad",
                        "titulo"});
                if (label.length() == 0) label = "Enlace " + (i + 1);
                into.add(new MovieItem.Source(label, url,
                        firstOf(row, new String[]{"type", "tipo", "format"})));
            } else {
                String url = Json.clean(entry);
                if (url.length() == 0) continue;
                into.add(new MovieItem.Source("Enlace " + (i + 1), url, ""));
            }
        }
        String direct = firstOf(map, new String[]{"url", "link", "stream", "enlace", "video"});
        if (direct.length() > 0) {
            into.add(new MovieItem.Source("Principal", direct,
                    firstOf(map, new String[]{"streamType", "tipo"})));
        }
    }

    private static void readEpisodes(Map<String, Object> map, MovieItem.Season season, int seasonNumber) {
        Object episodesValue = map.containsKey("episodes") ? map.get("episodes")
                : map.get("episodios");
        List<Object> episodes = Json.array(episodesValue);
        for (int i = 0; i < episodes.size(); i++) {
            Object entry = episodes.get(i);
            if (!(entry instanceof Map)) continue;
            Map<String, Object> row = Json.object(entry);
            int number = Json.integer(firstValue(row, new String[]{"number", "episode", "numero",
                    "número", "episodio"}));
            if (number <= 0) number = i + 1;
            String title = firstOf(row, new String[]{"title", "name", "titulo", "nombre"});
            String synopsis = firstOf(row, new String[]{"synopsis", "description", "sinopsis",
                    "overview"});
            String still = firstOf(row, new String[]{"still", "image", "imagen", "poster"});
            MovieItem.Episode episode = new MovieItem.Episode(seasonNumber, number, title, synopsis,
                    still);
            readSources(row, episode.sources);
            if (!episode.sources.isEmpty()) season.episodes.add(episode);
        }
    }

    /**
     * Busca la lista de títulos dentro del archivo. Se aceptan varios nombres de
     * clave porque no todos los repositorios usan el mismo ("items", "catalogo",
     * "results", "titles"…) y, si viene envuelto en "data", se mira dentro.
     */
    private static List<Object> catalogRows(Map<String, Object> map, int depth) {
        String[] keys = {"items", "catalogo", "catalog", "results", "titles", "titulos",
                "movies", "peliculas", "series", "list", "lista", "content", "entries"};
        for (int i = 0; i < keys.length; i++) {
            List<Object> rows = Json.array(map.get(keys[i]));
            if (!rows.isEmpty()) return rows;
        }
        if (depth < 3) {
            Map<String, Object> data = Json.object(map.get("data"));
            if (!data.isEmpty()) return catalogRows(data, depth + 1);
        }
        return new ArrayList<Object>();
    }

    private static String firstOf(Map<String, Object> map, String[] keys) {
        return Json.clean(firstValue(map, keys));
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

    // ------------------------------------------------------------------ escritura

    /**
     * Vuelve a escribir los títulos como JSON. Así el catálogo descargado se
     * guarda en el teléfono con el MISMO formato que se lee luego, y no hay dos
     * maneras distintas de interpretarlo.
     */
    private static String itemsToJson(ArrayList<MovieItem> items) {
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            MovieItem item = items.get(i);
            if (item == null) continue;
            if (out.length() > 1) out.append(',');
            out.append('{');
            out.append("\"id\":").append(quoted(item.id)).append(',');
            out.append("\"title\":").append(quoted(item.title)).append(',');
            out.append("\"kind\":").append(quoted(item.kind)).append(',');
            out.append("\"poster\":").append(quoted(item.poster)).append(',');
            out.append("\"year\":").append(quoted(item.year)).append(',');
            out.append("\"rating\":").append(item.rating).append(',');
            out.append("\"synopsis\":").append(quoted(item.synopsis)).append(',');
            out.append("\"genres\":[");
            for (int g = 0; g < item.genres.size(); g++) {
                if (g > 0) out.append(',');
                out.append(quoted(item.genres.get(g)));
            }
            out.append("],\"sources\":[");
            for (int s = 0; s < item.sources.size(); s++) {
                if (s > 0) out.append(',');
                MovieItem.Source source = item.sources.get(s);
                out.append("{\"label\":").append(quoted(source.label)).append(",\"url\":")
                        .append(quoted(source.url)).append(",\"type\":")
                        .append(quoted(source.type)).append('}');
            }
            out.append(']');
            // Dirección para pedir los episodios de una serie de Xtream (solo las
            // series la tienen; en los demás títulos no se escribe nada).
            if (item.resolveUrl != null && item.resolveUrl.length() > 0) {
                out.append(",\"resolve\":").append(quoted(item.resolveUrl));
            }
            if (!item.seasons.isEmpty()) {
                out.append(",\"seasons\":[");
                for (int s = 0; s < item.seasons.size(); s++) {
                    if (s > 0) out.append(',');
                    MovieItem.Season season = item.seasons.get(s);
                    out.append("{\"number\":").append(season.number).append(",\"title\":")
                            .append(quoted(season.title)).append(",\"episodes\":[");
                    for (int e = 0; e < season.episodes.size(); e++) {
                        if (e > 0) out.append(',');
                        MovieItem.Episode episode = season.episodes.get(e);
                        out.append("{\"number\":").append(episode.number).append(",\"title\":")
                                .append(quoted(episode.title)).append(",\"synopsis\":")
                                .append(quoted(episode.synopsis)).append(",\"sources\":[");
                        for (int k = 0; k < episode.sources.size(); k++) {
                            if (k > 0) out.append(',');
                            MovieItem.Source source = episode.sources.get(k);
                            out.append("{\"label\":").append(quoted(source.label))
                                    .append(",\"url\":").append(quoted(source.url))
                                    .append(",\"type\":").append(quoted(source.type)).append('}');
                        }
                        out.append("]}");
                    }
                    out.append("]}");
                }
                out.append(']');
            }
            out.append('}');
        }
        out.append(']');
        return out.toString();
    }

    private static String quoted(String value) {
        if (value == null) return "\"\"";
        StringBuilder out = new StringBuilder(value.length() + 8);
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') {
                out.append('\\').append(c);
            } else if (c == '\n') {
                out.append("\\n");
            } else if (c == '\r') {
                out.append("\\r");
            } else if (c == '\t') {
                out.append("\\t");
            } else if (c < 0x20) {
                out.append(' ');
            } else {
                out.append(c);
            }
        }
        out.append('"');
        return out.toString();
    }
}
