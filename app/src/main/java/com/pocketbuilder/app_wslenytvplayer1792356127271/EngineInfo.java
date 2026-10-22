package com.pocketbuilder.app_wslenytvplayer1792356127271;

import java.util.ArrayList;

/**
 * Informe del motor de reproducción que se puede leer DENTRO de la app
 * (pantalla DIAGNÓSTICO y botón COPIAR).
 *
 * Responde a las preguntas del informe técnico sin necesidad de conectar el
 * teléfono a un ordenador:
 *
 *  1. QUÉ MOTOR reproduce cada formato en esta revisión, leído del recorrido real
 *     del código (detección del tipo → pantalla del reproductor → motor).
 *  2. POR DÓNDE PASA el canal: cuadrícula → pantalla del reproductor → tipo de
 *     contenido detectado y de dónde salió → motor que lo atiende.
 *  3. CUÁL FUE EL ERROR TÉCNICO COMPLETO: los códigos crudos del reproductor de
 *     Android (what/extra) con los datos que guarda el propio reproductor, el
 *     motivo del motor DASH con su etapa exacta, y lo que responde el servidor en
 *     ese momento (código HTTP real y tipo de contenido).
 */
final class EngineInfo {

    /** Revisión del informe, para saber con qué compilación se generó. */
    static final String REVISION = "revisión 70-Media3";

    private static final int MAX_EVENTS = 40;
    private static final Object LOCK = new Object();
    private static final ArrayList<String> EVENTS = new ArrayList<String>();

    private static String lastEngine = "(todavía no se ha abierto ningún canal)";
    private static String lastType = "(sin detectar)";
    private static String lastWhere = "";
    private static String lastRoute = "(sin recorrido todavía)";
    private static String lastRaw = "(sin errores de reproducción)";
    private static String lastDash = "(el motor DASH no ha dado ningún aviso)";
    private static String lastServer = "(no se ha preguntado al servidor)";
    private static String lastHeaders = "(todavía no se ha enviado ninguna cabecera)";
    private static String lastDetect = "(el tipo se decidió por la extensión del enlace)";
    private static String lastLicense = "(el canal no declara DRM)";
    private static String lastDrm = DrmInfo.summary();

    private EngineInfo() {
    }

    /** Anota qué motor atiende el canal y con qué tipo de contenido. */
    static void attempt(String engine, String type, String where) {
        synchronized (LOCK) {
            lastEngine = engine == null ? "" : engine;
            lastType = type == null ? "" : type;
            lastWhere = where == null ? "" : where;
            add("intento con " + lastEngine + " · tipo " + lastType + " · " + lastWhere);
        }
    }

    /** Anota el recorrido completo desde la cuadrícula hasta el motor. */
    static void route(String text) {
        if (text == null || text.trim().length() == 0) return;
        synchronized (LOCK) {
            lastRoute = text.trim();
            add(text.trim());
        }
    }

    /** Anota el error crudo del reproductor (what/extra, datos del motor o excepción). */
    static void rawError(String text) {
        if (text == null || text.trim().length() == 0) return;
        synchronized (LOCK) {
            lastRaw = text.trim();
            add("ERROR · " + text.trim());
        }
    }

    /** Anota el motivo concreto que dio el motor DASH (etapa, código HTTP...). */
    static void dash(String text) {
        if (text == null || text.trim().length() == 0) return;
        synchronized (LOCK) {
            lastDash = text.trim();
            add("DASH · " + text.trim());
        }
    }

    /** Anota lo que contestó el servidor al preguntarle por el enlace. */
    static void server(String text) {
        if (text == null || text.trim().length() == 0) return;
        synchronized (LOCK) {
            lastServer = text.trim();
            add("SERVIDOR · " + text.trim());
        }
    }

    /**
     * Anota las cabeceras HTTP que se aplicaron al canal y de qué directiva
     * salieron. Los valores sensibles (Cookie, tokens) van ocultos.
     */
    static void headers(String text) {
        if (text == null || text.trim().length() == 0) return;
        synchronized (LOCK) {
            lastHeaders = text.trim();
            add("CABECERAS · " + text.trim());
        }
    }

    /** Anota cómo se reconoció el tipo de contenido del canal. */
    static void detect(String text) {
        if (text == null || text.trim().length() == 0) return;
        synchronized (LOCK) {
            lastDetect = text.trim();
            add("DETECCIÓN · " + text.trim());
        }
    }

    /** Anota la protección de contenido que declara la lista (sin claves). */
    static void license(String text) {
        if (text == null || text.trim().length() == 0) return;
        synchronized (LOCK) {
            lastLicense = text.trim();
            add("LICENCIA · " + text.trim());
        }
    }

