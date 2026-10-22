package com.pocketbuilder.app_wslenytvplayer1792356127271;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;

/**
 * Prueba un enlace DASH y devuelve un informe legible.
 *
 * Es la herramienta que dice POR QUÉ no se reproduce un canal: código HTTP real,
 * tipo de contenido, tamaño recibido, redirecciones, si el archivo es de verdad un
 * manifiesto .mpd (y de qué tipo: en directo o bajo demanda), cuántas pistas trae,
 * qué códecs usa y si lleva protección DRM. Se ejecuta a petición del usuario y
 * nunca lanza excepciones ni toca la reproducción en curso.
 */
final class DashProbe {

    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final int MAX_HOPS = 5;

    private DashProbe() {
    }

    /** Informe del enlace, listo para mostrar o copiar. */
    static String report(String url, Map<String, String> headers) {
        StringBuilder sb = new StringBuilder();
        if (url == null || url.trim().length() == 0) {
            return "No hay ningún enlace que probar.";
        }
        sb.append("ENLACE: ").append(Diag.channelUrl(url)).append('\n');
        sb.append("CABECERAS ENVIADAS: ").append(Net.describeHeaders(headers)).append('\n');
        ArrayList<String> trace = new ArrayList<String>();
        try {
            Response response = fetch(url, headers, trace);
            for (int i = 0; i < trace.size(); i++) sb.append("PASO: ").append(trace.get(i)).append('\n');
            sb.append("CÓDIGO HTTP: ").append(response.code).append('\n');
            sb.append("MOTIVO: ").append(MediaErrors.httpReason(response.code)).append('\n');
            sb.append("TIPO DE CONTENIDO: ")
                    .append(response.contentType == null ? "(el servidor no lo informa)" : response.contentType)
                    .append('\n');
            sb.append("TAMAÑO RECIBIDO: ").append(Net.readable(response.body.length)).append('\n');
            String text = new String(response.body, "UTF-8");
            if (Net.looksLikeHtml(text)) {
                sb.append("AVISO: el servidor devolvió una página web en lugar del manifiesto"
                        + " (puede pedir iniciar sesión, haber caducado el enlace o exigir cabeceras).\n");
            }
            try {
                Mpd mpd = Mpd.parse(text, response.url);
                sb.append("MANIFIESTO LEÍDO: ").append(mpd.summary()).append('\n');
                sb.append(mpd.dynamic
                        ? "Es un canal EN DIRECTO (el manifiesto cambia cada pocos segundos).\n"
                        : "Es contenido BAJO DEMANDA (duración fija).\n");
                ArrayList<Mpd.Rep> videos = mpd.videoReps();
                for (int i = 0; i < videos.size() && i < 8; i++) {
                    Mpd.Rep rep = videos.get(i);
                    sb.append("  CALIDAD ").append(i + 1).append(": ").append(rep.label())
                            .append(" · ").append(rep.describe()).append('\n');
                    sb.append("     decodificador de este teléfono: ").append(decoderLine(rep)).append('\n');
                }
                ArrayList<Mpd.Rep> audios = mpd.audioReps();
                for (int i = 0; i < audios.size() && i < 4; i++) {
                    Mpd.Rep rep = audios.get(i);
                    sb.append("  AUDIO ").append(i + 1).append(": ").append(rep.describe()).append('\n');
                    sb.append("     decodificador de este teléfono: ").append(decoderLine(rep)).append('\n');
                }
                if (videos.isEmpty()) {
                    sb.append("AVISO: el manifiesto no declara ninguna pista de vídeo; no hay imagen que mostrar.\n");
                } else {
                    Mpd.Rep first = videos.get(0);
                    if (first.timeline.isEmpty() && first.segDuration <= 0 && first.media.length() > 0) {
                        sb.append("AVISO: las pistas no indican la duración de los trozos ni su lista de"
                                + " tiempos; con ese manifiesto no se puede pedir cada trozo.\n");
                    }
                }
                if (mpd.hasProtection()) {
                    String system = mpd.protectionSystem();
                    sb.append("PROTECCIÓN DRM: ")
                            .append(system.length() == 0 ? "sistema no identificado" : system)
                            .append(". LENY TV no descifra contenido protegido: hace falta una licencia"
                                    + " autorizada del proveedor.\n");
                    if (mpd.licenseHost().length() > 0) {
                        sb.append("SERVIDOR DE LICENCIAS: ").append(mpd.licenseHost())
                                .append(" (la app no le pide ninguna licencia).\n");
                    }
                    if (mpd.hasPssh) {
                        sb.append("El manifiesto incluye datos de inicio de protección (pssh).\n");
                    }
                    sb.append(DrmInfo.summary()).append('\n');
                } else {
                    sb.append("PROTECCIÓN DRM: el manifiesto no declara ninguna.\n");
                }
                if (!videos.isEmpty()) {
                    sb.append(probeSegment(mpd, videos.get(0), headers));
                }
                sb.append("RESULTADO: el manifiesto se descarga y se entiende; la reproducción depende del"
                        + " códec del teléfono y de que los trozos respondan.\n");
            } catch (Throwable parseError) {
                sb.append("EL MANIFIESTO NO SE PUDO INTERPRETAR: ").append(Net.describe(parseError)).append('\n');
                sb.append("PRIMERAS LÍNEAS RECIBIDAS: ").append(Net.snippetOf(text)).append('\n');
                sb.append("Esto significa que el enlace no devuelve un manifiesto DASH válido.\n");
            }
        } catch (Throwable t) {
            for (int i = 0; i < trace.size(); i++) sb.append("PASO: ").append(trace.get(i)).append('\n');
            if (t instanceof MediaErrors.Http) {
                MediaErrors.Http http = (MediaErrors.Http) t;
                sb.append("CÓDIGO HTTP: ").append(http.code).append('\n');
                sb.append("MOTIVO: ").append(MediaErrors.httpReason(http.code)).append('\n');
                if (MediaErrors.looksLikeAuthorization(http.code)) {
                    sb.append("QUÉ HACER: escribe el User-Agent y el Referer que use tu proveedor en"
                            + " AJUSTES DEL REPRODUCTOR y vuelve a probar.\n");
                } else if (http.code == 404 || http.code == 410) {
                    sb.append("QUÉ HACER: el enlace ya no existe; pide a tu proveedor la dirección actual.\n");
                } else {
                    sb.append("QUÉ HACER: vuelve a probar en unos segundos (fallo del servidor).\n");
                }
            } else {
                sb.append("FALLO AL DESCARGAR: ").append(Net.describe(t)).append('\n');
                sb.append("QUÉ HACER: comprueba la conexión y que la dirección esté escrita"
                        + " exactamente como te la dio el proveedor.\n");
            }
        }
        return sb.toString();
    }

