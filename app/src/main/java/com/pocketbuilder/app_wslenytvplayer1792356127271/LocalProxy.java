package com.pocketbuilder.app_wslenytvplayer1792356127271;

import android.util.Base64;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * Servidor HTTP pequeño dentro de la propia app (solo 127.0.0.1).
 *
 * Muchos proveedores IPTV no entregan el canal si la petición no lleva su
 * User-Agent y su Referer, y algunos entregan la lista de canales con una
 * dirección que salta a otro servidor (redirección). El reproductor de Android
 * acepta cabeceras en la petición inicial, pero no siempre las repite en cada
 * trozo de vídeo de un canal HLS.
 *
 * Este puente recibe la petición del reproductor, vuelve a pedir el contenido al
 * servidor real con las cabeceras correctas, sigue las redirecciones y, cuando
 * lo que llega es una lista HLS, cambia las direcciones internas (segmentos y
 * sublistas) por direcciones de este puente para que también lleven cabeceras.
 *
 * REVISIÓN 59 · CABECERAS POR SESIÓN
 * Antes había UN SOLO mapa de cabeceras para todo el puente: el último que
 * reproducía fijaba las cabeceras de todos los envíos. Con dos reproductores o
 * dos módulos eso mezclaba peticiones. Ahora cada reproducción abre su SESIÓN
 * (una entrada propia con sus cabeceras) y la dirección del puente lleva esa
 * sesión dentro:
 *
 *     http://127.0.0.1:PUERTO/s/<sesión>/<dirección real codificada>
 *
 * Los segmentos y sublistas de un canal HLS conservan la sesión de la lista que
 * los pidió, así que cada reproducción usa siempre sus propias cabeceras.
 *
 * REVISIÓN 59 · REGISTRO CONECTABLE
 * El puente no escribe siempre en el registro de TV: cada módulo puede conectar
 * su propio registro con {@link #setLog(Log)}. Si no se conecta ninguno, se usa
 * el de la televisión, como antes.
 */
public final class LocalProxy {

    private static final int CONNECT_TIMEOUT = 12000;
    private static final int READ_TIMEOUT = 25000;
    private static final int MAX_PLAYLIST_BYTES = 6 * 1024 * 1024;
    private static final int MAX_HOPS = 6;
    private static final int HEAD_SIZE = 512;
    /** Sesiones de cabeceras que se conservan a la vez. */
    private static final int MAX_SESSIONS = 16;

    private static LocalProxy shared;

    /** Peticiones anotadas en el registro (el resto solo si son errores). */
    private static final int LOGGED_REQUESTS = 8;

    /** Registro al que se apuntan los mensajes del puente. */
    public interface Log {
        void add(String message);
    }

    private ServerSocket server;
    private ExecutorService pool;
    private int port = -1;
    private volatile boolean running;
    private volatile Map<String, String> headers = new TreeMap<String, String>();
    /** Cabeceras de cada reproducción, por identificador de sesión. */
    private final LinkedHashMap<String, Map<String, String>> sessions =
            new LinkedHashMap<String, Map<String, String>>(16, 0.75f, false);
    private long sessionCounter = 0L;
    private int requestsSeen;
    private int loggedRequests;
    /** Si ya se ha anotado en el diagnóstico qué cabeceras repite el puente. */
    private boolean headersLogged;
    private volatile Log log = null;

    private LocalProxy() {
    }

    public static synchronized LocalProxy get() {
        if (shared == null) shared = new LocalProxy();
        return shared;
    }

    /** Conecta el registro propio de un módulo (por ejemplo, el de películas). */
    public void setLog(Log sink) {
        log = sink;
    }

    /** Arranca el puente si hacía falta y devuelve su puerto (-1 si no se pudo). */
    public synchronized int ensureStarted() {
        if (running && server != null && !server.isClosed()) return port;
        try {
            server = new ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"));
            port = server.getLocalPort();
            running = true;
            pool = Executors.newCachedThreadPool();
            Thread loop = new Thread(new Runnable() {
                @Override
                public void run() {
                    acceptLoop();
                }
            });
            loop.setDaemon(true);
            loop.setName("leny-tv-proxy");
            loop.start();
            return port;
        } catch (Throwable t) {
            running = false;
            port = -1;
            closeQuietly(server);
            server = null;
            return -1;
        }
    }

    /** Cabeceras por defecto (compatibilidad con el uso antiguo). */
    public void setHeaders(Map<String, String> map) {
        Map<String, String> clean = map == null
                ? new TreeMap<String, String>() : new TreeMap<String, String>(map);
        if (!clean.equals(headers)) headersLogged = false;
        headers = clean;
    }

    /**
     * Cabeceras que el puente repite en CADA petición (manifiesto, segmentos,
     * sublistas y claves de un canal HLS). Es el mismo cálculo que usa la petición
     * real, así que la prueba de dentro de la app comprueba el camino de verdad.
     */
    public static Map<String, String> bridgeHeaders(Map<String, String> channelHeaders) {
        return Headers.forRequest(channelHeaders);
    }

    /**
     * Dirección local que sirve el enlace remoto con estas cabeceras.
     * Devuelve null si el puente no se pudo abrir (entonces se reproduce directo).
     */
    public String proxyUrl(String remoteUrl, Map<String, String> requestHeaders) {
        if (remoteUrl == null || remoteUrl.length() == 0) return null;
        if (!isHttp(remoteUrl)) return null;
        int localPort = ensureStarted();
        if (localPort <= 0) return null;
        String session = openSession(requestHeaders);
        if (!headersLogged) {
            headersLogged = true;
            Map<String, String> applied = bridgeHeaders(headersFor(session));
            note("Puente local · repetirá en cada petición las cabeceras de la reproducción ("
                    + Headers.names(applied) + "): " + Headers.describe(applied));
        }
        String token = encode(remoteUrl);
        if (token == null) return null;
        return "http://127.0.0.1:" + localPort + "/s/" + session + "/" + token;
    }

    public static boolean isHttp(String url) {
        String low = url == null ? "" : url.toLowerCase(Locale.US);
        return low.startsWith("http://") || low.startsWith("https://");
    }

    // --------------------------------------------------------------- sesiones

    /** Abre una sesión con sus cabeceras y devuelve su identificador. */
    private synchronized String openSession(Map<String, String> requestHeaders) {
        Map<String, String> clean = requestHeaders == null
                ? new TreeMap<String, String>() : new TreeMap<String, String>(requestHeaders);
        // Si ya hay una sesión con exactamente estas cabeceras se reutiliza: así no
        // se llena la tabla al reproducir varias veces lo mismo.
        for (Map.Entry<String, Map<String, String>> entry : sessions.entrySet()) {
            if (clean.equals(entry.getValue())) return entry.getKey();
        }
        sessionCounter++;
        String id = "s" + Long.toString(sessionCounter, 36);
        sessions.put(id, clean);
        while (sessions.size() > MAX_SESSIONS) {
            String oldest = sessions.keySet().iterator().next();
            sessions.remove(oldest);
        }
        headers = clean;
        return id;
    }

    /** Cabeceras de una sesión; si ya no existe, las de por defecto. */
    private Map<String, String> headersFor(String session) {
        if (session != null) {
            synchronized (this) {
                Map<String, String> found = sessions.get(session);
                if (found != null) return found;
            }
        }
        return headers;
    }

    // ------------------------------------------------------------------ puente

    private void acceptLoop() {
        while (running) {
            Socket client;
            try {
                client = server.accept();
            } catch (Throwable t) {
                if (!running) return;
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    return;
                }
                continue;
            }
            final Socket socket = client;
            try {
                pool.execute(new Runnable() {
                    @Override
                    public void run() {
                        handle(socket);
                    }
                });
            } catch (Throwable t) {
                closeQuietly(socket);
            }
        }
    }

    private void handle(Socket client) {
        try {
            client.setSoTimeout(30000);
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();
            String requestLine = readLine(in, 8192);
            if (requestLine == null) return;
            String method = "GET";
            String path = "/";
            String[] parts = requestLine.split(" ");
            if (parts.length > 0 && parts[0].length() > 0) method = parts[0].toUpperCase(Locale.US);
            if (parts.length > 1) path = parts[1];
            String range = null;
            String line;
            while ((line = readLine(in, 8192)) != null && line.length() > 0) {
                int colon = line.indexOf(':');
                if (colon <= 0) continue;
                String name = line.substring(0, colon).trim();
                String value = line.substring(colon + 1).trim();
                if ("range".equalsIgnoreCase(name)) range = value;
            }
            String[] target = decodePath(path);
            if (target == null) {
                sendSimple(out, 400, "Bad Request", "Petición no válida");
                return;
            }
            forward(target[1], target[0], range, method, out);
        } catch (Throwable ignored) {
            // Una petición con problemas no debe tumbar el puente.
        } finally {
            closeQuietly(client);
        }
    }

    private void forward(String url, String session, String range, String method, OutputStream out)
            throws IOException {
        String current = url;
        Map<String, String> own = headersFor(session);
        for (int hop = 0; hop < MAX_HOPS; hop++) {
            HttpURLConnection conn = open(current, range, method, own);
            int code = conn.getResponseCode();
            // Queda anotada la respuesta REAL del servidor del canal: es el dato que
            // permite distinguir un fallo de la app de un 403/404 del proveedor.
            logRequest(code, conn.getContentType(), current, range);
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String location = conn.getHeaderField("Location");
                conn.disconnect();
                if (location == null || location.length() == 0) {
                    note("Puente local · el servidor redirigió sin indicar a dónde (código "
                            + code + ") · " + Diag.channelUrl(current));
                    sendSimple(out, 502, "Bad Gateway",
                            "El servidor redirigió el enlace sin indicar a dónde");
                    return;
                }
                note("Puente local · redirección (código " + code + ") a otro servidor");
                current = resolve(current, location);
                continue;
            }
            if (code < 200 || code >= 400) {
                conn.disconnect();
                sendSimple(out, 502, "Bad Gateway", "El servidor respondió con el código " + code);
                return;
            }
            String contentType = conn.getContentType();
            InputStream stream = wrapStream(conn);
            BufferedInputStream buffered = new BufferedInputStream(stream, 16384);
            buffered.mark(HEAD_SIZE + 512);
            byte[] head = new byte[HEAD_SIZE];
            int headRead = readSome(buffered, head);
            buffered.reset();
            if (looksLikePlaylist(current, contentType, head, headRead)) {
                byte[] body = readAll(buffered, MAX_PLAYLIST_BYTES);
                String text = Net.decode(body, contentType);
                byte[] output = rewrite(text, current, session).getBytes("UTF-8");
                StringBuilder header = new StringBuilder();
                header.append("HTTP/1.1 200 OK\r\n");
                header.append("Content-Type: application/vnd.apple.mpegurl\r\n");
                header.append("Content-Length: ").append(output.length).append("\r\n");
                header.append("Cache-Control: no-cache\r\n");
                header.append("Connection: close\r\n\r\n");
                out.write(header.toString().getBytes("ISO-8859-1"));
                out.write(output);
                out.flush();
            } else {
                writePassThroughHeaders(out, code, conn);
                copy(buffered, out);
                out.flush();
            }
            conn.disconnect();
            return;
        }
        note("Puente local · el enlace da demasiadas vueltas (" + MAX_HOPS
                + " redirecciones seguidas)");
        sendSimple(out, 502, "Bad Gateway",
                "El enlace da demasiadas vueltas (demasiadas redirecciones)");
    }

    /**
     * Anota en el diagnóstico la respuesta del servidor. Se anotan las primeras
     * peticiones y TODOS los errores: un enlace HLS pide muchos trozos y el
     * registro no debe llenarse con cientos de líneas iguales.
     */
    private synchronized void logRequest(int code, String contentType, String url, String range) {
        requestsSeen++;
        boolean error = code < 200 || code >= 400;
        if (!error && loggedRequests >= LOGGED_REQUESTS) return;
        loggedRequests++;
        StringBuilder sb = new StringBuilder();
        sb.append("Puente local · petición ").append(requestsSeen);
        if (error) sb.append(" · FALLO");
        sb.append(": respuesta HTTP ").append(code);
        sb.append(" · ").append(contentType == null || contentType.length() == 0
                ? "(sin tipo de contenido)" : contentType);
        if (range != null && range.length() > 0) sb.append(" · petición parcial (Range)");
        sb.append(" · ").append(MoviesDiagLike.shortUrl(url));
        note(sb.toString());
    }

    private HttpURLConnection open(String url, String range, String method,
                                   Map<String, String> ownHeaders) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setInstanceFollowRedirects(false);
        conn.setConnectTimeout(CONNECT_TIMEOUT);
        conn.setReadTimeout(READ_TIMEOUT);
        try {
            if ("HEAD".equals(method)) conn.setRequestMethod("HEAD");
            else conn.setRequestMethod("GET");
        } catch (Exception ignored) {
        }
        conn.setRequestProperty("Accept", "*/*");
        // Se pide sin compresión: así el tamaño y los trozos parciales que recibe
        // el reproductor son los reales y la lista HLS se puede reescribir.
        conn.setRequestProperty("Accept-Encoding", "identity");
        // TODAS las cabeceras de la reproducción, no solo dos: User-Agent, Referer,
        // Origin, Cookie, Authorization y las propias del proveedor. Las de control
        // (Host, Range...) se descartan porque las pone la propia conexión.
        Map<String, String> own = bridgeHeaders(ownHeaders);
        for (Map.Entry<String, String> entry : own.entrySet()) {
            if (entry == null) continue;
            String name = entry.getKey();
            String value = entry.getValue();
            if (name == null || name.length() == 0) continue;
            if (value == null || value.length() == 0) continue;
            conn.setRequestProperty(name, value);
        }
        String ua = own.get("User-Agent");
        if (ua == null || ua.length() == 0) conn.setRequestProperty("User-Agent", Net.PLAYER_UA);
        if (range != null && range.length() > 0) conn.setRequestProperty("Range", range);
        return conn;
    }

    private InputStream wrapStream(HttpURLConnection conn) throws IOException {
        InputStream in = conn.getInputStream();
        String encoding = conn.getContentEncoding();
        if (encoding != null) {
            String low = encoding.toLowerCase(Locale.US);
            if (low.indexOf("gzip") >= 0) return new GZIPInputStream(in);
            if (low.indexOf("deflate") >= 0) return new InflaterInputStream(in);
        }
        return in;
    }

    private void writePassThroughHeaders(OutputStream out, int code, HttpURLConnection conn)
            throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(code)
                .append(code == 206 ? " Partial Content" : " OK").append("\r\n");
        String contentType = conn.getContentType();
        sb.append("Content-Type: ")
                .append(contentType == null || contentType.length() == 0
                        ? "application/octet-stream" : contentType)
                .append("\r\n");
        boolean encoded = conn.getContentEncoding() != null;
        String length = conn.getHeaderField("Content-Length");
        if (!encoded && length != null && length.length() > 0) {
            sb.append("Content-Length: ").append(length).append("\r\n");
        }
        String contentRange = conn.getHeaderField("Content-Range");
        if (contentRange != null && contentRange.length() > 0) {
            sb.append("Content-Range: ").append(contentRange).append("\r\n");
        }
        sb.append("Accept-Ranges: bytes\r\n");
        sb.append("Connection: close\r\n\r\n");
        out.write(sb.toString().getBytes("ISO-8859-1"));
    }

    // -------------------------------------------------- listas HLS por dentro

    private boolean looksLikePlaylist(String url, String contentType, byte[] head, int headRead) {
        String low = url == null ? "" : url.toLowerCase(Locale.US);
        if (contentType != null && contentType.toLowerCase(Locale.US).indexOf("mpegurl") >= 0) {
            return true;
        }
        if (low.endsWith(".m3u8") || low.endsWith(".m3u")) return true;
        return containsIgnoreCase(head, headRead, "#EXTM3U");
    }

    /** Cambia las direcciones internas de la lista por direcciones del puente. */
    private String rewrite(String text, String baseUrl, String session) {
        if (text == null || text.length() == 0) return "";
        String[] lines = text.replace("\r", "").split("\n", -1);
        StringBuilder out = new StringBuilder(text.length() + 64);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();
            if (trimmed.length() == 0) {
                out.append('\n');
                continue;
            }
            if (trimmed.charAt(0) == '#') {
                out.append(rewriteUriAttributes(line, baseUrl, session)).append('\n');
                continue;
            }
            String proxied = proxyFor(absolute(baseUrl, trimmed), session);
            out.append(proxied == null ? line : proxied).append('\n');
        }
        return out.toString();
    }

    private String rewriteUriAttributes(String line, String baseUrl, String session) {
        int index = 0;
        StringBuilder out = new StringBuilder(line.length() + 32);
        while (true) {
            int at = line.indexOf("URI=\"", index);
            if (at < 0) {
                out.append(line.substring(index));
                break;
            }
            int start = at + 5;
            int end = line.indexOf('"', start);
            if (end < 0) {
                out.append(line.substring(index));
                break;
            }
            String value = line.substring(start, end);
            String proxied = proxyFor(absolute(baseUrl, value), session);
            out.append(line, index, start);
            out.append(proxied == null ? value : proxied);
            index = end;
        }
        return out.toString();
    }

    private String absolute(String baseUrl, String value) {
        if (value == null || value.length() == 0) return null;
        String trimmed = value.trim();
        if (isHttp(trimmed)) return trimmed;
        try {
            return new URL(new URL(baseUrl), trimmed).toString();
        } catch (Exception e) {
            return null;
        }
    }

    private String proxyFor(String absoluteUrl, String session) {
        if (absoluteUrl == null || !isHttp(absoluteUrl)) return null;
        int localPort = port;
        if (localPort <= 0) return null;
        String token = encode(absoluteUrl);
        if (token == null) return null;
        String use = session == null || session.length() == 0 ? "s0" : session;
        return "http://127.0.0.1:" + localPort + "/s/" + use + "/" + token;
    }

    private static String encode(String url) {
        try {
            return Base64.encodeToString(url.getBytes("UTF-8"),
                    Base64.NO_WRAP | Base64.NO_PADDING | Base64.URL_SAFE);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Separa la ruta del puente en {sesión, dirección real}. Se admiten las dos
     * formas: la nueva "/s/sesión/dirección" y la antigua "/s/dirección".
     */
    private static String[] decodePath(String path) {
        if (path == null) return null;
        int at = path.indexOf("/s/");
        if (at < 0) return null;
        String rest = path.substring(at + 3);
        int cut = rest.indexOf('?');
        if (cut >= 0) rest = rest.substring(0, cut);
        cut = rest.indexOf('#');
        if (cut >= 0) rest = rest.substring(0, cut);
        rest = rest.trim();
        if (rest.length() == 0) return null;
        int slash = rest.indexOf('/');
        String session = "";
        String token = rest;
        if (slash > 0 && slash <= 12) {
            session = rest.substring(0, slash);
            token = rest.substring(slash + 1);
        }
        boolean sessionLooksRight = session.length() > 0;
        for (int i = 0; i < session.length() && sessionLooksRight; i++) {
            char c = session.charAt(i);
            if (!Character.isLetterOrDigit(c)) sessionLooksRight = false;
        }
        if (!sessionLooksRight) {
            session = "";
            token = rest;
        }
        if (token.length() == 0) return null;
        try {
            byte[] decoded = Base64.decode(token,
                    Base64.NO_WRAP | Base64.NO_PADDING | Base64.URL_SAFE);
            String url = new String(decoded, "UTF-8");
            return isHttp(url) ? new String[]{session, url} : null;
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------ ayudas

    /** Anota un mensaje en el registro conectado (o en el de TV si no hay ninguno). */
    private void note(String message) {
        Log sink = log;
        if (sink != null) {
            try {
                sink.add(message);
                return;
            } catch (Throwable ignored) {
            }
        }
        Diag.add(message);
    }

    private static String resolve(String base, String location) {
        try {
            return new URL(new URL(base), location).toString();
        } catch (Exception e) {
            return location;
        }
    }

    private static String readLine(InputStream in, int limit) throws IOException {
        StringBuilder sb = new StringBuilder();
        int b;
        boolean any = false;
        while ((b = in.read()) >= 0) {
            any = true;
            if (b == '\n') break;
            if (b != '\r') sb.append((char) b);
            if (sb.length() > limit) break;
        }
        if (!any && sb.length() == 0) return null;
        return sb.toString();
    }

    private static int readSome(InputStream in, byte[] buffer) throws IOException {
        int total = 0;
        while (total < buffer.length) {
            int read = in.read(buffer, total, buffer.length - total);
            if (read <= 0) break;
            total += read;
        }
        return total;
    }

    private static byte[] readAll(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = in.read(buffer)) > 0) {
            total += read;
            if (total > limit) break;
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[16384];
        int read;
        while ((read = in.read(buffer)) > 0) {
            out.write(buffer, 0, read);
        }
    }

    private static boolean containsIgnoreCase(byte[] data, int length, String wanted) {
        if (data == null || length <= 0) return false;
        String text = new String(data, 0, Math.min(length, data.length)).toUpperCase(Locale.US);
        return text.indexOf(wanted.toUpperCase(Locale.US)) >= 0;
    }

    private static void sendSimple(OutputStream out, int code, String reason, String message) {
        try {
            byte[] body = message.getBytes("UTF-8");
            StringBuilder sb = new StringBuilder();
            sb.append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n");
            sb.append("Content-Type: text/plain; charset=utf-8\r\n");
            sb.append("Content-Length: ").append(body.length).append("\r\n");
            sb.append("Connection: close\r\n\r\n");
            out.write(sb.toString().getBytes("ISO-8859-1"));
            out.write(body);
            out.flush();
        } catch (Throwable ignored) {
        }
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (Throwable ignored) {
        }
    }

    /** Ayuda para acortar direcciones sin exponer datos privados. */
    private static final class MoviesDiagLike {
        static String shortUrl(String url) {
            return Diag.channelUrl(url);
        }
    }
}
