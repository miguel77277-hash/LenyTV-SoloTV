package com.pocketbuilder.app_wslenytvplayer1792356127271;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.view.Surface;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Reproductor DASH de LENY TV.
 *
 * LEE el manifiesto .mpd, elige la calidad, y va pidiendo los trozos (segmentos)
 * del canal para reproducirlos seguidos. Si el manifiesto trae el vídeo y el
 * audio en pistas separadas —lo normal en DASH— copia las dos pistas en un solo
 * archivo temporal antes de reproducirlo, para que el canal tenga imagen y
 * sonido. En emisiones en directo salta siempre al trozo más reciente.
 *
 * El trabajo pesado (descargar y unir) se hace en un hilo aparte; el reproductor
 * de Android solo se toca desde el hilo de la pantalla, con dos reproductores que
 * se van relevando para que la imagen no se corte entre trozo y trozo.
 */
final class DashPlayer {

    interface Listener {
        /** Línea de estado para el registro y la barra superior. */
        void onStatus(String message);

        /** Cargando (todavía sin imagen). */
        void onBuffering(boolean active);

        /** Tamaño de la imagen ya en marcha. */
        void onVideoSize(int width, int height);

        /** No se pudo reproducir. */
        void onError(String title, String body, boolean retryable);

        /** Se acabó el contenido (no ocurre en directo). */
        void onFinished();
    }

    /** Trozo ya preparado, listo para entregar al reproductor. */
    private static final class Piece {
        /** El archivo que se reproduce (con sonido si se pudieron unir las pistas). */
        final File file;
        /** El trozo de vídeo tal cual llegó, por si hay que repetirlo sin sonido. */
        final File rawVideo;
        final long startMs;
        final long number;
        final long time;
        /** true si el archivo que se reproduce lleva el audio unido al vídeo. */
        final boolean muxed;
        /** Lo que se vio por dentro del trozo (pistas, códecs, muestras). */
        final String check;

        Piece(File file, File rawVideo, long startMs, long number, long time, boolean muxed, String check) {
            this.file = file;
            this.rawVideo = rawVideo;
            this.startMs = startMs;
            this.number = number;
            this.time = time;
            this.muxed = muxed;
            this.check = check == null ? "" : check;
        }
    }

    /** Uno de los dos reproductores que se van turnando. */
    private static final class Slot {
        MediaPlayer player;
        File file;
        long startMs;
        Piece piece;
        boolean prepared;
        boolean chained;
        boolean playing;
    }

    private static final long MAX_SEGMENT_BYTES = 48L * 1024L * 1024L;
    private static final int CACHE_PIECES = 3;
    private static final int LIVE_BEHIND_SEGMENTS = 3;
    private static final long TICK_MS = 250L;
    /** Si en este tiempo no se ha reproducido ni un trozo, se explica el motivo. */
    private static final long NO_IMAGE_TIMEOUT_MS = 25000L;
    /** Primera entrada del menú CALIDAD: la app elige sola la mejor resolución. */
    static final String AUTOMATIC_LABEL = "Automática (recomendada)";

    private final Context context;
    private final Handler ui;
    private final Listener listener;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ArrayBlockingQueue<Piece> ready = new ArrayBlockingQueue<Piece>(CACHE_PIECES);
    private final ArrayList<Mpd.Rep> videoReps = new ArrayList<Mpd.Rep>();
    private final ArrayList<Mpd.Rep> audioReps = new ArrayList<Mpd.Rep>();
    /** Trozos pendientes de descargar, en el hilo de trabajo: {número, tiempo}. */
    private final ArrayList<long[]> plan = new ArrayList<long[]>();

    private volatile boolean released;
    private volatile boolean paused;
    private volatile boolean restartRequested;
    private volatile boolean stopped;

    private Mpd mpd;
    private Mpd.Rep selectedVideo;
    private Mpd.Rep selectedAudio;
    private int qualityIndex;
    private int requestedQuality = -1;
    /** false = Automática (la app elige); true = la resolución la eligió la persona. */
    private volatile boolean manualQuality;
    /** Último aviso preciso sobre la calidad elegida. */
    private volatile String qualityNotice = "";
    /** Avance extra del punto de petición cuando el directo va por delante del reloj. */
    private int liveSkip;
    /** Resolución que el teléfono no pudo decodificar y a la que se volvió. */
    private volatile String qualityFallback = "";
    private String sourceUrl = "";
    private Map<String, String> headers = new TreeMap<String, String>();
    private Surface surface;
    private File cacheDir;
    private byte[] videoInit;
    private byte[] audioInit;
    /** Lista de tiempos de la pista de audio: puede no coincidir con la de vídeo. */
    private ArrayList<long[]> audioTimeline;
    private boolean videoInitFailed;
    private boolean audioInitFailed;

    private final Slot slotA = new Slot();
    private final Slot slotB = new Slot();
    private Slot active;
    private Slot pending;

    private int videoWidth;
    private int videoHeight;
    private boolean firstPieceStarted;
    private int consecutiveErrors;
    private long lastNumber = -1;
    private long playClockMs;
    private long activeStartMs = -1;
    private boolean singleFileMode;

