package com.pocketbuilder.app_wslenytvplayer1792356127271;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.File;
import java.nio.ByteBuffer;

/**
 * Une un trozo de vídeo y un trozo de audio en un solo archivo reproducible.
 *
 * En DASH el vídeo y el audio casi siempre van en pistas separadas. El
 * reproductor de Android solo sabe reproducir UN archivo a la vez, así que aquí
 * se copian las dos pistas dentro de un mismo archivo MP4 temporal y se le
 * entrega ese archivo al reproductor.
 *
 * Dos cuidados que evitan el fallo genérico del reproductor de Android:
 *
 *  1. Los tiempos se escriben siempre en orden creciente. Con cuadros B (muy
 *     habituales en H.264/H.265) el lector de Android devuelve tiempos de
 *     presentación que van hacia atrás; si esos tiempos se copian tal cual, el
 *     creador del MP4 descarta muestras y el archivo sale dañado. Aquí se
 *     corrigen al vuelo para que el archivo resultante siempre sea válido.
 *  2. Antes de entregar el archivo se comprueba por dentro que tiene imagen de
 *     verdad (pistas, muestras y duración). Si el resultado no sirve, se avisa y
 *     se devuelve false para que el motor reproduzca el vídeo sin sonido en vez
 *     de mostrar un error sin motivo.
 */
final class Mux {

    private static final int BUFFER_LIMIT = 8 * 1024 * 1024;

    private Mux() {
    }

    static boolean combine(File videoFile, File audioFile, File outputFile) {
        MediaExtractor video = new MediaExtractor();
        MediaExtractor audio = new MediaExtractor();
        MediaMuxer muxer = null;
        try {
            video.setDataSource(videoFile.getAbsolutePath());
            audio.setDataSource(audioFile.getAbsolutePath());

            int videoTrack = firstTrack(video, "video/");
            int audioTrack = firstTrack(audio, "audio/");
            if (videoTrack < 0) {
                Diag.add("DASH · el trozo de vídeo no trae ninguna pista de imagen: no se une nada");
                return false;
            }

            MediaFormat videoFormat = video.getTrackFormat(videoTrack);
            MediaFormat audioFormat = audioTrack >= 0 ? audio.getTrackFormat(audioTrack) : null;

            long videoFirst = firstSampleTime(video, videoTrack);
            long audioFirst = audioTrack >= 0 ? firstSampleTime(audio, audioTrack) : -1;
            if (videoFirst < 0) videoFirst = 0;
            long base = videoFirst;
            if (audioFirst >= 0 && audioFirst < base) base = audioFirst;

            video.selectTrack(videoTrack);
            if (audioTrack >= 0) audio.selectTrack(audioTrack);

            if (outputFile.exists() && !outputFile.delete()) return false;
            muxer = new MediaMuxer(outputFile.getAbsolutePath(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int videoOut = muxer.addTrack(videoFormat);
            int audioOut = -1;
            if (audioFormat != null) audioOut = muxer.addTrack(audioFormat);
            muxer.start();

            long[] videoClock = new long[]{Long.MIN_VALUE};
            long[] audioClock = new long[]{Long.MIN_VALUE};
            long videoWritten = copyTrack(video, videoOut, muxer, base, videoFormat, videoClock);
            long audioWritten = 0;
            if (audioOut >= 0) {
                audioWritten = copyTrack(audio, audioOut, muxer, base, audioFormat, audioClock);
            }

            muxer.stop();
            muxer.release();
            muxer = null;

            if (videoWritten <= 0) {
                Diag.add("DASH · al unir las pistas no se pudo copiar ni una muestra de vídeo");
                deleteQuietly(outputFile);
                return false;
            }

            // Comprobación de verdad: ¿el archivo resultante tiene imagen y sonido?
            StreamCheck.Info check = StreamCheck.inspect(outputFile);
            if (!check.hasVideo()) {
                Diag.add("DASH · el archivo unido no se pudo leer (" + check.problemText()
                        + "): se reproducirá solo el vídeo");
                deleteQuietly(outputFile);
                return false;
            }
            Diag.add("DASH · trozo unido " + Net.readable((int) outputFile.length()) + ": "
                    + check.describe()
                    + (check.hasAudio() ? "" : " · sin pista de audio utilizable"));
            return check.hasAudio();
        } catch (Throwable t) {
            Diag.add("DASH · no se pudo unir audio y vídeo (" + Net.describe(t) + ")");
            deleteQuietly(outputFile);
            return false;
        } finally {
            if (muxer != null) {
                try {
                    muxer.release();
                } catch (Throwable ignored) {
                }
            }
            try {
                video.release();
            } catch (Throwable ignored) {
            }
            try {
                audio.release();
            } catch (Throwable ignored) {
            }
        }
    }

    private static int firstTrack(MediaExtractor extractor, String prefix) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            if (format == null) continue;
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith(prefix)) return i;
        }
        return -1;
    }

    /** Tiempo del primer trozo de una pista, dejando el lector al principio otra vez. */
    private static long firstSampleTime(MediaExtractor extractor, int track) {
        try {
            extractor.selectTrack(track);
            ByteBuffer scratch = ByteBuffer.allocate(1024 * 1024);
            long time = -1;
            if (extractor.readSampleData(scratch, 0) >= 0) time = extractor.getSampleTime();
            extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
            return time;
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * Copia una pista entera al archivo unido. Los tiempos se ajustan al mismo
     * origen y se obliga a que nunca retrocedan; si algún cuadro venía con el
     * tiempo desordenado (cuadros B), se coloca en su sitio y se anota.
     */
    private static long copyTrack(MediaExtractor extractor, int track, MediaMuxer muxer,
                                  long base, MediaFormat format, long[] clock) {
        int size = 1024 * 1024;
        try {
            if (format != null && format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                int value = format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE);
                if (value > size) size = value;
            }
        } catch (Throwable ignored) {
        }
        if (size > BUFFER_LIMIT) size = BUFFER_LIMIT;
        ByteBuffer buffer = ByteBuffer.allocate(size);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        long written = 0;
        long fixed = 0;
        while (true) {
            int read;
            try {
                read = extractor.readSampleData(buffer, 0);
            } catch (Throwable t) {
                break;
            }
            if (read < 0) break;
            long time;
            try {
                time = extractor.getSampleTime();
            } catch (Throwable t) {
                break;
            }
            long adjusted = time - base;
            if (adjusted < 0) adjusted = 0;
            if (clock[0] != Long.MIN_VALUE && adjusted <= clock[0]) {
                adjusted = clock[0] + 1;
                fixed++;
            }
            clock[0] = adjusted;
            info.set(0, read, adjusted, extractor.getSampleFlags());
            try {
                muxer.writeSampleData(track, buffer, info);
                written++;
            } catch (Throwable t) {
                Diag.add("DASH · al unir las pistas se perdió una muestra (" + Net.describe(t) + ")");
            }
            try {
                if (!extractor.advance()) break;
            } catch (Throwable t) {
                break;
            }
        }
        if (fixed > 0) {
            Diag.add("DASH · se corrigieron " + fixed + " tiempos desordenados al unir las pistas"
                    + " (cuadros B): sin esta corrección el archivo no se podría reproducir");
        }
        return written;
    }

    private static void deleteQuietly(File file) {
        if (file == null) return;
        try {
            file.delete();
        } catch (Throwable ignored) {
        }
    }
}
