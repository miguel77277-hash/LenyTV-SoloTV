package com.pocketbuilder.app_wslenytvplayer1792356127271;

import java.util.Map;

/**
 * Prueba del lector de listas que se puede ejecutar dentro de la propia app.
 *
 * Analiza varias listas de MUESTRA escritas en el código (no son los canales
 * del usuario: son textos de prueba con la misma forma que usan los
 * proveedores) y dice cuántas entradas y categorías reconoce de cada una, y
 * cuánto tarda. Así se sabe si el problema está en el lector de la app o en lo
 * que responde el servidor del proveedor.
 *
 * Incluye a propósito los casos que antes dejaban la app pensando para siempre
 * (por ejemplo "#EXTINF" sin atributos) y una lista grande, para comprobar que
 * el lector siempre termina y que no bloquea la pantalla.
 */
final class SelfTest {

    private SelfTest() {
    }

    static String run() {
        StringBuilder sb = new StringBuilder();
        sb.append("PRUEBA DEL LECTOR DE LISTAS\n");
        sb.append("(son listas de muestra del propio código, no tus canales)\n");

        test(sb, "IPTV con tvg-name, tvg-logo y group-title", sampleWithAttributes(),
                null, 3, "Deportes");
        test(sb, "IPTV copiada desde Windows (BOM, CRLF, sin espacios)", sampleWindows(),
                null, 3, "Series");
        test(sb, "Lista con #EXTGRP y cabeceras por canal", sampleExtgrp(),
                null, 2, "Noticias");
        test(sb, "Direcciones que no son http (acestream, plugin)", sampleOtherSchemes(),
                null, 2, "P2P");
        test(sb, "Atributos con espacios alrededor del '=' y comillas simples", sampleLooseAttributes(),
                null, 2, "Música");
        test(sb, "Atributos sin comillas y comas dentro del nombre", sampleUnquoted(),
                null, 2, "Cine");
        test(sb, "Enlaces relativos (video/1.ts)", sampleRelative(),
                "http://servidor.ejemplo/live/lista.m3u", 2, "Relativos");
        test(sb, "Enlace .m3u8 de vídeo (lista HLS)", sampleHls(), null, 0, "");
        test(sb, "Respuesta que no es una lista (página web)", sampleHtml(), null, 0, "");

        sb.append('\n');
        sb.append(status("Lista grande (3000 canales)", testLarge()));
        sb.append(status("UTF-16 (lista guardada por Windows)", testUtf16()));
        sb.append(status("Archivo comprimido (lo que NO se puede leer)", testNotAPlaylist()));
        testChannelBlock(sb);
        testBridgeHeaders(sb);
        testDrmAndLicense(sb);
        testCategoriesAndNames(sb);
        return sb.toString();
    }