    /** Momento en el que empezó este intento (para el vigilante de "sin imagen"). */
    private long startedAtMs;
    /** Último resultado HTTP anotado: permite explicar por qué no llega la imagen. */
    private volatile String lastHttpNote = "";
    /** Etapa que se está pidiendo ahora mismo (manifiesto, datos iniciales, trozo...). */
    private volatile String stage = "manifiesto";
    /** Resultados HTTP por etapa, para poder decir exactamente qué falló. */
    private final ArrayList<String> httpNotes = new ArrayList<String>();
    /** Lo que se vio por dentro del último trozo comprobado. */
    private volatile String lastCheckNote = "";
    /** false cuando unir audio y vídeo ha dado un archivo que el teléfono rechaza. */
    private boolean muxEnabled = true;
    /** El códec de la calidad elegida ya se comprobó contra los decodificadores del teléfono. */
    private boolean codecChecked;
    /** Calidades ya descartadas por falta de decodificador, para no dar vueltas. */
    private final ArrayList<Integer> triedQualities = new ArrayList<Integer>();
    /** Trozos seguidos que ya no existen en el servidor (recolocación en directo). */
    private int missingStreak;
    /** Descargas lentas seguidas: si se acumulan, se baja de calidad. */
    private int slowStreak;
    /** Trozos pedidos en total y sin imagen todavía, para el mensaje de fallo. */
    private int piecesBuilt;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            try {
                step();
            } catch (Throwable t) {
                Diag.add("DASH · aviso del reproductor: " + Net.describe(t));
            } finally {
                if (!released) ui.postDelayed(this, TICK_MS);
            }
        }
    };

    DashPlayer(Context context, Handler ui, Listener listener) {
        this.context = context.getApplicationContext();
        this.ui = ui;
        this.listener = listener;
        releaseCache();
    }

    // ------------------------------------------------------------------ mando

    /** Empieza a reproducir un manifiesto DASH. */
    void start(String mpdUrl, Map<String, String> httpHeaders) {
        start(mpdUrl, httpHeaders, -1);
    }

    /**
     * Empieza a reproducir un manifiesto DASH.
     *
     * @param preferredQuality resolución pedida antes de arrancar (0 y siguientes,
     *                         en el mismo orden que el menú CALIDAD; -1 = automática).
     *                         Se usa cuando la persona elige la calidad antes de que
     *                         el motor esté en marcha (por ejemplo en un canal que se
     *                         estaba reproduciendo con el reproductor del sistema).
     */
    void start(String mpdUrl, Map<String, String> httpHeaders, int preferredQuality) {
        released = false;
        stopped = false;
        paused = false;
        restartRequested = false;
        sourceUrl = mpdUrl == null ? "" : mpdUrl;
        headers = httpHeaders == null ? new TreeMap<String, String>() : new TreeMap<String, String>(httpHeaders);
        lastNumber = -1;
        firstPieceStarted = false;
        consecutiveErrors = 0;
        playClockMs = System.currentTimeMillis();
        activeStartMs = -1;
        singleFileMode = false;
        muxEnabled = true;
        codecChecked = false;
        manualQuality = preferredQuality >= 0;
        qualityIndex = preferredQuality >= 0 ? preferredQuality : 0;
        requestedQuality = preferredQuality;
        qualityNotice = "";
        qualityFallback = "";
        liveSkip = 0;
        triedQualities.clear();
        lastCheckNote = "";
        lastHttpNote = "";
        stage = "manifiesto";
        synchronized (this) {
            httpNotes.clear();
        }
        ui.post(tick);
        worker.execute(new Runnable() {
            @Override
            public void run() {
                runEngine();
            }
        });
    }

    void setSurface(Surface value) {
        surface = value;
        applySurface(slotA);
        applySurface(slotB);
    }

    boolean isPlaying() {
        return !paused && !stopped && (singleFileMode || active != null || !ready.isEmpty());
    }

    boolean isSingleFile() {
        return singleFileMode;
    }

    void setPaused(boolean value) {
        paused = value;
        if (singleFileMode) {
            MediaPlayer player = active != null ? active.player : null;
            if (player != null) {
                try {
                    if (value) player.pause();
                    else player.start();
                } catch (Throwable ignored) {
                }
            }
            return;
        }
        Slot slot = active;
        if (slot != null && slot.player != null) {
            try {
                if (value) slot.player.pause();
                else slot.player.start();
            } catch (Throwable ignored) {
            }
        }
    }

    void release() {
        released = true;
        stopped = true;
        restartRequested = false;
        ui.removeCallbacks(tick);
        worker.shutdownNow();
        clearSlots();
        try {
            worker.awaitTermination(300, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (Throwable ignored) {
        }
        drainQueue();
        releaseCache();
    }

    // -------------------------------------------------------------- calidades

    int qualityCount() {
        ArrayList<Mpd.Rep> reps = qualitySnapshot();
        return reps.isEmpty() ? 0 : reps.size() + 1;
    }

    /** Copia de las calidades del manifiesto (el manifiesto se lee en otro hilo). */
    private synchronized ArrayList<Mpd.Rep> qualitySnapshot() {
        return new ArrayList<Mpd.Rep>(videoReps);
    }

    String qualityLabel(int index) {
        ArrayList<Mpd.Rep> reps = qualitySnapshot();
        if (index == 0 && !reps.isEmpty()) return AUTOMATIC_LABEL;
        int rep = index - 1;
        if (rep < 0 || rep >= reps.size()) return "";
        return reps.get(rep).label();
    }

    int qualityIndex() {
        ArrayList<Mpd.Rep> reps = qualitySnapshot();
        if (reps.isEmpty()) return 0;
        if (!manualQuality) return 0;
        int index = qualityIndex;
        if (index < 0) index = 0;
        if (index >= reps.size()) index = reps.size() - 1;
        return index + 1;
    }

    /** true si el manifiesto ofrece una sola resolución (no hay nada que elegir). */
    boolean hasSingleQuality() {
        return qualitySnapshot().size() == 1;
    }

    /** Texto exacto para cuando el canal solo ofrece una resolución. */
    String singleQualityMessage() {
        ArrayList<Mpd.Rep> reps = qualitySnapshot();
        if (reps.size() != 1) return "";
        return "Este canal solo ofrece una resolución: " + reps.get(0).label()
                + ".\n\nLENY TV la reproduce directamente y no hay otra que elegir. Si el canal tuviera"
                + " más calidades, aparecerían aquí al leer su manifiesto .mpd.";
    }

    /** Resolución que se está usando ahora mismo, en texto corto. */
    String currentQualityText() {
        Mpd.Rep rep = selectedVideo;
        if (rep == null) return "";
        return rep.label() + (manualQuality ? " (elegida)" : " (automática)");
    }

    /**
     * Aplica la resolución elegida en el menú CALIDAD.
     *
     * @param position 0 = Automática; 1 y siguientes = las calidades del manifiesto,
     *                 en el mismo orden en que las muestra el menú.
     * @return aviso preciso para la persona (cadena vacía si no hace falta).
     */
    String setQuality(int position) {
        qualityNotice = "";
        ArrayList<Mpd.Rep> reps = qualitySnapshot();
        if (reps.isEmpty()) {
            qualityNotice = "El manifiesto DASH todavía no ha entregado sus resoluciones."
                    + " Espera a que aparezca la imagen y vuelve a abrir CALIDAD.";
            return qualityNotice;
        }
        if (position == 0) {
            if (!manualQuality) {
                return firstPieceStarted ? "" : "Se usará la calidad automática en cuanto empiece la imagen.";
            }
            manualQuality = false;
            requestedQuality = -1;
            qualityFallback = "";
            Diag.add("DASH · calidad automática: la app elige la mejor resolución posible del canal");
            qualityNotice = "Calidad automática: LENY TV elige la mejor resolución que tu teléfono"
                    + " y tu conexión puedan con este canal.";
            if (!released) restartRequested = true;
            return qualityNotice;
        }
        int index = position - 1;
        if (index >= reps.size()) {
            qualityNotice = "Esa resolución ya no está en el manifiesto del canal."
                    + " Vuelve a abrir CALIDAD para ver las que ofrece ahora.";
            return qualityNotice;
        }
        Mpd.Rep rep = reps.get(index);
        if (manualQuality && index == qualityIndex && firstPieceStarted) {
            return "Ya estabas viendo " + rep.label() + ".";
        }
        manualQuality = true;
        qualityFallback = "";
        requestedQuality = index;
        qualityIndex = index;
        boolean canDecode = decodable(rep);
        if (!canDecode) {
            qualityNotice = "Tu teléfono no declara decodificador para " + rep.label()
                    + ". Se intentará igualmente; si no puede, LENY TV volverá a una resolución compatible.";
        }
        Diag.add("DASH · resolución elegida a mano: " + rep.label()
                + " · ¿el teléfono puede decodificarla? " + (canDecode ? "sí" : "no, según el manifiesto"));
        if (!released) restartRequested = true;
        return qualityNotice;
    }

    /** Descripción del manifiesto y de la pista elegida, para el registro. */
    String statusLine() {
        StringBuilder sb = new StringBuilder("DASH");
        if (mpd != null) {
            sb.append(" · ").append(mpd.dynamic ? "en directo" : "bajo demanda");
            if (selectedVideo != null && selectedVideo.height > 0) {
                sb.append(" · ").append(selectedVideo.width).append("×").append(selectedVideo.height);
                String codec = selectedVideo.codecName();
                if (codec.length() > 0) sb.append(" ").append(codec);
            }
            if (selectedAudio != null) {
                String codec = selectedAudio.codecName();
                if (codec.length() > 0) sb.append(" · audio ").append(codec);
            }
            sb.append(" · ").append(videoReps.size())
                    .append(videoReps.size() == 1 ? " calidad" : " calidades");
            if (videoReps.size() > 1) {
                sb.append(manualQuality ? " · resolución elegida a mano" : " · calidad automática");
            }
            if (qualityFallback.length() > 0) sb.append(" · ").append(qualityFallback);
        }
        return sb.toString();
    }

    /** Texto del reloj: en directo, el tiempo que lleva viéndose; si no, posición y duración. */
    String timeText() {
        if (singleFileMode) {
            MediaPlayer player = active != null ? active.player : null;
            if (player == null) return "--:--";
            try {
                int duration = player.getDuration();
                int position = Math.max(0, player.getCurrentPosition());
                if (duration > 0) return format(position) + " / " + format(duration);
                return "EN DIRECTO " + format(position);
            } catch (Throwable t) {
                return "--:--";
            }
        }
        long positionMs = positionMs();
        if (mpd != null && mpd.dynamic) return "EN DIRECTO " + format(positionMs);
        long totalMs = mpd == null ? 0 : mpd.mediaDurationMs;
        if (totalMs > 0) return format(positionMs) + " / " + format(totalMs);
        return format(positionMs);
    }

    long positionMs() {
        long base = activeStartMs >= 0 ? activeStartMs : 0;
        if (base <= 0) {
            // Sin pistas de la duración del trozo: se cuenta el tiempo desde que empezó.
            return System.currentTimeMillis() - playClockMs;
        }
        MediaPlayer player = active != null ? active.player : null;
        if (player != null) {
            try {
                int inside = player.getCurrentPosition();
                if (inside > 0) return base + inside;
            } catch (Throwable ignored) {
            }
        }
        return base;
    }

    static String format(long millis) {
        if (millis < 0) millis = 0;
        long seconds = millis / 1000;
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long rest = seconds % 60;
        if (hours > 0) return String.format(Locale.US, "%d:%02d:%02d", hours, minutes, rest);
        return String.format(Locale.US, "%d:%02d", minutes, rest);
    }

    // ------------------------------------------------------------- el motor

    private void runEngine() {
        while (!released) {
            try {
                runPipeline();
                return;
            } catch (RestartSignal restart) {
                Diag.add("DASH · se vuelve a empezar para aplicar el cambio de calidad");
            } catch (FatalError fatal) {
                fail(fatal.title, fatal.getMessage());
                return;
            } catch (Cancelled cancelled) {
                return;
            } catch (Throwable t) {
                fail("No se pudo reproducir el canal DASH", explain(t));
                return;
            }
        }
    }

    @SuppressWarnings("serial")
    private static final class RestartSignal extends RuntimeException {
        RestartSignal() {
            super("reinicio");
        }
    }

    private static final class Cancelled extends RuntimeException {
        Cancelled() {
            super("cancelado");
        }
    }

    private synchronized void adoptReps(Mpd manifest) {
        videoReps.clear();
        videoReps.addAll(manifest.videoReps());
        audioReps.clear();
        audioReps.addAll(manifest.audioReps());
    }

    private void runPipeline() throws Exception {
        checkRestart();
        singleFileMode = false;
        startedAtMs = System.currentTimeMillis();
        lastHttpNote = "";
        piecesBuilt = 0;
        missingStreak = 0;
        slowStreak = 0;
        Diag.add("DASH · pidiendo el manifiesto · cabeceras " + Net.describeHeaders(headers)
                + " · " + Diag.channelUrl(sourceUrl));
        stage = "manifiesto";
        String xml;
        try {
            xml = new String(fetchBytes(sourceUrl, headers, 4 * 1024 * 1024), "UTF-8");
        } catch (MissingSegment missing) {
            lastHttpNote = missing.getMessage();
            throw new FatalError("La dirección del manifiesto DASH no existe",
                    "El servidor respondió: " + missing.getMessage() + ".\n\nEl enlace del canal puede"
                            + " haber caducado o estar mal escrito. Comprueba la dirección en tu proveedor.");
        } catch (IOException netError) {
            throw new FatalError("No se pudo descargar el manifiesto DASH", networkReason(netError));
        }
        Mpd manifest;
        try {
            manifest = Mpd.parse(xml, sourceUrl);
        } catch (Throwable parseError) {
            Diag.add("DASH · el manifiesto no se pudo interpretar: " + Net.describe(parseError)
                    + " · primeras líneas: " + Net.snippetOf(xml));
            throw new FatalError("El manifiesto DASH no es válido",
                    "El archivo se descargó, pero no se pudo interpretar como manifiesto DASH: "
                            + Net.describe(parseError) + ".\n\nPrimeras líneas recibidas: "
                            + Net.snippetOf(xml) + "\n\nSuele indicar que el enlace devuelve una página web,"
                            + " un texto de error del proveedor o un archivo incompleto.");
        }
        mpd = manifest;
        Diag.add("DASH · manifiesto leído: " + manifest.summary());
        if (!manifest.protections.isEmpty()) {
            for (int i = 0; i < manifest.protections.size(); i++) {
                Diag.add("DASH · protección declarada: " + manifest.protections.get(i));
            }
        }
        adoptReps(manifest);
        if (videoReps.isEmpty() && audioReps.isEmpty()) {
            throw new FatalError("El manifiesto DASH no trae pistas",
                    "El archivo .mpd se descargó correctamente pero no declara ninguna pista de vídeo o audio."
                            + " Puede ser un manifiesto incompleto o un error del proveedor.");
        }
        if (videoReps.isEmpty()) {
            throw new FatalError("El manifiesto DASH solo trae audio",
                    "Este canal no ofrece pista de vídeo en el manifiesto, así que no hay imagen que mostrar.");
        }

        if (requestedQuality >= 0 && requestedQuality < videoReps.size()) {
            qualityIndex = requestedQuality;
        } else if (manualQuality && qualityIndex >= 0 && qualityIndex < videoReps.size()) {
            Diag.add("DASH · se mantiene la resolución elegida a mano: "
                    + videoReps.get(qualityIndex).label());
        } else {
            qualityIndex = chooseQuality(videoReps);
        }
        requestedQuality = -1;
        Mpd.Rep video = videoReps.get(qualityIndex);
        Mpd.Rep audio = chooseAudio(audioReps);
        selectedVideo = video;
        selectedAudio = audio;
        audioTimeline = audio == null ? null : Mpd.expandTimeline(audio);
        Diag.add("DASH · calidad elegida: " + video.label() + " · "
                + (audio == null ? "sin pista de audio aparte (el vídeo trae el sonido o no hay audio)"
                : "audio " + audio.label()));

        // ---- Protección de contenido (DRM) ----
        // Antes se detenía el canal con solo ver un ContentProtection en el
        // manifiesto. Ahora se comprueba la PISTA que se va a usar y, además, si sus
        // datos traen de verdad muestras cifradas (encv/enca). Un manifiesto que
        // declara DRM pero entrega los bytes sin cifrar ya se puede reproducir.
        if (video.protectedContent) {
            String system = video.protectionSystem.length() == 0
                    ? manifest.videoProtectionSystem() : video.protectionSystem;
            Boolean encrypted = trackLooksEncrypted(video);
            if (encrypted == null || encrypted.booleanValue()) {
                throw new FatalError("Canal protegido con DRM", protectedBody(manifest, system,
                        encrypted == null));
            }
            Diag.add("DASH · el manifiesto declara DRM (" + system + "), pero los datos iniciales del vídeo"
                    + " no traen muestras cifradas (no hay encv/enca): se intenta reproducir el canal");
            EngineInfo.license("el manifiesto declara DRM (" + system + "), pero los datos llegan sin"
                    + " cifrar · se intenta reproducir");
        }
        if (audio != null && audio.protectedContent) {
            Diag.add("DASH · la pista de audio declara DRM: se reproduce el vídeo sin ese audio aparte");
            audio = null;
            selectedAudio = null;
            audioTimeline = null;
        }

        if (video.singleFile) {
            singleFileMode = true;
            startSingleFile(video);
            // Este tipo de manifiesto se reproduce de un solo archivo, pero la
            // persona puede pedir otra resolución: se espera aquí (en vez de dejar
            // el motor parado) para poder reiniciar con la elegida.
            awaitQualityChange();
            return;
        }
        if (video.media.length() == 0 && video.listUrls.isEmpty() && video.timeline.isEmpty()) {
            throw new FatalError("El manifiesto DASH no dice cómo pedir los trozos",
                    "El archivo .mpd no incluye plantilla de segmentos, ni lista, ni dirección de archivo único.");
        }

        videoInit = null;
        audioInit = null;
        videoInitFailed = false;
        audioInitFailed = false;
        plan.clear();
        lastNumber = -1;

        while (!released) {
            checkRestart();
            checkNoImageWatchdog();
            if (plan.isEmpty()) {
                if (!refill()) {
                    if (mpd.dynamic) {
                        sleepQuiet(800);
                        continue;
                    }
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if (!released) listener.onFinished();
                        }
                    });
                    return;
                }
            }
            if (ready.remainingCapacity() == 0) {
                sleepQuiet(120);
                continue;
            }
            long[] segment = plan.remove(0);
            Piece piece = null;
            long buildStart = System.currentTimeMillis();
            try {
                piece = buildPiece(segment[0], segment[1]);
            } catch (Cancelled cancelled) {
                throw cancelled;
            } catch (MissingSegment missing) {
                Diag.add("DASH · el trozo " + segment[0] + " no existe en el servidor · " + missing.getMessage());
                if (mpd.dynamic) {
                    // En directo los trozos caducan: si el teléfono se queda atrás (o su
                    // reloj no coincide con el del servidor) hay que releer el manifiesto
                    // y recolocarse en el trozo más reciente.
                    missingStreak++;
                    lastNumber = segment[0];
                    if (missingStreak >= 2) {
                        missingStreak = 0;
                        // Si el directo va por delante de lo que dice el reloj del
                        // servidor, se avanza el punto de petición en lugar de pedir
                        // una y otra vez trozos que ya no existen.
                        if (mpd.availabilityStartMs > 0 && selectedVideo != null
                                && selectedVideo.timeline.isEmpty() && selectedVideo.segDuration > 0) {
                            liveSkip += 2;
                            if (liveSkip > 900) liveSkip = 900;
                            Diag.add("DASH · el directo va por delante: se avanza " + liveSkip
                                    + " trozos el punto de petición");
                        }
                        reloadManifest();
                    }
                    continue;
                }
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!released) listener.onFinished();
                    }
                });
                return;
            } catch (BadSegment bad) {
                // El trozo llegó vacío, a medias o sin imagen utilizable. No se le
                // entrega al reproductor (que solo diría un código sin detalle).
                Diag.add("DASH · trozo descartado · " + bad.getMessage());
                consecutiveErrors++;
                if (consecutiveErrors >= 4) {
                    throw new FatalError("El canal no entrega trozos utilizables",
                            "Se han pedido varios trozos seguidos y ninguno llegó completo o legible.\n\n"
                                    + bad.getMessage() + ".\n"
                                    + "Resultados del servidor: " + httpHistory() + ".\n"
                                    + "Cabeceras enviadas: " + Net.describeHeaders(headers) + ".");
                }
                continue;
            } catch (Throwable t) {
                Diag.add("DASH · fallo al pedir el trozo " + segment[0] + ": " + Net.describe(t));
                consecutiveErrors++;
                if (consecutiveErrors >= 4) {
                    throw new FatalError("No se pudieron descargar los trozos del canal",
                            "Se pidieron varios trozos seguidos y el servidor no los entregó.\n\n"
                                    + MediaErrors.describeNetwork(t, headers));
                }
                continue;
            }
            piecesBuilt++;
            checkRestart();
            lastNumber = segment[0];
            consecutiveErrors = 0;
            watchSpeed(System.currentTimeMillis() - buildStart);
            try {
                while (!ready.offer(piece, 200, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    if (released) {
                        deleteQuietly(piece.file);
                        deleteQuietly(piece.rawVideo);
                        throw new Cancelled();
                    }
                    if (restartRequested) {
                        // Se pide otra resolución: el trozo que ya no se va a usar se
                        // borra antes de reiniciar.
                        deleteQuietly(piece.file);
                        deleteQuietly(piece.rawVideo);
                        checkRestart();
                    }
                }
            } catch (InterruptedException interrupted) {
                throw new Cancelled();
            }
        }
    }

    /**
     * Si el motor lleva demasiado tiempo sin conseguir reproducir ni un trozo, se
     * corta con el motivo real (último código HTTP, trozos pedidos y cabeceras) en
     * lugar de dejar el círculo de carga girando para siempre.
     */
    private void checkNoImageWatchdog() {
        if (firstPieceStarted || singleFileMode) return;
        if (System.currentTimeMillis() - startedAtMs < NO_IMAGE_TIMEOUT_MS) return;
        StringBuilder body = new StringBuilder();
        body.append("Han pasado ").append(NO_IMAGE_TIMEOUT_MS / 1000)
                .append(" segundos sin conseguir reproducir ningún trozo del canal");
        body.append(piecesBuilt > 0 ? " (se pidieron " + piecesBuilt + " trozos)." : " (no se llegó a pedir ningún trozo).");
        body.append("\nResultados del servidor por etapa: ").append(httpHistory()).append('.');
        if (lastCheckNote.length() > 0) {
            body.append("\nÚltimo trozo comprobado: ").append(lastCheckNote).append('.');
        }
        if (mpd != null) {
            body.append("\nManifiesto: ").append(mpd.dynamic ? "en directo" : "bajo demanda")
                    .append(", trozos de ").append(selectedVideo == null ? 0 : selectedVideo.segmentMs() / 1000f)
                    .append(" s, ").append(videoReps.size()).append(" calidades.");
            if (selectedVideo != null && selectedVideo.timeline.isEmpty() && selectedVideo.segDuration <= 0
                    && selectedVideo.media.length() > 0) {
                body.append("\nEl manifiesto no indica la duración de los trozos ni su lista de tiempos:"
                        + " el proveedor publica un índice que LENY TV no puede seguir con este enlace.");
            }
        }
        body.append("\n\nCabeceras enviadas: ").append(Net.describeHeaders(headers))
                .append(".\nSi el canal exige otras, escríbelas en AJUSTES DEL REPRODUCTOR.");
        throw new FatalError("El canal DASH no da imagen", body.toString());
    }

    /**
     * Vuelve a leer el manifiesto. En los canales en directo el índice cambia cada
     * pocos segundos: releyéndolo, el motor se recoloca en el trozo más reciente en
     * vez de pedir trozos que ya no existen.
     */
    private void reloadManifest() throws Exception {
        Diag.add("DASH · el manifiesto se vuelve a leer para recolocarse en el directo");
        String xml;
        try {
            xml = new String(fetchBytes(sourceUrl, headers, 4 * 1024 * 1024), "UTF-8");
        } catch (IOException netError) {
            throw new FatalError("El canal DASH en directo dejó de responder",
                    "Los trozos pedidos ya no existen en el servidor y el manifiesto tampoco se pudo"
                            + " volver a leer.\n\n" + MediaErrors.describeNetwork(netError, headers));
        }
        Mpd refreshed = Mpd.parse(xml, sourceUrl);
        mpd = refreshed;
        adoptReps(refreshed);
        if (videoReps.isEmpty()) {
            throw new FatalError("El manifiesto DASH se quedó sin pistas",
                    "El canal responde, pero su manifiesto ya no declara ninguna pista de vídeo.");
        }
        if (qualityIndex < 0 || qualityIndex >= videoReps.size()) qualityIndex = videoReps.size() - 1;
        selectedVideo = videoReps.get(qualityIndex);
        selectedAudio = chooseAudio(audioReps);
        audioTimeline = selectedAudio == null ? null : Mpd.expandTimeline(selectedAudio);
        plan.clear();
        lastNumber = -1;
        videoInit = null;
        audioInit = null;
        videoInitFailed = false;
        audioInitFailed = false;
        drainQueue();
        Diag.add("DASH · manifiesto releído: " + refreshed.summary());
    }

    /**
     * Si los trozos tardan más en descargarse que su propia duración, la imagen se
     * cortará: se baja automáticamente a una calidad inferior en lugar de esperar.
     */
    private void watchSpeed(long downloadMs) {
        if (selectedVideo == null) return;
        if (manualQuality) {
            // La resolución la eligió la persona: no se cambia sola.
            Diag.add("DASH · la descarga va lenta (" + downloadMs + " ms) pero la resolución la eligió"
                    + " la persona: no se cambia automáticamente");
            return;
        }
        long segMs = selectedVideo.segmentMs();
        if (segMs <= 0 || downloadMs < 1200) return;
        // Se descuenta el tiempo de unión de audio y vídeo, que no depende de la red.
        long networkMs = downloadMs - Math.min(segMs, downloadMs / 2);
        if (networkMs > segMs) slowStreak++;
        else slowStreak = 0;
        if (slowStreak < 3) return;
        slowStreak = 0;
        if (qualityIndex <= 0) {
            Diag.add("DASH · la descarga va lenta (" + downloadMs + " ms por trozo de " + segMs
                    + " ms) y ya se está usando la calidad más baja del manifiesto");
            return;
        }
        requestedQuality = qualityIndex - 1;
        Diag.add("DASH · la descarga va lenta (" + downloadMs + " ms para trozos de " + segMs
                + " ms): se baja a la calidad " + videoReps.get(requestedQuality).label());
        restartRequested = true;
    }

    /** Motivo real de un fallo de red, con las cabeceras que se enviaron. */
    private String networkReason(Throwable error) {
        String reason = MediaErrors.describeNetwork(error, headers);
        if (lastHttpNote.length() > 0) reason = reason + "\nÚltimo resultado: " + lastHttpNote + ".";
        return reason;
    }

    /** true si hay trozos nuevos preparados en el plan. */
    private boolean refill() {
        Mpd.Rep video = selectedVideo;
        if (video == null) return false;
        long segMs = video.segmentMs();

        if (video.timeline.size() > 0) {
            ArrayList<long[]> all = Mpd.expandTimeline(video);
            if (all.isEmpty()) return false;
            long firstNumber = video.startNumber;
            int from = 0;
            if (lastNumber < 0) {
                from = mpd.dynamic && all.size() > LIVE_BEHIND_SEGMENTS
                        ? all.size() - LIVE_BEHIND_SEGMENTS : 0;
            }
            for (int i = from; i < all.size(); i++) {
                long number = firstNumber + i;
                if (lastNumber < 0 || number > lastNumber) plan.add(new long[]{number, all.get(i)[0]});
            }
            return !plan.isEmpty();
        }

        if (!video.listUrls.isEmpty()) {
            long firstNumber = video.startNumber;
            for (int i = 0; i < video.listUrls.size(); i++) {
                long number = firstNumber + i;
                if (lastNumber < 0 || number > lastNumber) plan.add(new long[]{number, -1});
            }
            return !plan.isEmpty();
        }

        if (segMs <= 0) return false;

        if (mpd.dynamic) {
            long current = liveNumber(video, segMs);
            long from = lastNumber < 0 ? current - (LIVE_BEHIND_SEGMENTS - 1) : lastNumber + 1;
            if (current - from > 8) from = current - 2;
            if (from < video.startNumber) from = video.startNumber;
            for (long number = from; number <= current && plan.size() < 8; number++) {
                // Algunos canales piden el trozo con $Time$: pasar siempre 0 devolvía
                // la misma dirección en todos los trozos y el canal no arrancaba.
                long time = (number - video.startNumber) * video.segDuration;
                plan.add(new long[]{number, time});
            }
            return !plan.isEmpty();
        }

        long totalMs = mpd.mediaDurationMs;
        long count = totalMs > 0 ? Math.max(1, (totalMs + segMs - 1) / segMs) : 100000;
        long from = lastNumber < 0 ? video.startNumber : lastNumber + 1;
        long to = video.startNumber + count - 1;
        if (from > to) return false;
        long limit = Math.min(to, from + 5);
        for (long number = from; number <= limit; number++) {
            long time = (number - video.startNumber) * video.segDuration;
            plan.add(new long[]{number, time});
        }
        return !plan.isEmpty();
    }

    /** Número de trozo que corresponde al momento actual en un canal en directo. */
    private long liveNumber(Mpd.Rep rep, long segMs) {
        long start = rep.startNumber;
        long elapsed = 0;
        if (mpd.availabilityStartMs > 0) {
            elapsed = System.currentTimeMillis() - mpd.availabilityStartMs;
        }
        if (elapsed < 0) elapsed = 0;
        long number = start + (long) Math.floor(elapsed / (double) segMs) - LIVE_BEHIND_SEGMENTS + liveSkip;
        if (mpd.timeShiftMs > 0) {
            long oldest = start + (long) Math.floor((elapsed - mpd.timeShiftMs) / (double) segMs) + 1;
            if (number < oldest) number = oldest;
        }
        return Math.max(start, number);
    }

    /**
     * Dirección de un trozo concreto de una pista. Se apoya en Mpd.segmentUrl
     * para que la prueba de enlace compruebe exactamente la misma dirección que
     * pide el reproductor.
     */
    private String urlFor(Mpd.Rep rep, long number, long time) {
        return Mpd.segmentUrl(rep, number, time);
    }

    private long startTimeMs(Mpd.Rep rep, long number, long time) {
        if (time >= 0) {
            long relative = time - rep.presentationTimeOffset;
            // En directo el desplazamiento suele ser mayor que el tiempo del trozo:
            // restarlo daría un tiempo negativo (reloj imposible).
            if (relative < 0) relative = time;
            return rep.toMs(relative);
        }
        if (rep.segDuration > 0) return (number - rep.startNumber) * rep.toMs(rep.segDuration);
        return -1;
    }

    /** Descarga un trozo de vídeo (y su audio) y devuelve el archivo reproducible. */
    private Piece buildPiece(long number, long time) throws IOException {
        Mpd.Rep video = selectedVideo;
        String videoUrl = urlFor(video, number, time);
        if (videoUrl == null) throw new IOException("no hay dirección para el trozo " + number + " de vídeo");
        byte[] init = initBytes(video, true);
        File videoFile = new File(cacheDir, "v_" + number + ".mp4");
        stage = "trozo de vídeo " + number;
        download(videoUrl, init, videoFile);

        // Se mira por dentro el trozo recién descargado. Si llegó a medias o sin
        // imagen no se le entrega al reproductor (que solo diría un código sin
        // detalle): se vuelve a pedir una vez y, si sigue mal, se descarta.
        StreamCheck.Info info = StreamCheck.inspect(videoFile);
        if (!info.hasVideo()) {
            Diag.add("DASH · el trozo " + number + " no sirve (" + info.problemText()
                    + "); se vuelve a pedir · " + httpHistory());
            deleteQuietly(videoFile);
            download(videoUrl, init, videoFile);
            info = StreamCheck.inspect(videoFile);
            if (!info.hasVideo()) {
                String reason = info.problemText() + " · recibido " + Net.readable((int) info.size);
                deleteQuietly(videoFile);
                throw new BadSegment(number, reason, "resultados del servidor: " + httpHistory());
            }
        }
        lastCheckNote = info.describe();

        // ¿Este teléfono sabe decodificar el vídeo de este trozo? Es la comprobación
        // que distingue "el canal está bien pero el aparato no puede con el códec".
        if (!codecChecked) {
            codecChecked = true;
            StreamCheck.Track videoTrack = info.video();
            if (!StreamCheck.canDecode(videoTrack.format)) {
                int alternative = alternativeQuality(videoTrack);
                if (alternative >= 0) {
                    String failed = qualityIndex >= 0 && qualityIndex < videoReps.size()
                            ? videoReps.get(qualityIndex).label() : "la elegida";
                    Diag.add("DASH · este teléfono no puede decodificar la calidad elegida ("
                            + StreamCheck.decoderNote(videoTrack.format) + "): se cambia a la calidad "
                            + videoReps.get(alternative).label());
                    deleteQuietly(videoFile);
                    requestedQuality = alternative;
                    qualityIndex = alternative;
                    manualQuality = false;
                    qualityFallback = "no se pudo usar " + failed + " · se usa "
                            + videoReps.get(alternative).label();
                    qualityNotice = "Tu teléfono no puede decodificar " + failed
                            + "; LENY TV sigue con " + videoReps.get(alternative).label() + ".";
                    codecChecked = false;
                    throw new RestartSignal();
                }
                String list = qualitySummary(videoReps);
                deleteQuietly(videoFile);
                throw new FatalError("El teléfono no puede decodificar este canal",
                        "El enlace y el manifiesto están bien, pero este teléfono no puede reproducir"
                                + " el vídeo del canal.\n\nComprobación real del decodificador: "
                                + StreamCheck.decoderNote(videoTrack.format) + ".\n"
                                + "Calidades del manifiesto: " + list + ".\n\n"
                                + "El límite está en el aparato: no tiene decodificador para ese códec"
                                + " (ocurre con H.265/HEVC, AV1 y algunos sonidos AC-3).");
            }
            Diag.add("DASH · decodificador del teléfono para la calidad elegida: "
                    + StreamCheck.decoderNote(videoTrack.format));
        }

        Mpd.Rep audio = selectedAudio;
        File audioFile = null;
        if (audio != null && muxEnabled) {
            // El audio puede llevar su propia lista de tiempos: se usa el suyo, no el
            // del vídeo, para que ambos trozos correspondan al mismo instante.
            String audioUrl = urlFor(audio, number, audioTimeFor(number, time));
            if (audioUrl != null && !audioInitFailed) {
                byte[] aInit = initBytes(audio, false);
                File candidate = new File(cacheDir, "a_" + number + ".mp4");
                try {
                    stage = "trozo de audio " + number;
                    download(audioUrl, aInit, candidate);
                    audioFile = candidate;
                } catch (MissingSegment missing) {
                    Diag.add("DASH · el audio del trozo " + number + " no existe; se reproduce solo el vídeo");
                    audioFile = null;
                }
            }
        }
        long startMs = startTimeMs(video, number, time);
        if (audioFile == null) {
            if (!firstPieceStarted) {
                Diag.add("DASH · primer trozo (" + number + ") descargado sin pista de audio aparte: "
                        + Net.readable((int) videoFile.length()) + " · " + info.describe());
            }
            return new Piece(videoFile, null, startMs, number, time, false, info.describe());
        }
        File muxed = new File(cacheDir, "m_" + number + ".mp4");
        if (Mux.combine(videoFile, audioFile, muxed)) {
            deleteQuietly(audioFile);
            if (!firstPieceStarted) {
                Diag.add("DASH · primer trozo (" + number + ") descargado y unido con el audio: "
                        + Net.readable((int) muxed.length()));
            }
            // El trozo sin sonido se conserva: si el archivo unido no le sirve al
            // reproductor, se sigue con el vídeo en vez de parar con un error.
            return new Piece(muxed, videoFile, startMs, number, time, true, info.describe());
        }
        deleteQuietly(audioFile);
        Diag.add("DASH · el trozo " + number + " se reproduce sin audio (no se pudo unir la pista de audio)");
        return new Piece(videoFile, null, startMs, number, time, false, info.describe());
    }

    /**
     * Busca otra calidad del mismo manifiesto que este teléfono sí pueda
     * decodificar (por ejemplo, pasar de H.265 a H.264) y que no se haya probado
     * todavía. Devuelve -1 si no hay ninguna.
     */
    private int alternativeQuality(StreamCheck.Track videoTrack) {
        triedQualities.add(Integer.valueOf(qualityIndex));
        int best = -1;
        int bestScore = Integer.MAX_VALUE;
        for (int i = 0; i < videoReps.size(); i++) {
            if (triedQualities.contains(Integer.valueOf(i))) continue;
            Mpd.Rep rep = videoReps.get(i);
            if (!decodable(rep)) continue;
            if (rep.codecs.length() > 0 && videoTrack.codec.length() > 0
                    && rep.codecs.equalsIgnoreCase(videoTrack.codec)) {
                continue;
            }
            int bandwidth = rep.bandwidth > 0 ? rep.bandwidth : 1200000;
            int score = Math.abs(bandwidth - 1200000);
            if (score < bestScore) {
                bestScore = score;
                best = i;
            }
        }
        return best;
    }

    /** Tiempo del trozo en la pista de audio, usando la lista de tiempos de esa pista. */
    private long audioTimeFor(long number, long videoTime) {
        if (videoTime < 0 || selectedAudio == null) return videoTime;
        if (audioTimeline == null || audioTimeline.isEmpty()) return videoTime;
        int index = (int) (number - selectedAudio.startNumber);
        if (index >= 0 && index < audioTimeline.size()) return audioTimeline.get(index)[0];
        return videoTime;
    }

    private byte[] initBytes(Mpd.Rep rep, boolean video) throws IOException {
        if (rep.initUrl == null || rep.initUrl.length() == 0) return null;
        if (video) {
            if (videoInit != null) return videoInit;
        } else if (audioInit != null) {
            return audioInit;
        }
        try {
            stage = "datos iniciales";
            byte[] data = fetchBytes(rep.initUrl, headers, 4 * 1024 * 1024);
            if (video) videoInit = data;
            else audioInit = data;
            return data;
        } catch (IOException e) {
            if (video) videoInitFailed = true;
            else audioInitFailed = true;
            throw e;
        }
    }

    /**
     * Comprueba si los datos de una pista están CIFRADOS de verdad: descarga sus
     * datos iniciales (o su primer trozo) y busca las entradas de muestra cifradas
     * (encv/enca) que toda pista CENC está obligada a llevar. Devuelve null si no
     * se pudo comprobar. No descifra nada ni pide licencias.
     */
    private Boolean trackLooksEncrypted(Mpd.Rep rep) {
        if (rep == null) return null;
        try {
            byte[] data;
            if (rep.initUrl != null && rep.initUrl.length() > 0) {
                stage = "datos iniciales (comprobación de cifrado)";
                data = fetchBytes(rep.initUrl, headers, 8L * 1024L * 1024L);
            } else {
                long[] first = Mpd.firstSegment(mpd, rep);
                if (first == null) return null;
                String url = Mpd.segmentUrl(rep, first[0], first[1]);
                if (url == null || url.length() == 0) return null;
                stage = "primer trozo (comprobación de cifrado)";
                data = fetchBytes(url, headers, 4L * 1024L * 1024L);
            }
            return Boolean.valueOf(DrmInfo.hasEncryptedSampleEntry(data));
        } catch (Cancelled cancelled) {
            throw cancelled;
        } catch (Throwable t) {
            Diag.add("DASH · no se pudo comprobar si los datos del canal están cifrados: "
                    + Net.describe(t));
            return null;
        }
    }

    /**
     * Texto del aviso de un canal protegido. Separa los tres motivos posibles (motor,
     * teléfono y licencia) y dice qué cambio técnico haría falta, sin prometer que el
     * canal se pueda ver.
     */
    private String protectedBody(Mpd manifest, String system, boolean unknown) {
        String name = system == null || system.length() == 0 ? "sistema no identificado" : system;
        String host = manifest == null ? "" : manifest.licenseHost();
        String uuid = manifest == null ? "" : manifest.videoProtectionUuid();
        StringBuilder body = new StringBuilder();
        body.append("El canal va protegido con DRM (").append(name).append(")");
        if (unknown) {
            body.append(" y no se ha podido comprobar si sus datos llegan cifrados; se trata como")
                    .append(" protegido para no entregar al reproductor datos que no sabría descifrar.");
        } else {
            body.append(" y sus datos llegan cifrados (llevan muestras cifradas encv/enca).");
        }
        body.append("\n\n· LÍMITE DEL MOTOR: el motor DASH interno de LENY TV no descifra contenido")
                .append(" protegido (CENC). Lee el manifiesto, las calidades y los trozos, pero no puede")
                .append(" convertir en imagen unos datos cifrados.");
        body.append("\n· LÍMITE DEL TELÉFONO: ").append(DrmInfo.deviceSupportFor(uuid, name)).append('.');
        if (host.length() > 0) {
            body.append("\n· LICENCIA: el manifiesto indica un servidor de licencias (").append(host)
                    .append("). LENY TV no le pide ninguna licencia y no usa claves ajenas, así que aquí")
                    .append(" no hay ninguna licencia rechazada.");
        } else {
            body.append("\n· LICENCIA: el manifiesto no indica servidor de licencias, así que tampoco hay")
                    .append(" una licencia autorizada que se pueda usar.");
        }
        body.append("\n\n· QUÉ CAMBIO TÉCNICO HARÍA FALTA: un motor que descifre CENC con el sistema DRM")
                .append(" del teléfono (MediaDrm para obtener la licencia del proveedor y MediaCodec con")
                .append(" cifrado para decodificar), por ejemplo ExoPlayer/Media3 con su gestor de")
                .append(" licencias. Ese módulo no está disponible en esta versión de la app.");
        if (manifest != null && manifest.hasPssh) {
            body.append("\n\nEl manifiesto incluye datos de inicio de protección (pssh).");
        }
        Diag.add("DASH · canal protegido · sistema " + name
                + (host.length() == 0 ? "" : " · licencia en " + host)
                + (unknown ? " · no se pudo comprobar el cifrado de los datos" : " · datos cifrados")
                + " · el motor no descifra");
        EngineInfo.license("protegido con " + name
                + (host.length() == 0 ? "" : " · servidor de licencias: " + host)
                + " · el motor no descifra ni pide licencias");
        EngineInfo.drm(DrmInfo.summary());
        return body.toString();
    }

    /** Reproduce una pista DASH de un solo archivo (SegmentBase). */
    private void startSingleFile(final Mpd.Rep rep) {
        singleFileMode = true;
        Diag.add("DASH · la pista es un solo archivo: se reproduce directamente");
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (released) return;
                if (!stopped) listener.onBuffering(true);
                MediaPlayer player = new MediaPlayer();
                player.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build());
                if (surface != null) player.setSurface(surface);
                player.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                    @Override
                    public void onPrepared(MediaPlayer mp) {
                        firstPieceStarted = true;
                        activeStartMs = 0;
                        playClockMs = System.currentTimeMillis();
                        videoWidth = mp.getVideoWidth();
                        videoHeight = mp.getVideoHeight();
                        listener.onVideoSize(videoWidth, videoHeight);
                        listener.onBuffering(false);
                        mp.start();
                        if (paused) {
                            // El cambio de resolución no debe arrancar el canal si la
                            // persona lo había pausado.
                            try {
                                mp.pause();
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                });
                player.setOnVideoSizeChangedListener(new MediaPlayer.OnVideoSizeChangedListener() {
                    @Override
                    public void onVideoSizeChanged(MediaPlayer mp, int width, int height) {
                        videoWidth = width;
                        videoHeight = height;
                        listener.onVideoSize(width, height);
                    }
                });
                player.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                    @Override
                    public boolean onError(MediaPlayer mp, int what, int extra) {
                        Diag.add("DASH · error del archivo único · " + MediaErrors.kind(what, extra)
                                + " (what=" + what + ", extra=" + extra + ")");
                        fail("No se pudo reproducir el archivo DASH",
                                "El manifiesto apunta a un solo archivo y el reproductor no pudo con él.\n\n"
                                        + MediaErrors.explain(what, extra));
                        return true;
                    }
                });
                player.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                    @Override
                    public void onCompletion(MediaPlayer mp) {
                        if (!released) listener.onFinished();
                    }
                });
                slotA.player = player;
                active = slotA;
                try {
                    if (headers.isEmpty()) player.setDataSource(context, Uri.parse(rep.baseUrl));
                    else player.setDataSource(context, Uri.parse(rep.baseUrl), headers);
                    player.prepareAsync();
                } catch (Throwable t) {
                    fail("No se pudo abrir el archivo DASH", "El reproductor informó: " + Net.describe(t));
                }
            }
        });
    }

    // ------------------------------------------------- entrega al reproductor

    private void step() {
        if (released || paused) return;
        if (singleFileMode) return;
        if (active == null) {
            Slot idle = slotA.player == null ? slotA : (slotB.player == null ? slotB : null);
            if (idle == null) return;
            Piece piece = ready.poll();
            if (piece == null) {
                if (!firstPieceStarted) listener.onBuffering(true);
                return;
            }
            prepare(idle, piece);
            return;
        }
        if (pending == null) {
            Slot idle = active == slotA ? slotB : slotA;
            if (idle.player != null) return;
            Piece piece = ready.poll();
            if (piece == null) return;
            prepare(idle, piece);
        }
    }

    private void prepare(final Slot slot, final Piece piece) {
        releaseSlot(slot);
        final MediaPlayer player = new MediaPlayer();
        player.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                .build());
        if (surface != null) {
            try {
                player.setSurface(surface);
            } catch (Throwable ignored) {
            }
        }
        slot.player = player;
        slot.file = piece.file;
        slot.startMs = piece.startMs;
        slot.piece = piece;
        slot.prepared = false;
        slot.playing = false;
        slot.chained = false;
        player.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(MediaPlayer mp) {
                if (released) return;
                slot.prepared = true;
                if (slot.startMs >= 0) activeStartMs = slot.startMs;
                if (active == null) {
                    startPiece(slot);
                } else {
                    pending = slot;
                    try {
                        active.player.setNextMediaPlayer(mp);
                        slot.chained = true;
                    } catch (Throwable ignored) {
                        slot.chained = false;
                    }
                }
            }
        });
        player.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            @Override
            public void onCompletion(MediaPlayer mp) {
                onPieceFinished(slot);
            }
        });
        player.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(MediaPlayer mp, int what, int extra) {
                onPieceError(slot, what, extra);
                return true;
            }
        });
        player.setOnVideoSizeChangedListener(new MediaPlayer.OnVideoSizeChangedListener() {
            @Override
            public void onVideoSizeChanged(MediaPlayer mp, int width, int height) {
                videoWidth = width;
                videoHeight = height;
                listener.onVideoSize(width, height);
            }
        });
        try {
            player.setDataSource(slot.file.getAbsolutePath());
            player.prepareAsync();
        } catch (Throwable t) {
            Diag.add("DASH · no se pudo preparar el trozo: " + Net.describe(t));
            releaseSlot(slot);
            if (!firstPieceStarted) {
                fail("No se pudo preparar el trozo del canal",
                        "El reproductor de Android rechazó el archivo descargado (" + Net.describe(t) + ").\n\n"
                                + "Contenido del trozo: " + lastCheckNote + ".\n"
                                + "Calidad en uso: "
                                + (selectedVideo == null ? "(todavía sin datos)" : selectedVideo.label()) + ".");
            }
        }
    }

    private void startPiece(Slot slot) {
        try {
            slot.player.start();
            slot.playing = true;
            active = slot;
            firstPieceStarted = true;
            listener.onBuffering(false);
            if (videoWidth <= 0) {
                int width = slot.player.getVideoWidth();
                int height = slot.player.getVideoHeight();
                if (width > 0 && height > 0) {
                    videoWidth = width;
                    videoHeight = height;
                    listener.onVideoSize(width, height);
                }
            }
        } catch (Throwable t) {
            Diag.add("DASH · no se pudo arrancar el trozo: " + Net.describe(t));
            releaseSlot(slot);
            active = null;
        }
    }

    private void onPieceFinished(Slot slot) {
        if (slot == active) {
            Slot next = pending;
            pending = null;
            if (next != null && next.prepared) {
                if (!next.chained) {
                    try {
                        next.player.start();
                    } catch (Throwable ignored) {
                    }
                }
                next.playing = true;
                next.chained = false;
                if (next.startMs >= 0) activeStartMs = next.startMs;
                releaseSlot(slot);
                active = next;
                listener.onBuffering(false);
            } else {
                releaseSlot(slot);
                active = null;
                if (!released) listener.onBuffering(true);
            }
        } else if (slot == pending) {
            // El trozo preparado de más no llegó a usarse.
            pending = null;
            releaseSlot(slot);
        }
    }

    private void onPieceError(Slot slot, int what, int extra) {
        String detail = MediaErrors.explain(what, extra);
        Piece tried = slot.piece;
        Diag.add("DASH · fallo al reproducir el trozo" + (tried == null ? "" : " " + tried.number)
                + " · " + MediaErrors.kind(what, extra) + " (what=" + what + ", extra=" + extra + ") · "
                + detail + (tried == null || tried.check.length() == 0 ? "" : " · contenido: " + tried.check));

        // Si el archivo que falla era el que llevaba el audio unido al vídeo, se
        // sigue con el vídeo solo (sin sonido) en lugar de parar con un código sin
        // motivo: el canal continúa y el diagnóstico dice qué pasó.
        if (tried != null && tried.muxed && muxEnabled
                && tried.rawVideo != null && tried.rawVideo.exists()) {
            File raw = tried.rawVideo;
            muxEnabled = false;
            Diag.add("DASH · el archivo unido (audio + vídeo) no se pudo reproducir: se continúa con el"
                    + " vídeo sin sonido en lugar de detener el canal");
            slot.piece = null;
            releaseSlot(slot);
            active = null;
            prepare(slot, new Piece(raw, null, tried.startMs, tried.number, tried.time, false, tried.check));
            return;
        }

        boolean wasActive = slot == active;
        if (slot == pending) pending = null;
        releaseSlot(slot);
        if (wasActive) {
            active = null;
            consecutiveErrors++;
            String estado = "Calidad en uso: "
                    + (selectedVideo == null ? "(todavía sin datos)" : selectedVideo.label()) + ".\n"
                    + "Último trozo comprobado: "
                    + (lastCheckNote.length() == 0 ? "(ninguno)" : lastCheckNote) + ".\n"
                    + "Resultados del servidor: " + httpHistory() + ".";
            if (!firstPieceStarted) {
                fail("El canal DASH no da imagen", "La imagen no llegó a empezar.\n\n" + detail
                        + "\n\n" + estado);
            } else if (consecutiveErrors >= 4) {
                fail("El canal DASH se ha detenido",
                        "Se han perdido varios trozos seguidos.\n\n" + detail + "\n\n" + estado);
            } else {
                listener.onBuffering(true);
            }
        }
    }

    private void releaseSlot(Slot slot) {
        if (slot == null) return;
        MediaPlayer player = slot.player;
        slot.player = null;
        slot.prepared = false;
        slot.playing = false;
        slot.chained = false;
        if (player != null) {
            try {
                player.setOnPreparedListener(null);
                player.setOnCompletionListener(null);
                player.setOnErrorListener(null);
                player.setOnVideoSizeChangedListener(null);
                player.setOnInfoListener(null);
            } catch (Throwable ignored) {
            }
            try {
                player.setSurface(null);
            } catch (Throwable ignored) {
            }
            try {
                player.reset();
            } catch (Throwable ignored) {
            }
            try {
                player.release();
            } catch (Throwable ignored) {
            }
        }
        File file = slot.file;
        Piece piece = slot.piece;
        slot.file = null;
        slot.piece = null;
        slot.startMs = -1;
        deleteQuietly(file);
        if (piece != null) {
            // El trozo de vídeo sin sonido y el archivo unido se borran aquí, salvo
            // el que se acaba de eliminar por ser el que se estaba reproduciendo.
            if (piece.rawVideo != null && piece.rawVideo != file) deleteQuietly(piece.rawVideo);
            if (piece.file != null && piece.file != file) deleteQuietly(piece.file);
        }
    }

    private void clearSlots() {
        releaseSlot(slotA);
        releaseSlot(slotB);
        active = null;
        pending = null;
    }

    private void applySurface(Slot slot) {
        if (slot == null || slot.player == null || surface == null) return;
        try {
            slot.player.setSurface(surface);
        } catch (Throwable ignored) {
        }
    }

    // -------------------------------------------------------------- descargas

    private byte[] fetchBytes(String url, Map<String, String> httpHeaders, long limit) throws IOException {
        InputStream in = null;
        try {
            in = openStream(url, httpHeaders);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[16384];
            int read;
            while ((read = in.read(buffer)) > 0) {
                if (released) throw new Cancelled();
                out.write(buffer, 0, read);
                if (out.size() > limit) throw new IOException("el archivo recibido es demasiado grande");
            }
            return out.toByteArray();
        } finally {
            closeQuietly(in);
        }
    }

    private void download(String url, byte[] init, File destination) throws IOException {
        FileOutputStream out = null;
        InputStream in = null;
        try {
            if (destination.exists()) destination.delete();
            out = new FileOutputStream(destination, false);
            if (init != null && init.length > 0) out.write(init);
            in = openStream(url, headers);
            byte[] buffer = new byte[32768];
            int read;
            long total = 0;
            while ((read = in.read(buffer)) > 0) {
                if (released) throw new Cancelled();
                out.write(buffer, 0, read);
                total += read;
                if (total > MAX_SEGMENT_BYTES) throw new IOException("el trozo es demasiado grande");
            }
        } catch (IOException e) {
            closeQuietly(out);
            out = null;
            if (destination.exists()) destination.delete();
            throw e;
        } finally {
            closeQuietly(out);
            closeQuietly(in);
        }
        if (destination.length() == 0) {
            destination.delete();
            throw new IOException("el trozo llegó vacío");
        }
    }

    /** Abre un enlace siguiendo redirecciones y, si falla, probando el puente local. */
    private InputStream openStream(String url, Map<String, String> httpHeaders) throws IOException {
        IOException first = null;
        try {
            return openDirect(url, httpHeaders);
        } catch (MissingSegment missing) {
            // Un trozo que ya no existe no se arregla cambiando de cabeceras: se
            // informa de inmediato para que el motor se recol oque en el directo.
            throw missing;
        } catch (MediaErrors.Http http) {
            if (http.code >= 400 && http.code < 500 && http.code != 401 && http.code != 403) throw http;
            first = http;
        } catch (IOException e) {
            first = e;
        }
        if (!LocalProxy.isHttp(url)) throw first;
        try {
            String bridged = LocalProxy.get().proxyUrl(url, httpHeaders);
            if (bridged == null) throw first;
            Diag.add("DASH · la petición directa falló (" + Net.describe(first)
                    + "); se reintenta con el puente local y cabeceras");
            return openDirect(bridged, httpHeaders);
        } catch (IOException second) {
            throw second;
        }
    }

    private InputStream openDirect(String url, Map<String, String> httpHeaders) throws IOException {
        String current = url;
        for (int hop = 0; hop < 5; hop++) {
            HttpURLConnection conn = open(current, httpHeaders);
            int code = conn.getResponseCode();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String location = conn.getHeaderField("Location");
                conn.disconnect();
                if (location == null || location.length() == 0) {
                    throw new IOException("el servidor redirigió sin indicar a dónde (código " + code + ")");
                }
                current = Mpd.resolve(current, location);
                continue;
            }
            if (code == 404 || code == 410) {
                conn.disconnect();
                lastHttpNote = recordHttp("código HTTP " + code + " · " + Diag.shortUrl(current));
                throw new MissingSegment(MediaErrors.httpReason(code));
            }
            if (code < 200 || code >= 400) {
                conn.disconnect();
                lastHttpNote = recordHttp("código HTTP " + code + " · " + Diag.shortUrl(current));
                throw new MediaErrors.Http(code, "el servidor respondió: " + MediaErrors.httpReason(code));
            }
            lastHttpNote = recordHttp("código HTTP " + code + " · "
                    + MediaErrors.normalizeType(conn.getContentType()));
            return conn.getInputStream();
        }
        throw new IOException("el enlace da demasiadas vueltas (demasiadas redirecciones)");
    }

    /**
     * Abre la conexión aplicando TODAS las cabeceras configuradas (User-Agent,
     * Referer y cualquier otra que exija el proveedor), tanto al manifiesto como a
     * los datos iniciales y a cada trozo de vídeo o de audio. Si el servidor exige
     * una cabecera concreta, es aquí donde se envía.
     */
    private HttpURLConnection open(String url, Map<String, String> httpHeaders) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setInstanceFollowRedirects(false);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(20000);
        conn.setRequestProperty("Accept", "*/*");
        conn.setRequestProperty("Accept-Encoding", "identity");
        String userAgent = httpHeaders == null ? null : httpHeaders.get("User-Agent");
        conn.setRequestProperty("User-Agent",
                userAgent == null || userAgent.length() == 0 ? Net.PLAYER_UA : userAgent);
        if (httpHeaders != null) {
            for (Map.Entry<String, String> entry : httpHeaders.entrySet()) {
                if (entry == null) continue;
                String name = entry.getKey() == null ? "" : entry.getKey().trim();
                String value = entry.getValue();
                if (name.length() == 0 || value == null || value.length() == 0) continue;
                if ("user-agent".equalsIgnoreCase(name)) continue;
                try {
                    conn.setRequestProperty(name, value);
                } catch (Throwable ignored) {
                    // Una cabecera con nombre inválido no debe impedir la descarga.
                }
            }
        }
        return conn;
    }

    // ---------------------------------------------------------------- ayudas

    private int chooseQuality(ArrayList<Mpd.Rep> reps) {
        // Se busca una calidad intermedia (en torno a 1,2 Mbps) que el teléfono
        // pueda descargar sin cortes. Además se prefiere una calidad que ESTE
        // teléfono pueda decodificar: así un canal que ofrece H.265 y H.264 arranca
        // en H.264 en un aparato sin decodificador de H.265, en vez de dar error.
        if (reps.isEmpty()) return 0;
        Diag.add("DASH · calidades del canal (y si este teléfono puede decodificarlas): "
                + qualitySummary(reps));
        int best = 0;
        int bestScore = Integer.MAX_VALUE;
        for (int i = 0; i < reps.size(); i++) {
            Mpd.Rep rep = reps.get(i);
            if (rep.height > 1080) continue;
            int bandwidth = rep.bandwidth > 0 ? rep.bandwidth : 1200000;
            int score = Math.abs(bandwidth - 1200000);
            if (!decodable(rep)) score += 1000000000;
            if (score < bestScore) {
                bestScore = score;
                best = i;
            }
        }
        if (!decodable(reps.get(best))) {
            Diag.add("DASH · aviso: el teléfono no declara decodificador para ninguna de las calidades"
                    + " del manifiesto; se probará la de " + reps.get(best).label());
        }
        return best;
    }

    /**
     * Comprueba, con lo que dice el manifiesto (tipo y códec), si este teléfono
     * tiene decodificador para esa calidad. La comprobación definitiva se hace
     * después con el formato real del trozo descargado.
     */
    private boolean decodable(Mpd.Rep rep) {
        if (rep == null) return false;
        return StreamCheck.canDecode(StreamCheck.formatFor(rep.mimeType, rep.codecs));
    }

    /** Lista corta de las calidades con el resultado de la comprobación del decodificador. */
    private String qualitySummary(ArrayList<Mpd.Rep> reps) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < reps.size() && i < 5; i++) {
            Mpd.Rep rep = reps.get(i);
            if (i > 0) sb.append(" · ");
            sb.append(rep.label()).append(decodable(rep) ? " [se puede decodificar]" : " [sin decodificador]");
        }
        return sb.toString();
    }

    private Mpd.Rep chooseAudio(ArrayList<Mpd.Rep> reps) {
        Mpd.Rep best = null;
        int bestScore = Integer.MAX_VALUE;
        // Si el manifiesto protege unas pistas de audio y otras no, se usa la libre:
        // el canal se puede oír aunque el audio protegido no se descifre.
        boolean anyClear = false;
        for (int i = 0; i < reps.size(); i++) {
            if (!reps.get(i).protectedContent) anyClear = true;
        }
        for (int i = 0; i < reps.size(); i++) {
            Mpd.Rep rep = reps.get(i);
            if (anyClear && rep.protectedContent) continue;
            if (rep.singleFile && reps.size() > 1 && best != null) continue;
            int bandwidth = rep.bandwidth > 0 ? rep.bandwidth : 128000;
            int score = Math.abs(bandwidth - 128000);
            if (score < bestScore) {
                bestScore = score;
                best = rep;
            }
        }
        return best;
    }

    private void checkRestart() {
        if (restartRequested) {
            restartRequested = false;
            clearPending();
            Diag.add("DASH · se reinicia el motor con la nueva calidad");
            throw new RestartSignal();
        }
    }

    /**
     * En los manifiestos de un solo archivo no hay trozos que preparar: se espera
     * aquí a que la persona pida otra resolución para reiniciar con ella.
     */
    private void awaitQualityChange() {
        while (!released) {
            if (restartRequested) {
                restartRequested = false;
                Diag.add("DASH · se reinicia el archivo único con la resolución elegida");
                clearPending();
                throw new RestartSignal();
            }
            sleepQuiet(200);
        }
    }

    private void clearPending() {
        drainQueue();
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (!released) {
                    clearSlots();
                    listener.onBuffering(true);
                }
            }
        });
        plan.clear();
        lastNumber = -1;
        firstPieceStarted = false;
    }

    /** Anota el resultado HTTP de la etapa que se está pidiendo (se guardan los últimos). */
    private synchronized String recordHttp(String text) {
        String entry = (stage == null || stage.length() == 0 ? "" : stage + ": ") + text;
        httpNotes.add(entry);
        while (httpNotes.size() > 6) httpNotes.remove(0);
        return entry;
    }

    /** Resumen de los últimos resultados del servidor, por etapa. */
    private synchronized String httpHistory() {
        if (httpNotes.isEmpty()) return "(el servidor no llegó a responder)";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < httpNotes.size(); i++) {
            if (i > 0) sb.append(" · ");
            sb.append(httpNotes.get(i));
        }
        return sb.toString();
    }

    private void drainQueue() {
        Piece piece;
        while ((piece = ready.poll()) != null) {
            deleteQuietly(piece.file);
            if (piece.rawVideo != null && piece.rawVideo != piece.file) deleteQuietly(piece.rawVideo);
        }
    }

    private static void deleteQuietly(File file) {
        if (file == null) return;
        try {
            file.delete();
        } catch (Throwable ignored) {
        }
    }

    private void releaseCache() {
        try {
            cacheDir = new File(context.getCacheDir(), "dash");
            if (!cacheDir.exists() && !cacheDir.mkdirs()) {
                Diag.add("DASH · no se pudo preparar la carpeta temporal");
            }
            File[] files = cacheDir.listFiles();
            if (files != null) {
                for (int i = 0; i < files.length; i++) files[i].delete();
            }
        } catch (Throwable t) {
            Diag.add("DASH · aviso con la carpeta temporal: " + Net.describe(t));
        }
    }

    private void fail(final String title, final String body) {
        if (stopped) return;
        stopped = true;
        Diag.add("DASH · fallo definitivo: " + title + " · " + body);
        Diag.save(context);
        ui.post(new Runnable() {
            @Override
            public void run() {
                releaseSlotsLater();
                if (!released) listener.onError(title, body, true);
            }
        });
    }

    private void releaseSlotsLater() {
        clearSlots();
    }

    private String explain(Throwable t) {
        if (t instanceof FatalError) return t.getMessage();
        if (t instanceof Cancelled) return "la reproducción se canceló";
        String reason = t instanceof MediaErrors.Http
                ? MediaErrors.describeNetwork(t, headers)
                : Net.describe(t) + ".\n\nCabeceras enviadas: " + Net.describeHeaders(headers);
        return "No se pudo seguir con el canal DASH.\nMotivo: " + reason
                + "\n\nComprueba la conexión y, si el proveedor lo pide, escribe el User-Agent y el"
                + " Referer correctos en AJUSTES DEL REPRODUCTOR.";
    }

    private void sleepQuiet(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            if (released) throw new Cancelled();
        }
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (Throwable ignored) {
        }
    }

    /** Error que ya viene explicado al usuario. */
    private static final class FatalError extends RuntimeException {
        FatalError(String title, String body) {
            super(body);
            this.title = title;
        }

        final String title;
    }

    /** El trozo no existe en el servidor (puede ser el final de la emisión). */
    private static final class MissingSegment extends IOException {
        MissingSegment(String message) {
            super(message);
        }
    }

    /**
     * El trozo llegó vacío, a medias o sin ninguna imagen utilizable. No se le
     * entrega al reproductor: se descarta indicando el motivo real.
     */
    private static final class BadSegment extends IOException {
        BadSegment(long number, String reason, String extra) {
            super("trozo " + number + ": " + reason + (extra == null || extra.length() == 0
                    ? "" : " (" + extra + ")"));
        }
    }
}