    /**
     * Anota qué dijo el sistema DRM del teléfono cuando se le preguntó por un
     * canal protegido. Es informativo: el motor no descifra nada.
     */
    static void drm(String text) {
        if (text == null || text.trim().length() == 0) return;
        synchronized (LOCK) {
            lastDrm = text.trim();
            add("DRM · " + text.trim());
        }
    }

    private static void add(String line) {
        // Se llama siempre con LOCK tomado.
        EVENTS.add(line);
        while (EVENTS.size() > MAX_EVENTS) EVENTS.remove(0);
    }

    /**
     * Informe completo, en texto sencillo, para la pantalla DIAGNÓSTICO.
     */
    static String report() {
        StringBuilder sb = new StringBuilder();
        sb.append("MOTOR DE REPRODUCCIÓN DE LENY TV · ").append(REVISION).append('\n');
        sb.append("QUÉ MOTOR USA CADA FORMATO (recorrido real del código)\n");
        sb.append("· .mpd (DASH) → motor DASH interno de LENY TV: lee el manifiesto (Mpd), pide los")
                .append(" trozos y une audio y vídeo (Mux) antes de reproducirlo. NO es ExoPlayer.\n");
        sb.append("· .m3u8 (HLS), .ts, .mp4, .webm y enlaces directos → reproductor propio de Android")
                .append(" (android.media.MediaPlayer) sobre la superficie de vídeo, con un puente local")
                .append(" (127.0.0.1) que repite la petición con TODAS las cabeceras del canal (User-Agent,")
                .append(" Referer, Origin, Cookie, Authorization y las del proveedor) cuando el servidor")
                .append(" las exige, también en cada trozo y en cada sublista de un canal HLS.\n");
        sb.append("· AndroidX Media3 / ExoPlayer: NO está integrado. Esta compilación es local (sin Gradle")
                .append(" ni descarga de librerías .aar): el catálogo de librerías del servidor devuelve vacío")
                .append(" y la búsqueda de \"media3 exoplayer\" no encuentra nada, así que no se puede añadir.")
                .append(" El motor DASH de la app es propio.\n");
        sb.append("· Cabeceras HTTP por canal: se leen del bloque M3U (#EXTINF, #EXTVLCOPT, #KODIPROP,")
                .append(" #EXTHTTP y los parámetros detrás de \"|\") y se aplican a ese canal ANTES del")
                .append(" primer intento, al manifiesto, a los datos iniciales y a cada trozo.\n");
        sb.append("· DRM: las directivas de licencia se identifican, pero el motor NO descifra contenido")
                .append(" protegido (ni ClearKey ni ningún otro sistema). No se inventan claves.\n");
        sb.append("· Lector de listas (revisión 39): respeta group-title, conserva los nombres con comas,")
                .append(" acentos y comillas, entiende los atributos repartidos en varias líneas, acepta")
                .append(" saltos de línea \\n, \\r\\n y \\r solo, y no repite entradas iguales.\n");
        sb.append("· Interfaz: la app abre con una portada de bienvenida (CoverActivity) y el menú de")
                .append(" canales ya no muestra «Añadir lista» ni el selector de tipo: eso vive en AJUSTES.\n");
        synchronized (LOCK) {
            sb.append("\nÚLTIMO CANAL Y MOTOR\n");
            sb.append("· motor: ").append(lastEngine).append('\n');
            sb.append("· tipo de contenido detectado: ").append(lastType).append('\n');
            if (lastWhere.length() > 0) sb.append("· enlace: ").append(lastWhere).append('\n');
            sb.append("· recorrido: ").append(lastRoute).append('\n');
            sb.append("· cómo se reconoció el tipo: ").append(lastDetect).append('\n');
            sb.append("\nCABECERAS Y LICENCIA\n");
            sb.append("· cabeceras aplicadas: ").append(lastHeaders).append('\n');
            sb.append("· protección de contenido: ").append(lastLicense).append('\n');
            sb.append("· qué dijo el sistema DRM sobre este canal: ").append(lastDrm).append('\n');
            sb.append('\n').append(DrmInfo.report());
            sb.append("\nERROR TÉCNICO COMPLETO\n");
            sb.append("· reproductor de Android (what/extra y datos del motor): ").append(lastRaw).append('\n');
            sb.append("· motor DASH (etapa exacta): ").append(lastDash).append('\n');
            sb.append("· respuesta del servidor al preguntarle: ").append(lastServer).append('\n');
            sb.append("\nÚLTIMOS PASOS ANOTADOS POR EL INFORME\n");
            if (EVENTS.isEmpty()) {
                sb.append("(todavía no hay pasos anotados)\n");
            } else {
                for (int i = 0; i < EVENTS.size(); i++) {
                    sb.append("· ").append(EVENTS.get(i)).append('\n');
                }
            }
        }
        return sb.toString();
    }
}