    /**
     * Comprueba dos cosas que hasta ahora solo estaban explicadas de palabra:
     *
     *  1. Que el lector del manifiesto DASH reconoce un canal PROTEGIDO (Widevine),
     *     anota que trae datos de inicio de protección (pssh) y guarda el servidor
     *     de licencias SIN el token que suele ir en la dirección.
     *  2. Que la consulta al sistema DRM del teléfono responde algo útil y que el
     *     texto de la app dice CLARAMENTE que no descifra contenido protegido.
     *
     * Se usan textos de muestra del propio código: esta prueba no reproduce ningún
     * canal, no pide ninguna licencia y no descarga nada.
     */
    private static void testDrmAndLicense(StringBuilder sb) {
        sb.append('\n');
        sb.append("DRM Y LICENCIA DEL MANIFIESTO\n");
        StringBuilder problems = new StringBuilder();
        String sample = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" type=\"static\""
                + " mediaPresentationDuration=\"PT10S\">"
                + "<Period id=\"p0\">"
                + "<AdaptationSet mimeType=\"video/mp4\">"
                + "<ContentProtection schemeIdUri=\"urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed\">"
                + "<dashif:Laurl>https://licencias.ejemplo.com/wv?token=secreto-de-prueba</dashif:Laurl>"
                + "<cenc:pssh>AAAABBBBCCCC</cenc:pssh>"
                + "</ContentProtection>"
                + "<Representation id=\"v1\" bandwidth=\"900000\" width=\"1280\" height=\"720\""
                + " codecs=\"avc1.4d401f\">"
                + "<SegmentTemplate media=\"seg-$Number$.m4s\" initialization=\"init.m4s\""
                + " duration=\"4\" timescale=\"1\" startNumber=\"1\"/>"
                + "</Representation>"
                + "</AdaptationSet>"
                + "</Period>"
                + "</MPD>";
        Mpd mpd = null;
        try {
            mpd = Mpd.parse(sample, "https://servidor.ejemplo/canal/manifiesto.mpd");
        } catch (Throwable t) {
            sb.append("· no se pudo leer el manifiesto de muestra: ").append(Net.describe(t)).append('\n');
            problems.append("el manifiesto protegido de muestra no se pudo leer; ");
        }
        if (mpd != null) {
            sb.append("    sistema detectado: ")
                    .append(mpd.hasProtection()
                            ? (mpd.protectionSystem().length() == 0 ? "no identificado" : mpd.protectionSystem())
                            : "ninguno")
                    .append('\n');
            sb.append("    servidor de licencias: ")
                    .append(mpd.licenseHost().length() == 0 ? "(no declarado)" : mpd.licenseHost())
                    .append('\n');
            sb.append("    datos de inicio de protección (pssh): ")
                    .append(mpd.hasPssh ? "sí" : "no").append('\n');
            sb.append("    resumen del manifiesto: ").append(mpd.summary()).append('\n');
            if (!mpd.hasProtection()) problems.append("no se detectó que el canal está protegido; ");
            if (!"Widevine".equals(mpd.protectionSystem())) {
                problems.append("no se identificó Widevine como sistema de protección; ");
            }
            if (!mpd.hasPssh) problems.append("no se anotaron los datos pssh; ");
            if (mpd.licenseHost().indexOf("licencias.ejemplo.com") < 0) {
                problems.append("no se guardó el servidor de licencias; ");
            }
            if (mpd.licenseHost().indexOf("secreto-de-prueba") >= 0
                    || mpd.summary().indexOf("secreto-de-prueba") >= 0) {
                problems.append("el token de la dirección de licencia apareció en el texto; ");
            }
        }
        String deviceReport = DrmInfo.report();
        String deviceLine = DrmInfo.summary();
        sb.append("    consulta al sistema del teléfono:\n");
        sb.append(indent(deviceReport));
        sb.append("    aviso para el usuario: ").append(deviceLine).append('\n');
        if (deviceReport.length() == 0) problems.append("la consulta DRM del teléfono quedó vacía; ");
        if (deviceReport.indexOf("NO") < 0 || deviceReport.indexOf("descifra") < 0) {
            problems.append("el informe del teléfono no avisa de que el motor no descifra; ");
        }
        if (deviceLine.indexOf("no descifra") < 0) {
            problems.append("el aviso al usuario no dice que el motor no descifra; ");
        }
        sb.append(problems.length() == 0
                ? "    RESULTADO: correcto"
                : "    RESULTADO: revisar — " + problems);
        sb.append('\n');
    }

