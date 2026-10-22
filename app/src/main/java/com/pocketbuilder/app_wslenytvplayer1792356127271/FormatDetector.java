package com.pocketbuilder.app_wslenytvplayer1792356127271;

import java.util.Locale;

/**
 * Detección del tipo de contenido por extensión de la URL y por el tipo MIME
 * informado por el servidor. El tipo elegido a mano por el usuario siempre gana.
 */
public final class FormatDetector {

    static final String AUTO = "Automático";
    public static final String HLS = "HLS (.m3u8)";
    static final String TS = "MPEG-TS (.ts)";
    public static final String DASH = "DASH (.mpd)";
    static final String MP4 = "MP4 (.mp4)";
    static final String WEBM = "WebM (.webm)";
    static final String OTHER = "Otro (directo)";

    private FormatDetector() {
    }

    static String[] options() {
        return new String[]{AUTO, HLS, TS, DASH, MP4, WEBM, OTHER};
    }

    static boolean isAuto(String label) {
        return label == null || label.length() == 0 || AUTO.equals(label);
    }

    public static boolean isDash(String label) {
        return DASH.equals(label);
    }

    /** Detección por extensión. */
    static String fromUrl(String url) {
        String path = url == null ? "" : url.toLowerCase(Locale.US);
        int q = path.indexOf('?');
        if (q > 0) path = path.substring(0, q);
        int h = path.indexOf('#');
        if (h > 0) path = path.substring(0, h);
        if (path.endsWith(".m3u8") || path.endsWith(".m3u")) return HLS;
        if (path.endsWith(".mpd")) return DASH;
        if (path.endsWith(".ts") || path.endsWith(".mts") || path.endsWith(".m2ts")) return TS;
        if (path.endsWith(".mp4") || path.endsWith(".m4v") || path.endsWith(".mov")) return MP4;
        if (path.endsWith(".webm") || path.endsWith(".mkv")) return WEBM;
        return OTHER;
    }

    /** Detección por el tipo de contenido (Content-Type) del servidor. */
    static String fromContentType(String contentType) {
        if (contentType == null) return OTHER;
        String ct = contentType.toLowerCase(Locale.US);
        if (ct.indexOf("mpegurl") >= 0 || ct.indexOf("x-mpegurl") >= 0 || ct.indexOf("vnd.apple.mpegurl") >= 0) return HLS;
        if (ct.indexOf("dash+xml") >= 0) return DASH;
        if (ct.indexOf("mp2t") >= 0) return TS;
        if (ct.indexOf("webm") >= 0) return WEBM;
        if (ct.indexOf("mp4") >= 0 || ct.indexOf("quicktime") >= 0) return MP4;
        return OTHER;
    }

    /**
     * Afina el tipo cuando la extensión no dice nada: primero por el tipo MIME,
     * y como último recurso se deja "Otro (directo)".
     */
    static String refine(String url, String contentType) {
        String byUrl = fromUrl(url);
        if (!OTHER.equals(byUrl)) return byUrl;
        String byMime = fromContentType(contentType);
        if (!OTHER.equals(byMime)) return byMime;
        return OTHER;
    }

    /** Una URL con pinta de archivo de lista (M3U/M3U8). */
    static boolean looksLikePlaylistFile(String url) {
        String u = url == null ? "" : url.toLowerCase(Locale.US);
        return u.endsWith(".m3u") || u.endsWith(".m3u8");
    }

    static String explain(String type) {
        if (HLS.equals(type)) return "HLS: lista de segmentos .m3u8 (se reproduce como vídeo)";
        if (TS.equals(type)) return "MPEG-TS: flujo de transporte .ts";
        if (DASH.equals(type)) return "DASH (.mpd): manifiesto de segmentos; lo reproduce Media3 / ExoPlayer";
        if (MP4.equals(type)) return "MP4: archivo progresivo";
        if (WEBM.equals(type)) return "WebM: archivo progresivo";
        return "Tipo directo: se enviará el enlace al reproductor del sistema";
    }

    /**
     * Texto de ayuda del formato DASH. LENY TV lo reproduce con su motor DASH
     * interno (lectura del manifiesto, descarga de trozos y unión de audio y
     * vídeo), así que ya no se avisa de que el formato no sea compatible.
     */
    static String dashHelp(String url) {
        return "El formato DASH (.mpd) lo reproduce el motor DASH interno de LENY TV: lee el manifiesto,"
                + " elige la calidad y va pidiendo los trozos del canal (uniendo la pista de audio con la"
                + " de vídeo cuando van separadas).\n\nEnlace: " + shorten(url);
    }

    static String shorten(String url) {
        if (url == null) return "";
        if (url.length() <= 70) return url;
        return url.substring(0, 40) + "…" + url.substring(url.length() - 25);
    }
}
