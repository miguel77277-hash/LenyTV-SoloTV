package com.pocketbuilder.app_wslenytvplayer1792356127271;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.StringReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Lector de manifiestos DASH (.mpd).
 *
 * Un manifiesto DASH no es un vídeo: es un índice XML que describe las calidades
 * disponibles (Representation) y cómo pedir cada trozo de vídeo o de audio
 * (SegmentTemplate con $Number$ o con SegmentTimeline, SegmentList, o un único
 * archivo con SegmentBase).
 *
 * Esta clase solo lee el índice y deja preparadas las direcciones de cada trozo.
 * La descarga y la reproducción las hace DashPlayer.
 */
final class Mpd {

    /** Una calidad del manifiesto: una pista de vídeo o una pista de audio. */
    static final class Rep {
        String id = "";
        String mimeType = "";
        String contentType = "";
        String codecs = "";
        String lang = "";
        int bandwidth;
        int width;
        int height;
        int audioChannels;
        String baseUrl = "";
        String initUrl = "";
        String media = "";
        long timescale = 1;
        long segDuration = 0;
        long startNumber = 1;
        long presentationTimeOffset = 0;
        /** Un solo archivo para toda la pista (SegmentBase): se reproduce directo. */
        boolean singleFile;
        /** Entradas {t, d} del SegmentTimeline (t = -1 significa "a continuación"). */
        final ArrayList<long[]> timeline = new ArrayList<long[]>();
        /** Direcciones sueltas de un SegmentList. */
        final ArrayList<String> listUrls = new ArrayList<String>();
        /**
         * El manifiesto declara protección sobre ESTA pista concreta (no solo
         * sobre el canal). Así un manifiesto que protege únicamente una pista que
         * la app no usa (por ejemplo unos subtítulos o un audio alternativo) ya no
         * bloquea la reproducción del vídeo que sí es libre.
         */
        boolean protectedContent;
        /** Sistemas de protección declarados para esta pista, tal cual vienen. */
        final ArrayList<String> protectionSchemes = new ArrayList<String>();
        /** Nombre del sistema de protección de esta pista ("" si no se identifica). */
        String protectionSystem = "";

        boolean isVideo() {
            String mime = mimeType.toLowerCase(Locale.US);
            if (mime.indexOf("video") == 0) return true;
            if (mime.indexOf("audio") == 0) return false;
            // Muchos manifiestos no escriben mimeType en la pista y se reconocen por
            // contentType, por la resolución o por el nombre del códec.
            String kind = contentType.toLowerCase(Locale.US);
            if (kind.indexOf("video") >= 0) return true;
            if (kind.indexOf("audio") >= 0 || kind.indexOf("text") >= 0) return false;
            if (height > 0 || width > 0) return true;
            String codec = codecs.toLowerCase(Locale.US);
            if (codec.indexOf("avc") >= 0 || codec.indexOf("hvc") >= 0 || codec.indexOf("hev") >= 0
                    || codec.indexOf("av01") >= 0 || codec.indexOf("vp09") >= 0 || codec.indexOf("vp08") >= 0
                    || codec.indexOf("dvh") >= 0) {
                return true;
            }
            return false;
        }

        boolean isAudio() {
            String mime = mimeType.toLowerCase(Locale.US);
            if (mime.indexOf("audio") == 0) return true;
            if (mime.indexOf("video") == 0) return false;
            String kind = contentType.toLowerCase(Locale.US);
            if (kind.indexOf("audio") >= 0) return true;
            if (kind.indexOf("video") >= 0 || kind.indexOf("text") >= 0) return false;
            String codec = codecs.toLowerCase(Locale.US);
            // Una pista con códec de vídeo no se trata como audio, aunque su lista de
            // códecs incluya también el del sonido.
            if (codec.indexOf("avc") >= 0 || codec.indexOf("hvc") >= 0 || codec.indexOf("hev") >= 0
                    || codec.indexOf("av01") >= 0 || codec.indexOf("vp09") >= 0 || codec.indexOf("vp08") >= 0) {
                return false;
            }
            return codec.indexOf("mp4a") >= 0 || codec.indexOf("ac-3") >= 0 || codec.indexOf("ec-3") >= 0
                    || codec.indexOf("opus") >= 0 || codec.indexOf("vorbis") >= 0 || codec.indexOf("dts") >= 0;
        }

        /** true si la pista no es ni vídeo ni audio (por ejemplo subtítulos). */
        boolean isOther() {
            String mime = mimeType.toLowerCase(Locale.US);
            if (mime.indexOf("text") == 0 || mime.indexOf("application/ttml") == 0) return true;
            return contentType.toLowerCase(Locale.US).indexOf("text") >= 0;
        }

        /** Duración de un trozo en milisegundos (0 si no se puede saber). */
        long segmentMs() {
            if (timeline.size() > 0) {
                long[] first = timeline.get(0);
                return toMs(first[1]);
            }
            return segDuration > 0 ? toMs(segDuration) : 0;
        }

        long toMs(long units) {
            if (timescale <= 0) return 0;
            return Math.round(units * 1000.0 / timescale);
        }

