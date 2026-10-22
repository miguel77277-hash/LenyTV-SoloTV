package com.pocketbuilder.app_wslenytvplayer1792356127271;

import java.util.Map;
import java.util.TreeMap;

/**
 * Una entrada de una lista IPTV o un enlace de vídeo suelto.
 *
 * Además de su nombre, su logo y su categoría, guarda TODO lo que la lista dice
 * de ese canal en concreto:
 *
 *  - Las cabeceras HTTP de su bloque (#EXTVLCOPT, #KODIPROP, #EXTHTTP y los
 *    parámetros detrás de "|": User-Agent, Referer, Origin, Cookie y otras).
 *  - De qué directiva salió cada cabecera, para poder explicarlo en el
 *    diagnóstico.
 *  - Las directivas de licencia (inputstream.adaptive.license_type,
 *    license_key y stream_headers). Media3 aplica ClearKey cuando license_type
 *    lo declara (o la clave es claramente inline KID:KEY).
 *
 * La clave de licencia nunca se muestra en pantalla ni en el diagnóstico.
 */
final class Channel {
    final String name;
    final String url;
    final String logo;
    /**
     * Apartado del canal. Es el único dato que puede cambiar después de leer la
     * lista: si una entrada viene sin apartado pero es el mismo canal que otra
     * que sí lo tiene, se coloca en ese apartado (ver
     * {@link #adoptCategory(String)}).
     */
    String category;
    /**
     * Direcciones ALTERNATIVAS del mismo canal (REVISIÓN 53). Muchas listas
     * ponen varias direcciones detrás de un solo #EXTINF: la misma emisión en
     * otro servidor, una «opción 2», un «index.m3u8» o el bloque de Kodi. Antes
     * cada una se mostraba como un canal nuevo y sin apartado, y por eso salía un
     * montón de entradas sueltas en «Sin categoría» con nombres como
     * «index.m3u8» o «3420». Ahora se guardan aquí y NO se muestran aparte.
     */
    final java.util.ArrayList<String> alternatives = new java.util.ArrayList<String>();
    /** Cabeceras propias del canal (vacío si la lista no indica ninguna). */
    final Map<String, String> headers;
    /** De dónde salió cada cabecera: "#EXTVLCOPT:http-user-agent", "después de \"|\""... */
    final Map<String, String> headerSources;
    /** User-Agent del canal (comodidad; ya está dentro de {@link #headers}). */
    final String userAgent;
    /** Referer del canal (comodidad; ya está dentro de {@link #headers}). */
    final String referer;
    /** Sistema de DRM declarado por la lista (vacío si no declara ninguno). */
    final String licenseType;
    /** Clave declarada por la lista. Se guarda, pero NUNCA se muestra. */
    final String licenseKey;
    /** Cabeceras declaradas para la licencia (inputstream.adaptive.stream_headers). */
    final String licenseStreamHeaders;

    Channel(String name, String url, String logo, String category) {
        this(name, url, logo, category, null, null, "", "", "");
    }

    Channel(String name, String url, String logo, String category,
            String userAgent, String referer) {
        this(name, url, logo, category, single(userAgent, referer), null, "", "", "");
    }

    Channel(String name, String url, String logo, String category,
            Map<String, String> headers, Map<String, String> headerSources,
            String licenseType, String licenseKey, String licenseStreamHeaders) {
        this.name = name == null ? "" : name.trim();
        this.url = url == null ? "" : url.trim();
        this.logo = logo == null ? "" : logo.trim();
        this.category = cleanCategory(category);
        this.headers = Headers.copy(headers);
        this.headerSources = headerSources == null
                ? new TreeMap<String, String>() : new TreeMap<String, String>(headerSources);
        String ua = this.headers.get("User-Agent");
        String ref = this.headers.get("Referer");
        this.userAgent = ua == null ? "" : ua;
        this.referer = ref == null ? "" : ref;
        this.licenseType = licenseType == null ? "" : licenseType.trim();
        this.licenseKey = licenseKey == null ? "" : licenseKey.trim();
        this.licenseStreamHeaders = licenseStreamHeaders == null ? "" : licenseStreamHeaders.trim();
    }

