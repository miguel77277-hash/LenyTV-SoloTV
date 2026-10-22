package com.pocketbuilder.app_wslenytvplayer1792356127271;

import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Cabeceras HTTP de LENY TV.
 *
 * Reúne lo que antes estaba repartido en varios archivos (y a medias): escribir
 * bien el nombre de una cabecera, quitar saltos de línea de los valores, juntar
 * las del canal con las generales dando prioridad a las del canal, describirlas
 * para el diagnóstico SIN enseñar cookies ni claves, y transportarlas de la
 * cuadrícula a la pantalla del reproductor.
 *
 * Todo aquí es tolerante a fallos: un dato raro de una lista nunca debe cerrar
 * la app, simplemente se ignora.
 */
final class Headers {

    /** Tope de un valor de cabecera (un valor enorme delataría una lista dañada). */
    private static final int MAX_VALUE = 2000;

    private Headers() {
    }

    static Map<String, String> empty() {
        return new TreeMap<String, String>();
    }

    /** Quita espacios, incluidos los invisibles que se cuelan al copiar y pegar. */
    static String trim(String value) {
        if (value == null) return "";
        int start = 0;
        int end = value.length();
        while (start < end && isSpace(value.charAt(start))) start++;
        while (end > start && isSpace(value.charAt(end - 1))) end--;
        return value.substring(start, end);
    }

    private static boolean isSpace(char c) {
        return c == ' ' || c == '\t' || c == '\r' || c == '\n' || c == '\u00A0'
                || c == '\uFEFF' || c == '\u000B' || c == '\f';
    }

