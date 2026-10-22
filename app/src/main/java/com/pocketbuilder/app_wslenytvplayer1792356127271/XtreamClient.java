package com.pocketbuilder.app_wslenytvplayer1792356127271;

import android.content.Context;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * LECTOR DE LISTAS XTREAM CODES (servidor + usuario + contraseña).
 *
 * Qué hace, en palabras sencillas: pregunta al servidor del usuario qué
 * películas tiene, se queda con las que son vídeo (MKV, MP4, AVI, MOV, WebM,
 * TS…), les pone su cartel, su año, su nota y su categoría, y las entrega en el
 * MISMO formato que el catálogo JSON del módulo. Así la cuadrícula de PELÍCULAS
 * las enseña y las reproduce sin ninguna pantalla nueva.
 *
 * La API que se usa es la estándar de Xtream Codes:
 *
 *   {servidor}/player_api.php?username=…&password=…                  → cuenta
 *   {servidor}/player_api.php?username=…&password=…&action=get_vod_categories
 *   {servidor}/player_api.php?username=…&password=…&action=get_vod_streams
 *   {servidor}/movie/{usuario}/{clave}/{stream_id}.{extensión}       → vídeo
 *
 * Se leen las PELÍCULAS y también las SERIES:
 *
 *   {servidor}/player_api.php?…&action=get_series_categories
 *   {servidor}/player_api.php?…&action=get_series
 *   {servidor}/player_api.php?…&action=get_series_info&series_id=…   → episodios
 *   {servidor}/series/{usuario}/{clave}/{episodio}.{extensión}       → vídeo
 *
 * Las series se piden con una sola consulta (get_series). Sus temporadas y
 * episodios necesitan una consulta POR CADA SERIE, así que NO se descargan al
 * cargar la lista: se piden cuando el usuario abre esa serie en la app
 * ({@link #fetchEpisodes}). Así una cuenta con cientos de series no tarda
 * minutos en abrirse.
 *
 * Avisos honestos:
 *   - Se ocultan los títulos marcados como adultos.
 *   - Se descartan las entradas sin extensión de vídeo conocida, porque su
 *     enlace no se podría reproducir.
 *   - Que un enlace aparezca aquí NO garantiza que el teléfono pueda con él: eso
 *     depende del códec y del servidor. Si falla, el reproductor lo explica.
 */
public final class XtreamClient {

    /** Tope de películas que se guardan por lista, igual que el catálogo JSON. */
    public static final int MAX_ITEMS = CatalogLoader.MAX_ITEMS;

    /**
     * Tope de series por lista. Una cuenta grande puede tener miles; con las
     * primeras 600 la app abre rápida y el catálogo guardado no se dispara.
     */
    public static final int MAX_SERIES = 600;

    /**
     * Extensiones de vídeo que el reproductor del módulo puede intentar abrir.
     * Las que no estén aquí se descartan y se cuentan como «no reproducibles».
     */
    private static final String[] VIDEO_EXTENSIONS = {
            "mkv", "mp4", "m4v", "avi", "mov", "webm", "ts", "m2ts", "m3u8",
            "mpg", "mpeg", "flv", "wmv", "3gp", "ogv", "divx", "vob"
    };

    private XtreamClient() {
    }

    /** Resultado de leer una lista: mismo formato que un catálogo descargado. */
    public static final class Loaded {
        public String name = "";
        public String itemsJson = "[]";
        public int count = 0;
        /** Entradas descartadas por no ser un formato de vídeo reconocido. */
        public int skipped = 0;
        /** Entradas ocultas por estar marcadas como contenido para adultos. */
        public int hidden = 0;
        /** Estado de la cuenta según el servidor («Active», «Expired»…). */
        public String accountState = "";
        /** Cuántas películas se han guardado. */
        public int movies = 0;
        /** Cuántas series se han guardado. */
        public int series = 0;
        public final ArrayList<String> notes = new ArrayList<String>();

        /**
         * Informe corto para enseñar en la app: qué se ha guardado y qué se ha
         * descartado, en palabras normales.
         */
        public String note() {
            StringBuilder out = new StringBuilder("Por la API de Xtream: ");
            out.append(movies).append(movies == 1 ? " película" : " películas");
            out.append(" · ").append(series).append(series == 1 ? " serie" : " series");
            if (series > 0) out.append(" (sus episodios se cargan al abrir cada serie)");
            for (int i = 0; i < notes.size() && i < 2; i++) {
                String line = notes.get(i);
                if (line == null || line.length() == 0) continue;
                out.append(" · ").append(line);
            }
            return out.toString();
        }
    }

    // ------------------------------------------------------------- direcciones

    /**
     * Deja el servidor en una forma utilizable: con http:// o https:// delante,
     * sin barra final y sin la parte de player_api.php si el usuario la pegó.
     */
    public static String normalizeServer(String raw) throws CatalogLoader.CatalogError {
        String value = raw == null ? "" : raw.trim();
        if (value.length() == 0) {
            throw new CatalogLoader.CatalogError(
                    "Escribe el servidor de tu lista (por ejemplo http://midominio.com:8080).");
        }
        if (value.indexOf(' ') >= 0) {
            throw new CatalogLoader.CatalogError(
                    "El servidor tiene espacios. Cópialo otra vez completo.");
        }
        String lower = value.toLowerCase(Locale.US);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            value = "http://" + value;
            lower = value.toLowerCase(Locale.US);
        }
        // Se quita la parte de la API si se pegó el enlace completo.
        int api = lower.indexOf("/player_api.php");
        if (api > 0) {
            value = value.substring(0, api);
            lower = value.toLowerCase(Locale.US);
        }
        int cut = value.indexOf('?');
        if (cut > 0) value = value.substring(0, cut);
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        if (value.length() <= "https://".length()) {
            throw new CatalogLoader.CatalogError("La dirección del servidor está incompleta.");
        }
        return value;
    }

    /** Resultado de leer una URL Xtream completa escrita en un solo campo. */
    public static final class Parsed {
        public String server = "";
        public String user = "";
        public String pass = "";
        /** Motivo, en palabras sencillas, si la dirección no se pudo entender. */
        public String problem = "";

        public boolean ok() {
            return problem.length() == 0;
        }

        /** Texto con los datos reconocidos, para enseñarlos en pantalla. */
        public String describe() {
            if (!ok()) return problem;
            return "Reconocido · servidor: " + server + " · usuario: " + user
                    + " · contraseña: " + (pass.length() > 0 ? "sí" : "no");
        }
    }

    /**
     * Reconoce servidor, usuario y contraseña dentro de una URL Xtream completa,
     * para no tener que separar los datos a mano. Acepta las formas habituales:
     *
     *   http://midominio.com:8080/get.php?username=USUARIO&password=CLAVE&type=m3u_plus
     *   http://midominio.com:8080/player_api.php?username=USUARIO&password=CLAVE
     *   http://midominio.com:8080/movie/USUARIO/CLAVE/12345.mp4
     *   USUARIO:CLAVE@midominio.com:8080   (sin http:// delante)
     *
     * No inventa nada: si falta un dato, lo dice en «problem» y no se guarda nada.
     */
    public static Parsed parseFullUrl(String raw) {
        Parsed out = new Parsed();
        String value = raw == null ? "" : raw.trim();
        if (value.length() == 0) {
            out.problem = "Pega la URL Xtream completa (la que te dio tu proveedor).";
            return out;
        }
        if (value.indexOf(' ') >= 0) {
            out.problem = "La dirección tiene espacios. Cópiala otra vez completa.";
            return out;
        }
        // Forma "usuario:clave@servidor:puerto", sin http:// delante.
        int at = value.indexOf('@');
        if (at > 0 && value.indexOf("://") < 0) {
            String credentials = value.substring(0, at);
            String host = value.substring(at + 1);
            int colon = credentials.indexOf(':');
            out.user = colon > 0 ? credentials.substring(0, colon) : credentials;
            out.pass = colon > 0 ? credentials.substring(colon + 1) : "";
            out.server = schemeAndHost(host);
            return finishParsed(out);
        }
        String lower = value.toLowerCase(Locale.US);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            value = "http://" + value;
            lower = value.toLowerCase(Locale.US);
        }
        int schemeEnd = lower.indexOf("://") + 3;
        if (schemeEnd < 3 || schemeEnd >= value.length()) {
            out.problem = "A esa dirección le falta el servidor (por ejemplo midominio.com:8080).";
            return out;
        }
        String rest = value.substring(schemeEnd);
        String query = "";
        int q = rest.indexOf('?');
        if (q >= 0) {
            query = rest.substring(q + 1);
            rest = rest.substring(0, q);
        }
        String authority = rest;
        String path = "";
        int slash = rest.indexOf('/');
        if (slash >= 0) {
            authority = rest.substring(0, slash);
            path = rest.substring(slash);
        }
        if (authority.length() == 0) {
            out.problem = "A esa dirección le falta el servidor (por ejemplo midominio.com:8080).";
            return out;
        }
        out.server = schemeAndHost(authority);
        out.user = param(query, "username");
        if (out.user.length() == 0) out.user = param(query, "user");
        out.pass = param(query, "password");
        if (out.pass.length() == 0) out.pass = param(query, "pass");
        // Forma /movie/USUARIO/CLAVE/12345.mp4 o /series/USUARIO/CLAVE/…
        if (out.user.length() == 0 || out.pass.length() == 0) {
            String[] parts = path.split("/");
            ArrayList<String> chunks = new ArrayList<String>();
            for (int i = 0; i < parts.length; i++) {
                if (parts[i] != null && parts[i].trim().length() > 0) chunks.add(parts[i].trim());
            }
            int movie = -1;
            for (int i = 0; i < chunks.size(); i++) {
                if ("movie".equalsIgnoreCase(chunks.get(i))
                        || "series".equalsIgnoreCase(chunks.get(i))) {
                    movie = i;
                    break;
                }
            }
            if (movie >= 0 && movie + 2 < chunks.size()) {
                if (out.user.length() == 0) out.user = chunks.get(movie + 1);
                if (out.pass.length() == 0) out.pass = chunks.get(movie + 2);
            }
        }
        return finishParsed(out);
    }

    /** Deja solo el esquema y el servidor (sin ruta, sin parámetros, sin barra final). */
    private static String schemeAndHost(String host) {
        String value = host == null ? "" : host.trim();
        String lower = value.toLowerCase(Locale.US);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            value = "http://" + value;
            lower = value.toLowerCase(Locale.US);
        }
        int schemeEnd = lower.indexOf("://") + 3;
        String rest = schemeEnd > 2 && schemeEnd <= value.length()
                ? value.substring(schemeEnd) : "";
        int slash = rest.indexOf('/');
        if (slash >= 0) rest = rest.substring(0, slash);
        int q = rest.indexOf('?');
        if (q >= 0) rest = rest.substring(0, q);
        while (rest.endsWith("/")) rest = rest.substring(0, rest.length() - 1);
        String scheme = lower.startsWith("https://") ? "https://" : "http://";
        return scheme + rest;
    }

    /** Comprueba lo reconocido y deja el motivo escrito si algo falta. */
    private static Parsed finishParsed(Parsed out) {
        try {
            out.server = normalizeServer(out.server);
        } catch (CatalogLoader.CatalogError error) {
            out.problem = error.getMessage() == null
                    ? "La dirección del servidor está incompleta." : error.getMessage();
            return out;
        } catch (Throwable t) {
            out.problem = "No se pudo leer el servidor de esa dirección.";
            return out;
        }
        out.user = decode(out.user);
        out.pass = decode(out.pass);
        if (out.user.length() == 0) {
            out.problem = "En esa URL no aparece el usuario (suele ser …?username=TU_USUARIO"
                    + "&password=TU_CLAVE). Escríbelo en los campos de abajo.";
            return out;
        }
        if (out.pass.length() == 0) {
            out.problem = "En esa URL no aparece la contraseña (suele ser …&password=TU_CLAVE). "
                    + "Escríbela en los campos de abajo.";
            return out;
        }
        return out;
    }

    /**
     * Verdadero si esa dirección parece un acceso de Xtream Codes y no una lista
     * M3U suelta. Se reconoce por la forma del enlace que dan los proveedores:
     *
     *   …/get.php?username=…&password=…&type=m3u_plus&output=ts
     *   …/player_api.php?username=…&password=…
     *   …/movie/USUARIO/CLAVE/123.mp4  (y /series/, /live/)
     */
    public static boolean looksXtream(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.US);
        if (value.length() == 0) return false;
        if (value.indexOf("/get.php") >= 0 || value.indexOf("/player_api.php") >= 0) return true;
        if (value.indexOf("type=m3u") >= 0) return true;
        if (value.indexOf("username=") >= 0
                && (value.indexOf("/movie/") >= 0 || value.indexOf("/series/") >= 0
                || value.indexOf("/live/") >= 0)) {
            return true;
        }
        return false;
    }

    /**
     * Devuelve servidor, usuario y contraseña si la dirección es de Xtream y está
     * completa; null si no es Xtream o si le falta algún dato (en ese caso se
     * trata como una lista normal).
     */
    public static Parsed xtreamFrom(String raw) {
        if (!looksXtream(raw)) return null;
        try {
            Parsed parsed = parseFullUrl(raw);
            return parsed != null && parsed.ok() ? parsed : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Dirección Xtream completa y normalizada, a partir de los tres datos. */
    public static String fullUrl(String server, String user, String pass) {
        return server + "/get.php?username=" + encode(user) + "&password=" + encode(pass)
                + "&type=m3u_plus&output=ts";
    }

    /** Enlace de la API que devuelve las temporadas y episodios de una serie. */
    public static String seriesInfoUrl(String server, String user, String pass, String seriesId) {
        return apiUrl(server, user, pass, "get_series_info") + "&series_id=" + encode(seriesId);
    }

    /** Enlace de reproducción de un episodio de serie. */
    public static String episodeUrl(String server, String user, String pass, String episodeId,
                                    String extension) {
        String ext = extension == null ? "" : extension.trim().toLowerCase(Locale.US);
        if (ext.length() == 0) ext = "mp4";
        return server + "/series/" + encode(user) + "/" + encode(pass) + "/" + episodeId + "." + ext;
    }

    /** Valor de un parámetro de la dirección (?username=…&password=…). */
    private static String param(String query, String name) {
        if (query == null || query.length() == 0 || name == null) return "";
        String[] pairs = query.split("&");
        for (int i = 0; i < pairs.length; i++) {
            String pair = pairs[i] == null ? "" : pairs[i];
            int equal = pair.indexOf('=');
            if (equal <= 0) continue;
            String key = pair.substring(0, equal).trim();
            if (key.equalsIgnoreCase(name)) return pair.substring(equal + 1).trim();
        }
        return "";
    }

    /** Deshace los %40, %2E… que traen algunas direcciones, sin tocar los signos +. */
    private static String decode(String value) {
        String text = value == null ? "" : value.trim();
        if (text.indexOf('%') < 0) return text;
        try {
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '%' && i + 2 < text.length()) {
                    int code = Integer.parseInt(text.substring(i + 1, i + 3), 16);
                    out.append((char) code);
                    i += 2;
                } else {
                    out.append(c);
                }
            }
            return out.toString();
        } catch (Throwable t) {
            return text;
        }
    }

    /** Enlace de la API para un recurso concreto de la lista. */
    private static String apiUrl(String server, String user, String pass, String action) {
        StringBuilder out = new StringBuilder(server);
        out.append("/player_api.php?username=").append(encode(user));
        out.append("&password=").append(encode(pass));
        if (action != null && action.length() > 0) {
            out.append("&action=").append(action);
        }
        return out.toString();
    }

    /** Enlace de reproducción de una película. */
    public static String movieUrl(String server, String user, String pass, String streamId,
                                 String extension) {
        String ext = extension == null ? "" : extension.trim().toLowerCase(Locale.US);
        if (ext.length() == 0) ext = "mp4";
        return server + "/movie/" + encode(user) + "/" + encode(pass) + "/"
                + streamId + "." + ext;
    }

    private static String encode(String value) {
        try {
            return java.net.URLEncoder.encode(value == null ? "" : value, "UTF-8")
                    .replace("+", "%20");
        } catch (Throwable t) {
            return value == null ? "" : value;
        }
    }

    /** Verdadero si esa extensión es un formato de vídeo que la app reconoce. */
    public static boolean isVideoExtension(String extension) {
        String ext = extension == null ? "" : extension.trim().toLowerCase(Locale.US);
        if (ext.startsWith(".")) ext = ext.substring(1);
        for (int i = 0; i < VIDEO_EXTENSIONS.length; i++) {
            if (VIDEO_EXTENSIONS[i].equals(ext)) return true;
        }
        return false;
    }

    // ----------------------------------------------------------------- lectura

    /**
     * Lee la lista completa: comprueba la cuenta, descarga las categorías y las
     * películas, y devuelve los títulos listos para guardar.
     */
    public static Loaded load(Context context, String rawServer, String user, String pass)
            throws CatalogLoader.CatalogError {
        String server = normalizeServer(rawServer);
        String cleanUser = user == null ? "" : user.trim();
        String cleanPass = pass == null ? "" : pass.trim();
        if (cleanUser.length() == 0) {
            throw new CatalogLoader.CatalogError("Escribe el usuario de la lista Xtream.");
        }
        if (cleanPass.length() == 0) {
            throw new CatalogLoader.CatalogError("Escribe la contraseña de la lista Xtream.");
        }
        if (!Net.online(context)) {
            throw new CatalogLoader.CatalogError(
                    "Sin conexión a Internet. Se muestra el catálogo guardado de esta lista.");
        }

        // 1) La cuenta: así, si el usuario o la clave están mal, el mensaje lo dice
        //    en vez de enseñar una lista vacía sin explicación.
        String accountState = checkAccount(context, server, cleanUser, cleanPass);

        // 2) Las categorías (se usan como género de cada película).
        Map<String, String> categories = new LinkedHashMap<String, String>();
        try {
            String catText = CatalogLoader.fetchText(context,
                    apiUrl(server, cleanUser, cleanPass, "get_vod_categories"));
            List<Object> rows = Json.array(Json.parseQuietly(catText));
            for (int i = 0; i < rows.size(); i++) {
                Map<String, Object> row = Json.object(rows.get(i));
                String id = Json.clean(row.get("category_id"));
                String name = Json.clean(row.get("category_name"));
                if (id.length() > 0 && name.length() > 0) categories.put(id, name);
            }
        } catch (CatalogLoader.CatalogError ignored) {
            // Sin categorías se puede seguir: las películas van sin género.
        } catch (Throwable ignored) {
        }

        Loaded loaded = new Loaded();
        loaded.accountState = accountState;
        // El nombre se fija ANTES de crear los títulos: así cada película guarda de
        // qué lista viene ("Origen" en su ficha).
        loaded.name = "Xtream · " + CatalogStore.Repo.hostOf(server) + " · " + cleanUser;
        ArrayList<MovieItem> items = new ArrayList<MovieItem>();

        // 3) Las PELÍCULAS (VOD) de la lista.
        int skipped = 0;
        int hidden = 0;
        String movieProblem = "";
        List<Object> rows = new ArrayList<Object>();
        try {
            rows = fetchRows(context, server, cleanUser, cleanPass, "get_vod_streams");
            for (int i = 0; i < rows.size(); i++) {
                if (items.size() >= MAX_ITEMS) break;
                MovieItem item = toItem(rows.get(i), server, cleanUser, cleanPass, categories,
                        loaded);
                if (item == null) {
                    // toItem deja el motivo en las notas; se cuentan los descartes.
                    skipped++;
                    continue;
                }
                items.add(item);
            }
        } catch (CatalogLoader.CatalogError error) {
            movieProblem = error.getMessage() == null ? "" : error.getMessage();
        }
        int movieCount = items.size();

        // 4) Las SERIES de la lista, también por la API. De cada serie se guarda su
        //    ficha y la dirección con la que se pedirán sus temporadas y episodios
        //    al abrirla: así no se hacen cientos de consultas al cargar la lista.
        int seriesCount = 0;
        boolean seriesOverLimit = false;
        String seriesProblem = "";
        try {
            Map<String, String> seriesCategories = new LinkedHashMap<String, String>();
            try {
                seriesCategories = categories(context, server, cleanUser, cleanPass,
                        "get_series_categories");
            } catch (Throwable ignored) {
                // Sin categorías de series se puede seguir: quedan sin género.
            }
            List<Object> seriesRows = fetchRows(context, server, cleanUser, cleanPass,
                    "get_series");
            for (int i = 0; i < seriesRows.size(); i++) {
                if (seriesCount >= MAX_SERIES) {
                    seriesOverLimit = true;
                    break;
                }
                MovieItem item = toSeriesItem(seriesRows.get(i), server, cleanUser, cleanPass,
                        seriesCategories, loaded);
                if (item == null) continue;
                items.add(item);
                seriesCount++;
            }
        } catch (CatalogLoader.CatalogError error) {
            seriesProblem = error.getMessage() == null ? "" : error.getMessage();
        }
        hidden = loaded.hidden;

        if (items.isEmpty()) {
            StringBuilder message = new StringBuilder("La lista respondió, pero no trajo películas "
                    + "ni series. Comprueba que la cuenta tenga VOD activo en ese servidor.");
            if (movieProblem.length() > 0) message.append(' ').append(movieProblem);
            if (seriesProblem.length() > 0) message.append(' ').append(seriesProblem);
            if (hidden > 0) {
                message.append(' ').append(hidden).append(" títulos ocultos por adultos.");
            }
            if (rows.isEmpty() && movieProblem.length() == 0 && seriesProblem.length() == 0) {
                message.append(" La cuenta responde, pero no tiene contenido VOD.");
            }
            throw new CatalogLoader.CatalogError(message.toString());
        }

        loaded.count = items.size();
        loaded.movies = movieCount;
        loaded.series = seriesCount;
        loaded.skipped = skipped;
        loaded.itemsJson = CatalogLoader.serialize(items);
        if (hidden > 0) {
            loaded.notes.add(hidden + (hidden == 1 ? " título oculto" : " títulos ocultos")
                    + " por estar marcado como contenido para adultos.");
        }
        if (skipped > 0) {
            loaded.notes.add(skipped + (skipped == 1 ? " entrada descartada" : " entradas descartadas")
                    + " porque su formato no es de vídeo (o no traía enlace).");
        }
        if (rows.size() > movieCount + skipped) {
            loaded.notes.add("Se guardan las primeras " + movieCount + " películas.");
        }
        if (seriesOverLimit) {
            loaded.notes.add("Se guardan las primeras " + MAX_SERIES + " series.");
        }
        return loaded;
    }

    /**
     * Comprueba la cuenta contra el servidor y devuelve su estado («Active»…).
     * Si el usuario o la clave no sirven, lanza un error con el motivo escrito.
     */
    private static String checkAccount(Context context, String server, String user, String pass)
            throws CatalogLoader.CatalogError {
        String accountText = CatalogLoader.fetchText(context, apiUrl(server, user, pass, ""));
        Map<String, Object> account = Json.object(Json.parseQuietly(accountText));
        String accountState = "";
        if (account.isEmpty()) return "";
        Map<String, Object> info = Json.object(account.get("user_info"));
        if (!info.isEmpty()) {
            accountState = Json.clean(info.get("status"));
            String accountMessage = Json.clean(info.get("message"));
            String auth = Json.clean(info.get("auth"));
            boolean denied = "0".equals(auth) || "false".equalsIgnoreCase(auth);
            String status = accountState.toLowerCase(Locale.US);
            boolean finished = status.length() > 0 && !status.contains("active")
                    && !status.contains("trial") && !status.contains("activo");
            if (denied || finished) {
                throw new CatalogLoader.CatalogError(describeAccount(accountState, accountMessage));
            }
        } else {
            Map<String, Object> userInfo = Json.object(account.get("user"));
            if (!userInfo.isEmpty()) accountState = Json.clean(userInfo.get("status"));
        }
        return accountState;
    }

    /** Categorías de la lista (id → nombre) para usarlas como género. */
    private static Map<String, String> categories(Context context, String server, String user,
                                                  String pass, String action)
            throws CatalogLoader.CatalogError {
        Map<String, String> out = new LinkedHashMap<String, String>();
        String text = CatalogLoader.fetchText(context, apiUrl(server, user, pass, action));
        List<Object> rows = Json.array(Json.parseQuietly(text));
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> row = Json.object(rows.get(i));
            String id = Json.clean(row.get("category_id"));
            String name = Json.clean(row.get("category_name"));
            if (id.length() > 0 && name.length() > 0) out.put(id, name);
        }
        return out;
    }

    /** Pide una lista a la API (películas o series) y devuelve sus entradas. */
    private static List<Object> fetchRows(Context context, String server, String user,
                                          String pass, String action)
            throws CatalogLoader.CatalogError {
        String text = CatalogLoader.fetchText(context, apiUrl(server, user, pass, action));
        List<Object> rows = rowsOf(Json.parseQuietly(text));
        if (rows.isEmpty()) {
            boolean series = "get_series".equals(action);
            throw new CatalogLoader.CatalogError("El servidor no devolvió la lista de "
                    + (series ? "series" : "películas") + " de esa cuenta.");
        }
        return rows;
    }

    /** Cuenta las entradas de una lista de la API sin construir el catálogo. */
    private static int countRows(Context context, String server, String user, String pass,
                                 String action) throws CatalogLoader.CatalogError {
        String text = CatalogLoader.fetchText(context, apiUrl(server, user, pass, action));
        return rowsOf(Json.parseQuietly(text)).size();
    }

    /**
     * COMPROBAR DIRECCIÓN de una lista Xtream: se pregunta a la API cuántas
     * películas y series tiene, SIN descargar la lista completa. Es rápido incluso
     * con listas enormes.
     */
    static RepoProbe.Result probe(Context context, Parsed parsed) {
        RepoProbe.Result result = new RepoProbe.Result();
        result.kind = RepoProbe.KIND_XTREAM;
        result.title = "Lista Xtream · " + CatalogStore.Repo.hostOf(parsed.server);
        // La dirección que se ofrece añadir es la Xtream completa: así la app la
        // guarda como lista Xtream y las siguientes lecturas también usan la API.
        result.address = fullUrl(parsed.server, parsed.user, parsed.pass);
        int movies = 0;
        int series = 0;
        String state = "";
        String problem = "";
        try {
            state = checkAccount(context, parsed.server, parsed.user, parsed.pass);
            movies = countRows(context, parsed.server, parsed.user, parsed.pass, "get_vod_streams");
            series = countRows(context, parsed.server, parsed.user, parsed.pass, "get_series");
        } catch (CatalogLoader.CatalogError error) {
            problem = error.getMessage() == null ? "" : error.getMessage();
        } catch (Throwable t) {
            problem = "No se pudo consultar la API de esa lista. " + Net.describe(t);
        }
        if (movies + series > 0) {
            result.usable = true;
            result.itemCount = movies + series;
            result.headline = "Compatible: lista Xtream con " + movies
                    + (movies == 1 ? " película" : " películas") + " y " + series
                    + (series == 1 ? " serie" : " series") + ".";
            result.lines.add("Se lee por la API de Xtream: solo sus películas y series, sin "
                    + "descargar la lista completa (que puede pesar muchos megas).");
            result.lines.add("Los episodios de cada serie se piden al servidor al abrir esa serie "
                    + "en PELÍCULAS.");
            result.lines.add("Los canales en directo no entran aquí: se ven en la pantalla de TV.");
        } else if (problem.length() > 0) {
            result.headline = "No se pudo leer esa lista Xtream.";
            result.lines.add(problem);
        } else {
            result.headline = "La cuenta responde, pero no tiene películas ni series.";
            result.lines.add("Es una lista solo de canales: esos se abren en la pantalla de TV.");
        }
        if (state.length() > 0) {
            result.lines.add("Estado de la cuenta en el servidor: " + state + ".");
        }
        return result;
    }

    /**
     * Pide al servidor las temporadas y los episodios de UNA serie. Se llama
     * cuando el usuario abre esa serie, no al cargar la lista.
     */
    public static ArrayList<MovieItem.Season> fetchEpisodes(Context context, String infoUrl)
            throws CatalogLoader.CatalogError {
        String url = infoUrl == null ? "" : infoUrl.trim();
        if (url.length() == 0) {
            throw new CatalogLoader.CatalogError("Esta serie no trae la dirección de sus episodios.");
        }
        if (context != null && !Net.online(context)) {
            throw new CatalogLoader.CatalogError("Sin conexión a Internet: los episodios se piden "
                    + "al servidor de tu lista.");
        }
        return parseEpisodes(url, CatalogLoader.fetchText(context, url));
    }

    /** Lee la respuesta de get_series_info y la convierte en temporadas. */
    static ArrayList<MovieItem.Season> parseEpisodes(String infoUrl, String text)
            throws CatalogLoader.CatalogError {
        Parsed parsed = parseFullUrl(infoUrl);
        if (parsed == null || !parsed.ok()) {
            throw new CatalogLoader.CatalogError(
                    "No se pudo leer la dirección de episodios de esa serie.");
        }
        Map<String, Object> map = Json.object(Json.parseQuietly(text));
        if (map.isEmpty()) {
            throw new CatalogLoader.CatalogError(
                    "El servidor no devolvió los episodios de esa serie.");
        }
        Map<String, Object> episodes = Json.object(map.get("episodes"));
        ArrayList<MovieItem.Season> seasons = new ArrayList<MovieItem.Season>();
        if (!episodes.isEmpty()) {
            for (Map.Entry<String, Object> entry : episodes.entrySet()) {
                int seasonNumber = number(entry.getKey(), 1);
                List<Object> rows = Json.array(entry.getValue());
                MovieItem.Season season = new MovieItem.Season(seasonNumber, "");
                for (int i = 0; i < rows.size() && i < 400; i++) {
                    MovieItem.Episode episode = toEpisode(rows.get(i), parsed, seasonNumber, i + 1);
                    if (episode != null) season.episodes.add(episode);
                }
                if (!season.episodes.isEmpty()) seasons.add(season);
            }
        }
        if (seasons.isEmpty()) {
            throw new CatalogLoader.CatalogError("Esa serie no tiene episodios en la lista.");
        }
        sortSeasons(seasons);
        return seasons;
    }

    /** Convierte una entrada de episodio de la API en un episodio de la app. */
    private static MovieItem.Episode toEpisode(Object entry, Parsed parsed, int seasonNumber,
                                               int fallbackNumber) {
        if (!(entry instanceof Map)) return null;
        Map<String, Object> row = Json.object(entry);
        String id = firstClean(row, new String[]{"id", "episode_id"});
        if (id.length() == 0) return null;
        String extension = firstClean(row, new String[]{"container_extension"});
        if (!isVideoExtension(extension)) extension = "mp4";
        int number = number(firstClean(row, new String[]{"episode_num", "episode"}), fallbackNumber);
        if (number <= 0) number = fallbackNumber;
        Map<String, Object> info = Json.object(row.get("info"));
        String title = firstClean(row, new String[]{"title", "name"});
        if (title.length() == 0) title = firstClean(info, new String[]{"name"});
        String synopsis = firstClean(info, new String[]{"plot", "overview", "description"});
        String still = firstClean(info, new String[]{"movie_image", "cover_big", "image"});
        MovieItem.Episode episode = new MovieItem.Episode(seasonNumber, number, title, synopsis,
                still);
        episode.sources.add(new MovieItem.Source(extension.toUpperCase(Locale.US),
                episodeUrl(parsed.server, parsed.user, parsed.pass, id, extension), extension));
        return episode;
    }

    /** Ordena temporadas y episodios por número. */
    private static void sortSeasons(ArrayList<MovieItem.Season> seasons) {
        for (int s = 1; s < seasons.size(); s++) {
            MovieItem.Season current = seasons.get(s);
            int j = s - 1;
            while (j >= 0 && seasons.get(j).number > current.number) {
                seasons.set(j + 1, seasons.get(j));
                j--;
            }
            seasons.set(j + 1, current);
        }
        for (int s = 0; s < seasons.size(); s++) {
            ArrayList<MovieItem.Episode> rows = seasons.get(s).episodes;
            for (int e = 1; e < rows.size(); e++) {
                MovieItem.Episode current = rows.get(e);
                int j = e - 1;
                while (j >= 0 && rows.get(j).number > current.number) {
                    rows.set(j + 1, rows.get(j));
                    j--;
                }
                rows.set(j + 1, current);
            }
        }
    }

    private static int number(String value, int fallback) {
        try {
            int parsed = Integer.parseInt(value == null ? "" : value.trim());
            return parsed > 0 ? parsed : fallback;
        } catch (Throwable t) {
            return fallback;
        }
    }

    private static String firstClean(Map<String, Object> map, String[] keys) {
        if (map == null || keys == null) return "";
        for (int i = 0; i < keys.length; i++) {
            String value = Json.clean(map.get(keys[i]));
            if (value.length() > 0) return value;
        }
        return "";
    }

    /** Texto de error cuando el servidor rechaza la cuenta. */
    private static String describeAccount(String status, String message) {
        String cleanStatus = status == null ? "" : status.trim();
        String lower = cleanStatus.toLowerCase(Locale.US);
        StringBuilder out = new StringBuilder();
        if (lower.contains("expired") || lower.contains("caduc")) {
            out.append("La cuenta de esa lista está caducada.");
        } else if (lower.contains("disabled") || lower.contains("banned")
                || lower.contains("desactiv")) {
            out.append("La cuenta de esa lista está desactivada en el servidor.");
        } else {
            out.append("El servidor no acepta ese usuario y contraseña.");
        }
        if (cleanStatus.length() > 0) out.append(" (estado: ").append(cleanStatus).append(")");
        if (message != null && message.length() > 0) out.append(' ').append(message);
        return out.toString();
    }

    /** Lista de entradas del JSON, tanto si viene como lista como envuelta. */
    private static List<Object> rowsOf(Object root) {
        List<Object> rows = Json.array(root);
        if (!rows.isEmpty()) return rows;
        Map<String, Object> map = Json.object(root);
        if (!map.isEmpty()) {
            String[] keys = {"movies", "vod", "data", "items", "available_channels"};
            for (int i = 0; i < keys.length; i++) {
                List<Object> inner = Json.array(map.get(keys[i]));
                if (!inner.isEmpty()) return inner;
            }
        }
        return new ArrayList<Object>();
    }

    /** Convierte una entrada de la lista en un título de la cuadrícula. */
    private static MovieItem toItem(Object entry, String server, String user, String pass,
                                    Map<String, String> categories, Loaded loaded) {
        if (!(entry instanceof Map)) {
            loaded.notes.add("Una entrada de la lista no tenía forma de ficha.");
            return null;
        }
        Map<String, Object> row = Json.object(entry);
        String title = Json.clean(row.get("name"));
        if (title.length() == 0) title = Json.clean(row.get("title"));
        if (title.length() == 0) return null;

        String adult = Json.clean(row.get("is_adult"));
        if ("1".equals(adult) || "true".equalsIgnoreCase(adult)) {
            loaded.hidden++;
            return null;
        }

        String streamId = Json.clean(row.get("stream_id"));
        if (streamId.length() == 0) streamId = Json.clean(row.get("id"));
        if (streamId.length() == 0) return null;

        String extension = Json.clean(row.get("container_extension"));
        if (!isVideoExtension(extension)) {
            // Si no viene la extensión, se intenta por la dirección directa.
            String direct = Json.clean(row.get("direct_source"));
            extension = extensionOf(direct);
            if (!isVideoExtension(extension)) return null;
        }

        MovieItem item = new MovieItem();
        item.title = title;
        item.kind = MovieItem.KIND_MOVIE;
        item.repoName = loaded.name == null || loaded.name.length() == 0
                ? "Lista Xtream" : loaded.name;
        item.poster = Json.clean(row.get("stream_icon"));
        if (item.poster.length() == 0) item.poster = Json.clean(row.get("cover"));
        item.synopsis = Json.clean(row.get("plot"));
        if (item.synopsis.length() == 0) item.synopsis = Json.clean(row.get("description"));

        String year = Json.clean(row.get("year"));
        if (year.length() == 0) year = Json.clean(row.get("releasedate"));
        if (year.length() > 4) year = year.substring(0, 4);
        item.year = year;

        double rating = Json.number(row.get("rating"));
        if (rating <= 0d) {
            double five = Json.number(row.get("rating_5based"));
            if (five > 0d) rating = five * 2d;
        }
        if (rating > 10d) rating = 10d;
        item.rating = rating;

        String categoryId = Json.clean(row.get("category_id"));
        String categoryName = categoryId.length() == 0 ? "" : categories.get(categoryId);
        if (categoryName != null && categoryName.length() > 0) item.genres.add(categoryName);

        String label = extension.toUpperCase(Locale.US);
        item.sources.add(new MovieItem.Source(label,
                movieUrl(server, user, pass, streamId, extension), extension));
        item.id = "xtream:" + streamId;
        return item;
    }

    /**
     * Convierte una entrada de get_series en una SERIE de la cuadrícula. No trae
     * episodios: solo su ficha y la dirección para pedirlos al abrirla.
     */
    private static MovieItem toSeriesItem(Object entry, String server, String user, String pass,
                                          Map<String, String> categories, Loaded loaded) {
        if (!(entry instanceof Map)) return null;
        Map<String, Object> row = Json.object(entry);
        String title = firstClean(row, new String[]{"name", "title"});
        if (title.length() == 0) return null;

        String adult = Json.clean(row.get("is_adult"));
        if ("1".equals(adult) || "true".equalsIgnoreCase(adult)) {
            loaded.hidden++;
            return null;
        }

        String seriesId = firstClean(row, new String[]{"series_id", "id"});
        if (seriesId.length() == 0) return null;

        MovieItem item = new MovieItem();
        item.title = title;
        item.kind = MovieItem.KIND_SERIES;
        item.repoName = loaded.name == null || loaded.name.length() == 0
                ? "Lista Xtream" : loaded.name;
        item.poster = firstClean(row, new String[]{"cover", "cover_big", "stream_icon",
                "movie_image"});
        item.synopsis = firstClean(row, new String[]{"plot", "description"});

        String year = firstClean(row, new String[]{"releaseDate", "release_date", "year"});
        if (year.length() > 4) year = year.substring(0, 4);
        item.year = year;

        double rating = Json.number(row.get("rating"));
        if (rating <= 0d) {
            double five = Json.number(row.get("rating_5based"));
            if (five > 0d) rating = five * 2d;
        }
        if (rating > 10d) rating = 10d;
        item.rating = rating;

        String categoryId = Json.clean(row.get("category_id"));
        String categoryName = categoryId.length() == 0 ? "" : categories.get(categoryId);
        if (categoryName != null && categoryName.length() > 0) item.genres.add(categoryName);

        // Se guarda con la serie la dirección con la que se pedirán sus temporadas
        // y episodios cuando el usuario la abra.
        item.resolveUrl = seriesInfoUrl(server, user, pass, seriesId);
        item.id = "xtream:s:" + seriesId;
        return item;
    }

    private static String extensionOf(String url) {
        String value = url == null ? "" : url.trim().toLowerCase(Locale.US);
        if (value.length() == 0) return "";
        int cut = value.indexOf('?');
        if (cut > 0) value = value.substring(0, cut);
        int dot = value.lastIndexOf('.');
        if (dot < 0 || dot + 1 >= value.length()) return "";
        return value.substring(dot + 1);
    }
}
