package com.pocketbuilder.app_wslenytvplayer1792356127271;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.Tracks;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.dash.DashMediaSource;
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager;
import androidx.media3.exoplayer.drm.DrmSessionManager;
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider;
import androidx.media3.exoplayer.drm.FrameworkMediaDrm;
import androidx.media3.exoplayer.drm.HttpMediaDrmCallback;
import androidx.media3.exoplayer.drm.LocalMediaDrmCallback;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Motor de reproducción de LENY TV basado en AndroidX Media3 (ExoPlayer).
 *
 * Sustituye al antiguo MediaPlayer + DashPlayer personalizado.
 * La interfaz visual de PlayerActivity permanece intacta: este motor solo
 * se encarga de abrir el stream, buffering, errores, calidades y superficie.
 *
 * Soporta HLS, DASH, progressive (mp4/ts/mkv), cabeceras HTTP por canal
 * y DRM ClearKey (claves inline KID:KEY o URL de licencia).
 */
public final class Media3Engine {

    public interface Listener {
        void onStatus(String message);

        void onBuffering(boolean active);

        void onVideoSize(int width, int height);

        void onError(String title, String body, boolean retryable);

        void onPrepared();

        void onFinished();

        void onPlayingChanged(boolean playing);
    }

    /** Una calidad de vídeo detectada en el stream (DASH/HLS). */
    static final class QualityOption {
        final int groupIndex;
        final int trackIndexInGroup;
        final int height;
        final int width;
        final int bitrate;
        final String label;

        QualityOption(int groupIndex, int trackIndexInGroup, int height, int width, int bitrate, String label) {
            this.groupIndex = groupIndex;
            this.trackIndexInGroup = trackIndexInGroup;
            this.height = height;
            this.width = width;
            this.bitrate = bitrate;
            this.label = label;
        }
    }

    private final Context appContext;
    private final Handler mainHandler;
    private final Listener listener;

    private ExoPlayer player;
    private DefaultTrackSelector trackSelector;
    private Surface surface;
    private boolean released;
    private int videoWidth;
    private int videoHeight;
    private final List<QualityOption> qualities = new ArrayList<QualityOption>();
    private int selectedQualityIndex = 0; // 0 = Automática

    public Media3Engine(Context context, Handler mainHandler, Listener listener) {
        this.appContext = context.getApplicationContext();
        this.mainHandler = mainHandler != null ? mainHandler : new Handler(Looper.getMainLooper());
        this.listener = listener;
    }

    public void setSurface(@Nullable Surface surface) {
        this.surface = surface;
        if (player != null) {
            player.setVideoSurface(surface);
        }
    }

    /** Arranque sin DRM (compatibilidad con llamadas antiguas). */
    public void start(String url, Map<String, String> headers, String contentTypeHint) {
        start(url, headers, contentTypeHint, null, null, null);
    }

