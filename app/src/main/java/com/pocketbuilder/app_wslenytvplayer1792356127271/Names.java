package com.pocketbuilder.app_wslenytvplayer1792356127271;

import android.graphics.Paint;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;

/**
 * Arreglo de los nombres de canal y de las categorías ANTES de dibujarlos.
 *
 * Muchas listas IPTV traen banderas y bolas de color en el nombre
 * ("FOX SPORTS 🇦🇷", "TIGO SPORTS 🔵🟡", "ESPN 🔴"). En la captura del usuario esos
 * nombres salían como letras sin sentido por dos motivos distintos:
 *
 *  1. El servidor de la lista dice una codificación (por ejemplo GBK o
 *     ISO-8859-1) pero en realidad manda el texto en UTF-8. Al leerlo con la
 *     codificación equivocada, cada emoji se convierte en tres o cuatro letras
 *     raras. Aquí se deshace ese destrozo volviendo a interpretar los bytes.
 *  2. Aunque el texto llegue bien, el teléfono puede no tener dibujo para ese
 *     emoji concreto. En ese caso se sustituye por algo que sí se puede leer:
 *     una bandera pasa a ser su código de país ("AR") y los símbolos que no se
 *     pueden dibujar se quitan, sin dejar huecos.
 *
 * No cambia el nombre guardado ni el enlace: solo lo que se muestra en pantalla.
 */
final class Names {

    /** Codificaciones con las que se suele leer por error un texto UTF-8. */
    private static final String[] WRONG_CHARSETS = {
            "windows-1252", "ISO-8859-1", "GBK", "Big5", "Shift_JIS", "EUC-KR", "windows-1251"
    };

    private Names() {
    }

    /** Texto de un nombre o categoría listo para mostrarse (sin comprobar el dibujo). */
    static String display(String raw) {
        return forDisplay(null, raw);
    }