    /**
     * Comprueba los errores que traían de cabeza las listas:
     *
     *  · Las categorías de group-title se respetan (y solo cae en "Sin categoría"
     *    el canal que de verdad no indica ninguna).
     *  · Los nombres con comas, acentos, comillas y signos raros se conservan
     *    enteros, incluido lo que va detrás de la coma.
     *  · Un #EXTINF con los atributos repartidos en varias líneas no pierde el
     *    grupo ni el nombre, y esas líneas no se toman por direcciones.
     *  · Los saltos de línea \r solos (listas de Mac o de algunos paneles) se leen
     *    igual que los normales.
     *  · Las entradas repetidas se muestran una sola vez.
     *  · Una entrada sin dirección se cuenta y se explica, sin inventar canales.
     */
    private static void testCategoriesAndNames(StringBuilder sb) {
        sb.append('\n');
        sb.append("CATEGORÍAS, COMAS, ACENTOS Y LÍNEAS\n");
        StringBuilder text = new StringBuilder();
        text.append("#EXTM3U\r");
        text.append("#EXTINF:-1 tvg-id=\"dep1\" tvg-name=\"Deportes, Uno\" group-title=\"Deportes\",Deportes Uno HD\r");
        text.append("http://servidor.ejemplo/live/1.ts\r");
        text.append("#EXTINF:-1 tvg-name=\"L'Equipe\" group-title=\"Deportes\",L'Equipe 21\r");
        text.append("http://servidor.ejemplo/live/2.ts\r");
        text.append("#EXTINF:-1 tvg-id=\"cine1\"\r");
        text.append("     group-title=\"Cine, Clásico\",Películas de acción y comas, ¿eh?\r");
        text.append("http://servidor.ejemplo/live/3.ts\r");
        text.append("#EXTINF:-1 tvg-name=\"Sin grupo\",Canal sin categoría\r");
        text.append("http://servidor.ejemplo/live/4.ts\r");
        text.append("#EXTINF:-1 tvg-name=\"Deportes, Uno\" group-title=\"Deportes\",Deportes Uno HD\r");
        text.append("http://servidor.ejemplo/live/1.ts\r");
        text.append("#EXTINF:-1 tvg-name=\"Roto\",Canal sin dirección\r");
        text.append("#EXTINF:-1 tvg-name=\"Acentos\" group-title=\"Música\",Canal con acentos: ñandú, José\r");
        text.append("http://servidor.ejemplo/live/5.ts\r");
        M3uParser.Result result;
        try {
            result = M3uParser.parse(text.toString(), null);
        } catch (Throwable t) {
            sb.append("· ERROR al leer la lista de muestra: ").append(Net.describe(t)).append('\n');
            sb.append("    RESULTADO: revisar\n");
            return;
        }
        sb.append("    canales: ").append(result.channels.size())
                .append(" · categorías: ").append(result.categories())
                .append(" · repetidas: ").append(result.duplicates)
                .append(" · entradas sin dirección: ").append(result.entriesWithoutUrl)
                .append(" · líneas de atributos: ").append(result.continuationLines).append('\n');
        for (int i = 0; i < result.channels.size(); i++) {
            Channel channel = result.channels.get(i);
            sb.append("    · ").append(channel.name).append("  →  [").append(channel.category).append("]\n");
        }
        StringBuilder problems = new StringBuilder();
        if (result.channels.size() != 5) {
            problems.append("se esperaban 5 canales y se leyeron ").append(result.channels.size()).append("; ");
        }
        int withoutCategory = 0;
        for (int i = 0; i < result.channels.size(); i++) {
            if (M3uParser.NO_CATEGORY.equals(result.channels.get(i).category)) withoutCategory++;
        }
        if (withoutCategory != 1) {
            problems.append("solo un canal de la muestra no tiene categoría y salieron ")
                    .append(withoutCategory).append(" sin categoría; ");
        }
        Channel deportes = findByUrl(result, "http://servidor.ejemplo/live/1.ts");
        if (deportes == null) {
            problems.append("no se encontró el primer canal; ");
        } else {
            if (!"Deportes".equals(deportes.category)) {
                problems.append("el group-title \"Deportes\" no se respetó (quedó \"")
                        .append(deportes.category).append("\"); ");
            }
            if (!"Deportes Uno HD".equals(deportes.name)) {
                problems.append("el nombre detrás de la coma no se conservó (\"").append(deportes.name).append("\"); ");
            }
        }
        Channel comillas = findByUrl(result, "http://servidor.ejemplo/live/2.ts");
        if (comillas == null) {
            problems.append("no se encontró el canal con comilla simple; ");
        } else if (!"L'Equipe 21".equals(comillas.name)) {
            problems.append("un nombre con comilla simple y coma se leyó mal (\"").append(comillas.name).append("\"); ");
        }
        Channel continuado = findByUrl(result, "http://servidor.ejemplo/live/3.ts");
        if (continuado == null) {
            problems.append("no se encontró el canal con atributos en varias líneas; ");
        } else {
            if (!"Cine, Clásico".equals(continuado.category)) {
                problems.append("la categoría con coma entre comillas se perdió (\"").append(continuado.category)
                        .append("\"); ");
            }
            if (!"Películas de acción y comas, ¿eh?".equals(continuado.name)) {
                problems.append("el nombre con varias comas y acentos se leyó mal (\"").append(continuado.name)
                        .append("\"); ");
            }
        }
        Channel sinGrupo = findByUrl(result, "http://servidor.ejemplo/live/4.ts");
        if (sinGrupo == null) {
            problems.append("no se encontró el canal sin categoría; ");
        } else if (!M3uParser.NO_CATEGORY.equals(sinGrupo.category)) {
            problems.append("un canal sin group-title debería quedar en \"Sin categoría\"; ");
        }
        Channel acentos = findByUrl(result, "http://servidor.ejemplo/live/5.ts");
        if (acentos == null) {
            problems.append("no se encontró el canal con acentos; ");
        } else if (!"Canal con acentos: ñandú, José".equals(acentos.name)) {
            problems.append("los acentos y la eñe no se conservaron (\"").append(acentos.name).append("\"); ");
        }
        if (result.duplicates != 1) {
            problems.append("se esperaba 1 entrada repetida y se contaron ").append(result.duplicates).append("; ");
        }
        if (result.entriesWithoutUrl < 1) {
            problems.append("no se avisó de la entrada que se quedó sin dirección; ");
        }
        if (result.continuationLines < 1) {
            problems.append("no se reconoció la línea de atributos que continúa el #EXTINF; ");
        }
        // tvg-id es un identificador, no el nombre: si la entrada trae tvg-name,
        // manda tvg-name (antes, si tvg-id venía primero, el canal se llamaba
        // "id.solo").
        M3uParser.Result idTest = M3uParser.parse(
                "#EXTINF:-1 tvg-id=\"id.solo\" tvg-name=\"Nombre Guia\" group-title=\"Pruebas\"\n"
                        + "http://servidor.ejemplo/live/6.ts\n", null);
        if (idTest.channels.size() != 1) {
            problems.append("la entrada de prueba de tvg-id/tvg-name no se leyó; ");
        } else if (!"Nombre Guia".equals(idTest.channels.get(0).name)) {
            problems.append("tvg-name no se usó como nombre del canal (quedó \"")
                    .append(idTest.channels.get(0).name).append("\"); ");
        }
        boolean warned = false;
        for (int i = 0; i < result.notes.size(); i++) {
            String note = result.notes.get(i);
            if (note.indexOf("repetidas") >= 0 || note.indexOf("no llevan dirección") >= 0) warned = true;
        }
        if (!warned) problems.append("el lector no explica las entradas repetidas ni las que no llevan dirección; ");
        sb.append(problems.length() == 0
                ? "    RESULTADO: correcto"
                : "    RESULTADO: revisar — " + problems);
        sb.append('\n');
    }