        /** Nombre corto y legible del códec. */
        String codecName() {
            String codecs = this.codecs.toLowerCase(Locale.US);
            if (codecs.length() == 0) return "";
            if (codecs.indexOf("avc") >= 0) return "H.264";
            if (codecs.indexOf("hvc") >= 0 || codecs.indexOf("hev") >= 0) return "H.265";
            if (codecs.indexOf("av01") >= 0) return "AV1";
            if (codecs.indexOf("vp09") >= 0 || codecs.indexOf("vp9") >= 0) return "VP9";
            if (codecs.indexOf("vp08") >= 0 || codecs.indexOf("vp8") >= 0) return "VP8";
            if (codecs.indexOf("mp4a") >= 0) return "AAC";
            if (codecs.indexOf("ac-3") >= 0) return "AC-3";
            if (codecs.indexOf("ec-3") >= 0) return "E-AC-3";
            if (codecs.indexOf("opus") >= 0) return "Opus";
            if (codecs.indexOf("vorbis") >= 0) return "Vorbis";
            if (codecs.indexOf("dts") >= 0) return "DTS";
            int dot = codecs.indexOf('.');
            return dot > 0 ? codecs.substring(0, dot) : codecs;
        }

        /** Etiqueta de esta calidad para el menú CALIDAD. */
        String label() {
            StringBuilder sb = new StringBuilder();
            if (height > 0) sb.append(height).append("p");
            else if (bandwidth > 0) sb.append(Math.round(bandwidth / 1000f)).append(" kbps");
            else if (id.length() > 0) sb.append("Pista ").append(id);
            else sb.append("Pista");
            if (bandwidth > 0 && height > 0) sb.append(" · ").append(Math.round(bandwidth / 1000f)).append(" kbps");
            String codec = codecName();
            if (codec.length() > 0) sb.append(" · ").append(codec);
            return sb.toString();
        }

        String describe() {
            return (width > 0 ? width + "×" + height + " · " : "")
                    + (mimeType.length() == 0 ? "(sin tipo)" : mimeType)
                    + (codecs.length() == 0 ? "" : " · " + codecs)
                    + (bandwidth > 0 ? " · " + Math.round(bandwidth / 1000f) + " kbps" : "");
        }
    }

    /** Cuántos trozos forman un bloque repetido del SegmentTimeline. */
    static final class Timeline {
        long time;
        long duration;
        long repeat;
    }

    String type = "static";
    boolean dynamic;
    long mediaDurationMs;
    long availabilityStartMs;
    long timeShiftMs = -1;
    long minimumUpdateMs;
    int periodCount;
    /**
     * Periodo del manifiesto que se reproduce (1 = el primero). En un canal en
     * directo es el último: los trozos de los periodos anteriores ya no están
     * publicados en el servidor y pedirlos era un motivo real de que el canal no
     * arrancara.
     */
    int usedPeriod;
    String baseUrl = "";
    /** Sistemas de protección declarados, tal cual aparecen (por ejemplo Widevine o ClearKey). */
    final ArrayList<String> protections = new ArrayList<String>();
    /**
     * Dirección del servidor de licencias que declara el manifiesto (elemento
     * Laurl / LicenseURL dentro de ContentProtection). Se lee para poder explicar
     * de dónde vendría la licencia; LENY TV NUNCA la pide ni la usa para descifrar.
     */
    String licenseUrl = "";
    /** El manifiesto incluye datos de inicio de protección (pssh). */
    boolean hasPssh;
    final ArrayList<Rep> reps = new ArrayList<Rep>();

    private Mpd() {
    }

    ArrayList<Rep> videoReps() {
        ArrayList<Rep> out = new ArrayList<Rep>();
        for (int i = 0; i < reps.size(); i++) {
            Rep r = reps.get(i);
            if (r.isVideo()) out.add(r);
        }
        return out;
    }

    ArrayList<Rep> audioReps() {
        ArrayList<Rep> out = new ArrayList<Rep>();
        for (int i = 0; i < reps.size(); i++) {
            Rep r = reps.get(i);
            if (r.isAudio()) out.add(r);
        }
        return out;
    }

    boolean hasProtection() {
        return schemesSayProtected(protections);
    }

    /**
     * true si esas protecciones significan contenido cifrado.
     *
     * Antes solo se daba por protegido un manifiesto con urn:uuid o con el nombre
     * del sistema escrito. Se quedaba fuera el caso más habitual de los manifiestos
     * reales: «urn:mpeg:dash:mp4protection» con value="cenc" (o "cbcs"), que es
     * justamente la declaración de que las pistas van cifradas con CENC.
     */
    static boolean schemesSayProtected(ArrayList<String> list) {
        if (list == null) return false;
        for (int i = 0; i < list.size(); i++) {
            String value = list.get(i) == null ? "" : list.get(i).toLowerCase(Locale.US);
            if (value.indexOf("urn:uuid") >= 0 || value.indexOf("widevine") >= 0
                    || value.indexOf("clearkey") >= 0 || value.indexOf("playready") >= 0
                    || value.indexOf("marlin") >= 0 || value.indexOf("fairplay") >= 0) {
                return true;
            }
            if (value.indexOf("mp4protection") >= 0
                    && (value.indexOf("cenc") >= 0 || value.indexOf("cbcs") >= 0
                    || value.indexOf("cens") >= 0 || value.indexOf("cbc1") >= 0)) {
                return true;
            }
        }
        return false;
    }

    /** Nombre legible del sistema de protección a partir de su identificador. */
    static String systemName(String scheme) {
        String low = scheme == null ? "" : scheme.toLowerCase(Locale.US);
        if (low.indexOf("edef8ba9") >= 0 || low.indexOf("widevine") >= 0) return "Widevine";
        if (low.indexOf("e2719d58") >= 0 || low.indexOf("clearkey") >= 0) return "ClearKey";
        if (low.indexOf("9a04f079") >= 0 || low.indexOf("playready") >= 0) return "PlayReady";
        if (low.indexOf("5e629af5") >= 0 || low.indexOf("marlin") >= 0) return "Marlin";
        if (low.indexOf("94ce86fb") >= 0 || low.indexOf("fairplay") >= 0) return "FairPlay";
        if (low.indexOf("mp4protection") >= 0) return "CENC (sin sistema nombrado)";
        return "";
    }

