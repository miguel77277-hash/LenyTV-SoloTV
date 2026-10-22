package com.pocketbuilder.app_wslenytvplayer1792356127271;

import android.media.MediaPlayer;

import java.io.IOException;
import java.util.Locale;

/**
 * Traduce los errores de reproducción a motivos reales.
 *
 * El reproductor de Android solo devuelve dos números (what y extra) y muchas
 * veces el segundo es -2147483648 (Integer.MIN_VALUE), que no explica nada: es
 * simplemente "sin detalle". Esta clase convierte esos códigos en una frase que
 * distingue entre problema de conexión, respuesta del servidor, códec no
 * soportado, contenido dañado, tiempo de espera o protección DRM.
 */
final class MediaErrors {

    /** Fallo con código HTTP real (lo lanza el motor DASH al pedir manifiesto o trozos). */
    static final class Http extends IOException {
        final int code;

        Http(int code, String message) {
            super(message);
            this.code = code;
        }
    }

    private MediaErrors() {
    }

    /** Categoría corta del fallo, para el registro y el resumen en pantalla. */
    static String kind(int what, int extra) {
        if (what == MediaPlayer.MEDIA_ERROR_SERVER_DIED) return "el servicio de medios de Android se reinició";
        switch (extra) {
            case MediaPlayer.MEDIA_ERROR_IO:
                return "conexión con el servidor";
            case MediaPlayer.MEDIA_ERROR_TIMED_OUT:
                return "tiempo de espera agotado";
            case MediaPlayer.MEDIA_ERROR_MALFORMED:
                return "contenido dañado o formato no reconocido";
            case MediaPlayer.MEDIA_ERROR_UNSUPPORTED:
                return "códec no soportado por este teléfono";
            case MediaPlayer.MEDIA_ERROR_NOT_VALID_FOR_PROGRESSIVE_PLAYBACK:
                return "el servidor no permite reproducir el archivo por partes";
            default:
                return "error del reproductor";
        }
    }

    /** Explicación en lenguaje sencillo de un error del reproductor de Android. */
    static String explain(int what, int extra) {
        StringBuilder sb = new StringBuilder();
        if (what == MediaPlayer.MEDIA_ERROR_SERVER_DIED) {
            sb.append("El servicio de medios de Android se reinició (código 100). "
                    + "Vuelve a intentarlo; si repite, reinicia el teléfono.");
            return sb.toString();
        }
        switch (extra) {
            case MediaPlayer.MEDIA_ERROR_IO:
                sb.append("No se pudo leer el contenido: la conexión falló o el servidor rechazó la "
                        + "petición (puede pedir cabeceras User-Agent/Referer o haber cortado el enlace).");
                break;
            case MediaPlayer.MEDIA_ERROR_TIMED_OUT:
                sb.append("El servidor tardó demasiado en enviar datos (se agotó el tiempo de espera).");
                break;
            case MediaPlayer.MEDIA_ERROR_MALFORMED:
                sb.append("El contenido llegó dañado o en un formato que el reproductor no reconoce "
                        + "(por ejemplo, un trozo incompleto o un manifiesto mal formado).");
                break;
            case MediaPlayer.MEDIA_ERROR_UNSUPPORTED:
                sb.append("El códec de vídeo o de audio no está soportado por este teléfono "
                        + "(es habitual con H.265/HEVC, AV1 o sonido AC-3/E-AC-3).");
                break;
            case MediaPlayer.MEDIA_ERROR_NOT_VALID_FOR_PROGRESSIVE_PLAYBACK:
                sb.append("El servidor no permite reproducir este archivo por partes; algunos servidores "
                        + "solo aceptan descargarlo entero.");
                break;
            case Integer.MIN_VALUE:
                sb.append("El reproductor de Android no dio ningún detalle: devolvió el código genérico "
                        + "-2147483648 (no es un código HTTP ni un motivo real).");
                break;
            default:
                sb.append("El reproductor informó del código ").append(extra)
                        .append(", que no identifica por sí solo la causa.");
                break;
        }
        sb.append(" (what=").append(what).append(", extra=").append(extra).append(')');
        return sb.toString();
    }

    /** Motivo real de una respuesta HTTP, en lenguaje sencillo. */
    static String httpReason(int code) {
        if (code == 400) return "la petición está mal formada (400)";
        if (code == 401) return "el servidor pide usuario y contraseña (401)";
        if (code == 403) return "el proveedor rechazó la petición (403): normalmente faltan las "
                + "cabeceras User-Agent o Referer correctas";
        if (code == 404) return "esa dirección ya no existe en el servidor (404)";
        if (code == 405) return "el servidor no acepta peticiones de ese tipo (405)";
        if (code == 410) return "el enlace se retiró del servidor (410)";
        if (code == 416) return "el servidor rechazó la petición por el rango pedido (416)";
        if (code == 429) return "el servidor está limitando las peticiones: demasiadas peticiones "
                + "seguidas (429)";
        if (code == 500) return "el servidor del proveedor falló por dentro (500)";
        if (code == 502 || code == 503 || code == 504) {
            return "el servidor del proveedor no está disponible en este momento (" + code + ")";
        }
        if (code >= 400 && code < 500) return "el servidor respondió con el código " + code;
        if (code >= 500) return "el servidor respondió con el código " + code;
        return "el servidor respondió con el código " + code;
    }

    /** true si el código HTTP se puede resolver simplemente volviendo a intentarlo. */
    static boolean retryable(int code) {
        return code == 408 || code == 429 || code >= 500;
    }

    /** true si el fallo apunta a la falta de cabeceras del proveedor. */
    static boolean looksLikeAuthorization(int code) {
        return code == 401 || code == 403;
    }

    /**
     * Explicación de un fallo de red al pedir el manifiesto o los trozos, con el
     * motivo real y las cabeceras que se enviaron (sin datos privados).
     */
    static String describeNetwork(Throwable e, java.util.Map<String, String> headers) {
        StringBuilder sb = new StringBuilder();
        if (e instanceof Http) {
            Http http = (Http) e;
            sb.append(httpReason(http.code));
            if (looksLikeAuthorization(http.code)) {
                sb.append(". Escribe el User-Agent y el Referer que use tu proveedor en"
                        + " AJUSTES DEL REPRODUCTOR y vuelve a intentarlo.");
            } else if (http.code == 404 || http.code == 410) {
                sb.append(". Comprueba que el enlace del canal siga siendo válido en tu proveedor.");
            } else if (retryable(http.code)) {
                sb.append(". Es un fallo temporal del servidor: vuelve a intentarlo en unos segundos.");
            }
        } else {
            sb.append(Net.describe(e));
        }
        sb.append(".\n\nCabeceras enviadas: ").append(Net.describeHeaders(headers));
        return sb.toString();
    }

    /** Tipo de contenido legible para el registro (por ejemplo video/mp4). */
    static String normalizeType(String contentType) {
        if (contentType == null) return "";
        String clean = contentType.trim().toLowerCase(Locale.US);
        int semi = clean.indexOf(';');
        if (semi > 0) clean = clean.substring(0, semi).trim();
        return clean;
    }
}
