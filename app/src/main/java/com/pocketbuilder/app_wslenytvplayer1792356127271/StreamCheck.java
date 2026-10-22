package com.pocketbuilder.app_wslenytvplayer1792356127271;

import android.media.MediaCodec;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Locale;

/**
 * Comprueba por dentro un archivo de vídeo descargado y los decodificadores del
 * teléfono.
 *
 * Es la pieza que convierte un fallo genérico del reproductor de Android
 * (what=1, extra=-2147483648, que no explica nada) en un motivo real:
 *
 *  - Si el archivo llegó vacío, a medias o sin pista de vídeo → trozo incompleto.
 *  - Qué pistas trae de verdad (vídeo, audio), con qué códec, cuántas muestras y
 *    cuánto dura cada una → permite ver si el audio y el vídeo coinciden.
 *  - Si este teléfono tiene un decodificador para ese formato (y cuál) → códec
 *    incompatible, la causa habitual de que MediaPlayer devuelva un código sin
 *    detalle cuando el canal usa H.265/HEVC o AV1 y el teléfono no los decodifica.
 *
 * Nunca lanza excepciones: si algo falla, devuelve el motivo en texto.
 */
final class StreamCheck {

    private static final int MAX_SAMPLES = 20000;
    private static final String KEY_CODECS = "codecs-string";
    private static final String KEY_MIME = "mime";

    private StreamCheck() {
    }

    /** Una pista del archivo (vídeo o audio). */
    static final class Track {
        String mime = "";
        String codec = "";
        int width;
        int height;
        long samples;
        long durationMs = -1;
        MediaFormat format;

        boolean isVideo() {
            return mime.toLowerCase(Locale.US).startsWith("video/");
        }

        boolean isAudio() {
            return mime.toLowerCase(Locale.US).startsWith("audio/");
        }

        String describe() {
            StringBuilder sb = new StringBuilder();
            sb.append(mime.length() == 0 ? "(tipo desconocido)" : mime);
            if (codec.length() > 0) sb.append(" · ").append(codec);
            if (width > 0) sb.append(" · ").append(width).append('×').append(height);
            sb.append(" · ").append(samples).append(" muestras");
            if (durationMs > 0) sb.append(" · ").append(ms(durationMs));
            return sb.toString();
        }
    }

    /** Resultado de mirar un archivo. */
    static final class Info {
        final ArrayList<Track> tracks = new ArrayList<Track>();
        boolean opened;
        long size;
        String problem = "";

        Track video() {
            for (int i = 0; i < tracks.size(); i++) {
                if (tracks.get(i).isVideo()) return tracks.get(i);
            }
            return null;
        }

        Track audio() {
            for (int i = 0; i < tracks.size(); i++) {
                if (tracks.get(i).isAudio()) return tracks.get(i);
            }
            return null;
        }

        boolean hasVideo() {
            Track v = video();
            return v != null && v.samples > 0;
        }

        boolean hasAudio() {
            Track a = audio();
            return a != null && a.samples > 0;
        }

        /** true si el archivo se abrió pero no trae imagen utilizable. */
        boolean emptyVideo() {
            return opened && !hasVideo();
        }

        String describe() {
            if (!opened) {
                return problem.length() == 0 ? "no se pudo abrir" : problem;
            }
            StringBuilder sb = new StringBuilder();
            Track v = video();
            Track a = audio();
            sb.append(v == null ? "sin pista de vídeo" : "vídeo " + v.describe());
            sb.append(" · ");
            sb.append(a == null ? "sin pista de audio" : "audio " + a.describe());
            if (tracks.size() > 2) sb.append(" · ").append(tracks.size()).append(" pistas en total");
            return sb.toString();
        }

        /** Motivo corto cuando el archivo no sirve. */
        String problemText() {
            if (!opened) {
                return problem.length() == 0 ? "el reproductor de Android no pudo abrir el archivo"
                        : problem;
            }
            if (!hasVideo()) {
                Track v = video();
                if (v == null) return "el archivo no trae ninguna pista de vídeo (" + describe() + ")";
                return "la pista de vídeo no trae ni un cuadro (" + v.describe() + ")";
            }
            return "";
        }
    }