    /**
     * Texto listo para mostrarse en una vista: arregla el destrozo de
     * codificación y, si la letra de esa vista no puede dibujar un emoji, lo
     * cambia por algo legible.
     */
    static String forDisplay(Paint paint, String raw) {
        String text = repair(raw);
        if (text.length() == 0) return text;
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            int code = text.codePointAt(i);
            int size = Character.charCount(code);
            // Marcas invisibles (unión de emojis y selectores de variación).
            if (code == 0x200D || (code >= 0xFE00 && code <= 0xFE0F) || code == 0x20E3) {
                i += size;
                continue;
            }
            if (code >= 0x1F1E6 && code <= 0x1F1FF) {
                // Bandera (dos letras regionales). REVISIÓN 53: se escribe SIEMPRE
                // el código del país, sin preguntar antes si la letra puede
                // dibujar la bandera. En el teléfono del usuario la letra decía
                // que sí podía dibujarla y luego pintaba dos cuadros, así que
                // «FOX SPORTS 🇦🇷» se veía mal. «AR», «MX», «ES»... se leen
                // siempre: «FOX SPORTS AR».
                int second = -1;
                int secondSize = 0;
                int next = i + size;
                if (next < text.length()) {
                    int other = text.codePointAt(next);
                    if (other >= 0x1F1E6 && other <= 0x1F1FF) {
                        second = other;
                        secondSize = Character.charCount(other);
                    }
                }
                out.append((char) ('A' + (code - 0x1F1E6)));
                if (second > 0) out.append((char) ('A' + (second - 0x1F1E6)));
                i = next + secondSize;
                continue;
            }
            if (code < 0x2190) {
                // Letras, acentos y puntuación normal: se respetan tal cual.
                out.appendCodePoint(code);
                i += size;
                continue;
            }
            String symbol = new String(Character.toChars(code));
            if (canDraw(paint, symbol)) out.append(symbol);
            i += size;
        }
        return tidy(out.toString());
    }

    /**
     * Nombre de un APARTADO listo para mostrarse: arreglado como cualquier nombre
     * y en MAYÚSCULAS, como en la lista del reproductor ("ENTEL GOL", "LIGA XBER",
     * "ESPN").
     *
     * Solo cambia lo que se dibuja: el nombre guardado del apartado sigue igual,
     * así que los canales se siguen agrupando con el mismo texto de antes.
     */
    static String forSection(Paint paint, String raw) {
        String text = forDisplay(paint, raw);
        if (text.length() == 0) return text;
        try {
            return text.toUpperCase(java.util.Locale.US);
        } catch (Throwable t) {
            return text;
        }
    }

    /** Deshace el destrozo de codificación y limpia caracteres de control. */
    static String repair(String raw) {
        if (raw == null) return "";
        String text = raw.replace('\u00A0', ' ');
        if (!looksMangled(text)) return tidy(text);
        String best = text;
        int bestScore = garbageScore(text);
        for (int i = 0; i < WRONG_CHARSETS.length; i++) {
            String candidate = reencode(text, WRONG_CHARSETS[i]);
            if (candidate == null) continue;
            int score = garbageScore(candidate);
            if (score < bestScore) {
                best = candidate;
                bestScore = score;
            }
        }
        return tidy(best);
    }

    // ------------------------------------------------------------------ interno

    /** Vuelve a pasar el texto por bytes (con la codificación equivocada) y lo lee en UTF-8. */
    private static String reencode(String text, String charset) {
        try {
            byte[] bytes = text.getBytes(Charset.forName(charset));
            if (bytes == null || bytes.length == 0) return null;
            String decoded = strictUtf8(bytes);
            if (decoded == null || decoded.equals(text)) return null;
            // Si al convertir faltaban letras (se cambiaron por "?"), el intento no
            // sirve: se habría perdido el nombre original.
            if (count(decoded, '?') > count(text, '?')) return null;
            if (count(decoded, '\uFFFD') > 0) return null;
            return decoded;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String strictUtf8(byte[] data) {
        try {
            CharsetDecoder decoder = Charset.forName("UTF-8").newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            return decoder.decode(ByteBuffer.wrap(data)).toString();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * true solo si el texto parece UTF-8 leído con otra codificación. Se exige que
     * haya letras latinas mezcladas: un nombre escrito de verdad en chino, ruso o
     * japonés no se toca.
     */
    private static boolean looksMangled(String text) {
        if (text.length() == 0) return false;
        int latin = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) latin++;
        }
        if (latin < 2) return false;
        return garbageScore(text) >= 2;
    }

    /** Cuántas señales de texto destrozado hay (cuanto más alto, peor). */
    private static int garbageScore(String text) {
        int score = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == 'Ã' || c == 'Â' || c == 'ð' || c == 'Ð' || c == 'Ñ' || c == 'â'
                    || c == '\uFFFD' || c == '\u009F' || c == '\u0080') {
                score += 3;
                continue;
            }
            if ((c >= 0x4E00 && c <= 0x9FFF)      // ideogramas chinos
                    || (c >= 0x3040 && c <= 0x30FF)   // japonés
                    || (c >= 0xAC00 && c <= 0xD7AF)   // coreano
                    || (c >= 0x0400 && c <= 0x04FF)) { // cirílico
                score += 2;
            }
        }
        if (text.indexOf('\uFFFD') >= 0) score += 2;
        return score;
    }

    private static boolean canDraw(Paint paint, String symbol) {
        if (paint == null) return true;
        try {
            return paint.hasGlyph(symbol);
        } catch (Throwable t) {
            return true;
        }
    }

    private static int count(String text, char value) {
        int total = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == value) total++;
        }
        return total;
    }

    /** Quita caracteres invisibles, junta espacios y recorta separadores sueltos. */
    private static String tidy(String text) {
        if (text == null) return "";
        StringBuilder sb = new StringBuilder(text.length());
        boolean space = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < ' ') continue;
            if (c == '\u200B' || c == '\u200C' || c == '\u200E' || c == '\u200F' || c == '\uFEFF') continue;
            if (c == ' ') {
                if (!space) sb.append(' ');
                space = true;
            } else {
                sb.append(c);
                space = false;
            }
        }
        String out = sb.toString().trim();
        while (out.length() > 0 && "·-|,;:/".indexOf(out.charAt(0)) >= 0) out = out.substring(1).trim();
        while (out.length() > 0 && "·-|,;:/".indexOf(out.charAt(out.length() - 1)) >= 0) {
            out = out.substring(0, out.length() - 1).trim();
        }
        return out;
    }
}