    /**
     * Nombre del apartado tal y como se guarda: sin los adornos que muchas listas
     * ponen delante y detrás ("### 24/7 ###", "== CINE ==", "· DEPORTES ·") y con
     * los espacios de sobra juntos.
     *
     * Antes esos adornos se guardaban tal cual, así que la lista traía DOS
     * apartados para lo mismo: "### 24/7 ###" y "24/7". Ahora los dos se guardan
     * como "24/7" y en la columna de apartados sale una sola fila con todos sus
     * canales.
     */
    private static String cleanCategory(String raw) {
        if (raw == null) return M3uParser.NO_CATEGORY;
        String text = raw.trim();
        while (text.length() > 0 && "#*·|_=~- ".indexOf(text.charAt(0)) >= 0) {
            text = text.substring(1).trim();
        }
        while (text.length() > 0 && "#*·|_=~- ".indexOf(text.charAt(text.length() - 1)) >= 0) {
            text = text.substring(0, text.length() - 1).trim();
        }
        StringBuilder sb = new StringBuilder(text.length());
        boolean space = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == ' ' || c == '\t') {
                if (!space) sb.append(' ');
                space = true;
            } else {
                sb.append(c);
                space = false;
            }
        }
        String out = sb.toString().trim();
        return out.length() == 0 ? M3uParser.NO_CATEGORY : out;
    }

    private static Map<String, String> single(String userAgent, String referer) {
        Map<String, String> map = new TreeMap<String, String>();
        Headers.put(map, "User-Agent", userAgent);
        Headers.put(map, "Referer", referer);
        return map;
    }

    /**
     * Guarda una dirección alternativa del mismo canal.
     * Devuelve false si esa dirección ya estaba (la principal o una repetida).
     */
    boolean addAlternative(String alternativeUrl) {
        if (alternativeUrl == null) return false;
        String clean = alternativeUrl.trim();
        if (clean.length() == 0) return false;
        if (clean.equals(url)) return false;
        if (alternatives.contains(clean)) return false;
        alternatives.add(clean);
        return true;
    }

    /** true si este canal trae direcciones alternativas. */
    boolean hasAlternatives() {
        return !alternatives.isEmpty();
    }

    /** Direcciones del canal, empezando por la principal. */
    java.util.ArrayList<String> allUrls() {
        java.util.ArrayList<String> out = new java.util.ArrayList<String>();
        if (url.length() > 0) out.add(url);
        out.addAll(alternatives);
        return out;
    }

    /**
     * Coloca el canal en su apartado cuando la lista no lo indicaba pero otro
     * canal con el mismo nombre sí lo traía. No cambia un apartado que ya tenga.
     */
    void adoptCategory(String value) {
        if (value == null || value.trim().length() == 0) return;
        if (!M3uParser.NO_CATEGORY.equals(category)) return;
        category = cleanCategory(value);
    }

    boolean hasOwnHeaders() {
        return !headers.isEmpty();
    }

    /** true si la lista declara protección de contenido para este canal. */
    boolean hasLicense() {
        return licenseType.length() > 0 || licenseKey.length() > 0;
    }

    /** Nombres de las cabeceras del canal, para el diagnóstico. */
    String headerNames() {
        return Headers.names(headers);
    }

    /**
     * Resumen del bloque del canal: qué cabeceras trae y de qué directiva salió
     * cada una. No incluye valores, así que también sirve para pegarlo.
     */
    String blockSummary() {
        if (headers.isEmpty()) return "(la lista no indica cabeceras para este canal)";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry == null || entry.getKey() == null) continue;
            if (sb.length() > 0) sb.append(" · ");
            sb.append(entry.getKey());
            String source = headerSources.get(entry.getKey());
            sb.append(" ← ").append(source == null || source.length() == 0 ? "lista" : source);
        }
        return sb.toString();
    }

    /**
     * Resumen de la protección de contenido declarada. Dice qué sistema indica la
     * lista y que el motor NO descifra contenido protegido. Nunca muestra la clave
     * ClearKey se aplica en Media3Engine cuando corresponde.
     */
    String licenseSummary() {
        if (!hasLicense()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("la lista declara DRM");
        if (licenseType.length() > 0) sb.append(" (license_type=").append(licenseType).append(')');
        if (licenseKey.length() > 0) sb.append(" con una clave de licencia (oculta)");
        if (licenseStreamHeaders.length() > 0) sb.append(" y cabeceras propias de licencia");
        sb.append(": el motor de LENY TV solo lo identifica, NO descifra contenido protegido");
        sb.append(" y no se inventan claves. Si la imagen no arranca, es contenido protegido.");
        return sb.toString();
    }

    /** Texto para llevar esta entrada a otra pantalla, sin formularios. */
    String headersSpec() {
        return Headers.toSpec(headers);
    }

    String initial() {
        if (name.length() == 0) return "?";
        return name.substring(0, 1).toUpperCase(java.util.Locale.US);
    }

    @Override
    public String toString() {
        return name + " [" + category + "]";
    }
}