    /**
     * Mira un archivo descargado. Devuelve siempre un resultado (nunca lanza).
     * Si el archivo es un trozo suelto de DASH ya unido a sus datos iniciales, se
     * leen sus pistas igual que si fuera un archivo completo.
     */
    static Info inspect(File file) {
        Info info = new Info();
        MediaExtractor extractor = null;
        try {
            if (file == null || !file.exists()) {
                info.problem = "el archivo no existe";
                return info;
            }
            info.size = file.length();
            if (info.size <= 0) {
                info.problem = "el archivo llegó vacío (0 bytes)";
                return info;
            }
            extractor = new MediaExtractor();
            extractor.setDataSource(file.getAbsolutePath());
            int count = extractor.getTrackCount();
            if (count <= 0) {
                info.problem = "el archivo no contiene ninguna pista";
                return info;
            }
            ByteBuffer buffer = ByteBuffer.allocate(2 * 1024 * 1024);
            for (int i = 0; i < count; i++) {
                MediaFormat format = null;
                try {
                    format = extractor.getTrackFormat(i);
                } catch (Throwable ignored) {
                }
                if (format == null) continue;
                Track track = new Track();
                track.format = format;
                track.mime = string(format, KEY_MIME);
                if (track.mime.length() == 0) track.mime = string(format, MediaFormat.KEY_MIME);
                track.codec = string(format, KEY_CODECS);
                track.width = integer(format, MediaFormat.KEY_WIDTH, 0);
                track.height = integer(format, MediaFormat.KEY_HEIGHT, 0);
                long durationUs = longValue(format, MediaFormat.KEY_DURATION, 0);
                if (durationUs > 0) track.durationMs = durationUs / 1000;
                countSamples(extractor, i, buffer, track);
                info.tracks.add(track);
            }
            info.opened = true;
        } catch (Throwable t) {
            info.problem = "el reproductor de Android no pudo abrir el archivo (" + Net.describe(t) + ")";
        } finally {
            if (extractor != null) {
                try {
                    extractor.release();
                } catch (Throwable ignored) {
                }
            }
        }
        return info;
    }

    /** Cuenta las muestras reales de una pista (para detectar trozos a medias). */
    private static void countSamples(MediaExtractor extractor, int index, ByteBuffer buffer, Track track) {
        try {
            extractor.selectTrack(index);
            long first = -1;
            long last = -1;
            long samples = 0;
            while (samples < MAX_SAMPLES && extractor.readSampleData(buffer, 0) >= 0) {
                long time = extractor.getSampleTime();
                if (first < 0) first = time;
                last = time;
                samples++;
                if (!extractor.advance()) break;
            }
            track.samples = samples;
            if (first >= 0 && last >= first) track.durationMs = (last - first) / 1000;
            extractor.unselectTrack(index);
        } catch (Throwable t) {
            // Se deja samples=0: significa "no se pudo leer nada de esta pista".
        }
    }