    /** true si alguna pista de VÍDEO del manifiesto declara protección. */
    boolean videosProtected() {
        for (int i = 0; i < reps.size(); i++) {
            Rep rep = reps.get(i);
            if (rep.isVideo() && rep.protectedContent) return true;
        }
        return false;
    }

    /** true si alguna pista de AUDIO del manifiesto declara protección. */
    boolean audiosProtected() {
        for (int i = 0; i < reps.size(); i++) {
            Rep rep = reps.get(i);
            if (rep.isAudio() && rep.protectedContent) return true;
        }
        return false;
    }

    /** Sistema de protección de las pistas de vídeo ("" si no se identifica). */
    String videoProtectionSystem() {
        for (int i = 0; i < reps.size(); i++) {
            Rep rep = reps.get(i);
            if (rep.isVideo() && rep.protectedContent && rep.protectionSystem.length() > 0) {
                return rep.protectionSystem;
            }
        }
        return protectionSystem();
    }

    /** Identificador UUID del sistema de protección del vídeo ("" si no se escribe). */
    String videoProtectionUuid() {
        for (int i = 0; i < reps.size(); i++) {
            Rep rep = reps.get(i);
            if (!rep.isVideo() || !rep.protectedContent) continue;
            for (int j = 0; j < rep.protectionSchemes.size(); j++) {
                String scheme = rep.protectionSchemes.get(j);
                int at = scheme.toLowerCase(Locale.US).indexOf("urn:uuid:");
                if (at >= 0) {
                    String uuid = scheme.substring(at + 9).trim();
                    int cut = uuid.indexOf(' ');
                    return cut > 0 ? uuid.substring(0, cut) : uuid;
                }
            }
        }
        return "";
    }

    /** Nombre del sistema de protección más habitual (o cadena vacía). */
    String protectionSystem() {
        for (int i = 0; i < protections.size(); i++) {
            String value = protections.get(i).toLowerCase(Locale.US);
            if (value.indexOf("edef8ba9") >= 0 || value.indexOf("widevine") >= 0) return "Widevine";
            if (value.indexOf("e2719d58") >= 0 || value.indexOf("clearkey") >= 0) return "ClearKey";
            if (value.indexOf("9a04f079") >= 0 || value.indexOf("playready") >= 0) return "PlayReady";
            if (value.indexOf("5e629af5") >= 0 || value.indexOf("marlin") >= 0) return "Marlin";
            if (value.indexOf("94ce86fb") >= 0 || value.indexOf("fairplay") >= 0) return "FairPlay";
        }
        return "";
    }

    /** Identificador del sistema de protección, si es uno conocido. */
    String protectionUuid() {
        for (int i = 0; i < protections.size(); i++) {
            String value = protections.get(i);
            String low = value.toLowerCase(Locale.US);
            int at = low.indexOf("urn:uuid:");
            if (at >= 0) {
                String uuid = value.substring(at + 9).trim();
                int cut = uuid.indexOf(' ');
                if (cut > 0) uuid = uuid.substring(0, cut);
                return uuid;
            }
        }
        return "";
    }

    /**
     * Servidor del que se pediría la licencia, escrito SOLO con el nombre del
     * servidor: la dirección de licencia suele llevar un token o una clave en el
     * camino, y eso no debe aparecer nunca en la app ni en el diagnóstico.
     */
    String licenseHost() {
        if (licenseUrl == null || licenseUrl.length() == 0) return "";
        try {
            java.net.URL parsed = new java.net.URL(licenseUrl);
            String host = parsed.getHost();
            if (host == null || host.length() == 0) return "(servidor local)";
            return parsed.getProtocol() + "://" + host + "/…";
        } catch (Throwable t) {
            return "(dirección de licencia no legible)";
        }
    }

