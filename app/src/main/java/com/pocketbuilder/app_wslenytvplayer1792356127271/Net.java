package com.pocketbuilder.app_wslenytvplayer1792356127271;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * Descargas de listas M3U/M3U8 con cabeceras HTTP (User-Agent y Referer).
 *
 * Muchos proveedores rechazan la petición si no se envían las cabeceras
 * correctas, y otros devuelven una redirección a otro servidor. Por eso:
 *
 *  - Las redirecciones se siguen a mano, incluso si saltan de http a https
 *    (cosa que la librería estándar de Android no hace sola).
 *  - Si el primer intento falla, se prueba con las cabeceras de un reproductor
 *    conocido (VLC), con y sin Referer, y después con las de un navegador.
 *  - Cada intento queda anotado con su código de respuesta, su tipo de
 *    contenido y su tamaño, para poder explicar exactamente qué pasó.
 *  - Una respuesta se acepta si parece una lista M3U: por sus etiquetas
 *    (#EXTM3U / #EXTINF / #EXT-X-) o porque contiene direcciones.
 */
final class Net {

    static final String DEFAULT_UA = "LenyTV/1.0 (Android)";
    /** Cabecera de reproductor que aceptan casi todos los proveedores IPTV. */
    static final String PLAYER_UA = "VLC/3.0.18 LibVLC/3.0.18";
    private static final String BROWSER_UA = "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private static final int CONNECT_TIMEOUT = 15000;
    private static final int READ_TIMEOUT = 25000;
    /** Espera corta para las comprobaciones que ocurren ANTES de reproducir. */
    private static final int QUICK_TIMEOUT = 8000;
    private static final int MAX_BYTES = 32 * 1024 * 1024;
    private static final int MAX_HOPS = 6;

    /** Resultado de intentar descargar una lista. */
    static final class LoadResult {
        String text;
        String error;
        String hint;
        String note;
        /** Cabeceras con las que por fin se pudo descargar (para reutilizarlas al reproducir). */
        String usedUserAgent;
        String usedReferer;
        /** Dirección real después de las redirecciones del servidor. */
        String finalUrl;
        /** Fragmento corto de lo recibido cuando no era una lista (ayuda a explicar el fallo). */
        String snippet;
        /** Paso a paso de lo que ocurrió con cada intento (código, tipo, tamaño...). */
        final ArrayList<String> attempts = new ArrayList<String>();
        /** Código HTTP del último intento realizado (-1 si ni se llegó a conectar). */
        int lastCode = -1;

        boolean ok() {
            return text != null && text.length() > 0;
        }
    }

    private Net() {
    }

    static boolean online(Context context) {
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return true;
            NetworkInfo info = cm.getActiveNetworkInfo();
            return info != null && info.isConnected();
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * Descarga una lista probando varios juegos de cabeceras.
     *
     * Una respuesta solo se acepta si de verdad parece una lista M3U: hay
     * proveedores que, con cabeceras equivocadas, contestan con un texto de error
     * o con una página web en lugar de con la lista.
     */
    static LoadResult loadList(String url, Map<String, String> configured) {
        LoadResult result = new LoadResult();
        if (url == null || url.trim().length() == 0) {
            result.error = "no hay ningún enlace que descargar";
            result.attempts.add("No hay ningún enlace que descargar.");
            return result;
        }
        url = url.trim();
        ArrayList<Map<String, String>> attempts = new ArrayList<Map<String, String>>();
        addAttempt(attempts, configured);
        addAttempt(attempts, playerHeaders(url));
        addAttempt(attempts, plainHeaders(url, PLAYER_UA));
        addAttempt(attempts, playerHeadersWithFullReferer(url));
        addAttempt(attempts, plainHeaders(url, BROWSER_UA));
        addAttempt(attempts, browserHeaders(url));

        String firstError = null;
        String htmlProblem = null;
        String wrongBody = null;
        String wrongSnippet = null;

        for (int i = 0; i < attempts.size(); i++) {
            Map<String, String> headers = attempts.get(i);
            ArrayList<String> trace = new ArrayList<String>();
            trace.add("Intento " + (i + 1) + " de " + attempts.size() + " con " + describeHeaders(headers));
            try {
                Response response = fetch(url, headers, "GET", trace);
                result.lastCode = response.code;
                trace.add("Respuesta: código " + response.code + " · tipo "
                        + (response.contentType == null ? "(sin informar)" : response.contentType)
                        + " · " + readable(response.body == null ? 0 : response.body.length));
                result.attempts.addAll(trace);
                String text = decode(response.body, response.contentType);
                if (text == null || text.length() == 0) {
                    if (firstError == null) firstError = "el servidor respondió con un archivo vacío";
                    continue;
                }
                if (looksLikeHtml(text)) {
                    if (htmlProblem == null) {
                        htmlProblem = "el servidor devolvió una página web en lugar de la lista"
                                + " (puede pedir iniciar sesión o haber cambiado el enlace)";
                        if (wrongSnippet == null) wrongSnippet = snippetOf(text);
                    }
                    continue;
                }
                boolean byTag = hasPlaylistTags(text);
                boolean byEntries = M3uParser.looksLikePlaylist(text);
                if (!byTag && !byEntries) {
                    if (wrongBody == null) {
                        wrongBody = "el servidor respondió algo que no es una lista M3U ("
                                + readable(response.body == null ? 0 : response.body.length) + ")";
                        wrongSnippet = snippetOf(text);
                    }
                    continue;
                }
                result.text = text;
                result.finalUrl = response.url;
                result.usedUserAgent = headers == null ? null : headers.get("User-Agent");
                result.usedReferer = headers == null ? null : headers.get("Referer");
                result.attempts.add("Aceptada como lista (" + (byTag ? "tiene etiquetas M3U" : "tiene direcciones")
                        + ", " + text.length() + " caracteres).");
                if (i > 0) {
                    result.note = "Se cargó con cabeceras de reproductor alternativas"
                            + (result.usedReferer == null || result.usedReferer.length() == 0
                            ? " (sin Referer)." : ".");
                }
                return result;
            } catch (IOException e) {
                result.attempts.addAll(trace);
                result.attempts.add("  fallo: " + describe(e));
                if (firstError == null) firstError = describe(e);
            } catch (Throwable t) {
                result.attempts.addAll(trace);
                result.attempts.add("  fallo: " + describe(t));
                if (firstError == null) firstError = describe(t);
            }
        }

        if (wrongBody != null || htmlProblem != null) {
            String problem = htmlProblem != null ? htmlProblem : wrongBody;
            result.error = firstError == null ? problem : problem + " · " + firstError;
            result.snippet = wrongSnippet;
            result.hint = "Comprueba el enlace o escríbelo otra vez desde el proveedor.";
        } else {
            result.error = firstError == null ? "no se pudo descargar la lista" : firstError;
            if (result.error.indexOf("403") >= 0 || result.error.indexOf("401") >= 0) {
                result.hint = "Tu proveedor pide cabeceras: escribe el User-Agent y el Referer en AJUSTES > LISTA.";
            }
        }
        return result;
    }

    /**
     * Prueba un enlace y devuelve un informe legible: código HTTP, tipo de
     * contenido, tamaño, redirecciones, primeros caracteres y qué entradas
     * reconoce el lector. Es la herramienta que dice si el fallo es de la app,
     * del servidor, de la URL o del formato.
     */
    static String probeReport(String url, Map<String, String> headers) {
        StringBuilder sb = new StringBuilder();
        if (url == null || url.trim().length() == 0) {
            return "Escribe primero un enlace para probar.";
        }
        String clean = url.trim();
        sb.append("ENLACE: ").append(clean).append('\n');
        Map<String, String> used = (headers == null || headers.isEmpty()) ? playerHeaders(clean) : headers;
        sb.append("CABECERAS: ").append(describeHeaders(used)).append('\n');
        ArrayList<String> trace = new ArrayList<String>();
        try {
            Response response = fetch(clean, used, "GET", trace);
            for (int i = 0; i < trace.size(); i++) sb.append(trace.get(i)).append('\n');
            sb.append("CÓDIGO HTTP: ").append(response.code).append('\n');
            sb.append("TIPO DE CONTENIDO: ")
                    .append(response.contentType == null ? "(el servidor no lo informa)" : response.contentType)
                    .append('\n');
            sb.append("TAMAÑO RECIBIDO: ").append(readable(response.body == null ? 0 : response.body.length)).append('\n');
            String text = decode(response.body, response.contentType);
            sb.append("TEXTO LEÍDO: ").append(text.length()).append(" caracteres\n");
            if (looksLikeHtml(text)) sb.append("AVISO: parece una página web, no una lista de texto.\n");
            M3uParser.Result parsed = M3uParser.parse(text, clean);
            sb.append("LECTURA DE LA LISTA: ").append(parsed.summary()).append('\n');
            for (int i = 0; i < parsed.notes.size(); i++) {
                sb.append("AVISO DEL LECTOR: ").append(parsed.notes.get(i)).append('\n');
            }
            if (parsed.channels.isEmpty()) {
                sb.append("MOTIVO: ").append(parsed.reason()).append('\n');
            }
            if (parsed.channels.size() > 0) {
                Channel first = parsed.channels.get(0);
                sb.append("PRIMER CANAL: ").append(first.name).append(" → ").append(first.url).append('\n');
                sb.append("Prueba a abrirlo tocándolo en la cuadrícula.\n");
            } else {
                sb.append("PRIMERAS LÍNEAS RECIBIDAS: ").append(snippetOf(text)).append('\n');
                if (parsed.mediaPlaylist) {
                    sb.append("Ese enlace es un VÍDEO HLS (.m3u8), no una lista de canales: "
                            + "usa el botón REPRODUCIR de la pantalla principal.\n");
                }
            }
        } catch (Throwable t) {
            for (int i = 0; i < trace.size(); i++) sb.append(trace.get(i)).append('\n');
            sb.append("FALLO AL DESCARGAR: ").append(describe(t)).append('\n');
            sb.append("Si el código era 401/403, el proveedor necesita tu User-Agent o tu Referer"
                    + " (AJUSTES > LISTA). Si fue 404, la dirección ya no existe. Si fue un fallo de"
                    + " conexión, revisa la escritura del enlace y tu internet.\n");
        }
        return sb.toString();
    }

    /** Fragmento corto y sin saltos de línea de lo recibido, para poder explicar el fallo. */
    static String snippetOf(String text) {
        if (text == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length() && sb.length() < 90; i++) {
            char c = text.charAt(i);
            sb.append(c < ' ' ? ' ' : c);
        }
        return sb.toString().trim();
    }

    /** Tamaño en unidades legibles. */
    static String readable(int bytes) {
        if (bytes < 1024) return bytes + " bytes";
        if (bytes < 1024 * 1024) return Math.round(bytes / 1024f) + " KB";
        return Math.round(bytes / (1024f * 1024f)) + " MB";
    }

    /**
     * Nombre de las cabeceras que se van a enviar, sin datos sensibles completos.
     * Se apoya en {@link Headers}, que oculta cookies, tokens y claves.
     */
    static String describeHeaders(Map<String, String> headers) {
        return Headers.describe(headers);
    }

    /**
     * Resultado de mirar qué tipo de contenido sirve un enlace, sin descargarlo.
     * Sirve para reconocer un manifiesto DASH aunque la dirección no termine en
     * ".mpd": manda lo que informa el servidor (y, si no lo informa, el propio
     * contenido del archivo).
     */
    static final class Sniff {
        /** Tipo según {@link FormatDetector} (DASH, HLS, TS, MP4, WEBM u OTHER). */
        String type = FormatDetector.OTHER;
        String contentType = "";
        /** De dónde salió la decisión, para el diagnóstico. */
        String how = "";
        /** Código HTTP y detalles de la comprobación. */
        String detail = "";
        boolean reached;
    }

    /**
     * Averigua el tipo real del enlace usando el tipo de contenido del servidor y,
     * si no basta, los primeros bytes del archivo (un manifiesto DASH empieza por
     * "&lt;MPD"). Nunca lanza excepción: si no se puede determinar, devuelve
     * "Otro" y la app sigue con el camino seguro de siempre.
     */
    static Sniff sniffType(String url, Map<String, String> headers) {
        Sniff sniff = new Sniff();
        if (url == null || url.trim().length() == 0) {
            sniff.detail = "no hay enlace que comprobar";
            return sniff;
        }
        String clean = url.trim();
        String low = clean.toLowerCase(Locale.US);
        if (!low.startsWith("http://") && !low.startsWith("https://")) {
            sniff.detail = "el enlace no es HTTP ni HTTPS";
            return sniff;
        }
        Map<String, String> used = (headers == null || headers.isEmpty())
                ? playerHeaders(clean) : headers;
        String contentType = "";
        int code = -1;
        ArrayList<String> trace = new ArrayList<String>();
        try {
            Response head = quickHead(clean, used, trace);
            code = head.code;
            contentType = head.contentType == null ? "" : head.contentType;
        } catch (Throwable t) {
            trace.add("no se pudo preguntar con HEAD: " + describe(t));
        }
        String byMime = FormatDetector.fromContentType(contentType);
        sniff.contentType = contentType;
        if (!FormatDetector.OTHER.equals(byMime)) {
            sniff.type = byMime;
            sniff.reached = true;
            sniff.how = "el servidor informa el tipo de contenido";
            sniff.detail = "código HTTP " + code + " · tipo " + contentType;
            return sniff;
        }
        // El servidor no dice nada útil (o informa un tipo genérico): se miran los
        // primeros bytes del archivo para reconocer un manifiesto DASH o una lista
        // HLS. Se piden solo unos pocos bytes y se cierra la conexión.
        String headText = "";
        try {
            headText = readHeadText(clean, used, 1500);
            sniff.reached = true;
        } catch (Throwable t) {
            trace.add("no se pudieron leer los primeros bytes: " + describe(t));
        }
        String upper = headText == null ? "" : headText.toUpperCase(Locale.US);
        if (upper.indexOf("<MPD") >= 0 || upper.indexOf(":MPD") >= 0) {
            sniff.type = FormatDetector.DASH;
            sniff.how = "el archivo empieza por un manifiesto <MPD>";
        } else if (upper.indexOf("#EXTM3U") >= 0) {
            sniff.type = FormatDetector.HLS;
            sniff.how = "el archivo empieza por una lista #EXTM3U";
        } else if (upper.indexOf("FTYP") >= 0 && upper.indexOf("MP4") >= 0) {
            sniff.type = FormatDetector.MP4;
            sniff.how = "el archivo empieza con la marca MP4";
        }
        if (!FormatDetector.OTHER.equals(sniff.type)) {
            sniff.detail = "código HTTP " + code + " · tipo " + (contentType.length() == 0
                    ? "(el servidor no lo informa)" : contentType);
            return sniff;
        }
        StringBuilder detail = new StringBuilder();
        detail.append("código HTTP ").append(code);
        detail.append(" · tipo ").append(contentType.length() == 0 ? "(sin informar)" : contentType);
        detail.append(" · los primeros bytes no son un manifiesto DASH");
        if (trace.size() > 0) detail.append(" · ").append(trace.get(trace.size() - 1));
        sniff.detail = detail.toString();
        return sniff;
    }

    /**
     * Pregunta rápida por el tipo del enlace antes de reproducir. Usa un tiempo de
     * espera corto (8 s): si el servidor calla, la app no puede quedarse 25 s
     * esperando antes de intentar la reproducción.
     */
    private static Response quickHead(String url, Map<String, String> headers,
                                     ArrayList<String> trace) throws IOException {
        String current = url;
        for (int hop = 0; hop < MAX_HOPS; hop++) {
            HttpURLConnection conn = null;
            try {
                conn = open(current, headers, "HEAD");
                conn.setConnectTimeout(QUICK_TIMEOUT);
                conn.setReadTimeout(QUICK_TIMEOUT);
                int code = conn.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String location = conn.getHeaderField("Location");
                    if (location == null || location.length() == 0) {
                        throw new IOException("el servidor redirigió el enlace pero no indicó a dónde"
                                + " (código " + code + ")");
                    }
                    current = resolve(current, location);
                    if (trace != null) trace.add("redirección " + code);
                    continue;
                }
                Response response = new Response();
                response.code = code;
                response.url = current;
                response.contentType = conn.getContentType();
                response.body = new byte[0];
                return response;
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        throw new IOException("el enlace da demasiadas vueltas (demasiadas redirecciones)");
    }

    /**
     * Primeros bytes de un enlace (para reconocer el formato por su contenido),
     * siguiendo las redirecciones y cerrando la conexión en cuanto se leen.
     */
    private static String readHeadText(String url, Map<String, String> headers, int maxBytes)
            throws IOException {
        String current = url;
        for (int hop = 0; hop < MAX_HOPS; hop++) {
            HttpURLConnection conn = null;
            try {
                conn = open(current, headers, "GET");
                conn.setConnectTimeout(QUICK_TIMEOUT);
                conn.setReadTimeout(QUICK_TIMEOUT);
                conn.setRequestProperty("Range", "bytes=0-" + Math.max(0, maxBytes - 1));
                int code = conn.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String location = conn.getHeaderField("Location");
                    if (location == null || location.length() == 0) {
                        throw new IOException("el servidor redirigió el enlace pero no indicó a dónde"
                                + " (código " + code + ")");
                    }
                    current = resolve(current, location);
                    continue;
                }
                if (code < 200 || code >= 400) {
                    throw new IOException("el servidor respondió con el código " + code);
                }
                InputStream in = conn.getInputStream();
                try {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buffer = new byte[1024];
                    int read;
                    int total = 0;
                    while ((read = in.read(buffer)) > 0) {
                        out.write(buffer, 0, read);
                        total += read;
                        if (total >= maxBytes) break;
                    }
                    return decode(out.toByteArray(), conn.getContentType());
                } finally {
                    try {
                        in.close();
                    } catch (Exception ignored) {
                    }
                }
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        throw new IOException("el enlace da demasiadas vueltas (demasiadas redirecciones)");
    }

    /** true si el texto contiene etiquetas propias del formato de listas. */
    static boolean hasPlaylistTags(String text) {
        if (text == null || text.length() == 0) return false;
        int limit = Math.min(text.length(), 8000);
        String head = text.substring(0, limit).toUpperCase(Locale.US);
        return head.indexOf("#EXTM3U") >= 0 || head.indexOf("#EXTINF") >= 0 || head.indexOf("#EXT-X-") >= 0;
    }

    /** Texto de una URL con las cabeceras indicadas. */
    static String getText(String url, Map<String, String> headers) throws IOException {
        Response response = fetch(url, headers, "GET", null);
        return decode(response.body, response.contentType);
    }

    /** Tipo de contenido informado por el servidor (nunca lanza excepción). */
    static String contentType(String url, Map<String, String> headers) {
        try {
            Response response = fetch(url, headers, "HEAD", null);
            return response.contentType;
        } catch (Exception e) {
            return null;
        }
    }

    /** Convierte bytes en texto usando la codificación del servidor y reserva Latin-1 y UTF-16. */
    static String decode(byte[] data, String contentType) {
        if (data == null) return "";
        if (data.length >= 2) {
            int b0 = data[0] & 0xFF;
            int b1 = data[1] & 0xFF;
            // Archivos .m3u guardados por Windows suelen venir en UTF-16.
            if (b0 == 0xFF && b1 == 0xFE) {
                String utf16le = decodeWith(data, 2, data.length - 2, "UTF-16LE");
                if (utf16le != null) return cleanBom(utf16le);
            }
            if (b0 == 0xFE && b1 == 0xFF) {
                String utf16be = decodeWith(data, 2, data.length - 2, "UTF-16BE");
                if (utf16be != null) return cleanBom(utf16be);
            }
        }
        // Si los bytes son UTF-8 válidos, se leen como UTF-8 AUNQUE el servidor
        // diga otra codificación. Muchos paneles anuncian "GBK", "ISO-8859-1" o
        // incluso "windows-1251" y en realidad sirven el archivo en UTF-8: al
        // hacerles caso, cada bandera y cada emoji del nombre del canal se
        // convertía en letras sin sentido (el nombre ya no se podía leer).
        String strict = strictUtf8(data);
        if (strict != null) return cleanBom(strict);
        String charset = charsetOf(contentType);
        String text = decodeWith(data, charset == null ? "UTF-8" : charset);
        if (text == null) text = new String(data);
        if (text.indexOf('\uFFFD') >= 0) {
            String latin = decodeWith(data, "ISO-8859-1");
            if (latin != null && latin.indexOf('\uFFFD') < 0) return cleanBom(latin);
        }
        return cleanBom(text);
    }

    /** true si el texto parece una página web y no una lista M3U. */
    static boolean looksLikeHtml(String text) {
        if (text == null) return false;
        int limit = Math.min(text.length(), 600);
        String head = text.substring(0, limit).toLowerCase(Locale.US);
        if (head.indexOf("#extm3u") >= 0 || head.indexOf("#extinf") >= 0 || head.indexOf("#ext-x-") >= 0) {
            return false;
        }
        return head.indexOf("<html") >= 0 || head.indexOf("<!doctype") >= 0
                || head.indexOf("<body") >= 0 || head.indexOf("<head") >= 0;
    }

    /** Mensaje de error en lenguaje sencillo. */
    static String describe(Throwable e) {
        if (e == null) return "error desconocido";
        if (e instanceof java.net.UnknownHostException) {
            return "no se encontró el servidor del enlace (revisa que esté bien escrito y que tengas internet)";
        }
        if (e instanceof java.net.SocketTimeoutException) {
            return "el servidor tardó demasiado en responder";
        }
        if (e instanceof java.net.ConnectException) {
            return "no se pudo conectar con el servidor";
        }
        if (e instanceof javax.net.ssl.SSLException) {
            return "el certificado de seguridad del servidor no es válido";
        }
        String message = e.getMessage();
        if (message == null || message.length() == 0) message = e.getClass().getSimpleName();
        // Se añade el detalle técnico de la excepción ORIGINAL (tipo y causas
        // encadenadas): sin esto, un fallo de descarga o de lectura del manifiesto
        // llegaba a la pantalla como una frase genérica y era imposible saber qué
        // había fallado de verdad.
        return message + technical(e);
    }

    /**
     * Detalle técnico de una excepción: su clase, su mensaje, la primera línea de
     * código donde nació y las excepciones encadenadas que la causaron. No se
     * oculta la excepción original detrás de un código genérico.
     */
    static String technical(Throwable e) {
        if (e == null) return "";
        StringBuilder sb = new StringBuilder(" [");
        Throwable current = e;
        int depth = 0;
        while (current != null && depth < 4) {
            if (depth > 0) sb.append(" ← causado por: ");
            sb.append(current.getClass().getName());
            String detail = current.getMessage();
            if (detail != null && detail.length() > 0) sb.append(": ").append(detail);
            if (depth == 0) {
                StackTraceElement[] frames = current.getStackTrace();
                if (frames != null && frames.length > 0 && frames[0] != null) {
                    sb.append(" (en ").append(frames[0].getFileName()).append(':')
                            .append(frames[0].getLineNumber()).append(')');
                }
            }
            current = current.getCause();
            depth++;
        }
        return sb.append(']').toString();
    }

    /**
     * Comprobación mínima del enlace: código HTTP real y tipo de contenido, sin
     * descargar el archivo. Se usa cuando el reproductor de Android devuelve un
     * código que no explica nada (-2147483648): así se ve si el servidor responde
     * 403 (faltan cabeceras), 404 (enlace caducado), una página web, etc.
     */
    static String quickStatus(String url, Map<String, String> headers) {
        if (url == null || url.trim().length() == 0) return "no hay ningún enlace que comprobar";
        String clean = url.trim();
        Map<String, String> used = (headers == null || headers.isEmpty()) ? playerHeaders(clean) : headers;
        ArrayList<String> trace = new ArrayList<String>();
        String firstProblem = null;
        try {
            Response response = probeOnce(clean, used, false, trace);
            if (response.code == 405 || response.code == 501 || response.code == 400) {
                // Algunos servidores no admiten la petición HEAD: se pregunta otra
                // vez pidiendo únicamente el primer byte del archivo.
                ArrayList<String> second = new ArrayList<String>();
                response = probeOnce(clean, used, true, second);
                return describeProbe(response, second, used);
            }
            return describeProbe(response, trace, used);
        } catch (Throwable t) {
            firstProblem = describe(t);
        }
        try {
            ArrayList<String> second = new ArrayList<String>();
            Response response = probeOnce(clean, used, true, second);
            return describeProbe(response, second, used);
        } catch (Throwable t) {
            return "no se pudo ni preguntar al servidor: " + describe(t)
                    + " · primer intento: " + firstProblem
                    + " · cabeceras enviadas: " + describeHeaders(used);
        }
    }

    /** Texto legible del resultado de una comprobación del enlace. */
    private static String describeProbe(Response response, ArrayList<String> trace,
                                        Map<String, String> headers) {
        StringBuilder sb = new StringBuilder();
        sb.append("código HTTP ").append(response.code);
        if (response.code >= 400) sb.append(" (").append(MediaErrors.httpReason(response.code)).append(')');
        sb.append(" · tipo de contenido ")
                .append(response.contentType == null || response.contentType.length() == 0
                        ? "(el servidor no lo informa)" : response.contentType);
        if (trace != null && !trace.isEmpty()) {
            sb.append(" · ").append(trace.size()).append(" redirección(es)");
        }
        sb.append(" · cabeceras enviadas: ").append(describeHeaders(headers));
        return sb.toString();
    }

    /**
     * Una sola pregunta al servidor, siguiendo las redirecciones a mano y sin
     * descargar el archivo: HEAD o, si el servidor no lo admite, GET con
     * "Range: bytes=0-0" (pide un solo byte).
     */
    private static Response probeOnce(String url, Map<String, String> headers, boolean ranged,
                                      ArrayList<String> trace) throws IOException {
        String current = url;
        for (int hop = 0; hop < MAX_HOPS; hop++) {
            HttpURLConnection conn = null;
            try {
                conn = open(current, headers, ranged ? "GET" : "HEAD");
                if (ranged) conn.setRequestProperty("Range", "bytes=0-0");
                int code = conn.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String location = conn.getHeaderField("Location");
                    if (location == null || location.length() == 0) {
                        throw new IOException("el servidor redirigió el enlace pero no indicó a dónde"
                                + " (código " + code + ")");
                    }
                    current = resolve(current, location);
                    if (trace != null) trace.add("redirección " + code);
                    continue;
                }
                Response response = new Response();
                response.code = code;
                response.url = current;
                String type = conn.getContentType();
                String range = conn.getHeaderField("Content-Range");
                if (range != null && range.length() > 0) {
                    type = (type == null ? "" : type + " · ") + "tamaño " + range;
                }
                response.contentType = type;
                response.body = new byte[0];
                return response;
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        throw new IOException("el enlace da demasiadas vueltas (demasiadas redirecciones)");
    }

    /** Origen del enlace (por ejemplo https://mi-proveedor.com/). */
    static String originOf(String url) {
        try {
            URL u = new URL(url);
            String host = u.getHost();
            if (host == null || host.length() == 0) return "";
            return u.getProtocol() + "://" + host + "/";
        } catch (Exception e) {
            return "";
        }
    }

    private static void addAttempt(ArrayList<Map<String, String>> attempts, Map<String, String> candidate) {
        for (int i = 0; i < attempts.size(); i++) {
            Map<String, String> existing = attempts.get(i);
            if (existing == null ? candidate == null : existing.equals(candidate)) return;
        }
        attempts.add(candidate);
    }

    private static Map<String, String> playerHeaders(String url) {
        Map<String, String> map = new TreeMap<String, String>();
        map.put("User-Agent", PLAYER_UA);
        String origin = originOf(url);
        if (origin.length() > 0) map.put("Referer", origin);
        return map;
    }

    /** Solo User-Agent: hay servidores que rechazan cualquier Referer. */
    private static Map<String, String> plainHeaders(String url, String userAgent) {
        Map<String, String> map = new TreeMap<String, String>();
        map.put("User-Agent", userAgent);
        return map;
    }

    /** Como las de reproductor, pero con el propio enlace como Referer (lo piden algunos paneles). */
    private static Map<String, String> playerHeadersWithFullReferer(String url) {
        Map<String, String> map = new TreeMap<String, String>();
        map.put("User-Agent", PLAYER_UA);
        if (url != null && url.length() > 0) map.put("Referer", url);
        return map;
    }

    private static Map<String, String> browserHeaders(String url) {
        Map<String, String> map = new TreeMap<String, String>();
        map.put("User-Agent", BROWSER_UA);
        String origin = originOf(url);
        if (origin.length() > 0) map.put("Referer", origin);
        return map;
    }

    private static String charsetOf(String contentType) {
        if (contentType == null) return null;
        String low = contentType.toLowerCase(Locale.US);
        int index = low.indexOf("charset=");
        if (index < 0) return null;
        String charset = contentType.substring(index + 8).trim();
        int semi = charset.indexOf(';');
        if (semi >= 0) charset = charset.substring(0, semi);
        charset = charset.replace("\"", "").trim();
        return charset.length() == 0 ? null : charset;
    }

    private static String cleanBom(String text) {
        if (text == null) return "";
        if (text.length() > 0 && text.charAt(0) == '\uFEFF') return text.substring(1);
        return text;
    }

    private static String decodeWith(byte[] data, String charset) {
        return decodeWith(data, 0, data.length, charset);
    }

    /** Lee los bytes como UTF-8 solo si son UTF-8 de verdad (null si no lo son). */
    private static String strictUtf8(byte[] data) {
        try {
            java.nio.charset.CharsetDecoder decoder = java.nio.charset.Charset.forName("UTF-8")
                    .newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
            return decoder.decode(java.nio.ByteBuffer.wrap(data)).toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private static String decodeWith(byte[] data, int offset, int length, String charset) {
        try {
            return new String(data, offset, length, charset);
        } catch (Exception e) {
            return null;
        }
    }

    private static final class Response {
        int code;
        byte[] body;
        String contentType;
        String url;
    }

    /**
     * Descarga una dirección siguiendo las redirecciones a mano (hasta 6 saltos),
     * también cuando la redirección cambia de http a https. Cada paso se anota.
     */
    private static Response fetch(String url, Map<String, String> headers, String method,
                                  ArrayList<String> trace) throws IOException {
        String current = url;
        for (int hop = 0; hop < MAX_HOPS; hop++) {
            HttpURLConnection conn = null;
            try {
                conn = open(current, headers, method);
                int code = conn.getResponseCode();
                String detail = "código " + code + " · " + (conn.getContentType() == null ? "(sin tipo)" : conn.getContentType());
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String location = conn.getHeaderField("Location");
                    if (location == null || location.length() == 0) {
                        throw new IOException("el servidor redirigió el enlace pero no indicó a dónde"
                                + " (código " + code + ")");
                    }
                    String next = resolve(current, location);
                    if (trace != null) trace.add("Redirección (" + detail + ") hacia: " + next);
                    current = next;
                    continue;
                }
                if (code < 200 || code >= 400) {
                    String extra = (code == 401 || code == 403)
                            ? " (revisa el User-Agent y el Referer: el proveedor rechazó la petición)" : "";
                    throw new IOException("el servidor respondió con el código " + code + extra);
                }
                Response response = new Response();
                response.code = code;
                response.url = current;
                response.contentType = conn.getContentType();
                byte[] body = readAll(conn);
                response.body = maybeGunzip(body);
                return response;
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        throw new IOException("el enlace da demasiadas vueltas (demasiadas redirecciones)");
    }

    private static String resolve(String base, String location) {
        try {
            return new URL(new URL(base), location).toString();
        } catch (Exception e) {
            return location;
        }
    }

    private static byte[] readAll(HttpURLConnection conn) throws IOException {
        InputStream in = conn.getInputStream();
        String encoding = conn.getContentEncoding();
        if (encoding != null) {
            String low = encoding.toLowerCase(Locale.US);
            if (low.indexOf("gzip") >= 0) in = new GZIPInputStream(in);
            else if (low.indexOf("deflate") >= 0) in = new InflaterInputStream(in);
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            int total = 0;
            while ((read = in.read(buffer)) > 0) {
                total += read;
                if (total > MAX_BYTES) {
                    throw new IOException("el archivo es demasiado grande para una lista (más de 32 MB)");
                }
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } finally {
            try {
                in.close();
            } catch (Exception ignored) {
            }
        }
    }

    /** Hay servidores que comprimen sin avisar en la cabecera. */
    private static byte[] maybeGunzip(byte[] body) {
        if (body == null || body.length < 3) return body;
        if ((body[0] & 0xFF) != 0x1F || (body[1] & 0xFF) != 0x8B) return body;
        InputStream in = null;
        try {
            in = new GZIPInputStream(new java.io.ByteArrayInputStream(body));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            int total = 0;
            while ((read = in.read(buffer)) > 0) {
                total += read;
                if (total > MAX_BYTES) break;
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } catch (Throwable t) {
            return body;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static HttpURLConnection open(String url, Map<String, String> headers, String method) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setInstanceFollowRedirects(false);
        conn.setConnectTimeout(CONNECT_TIMEOUT);
        conn.setReadTimeout(READ_TIMEOUT);
        try {
            conn.setRequestMethod("HEAD".equalsIgnoreCase(method) ? "HEAD" : "GET");
        } catch (Exception ignored) {
        }
        conn.setRequestProperty("Accept", "*/*");
        conn.setRequestProperty("Accept-Encoding", "identity");
        String ua = headers == null ? null : headers.get("User-Agent");
        conn.setRequestProperty("User-Agent", (ua == null || ua.length() == 0) ? DEFAULT_UA : ua);
        // Además del User-Agent y el Referer se envían las cabeceras adicionales
        // configuradas (algunos proveedores exigen Origin, Cookie o una propia).
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
        return conn;
    }
}