    /**
     * Nombre del decodificador que usaría este teléfono para ese formato, o cadena
     * vacía si no tiene ninguno. Es la comprobación que distingue "el enlace está
     * bien pero este teléfono no puede con el códec".
     */
    static String decoderName(MediaFormat format) {
        if (format == null) return "";
        String mime = string(format, KEY_MIME);
        if (mime.length() == 0) return "";
        try {
            MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
            String name = list.findDecoderForFormat(format);
            if (name != null && name.length() > 0) return name;
        } catch (Throwable ignored) {
        }
        try {
            MediaCodec codec = MediaCodec.createDecoderByType(mime);
            if (codec != null) {
                String name = codec.getName();
                codec.release();
                if (name != null) return name;
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    /** true si el teléfono puede decodificar ese formato. */
    static boolean canDecode(MediaFormat format) {
        return decoderName(format).length() > 0;
    }

    /** Explicación legible del decodificador: cuál es o por qué no hay. */
    static String decoderNote(MediaFormat format) {
        if (format == null) return "no se pudo leer el formato de la pista";
        String mime = string(format, KEY_MIME);
        if (mime.length() == 0) return "la pista no declara su tipo de contenido";
        String name = decoderName(format);
        if (name.length() == 0) {
            return "este teléfono no tiene ningún decodificador para " + mime
                    + " (el códec del canal no es compatible con el aparato)";
        }
        return mime + " con " + name + softwareNote(name);
    }

    /** " (por software)" / " (por hardware)" a partir del nombre del decodificador. */
    static String softwareNote(String codecName) {
        if (codecName == null || codecName.length() == 0) return "";
        String low = codecName.toLowerCase(Locale.US);
        if (low.indexOf("google") >= 0 || low.indexOf("c2.android") >= 0
                || low.indexOf(".sw.") >= 0 || low.indexOf("swdec") >= 0) {
            return " (por software: más lento)";
        }
        return " (por hardware)";
    }

    /**
     * Formato aproximado a partir de lo que dice el manifiesto (mimeType y codecs),
     * para comprobar ANTES de descargar si el teléfono tiene decodificador.
     * La comprobación definitiva se hace con el formato real del trozo descargado.
     */
    static MediaFormat formatFor(String mimeType, String codecs) {
        String type = mimeType == null ? "" : mimeType.trim().toLowerCase(Locale.US);
        if (type.length() == 0) type = guessMime(codecs);
        MediaFormat format = new MediaFormat();
        format.setString(KEY_MIME, type);
        if (codecs != null && codecs.trim().length() > 0) {
            try {
                format.setString(KEY_CODECS, codecs.trim());
            } catch (Throwable ignored) {
            }
        }
        return format;
    }

    private static String guessMime(String codecs) {
        String low = codecs == null ? "" : codecs.toLowerCase(Locale.US);
        if (low.indexOf("mp4a") >= 0 || low.indexOf("ac-3") >= 0 || low.indexOf("ec-3") >= 0) {
            return "audio/mp4";
        }
        if (low.indexOf("opus") >= 0) return "audio/opus";
        if (low.indexOf("vorbis") >= 0) return "audio/vorbis";
        if (low.indexOf("vp09") >= 0 || low.indexOf("vp08") >= 0) return "video/webm";
        return "video/mp4";
    }

    /** Resumen corto de un tipo MIME con sus códecs, para los mensajes. */
    static String formatName(String mimeType, String codecs) {
        String mime = mimeType == null || mimeType.length() == 0 ? "(sin tipo)" : mimeType;
        if (codecs == null || codecs.length() == 0) return mime;
        return mime + " · " + codecs;
    }

    /** Convierte milisegundos en un texto corto (1,2 s / 2:05). */
    static String ms(long millis) {
        if (millis <= 0) return "0 s";
        if (millis < 10000) {
            return String.format(Locale.US, "%.1f s", millis / 1000f).replace('.', ',');
        }
        long seconds = millis / 1000;
        long minutes = seconds / 60;
        long rest = seconds % 60;
        if (minutes > 0) return String.format(Locale.US, "%d:%02d min", minutes, rest);
        return seconds + " s";
    }

    private static String string(MediaFormat format, String key) {
        try {
            String value = format.getString(key);
            return value == null ? "" : value.trim();
        } catch (Throwable t) {
            return "";
        }
    }

    private static int integer(MediaFormat format, String key, int fallback) {
        try {
            if (!format.containsKey(key)) return fallback;
            return format.getInteger(key);
        } catch (Throwable t) {
            return fallback;
        }
    }

    private static long longValue(MediaFormat format, String key, long fallback) {
        try {
            if (!format.containsKey(key)) return fallback;
            return format.getLong(key);
        } catch (Throwable t) {
            return fallback;
        }
    }
}