    /**
     * Arranca la reproducción.
     *
     * @param licenseType tipo declarado en la lista (p. ej. "clearkey"); puede ser null
     * @param licenseKey  clave inline (KID:KEY) o URL de licencia; nunca se registra completa
     * @param licenseHeadersSpec cabeceras de licencia en formato Kodi (nombre=valor&...)
     */
    public void start(String url, Map<String, String> headers, String contentTypeHint,
                      @Nullable String licenseType, @Nullable String licenseKey,
                      @Nullable String licenseHeadersSpec) {
        if (released) return;
        releaseInternal(false);
        qualities.clear();
        selectedQualityIndex = 0;
        videoWidth = 0;
        videoHeight = 0;

        status("Media3 · preparando " + shortUrl(url));
        if (licenseType != null && licenseType.trim().length() > 0) {
            status("Media3 · DRM declarado: " + licenseType.trim()
                    + (licenseKey != null && licenseKey.length() > 0 ? " · con license_key" : ""));
        }

        try {
            trackSelector = new DefaultTrackSelector(appContext);
            trackSelector.setParameters(
                    trackSelector.buildUponParameters()
                            .setForceHighestSupportedBitrate(false)
                            // Preferir AAC/MP3/Opus (decodificables en casi todos los móviles)
                            // frente a AC-3/E-AC-3 cuando el manifiesto ofrece varias pistas.
                            .setPreferredAudioMimeTypes(
                                    MimeTypes.AUDIO_AAC,
                                    MimeTypes.AUDIO_MPEG,
                                    MimeTypes.AUDIO_OPUS,
                                    MimeTypes.AUDIO_VORBIS)
                            .setExceedAudioConstraintsIfNecessary(true)
                            .setAllowAudioMixedMimeTypeAdaptiveness(true)
                            .setAllowAudioNonSeamlessAdaptiveness(true)
                            .build());

            DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                    .setBufferDurationsMs(
                            15_000,
                            50_000,
                            1_500,
                            5_000)
                    .build();

            HttpDataSource.Factory httpFactory = buildHttpFactory(headers);

            DrmSessionManager drmSessionManager = buildDrmSessionManager(
                    licenseType, licenseKey, licenseHeadersSpec, httpFactory);

            DefaultMediaSourceFactory mediaSourceFactory =
                    new DefaultMediaSourceFactory(appContext)
                            .setDataSourceFactory(httpFactory);
            if (drmSessionManager != null) {
                final DrmSessionManager locked = drmSessionManager;
                mediaSourceFactory.setDrmSessionManagerProvider(new DrmSessionManagerProvider() {
                    @Override
                    public DrmSessionManager get(MediaItem mediaItem) {
                        return locked;
                    }
                });
            }

            player = new ExoPlayer.Builder(appContext)
                    .setTrackSelector(trackSelector)
                    .setLoadControl(loadControl)
                    .setMediaSourceFactory(mediaSourceFactory)
                    .build();

            player.setAudioAttributes(
                    new AudioAttributes.Builder()
                            .setUsage(C.USAGE_MEDIA)
                            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                            .build(),
                    true);

            player.addListener(playerListener);
            if (surface != null) {
                player.setVideoSurface(surface);
            }

            MediaSource mediaSource = buildMediaSource(url, httpFactory, contentTypeHint,
                    licenseType, licenseKey, drmSessionManager);
            player.setMediaSource(mediaSource);
            player.prepare();
            player.setPlayWhenReady(true);
            status("Media3 · prepare() enviado"
                    + (drmSessionManager != null ? " · DRM activo" : " · sin DRM"));
        } catch (Throwable t) {
            status("Media3 · error al crear: " + t.getMessage());
            notifyError("No se pudo iniciar el motor",
                    safeMsg(t), true);
        }
    }

    private HttpDataSource.Factory buildHttpFactory(Map<String, String> headers) {
        DefaultHttpDataSource.Factory httpFactory = new DefaultHttpDataSource.Factory()
                .setConnectTimeoutMs(15_000)
                .setReadTimeoutMs(20_000)
                .setAllowCrossProtocolRedirects(true)
                .setUserAgent(userAgentFrom(headers));

        if (headers != null && !headers.isEmpty()) {
            HashMap<String, String> fixed = new HashMap<String, String>();
            for (Map.Entry<String, String> e : headers.entrySet()) {
                if (e.getKey() == null) continue;
                if ("User-Agent".equalsIgnoreCase(e.getKey())) continue;
                fixed.put(e.getKey(), e.getValue() == null ? "" : e.getValue());
            }
            if (!fixed.isEmpty()) {
                httpFactory.setDefaultRequestProperties(fixed);
            }
        }
        return httpFactory;
    }

