package com.pocketbuilder.app_wslenytvplayer1792356127271;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LECTOR DE LISTAS M3U/M3U8 PARA EL APARTADO DE PELÍCULAS.
 *
 * Una lista de IPTV (por ejemplo la que devuelve
 * {@code http://servidor/get.php?username=USUARIO&password=CLAVE&type=m3u_plus&output=ts})
 * trae de todo: canales de televisión en directo, películas y series. Esta clase
 * la lee y se queda SOLO con lo que se puede ver en PELÍCULAS:
 *
 *   - Películas (VOD): enlaces .mkv, .mp4, .avi, .mov, .webm, .wmv, .flv, .mpg…
 *   - Series: agrupadas por título, con sus temporadas y episodios.
 *
 * Los canales en directo se descartan a propósito: esos van en la pantalla de TV
 * de la app, no en el catálogo de películas.
 *
 * CÓMO SE DECIDE QUÉ ES CADA COSA
 * -------------------------------
 * 1. Por la dirección (lo más fiable en las listas de Xtream y similares):
 *      .../live/...    → canal en directo
 *      .../movie/...   → película
 *      .../series/...  → serie
 * 2. Si la dirección no lo dice, por el grupo de la entrada
 *    («PELÍCULAS», «VOD», «SERIES», «DEPORTES»…).
 * 3. Si tampoco, por la extensión del archivo: .mkv y compañía son VOD; .m3u8,
 *    .mpd o una dirección sin extensión se toman como canal en directo.
 *
 * No se inventa nada: lo que no se reconoce se descarta y se cuenta, para poder
 * explicarlo en pantalla. Tampoco se descarga ni se ejecuta contenido: solo se
 * leen los enlaces que la lista ya trae.
 */
final class M3uVod {

    /** Tope de títulos (películas + series) que se guardan de una sola lista. */
    private static final int MAX_ITEMS = CatalogLoader.MAX_ITEMS;

    /** Marcas de grupo con contenido para adultos: se ocultan. */
    private static final String[] ADULT_HINTS = {"adult", "xxx", "porno", "porn", "erotic",
            "erótic", "erotic", "+18", "18+", "solo adultos"};

    /** Marcas de grupo de televisión en directo. */
    private static final String[] LIVE_HINTS = {"canal", "canales", "channel", "live", "directo",
            "deportes", "deporte", "sport", "noticias", "news", "24/7", "radio", "evento", "ppv",
            "infantil", "documental"};

    /** Marcas de grupo de películas. */
    private static final String[] MOVIE_HINTS = {"peli", "película", "pelicula", "movie", "film",
            "cine", "vod", "estreno", "estrenos"};

    /** Marcas de grupo de series. */
    private static final String[] SERIES_HINTS = {"serie", "series", "temporada", "season",
            "novela", "anime", "animación", "animacion"};

    private static final String KIND_LIVE = "live";
    private static final String KIND_SKIP = "skip";

    /** Patrones de «S01 E02», «1x02», «Temporada 1 Episodio 2»… */
    private static final Pattern[] EPISODE_PATTERNS = {
            Pattern.compile("(?iu)\\bS\\s*(\\d{1,2})\\s*[-._ ]?\\s*E\\s*(\\d{1,3})\\b"),
            Pattern.compile("(?iu)\\bT\\s*(\\d{1,2})\\s*[-._ ]?\\s*E\\s*(\\d{1,3})\\b"),
            Pattern.compile("(?iu)\\b(\\d{1,2})\\s*[xX]\\s*(\\d{1,3})\\b"),
            Pattern.compile("(?iu)\\btemporada\\s*(\\d{1,2})\\s*(?:episodio|cap[ií]tulo|ep)\\s*"
                    + "(\\d{1,3})\\b"),
            Pattern.compile("(?iu)\\bseason\\s*(\\d{1,2})\\s*episode\\s*(\\d{1,3})\\b")
    };

    private static final Pattern YEAR_PATTERN = Pattern.compile("\\((19|20)(\\d{2})\\)");

    private M3uVod() {
    }

    /** Verdadero si el texto descargado es una lista M3U/M3U8. */
    static boolean looksLikeM3u(String text) {
        if (text == null || text.length() == 0) return false;
        int limit = Math.min(text.length(), 4000);
        String head = text.substring(0, limit).toLowerCase(Locale.US);
        return head.indexOf("#extm3u") >= 0 || head.indexOf("#extinf") >= 0;
    }

    /** Un episodio reconocido dentro del nombre de una entrada. */
    private static final class EpisodeInfo {
        int season;
        int episode;
        String base;
    }

    /** Resultado de leer una lista, con el informe de lo que se ha encontrado. */
    static final class Report {
        String name = "";
        final ArrayList<MovieItem> items = new ArrayList<MovieItem>();
        int entries = 0;
        int movies = 0;
        int series = 0;
        int episodes = 0;
        int liveSkipped = 0;
        int adultSkipped = 0;
        int unsupported = 0;
        int overLimit = 0;
        final HashSet<String> seenUrls = new HashSet<String>();
        final HashSet<String> seenEpisodes = new HashSet<String>();
        final Map<String, MovieItem> seriesByTitle = new LinkedHashMap<String, MovieItem>();

        /** Línea corta con lo que se ha guardado, para enseñarla en la app. */
        String note() {
            StringBuilder out = new StringBuilder();
            out.append(movies).append(movies == 1 ? " película" : " películas");
            out.append(" · ").append(series).append(series == 1 ? " serie" : " series");
            if (episodes > 0) {
                out.append(" (").append(episodes)
                        .append(episodes == 1 ? " episodio" : " episodios").append(')');
            }
            if (liveSkipped > 0) {
                out.append(" · ").append(liveSkipped)
                        .append(liveSkipped == 1 ? " canal en directo ignorado"
                                : " canales en directo ignorados");
            }
            if (adultSkipped > 0) {
                out.append(" · ").append(adultSkipped)
                        .append(adultSkipped == 1 ? " oculto para adultos"
                                : " ocultos para adultos");
            }
            if (unsupported > 0) {
                out.append(" · ").append(unsupported)
                        .append(unsupported == 1 ? " entrada sin vídeo" : " entradas sin vídeo");
            }
            if (overLimit > 0) {
                out.append(" · se guardan los primeros ").append(MAX_ITEMS).append(" títulos");
            }
            return out.toString();
        }

        /** Explicación cuando la lista no tiene nada que llevar a PELÍCULAS. */
        String emptyMessage() {
            StringBuilder out = new StringBuilder(
                    "Esa lista no tiene películas ni series que se puedan añadir.");
            if (liveSkipped > 0) {
                out.append(" Se han leído ").append(liveSkipped)
                        .append(liveSkipped == 1 ? " canal en directo" : " canales en directo")
                        .append("; los canales van en la pantalla de TV, no en películas.");
            }
            if (unsupported > 0) {
                out.append(" ").append(unsupported)
                        .append(unsupported == 1 ? " entrada no tenía" : " entradas no tenían")
                        .append(" un formato de vídeo reconocible.");
            }
            if (adultSkipped > 0) {
                out.append(" Se han ocultado ").append(adultSkipped)
                        .append(" entradas marcadas como contenido para adultos.");
            }
            return out.toString();
        }
    }

    // ------------------------------------------------------------------ lectura

    /** Lee la lista entera. No usa Internet ni abre los enlaces: solo el texto. */
    static Report parse(String text, String fallbackName) {
        Report report = new Report();
        report.name = cleanName(fallbackName);
        if (text == null || text.length() == 0) return report;

        String pendingTitle = "";
        String pendingName = "";
        String pendingLogo = "";
        String pendingGroup = "";

        int length = text.length();
        int start = 0;
        while (start < length) {
            int end = text.indexOf('\n', start);
            if (end < 0) end = length;
            String line = text.substring(start, end).trim();
            start = end + 1;
            if (line.length() == 0) continue;

            if (line.charAt(0) == '#') {
                String upper = line.toUpperCase(Locale.US);
                if (upper.startsWith("#EXTINF")) {
                    pendingTitle = displayName(line);
                    pendingName = attribute(line, "tvg-name");
                    pendingLogo = attribute(line, "tvg-logo");
                    String group = attribute(line, "group-title");
                    if (group.length() > 0) pendingGroup = group;
                    continue;
                }
                if (upper.startsWith("#EXTGRP:")) {
                    String group = line.length() > 8 ? line.substring(8).trim() : "";
                    if (group.length() > 0) pendingGroup = group;
                    continue;
                }
                if (upper.startsWith("#PLAYLIST")) {
                    int colon = line.indexOf(':');
                    String listName = colon >= 0 ? cleanName(line.substring(colon + 1)) : "";
                    if (listName.length() > 0 && report.name.length() == 0) report.name = listName;
                    continue;
                }
                // #EXTVLCOPT, #KODIPROP, comentarios… no aportan datos al catálogo.
                continue;
            }

            String url = line;
            String title = cleanName(pendingTitle);
            if (title.length() == 0) title = cleanName(pendingName);
            addEntry(report, url, title, cleanName(pendingName), cleanName(pendingLogo),
                    pendingGroup);
            pendingTitle = "";
            pendingName = "";
            pendingLogo = "";
            // El grupo se conserva: muchas listas lo escriben una vez y luego
            // ponen muchas entradas seguidas.
        }

        finishSeries(report);
        return report;
    }

    /** Añade una entrada de la lista si de verdad es una película o una serie. */
    private static void addEntry(Report report, String url, String title, String tvgName,
                                 String logo, String group) {
        if (url == null) return;
        String address = url.trim();
        String lower = address.toLowerCase(Locale.US);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            // rtmp/udp/rtsp y demás no los abre el reproductor del módulo.
            if (lower.startsWith("rtmp") || lower.startsWith("udp") || lower.startsWith("rtsp")
                    || lower.startsWith("mms")) {
                report.unsupported++;
            }
            return;
        }

        String groupText = cleanName(group);
        String groupLower = groupText.toLowerCase(Locale.US);
        if (containsAny(groupLower, ADULT_HINTS)) {
            report.adultSkipped++;
            return;
        }
        if (lower.indexOf("/adult") >= 0 || lower.indexOf("/xxx") >= 0) {
            report.adultSkipped++;
            return;
        }
        report.entries++;

        String kind = classify(lower, groupLower, (tvgName + " " + title).toLowerCase(Locale.US));
        if (KIND_LIVE.equals(kind)) {
            report.liveSkipped++;
            return;
        }
        if (KIND_SKIP.equals(kind)) {
            report.unsupported++;
            return;
        }

        String name = tvgName.length() > 0 ? tvgName : title;
        if (name.length() == 0) {
            report.unsupported++;
            return;
        }

        if (MovieItem.KIND_SERIES.equals(kind)) {
            addSeriesEntry(report, address, name, logo, groupText);
        } else {
            addMovieEntry(report, address, name, logo, groupText);
        }
    }

    /** Decide qué es una entrada: película, serie, canal en directo o descartar. */
    private static String classify(String lowerUrl, String groupLower, String nameLower) {
        String path = lowerUrl;
        int query = path.indexOf('?');
        if (query >= 0) path = path.substring(0, query);

        // 1) Lo que dice la propia dirección.
        if (path.indexOf("/live/") >= 0) return KIND_LIVE;
        if (path.indexOf("/movie/") >= 0 || path.indexOf("/movies/") >= 0
                || path.indexOf("/vod/") >= 0 || path.indexOf("/peliculas/") >= 0
                || path.indexOf("/películas/") >= 0 || path.indexOf("/films/") >= 0) {
            return MovieItem.KIND_MOVIE;
        }
        if (path.indexOf("/series/") >= 0 || path.indexOf("/serie/") >= 0
                || path.indexOf("/show/") >= 0) {
            return MovieItem.KIND_SERIES;
        }

        // 2) Lo que dice el grupo de la lista.
        boolean movieGroup = containsAny(groupLower, MOVIE_HINTS);
        boolean seriesGroup = containsAny(groupLower, SERIES_HINTS);
        boolean liveGroup = containsAny(groupLower, LIVE_HINTS);
        if (movieGroup && !seriesGroup) return MovieItem.KIND_MOVIE;
        if (seriesGroup && !movieGroup) return MovieItem.KIND_SERIES;
        if (liveGroup) return KIND_LIVE;

        // 3) Lo que dice la extensión del archivo.
        String extension = extensionOf(path);
        if (extension.length() == 0) return KIND_LIVE;
        if ("m3u8".equals(extension) || "mpd".equals(extension)) return KIND_LIVE;
        if ("ts".equals(extension) || "m2ts".equals(extension)) return KIND_LIVE;
        if (XtreamClient.isVideoExtension(extension)) {
            // Un .mkv con nombre de episodio es una serie aunque su grupo no lo diga.
            if (seasonEpisode(nameLower) != null) return MovieItem.KIND_SERIES;
            return MovieItem.KIND_MOVIE;
        }
        return KIND_SKIP;
    }

    private static void addMovieEntry(Report report, String url, String name, String logo,
                                      String group) {
        if (!report.seenUrls.add(url)) return;
        if (report.items.size() >= MAX_ITEMS) {
            report.overLimit++;
            return;
        }
        MovieItem item = new MovieItem();
        item.year = yearOf(name);
        item.title = stripYear(name);
        if (item.title.length() == 0) item.title = name;
        item.kind = MovieItem.KIND_MOVIE;
        item.repoName = report.name;
        item.poster = logo;
        if (group.length() > 0) item.genres.add(prettyGroup(group));
        String extension = extensionOf(url);
        item.sources.add(new MovieItem.Source(
                extension.length() > 0 ? extension.toUpperCase(Locale.US) : "Enlace", url,
                extension));
        item.id = "m3u:" + Integer.toHexString(url.hashCode());
        report.items.add(item);
        report.movies++;
    }

    private static void addSeriesEntry(Report report, String url, String name, String logo,
                                       String group) {
        EpisodeInfo info = seasonEpisode(name);
        String base = info == null ? name : cleanName(info.base);
        if (base.length() == 0) base = name;
        String key = base.toLowerCase(Locale.US);
        MovieItem series = report.seriesByTitle.get(key);
        if (series == null) {
            if (report.items.size() >= MAX_ITEMS) {
                report.overLimit++;
                return;
            }
            series = new MovieItem();
            series.year = yearOf(base);
            series.title = stripYear(base);
            if (series.title.length() == 0) series.title = base;
            series.kind = MovieItem.KIND_SERIES;
            series.repoName = report.name;
            series.poster = logo;
            if (group.length() > 0) series.genres.add(prettyGroup(group));
            series.id = "m3u:s:" + Integer.toHexString(key.hashCode());
            report.seriesByTitle.put(key, series);
            report.items.add(series);
            report.series++;
        } else if (series.poster.length() == 0 && logo.length() > 0) {
            series.poster = logo;
        }

        int season = info == null ? 1 : info.season;
        int episode = info == null ? nextEpisodeNumber(series, season) : info.episode;
        if (!report.seenEpisodes.add(key + "#" + season + "#" + episode)) return;

        MovieItem.Season target = null;
        for (int i = 0; i < series.seasons.size(); i++) {
            MovieItem.Season season2 = series.seasons.get(i);
            if (season2 != null && season2.number == season) {
                target = season2;
                break;
            }
        }
        if (target == null) {
            target = new MovieItem.Season(season, "");
            series.seasons.add(target);
        }
        String extension = extensionOf(url);
        MovieItem.Episode row = new MovieItem.Episode(season, episode, "", "", logo);
        row.sources.add(new MovieItem.Source(
                extension.length() > 0 ? extension.toUpperCase(Locale.US) : "Enlace", url,
                extension));
        target.episodes.add(row);
        report.episodes++;
    }

    private static int nextEpisodeNumber(MovieItem series, int season) {
        int highest = 0;
        for (int i = 0; i < series.seasons.size(); i++) {
            MovieItem.Season row = series.seasons.get(i);
            if (row == null || row.number != season) continue;
            if (!row.episodes.isEmpty()) highest = row.episodes.get(row.episodes.size() - 1).number;
        }
        return highest + 1;
    }

    /** Ordena temporadas y episodios por número, sin tocar el resto. */
    private static void finishSeries(Report report) {
        for (int i = 0; i < report.items.size(); i++) {
            MovieItem item = report.items.get(i);
            if (item == null || !MovieItem.KIND_SERIES.equals(item.kind)) continue;
            for (int s = 1; s < item.seasons.size(); s++) {
                MovieItem.Season current = item.seasons.get(s);
                int j = s - 1;
                while (j >= 0 && item.seasons.get(j).number > current.number) {
                    item.seasons.set(j + 1, item.seasons.get(j));
                    j--;
                }
                item.seasons.set(j + 1, current);
            }
            for (int s = 0; s < item.seasons.size(); s++) {
                ArrayList<MovieItem.Episode> rows = item.seasons.get(s).episodes;
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
    }

    // ------------------------------------------------------------------- ayudas

    /** Reconoce «S01 E02», «1x02», «Temporada 3 Episodio 7»… dentro del nombre. */
    private static EpisodeInfo seasonEpisode(String name) {
        if (name == null || name.length() == 0) return null;
        for (int i = 0; i < EPISODE_PATTERNS.length; i++) {
            Matcher matcher = EPISODE_PATTERNS[i].matcher(name);
            if (!matcher.find()) continue;
            try {
                EpisodeInfo info = new EpisodeInfo();
                info.season = Integer.parseInt(matcher.group(1));
                info.episode = Integer.parseInt(matcher.group(2));
                if (info.season <= 0 || info.season > 99) continue;
                if (info.episode <= 0 || info.episode > 999) continue;
                info.base = name.substring(0, matcher.start());
                return info;
            } catch (Throwable ignored) {
                // Si el número no se puede leer se prueba con el siguiente patrón.
            }
        }
        return null;
    }

    /** Año entre paréntesis del nombre, o vacío. */
    private static String yearOf(String name) {
        if (name == null) return "";
        Matcher matcher = YEAR_PATTERN.matcher(name);
        if (!matcher.find()) return "";
        return matcher.group(1) + matcher.group(2);
    }

    /** Quita el «(2021)» del nombre: el año ya se enseña aparte. */
    private static String stripYear(String name) {
        String value = name == null ? "" : name;
        String cleaned = YEAR_PATTERN.matcher(value).replaceAll(" ").trim();
        cleaned = cleanName(cleaned);
        return cleaned.length() == 0 ? cleanName(value) : cleaned;
    }

    /** Extensión del archivo (sin parámetros y en minúsculas). */
    private static String extensionOf(String url) {
        String value = url == null ? "" : url.trim().toLowerCase(Locale.US);
        int cut = value.indexOf('?');
        if (cut >= 0) value = value.substring(0, cut);
        cut = value.indexOf('#');
        if (cut >= 0) value = value.substring(0, cut);
        int slash = value.lastIndexOf('/');
        String file = slash >= 0 ? value.substring(slash + 1) : value;
        int dot = file.lastIndexOf('.');
        if (dot < 0 || dot + 1 >= file.length()) return "";
        String extension = file.substring(dot + 1);
        return extension.length() <= 5 ? extension : "";
    }

    /** Nombre del grupo como categoría, sin cortar palabras raras. */
    private static String prettyGroup(String group) {
        String value = cleanName(group);
        if (value.length() > 26) {
            int bar = value.indexOf('|');
            if (bar > 0) value = cleanName(value.substring(0, bar));
        }
        if (value.length() > 26) value = value.substring(0, 26).trim();
        return value;
    }

    private static boolean containsAny(String text, String[] hints) {
        if (text == null || text.length() == 0) return false;
        for (int i = 0; i < hints.length; i++) {
            if (text.indexOf(hints[i]) >= 0) return true;
        }
        return false;
    }

    /** Texto delante de la primera coma que no esté dentro de comillas. */
    private static String displayName(String line) {
        int colon = line.indexOf(':');
        int from = colon >= 0 ? colon + 1 : 0;
        boolean quoted = false;
        for (int i = from; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                quoted = !quoted;
            } else if (c == ',' && !quoted) {
                return line.substring(i + 1).trim();
            }
        }
        return "";
    }

    /** Valor de un atributo de #EXTINF (tvg-name="…", group-title="…"). */
    private static String attribute(String line, String key) {
        if (line == null || key == null) return "";
        String lower = line.toLowerCase(Locale.US);
        String needle = key.toLowerCase(Locale.US) + "=";
        int from = 0;
        while (from < lower.length()) {
            int at = lower.indexOf(needle, from);
            if (at < 0) return "";
            if (at > 0) {
                char before = lower.charAt(at - 1);
                if (Character.isLetterOrDigit(before) || before == '-' || before == '_') {
                    from = at + needle.length();
                    continue;
                }
            }
            int valueStart = at + needle.length();
            if (valueStart >= line.length()) return "";
            char quote = line.charAt(valueStart);
            if (quote == '"' || quote == '\'') {
                int end = line.indexOf(quote, valueStart + 1);
                if (end < 0) return "";
                return line.substring(valueStart + 1, end).trim();
            }
            int end = valueStart;
            while (end < line.length() && line.charAt(end) != ' '
                    && line.charAt(end) != ',') {
                end++;
            }
            return line.substring(valueStart, end).trim();
        }
        return "";
    }

    /** Espacios limpios y sin adornos delante. */
    private static String cleanName(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.length() == 0) return "";
        StringBuilder out = new StringBuilder(value.length());
        boolean space = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\t' || c == '\n' || c == '\r') c = ' ';
            if (c == ' ') {
                if (!space) out.append(' ');
                space = true;
            } else {
                out.append(c);
                space = false;
            }
        }
        String result = out.toString().trim();
        while (result.length() > 0) {
            char first = result.charAt(0);
            if (first == '|' || first == '-' || first == '·' || first == ':' || first == '.') {
                result = result.substring(1).trim();
            } else {
                break;
            }
        }
        return result;
    }
}