    /** Busca un canal por su dirección en el resultado de una lectura. */
    private static Channel findByUrl(M3uParser.Result result, String url) {
        for (int i = 0; i < result.channels.size(); i++) {
            Channel channel = result.channels.get(i);
            if (url.equals(channel.url)) return channel;
        }
        return null;
    }

    /** Sangra un texto de varias líneas para que se lea dentro de la prueba. */
    private static String indent(String text) {
        if (text == null) return "";
        StringBuilder sb = new StringBuilder();
        String[] lines = text.split("\n");
        for (int i = 0; i < lines.length; i++) {
            sb.append("      ").append(lines[i]).append('\n');
        }
        return sb.toString();
    }

    /**
     * Comprueba las cabeceras que el PUENTE LOCAL (segundo intento de un canal
     * HLS/MP4) repite en cada petición. Antes solo repetía User-Agent y Referer:
     * un canal que exige Origin o Cookie fallaba también el segundo intento.
     * Aquí se usa el mismo cálculo que hace la petición real.
     */
    private static void testBridgeHeaders(StringBuilder sb) {
        sb.append('\n');
        sb.append("CABECERAS DEL PUENTE LOCAL (segundo intento)\n");
        Map<String, String> channel = Headers.fromSpec(
                "User-Agent: Enlace/2.0\n"
                        + "Referer: https://proveedor.ejemplo/\n"
                        + "Origin: https://proveedor.ejemplo\n"
                        + "Cookie: sesion=abc123\n"
                        + "Authorization: Bearer token-de-prueba\n"
                        + "X-Dispositivo: android\n"
                        + "Host: otro.ejemplo\n"
                        + "Connection: keep-alive\n"
                        + "Content-Length: 12\n"
                        + "Content-Type: text/plain\n"
                        + "Accept-Encoding: gzip\n"
                        + "Range: bytes=0-\n");
        Map<String, String> sent = LocalProxy.bridgeHeaders(channel);
        sb.append("    se envían: ").append(Headers.names(sent)).append('\n');
        sb.append("    resumen: ").append(Headers.describe(sent)).append('\n');
        StringBuilder problems = new StringBuilder();
        if (!"Enlace/2.0".equals(sent.get("User-Agent"))) {
            problems.append("el User-Agent del canal no llega al puente; ");
        }
        if (!"https://proveedor.ejemplo/".equals(sent.get("Referer"))) {
            problems.append("el Referer del canal no llega al puente; ");
        }
        if (!"https://proveedor.ejemplo".equals(sent.get("Origin"))) {
            problems.append("el Origin del canal no llega al puente; ");
        }
        if (sent.get("Cookie") == null) {
            problems.append("la Cookie del canal no llega al puente; ");
        }
        if (sent.get("Authorization") == null) {
            problems.append("la cabecera Authorization no llega al puente; ");
        }
        if (!"android".equals(sent.get("X-Dispositivo"))) {
            problems.append("una cabecera propia del proveedor no llega al puente; ");
        }
        if (sent.containsKey("Host") || sent.containsKey("Connection")
                || sent.containsKey("Content-Length") || sent.containsKey("Content-Type")
                || sent.containsKey("Accept-Encoding") || sent.containsKey("Range")) {
            problems.append("se copian cabeceras de control que romperían la petición; ");
        }
        String described = Headers.describe(sent);
        if (described.indexOf("abc123") >= 0 || described.indexOf("token-de-prueba") >= 0) {
            problems.append("el diagnóstico enseñó una cookie o un token en claro; ");
        }
        if (described.indexOf("Origin=") < 0) {
            problems.append("el diagnóstico no menciona el Origin aplicado; ");
        }
        sb.append(problems.length() == 0
                ? "    RESULTADO: correcto"
                : "    RESULTADO: revisar — " + problems);
        sb.append('\n');
    }