    /** Qué decodificador usaría este teléfono para esa pista del manifiesto. */
    private static String decoderLine(Mpd.Rep rep) {
        android.media.MediaFormat format = StreamCheck.formatFor(rep.mimeType, rep.codecs);
        String note = StreamCheck.decoderNote(format);
        return StreamCheck.canDecode(format) ? "sí · " + note : "NO · " + note;
    }

    /**
     * Comprueba el primer trozo que el reproductor pediría de la primera calidad:
     * código HTTP real, tipo de contenido, cuánto entrega el servidor y qué pinta
     * tiene el principio del archivo. Es lo que distingue "el manifiesto está bien
     * pero los trozos dan 403/404" de "el canal es accesible".
     */
    private static String probeSegment(Mpd mpd, Mpd.Rep rep, Map<String, String> headers) {
        StringBuilder sb = new StringBuilder();
        try {
            long[] first = Mpd.firstSegment(mpd, rep);
            if (first == null) {
                sb.append("TROZO DE PRUEBA: no se pudo calcular qué trozo pedir con este manifiesto.\n");
                return sb.toString();
            }
            String url = Mpd.segmentUrl(rep, first[0], first[1]);
            if (url == null || url.length() == 0) {
                sb.append("TROZO DE PRUEBA: el manifiesto no indica la dirección de sus trozos.\n");
                return sb.toString();
            }
            ArrayList<String> trace = new ArrayList<String>();
            Response response = fetch(url, headers, trace, 512 * 1024);
            sb.append("TROZO DE PRUEBA (").append(rep.label()).append("): trozo ").append(first[0])
                    .append(" · código HTTP ").append(response.code)
                    .append(" · ").append(response.contentType == null ? "(sin tipo)" : response.contentType)
                    .append(" · ").append(Net.readable(response.body.length)).append('\n');
            sb.append("CONTENIDO DEL TROZO: ").append(sniff(response.body)).append('\n');
            sb.append("Los trozos del canal responden con este resultado, y se piden con las cabeceras"
                    + " indicadas arriba.\n");
        } catch (Throwable t) {
            if (t instanceof MediaErrors.Http) {
                MediaErrors.Http http = (MediaErrors.Http) t;
                sb.append("TROZO DE PRUEBA: el servidor respondió ").append(http.code)
                        .append(" · ").append(MediaErrors.httpReason(http.code)).append('\n');
                if (MediaErrors.looksLikeAuthorization(http.code)) {
                    sb.append("QUÉ HACER: los trozos exigen autorización; escribe el User-Agent, el Referer"
                            + " (o la cabecera que pida el proveedor) en AJUSTES DEL REPRODUCTOR.\n");
                } else if (http.code == 404 || http.code == 410) {
                    sb.append("QUÉ HACER: los trozos de este manifiesto ya no existen en el servidor:"
                            + " puede ser un canal en directo que necesita que el teléfono vaya a la hora"
                            + " correcta, o un enlace caducado.\n");
                } else if (MediaErrors.retryable(http.code)) {
                    sb.append("QUÉ HACER: es un fallo temporal del servidor; vuelve a probar en unos segundos.\n");
                }
            } else {
                sb.append("TROZO DE PRUEBA: no se pudo pedir ningún trozo (")
                        .append(Net.describe(t)).append(").\n");
            }
        }
        return sb.toString();
    }

