package com.pocketbuilder.app_wslenytvplayer1792356127271;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;

/**
 * Lee el ENLACE de un canal (no lo reproduce) para saber qué calidades ofrece de
 * verdad, cuando el menú CALIDAD no puede preguntárselo al motor DASH porque ese
 * canal no está pasando por él.
 *
 * Antes, un canal que no usaba el motor DASH solo podía mirar las pistas que
 * entrega el reproductor de Android, y casi siempre respondía «no ofrece varias
 * pistas de vídeo seleccionables» aunque el enlace sí tuviera varias calidades.
 * Ahora se lee el propio enlace:
 *
 *  · Manifiesto DASH (.mpd): se usa el lector de manifiestos de la app y se
 *    devuelve una entrada por cada Representación de VÍDEO que declara.
 *  · Lista maestra HLS (.m3u8 con #EXT-X-STREAM-INF): se devuelve una entrada por
 *    cada variante, con su resolución y su velocidad, y la dirección que hay que
 *    reproducir para verla.
 *
 * Es solo lectura: esta clase no descarga vídeo, no reproduce nada y no descifra
 * ninguna protección. Nunca lanza excepciones hacia la pantalla: si algo falla,
 * devuelve el motivo escrito para poder mostrarlo.
 */
final class StreamQuality {

    /** Primera entrada del menú CALIDAD: la app elige sola la mejor calidad. */
    static final String AUTOMATIC_LABEL = "Automática (recomendada)";

    /** Una calidad real del enlace. */
    static final class Option {
        /** Texto que ve la persona (por ejemplo «720p · 2500 kbps · H.264»). */
        final String label;
        /** Dirección que hay que reproducir para esta calidad ("" = la del canal). */
        final String url;
        /** Índice de la Representación dentro del manifiesto DASH (-1 si no es DASH). */
        final int dashIndex;
        /** true si la calidad viene de un manifiesto DASH. */
        final boolean dash;

        Option(String label, String url, int dashIndex, boolean dash) {
            this.label = label;
            this.url = url == null ? "" : url;
            this.dashIndex = dashIndex;
            this.dash = dash;
        }
    }

    /** Lo que ofrece el enlace. */
    static final class Result {
        /** "DASH", "HLS" o "" cuando el enlace no es un manifiesto reconocible. */
        final String kind;
        /** Calidades encontradas (sin contar la opción automática). */
        final ArrayList<Option> options = new ArrayList<Option>();
        /** Explicación breve cuando no hay calidades que elegir. */
        String note = "";
        /** De dónde salió el enlace: la dirección final tras las redirecciones. */
        String sourceUrl = "";

        Result(String kind) {
            this.kind = kind == null ? "" : kind;
        }

        boolean multiple() {
            return options.size() >= 2;
        }

        String kindText() {
            if ("DASH".equals(kind)) return "manifiesto DASH";
            if ("HLS".equals(kind)) return "lista HLS";
            return "este enlace";
        }
    }

    private static final int MAX_BYTES = 3 * 1024 * 1024;
    private static final int MAX_HOPS = 5;

    private StreamQuality() {
    }

    /** Lee el enlace con las cabeceras del canal. Nunca lanza: explica el motivo. */
    static Result inspect(String channelUrl, Map<String, String> headers) {
        Result result = new Result("");
        if (channelUrl == null || channelUrl.trim().length() == 0) {
            result.note = "Este canal no tiene un enlace que se pueda leer.";
            return result;
        }
        if (!LocalProxy.isHttp(channelUrl)) {
            result.note = "Este canal no se abre por Internet (es un archivo del teléfono), así que el"
                    + " reproductor usa la única pista que contiene.";
            return result;
        }
        Response response;
        try {
            response = fetch(channelUrl, headers);
        } catch (Throwable t) {
            result.note = "No se pudo leer el enlace del canal para ver sus calidades: "
                    + explain(t) + ".\\n\\nSe sigue usando la calidad que elige el reproductor.";
            return result;
        }
        result.sourceUrl = response.url;
        String text = response.text == null ? "" : response.text;
        String head = text.trim();
        if (head.startsWith("#EXTM3U")) {
            Result hls = new Result("HLS");
            hls.sourceUrl = response.url;
            parseHls(text, response.url, hls);
            return hls;
        }
        if (looksLikeMpd(text)) {
            Result dash = new Result("DASH");
            dash.sourceUrl = response.url;
            parseDash(text, response.url, dash);
            return dash;
        }
        result.note = "El enlace del canal no es una lista ni un manifiesto con varias calidades"
                + " (el servidor entrega una sola pista de vídeo), así que el reproductor usa la que hay.";
        return result;
    }