    /**
     * Comprueba la lectura del BLOQUE del canal: cabeceras de #EXTVLCOPT,
     * #KODIPROP, #EXTHTTP y los parámetros detrás de "|", más las directivas de
     * licencia. Es la prueba de que el canal se abre con SUS cabeceras y no con
     * las generales, y de que una clave nunca se muestra.
     */
    private static void testChannelBlock(StringBuilder sb) {
        sb.append('\n');
        sb.append("BLOQUE DEL CANAL (cabeceras y licencia)\n");
        M3uParser.Result result;
        try {
            result = M3uParser.parse(sampleChannelBlock(), null);
        } catch (Throwable t) {
            sb.append("· ERROR al leer el bloque: ").append(Net.describe(t)).append('\n');
            sb.append("RESULTADO: revisar\n");
            return;
        }
        sb.append("    canales: ").append(result.channels.size())
                .append(" · con cabeceras propias: ").append(result.channelsWithHeaders)
                .append(" · con DRM declarado: ").append(result.channelsWithLicense).append('\n');
        for (int i = 0; i < result.channels.size(); i++) {
            Channel channel = result.channels.get(i);
            sb.append("    · ").append(channel.name).append(" → ").append(channel.blockSummary()).append('\n');
            sb.append("      valores: ").append(Headers.describe(channel.headers)).append('\n');
            if (channel.hasLicense()) {
                sb.append("      licencia: ").append(channel.licenseSummary()).append('\n');
            }
        }
        for (int i = 0; i < result.notes.size(); i++) {
            sb.append("    aviso del lector: ").append(result.notes.get(i)).append('\n');
        }
        StringBuilder problems = new StringBuilder();
        if (result.channels.size() != 3) {
            problems.append("se esperaban 3 canales y se leyeron ").append(result.channels.size()).append("; ");
        }
        if (result.channels.size() == 3) {
            Channel one = result.channels.get(0);
            if (!"Enlace/2.0".equals(one.headers.get("User-Agent"))) {
                problems.append("el User-Agent de los parámetros tras \"|\" no se aplicó (")
                        .append(one.headers.get("User-Agent")).append("); ");
            }
            if (!"https://enlace.ejemplo/".equals(one.headers.get("Referer"))) {
                problems.append("el Referer tras \"|\" no se aplicó; ");
            }
            if (!"https://bloque.ejemplo".equals(one.headers.get("Origin"))) {
                problems.append("el Origin de #EXTHTTP no se aplicó; ");
            }
            if (one.headers.get("Cookie") == null) {
                problems.append("la Cookie de #EXTHTTP no se aplicó; ");
            }
            if (one.headers.get("X-Dispositivo") == null) {
                problems.append("una cabecera propia tras \"|\" no se aplicó; ");
            }
            if (one.headers.get("Network-Caching") != null) {
                problems.append("una opción de VLC que no es cabecera se envió como cabecera; ");
            }
            Channel two = result.channels.get(1);
            if (!"Kodi/1.0".equals(two.headers.get("User-Agent"))) {
                problems.append("las cabeceras de inputstream.adaptive.stream_headers no se aplicaron; ");
            }
            if (!two.hasLicense() || !"clearkey".equals(two.licenseType)) {
                problems.append("no se identificó el sistema de DRM declarado; ");
            }
            if (two.licenseSummary().indexOf("clave-de-prueba") >= 0) {
                problems.append("la clave de licencia apareció en el texto de la app; ");
            }
            if (!two.licenseSummary().contains("NO descifra")) {
                problems.append("no se avisa de que el motor no descifra contenido protegido; ");
            }
            Channel three = result.channels.get(2);
            if (!"https://bloque.ejemplo/portada".equals(three.headers.get("Referer"))) {
                problems.append("el Referer de #EXTVLCOPT no se aplicó; ");
            }
        }
        // El mismo bloque, buscado por su dirección (es lo que hace el reproductor
        // antes del primer intento).
        Channel looked = M3uParser.lookupBlock(sampleChannelBlock(),
                "https://canal.ejemplo/protegido/manifiesto", null);
        if (looked == null) {
            problems.append("no se encontró el bloque del canal por su dirección; ");
        } else if (!looked.hasOwnHeaders()) {
            problems.append("el bloque encontrado llegó sin cabeceras; ");
        }
        sb.append(problems.length() == 0 ? "    RESULTADO: correcto" : "    RESULTADO: revisar — " + problems);
        sb.append('\n');
    }