    /**
     * Nombre de cabecera bien escrito: "user-agent" → "User-Agent",
     * "x-mi-cabecera" → "X-Mi-Cabecera". Devuelve "" si el nombre no es válido
     * (así no se envía jamás una cabecera con caracteres imposibles).
     */
    static String canonical(String raw) {
        String name = trim(raw);
        if (name.length() == 0) return "";
        int colon = name.indexOf(':');
        if (colon > 0) name = trim(name.substring(0, colon));
        if (name.length() == 0 || name.length() > 64) return "";
        StringBuilder normalized = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-') {
                normalized.append(c);
            } else if (c == '_') {
                normalized.append('-');
            } else {
                return "";
            }
        }
        String[] parts = normalized.toString().split("-");
        StringBuilder out = new StringBuilder(normalized.length());
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].length() == 0) continue;
            if (out.length() > 0) out.append('-');
            out.append(parts[i].substring(0, 1).toUpperCase(Locale.US));
            if (parts[i].length() > 1) {
                out.append(parts[i].substring(1).toLowerCase(Locale.US));
            }
        }
        String result = out.toString();
        if ("Referrer".equals(result)) return "Referer";
        return result;
    }

    /** Valor limpio: nunca con saltos de línea y con un tope de longitud. */
    static String value(String raw) {
        if (raw == null) return "";
        String clean = raw.replace('\r', ' ').replace('\n', ' ').trim();
        if (clean.length() > MAX_VALUE) clean = clean.substring(0, MAX_VALUE);
        return clean;
    }

    /**
     * Nombre de cabecera a partir de una opción de lista (#EXTVLCOPT:http-user-agent,
     * parámetros detrás de "|", stream_headers...). Devuelve "" cuando la opción no
     * es una cabecera HTTP (por ejemplo ":network-caching").
     */
    static String optionName(String raw) {
        String name = trim(raw);
        if (name.length() == 0) return "";
        String low = name.toLowerCase(Locale.US);
        boolean httpPrefix = low.startsWith("http-") || low.startsWith("http_");
        if (httpPrefix) {
            name = name.substring(5);
            low = name.toLowerCase(Locale.US);
        } else if (!isKnownHeader(low)) {
            return "";
        }
        if (low.equals("ua") || low.equals("useragent")) return "User-Agent";
        if (low.equals("referer") || low.equals("referrer")) return "Referer";
        return canonical(name);
    }

    private static boolean isKnownHeader(String low) {
        return low.equals("user-agent") || low.equals("useragent") || low.equals("ua")
                || low.equals("referer") || low.equals("referrer") || low.equals("origin")
                || low.equals("cookie") || low.equals("authorization") || low.startsWith("x-");
    }

    /** Añade (o reemplaza) una cabecera; ignora nombres o valores no utilizables. */
    static void put(Map<String, String> map, String name, String value) {
        if (map == null) return;
        String key = canonical(name);
        if (key.length() == 0) return;
        String clean = value(value);
        if (clean.length() == 0) {
            map.remove(key);
            return;
        }
        map.put(key, clean);
    }

    /**
     * Junta las cabeceras generales con las del canal. Las del canal MANDAN: si la
     * lista indica su propio User-Agent, Referer, Origin o Cookie, se usa el suyo
     * y no el general.
     */
    static Map<String, String> merge(Map<String, String> general, Map<String, String> own) {
        Map<String, String> out = new TreeMap<String, String>();
        copyInto(out, general);
        copyInto(out, own);
        return out;
    }

    private static void copyInto(Map<String, String> target, Map<String, String> source) {
        if (target == null || source == null) return;
        for (Map.Entry<String, String> entry : source.entrySet()) {
            if (entry == null) continue;
            put(target, entry.getKey(), entry.getValue());
        }
    }

    /** true si el nombre corresponde a un dato que no debe mostrarse completo. */
    static boolean sensitive(String name) {
        String low = name == null ? "" : name.toLowerCase(Locale.US);
        return low.indexOf("cookie") >= 0 || low.indexOf("authorization") >= 0
                || low.indexOf("token") >= 0 || low.indexOf("key") >= 0
                || low.indexOf("secret") >= 0 || low.indexOf("password") >= 0
                || low.indexOf("session") >= 0 || low.indexOf("auth") >= 0;
    }

    /** Texto de un valor para el diagnóstico: los datos sensibles van ocultos. */
    static String maskedValue(String name, String value) {
        if (value == null || value.length() == 0) return "(vacío)";
        if (sensitive(name)) return "(oculto, " + value.length() + " caracteres)";
        return shorten(value);
    }

    /** Valor corto y sin la parte de la dirección que puede llevar tokens. */
    static String shorten(String value) {
        if (value == null) return "";
        String clean = value.trim();
        int query = clean.indexOf('?');
        if (query > 0 && clean.indexOf("://") >= 0) clean = clean.substring(0, query);
        if (clean.length() <= 90) return clean;
        return clean.substring(0, 70) + "…" + clean.substring(clean.length() - 12);
    }

    /**
     * Descripción de las cabeceras para la pantalla DIAGNÓSTICO y los avisos:
     * dice cuáles se envían y oculta cookies, tokens y claves.
     */
    static String describe(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) return "(sin cabeceras propias)";
        StringBuilder sb = new StringBuilder();
        String userAgent = headers.get("User-Agent");
        String referer = headers.get("Referer");
        sb.append("User-Agent=").append(userAgent == null || userAgent.length() == 0
                ? "(por defecto)" : shorten(userAgent));
        if (referer != null && referer.length() > 0) {
            sb.append(" · Referer=").append(shorten(referer));
        } else {
            sb.append(" · sin Referer");
        }
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry == null) continue;
            String name = entry.getKey();
            if (name == null) continue;
            if ("User-Agent".equalsIgnoreCase(name) || "Referer".equalsIgnoreCase(name)) continue;
            sb.append(" · ").append(name).append('=').append(maskedValue(name, entry.getValue()));
        }
        return sb.toString();
    }

    /** Nombres de las cabeceras que trae el canal (para el diagnóstico). */
    static String names(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) return "(ninguna)";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry == null || entry.getKey() == null) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(entry.getKey());
        }
        return sb.toString();
    }

    /**
     * Texto para llevar las cabeceras del canal de una pantalla a otra sin
     * formularios: una por línea, "Nombre: valor".
     */
    static String toSpec(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry == null) continue;
            String name = canonical(entry.getKey());
            String value = value(entry.getValue());
            if (name.length() == 0 || value.length() == 0) continue;
            sb.append(name).append(": ").append(value).append('\n');
        }
        return sb.toString();
    }

    /** Cabeceras recibidas desde otra pantalla. Nunca lanza excepción. */
    static Map<String, String> fromSpec(String spec) {
        Map<String, String> out = new TreeMap<String, String>();
        if (spec == null || spec.length() == 0) return out;
        String[] lines = spec.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = trim(lines[i]);
            if (line.length() == 0) continue;
            int cut = line.indexOf(':');
            if (cut <= 0) continue;
            put(out, line.substring(0, cut), line.substring(cut + 1));
        }
        return out;
    }

    /** Copia ordenada y segura de un mapa de cabeceras (nunca null). */
    static Map<String, String> copy(Map<String, String> source) {
        Map<String, String> out = new TreeMap<String, String>();
        copyInto(out, source);
        return out;
    }

    /**
     * Cabeceras que se envían de verdad al pedir el vídeo: TODAS las del canal
     * (User-Agent, Referer, Origin, Cookie, Authorization y las propias del
     * proveedor) menos las de control, que pone la propia conexión.
     *
     * Así el puente local repite exactamente las mismas cabeceras que el canal
     * necesita, también en cada trozo de un canal HLS, y no solo dos.
     */
    static Map<String, String> forRequest(Map<String, String> headers) {
        Map<String, String> out = new TreeMap<String, String>();
        if (headers == null) return out;
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry == null) continue;
            String name = canonical(entry.getKey());
            if (name.length() == 0) continue;
            if (!allowedOnRequest(name)) continue;
            String clean = value(entry.getValue());
            if (clean.length() == 0) continue;
            out.put(name, clean);
        }
        return out;
    }

    /**
     * Cabeceras que NO se copian tal cual: las calcula la conexión (Host,
     * Content-Length...), las controla el puente (Range, Accept-Encoding) o, si se
     * repiten mal, romperían la petición. Enviarlas equivaldría a inventar datos.
     */
    static boolean allowedOnRequest(String name) {
        String low = name == null ? "" : name.toLowerCase(Locale.US);
        return !(low.equals("host") || low.equals("connection") || low.equals("keep-alive")
                || low.equals("transfer-encoding") || low.equals("content-length")
                || low.equals("content-type") || low.equals("content-encoding")
                || low.equals("accept-encoding") || low.equals("range")
                || low.equals("te") || low.equals("trailer") || low.equals("upgrade")
                || low.equals("proxy-connection") || low.equals("expect")
                || low.equals("accept") || low.equals("if-range")
                || low.equals("if-modified-since") || low.equals("if-none-match"));
    }
}