    /** Qué pinta tiene el principio del archivo recibido. */
    private static String sniff(byte[] body) {
        if (body == null || body.length < 8) return "el trozo llegó vacío o casi vacío";
        String head = new String(body, 0, 8);
        String low = head.toLowerCase(Locale.US);
        if (low.startsWith("<!doctype") || low.startsWith("<html")) {
            return "parece una página web, no un trozo de vídeo (el servidor devolvió otra cosa)";
        }
        if (low.indexOf("<?xml") >= 0 || low.indexOf("<mpd") >= 0) {
            return "parece otro manifiesto XML en lugar de un trozo de vídeo";
        }
        if (body[0] == 0x47) return "parece un trozo MPEG-TS (empieza por el byte de sincronía 0x47)";
        String box = new String(body, 4, 4);
        if ("ftyp".equals(box)) return "MP4 o trozo DASH válido (trae la cabecera ftyp)";
        if ("moof".equals(box)) return "trozo DASH válido (trae la caja moof de datos)";
        if ("styp".equals(box)) return "trozo DASH válido (trae la caja styp)";
        if ("moov".equals(box)) return "MP4 con su cabecera moov";
        if ("mdat".equals(box)) return "datos de vídeo (caja mdat)";
        return "datos binarios (no se reconoce la cabecera)";
    }

    private static final class Response {
        int code;
        String contentType;
        byte[] body;
        String url;
    }

    private static Response fetch(String url, Map<String, String> headers, ArrayList<String> trace)
            throws Exception {
        return fetch(url, headers, trace, MAX_BYTES);
    }

    /** Igual que fetch, pero sin descargar más de maxBytes (para probar un trozo del canal). */
    private static Response fetch(String url, Map<String, String> headers, ArrayList<String> trace,
                                  int maxBytes) throws Exception {
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
                        }
                    }
                }
                int code = conn.getResponseCode();
                String contentType = conn.getContentType();
                trace.add("código " + code + " · " + (contentType == null ? "(sin tipo)" : contentType)
                        + " · " + Diag.channelUrl(current));
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
                response.code = code;
                response.contentType = contentType;
                response.url = current;
                response.body = readAll(conn, maxBytes);
                return response;
            } finally {
                conn.disconnect();
            }
        }
        throw new MediaErrors.Http(0, "el enlace da demasiadas vueltas (demasiadas redirecciones)");
    }

    private static byte[] readAll(HttpURLConnection conn, int maxBytes) throws Exception {
        InputStream in = conn.getInputStream();
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[16384];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
                if (out.size() > maxBytes) break;
            }
            return out.toByteArray();
        } finally {
            try {
                in.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /** Tipo de contenido legible (se usa en el informe y en el registro). */
    static String describeType(String contentType) {
        String clean = MediaErrors.normalizeType(contentType);
        return clean.length() == 0 ? "(sin informar)" : clean.toLowerCase(Locale.US);
    }
}