    /** Lista de muestra con el bloque completo de cada canal. */
    private static String sampleChannelBlock() {
        return "#EXTM3U\n"
                + "#EXTINF:-1 tvg-name=\"Prueba Uno\" group-title=\"Pruebas\",Prueba Uno\n"
                + "#EXTVLCOPT:http-user-agent=Antiguo/1.0\n"
                + "#EXTVLCOPT:network-caching=3000\n"
                + "#EXTHTTP:{\"Origin\":\"https://bloque.ejemplo\",\"Cookie\":\"sesion=abc123\"}\n"
                + "https://canal.ejemplo/directo/index.mpd|User-Agent=Enlace%2F2.0"
                + "&Referer=https%3A%2F%2Fenlace.ejemplo%2F&X-Dispositivo=android\n"
                + "#EXTINF:-1 tvg-name=\"Prueba Dos\" group-title=\"Pruebas\",Prueba Dos\n"
                + "#KODIPROP:inputstream.adaptive.stream_headers=User-Agent=Kodi%2F1.0"
                + "&Referer=https%3A%2F%2Fkodi.ejemplo%2F\n"
                + "#KODIPROP:inputstream.adaptive.license_type=clearkey\n"
                + "#KODIPROP:inputstream.adaptive.license_key=oculto:clave-de-prueba\n"
                + "https://canal.ejemplo/protegido/manifiesto\n"
                + "#EXTINF:-1 tvg-name=\"Prueba Tres\" group-title=\"Pruebas\",Prueba Tres\n"
                + "#EXTVLCOPT:http-referrer=https://bloque.ejemplo/portada\n"
                + "https://canal.ejemplo/directo/tercero.ts\n";
    }

    private static void test(StringBuilder sb, String name, String text, String baseUrl,
                             int expectedChannels, String expectedCategory) {
        M3uParser.Result result;
        long started = android.os.SystemClock.elapsedRealtime();
        try {
            result = M3uParser.parse(text, baseUrl);
        } catch (Throwable t) {
            sb.append("\n· ").append(name).append("\n    ERROR al leer: ").append(Net.describe(t)).append('\n');
            sb.append("    RESULTADO: revisar\n");
            return;
        }
        long elapsed = android.os.SystemClock.elapsedRealtime() - started;
        sb.append("\n· ").append(name).append('\n');
        sb.append("    #EXTINF: ").append(result.extinfCount)
                .append(" · canales: ").append(result.channels.size())
                .append(" · categorías: ").append(result.categories())
                .append(" · ").append(elapsed).append(" ms\n");
        if (result.mediaPlaylist) sb.append("    aviso: es una lista de vídeo HLS, no una lista de canales\n");
        for (int i = 0; i < result.notes.size(); i++) {
            sb.append("    aviso del lector: ").append(result.notes.get(i)).append('\n');
        }
        if (result.channels.size() > 0) {
            Channel first = result.channels.get(0);
            sb.append("    primero: ").append(first.name)
                    .append(" → ").append(shorten(first.url))
                    .append('\n');
            sb.append("    logo: ").append(first.logo.length() > 0 ? "sí" : "no")
                    .append(" · categoría: ").append(first.category).append('\n');
        }
        boolean ok = result.channels.size() == expectedChannels
                && (expectedCategory.length() == 0 || hasCategory(result, expectedCategory));
        sb.append(ok ? "    RESULTADO: correcto" : "    RESULTADO: revisar (se esperaban "
                + expectedChannels + " canales"
                + (expectedCategory.length() == 0 ? "" : " y la categoría " + expectedCategory) + ")");
        if (!ok && result.channels.isEmpty() && result.reason().length() > 0) {
            sb.append(" — motivo: ").append(result.reason());
        }
        sb.append('\n');
    }