    /** Resumen legible del manifiesto para el registro de diagnóstico. */
    String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append(dynamic ? "EN DIRECTO (dynamic)" : "bajo demanda (static)");
        int totalPeriods = periodCount <= 0 ? 1 : periodCount;
        sb.append(" · ").append(totalPeriods).append(" periodo(s)");
        if (totalPeriods > 1) {
            sb.append(" · se reproduce el periodo ").append(usedPeriod <= 0 ? totalPeriods : usedPeriod)
                    .append(dynamic ? " (el más reciente: es el que tiene trozos publicados)"
                            : " (el primero: es el principio del contenido)");
        }
        sb.append(" · ").append(reps.size()).append(" pistas");
        ArrayList<Rep> videos = videoReps();
        ArrayList<Rep> audios = audioReps();
        sb.append(" (").append(videos.size()).append(" de vídeo, ").append(audios.size()).append(" de audio)");
        if (videos.size() > 0) {
            Rep best = videos.get(videos.size() - 1);
            sb.append(" · vídeo ").append(best.width).append("×").append(best.height)
                    .append(" ").append(best.codecName());
            if (best.segmentMs() > 0) sb.append(" · trozos de ").append(best.segmentMs() / 1000f).append(" s");
        }
        if (audios.size() > 0) {
            Rep audio = audios.get(0);
            sb.append(" · audio ").append(audio.codecName());
            if (audio.lang.length() > 0) sb.append(" (").append(audio.lang).append(")");
        }
        if (hasProtection()) {
            if (videosProtected()) {
                String system = videoProtectionSystem();
                sb.append(" · PROTEGIDO: ")
                        .append(system.length() == 0 ? "sistema desconocido" : system);
                if (licenseHost().length() > 0) sb.append(" · licencia en ").append(licenseHost());
            } else {
                sb.append(" · declara DRM solo en pistas que no se usan (el vídeo no marca protección)");
            }
        }
        return sb.toString();
    }

    /**
     * Lee un manifiesto. Lanza excepción si el texto no es un MPD.
     *
     * @param xml    texto del archivo .mpd
     * @param mpdUrl dirección desde la que se descargó (para resolver direcciones relativas)
     */
    static Mpd parse(String xml, String mpdUrl) throws Exception {
        XmlPullParser parser = XmlPullParserFactory.newInstance().newPullParser();
        parser.setInput(new StringReader(xml));
        Mpd mpd = new Mpd();
        mpd.baseUrl = mpdUrl == null ? "" : mpdUrl;
        String periodBase = mpd.baseUrl;
        AdaSet adaptation = null;
        // Primera pista del Period que pertenece al AdaptationSet que se está
        // leyendo: sirve para marcar como protegidas las pistas ya creadas cuando el
        // manifiesto escribe la protección después de ellas.
        int adaptationRepStart = -1;
        // Valores de segmentación escritos en el propio Period (por defecto para
        // todas sus pistas). Si no se leen, un manifiesto que los pone ahí se queda
        // sin direcciones de trozos y el canal no arranca.
        AdaSet periodDefaults = new AdaSet();
        Rep current = null;
        boolean insidePeriod = false;
        // Todos los Period del manifiesto, cada uno con sus pistas. Antes solo se
        // leía el primero: en un canal en directo cuyos Period cambian (muy
        // habitual), la app pedía trozos ya caducados y el canal no arrancaba.
        ArrayList<ArrayList<Rep>> periods = new ArrayList<ArrayList<Rep>>();
        ArrayList<Rep> periodReps = null;
        ArrayList<Rep> looseReps = new ArrayList<Rep>();
        boolean sawMpd = false;
        StringBuilder text = new StringBuilder();
        int event = parser.getEventType();
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                text.setLength(0);
                String name = parser.getName();
                if ("MPD".equals(name)) {
                    sawMpd = true;
                    mpd.type = attr(parser, "type", "static");
                    mpd.dynamic = "dynamic".equalsIgnoreCase(mpd.type);
                    mpd.mediaDurationMs = durationMs(attr(parser, "mediaPresentationDuration", ""));
                    mpd.availabilityStartMs = timestampMs(attr(parser, "availabilityStartTime", ""));
                    mpd.timeShiftMs = durationMs(attr(parser, "timeShiftBufferDepth", ""));
                    long update = durationMs(attr(parser, "minimumUpdatePeriod", ""));
                    mpd.minimumUpdateMs = update;
                    periodBase = mpd.baseUrl;
                } else if ("Period".equals(name)) {
                    insidePeriod = true;
                    mpd.periodCount++;
                    adaptation = null;
                    current = null;
                    periodBase = mpd.baseUrl;
                    periodDefaults = new AdaSet();
                    periodDefaults.base = mpd.baseUrl;
                    periodReps = new ArrayList<Rep>();
                    periods.add(periodReps);
                } else if ("AdaptationSet".equals(name)) {
                    adaptation = new AdaSet();
                    adaptationRepStart = periodReps == null ? -1 : periodReps.size();
                    adaptation.base = periodBase;
                    adaptation.mimeType = attr(parser, "mimeType", "");
                    adaptation.contentType = attr(parser, "contentType", "");
                    adaptation.lang = attr(parser, "lang", "");
                    adaptation.inheritDefaults(periodDefaults);
                    current = null;
                } else if ("Representation".equals(name)) {
                    current = new Rep();
                    current.baseUrl = adaptation != null ? adaptation.base : periodBase;
                    if (adaptation != null) {
                        current.mimeType = adaptation.mimeType;
                        current.lang = adaptation.lang;
                        // La protección escrita en el AdaptationSet vale para todas
                        // sus pistas.
                        if (adaptation.protectedContent) {
                            current.protectedContent = true;
                            current.protectionSchemes.addAll(adaptation.protections);
                        }
                    }
                    if (!current.protectedContent && periodDefaults.protectedContent) {
                        current.protectedContent = true;
                        current.protectionSchemes.addAll(periodDefaults.protections);
                    }
                    if (adaptation != null && adaptation.contentType.length() > 0) {
                        current.contentType = adaptation.contentType;
                    }
                    current.id = attr(parser, "id", "");
                    current.mimeType = attr(parser, "mimeType", current.mimeType);
                    current.contentType = attr(parser, "contentType", current.contentType);
                    current.codecs = attr(parser, "codecs", "");
                    current.lang = attr(parser, "lang", current.lang);
                    current.bandwidth = (int) longAttr(parser, "bandwidth", 0);
                    current.width = (int) longAttr(parser, "width", 0);
                    current.height = (int) longAttr(parser, "height", 0);
                    current.audioChannels = (int) longAttr(parser, "audioSamplingRate", 0);
                } else if ("SegmentTemplate".equals(name)) {
                    String media = attr(parser, "media", "");
                    String init = attr(parser, "initialization", "");
                    long timescale = longAttr(parser, "timescale", 0);
                    long duration = longAttr(parser, "duration", 0);
                    long start = longAttr(parser, "startNumber", -1);
                    long pto = longAttr(parser, "presentationTimeOffset", 0);
                    if (current != null) {
                        if (media.length() > 0) current.media = media;
                        if (init.length() > 0) current.initUrl = init;
                        if (timescale > 0) current.timescale = timescale;
                        if (duration > 0) current.segDuration = duration;
                        if (start > 0) current.startNumber = start;
                        current.presentationTimeOffset = pto;
                    } else {
                        AdaSet host = adaptation != null ? adaptation : periodDefaults;
                        if (media.length() > 0) host.media = media;
                        if (init.length() > 0) host.initUrl = init;
                        if (timescale > 0) host.timescale = timescale;
                        host.segDuration = duration;
                        if (start > 0) host.startNumber = start;
                        host.presentationTimeOffset = pto;
                        host.hasTemplate = true;
                    }
                } else if ("SegmentList".equals(name)) {
                    long timescale = longAttr(parser, "timescale", 0);
                    long duration = longAttr(parser, "duration", 0);
                    long start = longAttr(parser, "startNumber", -1);
                    if (current != null) {
                        if (timescale > 0) current.timescale = timescale;
                        if (duration > 0) current.segDuration = duration;
                        if (start > 0) current.startNumber = start;
                    } else {
                        AdaSet host = adaptation != null ? adaptation : periodDefaults;
                        if (timescale > 0) host.timescale = timescale;
                        host.segDuration = duration;
                        if (start > 0) host.startNumber = start;
                        host.hasList = true;
                    }
                } else if ("SegmentURL".equals(name)) {
                    String media = attr(parser, "media", "");
                    if (media.length() > 0) {
                        if (current != null) current.listUrls.add(media);
                        else if (adaptation != null) adaptation.listUrls.add(media);
                        else periodDefaults.listUrls.add(media);
                    }
                } else if ("SegmentBase".equals(name)) {
                    if (current != null) current.singleFile = true;
                    else if (adaptation != null) adaptation.singleFile = true;
                    else periodDefaults.singleFile = true;
                } else if ("ContentProtection".equals(name)) {
                    String scheme = attr(parser, "schemeIdUri", "");
                    String value = attr(parser, "value", "");
                    String entry = scheme + (value.length() == 0 ? "" : " " + value);
                    if (scheme.length() > 0 && !mpd.protections.contains(entry)) mpd.protections.add(entry);
                    // La protección se apunta también en la PISTA a la que afecta: si
                    // el manifiesto solo protege una pista que la app no usa, el vídeo
                    // libre se puede seguir reproduciendo.
                    if (scheme.length() > 0) {
                        if (current != null) {
                            current.protectedContent = true;
                            if (!current.protectionSchemes.contains(entry)) {
                                current.protectionSchemes.add(entry);
                            }
                        } else if (adaptation != null) {
                            adaptation.protectedContent = true;
                            adaptation.protections.add(entry);
                            markProtected(periodReps, adaptationRepStart, entry);
                        } else {
                            periodDefaults.protectedContent = true;
                            periodDefaults.protections.add(entry);
                        }
                    }
                    // Algunos manifiestos escriben el servidor de licencias como
                    // atributo del propio ContentProtection.
                    String license = attr(parser, "licenseUrl", "");
                    if (license.length() > 0 && mpd.licenseUrl.length() == 0) {
                        mpd.licenseUrl = license;
                    }
                } else if ("pssh".equals(local(name))) {
                    // Datos de inicio de protección: se anota dónde aparecen.
                    mpd.hasPssh = true;
                    if (current != null) {
                        current.protectedContent = true;
                        String pssh = attr(parser, "systemID", "");
                        if (pssh.length() > 0 && !current.protectionSchemes.contains(pssh)) {
                            current.protectionSchemes.add(pssh);
                        }
                        if (current.protectionSchemes.isEmpty()) {
                            String owner = mpd.protectionSystem();
                            if (owner.length() > 0) current.protectionSchemes.add(owner);
                        }
                    } else if (adaptation != null) {
                        adaptation.protectedContent = true;
                    } else {
                        periodDefaults.protectedContent = true;
                    }
                } else if ("S".equals(name)) {
                    long t = longAttr(parser, "t", -1);
                    long d = longAttr(parser, "d", 0);
                    long r = longAttr(parser, "r", 0);
                    if (r < 0) r = 0;
                    if (d > 0) {
                        if (current != null) current.timeline.add(new long[]{t, d, r});
                        else if (adaptation != null) adaptation.timeline.add(new long[]{t, d, r});
                    }
                }
            } else if (event == XmlPullParser.TEXT) {
                String chunk = parser.getText();
                if (chunk != null) text.append(chunk);
            } else if (event == XmlPullParser.END_TAG) {
                String name = parser.getName();
                if ("BaseURL".equals(name)) {
                    String value = text.toString().trim();
                    if (value.length() > 0) {
                        if (current != null) current.baseUrl = resolve(current.baseUrl, value);
                        else if (adaptation != null) adaptation.base = resolve(adaptation.base, value);
                        else if (insidePeriod) periodBase = resolve(periodBase, value);
                        else mpd.baseUrl = resolve(mpd.baseUrl, value);
                    }
                } else if ("Representation".equals(name)) {
                    if (current != null) {
                        if (adaptation != null) adaptation.inherit(current);
                        finish(current);
                        // Se guarda la pista en su Period. Al terminar de leer el
                        // manifiesto se elige un único Period (en directo, el último)
                        // para no mezclar tramos de momentos distintos.
                        if (periodReps != null) periodReps.add(current);
                        else looseReps.add(current);
                    }
                    current = null;
                } else if ("AdaptationSet".equals(name)) {
                    adaptation = null;
                } else if ("Laurl".equals(local(name)) || "LicenseURL".equals(local(name))
                        || "licenseUrl".equals(local(name))) {
                    // Dirección del servidor de licencias. Se guarda solo para poder
                    // nombrar el servidor en el diagnóstico: la app no pide licencias.
                    String value = text.toString().trim();
                    if (value.length() > 0 && mpd.licenseUrl.length() == 0) {
                        String full = resolve(mpd.baseUrl, value);
                        if (full.length() > 500) full = full.substring(0, 500);
                        mpd.licenseUrl = full;
                    }
                } else if ("Period".equals(name)) {
                    // Solo se abre el primero: es lo que usan los canales en directo y
                    // evita mezclar tramos de momentos distintos.
                    insidePeriod = false;
                }
            }
            event = parser.next();
        }
        if (!sawMpd) throw new Exception("el archivo descargado no es un manifiesto DASH (.mpd)");
        for (int i = 0; i < looseReps.size(); i++) mpd.reps.add(looseReps.get(i));
        if (mpd.reps.isEmpty() && !periods.isEmpty()) {
            int chosen = choosePeriod(mpd, periods);
            mpd.usedPeriod = chosen + 1;
            ArrayList<Rep> reps = periods.get(chosen);
            for (int i = 0; i < reps.size(); i++) mpd.reps.add(reps.get(i));
        } else if (mpd.usedPeriod <= 0) {
            // Las pistas venían fuera de cualquier Period: manifiesto de un solo bloque.
            mpd.usedPeriod = 1;
        }
        for (int i = 0; i < mpd.reps.size(); i++) finish(mpd.reps.get(i));
        return mpd;
    }

    /**
     * Elige qué Period se reproduce.
     *
     * En un canal EN DIRECTO se usa el último Period con pista de vídeo: los trozos
     * de los periodos anteriores ya no están publicados en el servidor, así que
     * pedirlos hacía que el canal no arrancara (o que se quedara cargando).
     *
     * En un manifiesto BAJO DEMANDA se usa el primer Period con vídeo, que es el
     * principio del contenido.
     */
    private static int choosePeriod(Mpd mpd, ArrayList<ArrayList<Rep>> periods) {
        if (mpd.dynamic) {
            for (int i = periods.size() - 1; i >= 0; i--) {
                if (hasVideo(periods.get(i))) return i;
            }
            return periods.size() - 1;
        }
        for (int i = 0; i < periods.size(); i++) {
            if (hasVideo(periods.get(i))) return i;
        }
        return 0;
    }

    private static boolean hasVideo(ArrayList<Rep> reps) {
        if (reps == null) return false;
        for (int i = 0; i < reps.size(); i++) {
            if (reps.get(i).isVideo()) return true;
        }
        return false;
    }

    /**
     * Marca como protegidas las pistas del Period ya creadas dentro de un
     * AdaptationSet (el manifiesto puede escribir la protección después de ellas).
     */
    private static void markProtected(ArrayList<Rep> reps, int from, String entry) {
        if (reps == null) return;
        int start = from < 0 ? 0 : from;
        for (int i = start; i < reps.size(); i++) {
            Rep rep = reps.get(i);
            rep.protectedContent = true;
            if (entry != null && entry.length() > 0 && !rep.protectionSchemes.contains(entry)) {
                rep.protectionSchemes.add(entry);
            }
        }
    }

    /** Completa las direcciones de una pista cuando estén claras. */
    private static void finish(Rep rep) {
        // Nombre del sistema de protección de esta pista (Widevine, ClearKey...).
        if (rep.protectedContent && rep.protectionSystem.length() == 0) {
            for (int i = 0; i < rep.protectionSchemes.size(); i++) {
                String name = systemName(rep.protectionSchemes.get(i));
                if (name.length() > 0) {
                    rep.protectionSystem = name;
                    break;
                }
            }
            if (rep.protectionSystem.length() == 0) rep.protectionSystem = "CENC";
        }
        if (rep.initUrl.length() > 0) rep.initUrl = resolve(rep.baseUrl, rep.initUrl);
        if (rep.media.length() > 0) rep.media = resolve(rep.baseUrl, rep.media);
        if (rep.media.length() == 0 && rep.listUrls.isEmpty() && rep.initUrl.length() == 0) {
            // Sin plantilla ni lista: la pista es un solo archivo, que se reproduce
            // directamente (Android sabe pedirlo por partes al servidor).
            rep.singleFile = true;
        }
    }

    /** Valores que un AdaptationSet hereda a todas sus pistas. */
    private static final class AdaSet {
        String base = "";
        String mimeType = "";
        String contentType = "";
        String lang = "";
        String media = "";
        String initUrl = "";
        long timescale;
        long segDuration;
        long startNumber;
        long presentationTimeOffset;
        boolean singleFile;
        boolean hasTemplate;
        boolean hasList;
        /** Protección de contenido declarada para todo el conjunto o el Period. */
        boolean protectedContent;
        final ArrayList<String> protections = new ArrayList<String>();
        final ArrayList<String> listUrls = new ArrayList<String>();
        /**
         * SegmentTimeline escrito en el AdaptationSet (fuera de cada pista).
         * Es habitual en los canales en directo y, si no se lee, el motor no sabe
         * qué trozos pedir y la imagen nunca llega.
         */
        final ArrayList<long[]> timeline = new ArrayList<long[]>();

        /** Copia los valores de segmentación declarados en el propio Period. */
        void inheritDefaults(AdaSet other) {
            if (other == null) return;
            if (media.length() == 0) media = other.media;
            if (initUrl.length() == 0) initUrl = other.initUrl;
            if (timescale <= 1 && other.timescale > 0) timescale = other.timescale;
            if (segDuration <= 0 && other.segDuration > 0) segDuration = other.segDuration;
            if (startNumber <= 1 && other.startNumber > 0) startNumber = other.startNumber;
            if (presentationTimeOffset <= 0 && other.presentationTimeOffset > 0) {
                presentationTimeOffset = other.presentationTimeOffset;
            }
            if (listUrls.isEmpty() && !other.listUrls.isEmpty()) listUrls.addAll(other.listUrls);
            if (timeline.isEmpty() && !other.timeline.isEmpty()) timeline.addAll(other.timeline);
            if (other.hasTemplate) hasTemplate = true;
            if (other.hasList) hasList = true;
            if (other.singleFile) singleFile = true;
            if (other.protectedContent) {
                protectedContent = true;
                if (protections.isEmpty()) protections.addAll(other.protections);
            }
        }

        void inherit(Rep rep) {
            if (rep.media.length() == 0 && media.length() > 0) rep.media = media;
            if (rep.initUrl.length() == 0 && initUrl.length() > 0) rep.initUrl = initUrl;
            if (rep.timescale <= 1 && timescale > 0) rep.timescale = timescale;
            if (rep.segDuration <= 0 && segDuration > 0) rep.segDuration = segDuration;
            if (rep.startNumber <= 1 && startNumber > 0) rep.startNumber = startNumber;
            if (rep.presentationTimeOffset <= 0 && presentationTimeOffset > 0) {
                rep.presentationTimeOffset = presentationTimeOffset;
            }
            if (rep.contentType.length() == 0 && contentType.length() > 0) rep.contentType = contentType;
            if (rep.listUrls.isEmpty() && !listUrls.isEmpty()) rep.listUrls.addAll(listUrls);
            if (rep.timeline.isEmpty() && !timeline.isEmpty()) {
                for (int i = 0; i < timeline.size(); i++) rep.timeline.add(timeline.get(i));
            }
            if (singleFile && rep.media.length() == 0 && rep.listUrls.isEmpty()) rep.singleFile = true;
            if (hasTemplate && rep.media.length() == 0 && rep.listUrls.isEmpty()) rep.singleFile = false;
        }
    }

    // ------------------------------------------------------------------ ayudas

    /** Sustituye los huecos de una plantilla: $Number$, $Time$, $Bandwidth$, $RepresentationID$. */
    static String fill(String template, Rep rep, long number, long time) {
        if (template == null || template.length() == 0) return "";
        StringBuilder out = new StringBuilder(template.length() + 24);
        int i = 0;
        while (i < template.length()) {
            int dollar = template.indexOf('$', i);
            if (dollar < 0) {
                out.append(template, i, template.length());
                break;
            }
            int end = template.indexOf('$', dollar + 1);
            if (end < 0) {
                out.append(template, i, template.length());
                break;
            }
            out.append(template, i, dollar);
            String token = template.substring(dollar + 1, end);
            if (token.length() == 0) {
                // "$$" se convierte en un solo dólar.
                out.append('$');
            } else {
                out.append(valueOfToken(token, rep, number, time));
            }
            i = end + 1;
        }
        return out.toString();
    }

    private static String valueOfToken(String token, Rep rep, long number, long time) {
        String name = token;
        String format = "";
        int percent = token.indexOf('%');
        if (percent > 0) {
            name = token.substring(0, percent);
            format = token.substring(percent + 1);
        }
        long value;
        if ("Number".equals(name)) value = number;
        else if ("Time".equals(name)) value = time;
        else if ("Bandwidth".equals(name)) value = rep.bandwidth;
        else if ("RepresentationID".equals(name)) return rep.id;
        else return token;
        if (format.length() == 0) return Long.toString(value);
        // Formatos tipo %05d
        String digits = format;
        if (digits.endsWith("d") || digits.endsWith("i") || digits.endsWith("u")) {
            digits = digits.substring(0, digits.length() - 1);
        }
        if (digits.startsWith("0")) {
            int width = 0;
            try {
                width = Integer.parseInt(digits);
            } catch (Exception ignored) {
                width = 0;
            }
            String text = Long.toString(value);
            StringBuilder sb = new StringBuilder();
            for (int i = text.length(); i < width; i++) sb.append('0');
            return sb.append(text).toString();
        }
        return Long.toString(value);
    }

    /** Expande el SegmentTimeline en la lista de {tiempo, duración} de cada trozo. */
    static ArrayList<long[]> expandTimeline(Rep rep) {
        ArrayList<long[]> out = new ArrayList<long[]>();
        // Algunos manifiestos en directo no escriben el atributo t en el primer
        // tramo. Antes ese primer trozo se descartaba; ahora empieza en el
        // desplazamiento declarado (presentationTimeOffset) o en 0.
        long time = rep.presentationTimeOffset > 0 ? rep.presentationTimeOffset : 0;
        for (int i = 0; i < rep.timeline.size(); i++) {
            long[] entry = rep.timeline.get(i);
            long t = entry[0] >= 0 ? entry[0] : time;
            long d = entry[1];
            long repeat = entry[2] < 0 ? 0 : entry[2];
            if (t < 0) t = 0;
            if (d <= 0) continue;
            for (long r = 0; r <= repeat; r++) {
                out.add(new long[]{t + r * d, d});
                if (out.size() > 4000) return out;
            }
            time = t + (repeat + 1) * d;
        }
        return out;
    }

    /**
     * Dirección de un trozo concreto de una pista. Es el único sitio donde se
     * construye la dirección, así que la usa tanto el reproductor como la prueba
     * de enlace: lo que se comprueba es exactamente lo que se va a pedir.
     */
    static String segmentUrl(Rep rep, long number, long time) {
        if (rep == null) return null;
        if (rep.media.length() > 0) {
            String filled = fill(rep.media, rep, number, time < 0 ? 0 : time);
            return resolve(rep.baseUrl, filled);
        }
        if (!rep.listUrls.isEmpty()) {
            long index = number - rep.startNumber;
            if (index < 0 || index >= rep.listUrls.size()) return null;
            return resolve(rep.baseUrl, rep.listUrls.get((int) index));
        }
        return rep.baseUrl;
    }

    /**
     * Primer trozo que el reproductor pediría de una pista: {número, tiempo}.
     * En un canal en directo se apunta al trozo más reciente que ya debería estar
     * publicado, que es el que de verdad se pide al abrir el canal.
     */
    static long[] firstSegment(Mpd mpd, Rep rep) {
        if (rep == null) return null;
        if (rep.timeline.size() > 0) {
            ArrayList<long[]> all = expandTimeline(rep);
            if (all.isEmpty()) return null;
            int behind = mpd != null && mpd.dynamic ? 3 : 0;
            int index = all.size() > behind ? all.size() - behind - 1 : 0;
            if (index < 0) index = 0;
            return new long[]{rep.startNumber + index, all.get(index)[0]};
        }
        if (!rep.listUrls.isEmpty()) return new long[]{rep.startNumber, -1};
        if (rep.segDuration > 0 && rep.media.length() > 0) {
            long number = rep.startNumber;
            if (mpd != null && mpd.dynamic && mpd.availabilityStartMs > 0) {
                long segMs = rep.toMs(rep.segDuration);
                if (segMs > 0) {
                    long elapsed = System.currentTimeMillis() - mpd.availabilityStartMs;
                    if (elapsed > 0) {
                        number = rep.startNumber + (long) Math.floor(elapsed / (double) segMs) - 3;
                        if (number < rep.startNumber) number = rep.startNumber;
                    }
                }
            }
            // El valor de $Time$ es el tiempo del trozo en la escala del manifiesto:
            // pasar siempre -1 (que se convertía en 0) daba la misma dirección para
            // todos los trozos y el canal no arrancaba.
            return new long[]{number, (number - rep.startNumber) * rep.segDuration};
        }
        return null;
    }

    static String resolve(String base, String value) {
        if (value == null || value.length() == 0) return base == null ? "" : base;
        String clean = value.trim();
        if (clean.startsWith("http://") || clean.startsWith("https://")) return clean;
        if (base == null || base.length() == 0) return clean;
        try {
            return new java.net.URL(new java.net.URL(base), clean).toString();
        } catch (Exception e) {
            int slash = base.lastIndexOf('/');
            if (slash > 0) return base.substring(0, slash + 1) + clean;
            return clean;
        }
    }

    /**
     * Nombre de la etiqueta sin el prefijo de espacio de nombres: un manifiesto
     * suele escribir cenc:pssh o dashif:Laurl, y aquí interesa la parte final.
     */
    static String local(String name) {
        if (name == null) return "";
        int colon = name.indexOf(':');
        return colon >= 0 && colon < name.length() - 1 ? name.substring(colon + 1) : name;
    }

    private static String attr(XmlPullParser parser, String name, String fallback) {
        String value = parser.getAttributeValue(null, name);
        if (value == null || value.length() == 0) return fallback;
        return value.trim();
    }

    private static long longAttr(XmlPullParser parser, String name, long fallback) {
        String value = parser.getAttributeValue(null, name);
        if (value == null || value.length() == 0) return fallback;
        try {
            return Long.parseLong(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    /** Convierte "PT1H2M3.5S" en milisegundos. */
    static long durationMs(String value) {
        if (value == null || value.length() == 0) return -1;
        String text = value.trim().toUpperCase(Locale.US);
        if (text.charAt(0) != 'P') return -1;
        double total = 0;
        double number = 0;
        boolean inTime = false;
        for (int i = 1; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == 'T') {
                inTime = true;
                continue;
            }
            if ((c >= '0' && c <= '9') || c == '.') {
                int start = i;
                while (i < text.length() && ((text.charAt(i) >= '0' && text.charAt(i) <= '9') || text.charAt(i) == '.')) i++;
                try {
                    number = Double.parseDouble(text.substring(start, i));
                } catch (Exception e) {
                    number = 0;
                }
                i--;
                continue;
            }
            if (c == 'D') total += number * 24 * 3600;
            else if (c == 'H' && inTime) total += number * 3600;
            else if (c == 'M' && inTime) total += number * 60;
            else if (c == 'S') total += number;
            number = 0;
        }
        return Math.round(total * 1000.0);
    }

    /** Convierte "2024-05-01T10:00:00Z" en milisegundos desde 1970. */
    static long timestampMs(String value) {
        if (value == null || value.length() == 0) return -1;
        String text = value.trim();
        String[] patterns = new String[]{
                "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                "yyyy-MM-dd'T'HH:mm:ss'Z'",
                "yyyy-MM-dd'T'HH:mm:ss.SSSZ",
                "yyyy-MM-dd'T'HH:mm:ssZ"
        };
        for (int i = 0; i < patterns.length; i++) {
            try {
                SimpleDateFormat format = new SimpleDateFormat(patterns[i], Locale.US);
                format.setTimeZone(TimeZone.getTimeZone("UTC"));
                Date parsed = format.parse(text);
                if (parsed != null) return parsed.getTime();
            } catch (Exception ignored) {
                // Se prueba el siguiente formato.
            }
        }
        return -1;
    }
}