    // ------------------------------------------------------------------- DASH

    private static void parseDash(String text, String finalUrl, Result result) {
        Mpd manifest;
        try {
            manifest = Mpd.parse(text, finalUrl);
        } catch (Throwable t) {
            result.note = "El enlace parece un manifiesto DASH pero no se pudo interpretar: "
                    + explain(t) + ".";
            return;
        }
        ArrayList<Mpd.Rep> videos = manifest.videoReps();
        for (int i = 0; i < videos.size(); i++) {
            Mpd.Rep rep = videos.get(i);
            String label = rep.label();
            if (rep.protectedContent && rep.protectionSystem.length() > 0) {
                label = label + " · protegido (" + rep.protectionSystem + ")";
            }
            result.options.add(new Option(label, "", i, true));
        }
        if (result.options.isEmpty()) {
            result.note = "El manifiesto DASH no declara ninguna pista de vídeo, así que no hay"
                    + " resoluciones que elegir.";
        } else if (result.options.size() == 1) {
            result.note = "Este canal solo ofrece una resolución: " + result.options.get(0).label + ".";
        }
        Diag.add("CALIDAD · manifiesto leído para el menú: " + result.options.size()
                + " resolución(es) · " + manifest.summary());
    }

    // -------------------------------------------------------------------- HLS

