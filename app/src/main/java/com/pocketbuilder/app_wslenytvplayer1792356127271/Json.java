package com.pocketbuilder.app_wslenytvplayer1792356127271;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lector de JSON pequeño y sin dependencias.
 *
 * La app no usa ninguna librería externa (todo el motor del reproductor son APIs
 * de Android), así que los catálogos de películas se leen con esta clase, que
 * convierte el texto en mapas y listas normales de Java:
 *
 *     {"items": [{"title": "Ejemplo"}]}   ->   Map("items" -> List(Map("title" -> "Ejemplo")))
 *
 * No valida el esquema del catálogo: de eso se encarga CatalogLoader.
 */
final class Json {

    /** Error de sintaxis con la posición exacta, para poder avisar con claridad. */
    static final class ParseError extends Exception {
        ParseError(String message) {
            super(message);
        }
    }

    private final String src;
    private int pos;

    private Json(String src) {
        this.src = src == null ? "" : src;
    }

    static Object parse(String text) throws ParseError {
        Json reader = new Json(text);
        reader.skipSpace();
        Object value = reader.readValue();
        reader.skipSpace();
        if (reader.pos < reader.src.length()) {
            throw new ParseError("hay texto de más después del JSON (posición " + reader.pos + ")");
        }
        return value;
    }