    private static boolean hasCategory(M3uParser.Result result, String category) {
        for (int i = 0; i < result.channels.size(); i++) {
            if (category.equalsIgnoreCase(result.channels.get(i).category)) return true;
        }
        return false;
    }

    /** Lista de 3000 entradas: comprueba que el lector termina y cuánto tarda. */
    private static String testLarge() {
        StringBuilder text = new StringBuilder();
        text.append("#EXTM3U\n");
        for (int i = 1; i <= 3000; i++) {
            text.append("#EXTINF:-1 tvg-name=\"Canal ").append(i)
                    .append("\" group-title=\"Grupo ").append(i % 20)
                    .append("\",Canal ").append(i).append('\n');
            text.append("http://servidor.ejemplo/live/").append(i).append(".ts\n");
        }
        long started = android.os.SystemClock.elapsedRealtime();
        M3uParser.Result result = M3uParser.parse(text.toString(), null);
        long elapsed = android.os.SystemClock.elapsedRealtime() - started;
        if (result.channels.size() != 3000) {
            return "se esperaban 3000 canales y se leyeron " + result.channels.size()
                    + " (" + result.reason() + ")";
        }
        if (elapsed > 2000) return "tardó " + elapsed + " ms, demasiado para no bloquear la pantalla";
        return "";
    }

    /** Devuelve "" si todo fue bien o el error concreto. */
    private static String testUtf16() {
        try {
            String text = "#EXTM3U\r\n#EXTINF:-1 tvg-name=\"Uno\" group-title=\"Prueba\",Canal uno\r\nhttp://servidor/prueba/1.ts\r\n";
            byte[] body = text.getBytes("UTF-16LE");
            byte[] data = new byte[body.length + 2];
            data[0] = (byte) 0xFF;
            data[1] = (byte) 0xFE;
            System.arraycopy(body, 0, data, 2, body.length);
            String decoded = Net.decode(data, null);
            M3uParser.Result result = M3uParser.parse(decoded, null);
            if (result.channels.size() != 1) return "no se leyó bien (canales: " + result.channels.size() + ")";
            return "";
        } catch (Throwable t) {
            return Net.describe(t);
        }
    }

    private static String testNotAPlaylist() {
        try {
            String zip = "PK\u0003\u0004\u0014\u0000\u0000\u0000\b\u0000\u0000\u0000\u0000\u0000contenido";
            if (M3uParser.looksLikePlaylist(zip)) return "un archivo comprimido se confundió con una lista";
            if (M3uParser.parse(sampleHtml(), null).channels.size() != 0) {
                return "la página web se leyó como canales";
            }
            if (!Net.looksLikeHtml(sampleHtml())) return "la página web no se detectó como página";
            return "";
        } catch (Throwable t) {
            return Net.describe(t);
        }
    }

    private static String status(String name, String error) {
        return "· " + name + ": " + (error.length() == 0 ? "correcto" : "revisar — " + error) + "\n";
    }

    private static String shorten(String url) {
        if (url == null) return "";
        if (url.length() <= 60) return url;
        return url.substring(0, 40) + "…" + url.substring(url.length() - 15);
    }

