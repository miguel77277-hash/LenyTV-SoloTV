package com.pocketbuilder.app_wslenytvplayer1792356127271;

import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Lector de listas IPTV (M3U / M3U8) escrito de nuevo desde cero.
 *
 * CÓMO FUNCIONA (procesamiento por líneas, sin expresiones regulares):
 *
 *  1. El texto se recorre línea a línea (saltos \n, \r\n, \r y \f).
 *  2. Cada línea se clasifica: etiqueta (#EXTM3U, #EXTINF, #EXTGRP,
 *     #EXTVLCOPT / #KODIPROP / #EXTHTTP, #EXT-X-..., #PLAYLIST) o dirección.
 *  3. Los atributos de #EXTINF (tvg-name, tvg-logo, group-title...) se leen con
 *     un recorrido propio del texto que admite comillas dobles o simples,
 *     valores sin comillas y espacios alrededor del "=".
 *     Ese recorrido tiene garantizado el avance: cada vuelta del bucle consume
 *     siempre al menos un carácter, así que NUNCA puede quedarse colgado.
 *  4. El BLOQUE COMPLETO del canal se interpreta antes de leer su dirección:
 *       #EXTINF          → nombre, logo y categoría
 *       #EXTGRP          → categoría
 *       #EXTVLCOPT       → http-user-agent, http-referrer/referer, http-origin,
 *                          http-cookie y cualquier http-<cabecera>
 *       #KODIPROP        → inputstream.adaptive.stream_headers (cabeceras),
 *                          license_type, license_key, y otras propiedades
 *       #EXTHTTP         → JSON con cabeceras: {"User-Agent":"...", ...}
 *       url|A=B&C=D      → parámetros de cabecera detrás de "|"
 *  5. La línea de dirección cierra la entrada: se le asocian el nombre, el logo,
 *     la categoría y TODAS esas cabeceras, y se resuelve si es un enlace relativo
 *     (usando la dirección de la lista).
 *
 * Las directivas de licencia se IDENTIFICAN y se guardan, pero el motor DASH de
 * LENY TV no descifra contenido protegido: aquí no se simula compatibilidad con
 * ClearKey ni con ningún otro DRM.
 *
 * El lector solo EXTRAE Y VALIDA las entradas: no descarga nada por su cuenta y
 * no reproduce nada. La descarga la hace {@link Net} y la reproducción el
 * reproductor ({@link PlayerActivity}); aquí solo se lee el texto recibido.
 *
 * Distingue dos cosas que no son lo mismo:
 *  - Lista de canales IPTV: cada #EXTINF es un canal con su dirección.
 *  - Lista de reproducción HLS (.m3u8 de un solo vídeo): sus direcciones son
 *    los trozos o las calidades del mismo vídeo, no canales.
 *
 * La lista original NUNCA se modifica: solo se lee.
 */
final class M3uParser {

    static final String NO_CATEGORY = "Sin categoría";

    /** Tope de entradas admitidas de una sola lista (evita agotar la memoria). */
    static final int MAX_CHANNELS = 50000;

    /** Longitud máxima que se lee de una sola línea (protege de textos dañados). */
    private static final int MAX_LINE_LENGTH = 60000;

    private M3uParser() {
    }

    /** Resultado de leer una lista. */
    static final class Result {
        final ArrayList<Channel> channels = new ArrayList<Channel>();
        /** Avisos concretos de lo que se encontró durante la lectura. */
        final ArrayList<String> notes = new ArrayList<String>();

        /** true si lo recibido es un vídeo HLS y no una lista de canales. */
        boolean mediaPlaylist;
        String title = "";
        /** Líneas #EXTINF encontradas. */
        int extinfCount;
        /** Líneas de dirección encontradas. */
        int urlCount;
        /** Etiquetas #EXT-X-... vistas. */
        int hlsTags;
        /** Etiquetas que demuestran que el texto es un HLS de verdad. */
        int hlsStructural;
        /**
         * Etiquetas que SOLO aparecen en una lista de reproducción de vídeo HLS
         * (#EXT-X-STREAM-INF, #EXT-X-TARGETDURATION, #EXT-X-MEDIA-SEQUENCE,
         * #EXT-X-ENDLIST, #EXT-X-PLAYLIST-TYPE). Son la única prueba que se
         * acepta para declarar que el texto es un vídeo y no una lista de canales.
         */
        int hlsStrong;
        int hlsStreamInf;
        int hlsTargetDuration;
        int hlsMediaSequence;
        int hlsEndlist;
        int hlsPlaylistType;
        /** Entradas #EXTINF que traían un nombre propio detrás de la coma. */
        int namedEntries;
        /** Entradas #EXTINF con duración numérica y con duración mayor que cero. */
        int numericDurations;
        int positiveDurations;
        /** true si al final se conservaron los canales pese a parecer una lista HLS. */
        boolean keptChannels;
        /** Líneas que no eran ni etiqueta ni dirección (se ignoraron). */
        int skippedLines;
        /** Líneas de una página web (se ignoraron). */
        int htmlLines;
        /** Entradas repetidas que se mostraron una sola vez. */
        int duplicates;
        /** Líneas de atributos que continuaban el #EXTINF anterior. */
        int continuationLines;
        /** Claves ya leídas (dirección + nombre + categoría), para no duplicar. */
        private final java.util.HashSet<String> seenKeys = new java.util.HashSet<String>();
        /** Entradas #EXTINF a las que les faltaba la línea de dirección. */
        int entriesWithoutUrl;
        /**
         * Direcciones ALTERNATIVAS del mismo canal (REVISIÓN 53): iban detrás de
         * la dirección principal del bloque y antes se mostraban como canales
         * sueltos sin apartado.
         */
        int alternatives;
        /** Canales sin apartado que se colgaron del apartado de su mismo nombre. */
        int adoptedGroups;
        /** Líneas de directiva escritas sin la «#» de delante (EXTINF:, KODIPROP:...). */
        int directivesWithoutHash;
        /** true si alguna entrada traía datos de canal (logo, grupo, cabeceras). */
        boolean channelAttributes;
        /** true si la lista tenía más entradas de las admitidas. */
        boolean capped;
        /** Directivas de cabeceras encontradas (#EXTVLCOPT, #KODIPROP, #EXTHTTP). */
        int headerDirectives;
        /** Canales con cabeceras propias leídas de su bloque. */
        int channelsWithHeaders;
        /** Canales que declaran DRM en su bloque. */
        int channelsWithLicense;
        String firstLine = "";

        int categories() {
            ArrayList<String> seen = new ArrayList<String>();
            for (int i = 0; i < channels.size(); i++) {
                String category = channels.get(i).category;
                if (!seen.contains(category)) seen.add(category);
            }
            return seen.size();
        }

        String summary() {
            StringBuilder sb = new StringBuilder();
            sb.append(channels.size()).append(" canales");
            sb.append(", ").append(categories()).append(" categorías");
            sb.append(", ").append(extinfCount).append(" líneas #EXTINF");
            sb.append(", ").append(urlCount).append(" direcciones");
            if (hlsTags > 0) sb.append(", ").append(hlsTags).append(" etiquetas #EXT-X-");
            if (hlsStrong > 0) sb.append(" (").append(hlsStrong).append(" sólo de vídeo HLS)");
            if (namedEntries > 0) sb.append(", ").append(namedEntries).append(" entradas con nombre");
            if (channelsWithHeaders > 0) sb.append(", ").append(channelsWithHeaders).append(" con cabeceras propias");
            if (channelsWithLicense > 0) sb.append(", ").append(channelsWithLicense).append(" con DRM declarado");
            if (alternatives > 0) sb.append(", ").append(alternatives).append(" direcciones alternativas agrupadas");
            if (duplicates > 0) sb.append(", ").append(duplicates).append(" entradas repetidas");
            if (continuationLines > 0) sb.append(", ").append(continuationLines).append(" líneas de atributos");
            if (keptChannels) sb.append(" · se conservaron los canales");
            if (capped) sb.append(" · se alcanzó el tope de ").append(MAX_CHANNELS).append(" entradas");
            return sb.toString();
        }

        /**
         * Motivo concreto y en lenguaje sencillo de por qué no hay canales.
         * Devuelve "" cuando sí se han leído canales.
         */
        String reason() {
            if (channels.size() > 0) return "";
            if (mediaPlaylist) {
                return "ese enlace es un vídeo HLS, no una lista de canales: trae las etiquetas propias"
                        + " de una lista de vídeo (" + hlsProof() + "), así que sus direcciones son los"
                        + " trozos del mismo vídeo. Pulsa REPRODUCIR EL ENLACE para verlo";
            }
            if (htmlLines > 0 && extinfCount == 0) {
                return "lo que respondió el servidor parece una página web, no una lista de canales";
            }
            if (extinfCount == 0 && urlCount == 0) {
                return "el texto recibido no contiene ninguna línea #EXTINF ni ninguna dirección";
            }
            if (extinfCount > 0 && urlCount == 0) {
                return "hay " + extinfCount + " líneas #EXTINF pero ninguna línea con la dirección del canal";
            }
            if (entriesWithoutUrl > 0) {
                return "las entradas de la lista no llevan dirección: " + entriesWithoutUrl
                        + " de ellas se quedaron sin enlace";
            }
            return "no se reconoció ninguna entrada de canal en el texto recibido";
        }

        /** Nombres de las etiquetas de vídeo HLS encontradas, para el motivo. */
        String hlsProof() {
            StringBuilder sb = new StringBuilder();
            if (hlsStreamInf > 0) sb.append("#EXT-X-STREAM-INF, ");
            if (hlsTargetDuration > 0) sb.append("#EXT-X-TARGETDURATION, ");
            if (hlsMediaSequence > 0) sb.append("#EXT-X-MEDIA-SEQUENCE, ");
            if (hlsEndlist > 0) sb.append("#EXT-X-ENDLIST, ");
            if (hlsPlaylistType > 0) sb.append("#EXT-X-PLAYLIST-TYPE, ");
            if (sb.length() == 0) return "etiquetas #EXT-X-";
            return sb.substring(0, sb.length() - 2);
        }
    }

    /** Datos que se van juntando antes de leer la dirección del canal. */
    private static final class Pending {
        String name;
        String logo = "";
        String group;
        final TreeMap<String, String> headers = new TreeMap<String, String>();
        final TreeMap<String, String> headerSources = new TreeMap<String, String>();
        String licenseType = "";
        String licenseKey = "";
        String licenseStreamHeaders = "";
        boolean open;
        /** El bloque ya aportó su dirección: las siguientes son alternativas. */
        boolean urlTaken;

        void reset() {
            name = null;
            logo = "";
            group = null;
            headers.clear();
            headerSources.clear();
            licenseType = "";
            licenseKey = "";
            licenseStreamHeaders = "";
            open = false;
            urlTaken = false;
        }

        /** Guarda una cabecera del bloque del canal, con su origen. */
        void putHeader(String headerName, String headerValue, String source) {
            String key = Headers.canonical(headerName);
            if (key.length() == 0) return;
            String clean = Headers.value(headerValue);
            if (clean.length() == 0) return;
            headers.put(key, clean);
            headerSources.put(key, source == null ? "" : source);
        }
    }

    /** Datos leídos de la parte de atributos de un #EXTINF. */
    private static final class Extinf {
        /** Nombre de la guía (tvg-name) o, si no hay, el nombre corto. */
        String name = "";
        /** Identificador de la guía (tvg-id). NO es el nombre del canal. */
        String id = "";
        String logo = "";
        String group = "";
        boolean anyAttribute;
    }

    // ------------------------------------------------------------------ lectura

    static Result parse(String text) {
        return parse(text, null);
    }

    /**
     * Lee una lista completa.
     *
     * @param text    contenido del archivo o de la descarga.
     * @param baseUrl dirección de la lista (para resolver enlaces relativos).
     *                Puede ser null.
     */
    static Result parse(String text, String baseUrl) {
        Result result = new Result();
        if (text == null || text.length() == 0) {
            result.notes.add("no se recibió ningún texto que leer");
            return result;
        }

        Pending pending = new Pending();
        boolean header = false;

        // El texto se recorre con un lector de líneas que entiende TODOS los
        // saltos: \n, \r\n, \r solo (listas guardadas desde un Mac o generadas
        // por algunos paneles), \f y los separadores Unicode. Antes solo se
        // cortaba por \n: una lista con saltos \r llegaba entera como una sola
        // línea y no se reconocía ningún canal.
        LineScanner scanner = new LineScanner(text);
        String raw;
        while ((raw = scanner.next()) != null) {
            if (raw.length() > MAX_LINE_LENGTH) raw = raw.substring(0, MAX_LINE_LENGTH);

            String line = trimAll(raw);
            if (line.length() == 0) continue;
            if (result.firstLine.length() == 0) result.firstLine = clip(line, 160);

            // REVISIÓN 53: algunas listas escriben las directivas SIN la «#» de
            // delante («EXTINF:-1 ...», «KODIPROP:inputstream.adaptive...»).
            // Antes esas líneas se tomaban por direcciones de vídeo y acababan
            // como canales llamados «EXTINF:-1» o «KODIPROP:inputstream.adapti…»
            // amontonados en «Sin categoría». Aquí se tratan como la directiva
            // que son.
            char first = line.charAt(0);
            if (first != '#' && missingHashDirective(line)) {
                line = "#" + line;
                first = '#';
                result.directivesWithoutHash++;
            }
            if (first == '#') {
                String upper = line.toUpperCase(Locale.US);
                if (upper.startsWith("#EXTM3U")) {
                    header = true;
                } else if (upper.startsWith("#EXTINF")) {
                    if (pending.open && !pending.urlTaken) result.entriesWithoutUrl++;
                    // Orden Kodi: #KODIPROP (licencia) ANTES del #EXTINF. Si el
                    // bloque anterior ya tenia URL o aun no habia EXTINF, esas
                    // directivas son del canal que empieza ahora: se conservan.
                    boolean carryLicense = !pending.open || pending.urlTaken;
                    String keepLicenseType = carryLicense ? pending.licenseType : "";
                    String keepLicenseKey = carryLicense ? pending.licenseKey : "";
                    String keepLicenseStreamHeaders = carryLicense ? pending.licenseStreamHeaders : "";
                    pending.reset();
                    pending.open = true;
                    if (carryLicense) {
                        pending.licenseType = keepLicenseType;
                        pending.licenseKey = keepLicenseKey;
                        pending.licenseStreamHeaders = keepLicenseStreamHeaders;
                    }
                    applyExtinf(pending, line, result);
                } else if (upper.startsWith("#EXTGRP")) {
                    String group = valueAfterColon(line);
                    if (group.length() > 0) {
                        pending.group = group;
                        result.channelAttributes = true;
                    }
                } else if (upper.startsWith("#EXTVLCOPT") || upper.startsWith("#EXTVLC")
                        || upper.startsWith("#KODIPROP") || upper.startsWith("#EXTHTTP")) {
                    readHeaderDirective(pending, line, result);
                } else if (upper.startsWith("#EXT-X-")) {
                    result.hlsTags++;
                    if (isHlsStructureTag(upper)) result.hlsStructural++;
                    // Solo estas etiquetas prueban que el texto es un vídeo HLS.
                    if (upper.startsWith("#EXT-X-STREAM-INF")) {
                        result.hlsStreamInf++;
                        result.hlsStrong++;
                    } else if (upper.startsWith("#EXT-X-TARGETDURATION")) {
                        result.hlsTargetDuration++;
                        result.hlsStrong++;
                    } else if (upper.startsWith("#EXT-X-MEDIA-SEQUENCE")) {
                        result.hlsMediaSequence++;
                        result.hlsStrong++;
                    } else if (upper.startsWith("#EXT-X-ENDLIST")) {
                        result.hlsEndlist++;
                        result.hlsStrong++;
                    } else if (upper.startsWith("#EXT-X-PLAYLIST-TYPE")) {
                        result.hlsPlaylistType++;
                        result.hlsStrong++;
                    }
                } else if (upper.startsWith("#PLAYLIST")) {
                    String title = valueAfterColon(line);
                    if (title.length() > 0 && result.title.length() == 0) result.title = title;
                }
                continue;
            }

            if (first == '<') {
                // Una página web no es una lista: sus líneas se ignoran.
                result.htmlLines++;
                continue;
            }

            // Algunas listas reparten los atributos del canal en varias líneas:
            //   #EXTINF:-1 tvg-id="dep1"
            //        group-title="Deportes",Deportes Uno
            // Esas líneas son METADATOS, no direcciones: se aplican al canal que
            // está abierto y nunca se confunden con un enlace de reproducción.
            if (pending.open && looksLikeAttributeLine(line)) {
                applyContinuation(pending, line, result);
                result.continuationLines++;
                continue;
            }

            // Línea de dirección: cierra la entrada que estuviera pendiente y, si
            // lleva parámetros detrás de "|", se leen como cabeceras del canal.
            String url = readUrlLine(line, pending);

            if (url.length() == 0) {
                result.skippedLines++;
                continue;
            }
            if (!looksLikeAddress(url)) {
                result.skippedLines++;
                if (result.notes.size() < 6) {
                    result.notes.add("se ignoró una línea que no es una dirección: " + clip(url, 60));
                }
                continue;
            }

            url = resolve(baseUrl, url);
            result.urlCount++;
            if (result.channels.size() >= MAX_CHANNELS) {
                result.capped = true;
                pending.reset();
                continue;
            }
            // REVISIÓN 53: el bloque ya tenía su dirección y llega otra dirección
            // sin un #EXTINF nuevo por medio: es una ALTERNATIVA del mismo canal
            // (otro servidor, «opción 2», index.m3u8, el conjunto de Kodi...). Se
            // guarda con su canal y NO se añade como canal suelto.
            if (pending.open && pending.urlTaken) {
                Channel owner = result.channels.isEmpty() ? null
                        : result.channels.get(result.channels.size() - 1);
                if (owner != null && owner.addAlternative(url)) result.alternatives++;
                continue;
            }
            String name = pending.name != null && pending.name.length() > 0 ? pending.name : guessName(url);
            String category = pending.group == null || pending.group.length() == 0
                    ? NO_CATEGORY : pending.group;
            // Duplicados accidentales: hay listas que repiten la misma entrada
            // (a veces por una fusión de dos listas). Se conserva la primera y se
            // cuentan las demás, en lugar de mostrar el mismo canal varias veces.
            String dedupeKey = url + '\u0000' + name + '\u0000' + category;
            if (!result.seenKeys.add(dedupeKey)) {
                result.duplicates++;
                pending.reset();
                continue;
            }
            result.channels.add(new Channel(name, url, pending.logo, pending.group,
                    pending.headers, pending.headerSources,
                    pending.licenseType, pending.licenseKey, pending.licenseStreamHeaders));
            if (!pending.headers.isEmpty()) result.channelsWithHeaders++;
            if (pending.licenseType.length() > 0 || pending.licenseKey.length() > 0) {
                result.channelsWithLicense++;
            }
            // El bloque SIGUE abierto: si detrás vienen más direcciones son
            // alternativas de este mismo canal, y sus #EXTVLCOPT / #KODIPROP
            // siguen siendo de este canal.
            pending.urlTaken = true;
        }

        if (pending.open && !pending.urlTaken) result.entriesWithoutUrl++;
        // Entradas sin apartado que son el mismo canal que otra que sí lo tiene:
        // se colocan en su apartado en vez de amontonarse en «Sin categoría».
        adoptGroups(result);
        if (!header && (result.extinfCount > 0 || result.urlCount > 0)) {
            result.notes.add("la lista no empezaba por #EXTM3U, se ha leído igualmente");
        }
        if (result.skippedLines > 0) {
            result.notes.add("se ignoraron " + result.skippedLines + " líneas sin dirección válida");
        }
        if (result.alternatives > 0) {
            result.notes.add(result.alternatives + " dirección(es) alternativas de un mismo canal se guardaron"
                    + " con su canal: son la misma emisión en otro enlace y no aparecen como canales aparte");
        }
        if (result.adoptedGroups > 0) {
            result.notes.add(result.adoptedGroups + " canal(es) sin apartado se colocaron en el apartado de su mismo"
                    + " nombre en lugar de quedar en «Sin categoría»");
        }
        if (result.directivesWithoutHash > 0) {
            result.notes.add(result.directivesWithoutHash + " línea(s) de directiva venían sin la «#» delante"
                    + " (EXTINF:, KODIPROP:...) y se leyeron como directiva, no como canal");
        }
        if (result.headerDirectives > 0) {
            result.notes.add("se leyeron " + result.headerDirectives + " directivas de cabeceras"
                    + " (#EXTVLCOPT, #KODIPROP, #EXTHTTP o parámetros tras \"|\")");
        }
        if (result.channelsWithHeaders > 0) {
            result.notes.add(result.channelsWithHeaders + " canal(es) traen sus propias cabeceras HTTP"
                    + " (User-Agent, Referer, Origin, Cookie...): se aplican a ese canal antes de reproducir");
        }
        if (result.duplicates > 0) {
            result.notes.add(result.duplicates + " entrada(s) repetidas en la lista: se muestran una sola vez");
        }
        if (result.continuationLines > 0) {
            result.notes.add(result.continuationLines + " línea(s) de atributos continuaban el #EXTINF anterior"
                    + " y se aplicaron a su canal (no se tomaron por direcciones)");
        }
        if (result.entriesWithoutUrl > 0) {
            result.notes.add(result.entriesWithoutUrl + " entrada(s) de la lista no llevan dirección"
                    + " y no se han podido añadir");
        }
        if (result.channelsWithLicense > 0) {
            result.notes.add(result.channelsWithLicense + " canal(es) declaran DRM"
                    + " (inputstream.adaptive.license_type / license_key): el motor DASH de LENY TV solo lo"
                    + " identifica y NO descifra contenido protegido");
        }

        // ¿Es un vídeo HLS o una lista de canales?
        //
        // REGLA ESTRICTA: solo se declara "vídeo" cuando hay PRUEBAS
        // ESTRUCTURALES de que lo es, es decir, cuando aparecen etiquetas que
        // únicamente existen en una lista de reproducción de vídeo HLS
        // (#EXT-X-STREAM-INF, #EXT-X-TARGETDURATION, #EXT-X-MEDIA-SEQUENCE,
        // #EXT-X-ENDLIST o #EXT-X-PLAYLIST-TYPE) y además la lista no trae
        // ningún dato de canal (tvg-name, tvg-logo, group-title, cabeceras...).
        //
        // Antes bastaba con que todas las duraciones de los #EXTINF fueran
        // números positivos (algo habitual en listas de películas y series) para
        // vaciar el catálogo entero con el aviso "es un vídeo HLS". Eso dejaba la
        // cuadrícula vacía aunque la lista se hubiera leído perfectamente.
        boolean strongHls = result.hlsStrong > 0;
        boolean media = strongHls && !result.channelAttributes;
        if (media && result.namedEntries >= 2 && result.channels.size() >= 2) {
            // Aunque haya etiquetas HLS, si la lista trae varias entradas con
            // nombre propio se conservan como canales: es más grave perder un
            // catálogo entero que mostrar de más.
            media = false;
            result.keptChannels = true;
            result.notes.add("la lista trae " + result.namedEntries
                    + " entradas con nombre propio: se conservan como canales y no se trata como un vídeo");
        }
        if (!media && result.extinfCount > 0 && result.numericDurations == result.extinfCount
                && result.positiveDurations == result.extinfCount) {
            // Caso típico de listas de películas y series: todas las entradas
            // llevan duración positiva. Eso NO significa que sea un vídeo: cada
            // entrada sigue siendo un canal con su dirección.
            result.notes.add("todas las entradas traen duración (" + result.positiveDurations
                    + "): se tratan como canales de la lista, no como trozos de un mismo vídeo");
        }
        result.mediaPlaylist = media;
        if (media) {
            if (!result.channels.isEmpty()) {
                result.notes.add("las " + result.channels.size() + " direcciones leídas son trozos del mismo vídeo HLS");
            }
            result.channels.clear();
        }
        return result;
    }

    /**
     * Busca en la lista el bloque del canal cuya dirección coincide con la pedida
     * y devuelve la entrada con sus cabeceras y su licencia ya aplicadas.
     *
     * Se usa al abrir un canal desde la cuadrícula: así las cabeceras propias del
     * canal están disponibles ANTES del primer intento de reproducción y no se
     * arranca con las cabeceras generales. Recorre el texto una sola vez y solo
     * guarda el bloque en curso, sin construir la lista entera (es rápido y no
     * consume memoria aunque la lista tenga miles de canales).
     *
     * Devuelve null si no encuentra el bloque o si no trae ningún dato propio.
     */
    static Channel lookupBlock(String text, String targetUrl, String baseUrl) {
        if (text == null || text.length() == 0) return null;
        String target = trimAll(targetUrl);
        if (target.length() == 0) return null;
        Pending pending = new Pending();
        LineScanner scanner = new LineScanner(text);
        String raw;
        while ((raw = scanner.next()) != null) {
            if (raw.length() > MAX_LINE_LENGTH) raw = raw.substring(0, MAX_LINE_LENGTH);
            String line = trimAll(raw);
            if (line.length() == 0) continue;
            char first = line.charAt(0);
            if (first == '#') {
                String upper = line.toUpperCase(Locale.US);
                if (upper.startsWith("#EXTINF")) {
                    boolean carryLicense = !pending.open || pending.urlTaken;
                    String keepLicenseType = carryLicense ? pending.licenseType : "";
                    String keepLicenseKey = carryLicense ? pending.licenseKey : "";
                    String keepLicenseStreamHeaders = carryLicense ? pending.licenseStreamHeaders : "";
                    pending.reset();
                    pending.open = true;
                    if (carryLicense) {
                        pending.licenseType = keepLicenseType;
                        pending.licenseKey = keepLicenseKey;
                        pending.licenseStreamHeaders = keepLicenseStreamHeaders;
                    }
                    applyExtinf(pending, line, null);
                } else if (upper.startsWith("#EXTGRP")) {
                    String group = valueAfterColon(line);
                    if (group.length() > 0) pending.group = group;
                } else if (upper.startsWith("#EXTVLCOPT") || upper.startsWith("#EXTVLC")
                        || upper.startsWith("#KODIPROP") || upper.startsWith("#EXTHTTP")) {
                    readHeaderDirective(pending, line, null);
                }
                continue;
            }
            if (first == '<') continue;
            String url = readUrlLine(line, pending);
            if (url.length() == 0 || !looksLikeAddress(url)) {
                pending.reset();
                continue;
            }
            String resolved = resolve(baseUrl, url);
            if (target.equals(resolved) || target.equalsIgnoreCase(resolved)) {
                if (pending.headers.isEmpty() && pending.licenseType.length() == 0
                        && pending.licenseKey.length() == 0) {
                    // El bloque no aporta nada: se deja el canal tal y como está.
                    return null;
                }
                String name = pending.name != null && pending.name.length() > 0
                        ? pending.name : guessName(resolved);
                return new Channel(name, resolved, pending.logo, pending.group,
                        pending.headers, pending.headerSources,
                        pending.licenseType, pending.licenseKey, pending.licenseStreamHeaders);
            }
            pending.reset();
        }
        return null;
    }

    /**
     * true si la línea es una directiva M3U escrita SIN la «#» de delante.
     *
     * Solo se acepta cuando detrás del nombre de la directiva viene «:», un
     * espacio o un «=». Así una dirección normal («extinf.ts», «playlist.m3u8»)
     * nunca se confunde con una directiva.
     */
    private static boolean missingHashDirective(String line) {
        if (line == null || line.length() == 0) return false;
        String upper = line.toUpperCase(Locale.US);
        if (upper.startsWith("EXT-X-")) return true;
        String[] tokens = {"EXTM3U", "EXTINF", "EXTGRP", "EXTVLCOPT", "EXTVLC", "EXTHTTP", "KODIPROP"};
        for (int i = 0; i < tokens.length; i++) {
            String token = tokens[i];
            if (!upper.startsWith(token)) continue;
            if (upper.length() == token.length()) return true;
            char next = upper.charAt(token.length());
            if (next == ':' || next == ' ' || next == '\t' || next == '=') return true;
        }
        return false;
    }

    /**
     * Coloca en su apartado las entradas que la lista dejó SIN apartado cuando son
     * el mismo canal que otra entrada que sí lo trae (mismo nombre).
     *
     * Es el caso de una lista donde el canal aparece primero con su
     * grupo («ESPN 🔴» dentro de ESPN) y luego otra vez con un enlace más y sin
     * grupo: antes esa segunda entrada se quedaba en «Sin categoría».
     */
    private static void adoptGroups(Result result) {
        if (result == null || result.channels.size() < 2) return;
        java.util.HashMap<String, String> groups = new java.util.HashMap<String, String>();
        for (int i = 0; i < result.channels.size(); i++) {
            Channel channel = result.channels.get(i);
            String key = nameKey(channel.name);
            if (key.length() == 0) continue;
            if (NO_CATEGORY.equals(channel.category)) continue;
            if (!groups.containsKey(key)) groups.put(key, channel.category);
        }
        if (groups.isEmpty()) return;
        for (int i = 0; i < result.channels.size(); i++) {
            Channel channel = result.channels.get(i);
            if (!NO_CATEGORY.equals(channel.category)) continue;
            String category = groups.get(nameKey(channel.name));
            if (category == null || category.length() == 0) continue;
            channel.adoptCategory(category);
            if (!NO_CATEGORY.equals(channel.category)) result.adoptedGroups++;
        }
    }

    /** Nombre comparable: sin destrozo de codificación, en minúsculas y con un solo espacio. */
    private static String nameKey(String name) {
        String text = Names.repair(name);
        if (text.length() == 0) return "";
        StringBuilder sb = new StringBuilder(text.length());
        boolean space = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == ' ' || c == '\t') {
                if (!space) sb.append(' ');
                space = true;
            } else {
                sb.append(Character.toLowerCase(c));
                space = false;
            }
        }
        return sb.toString().trim();
    }

    /**
     * Procesa la línea de dirección: separa los parámetros detrás de "|", los
     * convierte en cabeceras del canal y devuelve la dirección limpia.
     */
    private static String readUrlLine(String line, Pending pending) {
        String url = stripQuotes(line);
        int bar = url.indexOf('|');
        if (bar >= 0) {
            String options = url.substring(bar + 1);
            readHeaderPairs(options, pending, "después de \"|\"");
            url = trimAll(url.substring(0, bar));
        }
        // Alguna lista deja un espacio o un tabulador detrás de la dirección.
        int space = firstSpace(url);
        if (space > 0) url = url.substring(0, space);
        return url;
    }

    /**
     * Lee todas las directivas de un bloque (#EXTVLCOPT, #KODIPROP, #EXTHTTP) y
     * guarda lo que corresponde a cabeceras del canal y a licencia.
     */
    private static void readHeaderDirective(Pending pending, String line, Result result) {
        String upper = line.toUpperCase(Locale.US);
        if (upper.startsWith("#EXTHTTP")) {
            if (result != null) result.headerDirectives++;
            int before = pending.headers.size();
            readHeaderPairs(directiveBody(line), pending, "#EXTHTTP");
            if (result != null && pending.headers.size() > before) result.channelAttributes = true;
            return;
        }
        if (upper.startsWith("#KODIPROP")) {
            if (result != null) result.headerDirectives++;
            int before = pending.headers.size();
            readKodiProperty(pending, line);
            if (result != null && pending.headers.size() > before) result.channelAttributes = true;
            return;
        }
        // #EXTVLCOPT (o #EXTVLC): las opciones de cabecera de VLC.
        if (result != null) result.headerDirectives++;
        String[] pair = pairOf(directiveBody(line));
        if (pair == null) return;
        String headerName = Headers.optionName(pair[0]);
        if (headerName.length() == 0) {
            // Otras opciones de VLC (":network-caching=3000"...) no son cabeceras.
            return;
        }
        pending.putHeader(headerName, decodePercent(pair[1]), "#EXTVLCOPT:" + trimAll(pair[0]));
        if (result != null) result.channelAttributes = true;
    }

    /**
     * Propiedades de #KODIPROP. Las de cabeceras se aplican al canal; las de
     * licencia se identifican y se guardan, pero NO se usan para descifrar.
     */
    private static void readKodiProperty(Pending pending, String line) {
        String[] pair = pairOf(directiveBody(line));
        if (pair == null) return;
        String name = pair[0].toLowerCase(Locale.US);
        String value = pair[1];
        if (name.endsWith("stream_headers") || name.endsWith("manifest_headers")) {
            readHeaderPairs(value, pending, "#KODIPROP:" + trimAll(pair[0]));
            if (name.endsWith("stream_headers")) {
                pending.licenseStreamHeaders = clip(value, 200);
            }
            return;
        }
        if (name.indexOf("license_type") >= 0) {
            pending.licenseType = trimAll(value);
            return;
        }
        if (name.indexOf("license_key") >= 0) {
            // Se guarda para poder IDENTIFICAR que el canal está protegido, pero
            // nunca se muestra ni se envía a ningún sitio: el motor no descifra.
            pending.licenseKey = trimAll(value);
            return;
        }
        // El resto de propiedades de inputstream.adaptive no se usan.
    }

    /**
     * Convierte un trozo de texto con pares nombre=valor en cabeceras del canal.
     * Admite:
     *  - "User-Agent=VLC/3.0.18&Referer=https://servidor/"  (o con ";")
     *  - '{"User-Agent":"VLC/3.0.18","Referer":"https://servidor/"}'
     *  - "User-Agent: VLC/3.0.18"
     * Los valores con %XX se decodifican (muchas listas los escapan).
     */
    private static void readHeaderPairs(String text, Pending pending, String source) {
        if (text == null) return;
        String body = trimAll(text);
        if (body.length() == 0) return;
        if (body.indexOf('{') >= 0) {
            readJsonPairs(body, pending, source);
            return;
        }
        int index = 0;
        int length = body.length();
        while (index < length) {
            while (index < length && isPairSeparator(body.charAt(index))) index++;
            if (index >= length) break;
            int start = index;
            while (index < length && !isPairSeparator(body.charAt(index))) index++;
            String chunk = trimAll(body.substring(start, index));
            if (chunk.length() == 0) continue;
            String[] pair = pairOf(chunk);
            if (pair == null) continue;
            String headerName = Headers.optionName(pair[0]);
            if (headerName.length() == 0) continue;
            pending.putHeader(headerName, decodePercent(pair[1]), source);
        }
    }

    /** Pares "clave":"valor" de un texto con forma de JSON (por ejemplo #EXTHTTP). */
    private static void readJsonPairs(String text, Pending pending, String source) {
        int index = 0;
        int length = text.length();
        while (index < length) {
            int keyStart = -1;
            for (int i = index; i < length; i++) {
                char c = text.charAt(i);
                if (c == '"' || c == '\'') {
                    keyStart = i;
                    break;
                }
            }
            if (keyStart < 0) return;
            char keyQuote = text.charAt(keyStart);
            int keyEnd = text.indexOf(keyQuote, keyStart + 1);
            if (keyEnd < 0) return;
            String key = text.substring(keyStart + 1, keyEnd);
            int separator = -1;
            for (int i = keyEnd + 1; i < length; i++) {
                char c = text.charAt(i);
                if (c == ':' || c == '=') {
                    separator = i;
                    break;
                }
                if (c == '}' || c == ';' || c == '&') break;
            }
            if (separator < 0) {
                index = keyEnd + 1;
                continue;
            }
            int valueStart = -1;
            char valueQuote = 0;
            for (int i = separator + 1; i < length; i++) {
                char c = text.charAt(i);
                if (c == '"' || c == '\'') {
                    valueStart = i;
                    valueQuote = c;
                    break;
                }
                if (c == ',' || c == '}' || c == ';' || c == '&') break;
            }
            if (valueStart < 0) {
                index = separator + 1;
                continue;
            }
            int valueEnd = text.indexOf(valueQuote, valueStart + 1);
            if (valueEnd < 0) return;
            String value = text.substring(valueStart + 1, valueEnd);
            String headerName = Headers.optionName(key);
            if (headerName.length() > 0) {
                pending.putHeader(headerName, decodePercent(value), source);
            }
            index = valueEnd + 1;
        }
    }

    private static boolean isPairSeparator(char c) {
        return c == '&' || c == ';' || c == '|' || c == '\n' || c == '\r';
    }

    /** Decodifica los %XX que usan muchas listas dentro de los valores. */
    private static String decodePercent(String value) {
        if (value == null || value.indexOf('%') < 0) return value;
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '%' && i + 2 < value.length()) {
                int high = hexValue(value.charAt(i + 1));
                int low = hexValue(value.charAt(i + 2));
                if (high >= 0 && low >= 0) {
                    sb.append((char) (high * 16 + low));
                    i += 2;
                    continue;
                }
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private static int hexValue(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    }

    /**
     * Texto que hay detrás de los dos puntos de una directiva
     * ("#EXTVLCOPT:http-user-agent=x" → "http-user-agent=x").
     */
    private static String directiveBody(String line) {
        int colon = line.indexOf(':');
        if (colon < 0 || colon + 1 > line.length()) return "";
        return trimAll(line.substring(colon + 1));
    }

    /**
     * Separa "nombre = valor" (o "nombre: valor") de un trozo de texto.
     * Devuelve null si no hay un nombre utilizable.
     */
    private static String[] pairOf(String text) {
        String body = trimAll(text);
        if (body.length() == 0) return null;
        int equals = body.indexOf('=');
        int colon = body.indexOf(':');
        int cut;
        if (equals >= 0 && (colon < 0 || equals < colon)) cut = equals;
        else cut = colon;
        if (cut <= 0) return null;
        String name = trimAll(body.substring(0, cut));
        String value = trimAll(body.substring(cut + 1));
        if (name.length() == 0) return null;
        return new String[]{name, value};
    }

    /** Aplica los datos de un #EXTINF al bloque en curso. */
    private static void applyExtinf(Pending pending, String line, Result result) {
        if (result != null) {
            result.extinfCount++;
            double duration = readDuration(line);
            if (!Double.isNaN(duration)) {
                result.numericDurations++;
                if (duration > 0d) result.positiveDurations++;
            }
        }
        int comma = firstCommaOutsideQuotes(line);
        String attributeText = comma >= 0 ? line.substring(0, comma) : line;
        String title = comma >= 0 ? stripQuotes(trimAll(line.substring(comma + 1))) : "";
        Extinf extinf = readAttributes(attributeText);
        if (result != null && extinf.anyAttribute) result.channelAttributes = true;
        if (extinf.name.length() > 0) pending.name = extinf.name;
        else if (extinf.id.length() > 0 && (pending.name == null || pending.name.length() == 0)) {
            // Solo si la entrada no da ningún nombre se usa el identificador tvg-id.
            pending.name = extinf.id;
        }
        if (extinf.logo.length() > 0) pending.logo = extinf.logo;
        if (extinf.group.length() > 0) pending.group = extinf.group;
        if (title.length() > 0) {
            pending.name = title;
            if (result != null) result.namedEntries++;
        }
    }

    /**
     * Recorrido rápido del texto para saber si parece de verdad una lista M3U o
     * una lista de direcciones. Se usa para rechazar respuestas equivocadas del
     * servidor (una página web de error, por ejemplo) antes de darlas por buenas.
     */
    static boolean looksLikePlaylist(String text) {
        if (text == null || text.length() == 0) return false;
        LineScanner scanner = new LineScanner(text);
        int looked = 0;
        String raw;
        while ((raw = scanner.next()) != null && looked < 8000) {
            String line = trimAll(raw);
            looked++;
            if (line.length() == 0) continue;
            char first = line.charAt(0);
            if (first == '#') {
                String upper = line.toUpperCase(Locale.US);
                if (upper.startsWith("#EXTM3U") || upper.startsWith("#EXTINF") || upper.startsWith("#EXT-X-")) {
                    return true;
                }
                continue;
            }
            if (first == '<') return false;
            if (looksLikeAddress(stripQuotes(line))) return true;
        }
        return false;
    }

    /** Recorrido de los atributos de un #EXTINF, con avance garantizado. */
    private static Extinf readAttributes(String attributes) {
        Extinf out = new Extinf();
        if (attributes == null) return out;
        int length = attributes.length();
        int i = 0;
        while (i < length) {
            char c = attributes.charAt(i);
            if (isSpace(c) || c == ',') {
                i++;
                continue;
            }
            if (!isKeyChar(c)) {
                // Carácter suelto (por ejemplo el ":" de #EXTINF): se salta.
                i++;
                continue;
            }
            int keyStart = i;
            while (i < length && isKeyChar(attributes.charAt(i))) i++;
            String key = attributes.substring(keyStart, i);

            int j = i;
            while (j < length && isSpace(attributes.charAt(j))) j++;
            if (j < length && attributes.charAt(j) == '=') {
                j++;
                while (j < length && isSpace(attributes.charAt(j))) j++;
                String value;
                if (j < length && (attributes.charAt(j) == '"' || attributes.charAt(j) == '\'')) {
                    char quote = attributes.charAt(j);
                    int end = attributes.indexOf(quote, j + 1);
                    if (end < 0) {
                        value = attributes.substring(j + 1);
                        j = length;
                    } else {
                        value = attributes.substring(j + 1, end);
                        j = end + 1;
                    }
                } else {
                    int end = j;
                    while (end < length && !isSpace(attributes.charAt(end)) && attributes.charAt(end) != ',') end++;
                    value = attributes.substring(j, end);
                    j = end;
                }
                value = trimAll(value);
                out.anyAttribute = true;
                if (key.equalsIgnoreCase("tvg-name") || key.equalsIgnoreCase("name")) {
                    // tvg-name es el nombre de verdad del canal.
                    if (out.name.length() == 0) out.name = value;
                } else if (key.equalsIgnoreCase("tvg-id")) {
                    // tvg-id es un IDENTIFICADOR de la guía, no el nombre: antes se
                    // usaba como nombre y, si venía antes que tvg-name, el canal
                    // aparecía como "Dep1.es" en lugar de con su nombre. Ahora se
                    // guarda aparte y solo se usa si la entrada no da ningún nombre.
                    if (out.id.length() == 0) out.id = value;
                } else if (key.equalsIgnoreCase("tvg-logo") || key.equalsIgnoreCase("logo")
                        || key.equalsIgnoreCase("logo_plus")) {
                    out.logo = value;
                } else if (key.equalsIgnoreCase("group-title") || key.equalsIgnoreCase("group")
                        || key.equalsIgnoreCase("group_name")) {
                    out.group = value;
                }
                // El avance es seguro: j ha pasado al menos el signo "=".
                i = j;
            }
            // Sin "=", i ya ha avanzado al menos un carácter sobre la clave leída.
        }
        return out;
    }

    /**
     * Devuelve {tvg-name, tvg-logo, group-title} de la parte de atributos de un
     * #EXTINF. Se conserva para poder comprobar los valores por separado.
     */
    static String[] readExtinfAttributes(String attributes) {
        Extinf extinf = readAttributes(attributes);
        return new String[]{extinf.name, extinf.logo, extinf.group};
    }

    /** Valor de un atributo suelto (se usa para comprobar resultados). */
    static String attributeOf(String attributes, String key) {
        Extinf extinf = readAttributes(attributes);
        if (key == null) return "";
        if (key.equalsIgnoreCase("tvg-name")) return extinf.name;
        if (key.equalsIgnoreCase("tvg-logo")) return extinf.logo;
        if (key.equalsIgnoreCase("group-title")) return extinf.group;
        return "";
    }

    /**
     * Saca el valor de una opción escrita como clave=valor, clave:valor o
     * "clave":"valor" (se usa en #EXTVLCOPT, #KODIPROP y detrás de "|").
     */
    static String optionValue(String text, String key) {
        if (text == null || key == null || key.length() == 0) return "";
        String lower = text.toLowerCase(Locale.US);
        String wanted = key.toLowerCase(Locale.US);
        int at = lower.indexOf(wanted);
        if (at < 0) return "";
        int i = at + wanted.length();
        int length = text.length();
        while (i < length && isSpace(text.charAt(i))) i++;
        if (i < length && (text.charAt(i) == '"' || text.charAt(i) == '\'')) i++;
        while (i < length && isSpace(text.charAt(i))) i++;
        if (i >= length) return "";
        char separator = text.charAt(i);
        if (separator != '=' && separator != ':') return "";
        i++;
        while (i < length && isSpace(text.charAt(i))) i++;
        if (i >= length) return "";
        char quote = text.charAt(i);
        if (quote == '"' || quote == '\'') {
            int end = text.indexOf(quote, i + 1);
            if (end < 0) end = length;
            return trimAll(text.substring(i + 1, end));
        }
        int end = i;
        while (end < length) {
            char c = text.charAt(end);
            if (c == ';' || c == '&' || c == '|' || c == '}' || c == '\n' || c == '\r') break;
            end++;
        }
        return trimAll(text.substring(i, end));
    }

    /**
     * Número de duración de un #EXTINF (el valor que va detrás de "EXTINF:").
     * Devuelve Double.NaN cuando no es un número.
     */
    private static double readDuration(String line) {
        int colon = line.indexOf(':');
        if (colon < 0) return Double.NaN;
        int length = line.length();
        int i = colon + 1;
        while (i < length && isSpace(line.charAt(i))) i++;
        int start = i;
        while (i < length && i - start < 12) {
            char c = line.charAt(i);
            if ((c >= '0' && c <= '9') || c == '.' || c == '-' || c == '+') i++;
            else break;
        }
        if (i == start) return Double.NaN;
        try {
            return Double.parseDouble(line.substring(start, i));
        } catch (Throwable t) {
            return Double.NaN;
        }
    }

    /** Etiquetas que demuestran que el texto es una lista de reproducción HLS. */
    private static boolean isHlsStructureTag(String upperLine) {
        return upperLine.startsWith("#EXT-X-STREAM-INF")
                || upperLine.startsWith("#EXT-X-TARGETDURATION")
                || upperLine.startsWith("#EXT-X-MEDIA-SEQUENCE")
                || upperLine.startsWith("#EXT-X-ENDLIST")
                || upperLine.startsWith("#EXT-X-KEY")
                || upperLine.startsWith("#EXT-X-MAP")
                || upperLine.startsWith("#EXT-X-PLAYLIST-TYPE")
                || upperLine.startsWith("#EXT-X-I-FRAME-STREAM-INF")
                || upperLine.startsWith("#EXT-X-MEDIA:")
                || upperLine.startsWith("#EXT-X-BYTERANGE")
                || upperLine.startsWith("#EXT-X-DISCONTINUITY");
    }

    /**
     * true si la línea parece una dirección reproducible. Se admite cualquier
     * formato (http, https, rtsp, rtmp, udp, acestream, plugin...) y también
     * rutas relativas como "video/1.ts".
     */
    private static boolean looksLikeAddress(String value) {
        if (value == null || value.length() == 0) return false;
        if (value.indexOf(' ') >= 0 || value.indexOf('\t') >= 0) return false;
        if (hasScheme(value)) return true;
        char first = value.charAt(0);
        if (first == '/') return true;
        if (value.indexOf('/') > 0) return true;
        return hasMediaExtension(value);
    }

    /** true si el texto empieza por un esquema (por ejemplo "https:"). */
    private static boolean hasScheme(String value) {
        int length = value.length();
        for (int i = 0; i < length; i++) {
            char c = value.charAt(i);
            if (c == ':') return i > 0;
            boolean allowed = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '+' || c == '-' || c == '.';
            if (!allowed) return false;
        }
        return false;
    }

    private static boolean hasMediaExtension(String value) {
        String lower = value.toLowerCase(Locale.US);
        int query = lower.indexOf('?');
        if (query > 0) lower = lower.substring(0, query);
        return lower.endsWith(".m3u8") || lower.endsWith(".m3u") || lower.endsWith(".ts")
                || lower.endsWith(".mp4") || lower.endsWith(".m4v") || lower.endsWith(".mpd")
                || lower.endsWith(".webm") || lower.endsWith(".mkv") || lower.endsWith(".m4s");
    }

    /**
     * Convierte una dirección relativa en absoluta usando la dirección de la
     * lista ("video/1.ts" → "https://servidor/path/video/1.ts").
     */
    static String resolve(String baseUrl, String url) {
        if (url == null || url.length() == 0) return url == null ? "" : url;
        if (baseUrl == null || baseUrl.length() == 0) return url;
        if (hasScheme(url)) return url;
        char first = url.charAt(0);
        if (first != '/' && url.indexOf('/') < 0 && !url.startsWith("?")) return url;
        try {
            return new java.net.URL(new java.net.URL(baseUrl), url).toString();
        } catch (Throwable t) {
            return url;
        }
    }

    private static String valueAfterColon(String line) {
        int colon = line.indexOf(':');
        if (colon < 0 || colon + 1 >= line.length()) return "";
        return stripQuotes(trimAll(line.substring(colon + 1)));
    }

    /**
     * Posición de la primera coma que separa los atributos del NOMBRE del canal.
     *
     * Antes se usaba un solo indicador de "estoy entre comillas" para las comillas
     * dobles y las simples. Con un nombre como tvg-name="L'Equipe" la comilla
     * simple cerraba el grupo y la comilla doble final lo volvía a abrir, así que
     * la coma del nombre se tomaba por "dentro de comillas": el nombre del canal
     * se perdía y los atributos se comían el título. Ahora se guarda QUÉ comilla
     * abrió y solo esa la puede cerrar.
     */
    private static int firstCommaOutsideQuotes(String line) {
        char open = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (open == 0) {
                if (c == '"' || c == '\'') open = c;
                else if (c == ',') return i;
            } else if (c == open) {
                open = 0;
            }
        }
        return -1;
    }

    /**
     * true si la línea es un bloque de atributos del canal que continúa el
     * #EXTINF de la línea anterior (por ejemplo "group-title=\"Deportes\",Nombre").
     * Nunca devuelve true para una dirección: una dirección empieza por su
     * esquema (http, https, rtsp...) y eso no es un atributo de canal.
     */
    private static boolean looksLikeAttributeLine(String line) {
        if (line == null) return false;
        int i = 0;
        int length = line.length();
        while (i < length && isSpace(line.charAt(i))) i++;
        int start = i;
        while (i < length && isKeyChar(line.charAt(i))) i++;
        if (i == start) return false;
        String key = line.substring(start, i).toLowerCase(Locale.US);
        int j = i;
        while (j < length && isSpace(line.charAt(j))) j++;
        boolean hasSeparator = j < length && (line.charAt(j) == '=' || line.charAt(j) == ':');
        if (!hasSeparator) return false;
        return key.startsWith("tvg-") || key.equals("group-title") || key.equals("group")
                || key.equals("group_name") || key.equals("name") || key.equals("logo")
                || key.equals("logo_plus") || key.startsWith("catchup") || key.equals("radio")
                || key.equals("tvg-shift") || key.equals("timeshift") || key.equals("tvg-id");
    }

    /** Aplica una línea de atributos al canal que está abierto. */
    private static void applyContinuation(Pending pending, String line, Result result) {
        int comma = firstCommaOutsideQuotes(line);
        String attributeText = comma >= 0 ? line.substring(0, comma) : line;
        Extinf extinf = readAttributes(attributeText);
        if (result != null && extinf.anyAttribute) result.channelAttributes = true;
        if (extinf.name.length() > 0 && (pending.name == null || pending.name.length() == 0)) {
            pending.name = extinf.name;
        } else if (extinf.id.length() > 0 && (pending.name == null || pending.name.length() == 0)) {
            pending.name = extinf.id;
        }
        if (extinf.logo.length() > 0 && pending.logo.length() == 0) pending.logo = extinf.logo;
        if (extinf.group.length() > 0 && (pending.group == null || pending.group.length() == 0)) {
            pending.group = extinf.group;
        }
        if (comma >= 0) {
            String title = stripQuotes(trimAll(line.substring(comma + 1)));
            if (title.length() > 0) {
                pending.name = title;
                if (result != null) result.namedEntries++;
            }
        }
    }

    /**
     * Lector de líneas que entiende todos los saltos de línea habituales:
     * \n, \r\n, \r solo, \f y los separadores Unicode de párrafo. Así una lista
     * guardada con saltos \r se lee igual que cualquier otra.
     */
    private static final class LineScanner {
        private final String text;
        private int index;

        LineScanner(String text) {
            this.text = text;
            this.index = 0;
        }

        /** Siguiente línea sin el salto, o null cuando ya no queda texto. */
        String next() {
            int length = text.length();
            if (index >= length) return null;
            int start = index;
            int i = index;
            while (i < length && !isLineBreak(text.charAt(i))) i++;
            String line = text.substring(start, i);
            if (i < length) {
                char c = text.charAt(i);
                i++;
                if (c == '\r' && i < length && text.charAt(i) == '\n') i++;
            }
            index = i;
            return line;
        }

        private static boolean isLineBreak(char c) {
            return c == '\n' || c == '\r' || c == '\f' || c == '\u2028' || c == '\u2029' || c == '\u0085';
        }
    }

    private static int firstSpace(String value) {
        if (value == null) return -1;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == ' ' || c == '\t' || c == '\u00A0') return i;
        }
        return -1;
    }

    private static String stripQuotes(String value) {
        if (value == null) return "";
        String trimmed = trimAll(value);
        while (trimmed.length() >= 2) {
            char first = trimmed.charAt(0);
            char last = trimmed.charAt(trimmed.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                trimmed = trimAll(trimmed.substring(1, trimmed.length() - 1));
            } else {
                break;
            }
        }
        return trimmed;
    }

    /** Quita espacios, tabuladores, NBSP (copia desde Windows) y marcas invisibles. */
    static String trimAll(String value) {
        if (value == null) return "";
        int start = 0;
        int end = value.length();
        while (start < end && isSpace(value.charAt(start))) start++;
        while (end > start && isSpace(value.charAt(end - 1))) end--;
        return value.substring(start, end);
    }

    static String clip(String value, int max) {
        if (value == null) return "";
        String clean = trimAll(value);
        if (clean.length() <= max) return clean;
        return clean.substring(0, max) + "…";
    }

    private static boolean isSpace(char c) {
        return c == ' ' || c == '\t' || c == '\r' || c == '\n' || c == '\u00A0'
                || c == '\uFEFF' || c == '\u2028' || c == '\u2029' || c == '\u000B' || c == '\f';
    }

    private static boolean isKeyChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                || c == '-' || c == '_' || c == '.';
    }

    /** Nombre de reserva sacado de la propia dirección cuando la lista no lo indica. */
    static String guessName(String url) {
        String value = url == null ? "" : url;
        int options = value.indexOf('|');
        if (options > 0) value = value.substring(0, options);
        int query = value.indexOf('?');
        if (query > 0) value = value.substring(0, query);
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        int slash = value.lastIndexOf('/');
        String tail = slash >= 0 ? value.substring(slash + 1) : value;
        if (tail.length() == 0 && slash >= 0) {
            int previous = value.lastIndexOf('/', slash - 1);
            tail = previous >= 0 ? value.substring(previous + 1, slash) : value;
        }
        return tail.length() == 0 ? "Entrada" : tail;
    }

    /** Mapa de cabeceras de un canal, para pruebas y diagnóstico. */
    static Map<String, String> headersOf(String text, String targetUrl, String baseUrl) {
        Channel channel = lookupBlock(text, targetUrl, baseUrl);
        return channel == null ? new TreeMap<String, String>() : channel.headers;
    }
}