    /**
     * Igual que {@link #parse} pero sin lanzar error: devuelve null si el texto no
     * es un JSON válido. Se usa cuando el contenido no lo escribe la app (por
     * ejemplo, la respuesta de un servidor Xtream) y hay que seguir adelante.
     */
    static Object parseQuietly(String text) {
        try {
            return parse(text);
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------ ayudas

    static Map<String, Object> object(Object value) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        if (value instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (entry.getKey() != null) map.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return map;
    }

    static List<Object> array(Object value) {
        ArrayList<Object> list = new ArrayList<Object>();
        if (value instanceof List) {
            for (Object item : (List<?>) value) list.add(item);
        }
        return list;
    }

    static String text(Object value) {
        if (value == null) return "";
        if (value instanceof String) return (String) value;
        if (value instanceof Double) {
            double d = (Double) value;
            if (!Double.isNaN(d) && !Double.isInfinite(d) && d == Math.rint(d)
                    && Math.abs(d) < 1.0E15) {
                return String.valueOf((long) d);
            }
            return String.valueOf(d);
        }
        return String.valueOf(value);
    }

    /** Texto recortado; devuelve "" si el valor no existe. */
    static String clean(Object value) {
        return text(value).replace('\u00A0', ' ').trim();
    }

    static double number(Object value) {
        if (value instanceof Double) return (Double) value;
        try {
            return Double.parseDouble(clean(value));
        } catch (Throwable t) {
            return 0d;
        }
    }

    static int integer(Object value) {
        return (int) Math.round(number(value));
    }

    static boolean bool(Object value) {
        if (value instanceof Boolean) return (Boolean) value;
        String s = clean(value);
        return "true".equalsIgnoreCase(s) || "sí".equalsIgnoreCase(s) || "si".equalsIgnoreCase(s)
                || "1".equals(s);
    }

    // ------------------------------------------------------------------ lectura

    private Object readValue() throws ParseError {
        if (pos >= src.length()) throw new ParseError("el archivo JSON está vacío o incompleto");
        char c = src.charAt(pos);
        if (c == '{') return readObject();
        if (c == '[') return readArray();
        if (c == '"' || c == '\'') return readString();
        if (c == 't' || c == 'T') {
            expect("true");
            return Boolean.TRUE;
        }
        if (c == 'f' || c == 'F') {
            expect("false");
            return Boolean.FALSE;
        }
        if (c == 'n' || c == 'N') {
            expect("null");
            return null;
        }
        return readNumber();
    }

    private Map<String, Object> readObject() throws ParseError {
        LinkedHashMap<String, Object> map = new LinkedHashMap<String, Object>();
        pos++; // {
        skipSpace();
        if (pos < src.length() && src.charAt(pos) == '}') {
            pos++;
            return map;
        }
        while (pos < src.length()) {
            skipSpace();
            String key;
            if (pos < src.length() && (src.charAt(pos) == '"' || src.charAt(pos) == '\'')) {
                key = readString();
            } else {
                key = readBareKey();
            }
            skipSpace();
            if (pos < src.length() && src.charAt(pos) == ':') pos++;
            skipSpace();
            map.put(key, readValue());
            skipSpace();
            if (pos >= src.length()) throw new ParseError("falta la llave de cierre }");
            char c = src.charAt(pos);
            if (c == ',') {
                pos++;
                continue;
            }
            if (c == '}') {
                pos++;
                return map;
            }
            throw new ParseError("se esperaba , o } en la posición " + pos);
        }
        throw new ParseError("falta la llave de cierre }");
    }

    private List<Object> readArray() throws ParseError {
        ArrayList<Object> list = new ArrayList<Object>();
        pos++; // [
        skipSpace();
        if (pos < src.length() && src.charAt(pos) == ']') {
            pos++;
            return list;
        }
        while (pos < src.length()) {
            skipSpace();
            list.add(readValue());
            skipSpace();
            if (pos >= src.length()) throw new ParseError("falta el corchete de cierre ]");
            char c = src.charAt(pos);
            if (c == ',') {
                pos++;
                continue;
            }
            if (c == ']') {
                pos++;
                return list;
            }
            throw new ParseError("se esperaba , o ] en la posición " + pos);
        }
        throw new ParseError("falta el corchete de cierre ]");
    }

    private String readString() throws ParseError {
        char quote = src.charAt(pos);
        pos++;
        StringBuilder out = new StringBuilder();
        while (pos < src.length()) {
            char c = src.charAt(pos++);
            if (c == quote) return out.toString();
            if (c != '\\') {
                out.append(c);
                continue;
            }
            if (pos >= src.length()) break;
            char esc = src.charAt(pos++);
            switch (esc) {
                case 'n':
                    out.append('\n');
                    break;
                case 't':
                    out.append('\t');
                    break;
                case 'r':
                    out.append('\r');
                    break;
                case 'b':
                    out.append('\b');
                    break;
                case 'f':
                    out.append('\f');
                    break;
                case 'u':
                    if (pos + 4 > src.length()) throw new ParseError("escape \\u incompleto");
                    String hex = src.substring(pos, pos + 4);
                    pos += 4;
                    try {
                        out.append((char) Integer.parseInt(hex, 16));
                    } catch (Throwable t) {
                        throw new ParseError("escape \\u inválido: " + hex);
                    }
                    break;
                default:
                    out.append(esc);
                    break;
            }
        }
        throw new ParseError("texto sin comillas de cierre");
    }

    private String readBareKey() {
        int start = pos;
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == ':' || c == ',' || c == '}' || Character.isWhitespace(c)) break;
            pos++;
        }
        return src.substring(start, pos);
    }

    private Double readNumber() throws ParseError {
        int start = pos;
        while (pos < src.length()) {
            char c = src.charAt(pos);
            boolean allowed = (c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.'
                    || c == 'e' || c == 'E';
            if (!allowed) break;
            pos++;
        }
        if (start == pos) throw new ParseError("valor no reconocido en la posición " + pos);
        try {
            return Double.valueOf(src.substring(start, pos));
        } catch (Throwable t) {
            throw new ParseError("número inválido: " + src.substring(start, pos));
        }
    }

    private void expect(String word) throws ParseError {
        if (pos + word.length() > src.length()) throw new ParseError("valor incompleto");
        String found = src.substring(pos, pos + word.length());
        if (!found.equalsIgnoreCase(word)) throw new ParseError("se esperaba " + word);
        pos += word.length();
    }

    private void skipSpace() {
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n' || c == '\uFEFF') {
                pos++;
            } else {
                break;
            }
        }
    }
}