    /**
     * Lee una lista HLS. Si es una lista MAESTRA (#EXT-X-STREAM-INF) devuelve cada
     * variante con su resolución y la dirección que hay que reproducir para verla.
     */
    private static void parseHls(String text, String finalUrl, Result result) {
        String[] lines = text.split("\n");
        int width = 0;
        int height = 0;
        int bandwidth = 0;
        String waitingName = "";
        boolean expecting = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i] == null ? "" : lines[i].trim();
            if (line.length() == 0) continue;
            if (line.startsWith("#EXT-X-STREAM-INF:")) {
                String attrs = line.substring("#EXT-X-STREAM-INF:".length());
                width = 0;
                height = 0;
                bandwidth = 0;
                waitingName = "";
                String resolution = attribute(attrs, "RESOLUTION");
                if (resolution.length() > 0) {
                    int x = resolution.toLowerCase(Locale.US).indexOf('x');
                    if (x > 0) {
                        width = number(resolution.substring(0, x));
                        height = number(resolution.substring(x + 1));
                    }
                }
                bandwidth = number(attribute(attrs, "BANDWIDTH"));
                if (bandwidth == 0) bandwidth = number(attribute(attrs, "AVERAGE-BANDWIDTH"));
                waitingName = attribute(attrs, "NAME");
                expecting = true;
                continue;
            }
            if (line.startsWith("#")) continue;
            if (!expecting) continue;
            expecting = false;
            String url = Mpd.resolve(finalUrl, line);
            StringBuilder label = new StringBuilder();
            if (height > 0) label.append(height).append('p');
            else if (width > 0) label.append(width).append(" px");
            else if (waitingName.length() > 0) label.append(waitingName);
            else label.append("Calidad ").append(result.options.size() + 1);
            if (bandwidth > 0) {
                if (label.length() > 0) label.append(" · ");
                label.append(Math.round(bandwidth / 1000f)).append(" kbps");
            }
            result.options.add(new Option(label.toString(), url, -1, false));
        }
        if (result.options.isEmpty()) {
            result.note = "Esta lista HLS es una sola secuencia de vídeo (no es una lista maestra con"
                    + " varias calidades): el servidor entrega una única calidad.";
        } else if (result.options.size() == 1) {
            result.note = "Este canal solo ofrece una calidad: " + result.options.get(0).label + ".";
        }
        Diag.add("CALIDAD · lista HLS leída para el menú: " + result.options.size() + " variante(s)");
    }

    /** Valor de un atributo de una línea #EXT-X-STREAM-INF. */
    private static String attribute(String attrs, String name) {
        if (attrs == null) return "";
        String upper = attrs.toUpperCase(Locale.US);
        String key = name.toUpperCase(Locale.US);
        int at = upper.indexOf(key + "=");
        if (at < 0) return "";
        int start = at + key.length() + 1;
        if (start >= attrs.length()) return "";
        if (attrs.charAt(start) == '"') {
            int end = attrs.indexOf('"', start + 1);
            return end > start ? attrs.substring(start + 1, end) : "";
        }
        int end = attrs.indexOf(',', start);
        return end > start ? attrs.substring(start, end).trim() : attrs.substring(start).trim();
    }

    private static int number(String text) {
        if (text == null) return 0;
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '0' && c <= '9') digits.append(c);
            else if (digits.length() > 0) break;
        }
        if (digits.length() == 0) return 0;
        try {
            return Integer.parseInt(digits.toString());
        } catch (Exception e) {
            return 0;
        }
    }

    private static boolean looksLikeMpd(String text) {
        if (text == null) return false;
        String low = text.toLowerCase(Locale.US);
        return low.indexOf("<mpd") >= 0;
    }

    // --------------------------------------------------------------- descarga

    private static final class Response {
        String url;
        String text;
    }

    private static Response fetch(String url, Map<String, String> headers) throws Exception {
        String current = url.trim();
        for (int hop = 0; hop < MAX_HOPS; hop++) {
            HttpURLConnection conn = (HttpURLConnection) new URL(current).openConnection();
            try {
                conn.setInstanceFollowRedirects(false);
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(20000);
                conn.setRequestProperty("Accept", "*/*");
                conn.setRequestProperty("Accept-Encoding", "identity");
                String userAgent = headers == null ? null : headers.get("User-Agent");
                conn.setRequestProperty("User-Agent",
                        userAgent == null || userAgent.length() == 0 ? Net.PLAYER_UA : userAgent);
                if (headers != null) {
                    for (Map.Entry<String, String> entry : headers.entrySet()) {
                        if (entry == null) continue;
                        String name = entry.getKey() == null ? "" : entry.getKey().trim();
                        String value = entry.getValue();
                        if (name.length() == 0 || value == null || value.length() == 0) continue;
                        if ("user-agent".equalsIgnoreCase(name)) continue;
                        try {
                            conn.setRequestProperty(name, value);
                        } catch (Throwable ignored) {
                            // Una cabecera con nombre inválido no debe impedir la lectura.
                        }
                    }
                }
                int code = conn.getResponseCode();
                if (code >= 300 && code < 400) {
                    String location = conn.getHeaderField("Location");
                    if (location == null || location.length() == 0) {
                        throw new MediaErrors.Http(code, "el servidor redirigió sin indicar a dónde");
                    }
                    current = Mpd.resolve(current, location);
                    continue;
                }
                if (code < 200 || code >= 400) {
                    throw new MediaErrors.Http(code, "el servidor respondió con el código " + code);
                }
                Response response = new Response();
                response.url = current;
                response.text = readAll(conn);
                return response;
            } finally {
                conn.disconnect();
            }
        }
        throw new MediaErrors.Http(0, "el enlace da demasiadas vueltas (demasiadas redirecciones)");
    }

    private static String readAll(HttpURLConnection conn) throws Exception {
        InputStream in = conn.getInputStream();
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[16384];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
                if (out.size() > MAX_BYTES) break;
            }
            return new String(out.toByteArray(), "UTF-8");
        } finally {
            try {
                in.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /** Motivo legible de un fallo de lectura. */
    private static String explain(Throwable t) {
        if (t instanceof MediaErrors.Http) {
            MediaErrors.Http http = (MediaErrors.Http) t;
            return "el servidor respondió con el código " + http.code + " ("
                    + MediaErrors.httpReason(http.code) + ")";
        }
        String message = t == null ? null : t.getMessage();
        if (message == null || message.length() == 0) {
            return t == null ? "error desconocido" : t.getClass().getSimpleName();
        }
        return message;
    }
}