    /**
     * Construye el gestor DRM solo para ClearKey cuando la lista lo declara.
     * No fuerza DRM en canales sin licencia.
     */
    @Nullable
    private DrmSessionManager buildDrmSessionManager(
            @Nullable String licenseType,
            @Nullable String licenseKey,
            @Nullable String licenseHeadersSpec,
            HttpDataSource.Factory httpFactory) {
        if (licenseType == null || licenseType.trim().length() == 0) {
            // Si no hay tipo pero la clave parece ClearKey inline, también se acepta
            if (licenseKey == null || !looksLikeInlineClearKey(licenseKey)) {
                return null;
            }
        } else {
            String t = licenseType.trim().toLowerCase(Locale.US);
            if (!t.contains("clearkey") && !t.contains("clear-key") && !t.equals("org.w3.clearkey")) {
                status("Media3 · DRM no soportado en esta version: " + licenseType.trim()
                        + " (solo ClearKey)");
                // No devolver manager: el stream fallara con error de cifrado si requiere otro DRM
                return null;
            }
        }
        if (licenseKey == null || licenseKey.trim().length() == 0) {
            status("Media3 · ClearKey declarado sin license_key");
            return null;
        }
        String key = licenseKey.trim();

        // URL de servidor de licencias
        if (isLicenseUrl(key)) {
            String licenseUrl = key;
            String headerPart = null;
            int pipe = key.indexOf('|');
            if (pipe > 0) {
                licenseUrl = key.substring(0, pipe).trim();
                headerPart = key.substring(pipe + 1).trim();
            }
            try {
                HttpMediaDrmCallback callback = new HttpMediaDrmCallback(licenseUrl, httpFactory);
                Map<String, String> licHeaders = parseHeaderSpec(headerPart);
                Map<String, String> fromSpec = parseHeaderSpec(licenseHeadersSpec);
                if (fromSpec != null) {
                    for (Map.Entry<String, String> e : fromSpec.entrySet()) {
                        licHeaders.put(e.getKey(), e.getValue());
                    }
                }
                for (Map.Entry<String, String> e : licHeaders.entrySet()) {
                    callback.setKeyRequestProperty(e.getKey(), e.getValue());
                }
                status("Media3 · ClearKey por URL de licencia");
                return new DefaultDrmSessionManager.Builder()
                        .setUuidAndExoMediaDrmProvider(C.CLEARKEY_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
                        .setMultiSession(true)
                        .build(callback);
            } catch (Throwable t) {
                status("Media3 · no se pudo crear callback de licencia: " + safeMsg(t));
                return null;
            }
        }

        // Clave inline KID:KEY (hex o base64)
        String json = buildClearKeyJson(key);
        if (json == null) {
            status("Media3 · license_key no reconocida como ClearKey inline ni como URL");
            return null;
        }
        try {
            LocalMediaDrmCallback callback =
                    new LocalMediaDrmCallback(json.getBytes(StandardCharsets.UTF_8));
            status("Media3 · ClearKey inline configurado");
            return new DefaultDrmSessionManager.Builder()
                    .setUuidAndExoMediaDrmProvider(C.CLEARKEY_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
                    .setMultiSession(true)
                    .build(callback);
        } catch (Throwable t) {
            status("Media3 · fallo al crear LocalMediaDrmCallback: " + safeMsg(t));
            return null;
        }
    }

    private MediaSource buildMediaSource(
            String url,
            HttpDataSource.Factory httpFactory,
            String contentTypeHint,
            @Nullable String licenseType,
            @Nullable String licenseKey,
            @Nullable DrmSessionManager drmSessionManager) {

        Uri uri = Uri.parse(url);
        MediaItem.Builder itemBuilder = new MediaItem.Builder().setUri(uri);

        // Anunciar ClearKey en el MediaItem cuando hay DRM (ayuda a la seleccion de pistas)
        if (drmSessionManager != null) {
            MediaItem.DrmConfiguration.Builder drmBuilder =
                    new MediaItem.DrmConfiguration.Builder(C.CLEARKEY_UUID)
                            .setMultiSession(true)
                            .setForceSessionsForAudioAndVideoTracks(true);
            if (licenseKey != null && isLicenseUrl(licenseKey.trim())) {
                String licenseUrl = licenseKey.trim();
                int pipe = licenseUrl.indexOf('|');
                if (pipe > 0) licenseUrl = licenseUrl.substring(0, pipe).trim();
                drmBuilder.setLicenseUri(licenseUrl);
            }
            itemBuilder.setDrmConfiguration(drmBuilder.build());
        }

        MediaItem mediaItem = itemBuilder.build();
        String type = contentTypeHint != null ? contentTypeHint : "";
        String lower = url.toLowerCase(Locale.US);

        if (FormatDetector.isDash(type) || lower.contains(".mpd") || lower.contains("type=mpd")) {
            DashMediaSource.Factory factory = new DashMediaSource.Factory(httpFactory);
            if (drmSessionManager != null) {
                final DrmSessionManager locked = drmSessionManager;
                factory.setDrmSessionManagerProvider(new DrmSessionManagerProvider() {
                    @Override
                    public DrmSessionManager get(MediaItem mediaItem) {
                        return locked;
                    }
                });
            }
            return factory.createMediaSource(mediaItem);
        }
        if (FormatDetector.HLS.equals(type) || lower.contains(".m3u8") || lower.contains("type=m3u8")) {
            HlsMediaSource.Factory factory = new HlsMediaSource.Factory(httpFactory);
            if (drmSessionManager != null) {
                final DrmSessionManager locked = drmSessionManager;
                factory.setDrmSessionManagerProvider(new DrmSessionManagerProvider() {
                    @Override
                    public DrmSessionManager get(MediaItem mediaItem) {
                        return locked;
                    }
                });
            }
            return factory.createMediaSource(mediaItem);
        }
        ProgressiveMediaSource.Factory factory = new ProgressiveMediaSource.Factory(httpFactory);
        if (drmSessionManager != null) {
            final DrmSessionManager locked = drmSessionManager;
            factory.setDrmSessionManagerProvider(new DrmSessionManagerProvider() {
                @Override
                public DrmSessionManager get(MediaItem mediaItem) {
                    return locked;
                }
            });
        }
        return factory.createMediaSource(mediaItem);
    }

    // ------------------------------------------------------------------ ClearKey helpers

    private static boolean isLicenseUrl(String key) {
        String k = key.trim().toLowerCase(Locale.US);
        return k.startsWith("http://") || k.startsWith("https://");
    }

    private static boolean looksLikeInlineClearKey(String key) {
        if (key == null) return false;
        String k = key.trim();
        if (isLicenseUrl(k)) return false;
        // Al menos un par kid:key
        return k.indexOf(':') > 0;
    }

    /**
     * Construye el JSON ClearKey que espera Android / Media3.
     * Admite uno o varios pares KID:KEY separados por coma.
     * Cada componente puede ir en hex (32 chars) o base64/base64url.
     */
    @Nullable
    private static String buildClearKeyJson(String licenseKey) {
        String body = licenseKey.trim();
        // Quitar posibles cabeceras pegadas tras |
        int pipe = body.indexOf('|');
        if (pipe > 0) body = body.substring(0, pipe).trim();
        if (body.length() == 0 || isLicenseUrl(body)) return null;

        String[] pairs = body.split(",");
        StringBuilder keysJson = new StringBuilder();
        int count = 0;
        for (String pair : pairs) {
            String p = pair.trim();
            if (p.length() == 0) continue;
            int colon = p.indexOf(':');
            if (colon <= 0 || colon >= p.length() - 1) continue;
            String kidRaw = p.substring(0, colon).trim();
            String keyRaw = p.substring(colon + 1).trim();
            // A veces viene kid=...&key=...
            if (kidRaw.regionMatches(true, 0, "kid=", 0, 4)) {
                kidRaw = kidRaw.substring(4);
            }
            if (keyRaw.regionMatches(true, 0, "key=", 0, 4)) {
                keyRaw = keyRaw.substring(4);
            }
            String kidB64 = toBase64Url16(kidRaw);
            String keyB64 = toBase64Url16(keyRaw);
            if (kidB64 == null || keyB64 == null) continue;
            if (count > 0) keysJson.append(',');
            keysJson.append("{\"kty\":\"oct\",\"k\":\"").append(keyB64)
                    .append("\",\"kid\":\"").append(kidB64).append("\"}");
            count++;
        }
        if (count == 0) return null;
        return "{\"keys\":[" + keysJson + "],\"type\":\"temporary\"}";
    }

    /**
     * Convierte hex (32 chars) o base64/base64url de 16 bytes a base64url sin padding.
     */
    @Nullable
    private static String toBase64Url16(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.length() == 0) return null;
        byte[] bytes = null;
        // Hex puro de 16 bytes
        if (s.length() == 32 && isHex(s)) {
            bytes = hexToBytes(s);
        } else {
            // Base64 o base64url
            try {
                String normalized = s.replace('-', '+').replace('_', '/');
                while (normalized.length() % 4 != 0) normalized += '=';
                bytes = android.util.Base64.decode(normalized, android.util.Base64.DEFAULT);
            } catch (Throwable ignored) {
                bytes = null;
            }
        }
        if (bytes == null || bytes.length != 16) {
            // Algunos listados mandan 32 bytes; tomar los primeros 16 no es seguro — rechazar
            return null;
        }
        String b64 = android.util.Base64.encodeToString(bytes,
                android.util.Base64.NO_PADDING | android.util.Base64.NO_WRAP | android.util.Base64.URL_SAFE);
        return b64;
    }

    private static boolean isHex(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c < '0' || c > '9') && (c < 'a' || c > 'f') && (c < 'A' || c > 'F')) {
                return false;
            }
        }
        return true;
    }

    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            out[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return out;
    }

    /** Parsea "Name=Value&Name2=Value2" o "Name: Value" a mapa de cabeceras. */
    private static Map<String, String> parseHeaderSpec(@Nullable String spec) {
        HashMap<String, String> map = new HashMap<String, String>();
        if (spec == null) return map;
        String body = spec.trim();
        if (body.length() == 0) return map;
        // Separar por & o ;
        String[] parts = body.split("[&;]");
        for (String part : parts) {
            String p = part.trim();
            if (p.length() == 0) continue;
            int eq = p.indexOf('=');
            int colon = p.indexOf(':');
            int sep = -1;
            if (eq > 0 && (colon < 0 || eq < colon)) sep = eq;
            else if (colon > 0) sep = colon;
            if (sep <= 0) continue;
            String name = p.substring(0, sep).trim();
            String value = p.substring(sep + 1).trim();
            if (name.length() == 0) continue;
            // Quitar comillas
            if (value.length() >= 2 && value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"') {
                value = value.substring(1, value.length() - 1);
            }
            try {
                value = java.net.URLDecoder.decode(value, "UTF-8");
            } catch (Throwable ignored) {
            }
            map.put(name, value);
        }
        return map;
    }

    private static String userAgentFrom(Map<String, String> headers) {
        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                if (e.getKey() != null && "User-Agent".equalsIgnoreCase(e.getKey())
                        && e.getValue() != null && e.getValue().length() > 0) {
                    return e.getValue();
                }
            }
        }
        return "LENY-TV/1.70 (Android; Media3)";
    }

    public void setPaused(boolean paused) {
        if (player == null || released) return;
        player.setPlayWhenReady(!paused);
    }

    public boolean isPlaying() {
        return player != null && player.getPlayWhenReady()
                && player.getPlaybackState() == Player.STATE_READY;
    }

    public long getCurrentPosition() {
        return player != null ? player.getCurrentPosition() : 0L;
    }

    public long getDuration() {
        if (player == null) return 0L;
        long d = player.getDuration();
        return d == C.TIME_UNSET ? 0L : d;
    }

    public void seekTo(long positionMs) {
        if (player != null && !released) {
            player.seekTo(positionMs);
        }
    }

    int getVideoWidth() {
        return videoWidth;
    }

    int getVideoHeight() {
        return videoHeight;
    }

    // ------------------------------------------------------------------ Calidad

    public int qualityCount() {
        // +1 por la opción Automática
        return qualities.isEmpty() ? 0 : qualities.size() + 1;
    }

    public boolean hasSingleQuality() {
        return qualities.size() <= 1;
    }

    public String qualityLabel(int index) {
        if (index <= 0) return DashPlayer.AUTOMATIC_LABEL;
        int i = index - 1;
        if (i < 0 || i >= qualities.size()) return "—";
        return qualities.get(i).label;
    }

    public int qualityIndex() {
        return selectedQualityIndex;
    }

    public String singleQualityMessage() {
        if (qualities.isEmpty()) {
            return "Todavía no se han detectado calidades en este stream.";
        }
        return "Este canal solo ofrece una calidad: " + qualities.get(0).label;
    }

    /**
     * Cambia la calidad. index 0 = Automática.
     * Devuelve texto informativo o null.
     */
    @Nullable
    public String setQuality(int index) {
        if (player == null || trackSelector == null) {
            return "El motor aun no esta listo.";
        }
        selectedQualityIndex = Math.max(0, index);
        if (index <= 0) {
            trackSelector.setParameters(
                    trackSelector.buildUponParameters()
                            .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                            .setForceHighestSupportedBitrate(false)
                            .build());
            status("Media3 · calidad automatica");
            return "Calidad automatica (recomendada).";
        }
        int i = index - 1;
        if (i < 0 || i >= qualities.size()) {
            return "Indice de calidad no valido.";
        }
        QualityOption q = qualities.get(i);
        try {
            // Preferencia por altura maxima = la elegida
            DefaultTrackSelector.Parameters.Builder b = trackSelector.buildUponParameters()
                    .clearOverridesOfType(C.TRACK_TYPE_VIDEO);
            if (q.height > 0) {
                b.setMaxVideoSize(q.width > 0 ? q.width : 4096, q.height)
                 .setMinVideoSize(0, q.height > 100 ? q.height - 40 : 0);
            }
            if (q.bitrate > 0) {
                b.setMaxVideoBitrate(q.bitrate + 1000)
                 .setMinVideoBitrate(Math.max(0, q.bitrate - 200_000));
            }
            trackSelector.setParameters(b.build());
            status("Media3 · calidad fija: " + q.label);
            return "Calidad fijada a " + q.label;
        } catch (Throwable t) {
            return "No se pudo cambiar la calidad: " + safeMsg(t);
        }
    }

    public String statusLine() {
        if (player == null) return "Media3 · inactivo";
        int state = player.getPlaybackState();
        String s;
        switch (state) {
            case Player.STATE_IDLE:
                s = "idle";
                break;
            case Player.STATE_BUFFERING:
                s = "buffering";
                break;
            case Player.STATE_READY:
                s = "ready";
                break;
            case Player.STATE_ENDED:
                s = "ended";
                break;
            default:
                s = "state=" + state;
        }
        return "Media3 · " + s
                + (videoWidth > 0 ? " · " + videoWidth + "×" + videoHeight : "")
                + (qualities.isEmpty() ? "" : " · " + qualities.size() + " cal.");
    }

    public String timeText() {
        long pos = getCurrentPosition();
        long dur = getDuration();
        return formatMs(pos) + (dur > 0 ? " / " + formatMs(dur) : "");
    }


    /**
     * Elige una pista de audio reproducible. Prioriza AAC; evita silencio cuando
     * el selector deja AC-3/E-AC-3 no soportado por el aparato.
     */
    private void ensurePlayableAudioTrack() {
        ExoPlayer p = player;
        DefaultTrackSelector ts = trackSelector;
        if (p == null || ts == null || released) return;
        try {
            Tracks tracks = p.getCurrentTracks();
            if (tracks == null) return;
            int bestGroupIndex = -1;
            int bestTrackIndex = 0;
            int bestScore = -1;
            java.util.List<Tracks.Group> groups = tracks.getGroups();
            for (int gi = 0; gi < groups.size(); gi++) {
                Tracks.Group group = groups.get(gi);
                if (group.getType() != C.TRACK_TYPE_AUDIO) continue;
                for (int ti = 0; ti < group.length; ti++) {
                    if (!group.isTrackSupported(ti)) continue;
                    Format fmt = group.getTrackFormat(ti);
                    String mime = fmt == null || fmt.sampleMimeType == null
                            ? "" : fmt.sampleMimeType.toLowerCase(Locale.US);
                    int score = 10;
                    if (mime.contains("mp4a") || mime.contains("aac")) score = 100;
                    else if (mime.contains("mpeg") || mime.contains("mp3")) score = 80;
                    else if (mime.contains("opus")) score = 70;
                    else if (mime.contains("vorbis")) score = 60;
                    else if (mime.contains("ac-3") || mime.contains("ac3")
                            || mime.contains("eac3") || mime.contains("ec-3")) score = 5;
                    else if (mime.contains("dts")) score = 3;
                    if (score > bestScore) {
                        bestScore = score;
                        bestGroupIndex = gi;
                        bestTrackIndex = ti;
                    }
                }
            }
            if (bestGroupIndex < 0) {
                status("Media3 · sin pista de audio soportada en este dispositivo");
                return;
            }
            Tracks.Group chosen = groups.get(bestGroupIndex);
            Format fmt = chosen.getTrackFormat(bestTrackIndex);
            String mime = fmt != null && fmt.sampleMimeType != null ? fmt.sampleMimeType : "?";
            DefaultTrackSelector.Parameters.Builder b = ts.buildUponParameters()
                    .clearOverridesOfType(C.TRACK_TYPE_AUDIO);
            b.setOverrideForType(new TrackSelectionOverride(chosen.getMediaTrackGroup(), bestTrackIndex));
            ts.setParameters(b.build());
            status("Media3 · audio: " + mime);
        } catch (Throwable ex) {
            status("Media3 · no se pudo fijar pista de audio: " + safeMsg(ex));
        }
    }

    public void release() {
        released = true;
        releaseInternal(true);
    }

    private void releaseInternal(boolean full) {
        ExoPlayer p = player;
        player = null;
        trackSelector = null;
        if (p != null) {
            try {
                p.setVideoSurface(null);
            } catch (Throwable ignored) {
            }
            try {
                p.release();
            } catch (Throwable ignored) {
            }
        }
        if (full) {
            surface = null;
        }
    }

    // ------------------------------------------------------------------ Listener interno

    private final Player.Listener playerListener = new Player.Listener() {
        @Override
        public void onPlaybackStateChanged(int playbackState) {
            if (released) return;
            if (playbackState == Player.STATE_BUFFERING) {
                notifyBuffering(true);
            } else if (playbackState == Player.STATE_READY) {
                notifyBuffering(false);
                notifyPrepared();
                refreshQualities();
                ensurePlayableAudioTrack();
            } else if (playbackState == Player.STATE_ENDED) {
                notifyFinished();
            }
        }

        @Override
        public void onIsPlayingChanged(boolean isPlaying) {
            if (released) return;
            if (listener != null) {
                listener.onPlayingChanged(isPlaying);
            }
        }

        @Override
        public void onVideoSizeChanged(androidx.media3.common.VideoSize videoSize) {
            if (released) return;
            videoWidth = videoSize.width;
            videoHeight = videoSize.height;
            if (listener != null && videoWidth > 0 && videoHeight > 0) {
                listener.onVideoSize(videoWidth, videoHeight);
            }
        }

        @Override
        public void onPlayerError(PlaybackException error) {
            if (released) return;
            notifyBuffering(false);
            int errCode = error != null ? error.errorCode : 0;
            String code = error != null ? ("codigo " + errCode) : "sin codigo";
            String msg = error != null ? error.getMessage() : "Error desconocido";
            boolean drm = error != null && (
                    errCode == PlaybackException.ERROR_CODE_DRM_SCHEME_UNSUPPORTED
                            || errCode == PlaybackException.ERROR_CODE_DRM_PROVISIONING_FAILED
                            || errCode == PlaybackException.ERROR_CODE_DRM_CONTENT_ERROR
                            || errCode == PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED
                            || errCode == PlaybackException.ERROR_CODE_DRM_DISALLOWED_OPERATION
                            || errCode == PlaybackException.ERROR_CODE_DRM_SYSTEM_ERROR
                            || errCode == PlaybackException.ERROR_CODE_DRM_DEVICE_REVOKED
                            || errCode == PlaybackException.ERROR_CODE_DRM_LICENSE_EXPIRED
            );
            boolean retryable = error != null && !drm && (
                    errCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
                            || errCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
                            || errCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED
                            || errCode == PlaybackException.ERROR_CODE_TIMEOUT
                            || errCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
            );
            status("Media3 · error " + code + (drm ? " · DRM" : "") + ": " + msg);
            String title = drm ? "Error de licencia DRM" : "Error de reproduccion";
            String body = drm
                    ? "El contenido esta cifrado y no se pudo obtener o aplicar la licencia ClearKey. "
                      + "Comprueba que la lista declare license_type=clearkey y una license_key valida "
                      + "(formato KID:KEY en hex o URL de licencia). "
                      + "No se muestran claves en este mensaje.\n(" + code + ")"
                    : ((msg != null ? msg : "Fallo del motor Media3") + "\n(" + code + ")");
            notifyError(title, body, retryable);
        }

        @Override
        public void onTracksChanged(Tracks tracks) {
            if (released) return;
            refreshQualities();
        }
    };

    private void refreshQualities() {
        qualities.clear();
        if (player == null) return;
        try {
            Tracks tracks = player.getCurrentTracks();
            for (Tracks.Group group : tracks.getGroups()) {
                if (group.getType() != C.TRACK_TYPE_VIDEO) continue;
                for (int i = 0; i < group.length; i++) {
                    Format format = group.getTrackFormat(i);
                    int h = format.height;
                    int w = format.width;
                    int br = format.bitrate;
                    if (h <= 0 && br <= 0) continue;
                    String label;
                    if (h > 0) {
                        label = h + "p";
                        if (br > 0) {
                            label += " · " + (br / 1000) + " kbps";
                        }
                    } else {
                        label = (br / 1000) + " kbps";
                    }
                    // groupIndex: usamos 0 para el primer grupo de vídeo (simplificado)
                    qualities.add(new QualityOption(0, i, h, w, br, label));
                }
            }
            // Ordenar de mayor a menor altura
            java.util.Collections.sort(qualities, new java.util.Comparator<QualityOption>() {
                @Override
                public int compare(QualityOption a, QualityOption b) {
                    if (b.height != a.height) return b.height - a.height;
                    return b.bitrate - a.bitrate;
                }
            });
            if (!qualities.isEmpty()) {
                status("Media3 · " + qualities.size() + " calidades detectadas");
            }
        } catch (Throwable t) {
            status("Media3 · no se pudieron listar calidades: " + safeMsg(t));
        }
    }

    private void status(String msg) {
        if (listener != null) listener.onStatus(msg);
    }

    private void notifyBuffering(final boolean active) {
        if (listener == null) return;
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                listener.onBuffering(active);
            }
        });
    }

    private void notifyPrepared() {
        if (listener == null) return;
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                listener.onPrepared();
            }
        });
    }

    private void notifyFinished() {
        if (listener == null) return;
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                listener.onFinished();
            }
        });
    }

    private void notifyError(final String title, final String body, final boolean retryable) {
        if (listener == null) return;
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                listener.onError(title, body, retryable);
            }
        });
    }

    private static String shortUrl(String url) {
        if (url == null) return "";
        if (url.length() <= 80) return url;
        return url.substring(0, 77) + "…";
    }

    private static String safeMsg(Throwable t) {
        if (t == null) return "error desconocido";
        String m = t.getMessage();
        return m != null && m.length() > 0 ? m : t.getClass().getSimpleName();
    }

    private static String formatMs(long ms) {
        if (ms < 0) ms = 0;
        long total = ms / 1000L;
        long h = total / 3600L;
        long m = (total % 3600L) / 60L;
        long s = total % 60L;
        if (h > 0) {
            return String.format(Locale.US, "%d:%02d:%02d", h, m, s);
        }
        return String.format(Locale.US, "%02d:%02d", m, s);
    }
}