    private static String sampleWithAttributes() {
        return "#EXTM3U url-tvg=\"http://guia.ejemplo/tv.xml\"\n"
                + "#EXTINF:-1 tvg-id=\"Dep1.es\" tvg-name=\"Deportes Uno\" tvg-logo=\"http://guia.ejemplo/logos/dep1.png\" group-title=\"Deportes\",Deportes Uno HD\n"
                + "http://servidor.ejemplo:8080/live/usuario/clave/101.ts\n"
                + "#EXTINF:-1 tvg-id=\"Cine.es\" tvg-name=\"Cine Acción\" tvg-logo=\"http://guia.ejemplo/logos/cine.png\" group-title=\"Cine\",Cine Acción\n"
                + "http://servidor.ejemplo:8080/live/usuario/clave/102.ts\n"
                + "#EXTINF:0 group-title=\"Deportes\",Deportes Dos\n"
                + "http://servidor.ejemplo:8080/live/usuario/clave/103.ts\n";
    }

    private static String sampleWindows() {
        return "\uFEFF#EXTM3U\r\n"
                + "#EXTINF:-1 tvg-name=\"Series Uno\" group-title=\"Series\",Series Uno\r\n"
                + "http://otro.ejemplo/live/1.ts\r\n"
                + "#EXTINF:-1 tvg-name=\"Series Dos\" group-title=\"Series\",Series Dos\r\n"
                + "http://otro.ejemplo/live/2.ts\r\n"
                + "#EXTINF:-1 tvg-name=\"P2P\" group-title=\"Series\",Canal por acestream\r\n"
                + "acestream://0123456789abcdef0123456789abcdef01234567\r\n";
    }

    private static String sampleExtgrp() {
        return "#EXTM3U\n"
                + "#EXTINF:-1 tvg-name=\"Info 24\",Info 24\n"
                + "#EXTGRP:Noticias\n"
                + "#EXTVLCOPT:http-user-agent=MiReproductor/1.0\n"
                + "#EXTVLCOPT:http-referrer=http://proveedor.ejemplo/\n"
                + "http://proveedor.ejemplo/live/info24/index.m3u8|User-Agent=VLC/3.0.18\n"
                + "#EXTINF:-1,Info 25\n"
                + "#EXTGRP:Noticias\n"
                + "rtsp://proveedor.ejemplo:554/live/info25\n";
    }

    private static String sampleOtherSchemes() {
        return "#EXTM3U\n"
                + "#EXTINF:-1 tvg-name=\"P2P Uno\" group-title=\"P2P\",Canal P2P Uno\n"
                + "acestream://aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\n"
                + "#EXTINF:-1 tvg-name=\"Plugin\" group-title=\"P2P\",Canal por plugin\n"
                + "plugin://plugin.video.ejemplo/?url=http%3A%2F%2Fservidor.ejemplo%2Fvivo\n";
    }

    /** El caso que antes dejaba la app pensando para siempre. */
    private static String sampleLooseAttributes() {
        return "#EXTM3U\n"
                + "#EXTINF: -1  tvg-name = 'Radio Uno'  group-title = 'Música' ,Musica Uno\n"
                + "http://audio.ejemplo/1.mp3\n"
                + "#EXTINF:-1 tvg-name='Radio Dos' group-title='Música',Musica Dos\n"
                + "\u00A0http://audio.ejemplo/2.mp3\n";
    }

    private static String sampleUnquoted() {
        return "#EXTM3U\n"
                + "#EXTINF:-1 tvg-name=Cine Uno group-title=Cine,Cine Uno\n"
                + "http://cine.ejemplo/1.ts\n"
                + "#EXTINF:-1 tvg-logo=http://cine.ejemplo/logo.png group-title=Cine,Películas de acción\n"
                + "http://cine.ejemplo/2.ts\n";
    }

    private static String sampleRelative() {
        return "#EXTM3U\n"
                + "#EXTINF:-1 tvg-name=\"Relativo Uno\" group-title=\"Relativos\",Relativo Uno\n"
                + "video/1.ts\n"
                + "#EXTINF:-1 tvg-name=\"Relativo Dos\" group-title=\"Relativos\",Relativo Dos\n"
                + "/absoluto/2.ts\n";
    }

    private static String sampleHls() {
        return "#EXTM3U\n"
                + "#EXT-X-VERSION:3\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=1280000,RESOLUTION=640x360\n"
                + "video/360/index.m3u8\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=2560000,RESOLUTION=1280x720\n"
                + "video/720/index.m3u8\n";
    }

    private static String sampleHtml() {
        return "<!DOCTYPE html>\n<html><head><title>Proveedor</title></head>\n"
                + "<body><h1>Bienvenido</h1><p>Accede con tu usuario y contraseña.</p></body></html>\n";
    }
}
