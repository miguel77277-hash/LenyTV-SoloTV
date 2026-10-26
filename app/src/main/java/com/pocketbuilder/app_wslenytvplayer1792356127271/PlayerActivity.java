package com.pocketbuilder.app_wslenytvplayer1792356127271;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Reproductor interno de LENY TV.
 * Motor: AndroidX Media3 (ExoPlayer) — sustituye al antiguo MediaPlayer + DashPlayer.
 * La interfaz, controles, gestos, panel lateral y bloqueo de pantalla se mantienen
 * exactamente iguales.
 */
public final class PlayerActivity extends Activity implements SurfaceHolder.Callback {

    static final String EXTRA_MODE = "mode";
    static final String EXTRA_PLAYLIST_ID = "playlistId";
    static final String EXTRA_URL = "url";
    static final String EXTRA_NAME = "name";
    static final String EXTRA_CATEGORY = "category";
    static final String EXTRA_TYPE = "type";
    /**
     * Cabeceras propias del canal, escritas como "Nombre: valor" por línea. Se
     * reciben ya leídas de la lista para que el PRIMER intento de reproducción
     * use las del canal y no las generales.
     */
    static final String EXTRA_HEADERS = "headers";
    static final String EXTRA_LICENSE_TYPE = "licenseType";
    static final String EXTRA_LICENSE_KEY = "licenseKey";
    static final String EXTRA_LICENSE_STREAM_HEADERS = "licenseStreamHeaders";
    static final String MODE_LIST = "list";
    static final String MODE_SINGLE = "single";

    /** Tiempo máximo que se espera a que el reproductor empiece a dar imagen. */
    private static final long PREPARE_TIMEOUT_MS = 20000L;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ArrayList<Channel> channels = new ArrayList<Channel>();
    private final ArrayList<Integer> trackIndexes = new ArrayList<Integer>();
    /** Hilo de trabajo para leer la lista de canales sin bloquear la pantalla. */
    private final ExecutorService background = Executors.newSingleThreadExecutor();

    private String mode = MODE_SINGLE;
    private String categoryFilter = "";
    private PlaylistStore.Playlist playlist;
    private int index = 0;
    private String manualType = "";

    /** Motor Media3 (ExoPlayer). Sustituye a MediaPlayer + DashPlayer. */
    private Media3Engine media3;
    private Surface surface;
    private boolean prepared = false;
    private boolean playing = false;
    private boolean finished = false;
    private int videoWidth = 0;
    private int videoHeight = 0;
    private String currentType = FormatDetector.OTHER;
    private boolean wasPlayingBeforePause = false;
    /**
     * Enlace de la calidad elegida cuando el canal NO usa el motor DASH (por
     * ejemplo una variante de una lista maestra HLS). Vacío = el enlace del canal.
     */
    private String variantUrl = "";
    private String variantLabel = "";
    /** Resolución pedida antes de arrancar el motor DASH (-1 = automática). */
    private int preferredDashQuality = -1;

    private FrameLayout root;
    private FrameLayout videoBox;
    private SurfaceView surfaceView;
    private FrameLayout messageHolder;
    private FrameLayout drawerHolder;
    private FrameLayout settingsHolder;
    private FrameLayout choiceHolder;
    private LinearLayout topBar;
    private LinearLayout bottomBar;
    private LinearLayout gesturePanel;
    private TextView messageTitle;
    private TextView messageBody;
    private ScrollView messageScroll;
    private TextView messageRetry;
    /** Texto del último aviso del reproductor, para poder copiarlo. */
    private String messageText = "";
    private TextView topTitle;
    private TextView topSubtitle;
    private TextView timeView;
    private TextView durationView;
    private SeekBar progressBar;
    private LinearLayout volumeRow;
    private TextView gestureLabel;
    private View playButton;
    private View lockButton;
    private View fullscreenButton;
    private View drawerButton;
    private View prevButton;
    private View nextButton;
    private View qualityButton;
    private View aspectButton;
    private View pipButton;
    private TextView drawerCount;
    private ProgressBar buffering;
    private ProgressBar gestureBar;
    private SeekBar volumeBar;
    private ListView drawerList;
    private DrawerAdapter drawerAdapter;
    /** Fila de apartados que se eligen dentro del propio panel de canales. */
    private LinearLayout drawerSectionRow;
    /** Apartado elegido en el panel ("" = todos los canales de la lista). */
    private String drawerSection = "";
    /** Canales visibles en el panel según el apartado elegido. */
    private final ArrayList<Integer> drawerPositions = new ArrayList<Integer>();

    private boolean locked = false;
    private boolean controlsVisible = true;
    private boolean immersive = true;
    private int audioMax = 15;
    private long lastTapTime = 0;
    /** Primero se intenta el reproductor directo; si falla, se usa el puente local con cabeceras. */
    private boolean useProxy = false;
    private boolean proxyAttempted = false;
    /** De dónde salió el tipo de contenido: extensión, servidor o elección manual. */
    private String typeOrigin = "";
    /** Número del intento en curso: invalida los avisos de un intento anterior. */
    private int attemptToken = 0;
    /** Intento al que pertenece el vigilante de preparación que está en marcha. */
    private int watchdogAttempt = -1;
    private int insetLeft = -1;
    private int insetTop = -1;
    private int insetRight = -1;
    private int insetBottom = -1;

    private final Runnable hideControls = new Runnable() {
        @Override
        public void run() {
            if (!locked && !isPanelOpen()) setControlsVisible(false);
        }
    };

    private final Runnable hideGesture = new Runnable() {
        @Override
        public void run() {
            if (gesturePanel != null) gesturePanel.setVisibility(View.GONE);
        }
    };

    private final Runnable timeTick = new Runnable() {
        @Override
        public void run() {
            updateTime();
            if (media3 != null) ui.postDelayed(this, 1000);
        }
    };

    /**
     * Vigilante de la preparación: si el servidor acepta la conexión pero no llega
     * imagen ni error, antes el círculo amarillo giraba para siempre y el registro
     * no decía nada. Ahora, pasados unos segundos, se avisa con el motivo exacto
     * en pantalla y en el diagnóstico.
     */
    private final Runnable prepareWatchdog = new Runnable() {
        @Override
        public void run() {
            onPrepareTimeout();
        }
    };

    private interface ChoiceListener {
        void onChoice(int position);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent intent = getIntent();
        String url = intent.getStringExtra(EXTRA_URL);
        String name = intent.getStringExtra(EXTRA_NAME);
        String requestedMode = intent.getStringExtra(EXTRA_MODE);
        manualType = intent.getStringExtra(EXTRA_TYPE) == null ? "" : intent.getStringExtra(EXTRA_TYPE);
        if (requestedMode != null && requestedMode.length() > 0) mode = requestedMode;
        categoryFilter = intent.getStringExtra(EXTRA_CATEGORY) == null ? "" : intent.getStringExtra(EXTRA_CATEGORY);
        // El panel de canales arranca en el apartado desde el que se abrió el canal,
        // y desde él se pueden elegir los demás apartados sin salir del reproductor.
        drawerSection = categoryFilter;
        long playlistId = intent.getLongExtra(EXTRA_PLAYLIST_ID, -1L);
        // Cabeceras del bloque M3U del canal (User-Agent, Referer, Origin, Cookie...).
        Map<String, String> ownHeaders = Headers.fromSpec(intent.getStringExtra(EXTRA_HEADERS));

        if (url == null || url.length() == 0) {
            toast("No se recibió ningún enlace");
            finish();
            return;
        }

        // El canal pedido se abre de inmediato. La lista completa se lee después,
        // en segundo plano: una lista muy grande ya no puede congelar la pantalla
        // (antes se leía aquí mismo, en el hilo principal).
        String licType = intent.getStringExtra(EXTRA_LICENSE_TYPE);
        String licKey = intent.getStringExtra(EXTRA_LICENSE_KEY);
        String licStream = intent.getStringExtra(EXTRA_LICENSE_STREAM_HEADERS);
        channels.add(new Channel(name == null || name.length() == 0 ? M3uParser.guessName(url) : name,
                url, "", categoryFilter.length() == 0 ? "Enlace directo" : categoryFilter,
                ownHeaders, null,
                licType == null ? "" : licType,
                licKey == null ? "" : licKey,
                licStream == null ? "" : licStream));
        index = 0;

        if (MODE_LIST.equals(mode)) {
            playlist = PlaylistStore.byId(this, playlistId);
            if (playlist == null) playlist = PlaylistStore.active(this);
        }

        buildUi();
        applyImmersive(true);
        // El vídeo y el fondo llenan toda la pantalla: los márgenes del recorte de
        // la cámara se los queda solo la barra de controles para que nada quede
        // tapado. Antes se encogía todo el contenido y el vídeo quedaba con franjas.
        Immersion.listenInsets(root, new Immersion.InsetCallback() {
            @Override
            public void onInsets(int left, int top, int right, int bottom) {
                applyControlInsets(left, top, right, bottom);
            }
        });

        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (am != null) audioMax = Math.max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        if (volumeBar != null) volumeBar.setProgress(volumePercent());
        applyKeepScreenOn();

        currentType = manualType.length() > 0 ? manualType : resolveType(currentChannel().url);
        // Primero la preparación corta (bloque del canal y tipo del enlace) y
        // después la lectura completa de la lista: así un catálogo enorme no
        // retrasa el arranque del canal que el usuario acaba de tocar.
        beginFirstPlayback(playlistId);
        if (MODE_LIST.equals(mode)) loadChannelList(playlistId, url, name);
    }

    /**
     * Prepara el PRIMER intento con los datos REALES del canal.
     *
     * Antes, al abrir un canal desde la cuadrícula, la reproducción empezaba con
     * las cabeceras generales porque el bloque del canal se leía después; y un
     * enlace DASH cuya dirección no termina en ".mpd" no se reconocía. Ahora,
     * cuando hace falta, se busca el bloque del canal en la lista (en segundo
     * plano, sin congelar la pantalla) y se pregunta al servidor qué tipo de
     * contenido sirve. Solo después se arranca la reproducción.
     */
    private void beginFirstPlayback(final long playlistId) {
        final Channel initial = currentChannel();
        final boolean needBlock = MODE_LIST.equals(mode) && !initial.hasOwnHeaders();
        final boolean needType = manualType.length() == 0
                && FormatDetector.OTHER.equals(currentType)
                && LocalProxy.isHttp(initial.url);
        if (!needBlock && !needType) {
            startPlayback();
            return;
        }
        if (buffering != null) setBuffering(true);
        try {
            background.execute(new Runnable() {
                @Override
                public void run() {
                    Channel found = null;
                    if (needBlock) {
                        try {
                            PlaylistStore.Playlist list = playlist;
                            String text = PlaylistStore.readCache(PlayerActivity.this, list);
                            if (text != null && text.length() > 0) {
                                found = M3uParser.lookupBlock(text, initial.url,
                                        list != null && !list.isDemo() ? list.ref : null);
                            }
                        } catch (OutOfMemoryError oom) {
                            Diag.add("Reproductor · la lista es demasiado grande para buscar el bloque del canal");
                        } catch (Throwable t) {
                            Diag.add("Reproductor · no se pudo buscar el bloque del canal: " + Net.describe(t));
                        }
                    }
                    Net.Sniff sniff = null;
                    if (needType) {
                        try {
                            sniff = Net.sniffType(initial.url,
                                    channelHeaders(found != null ? found : initial));
                        } catch (Throwable t) {
                            Diag.add("Reproductor · no se pudo averiguar el tipo del enlace: " + Net.describe(t));
                        }
                    }
                    final Channel enrichment = found;
                    final Net.Sniff detected = sniff;
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if (isFinishing() || isDestroyed()) return;
                            if (enrichment != null) applyChannelBlock(enrichment);
                            if (detected != null) applySniff(detected);
                            setBuffering(false);
                            startPlayback();
                        }
                    });
                }
            });
        } catch (Throwable t) {
            Diag.add("Reproductor · no se pudo preparar el primer intento: " + Net.describe(t));
            setBuffering(false);
            startPlayback();
        }
    }

    /**
     * Sustituye el canal de arranque por su entrada completa de la lista, con las
     * cabeceras y la licencia de su bloque, ANTES de reproducir.
     */
    private void applyChannelBlock(Channel found) {
        if (found == null) return;
        Channel current = currentChannel();
        if (current == null || !current.url.equals(found.url)) return;
        channels.set(index, found);
        Diag.add("Reproductor · bloque del canal leído ANTES del primer intento: " + found.blockSummary());
        EngineInfo.headers(found.blockSummary() + " · valores enviados: " + Headers.describe(found.headers));
        if (found.hasLicense()) {
            EngineInfo.license(found.licenseSummary());
            Diag.add("Reproductor · " + found.licenseSummary());
        }
        updateTopBar();
    }

    /**
     * Aplica lo que el servidor contestó sobre el tipo de contenido. Solo cambia
     * el tipo cuando la dirección no lo decía (extensión desconocida) y el usuario
     * no lo ha elegido a mano: así no se rompe ningún formato que ya funcionaba.
     */
    private void applySniff(Net.Sniff sniff) {
        if (sniff == null) return;
        EngineInfo.detect(sniff.detail + " → " + sniff.type
                + (sniff.how.length() == 0 ? "" : " (" + sniff.how + ")"));
        Diag.add("Reproductor · tipo del enlace: " + sniff.detail
                + (sniff.how.length() == 0 ? "" : " · " + sniff.how));
        if (manualType.length() == 0 && FormatDetector.OTHER.equals(currentType)
                && !FormatDetector.OTHER.equals(sniff.type)) {
            currentType = sniff.type;
            typeOrigin = " (reconocido por lo que informa el servidor: " + sniff.how + ")";
            Diag.add("Reproductor · la dirección no lo indicaba, pero el enlace es " + sniff.type
                    + " · " + sniff.how + " · " + sniff.detail);
        }
    }

    /**
     * Lee la lista de canales en segundo plano y la muestra cuando está lista.
     * El lector solo extrae y valida las entradas: no reproduce nada.
     */
    private void loadChannelList(final long playlistId, final String url, final String name) {
        background.execute(new Runnable() {
            @Override
            public void run() {
                final ArrayList<Channel> parsed = new ArrayList<Channel>();
                String problem = null;
                try {
                    // Una o varias listas activas: cada una se lee por separado y se unen
                    // los canales (misma idea que en el menú principal).
                    java.util.ArrayList<PlaylistStore.Playlist> lists =
                            PlaylistStore.activeLists(PlayerActivity.this);
                    if (lists.isEmpty() && playlist != null) lists.add(playlist);
                    if (lists.isEmpty()) {
                        problem = "no hay lista activa";
                    } else {
                        for (int li = 0; li < lists.size(); li++) {
                            PlaylistStore.Playlist list = lists.get(li);
                            String text;
                            if (list.isDemo()) {
                                text = DemoData.text();
                            } else {
                                text = PlaylistStore.readCache(PlayerActivity.this, list);
                            }
                            if (text == null || text.length() == 0) {
                                Diag.add("Reproductor · sin caché para " + list.name);
                                continue;
                            }
                            M3uParser.Result result = M3uParser.parse(text,
                                    list.isDemo() ? null : list.ref);
                            if (result.mediaPlaylist) {
                                Diag.add("Reproductor · " + list.name + " es vídeo HLS, se omite");
                                continue;
                            }
                            for (int i = 0; i < result.channels.size(); i++) {
                                Channel ch = result.channels.get(i);
                                if (ch == null || ch.name == null) continue;
                                String n = ch.name.trim().toLowerCase(java.util.Locale.US);
                                if (n.endsWith(".mpd") || n.endsWith(".m3u8") || n.endsWith(".m3u")
                                        || n.equals("index") || n.startsWith("index.")
                                        || n.equals("cenc") || n.startsWith("cenc.")) {
                                    continue;
                                }
                                parsed.add(ch);
                            }
                            Diag.add("Reproductor · " + list.name + ": " + result.summary());
                        }
                        if (parsed.isEmpty()) {
                            problem = "las listas activas no tienen canales legibles";
                        }
                    }
                } catch (OutOfMemoryError oom) {
                    problem = "la lista es demasiado grande para la memoria de este teléfono";
                } catch (Throwable t) {
                    problem = Net.describe(t);
                }
                final String failure = problem;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        applyChannelList(parsed, failure);
                    }
                });
            }
        });
    }

    /** Sustituye la lista lateral por la leída cuando llega desde el hilo de trabajo. */
    private void applyChannelList(ArrayList<Channel> parsed, String problem) {
        if (isFinishing() || isDestroyed()) return;
        if (parsed.isEmpty()) {
            Diag.add("Reproductor · no se pudo añadir la lista: "
                    + (problem == null ? "sin entradas" : problem));
            return;
        }
        String currentUrl = index >= 0 && index < channels.size() ? channels.get(index).url : "";
        channels.clear();
        channels.addAll(parsed);
        int position = indexOfUrl(currentUrl);
        if (position < 0) {
            channels.add(0, new Channel(currentUrl.length() == 0 ? "Entrada" : M3uParser.guessName(currentUrl),
                    currentUrl, "", categoryFilter.length() == 0 ? "Enlace directo" : categoryFilter));
            position = 0;
        }
        index = position;
        rebuildDrawerSections();
        updateTopBar();
        Diag.add("Reproductor · lista del canal: " + channels.size() + " entradas"
                + (categoryFilter.length() == 0 ? "" : " de la categoría " + categoryFilter)
                + " · canal actual " + (index + 1));
        Channel now = currentChannel();
        if (now.hasOwnHeaders()) {
            Diag.add("Reproductor · el canal actual usa las cabeceras de su bloque: " + now.blockSummary());
            EngineInfo.headers(now.blockSummary() + " · valores: " + Headers.describe(now.headers));
        }
        if (now.hasLicense()) {
            Diag.add("Reproductor · " + now.licenseSummary());
            EngineInfo.license(now.licenseSummary());
        }
    }

    private int indexOfUrl(String url) {
        for (int i = 0; i < channels.size(); i++) {
            if (channels.get(i).url.equals(url)) return i;
        }
        return -1;
    }

    private Channel currentChannel() {
        if (index < 0 || index >= channels.size()) index = 0;
        return channels.get(index);
    }

    // ------------------------------------------------------------------ vista

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);

        videoBox = new FrameLayout(this);
        surfaceView = new SurfaceView(this);
        surfaceView.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        surfaceView.getHolder().addCallback(this);
        videoBox.addView(surfaceView);
        videoBox.setClickable(true);
        videoBox.setOnTouchListener(new GestureListener());
        root.addView(videoBox, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        buffering = new ProgressBar(this);
        buffering.setIndeterminate(true);
        buffering.setIndeterminateTintList(ColorStateList.valueOf(Ui.RED));
        buffering.setVisibility(View.GONE);
        FrameLayout.LayoutParams bufferParams = new FrameLayout.LayoutParams(
                Ui.dpi(this, 54), Ui.dpi(this, 54));
        bufferParams.gravity = Gravity.CENTER;
        // El círculo de carga queda por encima de la banda de controles.
        bufferParams.bottomMargin = Ui.dpi(this, 92);
        root.addView(buffering, bufferParams);

        root.addView(buildMessagePanel());
        // REVISIÓN 50: barras del reproductor rehechas para copiar la captura.
        root.addView(buildTopBarSkin());
        root.addView(buildBottomBarSkin());
        // REVISIÓN 52: el cartel con el nombre y los datos del canal ya NO se crea.
        // Aparecía en mitad de la imagen cada vez que se cambiaba de canal (el
        // recuadro con «ESPN 1 opcion 4 Hls · Otro (directo) · 1280×720 · 157 de
        // 1157» de la captura) y el usuario ha pedido quitarlo.
        root.addView(buildGesturePanel());

        lockButton = skinButton(Icons.lock(this, Ui.TEXT, true), 44f, "Bloquear la pantalla");
        lockButton.setAlpha(0.85f);
        lockButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleLock();
            }
        });
        FrameLayout.LayoutParams lockParams = new FrameLayout.LayoutParams(
                Ui.dpi(this, 44), Ui.dpi(this, 44));
        // REVISIÓN 50: el candado va arriba a la derecha, sobre la barra superior,
        // como en la captura del reproductor.
        lockParams.gravity = Gravity.TOP | Gravity.END;
        lockParams.rightMargin = Ui.dpi(this, 6);
        lockParams.topMargin = Ui.dpi(this, 4);
        root.addView(lockButton, lockParams);

        // El botón de bloqueo flota SIEMPRE por encima de la banda de controles.
        // Antes estaba a 14 dp del borde inferior y se dibujaba encima del
        // deslizador de brillo: ahora su altura se calcula con el alto real de la
        // banda, así no tapa ningún control ni en horizontal ni en vertical.
        if (bottomBar != null) {
            bottomBar.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                @Override
                public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                           int oldLeft, int oldTop, int oldRight, int oldBottom) {
                    int height = bottom - top;
                    if (height <= 0 || lockButton == null) return;
                    ViewGroup.LayoutParams raw = lockButton.getLayoutParams();
                    if (!(raw instanceof FrameLayout.LayoutParams)) return;
                    FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) raw;
                    int wanted = height + Ui.dpi(PlayerActivity.this, 8);
                    if (params.bottomMargin != wanted) {
                        params.bottomMargin = wanted;
                        lockButton.setLayoutParams(params);
                    }
                }
            });
        }

        drawerHolder = buildDrawer();
        root.addView(drawerHolder, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        setContentView(root);
        updateTopBar();
        updatePlayButton();
        setControlsVisible(true);
    }

    private View buildMessagePanel() {
        messageHolder = new FrameLayout(this);
        messageHolder.setVisibility(View.GONE);
        messageHolder.setBackgroundColor(0x66000000);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(Ui.roundedStroke(this, 0xF212121C, Ui.RED, 1.5f, 12f));
        panel.setPadding(Ui.dpi(this, 18), Ui.dpi(this, 16), Ui.dpi(this, 18), Ui.dpi(this, 16));

        messageTitle = Ui.text(this, "", 17f, Ui.TEXT, true);
        panel.addView(messageTitle);

        messageBody = Ui.text(this, "", 12f, Ui.TEXT_DIM, false);
        messageBody.setLineSpacing(Ui.dp(this, 2f), 1f);
        // El motivo del fallo puede ser largo (por ejemplo el informe del enlace):
        // va dentro de un contenedor con desplazamiento para que se pueda leer todo.
        messageScroll = new ScrollView(this);
        messageScroll.addView(messageBody, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dpi(this, 120));
        bodyParams.topMargin = Ui.dpi(this, 8);
        panel.addView(messageScroll, bodyParams);

        // Los cinco botones del aviso se reparten en las líneas que hagan falta:
        // antes iban en una sola fila y en una pantalla estrecha los últimos
        // (Probar enlace, Volver) se salían del cuadro del aviso.
        WrapRow buttons = new WrapRow(this, Ui.dpi(this, 8), Ui.dpi(this, 8));

        messageRetry = Ui.primaryButton(this, "Reintentar");
        messageRetry.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hideMessage();
                // Vuelve a probar desde el principio: DASH con el motor interno y,
                // cuando el tipo no es DASH, con el modo compatible de cabeceras.
                proxyAttempted = false;
                useProxy = false;
                startPlayback();
            }
        });
        buttons.addView(messageRetry);

        TextView pick = Ui.actionButton(this, "Elegir canal");
        LinearLayout.LayoutParams p1 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p1.leftMargin = Ui.dpi(this, 8);
        pick.setLayoutParams(p1);
        pick.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openDrawer();
            }
        });
        buttons.addView(pick);

        TextView copy = Ui.actionButton(this, "Copiar motivo");
        copy.setContentDescription("Copiar el motivo del fallo para poder enviarlo");
        LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        copyParams.leftMargin = Ui.dpi(this, 8);
        copy.setLayoutParams(copyParams);
        copy.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyMessage();
            }
        });
        buttons.addView(copy);

        // Prueba el enlace y explica qué contesta el servidor de verdad: es lo que
        // permite distinguir conexión, código HTTP, manifiesto inválido, códec o DRM.
        TextView probe = Ui.actionButton(this, "Probar enlace");
        probe.setContentDescription("Probar el enlace y mostrar qué responde el servidor");
        LinearLayout.LayoutParams probeParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        probeParams.leftMargin = Ui.dpi(this, 8);
        probe.setLayoutParams(probeParams);
        probe.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                probeCurrentLink();
            }
        });
        buttons.addView(probe);

        TextView back = Ui.actionButton(this, "Volver");
        LinearLayout.LayoutParams p2 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p2.leftMargin = Ui.dpi(this, 8);
        back.setLayoutParams(p2);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        buttons.addView(back);

        LinearLayout.LayoutParams buttonsParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        buttonsParams.topMargin = Ui.dpi(this, 14);
        panel.addView(buttons, buttonsParams);

        // El ancho se limita a la pantalla para que los cinco botones del aviso
        // (Reintentar, Elegir canal, Copiar motivo, Probar enlace y Volver) quepan
        // siempre, también en pantallas de móvil estrechas.
        int panelWidth = Math.min(Ui.dpi(this, 620),
                getResources().getDisplayMetrics().widthPixels - Ui.dpi(this, 40));
        FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(
                panelWidth, FrameLayout.LayoutParams.WRAP_CONTENT);
        panelParams.gravity = Gravity.CENTER;
        messageHolder.addView(panel, panelParams);
        messageHolder.setClickable(true);
        return messageHolder;
    }

    private View buildTopBar() {
        // La barra superior ocupa DOS FILAS: arriba el nombre del canal y los
        // botones de ventana; debajo, CALIDAD e IMAGEN. Antes todo iba en la misma
        // fila con anchos fijos y en pantallas estrechas (o en vertical) los
        // últimos botones se salían de su cuadro; ahora cada fila cabe entera.
        topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.VERTICAL);
        topBar.setPadding(Ui.dpi(this, 10), Ui.dpi(this, 8), Ui.dpi(this, 10), Ui.dpi(this, 6));
        android.graphics.drawable.GradientDrawable shade = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xE6000000, 0x00000000});
        topBar.setBackground(shade);

        LinearLayout firstRow = new LinearLayout(this);
        firstRow.setOrientation(LinearLayout.HORIZONTAL);
        firstRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView back = Ui.iconButton(this, Icons.back(this, Ui.TEXT), "Volver a la lista de canales", 44f);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        firstRow.addView(back);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        topTitle = Ui.text(this, "", 16f, Ui.TEXT, true);
        topTitle.setMaxLines(1);
        topTitle.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(topTitle);
        topSubtitle = Ui.text(this, "", 11f, Ui.TEXT_DIM, false);
        topSubtitle.setMaxLines(1);
        topSubtitle.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(topSubtitle);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        textParams.leftMargin = Ui.dpi(this, 10);
        firstRow.addView(texts, textParams);

        qualityButton = barPlate("Calidad", "Elegir la calidad de vídeo", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openQualityPanel();
            }
        });
        LinearLayout chipRow = new LinearLayout(this);
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        chipRow.setGravity(Gravity.END);
        chipRow.addView(qualityButton);

        aspectButton = barPlate("Imagen", "Ajustar la imagen en pantalla", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openAspectPanel();
            }
        });
        chipRow.addView(aspectButton);

        TextView settings = Ui.iconButton(this, Icons.gear(this, Ui.TEXT), "Ajustes del reproductor", 44f);
        settings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openSettingsPanel();
            }
        });
        firstRow.addView(settings);

        fullscreenButton = Ui.iconButton(this, Icons.expand(this, Ui.TEXT), "Pantalla completa", 44f);
        fullscreenButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                applyImmersive(!immersive);
                scheduleHide();
            }
        });
        firstRow.addView(fullscreenButton);

        drawerButton = Ui.iconButton(this, Icons.list(this, Ui.TEXT), "Lista de canales", 44f);
        drawerButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openDrawer();
            }
        });
        firstRow.addView(drawerButton);

        topBar.addView(firstRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams chipParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        chipParams.topMargin = Ui.dpi(this, 4);
        topBar.addView(chipRow, chipParams);

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        params.gravity = Gravity.TOP;
        topBar.setLayoutParams(params);
        return topBar;
    }

    private View buildBottomBar() {
        // La banda inferior se reparte en DOS FILAS:
        //   1. anterior / reproducir / siguiente y el tiempo del canal.
        //   2. volumen y brillo, cada deslizador ocupando el espacio que sobra.
        // Antes iba todo en una sola fila con anchos fijos (150 dp + 110 dp para los
        // deslizadores): en un teléfono en vertical, o en horizontal pequeño, la
        // suma superaba el ancho de la pantalla y los últimos controles se salían
        // de su cuadro. Con dos filas y anchos elásticos, todo queda dentro.
        bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.VERTICAL);
        bottomBar.setGravity(Gravity.CENTER_HORIZONTAL);
        bottomBar.setPadding(Ui.dpi(this, 10), Ui.dpi(this, 8), Ui.dpi(this, 10), Ui.dpi(this, 8));
        // Banda de controles pegada al borde inferior (como en el reproductor de
        // referencia): el fondo se oscurece abajo y se desvanece hacia arriba, así
        // los botones se leen bien sin tapar la imagen.
        android.graphics.drawable.GradientDrawable shade = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xF2000000, 0x99000000, 0x00000000});
        bottomBar.setBackground(shade);

        LinearLayout transport = new LinearLayout(this);
        transport.setOrientation(LinearLayout.HORIZONTAL);
        transport.setGravity(Gravity.CENTER_VERTICAL);

        prevButton = barPlate("◀◀", "Canal anterior", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                playIndex(index - 1);
            }
        });
        transport.addView(prevButton);

        playButton = barPlate("▶", "Reproducir o pausar", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                togglePlay();
            }
        });
        // (Bloque anterior del reproductor: ya no se usa. La barra que se ve es la
        // de la captura, creada en buildBottomBarSkin.)
        transport.addView(playButton);

        nextButton = barPlate("▶▶", "Canal siguiente", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                playIndex(index + 1);
            }
        });
        transport.addView(nextButton);

        // El estado y el tiempo van en el centro de la fila, con ancho elástico.
        timeView = Ui.text(this, "--:--", 13f, Ui.TEXT, true);
        timeView.setTypeface(android.graphics.Typeface.MONOSPACE);
        timeView.setGravity(Gravity.CENTER);
        timeView.setMaxLines(1);
        timeView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        timeView.setContentDescription("Estado y tiempo de reproducción");
        LinearLayout.LayoutParams timeParams = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        timeParams.leftMargin = Ui.dpi(this, 12);
        timeParams.rightMargin = Ui.dpi(this, 8);
        transport.addView(timeView, timeParams);

        LinearLayout.LayoutParams transportParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        bottomBar.addView(transport, transportParams);

        // Segunda fila: volumen y brillo. Los dos deslizadores comparten el ancho
        // disponible, así que no se salen ni en vertical ni en pantallas pequeñas.
        LinearLayout sliders = new LinearLayout(this);
        sliders.setOrientation(LinearLayout.HORIZONTAL);
        sliders.setGravity(Gravity.CENTER_VERTICAL);

        TextView volumeLabel = Ui.text(this, "VOL", 11f, Ui.TEXT_DIM, true);
        volumeLabel.setPadding(0, 0, Ui.dpi(this, 6), 0);
        sliders.addView(volumeLabel);

        volumeBar = new SeekBar(this);
        volumeBar.setMax(100);
        volumeBar.setProgress(volumePercent());
        volumeBar.setContentDescription("Volumen");
        volumeBar.setMinimumHeight(Ui.dpi(this, 44));
        volumeBar.setProgressTintList(ColorStateList.valueOf(Ui.RED));
        volumeBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int value, boolean fromUser) {
                if (fromUser) {
                    setVolumePercent(value / 100f);
                    showGestureIndicator("VOLUMEN", value);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                ui.removeCallbacks(hideControls);
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                scheduleHide();
            }
        });
        sliders.addView(volumeBar, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView brightness = Ui.text(this, "BRILLO", 11f, Ui.TEXT_DIM, true);
        brightness.setPadding(Ui.dpi(this, 10), 0, Ui.dpi(this, 6), 0);
        brightness.setContentDescription("Brillo de la app: desliza arriba o abajo en la mitad derecha");
        sliders.addView(brightness);

        SeekBar brightnessBar = new SeekBar(this);
        brightnessBar.setMax(100);
        brightnessBar.setProgress(Math.round(brightnessValue() * 100f));
        brightnessBar.setContentDescription("Brillo de la aplicación");
        brightnessBar.setProgressTintList(ColorStateList.valueOf(Ui.BLUE));
        brightnessBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int value, boolean fromUser) {
                if (fromUser) {
                    setBrightness(Math.max(0.05f, value / 100f));
                    showGestureIndicator("BRILLO", value);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                ui.removeCallbacks(hideControls);
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                scheduleHide();
            }
        });
        brightnessBar.setMinimumHeight(Ui.dpi(this, 44));
        sliders.addView(brightnessBar, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout.LayoutParams sliderParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sliderParams.topMargin = Ui.dpi(this, 4);
        bottomBar.addView(sliders, sliderParams);

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        // Banda ordenada en dos filas: transporte y tiempo arriba; volumen y brillo
        // debajo, sin salirse del ancho de la pantalla.
        params.gravity = Gravity.BOTTOM;
        params.bottomMargin = Ui.dpi(this, 4);
        bottomBar.setLayoutParams(params);
        return bottomBar;
    }

    /**
     * Cartel con el nombre y los datos del canal, como en el reproductor de
     * referencia: aparece al abrir un canal o al cambiar de uno a otro y se retira
     * solo para no tapar la imagen.
     */
    // ------------------------------------------ barras copiadas de la captura

    /**
     * Botón cuadrado de las barras nuevas: dibuja su icono en el centro exacto del
     * cuadro, sin fondo visible (en la captura los botones van sobre el vídeo).
     */
    private View skinButton(Drawable icon, float sizeDp, String description) {
        PlayerIcons.IconButton button = new PlayerIcons.IconButton(this, icon, sizeDp, 0.56f);
        button.setContentDescription(description);
        button.setClickable(true);
        button.setFocusable(true);
        button.setFocusableInTouchMode(false);
        button.setBackground(Ui.buttonBg(this, 0x00000000, 0x33FFFFFF, 0x00000000, 6f));
        button.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) {
                    v.setBackground(Ui.buttonBg(PlayerActivity.this,
                            0x6600D4FF, 0x9900F0FF, 0xFF00F0FF, 10f));
                    v.setScaleX(1.12f);
                    v.setScaleY(1.12f);
                } else {
                    v.setBackground(Ui.buttonBg(PlayerActivity.this,
                            0x00000000, 0x33FFFFFF, 0x00000000, 6f));
                    v.setScaleX(1f);
                    v.setScaleY(1f);
                }
            }
        });
        return button;
    }

    /** Cambia el dibujo de un botón, sea del tipo nuevo o del tipo anterior. */
    private void setButtonIcon(View button, Drawable icon) {
        if (button == null || icon == null) return;
        if (button instanceof PlayerIcons.IconButton) {
            ((PlayerIcons.IconButton) button).setIcon(icon);
        } else if (button instanceof TextView) {
            icon.setBounds(0, 0, Ui.dpi(this, 23), Ui.dpi(this, 23));
            ((TextView) button).setCompoundDrawables(null, icon, null, null);
        }
    }

    private LinearLayout.LayoutParams skinParams(float leftMarginDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        if (leftMarginDp > 0f) params.leftMargin = Ui.dpi(this, leftMarginDp);
        return params;
    }

    /**
     * Barra superior del reproductor, copiada de la captura: flecha para volver,
     * nombre del canal y, a la derecha, pantalla completa, ventana flotante,
     * ajustes, volumen y candado (el candado de siempre, colocado aquí).
     */
    private View buildTopBarSkin() {
        topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(Ui.dpi(this, 6), Ui.dpi(this, 4), Ui.dpi(this, 6), Ui.dpi(this, 8));
        android.graphics.drawable.GradientDrawable shade = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xE6000000, 0x00000000});
        topBar.setBackground(shade);

        View backButton = skinButton(Icons.back(this, Ui.TEXT), 42f, "Volver a la lista de canales");
        backButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        topBar.addView(backButton);

        topTitle = Ui.text(this, "", 19f, 0xFFFFFFFF, true);
        topTitle.setSingleLine(true);
        topTitle.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        titleParams.leftMargin = Ui.dpi(this, 8);
        titleParams.rightMargin = Ui.dpi(this, 4);
        topBar.addView(topTitle, titleParams);

        // La captura solo muestra el nombre del canal: este texto no se ve, pero se
        // sigue creando porque los avisos internos lo actualizan.
        topSubtitle = Ui.text(this, "", 11f, Ui.TEXT_DIM, false);
        topSubtitle.setVisibility(View.GONE);

        fullscreenButton = skinButton(Icons.expand(this, Ui.TEXT), 42f, "Pantalla completa");
        fullscreenButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                applyImmersive(!immersive);
                scheduleHide();
            }
        });
        topBar.addView(fullscreenButton);

        pipButton = skinButton(PlayerIcons.pip(this, Ui.TEXT), 42f, "Ventana flotante");
        pipButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                enterPip();
            }
        });
        topBar.addView(pipButton);

        // Captura de referencia: aquí había un engranaje que solo abría volumen y
        // brillo. El usuario ya tiene esas dos funciones con los gestos y el botón
        // de altavoz, así que ese botón se ha quitado del reproductor.
        // (El panel de ajustes del reproductor sigue existiendo para el resto de
        // opciones, pero ya no se muestra desde esta barra.)

        View volumeButton = skinButton(PlayerIcons.volume(this, Ui.TEXT), 42f, "Volumen");
        volumeButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleVolumeRow();
            }
        });
        topBar.addView(volumeButton);

        // Hueco del candado: ese botón ya existe y se coloca flotando justo aquí.
        View lockSpace = new View(this);
        topBar.addView(lockSpace, new LinearLayout.LayoutParams(Ui.dpi(this, 46), 1));

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        params.gravity = Gravity.TOP;
        topBar.setLayoutParams(params);
        return topBar;
    }

    /**
     * Barra inferior del reproductor, copiada de la captura: tiempos con la barra
     * de progreso (punto rojo) y debajo los seis botones: imagen, anterior,
     * pausar en azul, siguiente, lista de canales y subtítulos (calidad).
     */
    private View buildBottomBarSkin() {
        bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.VERTICAL);
        bottomBar.setGravity(Gravity.CENTER_HORIZONTAL);
        bottomBar.setPadding(Ui.dpi(this, 14), Ui.dpi(this, 4), Ui.dpi(this, 14), Ui.dpi(this, 6));
        android.graphics.drawable.GradientDrawable shade = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xE6000000, 0x88000000, 0x00000000});
        bottomBar.setBackground(shade);

        // Fila de volumen: se abre y se cierra con el botón de altavoz de arriba.
        volumeRow = new LinearLayout(this);
        volumeRow.setOrientation(LinearLayout.HORIZONTAL);
        volumeRow.setGravity(Gravity.CENTER_VERTICAL);
        volumeRow.setVisibility(View.GONE);
        volumeBar = new SeekBar(this);
        volumeBar.setMax(100);
        volumeBar.setProgress(volumePercent());
        volumeBar.setContentDescription("Volumen");
        volumeBar.setMinimumHeight(Ui.dpi(this, 34));
        volumeBar.setProgressTintList(ColorStateList.valueOf(0xFF00D4FF));
        volumeBar.setThumbTintList(ColorStateList.valueOf(0xFF00E5FF));
        volumeBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int value, boolean fromUser) {
                if (fromUser) setVolumePercent(value / 100f);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                ui.removeCallbacks(hideControls);
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                scheduleHide();
            }
        });
        volumeRow.addView(volumeBar, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        bottomBar.addView(volumeRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout times = new LinearLayout(this);
        times.setOrientation(LinearLayout.HORIZONTAL);
        times.setGravity(Gravity.CENTER_VERTICAL);

        timeView = Ui.text(this, "00:00", 14f, 0xFFFFFFFF, true);
        timeView.setTypeface(android.graphics.Typeface.MONOSPACE);
        timeView.setSingleLine(true);
        timeView.setMinWidth(Ui.dpi(this, 58));
        timeView.setContentDescription("Tiempo transcurrido");
        times.addView(timeView);

        progressBar = new SeekBar(this);
        progressBar.setMax(1000);
        progressBar.setContentDescription("Barra de progreso del canal");
        progressBar.setMinimumHeight(Ui.dpi(this, 34));
        progressBar.setPadding(Ui.dpi(this, 6), 0, Ui.dpi(this, 6), 0);
        progressBar.setProgressTintList(ColorStateList.valueOf(0xFF00D4FF));
        progressBar.setThumbTintList(ColorStateList.valueOf(0xFF00E5FF));
        progressBar.setProgressBackgroundTintList(ColorStateList.valueOf(0x66FFFFFF));
        progressBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int value, boolean fromUser) {
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                ui.removeCallbacks(hideControls);
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                seekToProgress(seekBar.getProgress());
                scheduleHide();
            }
        });
        times.addView(progressBar, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        durationView = Ui.text(this, "00:00", 14f, 0xFFFFFFFF, true);
        durationView.setTypeface(android.graphics.Typeface.MONOSPACE);
        durationView.setSingleLine(true);
        durationView.setMinWidth(Ui.dpi(this, 58));
        durationView.setGravity(Gravity.END);
        durationView.setContentDescription("Duración del canal");
        times.addView(durationView);

        bottomBar.addView(times, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER);

        aspectButton = skinButton(Icons.aspect(this, Ui.TEXT), 46f, "Ajustar la imagen en pantalla");
        aspectButton.setOnClickListener(new HideThenRun(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openAspectPanel();
            }
        }));
        controls.addView(aspectButton, skinParams(0f));

        prevButton = skinButton(PlayerIcons.rewind(this, Ui.TEXT), 46f, "Canal anterior");
        prevButton.setOnClickListener(new HideThenRun(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stepChannel(-1);
            }
        }));
        controls.addView(prevButton, skinParams(6f));

        playButton = skinButton(PlayerIcons.play(this, 0xFFFFFFFF), 54f, "Reproducir o pausar");
        playButton.setBackground(Ui.buttonBg(this, 0xFF00A8E8, 0xFF00D4FF, 0x6600E5FF, 12f));
        playButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                togglePlay();
                scheduleHide();
            }
        });
        controls.addView(playButton, skinParams(10f));

        nextButton = skinButton(PlayerIcons.forward(this, Ui.TEXT), 46f, "Canal siguiente");
        nextButton.setOnClickListener(new HideThenRun(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stepChannel(1);
            }
        }));
        controls.addView(nextButton, skinParams(10f));

        drawerButton = skinButton(Icons.list(this, Ui.TEXT), 46f, "Lista de canales");
        drawerButton.setOnClickListener(new HideThenRun(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openDrawer();
            }
        }));
        controls.addView(drawerButton, skinParams(6f));

        View subtitlesButton = skinButton(PlayerIcons.subtitles(this, Ui.TEXT), 46f,
                "Elegir la calidad de vídeo");
        subtitlesButton.setOnClickListener(new HideThenRun(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openQualityPanel();
            }
        }));
        controls.addView(subtitlesButton, skinParams(6f));

        controls.setFocusable(false);
        controls.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        LinearLayout.LayoutParams controlParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        controlParams.topMargin = Ui.dpi(this, 2);
        bottomBar.addView(controls, controlParams);
        bottomBar.setFocusable(false);
        bottomBar.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        params.gravity = Gravity.BOTTOM;
        params.bottomMargin = 0;
        bottomBar.setLayoutParams(params);
        return bottomBar;
    }

    /** Muestra u oculta la fila del volumen (botón de altavoz de la barra). */
    private void toggleVolumeRow() {
        if (volumeRow == null) return;
        boolean showing = volumeRow.getVisibility() != View.VISIBLE;
        if (showing && volumeBar != null) volumeBar.setProgress(volumePercent());
        volumeRow.setVisibility(showing ? View.VISIBLE : View.GONE);
        scheduleHide();
    }

    /** Lleva la reproducción al punto tocado en la barra de progreso. */
    private void seekToProgress(int progress) {
        if (media3 == null || !prepared) return;
        try {
            long duration = media3.getDuration();
            if (duration <= 0) return;
            media3.seekTo(Math.round(duration * (progress / 1000f)));
        } catch (Exception ignored) {
        }
    }

    /** Ventana flotante (imagen en imagen) del botón de la barra superior. */
    private void enterPip() {
        try {
            enterPictureInPictureMode(new android.app.PictureInPictureParams.Builder().build());
        } catch (Throwable t) {
            toast("Este teléfono no permite la ventana flotante");
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean inPip,
                                              android.content.res.Configuration config) {
        super.onPictureInPictureModeChanged(inPip, config);
        try {
            if (inPip) {
                setControlsVisible(false);
            } else {
                setControlsVisible(true);
            }
        } catch (Throwable ignored) {
        }
    }

    // REVISIÓN 52: aquí estaban el cartel del canal (buildChannelBanner) y la
    // función que lo mostraba al abrir o cambiar de canal (showChannelBanner).
    // El usuario ha pedido quitarlo: tapaba la imagen justo al cambiar de canal.
    // El nombre del canal sigue viéndose en la barra superior del reproductor.

    private View buildGesturePanel() {
        gesturePanel = new LinearLayout(this);
        gesturePanel.setOrientation(LinearLayout.VERTICAL);
        gesturePanel.setBackground(Ui.rounded(this, 0xD9000000, 10f));
        gesturePanel.setPadding(Ui.dpi(this, 14), Ui.dpi(this, 12), Ui.dpi(this, 14), Ui.dpi(this, 12));
        gesturePanel.setVisibility(View.GONE);
        gestureLabel = Ui.text(this, "VOLUMEN  50%", 13f, Ui.TEXT, true);
        gesturePanel.addView(gestureLabel);
        gestureBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        gestureBar.setMax(100);
        gestureBar.setProgress(50);
        gestureBar.setProgressTintList(ColorStateList.valueOf(Ui.RED));
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(
                Ui.dpi(this, 200), Ui.dpi(this, 10));
        barParams.topMargin = Ui.dpi(this, 8);
        gesturePanel.addView(gestureBar, barParams);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        // El aviso de volumen/brillo se muestra arriba para no tapar los controles.
        params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        params.topMargin = Ui.dpi(this, 96);
        gesturePanel.setLayoutParams(params);
        return gesturePanel;
    }

    private FrameLayout buildDrawer() {
        final FrameLayout holder = new FrameLayout(this);
        // REVISIÓN 52: velo aún más suave, para que el canal se siga viendo mientras
        // se elige otro (el usuario pidió un fondo más transparente).
        holder.setBackgroundColor(0x1A000000);
        holder.setVisibility(View.GONE);
        holder.setClickable(true);
        holder.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeDrawer();
            }
        });

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        // REVISIÓN 51: fondo oscuro pero translúcido (deja ver el vídeo detrás),
        // como en la captura 3, en vez del bloque opaco anterior.
        android.graphics.drawable.GradientDrawable shell = new android.graphics.drawable.GradientDrawable();
        // REVISIÓN 52: el usuario pidió el fondo del panel MÁS transparente todavía:
        // antes 0xE0 (88 % opaco), ahora 0x99 (60 %), así se intuye el vídeo detrás.
        shell.setColor(0x990A0A12);
        float corner = Ui.dp(this, 10f);
        shell.setCornerRadii(new float[]{0f, 0f, corner, corner, corner, corner, 0f, 0f});
        panel.setBackground(shell);
        panel.setPadding(Ui.dpi(this, 12), Ui.dpi(this, 12), Ui.dpi(this, 12), Ui.dpi(this, 12));
        panel.setClickable(true);
        panel.setFocusable(false);
        panel.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = Ui.text(this, "CANALES", 14f, Ui.RED, true);
        title.setLetterSpacing(0.12f);
        header.addView(title, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        drawerCount = Ui.text(this, "", 10f, Ui.TEXT_MUTED, false);
        header.addView(drawerCount);
        TextView close = Ui.iconButton(this, Icons.close(this, Ui.TEXT), "Cerrar la lista de canales", 38f);
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(
                Ui.dpi(this, 38), Ui.dpi(this, 38));
        closeParams.leftMargin = Ui.dpi(this, 8);
        close.setLayoutParams(closeParams);
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeDrawer();
            }
        });
        header.addView(close);
        panel.addView(header);

        // REVISIÓN 51: apartados elegibles DENTRO del propio panel de canales.
        TextView sectionsLabel = Ui.text(this, "APARTADOS", 9.5f, Ui.TEXT_MUTED, true);
        sectionsLabel.setLetterSpacing(0.12f);
        sectionsLabel.setPadding(0, Ui.dpi(this, 10), 0, Ui.dpi(this, 4));
        panel.addView(sectionsLabel);

        android.widget.HorizontalScrollView sectionScroller =
                new android.widget.HorizontalScrollView(this);
        sectionScroller.setHorizontalScrollBarEnabled(false);
        drawerSectionRow = new LinearLayout(this);
        drawerSectionRow.setOrientation(LinearLayout.HORIZONTAL);
        sectionScroller.addView(drawerSectionRow);
        panel.addView(sectionScroller, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        drawerAdapter = new DrawerAdapter();
        drawerList = new ListView(this);
        drawerList.setAdapter(drawerAdapter);
        drawerList.setDivider(null);
        drawerList.setDividerHeight(Ui.dpi(this, 4));
        drawerList.setFocusable(true);
        drawerList.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        try { drawerList.setItemsCanFocus(true); } catch (Throwable ignored) {}
        drawerList.setSmoothScrollbarEnabled(true);
        drawerList.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode != KeyEvent.KEYCODE_DPAD_DOWN && keyCode != KeyEvent.KEYCODE_DPAD_UP) {
                    return false;
                }
                int pos = drawerList.getSelectedItemPosition();
                if (pos < 0) pos = drawerList.getFirstVisiblePosition();
                int count = drawerAdapter == null ? 0 : drawerAdapter.getCount();
                if (count <= 0) return false;
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && pos < count - 1) {
                    int next = pos + 1;
                    drawerList.setSelection(next);
                    drawerList.smoothScrollToPosition(next);
                    focusDrawerRow(next);
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP && pos > 0) {
                    int prev = pos - 1;
                    drawerList.setSelection(prev);
                    drawerList.smoothScrollToPosition(prev);
                    focusDrawerRow(prev);
                    return true;
                }
                return false;
            }
        });
        drawerList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                int target = channelIndexAt(position);
                if (target < 0) return;
                closeDrawer();
                playIndex(target);
            }
        });
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        listParams.topMargin = Ui.dpi(this, 8);
        panel.addView(drawerList, listParams);

        TextView note = Ui.text(this, "El canal que estás viendo aparece marcado.", 10f, Ui.TEXT_MUTED, false);
        note.setPadding(0, Ui.dpi(this, 6), 0, 0);
        panel.addView(note);

        // El panel de canales también se ajusta al ancho real de la pantalla.
        int drawerWidth = Math.min(Ui.dpi(this, 340),
                getResources().getDisplayMetrics().widthPixels - Ui.dpi(this, 24));
        FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(
                Math.max(Ui.dpi(this, 220), drawerWidth), FrameLayout.LayoutParams.MATCH_PARENT, Gravity.START);
        holder.addView(panel, panelParams);
        return holder;
    }

    private TextView barPlate(String label, String description, View.OnClickListener listener) {
        TextView b = Ui.text(this, label, 13f, Ui.TEXT, true);
        b.setAllCaps(true);
        b.setLetterSpacing(0.06f);
        b.setGravity(Gravity.CENTER);
        b.setContentDescription(description);
        b.setMinWidth(Ui.dpi(this, 52));
        b.setMinHeight(Ui.dpi(this, 44));
        b.setPadding(Ui.dpi(this, 12), Ui.dpi(this, 10), Ui.dpi(this, 12), Ui.dpi(this, 10));
        b.setBackground(Ui.buttonBg(this, 0x88000000, 0xCCE13A2B, 0x66F4F4F8, 6f));
        b.setClickable(true);
        b.setFocusable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = Ui.dpi(this, 6);
        b.setLayoutParams(lp);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                scheduleHide();
            }
        });
        // El listener real se añade después (setOnClickListener lo sustituye).
        b.setOnClickListener(new HideThenRun(listener));
        return b;
    }

    private final class HideThenRun implements View.OnClickListener {
        private final View.OnClickListener real;

        HideThenRun(View.OnClickListener real) {
            this.real = real;
        }

        @Override
        public void onClick(View v) {
            scheduleHide();
            if (real != null) real.onClick(v);
        }
    }

    // --------------------------------------------------------------- mando / TV box

    /**
     * En móvil los controles salen al tocar. En TV box el mando envía teclas.
     * Primera pulsación: solo MUESTRA la barra (no pausa).
     * OK/Enter: lo recibe el botón con foco (play, siguiente, lista…).
     * Teclas multimedia: play/pausa o cambio de canal.
     * Atrás: oculta barra o sale del reproductor.
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event == null) return super.dispatchKeyEvent(event);
        int action = event.getAction();
        int key = event.getKeyCode();

        if (isPanelOpen()) {
            return super.dispatchKeyEvent(event);
        }
        if (locked) {
            if (action == KeyEvent.ACTION_UP
                    && (key == KeyEvent.KEYCODE_BACK || key == KeyEvent.KEYCODE_ESCAPE)) {
                toggleLock();
                return true;
            }
            return super.dispatchKeyEvent(event);
        }

        // Controles ocultos: cualquier tecla de navegación solo los muestra.
        // No se pausa ni se cambia de canal en esta primera pulsación.
        if (!controlsVisible && action == KeyEvent.ACTION_DOWN && isRemoteNavKey(key)) {
            setControlsVisible(true);
            focusPlayerControls();
            scheduleHide();
            // Consumir OK/Enter/espacio para que NO activen play en el mismo toque
            if (key == KeyEvent.KEYCODE_DPAD_CENTER
                    || key == KeyEvent.KEYCODE_ENTER
                    || key == KeyEvent.KEYCODE_NUMPAD_ENTER
                    || key == KeyEvent.KEYCODE_SPACE) {
                return true;
            }
            // Flechas: dejar pasar para mover el foco tras mostrar la barra
            if (key == KeyEvent.KEYCODE_DPAD_UP
                    || key == KeyEvent.KEYCODE_DPAD_DOWN
                    || key == KeyEvent.KEYCODE_DPAD_LEFT
                    || key == KeyEvent.KEYCODE_DPAD_RIGHT) {
                return super.dispatchKeyEvent(event);
            }
        }

        if (action == KeyEvent.ACTION_UP) {
            switch (key) {
                case KeyEvent.KEYCODE_BACK:
                case KeyEvent.KEYCODE_ESCAPE:
                    if (controlsVisible) {
                        setControlsVisible(false);
                        return true;
                    }
                    finish();
                    return true;
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                    setControlsVisible(true);
                    togglePlay();
                    scheduleHide();
                    return true;
                case KeyEvent.KEYCODE_MEDIA_PLAY:
                    setControlsVisible(true);
                    if (media3 != null && !media3.isPlaying()) {
                        togglePlay();
                    }
                    scheduleHide();
                    return true;
                case KeyEvent.KEYCODE_MEDIA_PAUSE:
                    setControlsVisible(true);
                    if (media3 != null && media3.isPlaying()) {
                        togglePlay();
                    }
                    scheduleHide();
                    return true;
                case KeyEvent.KEYCODE_MEDIA_NEXT:
                case KeyEvent.KEYCODE_CHANNEL_UP:
                    setControlsVisible(true);
                    stepChannel(1);
                    scheduleHide();
                    return true;
                case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                case KeyEvent.KEYCODE_CHANNEL_DOWN:
                    setControlsVisible(true);
                    stepChannel(-1);
                    scheduleHide();
                    return true;
                default:
                    break;
            }
        }

        // Controles visibles: OK/Enter/DPAD van al sistema (foco en botones).
        // No forzar togglePlay aquí.
        if (controlsVisible && action == KeyEvent.ACTION_DOWN) {
            scheduleHide();
        }
        return super.dispatchKeyEvent(event);
    }

    private static boolean isRemoteNavKey(int key) {
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_SPACE:
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_INFO:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case KeyEvent.KEYCODE_CHANNEL_UP:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                return true;
            default:
                return false;
        }
    }

    private void focusPlayerControls() {
        try {
            // Enfocar un botón hijo (no el contenedor): así el DPAD puede moverse
            // entre anterior / play / siguiente / lista / calidad.
            if (bottomBar != null) {
                bottomBar.setFocusable(false);
                bottomBar.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
            }
            if (topBar != null) {
                topBar.setFocusable(false);
                topBar.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
            }
            View target = prevButton != null ? prevButton
                    : (playButton != null ? playButton : nextButton);
            if (target != null) {
                target.setFocusable(true);
                target.setFocusableInTouchMode(false);
                target.requestFocus();
            }
        } catch (Throwable ignored) {
        }
    }

    // --------------------------------------------------------------- controles

    private void setControlsVisible(boolean visible) {
        controlsVisible = visible;
        if (topBar == null) return;
        if (locked) {
            topBar.setVisibility(View.GONE);
            bottomBar.setVisibility(View.GONE);
            lockButton.setVisibility(View.VISIBLE);
            ui.removeCallbacks(hideControls);
            return;
        }
        topBar.setVisibility(visible ? View.VISIBLE : View.GONE);
        bottomBar.setVisibility(visible ? View.VISIBLE : View.GONE);
        lockButton.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (visible) {
            scheduleHide();
            focusPlayerControls();
        } else {
            ui.removeCallbacks(hideControls);
        }
    }

    private void toggleControls() {
        if (locked) return;
        setControlsVisible(!controlsVisible);
    }

    private void scheduleHide() {
        ui.removeCallbacks(hideControls);
        int seconds = AppPrefs.hideDelaySeconds(this);
        if (seconds <= 0 || locked || isPanelOpen()) return;
        ui.postDelayed(hideControls, seconds * 1000L);
    }

    private boolean isPanelOpen() {
        boolean drawer = drawerHolder != null && drawerHolder.getVisibility() == View.VISIBLE;
        boolean message = messageHolder != null && messageHolder.getVisibility() == View.VISIBLE;
        boolean settings = settingsHolder != null && settingsHolder.getVisibility() == View.VISIBLE;
        boolean choice = choiceHolder != null && choiceHolder.getVisibility() == View.VISIBLE;
        return drawer || message || settings || choice;
    }

    private void toggleLock() {
        locked = !locked;
        if (locked) {
            closeDrawer();
            closeChoice();
            closeSettings();
            hideMessage();
        }
        setButtonIcon(lockButton, Icons.lock(this, Ui.TEXT, locked));
        lockButton.setContentDescription(locked ? "Desbloquear la pantalla" : "Bloquear la pantalla");
        setControlsVisible(!locked);
        if (locked) {
            topBar.setVisibility(View.GONE);
            bottomBar.setVisibility(View.GONE);
            lockButton.setVisibility(View.VISIBLE);
            lockButton.setAlpha(0.85f);
        } else {
            toast("Pantalla desbloqueada");
        }
    }

    private void applyImmersive(boolean enable) {
        immersive = enable;
        if (enable) {
            Immersion.apply(this);
        } else {
            Immersion.showBars(this);
        }
        if (fullscreenButton != null) {
            setButtonIcon(fullscreenButton,
                    enable ? Icons.contract(this, Ui.TEXT) : Icons.expand(this, Ui.TEXT));
            fullscreenButton.setContentDescription(enable ? "Mostrar las barras del sistema" : "Pantalla completa");
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && immersive) applyImmersive(true);
    }

    /**
     * Reparte los márgenes de la zona de la cámara frontal solo entre los
     * controles: el vídeo y el fondo siguen ocupando toda la pantalla.
     */
    private void applyControlInsets(int left, int top, int right, int bottom) {
        if (left == insetLeft && top == insetTop && right == insetRight && bottom == insetBottom) return;
        insetLeft = left;
        insetTop = top;
        insetRight = right;
        insetBottom = bottom;
        if (topBar != null) {
            topBar.setPadding(Ui.dpi(this, 10) + left, Ui.dpi(this, 8) + top,
                    Ui.dpi(this, 10) + right, Ui.dpi(this, 10));
        }
        if (bottomBar != null) {
            bottomBar.setPadding(Ui.dpi(this, 10) + left, Ui.dpi(this, 10),
                    Ui.dpi(this, 10) + right, Ui.dpi(this, 8) + bottom);
        }
        if (lockButton != null) {
            ViewGroup.LayoutParams raw = lockButton.getLayoutParams();
            if (raw instanceof FrameLayout.LayoutParams) {
                FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) raw;
                params.rightMargin = Ui.dpi(this, 6) + right;
                params.bottomMargin = Ui.dpi(this, 14) + bottom;
                lockButton.setLayoutParams(params);
            }
        }
        applyAspect();
    }

    private void applyKeepScreenOn() {
        if (AppPrefs.keepScreenOn(this)) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    // ---------------------------------------------------------------- volumen y brillo

    private void setVolumePercent(float percent) {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (am == null) return;
        float clamped = clamp(percent, 0f, 1f);
        int value = Math.round(clamped * audioMax);
        try {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0);
        } catch (Exception ignored) {
        }
        if (volumeBar != null) volumeBar.setProgress(Math.round(clamped * 100f));
    }

    private int volumePercent() {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (am == null) return 50;
        int max = Math.max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        return Math.round(100f * am.getStreamVolume(AudioManager.STREAM_MUSIC) / max);
    }

    private float brightnessValue() {
        WindowManager.LayoutParams params = getWindow().getAttributes();
        return params.screenBrightness < 0f ? 0.5f : params.screenBrightness;
    }

    private void setBrightness(float value) {
        WindowManager.LayoutParams params = getWindow().getAttributes();
        params.screenBrightness = clamp(value, 0.05f, 1f);
        getWindow().setAttributes(params);
    }

    private void showGestureIndicator(String label, int percent) {
        if (gesturePanel == null) return;
        gestureLabel.setText(label + "  " + percent + "%");
        gestureBar.setProgress(percent);
        gesturePanel.setVisibility(View.VISIBLE);
        ui.removeCallbacks(hideGesture);
        ui.postDelayed(hideGesture, 900);
    }

    private static float clamp(float value, float min, float max) {
        return value < min ? min : (value > max ? max : value);
    }

    private final class GestureListener implements View.OnTouchListener {
        private boolean active;
        private boolean volumeSide;
        private boolean moved;
        private float startY;
        private long downTime;
        private float startVolumePercent;
        private float startBrightness;

        @Override
        public boolean onTouch(View view, MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                downTime = System.currentTimeMillis();
                startY = event.getY();
                moved = false;
                float edge = Ui.dpi(PlayerActivity.this, 36);
                active = !locked && event.getX() > edge && event.getX() < view.getWidth() - edge;
                volumeSide = event.getX() < view.getWidth() / 2f;
                startVolumePercent = volumePercent() / 100f;
                startBrightness = brightnessValue();
                return true;
            }
            if (action == MotionEvent.ACTION_MOVE) {
                if (!active) return true;
                float delta = startY - event.getY();
                float threshold = Ui.dpi(PlayerActivity.this, 12);
                if (!moved && Math.abs(delta) < threshold) return true;
                moved = true;
                float range = Math.max(1f, view.getHeight() * 0.7f);
                if (volumeSide) {
                    float percent = clamp(startVolumePercent + delta / range, 0f, 1f);
                    setVolumePercent(percent);
                    showGestureIndicator("VOLUMEN", Math.round(percent * 100f));
                } else {
                    float percent = clamp(startBrightness + delta / range, 0.05f, 1f);
                    setBrightness(percent);
                    showGestureIndicator("BRILLO", Math.round(percent * 100f));
                    if (gestureBar != null) gestureBar.setProgressTintList(ColorStateList.valueOf(Ui.BLUE));
                }
                if (volumeSide && gestureBar != null) {
                    gestureBar.setProgressTintList(ColorStateList.valueOf(Ui.RED));
                }
                return true;
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                if (!moved && !locked && System.currentTimeMillis() - downTime < 350) {
                    toggleControls();
                }
                ui.removeCallbacks(hideGesture);
                ui.postDelayed(hideGesture, 500);
                return true;
            }
            return true;
        }
    }

    // -------------------------------------------------------------- reproducción

    private String resolveType(String url) {
        return FormatDetector.fromUrl(url);
    }

    /**
     * Enlace que se reproduce del canal. Normalmente es el del canal; si la persona
     * eligió una calidad concreta de una lista con varias (HLS), es el de esa
     * calidad.
     */
    private String playbackUrl(Channel channel) {
        if (variantUrl != null && variantUrl.length() > 0) return variantUrl;
        return channel == null ? "" : channel.url;
    }

    /** Descripción del intento que está en curso, para el registro. */
    private String attemptLabel() {
        return useProxy ? "intento 2 (puente local con cabeceras)" : "intento 1 (conexión directa)";
    }

    /** De dónde salió el tipo de contenido, para el informe del motor. */
    private String routeOrigin() {
        if (manualType.length() > 0) return " (elegido a mano en AJUSTES DEL REPRODUCTOR)";
        if (typeOrigin.length() > 0) return typeOrigin;
        return " (por la extensión del enlace)";
    }

    /**
     * Datos internos que el reproductor de Android guarda sobre el intento. En
     * algunos teléfonos incluyen el motivo real del fallo, que los dos números de
     * onError no dan (por eso aparece el famoso -2147483648 sin explicación).
     */
    private String engineMetrics() {
        if (media3 == null) return "Media3 · inactivo";
        return media3.statusLine();
    }

    /**
     * Pregunta al servidor qué responde ahora mismo por el enlace (código HTTP
     * real y tipo de contenido, sin descargarlo) y lo añade al aviso de la
     * pantalla. Así un fallo deja de ser un número que no explica nada: se ve si
     * es 403 (faltan cabeceras User-Agent/Referer), 404 (el enlace caducó), una
     * página web en lugar del canal, etc.
     */
    private void askServerForReason(final Channel channel) {
        final Map<String, String> headers = channelHeaders(channel);
        try {
            background.execute(new ServerQuestion(channel, headers));
        } catch (Throwable t) {
            // El hilo de trabajo ya se cerró (por ejemplo al salir de la pantalla):
            // la comprobación es solo informativa, nunca debe cerrar la app.
            Diag.add("Reproductor · no se pudo preguntar al servidor: " + Net.describe(t));
        }
    }

    /** Comprobación del enlace en segundo plano, para no bloquear la imagen. */
    private final class ServerQuestion implements Runnable {
        private final Channel channel;
        private final Map<String, String> headers;

        ServerQuestion(Channel channel, Map<String, String> headers) {
            this.channel = channel;
            this.headers = headers;
        }

        @Override
        public void run() {
                String reply;
                try {
                    reply = Net.quickStatus(channel.url, headers);
                } catch (Throwable t) {
                    reply = "no se pudo preguntar al servidor: " + Net.describe(t);
                }
                final String answer = reply;
                EngineInfo.server(answer);
                Diag.add("Reproductor · el servidor responde ahora: " + answer);
                Diag.save(PlayerActivity.this);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing() || isDestroyed()) return;
                        appendMessage("\n\nLo que responde el servidor ahora mismo: " + answer);
                    }
                });
        }
    }

    /** Añade texto al aviso que ya está en pantalla, sin borrar lo anterior. */
    private void appendMessage(String extra) {
        if (extra == null || extra.length() == 0) return;
        if (messageBody == null || messageHolder == null) return;
        if (messageHolder.getVisibility() != View.VISIBLE) return;
        String current = messageBody.getText() == null ? "" : messageBody.getText().toString();
        if (current.indexOf(extra.trim()) >= 0) return;
        messageBody.setText(current + extra);
        messageText = messageText + extra;
        ui.removeCallbacks(fitMessage);
        ui.post(fitMessage);
    }

    private void startPlayback() {
        attemptToken++;
        ui.removeCallbacks(prepareWatchdog);
        releasePlayer();
        hideMessage();
        finished = false;
        prepared = false;
        final Channel channel = currentChannel();
        if (manualType.length() > 0) currentType = manualType;
        else if (typeOrigin.length() == 0) currentType = resolveType(playbackUrl(channel));
        updateTopBar();

        Map<String, String> headersForReport = channelHeaders(channel);
        String headerReport = Headers.describe(headersForReport);
        EngineInfo.headers((channel.hasOwnHeaders() ? "del bloque del canal: " : "generales de la app: ")
                + headerReport);
        if (channel.hasOwnHeaders()) {
            Diag.add("Reproductor · bloque del canal: " + channel.blockSummary());
        }
        if (channel.hasLicense()) {
            Diag.add("Reproductor · " + channel.licenseSummary());
            EngineInfo.license(channel.licenseSummary());
        }

        EngineInfo.attempt("Media3 / ExoPlayer (motor de LENY TV)",
                currentType, Diag.channelUrl(channel.url));
        EngineInfo.route("RUTA · canal elegido en la cuadrícula → pantalla del reproductor →"
                + " tipo de contenido detectado " + currentType + routeOrigin()
                + " → Media3 / ExoPlayer"
                + (useProxy ? " · a través del puente local" : ""));

        Map<String, String> headers = channelHeaders(channel);
        Diag.add("Reproductor · Media3 · canal " + channel.name + " · " + Diag.channelUrl(channel.url));
        Diag.add("Reproductor · cabeceras: " + Net.describeHeaders(headers));

        String source = playbackUrl(channel);
        if (useProxy && LocalProxy.isHttp(source)) {
            try {
                String bridged = LocalProxy.get().proxyUrl(source, headers);
                if (bridged != null && bridged.length() > 0) {
                    source = bridged;
                    Diag.add("Reproductor · usando puente local");
                }
            } catch (Throwable t) {
                Diag.add("Reproductor · puente local falló: " + t.getMessage());
            }
        }

        if (media3 == null) {
            media3 = new Media3Engine(this, ui, media3Listener);
        }
        media3.setSurface(surface);
        setBuffering(true);
        playing = true;
        updatePlayButton();
        ui.removeCallbacks(timeTick);
        ui.postDelayed(timeTick, 1000);

        watchdogAttempt = attemptToken;
        ui.postDelayed(prepareWatchdog, PREPARE_TIMEOUT_MS);

        media3.start(source, headers, currentType,
                channel.licenseType, channel.licenseKey, channel.licenseStreamHeaders);
        preferredDashQuality = -1;
    }

    /**
     * Cabeceras que se envían al abrir un canal: las generales de la app
     * (ajustes) y, si el canal trae las suyas en el bloque M3U, estas tienen
     * prioridad sobre las generales.
     */
    private Map<String, String> channelHeaders(Channel channel) {
        Map<String, String> general = AppPrefs.headers(this);
        Map<String, String> own = channel == null ? null : channel.headers;
        return Headers.merge(general, own);
    }

    private void onPrepareTimeout() {
        if (prepared || media3 == null) return;
        if (watchdogAttempt != attemptToken) return;
        final Channel channel = currentChannel();
        Diag.add("Reproductor · SIN IMAGEN tras " + (PREPARE_TIMEOUT_MS / 1000L) + " s · " + attemptLabel()
                + " · canal " + channel.name + " · " + Diag.channelUrl(channel.url)
                + " (el servidor aceptó la conexión pero no envió datos)");
        Diag.save(this);
        setBuffering(false);
        if (retryWithProxy(channel, "no llegó imagen en " + (PREPARE_TIMEOUT_MS / 1000L) + " s")) return;
        EngineInfo.rawError("Media3 · sin imagen en "
                + (PREPARE_TIMEOUT_MS / 1000L) + " s (no hubo código de error)");
        showMessage("El canal no responde",
                "Han pasado " + (PREPARE_TIMEOUT_MS / 1000L) + " segundos y el servidor no ha empezado a"
                        + " enviar imagen.\n\n" + playbackHints(), true);
        askServerForReason(channel);
    }

    /**
     * Segundo intento usando el puente local: repite la petición con las cabeceras
     * puestas y sigue las redirecciones, también en cada trozo de un canal HLS.
     * Devuelve true si se ha vuelto a lanzar la reproducción.
     */
    private boolean retryWithProxy(Channel channel, String reason) {
        if (proxyAttempted || !LocalProxy.isHttp(playbackUrl(channel))) {
            Diag.add("Reproductor · no se reintenta con el puente local (" + reason + "): "
                    + (proxyAttempted ? "ya se intentó antes" : "el enlace no es HTTP ni HTTPS")
                    + " · " + attemptLabel());
            Diag.save(this);
            return false;
        }
        proxyAttempted = true;
        useProxy = true;
        Diag.add("Reproductor · segundo intento con el puente local (" + reason + ") · "
                + Diag.channelUrl(channel.url));
        toast("Reintentando con el modo compatible…");
        startPlayback();
        return true;
    }

    // ------------------------------------------------------------------ Media3 listener

    private final Media3Engine.Listener media3Listener = new Media3Engine.Listener() {
        @Override
        public void onStatus(String message) {
            Diag.add(message);
        }

        @Override
        public void onBuffering(final boolean active) {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    setBuffering(active);
                }
            });
        }

        @Override
        public void onVideoSize(final int width, final int height) {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    videoWidth = width;
                    videoHeight = height;
                    applyAspect();
                    updateTopBar();
                    playing = true;
                    prepared = true;
                    updatePlayButton();
                }
            });
        }

        @Override
        public void onError(final String title, final String body, final boolean retryable) {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    setBuffering(false);
                    playing = false;
                    updatePlayButton();
                    EngineInfo.rawError("Media3 · " + title + " · " + body);
                    final Channel channel = currentChannel();
                    if (retryable && retryWithProxy(channel, title)) return;
                    showMessage(title, body + "\n\n" + playbackHints(), true);
                }
            });
        }

        @Override
        public void onPrepared() {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    prepared = true;
                    setBuffering(false);
                    ui.removeCallbacks(prepareWatchdog);
                    playing = media3 != null && media3.isPlaying();
                    updatePlayButton();
                    updateTopBar();
                }
            });
        }

        @Override
        public void onFinished() {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    finished = true;
                    playing = false;
                    updatePlayButton();
                }
            });
        }

        @Override
        public void onPlayingChanged(final boolean isPlaying) {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    playing = isPlaying;
                    updatePlayButton();
                }
            });
        }
    };

    private void releasePlayer() {
        ui.removeCallbacks(timeTick);
        ui.removeCallbacks(prepareWatchdog);
        watchdogAttempt = -1;
        prepared = false;
        playing = false;
        Media3Engine eng = media3;
        media3 = null;
        if (eng != null) {
            try {
                eng.release();
            } catch (Throwable ignored) {
            }
        }
    }


    private void restartPlayback() {
        startPlayback();
    }

    private void togglePlay() {
        if (media3 == null) {
            startPlayback();
            return;
        }
        try {
            if (playing) {
                media3.setPaused(true);
                playing = false;
                setControlsVisible(true);
            } else {
                if (finished) {
                    startPlayback();
                    return;
                }
                media3.setPaused(false);
                playing = true;
                scheduleHide();
                ui.removeCallbacks(timeTick);
                ui.postDelayed(timeTick, 1000);
            }
        } catch (Exception e) {
            showMessage("No se pudo cambiar el estado de reproducción", describe(e), true);
        }
        updatePlayButton();
    }

    private void updatePlayButton() {
        if (playButton == null) return;
        setButtonIcon(playButton, playing ? PlayerIcons.pause(this, 0xFFFFFFFF)
                : PlayerIcons.play(this, 0xFFFFFFFF));
        playButton.setContentDescription(playing ? "Pausar" : "Reproducir");
    }

    private void playIndex(int target) {
        if (channels.isEmpty()) return;
        int size = channels.size();
        if (size == 1) {
            toast("No hay más entradas en esta lista");
            return;
        }
        index = ((target % size) + size) % size;
        manualType = "";
        typeOrigin = "";
        proxyAttempted = false;
        useProxy = false;
        // La calidad elegida era de OTRO canal: se descarta al cambiar de canal.
        variantUrl = "";
        variantLabel = "";
        preferredDashQuality = -1;
        startPlayback();
        if (drawerAdapter != null) drawerAdapter.notifyDataSetChanged();
        toast(Names.display(currentChannel().name));
    }

    /**
     * Canal anterior / siguiente dentro del MISMO apartado. Antes la lista que se
     * movía era la del apartado ya filtrada; ahora el reproductor lleva la lista
     * completa (para poder cambiar de apartado en el panel), así que el salto se
     * calcula aquí para que siga pasando canales del apartado en el que estás.
     */
    private void stepChannel(int direction) {
        if (channels.isEmpty() || direction == 0) return;
        int size = channels.size();
        if (size == 1) {
            toast("No hay más entradas en esta lista");
            return;
        }
        String category = currentChannel().category == null ? "" : currentChannel().category.trim();
        for (int step = 1; step <= size; step++) {
            int candidate = ((index + direction * step) % size + size) % size;
            String other = channels.get(candidate).category == null
                    ? "" : channels.get(candidate).category.trim();
            if (other.equals(category)) {
                playIndex(candidate);
                return;
            }
        }
        playIndex(index + direction);
    }

    private void updateTopBar() {
        if (topTitle == null) return;
        Channel channel = currentChannel();
        topTitle.setText(Names.forDisplay(topTitle.getPaint(), channel.name));
        StringBuilder subtitle = new StringBuilder();
        subtitle.append(channel.category);
        subtitle.append(" · ");
        subtitle.append(currentType);
        String engine = media3 == null ? "" : media3.statusLine();
        if (engine.length() > 0) subtitle.append(" · ").append(engine);
        else if (videoWidth > 0 && videoHeight > 0) {
            subtitle.append(" · ").append(videoWidth).append("×").append(videoHeight);
        }
        if (channels.size() > 1) {
            subtitle.append(" · ").append(index + 1).append("/").append(channels.size());
        }
        topSubtitle.setText(subtitle.toString());
        if (drawerCount != null) refreshDrawerFilter();
    }

    private void updateTime() {
        if (timeView == null) return;
        if (media3 == null || !prepared) return;
        try {
            long duration = media3.getDuration();
            long position = media3.getCurrentPosition();
            if (position < 0) position = 0;
            timeView.setText(formatTime((int) position));
            if (durationView != null) {
                durationView.setText(duration > 0 ? formatTime((int) duration) : "00:00");
            }
            if (progressBar != null) {
                progressBar.setEnabled(duration > 0);
                if (duration > 0) {
                    progressBar.setProgress(Math.round(1000f * position / duration));
                }
            }
        } catch (Exception ignored) {
        }
    }

    private String formatTime(int millis) {
        int totalSeconds = millis / 1000;
        int hours = totalSeconds / 3600;
        int minutes = (totalSeconds % 3600) / 60;
        int seconds = totalSeconds % 60;
        // Formato de la captura: dos cifras siempre (00:06 y 00:00).
        if (hours > 0) return String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds);
        return String.format(Locale.US, "%02d:%02d", minutes, seconds);
    }

    private void setBuffering(boolean value) {
        if (buffering != null) buffering.setVisibility(value ? View.VISIBLE : View.GONE);
    }

    private void showMessage(String title, String body, boolean showRetry) {
        if (messageHolder == null) return;
        messageText = title + "\n" + body;
        messageTitle.setText(title);
        messageBody.setText(body);
        messageRetry.setVisibility(showRetry ? View.VISIBLE : View.GONE);
        messageHolder.setVisibility(View.VISIBLE);
        ui.removeCallbacks(hideControls);
        setControlsVisible(true);
        ui.removeCallbacks(fitMessage);
        ui.post(fitMessage);
        ui.postDelayed(fitMessage, 80);
    }

    private final Runnable fitMessage = new Runnable() {
        @Override
        public void run() {
            fitMessageBody();
        }
    };

    /**
     * Ajusta el alto del texto del aviso: crece con el contenido hasta media
     * pantalla y, si el texto es más largo, se puede desplazar para leerlo todo.
     */
    private void fitMessageBody() {
        if (messageScroll == null || messageBody == null) return;
        int maxHeight = Math.max(Ui.dpi(this, 140),
                getResources().getDisplayMetrics().heightPixels / 2);
        int available = messageScroll.getWidth();
        if (available <= 0) {
            available = Math.max(Ui.dpi(this, 200), Math.min(Ui.dpi(this, 560),
                    getResources().getDisplayMetrics().widthPixels - Ui.dpi(this, 96)));
        }
        try {
            messageBody.measure(View.MeasureSpec.makeMeasureSpec(available, View.MeasureSpec.AT_MOST),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        } catch (Throwable ignored) {
            return;
        }
        int height = Math.min(Math.max(Ui.dpi(this, 60), messageBody.getMeasuredHeight()), maxHeight);
        ViewGroup.LayoutParams raw = messageScroll.getLayoutParams();
        if (raw != null && raw.height != height) {
            raw.height = height;
            messageScroll.setLayoutParams(raw);
        }
    }

    private void hideMessage() {
        if (messageHolder != null) messageHolder.setVisibility(View.GONE);
    }

    /**
     * Prueba el enlace del canal actual y muestra un informe con lo que responde el
     * servidor paso a paso (código HTTP, tipo de contenido, si es un manifiesto DASH
     * válido, sus pistas, códecs y si lleva DRM). No cambia la reproducción.
     */
    private void probeCurrentLink() {
        final Channel channel = currentChannel();
        final Map<String, String> headers = channelHeaders(channel);
        showMessage("Comprobando el enlace…",
                "Se está pidiendo el archivo al servidor con las cabeceras configuradas."
                        + "\n\nEn cuanto responda aparecerá aquí el informe real: código HTTP, tipo de"
                        + " contenido, tipo de manifiesto, pistas y protección.", false);
        background.execute(new Runnable() {
            @Override
            public void run() {
                String report;
                try {
                    report = DashProbe.report(channel.url, headers);
                } catch (Throwable t) {
                    report = "No se pudo completar la prueba del enlace: " + describe(t);
                }
                final String text = report;
                Diag.add("Reproductor · informe del enlace del canal " + channel.name + ":\n" + text);
                Diag.save(PlayerActivity.this);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing() || isDestroyed()) return;
                        showMessage("Informe del enlace", text, true);
                    }
                });
            }
        });
    }

    /** Copia el motivo del fallo para que el usuario pueda enviarlo. */
    private void copyMessage() {
        String text = messageText.length() > 0 ? messageText : "LENY TV · reproductor";
        try {
            android.content.ClipboardManager clipboard =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (clipboard == null) {
                toast("No se pudo copiar el motivo");
                return;
            }
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("LENY TV", text));
            Diag.save(this);
            toast("Motivo copiado: ya puedes pegarlo");
        } catch (Throwable t) {
            toast("No se pudo copiar el motivo");
        }
    }

    private String describeError(int what, int extra) {
        String message;
        switch (extra) {
            case -1004:
                message = "No se pudo abrir el enlace: problema de conexión o el servidor rechazó la petición.";
                break;
            case -1007:
                message = "El contenido está dañado o usa un formato que este teléfono no puede leer.";
                break;
            case -1010:
                message = "El códec o el tipo de archivo no está soportado por este teléfono.";
                break;
            case -110:
                message = "Se agotó el tiempo de espera al conectar con el servidor.";
                break;
            case Integer.MIN_VALUE:
                // Este código (-2147483648) no significa nada por sí solo: es el
                // "sin detalle" del reproductor de Android.
                message = "El reproductor de Android no dio ningún detalle: devolvió el código genérico"
                        + " -2147483648, que no es un código HTTP ni un motivo real.";
                break;
            default:
                message = MediaErrors.explain(what, extra);
                break;
        }
        if (what == 100) {
            message = "El servicio de medios de Android se reinició. Vuelve a intentarlo.";
        }
        return message;
    }

    private String playbackHints() {
        return "Comprueba que el enlace esté activo y que haya conexión.\n"
                + "Algunos servidores necesitan cabeceras User-Agent o Referer: configúralas en los ajustes del reproductor.\n"
                + "También puede ser un códec (H.265, AC-3) o un contenido con DRM que este teléfono no admite.";
    }

    private static String describe(Throwable t) {
        String message = t == null ? null : t.getMessage();
        if (message == null || message.length() == 0) {
            message = t == null ? "error desconocido" : t.getClass().getSimpleName();
        }
        return message;
    }

    // ------------------------------------------------------------------ imagen

    private void applyAspect() {
        if (surfaceView == null || videoBox == null) return;
        int boxWidth = videoBox.getWidth();
        int boxHeight = videoBox.getHeight();
        if (boxWidth <= 0 || boxHeight <= 0) return;

        if (videoWidth <= 0 || videoHeight <= 0) {
            surfaceView.setLayoutParams(new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER));
            return;
        }

        String mode = AppPrefs.aspect(this);
        float videoAspect = (float) videoWidth / (float) videoHeight;
        float boxAspect = (float) boxWidth / (float) boxHeight;
        int width;
        int height;

        if (AppPrefs.ASPECT_FILL.equals(mode)) {
            width = boxWidth;
            height = boxHeight;
        } else if (AppPrefs.ASPECT_CROP.equals(mode)) {
            if (videoAspect > boxAspect) {
                height = boxHeight;
                width = Math.round(height * videoAspect);
            } else {
                width = boxWidth;
                height = Math.round(width / videoAspect);
            }
        } else if (AppPrefs.ASPECT_ORIGINAL.equals(mode)) {
            width = Math.min(videoWidth, boxWidth);
            height = Math.round(width / videoAspect);
        } else {
            if (videoAspect > boxAspect) {
                width = boxWidth;
                height = Math.round(width / videoAspect);
            } else {
                height = boxHeight;
                width = Math.round(height * videoAspect);
            }
        }
        if (width <= 0 || height <= 0) return;
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(width, height, Gravity.CENTER);
        surfaceView.setLayoutParams(params);
    }

    private void openAspectPanel() {
        String[] labels = AppPrefs.aspectLabels();
        final String[] keys = AppPrefs.aspectKeys();
        int selected = AppPrefs.aspectIndex(AppPrefs.aspect(this));
        showChoicePanel("MODO DE IMAGEN", labels, selected, new ChoiceListener() {
            @Override
            public void onChoice(int position) {
                closeChoice();
                if (position < 0 || position >= keys.length) return;
                AppPrefs.setAspect(PlayerActivity.this, keys[position]);
                applyAspect();
                toast("Modo de imagen: " + AppPrefs.aspectLabel(keys[position]));
            }
        });
    }

    private void openQualityPanel() {
        if (media3 != null && media3.qualityCount() > 0) {
            openDashQualityPanel();
            return;
        }
        openStreamQualityPanel();
    }


    private void openDashQualityPanel() {
        final Media3Engine engine = media3;
        if (engine == null || engine.qualityCount() == 0) {
            showMessage("Calidad", "El stream todavia no ha entregado sus calidades."
                    + " Espera unos segundos a que empiece la reproduccion y vuelve a intentarlo.", false);
            return;
        }
        if (engine.hasSingleQuality()) {
            showMessage("Calidad", engine.singleQualityMessage(), false);
            return;
        }
        int count = engine.qualityCount();
        String[] labels = new String[count];
        for (int i = 0; i < count; i++) labels[i] = engine.qualityLabel(i);
        showChoicePanel("CALIDAD DEL CANAL", labels, engine.qualityIndex(), new ChoiceListener() {
            @Override
            public void onChoice(int position) {
                closeChoice();
                if (position < 0 || position >= engine.qualityCount()) return;
                String label = engine.qualityLabel(position);
                String qualityNote = engine.setQuality(position);
                label = engine.qualityLabel(engine.qualityIndex());
                if (qualityNote != null && qualityNote.length() > 0) {
                    showMessage("Calidad", qualityNote, false);
                }
                setBuffering(true);
                toast("Calidad: " + label);
            }
        });
    }

    private void openStreamQualityPanel() {
        final Channel channel = currentChannel();
        final String url = channel == null ? "" : channel.url;
        final Map<String, String> headers = channelHeaders(channel);
        // Un archivo suelto (.mp4, .webm, .ts) no es una lista de calidades: no se
        // descarga solo para comprobarlo.
        String type = FormatDetector.fromUrl(url);
        if (FormatDetector.MP4.equals(type) || FormatDetector.WEBM.equals(type)
                || FormatDetector.TS.equals(type)) {
            if (openPlayerTrackPanel()) return;
            showMessage("Calidad", "Este canal es un archivo de vídeo con una sola calidad: LENY TV la"
                    + " reproduce directamente y no hay otras para elegir.", false);
            return;
        }
        showMessage("Calidad", "Leyendo las calidades que ofrece el canal…", false);
        setBuffering(true);
        try {
            background.execute(new Runnable() {
                @Override
                public void run() {
                    final StreamQuality.Result result = StreamQuality.inspect(url, headers);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if (isFinishing() || isDestroyed()) return;
                            setBuffering(false);
                            hideMessage();
                            showStreamQuality(result);
                        }
                    });
                }
            });
        } catch (Throwable t) {
            setBuffering(false);
            Diag.add("Reproductor · no se pudieron leer las calidades del canal: " + Net.describe(t));
            showMessage("Calidad", "No se pudieron leer las calidades del canal: " + describe(t) + ".", false);
        }
    }

    /** Muestra el resultado de leer el enlace: calidades reales o el motivo. */
    private void showStreamQuality(StreamQuality.Result result) {
        if (result == null) return;
        if (result.multiple()) {
            String[] labels = new String[result.options.size() + 1];
            labels[0] = StreamQuality.AUTOMATIC_LABEL;
            int selected = 0;
            for (int i = 0; i < result.options.size(); i++) {
                StreamQuality.Option option = result.options.get(i);
                labels[i + 1] = option.label;
                if (option.dash) {
                    if (preferredDashQuality >= 0 && option.dashIndex == preferredDashQuality) {
                        selected = i + 1;
                    }
                } else if (variantUrl.length() > 0 && option.url.equals(variantUrl)) {
                    selected = i + 1;
                }
            }
            final StreamQuality.Result found = result;
            showChoicePanel("CALIDAD DEL CANAL", labels, selected, new ChoiceListener() {
                @Override
                public void onChoice(int position) {
                    closeChoice();
                    if (position <= 0) {
                        boolean wasChosen = variantUrl.length() > 0 || variantLabel.length() > 0
                                || preferredDashQuality >= 0;
                        variantUrl = "";
                        variantLabel = "";
                        preferredDashQuality = -1;
                        if (!wasChosen) {
                            toast("Ya se está usando la calidad automática");
                            return;
                        }
                        toast("Calidad: " + StreamQuality.AUTOMATIC_LABEL);
                        startPlayback();
                        return;
                    }
                    StreamQuality.Option option = found.options.get(position - 1);
                    if (option.dash) {
                        variantUrl = "";
                        variantLabel = option.label;
                        preferredDashQuality = option.dashIndex;
                        // El enlace ha resultado ser un manifiesto DASH: se reproduce con
                        // el motor DASH interno, que es el único que aplica la resolución
                        // elegida (antes podía quedarse en el reproductor del sistema).
                        manualType = FormatDetector.DASH;
                        typeOrigin = "";
                    } else {
                        variantUrl = option.url;
                        variantLabel = option.label;
                        preferredDashQuality = -1;
                        // Cada calidad de una lista HLS es una lista de reproducción
                        // normal: se reproduce con el reproductor de Android.
                        manualType = FormatDetector.HLS;
                        typeOrigin = "";
                    }
                    Diag.add("Reproductor · calidad elegida en el menú (" + found.kindText() + "): "
                            + option.label + " · " + Diag.channelUrl(option.url.length() == 0
                            ? currentChannel().url : option.url));
                    toast("Calidad: " + option.label);
                    startPlayback();
                }
            });
            return;
        }
        if (result.options.size() == 1) {
            showMessage("Calidad", "Este canal solo ofrece una calidad: " + result.options.get(0).label
                    + ".\n\nLENY TV la reproduce directamente y no hay otra que elegir.", false);
            return;
        }
        if (openPlayerTrackPanel()) return;
        String note = result.note.length() == 0
                ? "Este canal entrega una sola pista de vídeo: el reproductor usa la única calidad"
                + " que hay y no ofrece otras para elegir." : result.note;
        showMessage("Calidad", note, false);
    }

    /**
     * Menú con las pistas de vídeo que sí expone el reproductor del sistema. Es el
     * último recurso, cuando el enlace no es un manifiesto con calidades.
     * Devuelve true si mostró algo.
     */
    private boolean openPlayerTrackPanel() {
        // Con Media3 las pistas de vídeo se gestionan desde el panel CALIDAD.
        openDashQualityPanel();
        return true;
    }


    private void openSettingsPanel() {
        if (settingsHolder != null) closeSettings();
        final FrameLayout holder = new FrameLayout(this);
        holder.setBackgroundColor(0xB3000000);
        holder.setClickable(true);
        holder.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeSettings();
            }
        });

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(Ui.roundedStroke(this, Ui.PANEL, Ui.RED_DEEP, 1.5f, 12f));
        panel.setPadding(Ui.dpi(this, 14), Ui.dpi(this, 12), Ui.dpi(this, 14), Ui.dpi(this, 12));
        panel.setClickable(true);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = Ui.text(this, "AJUSTES DEL REPRODUCTOR", 14f, Ui.RED, true);
        title.setLetterSpacing(0.1f);
        header.addView(title, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView close = Ui.iconButton(this, Icons.close(this, Ui.TEXT), "Cerrar los ajustes del reproductor", 38f);
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(
                Ui.dpi(this, 38), Ui.dpi(this, 38));
        close.setLayoutParams(closeParams);
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeSettings();
            }
        });
        header.addView(close);
        panel.addView(header);

        ScrollView scroll = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        TextView aspectInfo = Ui.text(this, "Modo de imagen actual: " + AppPrefs.aspectLabel(AppPrefs.aspect(this)), 12f, Ui.TEXT, false);
        aspectInfo.setPadding(0, Ui.dpi(this, 8), 0, Ui.dpi(this, 4));
        box.addView(aspectInfo);

        LinearLayout aspectRow = new LinearLayout(this);
        aspectRow.setOrientation(LinearLayout.HORIZONTAL);
        TextView changeAspect = Ui.primaryButton(this, "Cambiar modo de imagen");
        changeAspect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openAspectPanel();
            }
        });
        aspectRow.addView(changeAspect);
        TextView quality = Ui.actionButton(this, "Elegir calidad");
        LinearLayout.LayoutParams qp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        qp.leftMargin = Ui.dpi(this, 8);
        quality.setLayoutParams(qp);
        quality.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openQualityPanel();
            }
        });
        aspectRow.addView(quality);
        box.addView(aspectRow);

        TextView typeLabel = Ui.text(this, "Tipo de contenido (se aplica al reintentar)", 11f, Ui.TEXT_DIM, true);
        typeLabel.setPadding(0, Ui.dpi(this, 12), 0, Ui.dpi(this, 4));
        box.addView(typeLabel);
        final Ui.SpinnerAdapter typeAdapter = new Ui.SpinnerAdapter(this, FormatDetector.options());
        final Spinner typeSpinner = new Spinner(this);
        typeSpinner.setAdapter(typeAdapter);
        int typeSelection = FormatDetector.AUTO.equals(currentType) ? 0 : typeAdapter.indexOf(currentType);
        typeSpinner.setSelection(typeSelection);
        typeSpinner.setContentDescription("Tipo de contenido del canal");
        box.addView(typeSpinner);

        TextView uaLabel = Ui.text(this, "User-Agent (opcional)", 11f, Ui.TEXT_DIM, true);
        uaLabel.setPadding(0, Ui.dpi(this, 12), 0, Ui.dpi(this, 4));
        box.addView(uaLabel);
        final EditText uaField = new EditText(this);
        uaField.setHint("Por ejemplo: VLC/3.0.18 LibVLC/3.0.18");
        uaField.setHintTextColor(Ui.TEXT_MUTED);
        uaField.setTextColor(Ui.TEXT);
        uaField.setTextSize(12f);
        uaField.setSingleLine(true);
        uaField.setInputType(InputType.TYPE_CLASS_TEXT);
        uaField.setText(AppPrefs.userAgent(this));
        uaField.setBackground(Ui.roundedStroke(this, Ui.BG, Ui.BLUE_DEEP, 1f, 6f));
        uaField.setPadding(Ui.dpi(this, 10), Ui.dpi(this, 8), Ui.dpi(this, 10), Ui.dpi(this, 8));
        box.addView(uaField);

        TextView refLabel = Ui.text(this, "Referer (opcional)", 11f, Ui.TEXT_DIM, true);
        refLabel.setPadding(0, Ui.dpi(this, 12), 0, Ui.dpi(this, 4));
        box.addView(refLabel);
        final EditText refField = new EditText(this);
        refField.setHint("Por ejemplo: https://mi-proveedor.com/");
        refField.setHintTextColor(Ui.TEXT_MUTED);
        refField.setTextColor(Ui.TEXT);
        refField.setTextSize(12f);
        refField.setSingleLine(true);
        refField.setInputType(InputType.TYPE_CLASS_TEXT);
        refField.setText(AppPrefs.referer(this));
        refField.setBackground(Ui.roundedStroke(this, Ui.BG, Ui.BLUE_DEEP, 1f, 6f));
        refField.setPadding(Ui.dpi(this, 10), Ui.dpi(this, 8), Ui.dpi(this, 10), Ui.dpi(this, 8));
        box.addView(refField);

        // Cabeceras adicionales: hay proveedores que exigen Origin, Cookie o una
        // cabecera propia. Se envían al manifiesto .mpd, a los datos iniciales y a
        // cada trozo del canal, igual que el User-Agent y el Referer.
        TextView extraLabel = Ui.text(this, "Cabeceras adicionales (una por línea: Nombre: valor)",
                11f, Ui.TEXT_DIM, true);
        extraLabel.setPadding(0, Ui.dpi(this, 12), 0, Ui.dpi(this, 4));
        box.addView(extraLabel);
        final EditText extraField = new EditText(this);
        extraField.setHint("Por ejemplo: Origin: https://mi-proveedor.com");
        extraField.setHintTextColor(Ui.TEXT_MUTED);
        extraField.setTextColor(Ui.TEXT);
        extraField.setTextSize(12f);
        extraField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        extraField.setMinLines(2);
        extraField.setMaxLines(3);
        extraField.setText(AppPrefs.extraHeaders(this));
        extraField.setBackground(Ui.roundedStroke(this, Ui.BG, Ui.BLUE_DEEP, 1f, 6f));
        extraField.setPadding(Ui.dpi(this, 10), Ui.dpi(this, 8), Ui.dpi(this, 10), Ui.dpi(this, 8));
        box.addView(extraField);

        TextView volumeLabel = Ui.text(this, "Volumen", 11f, Ui.TEXT_DIM, true);
        volumeLabel.setPadding(0, Ui.dpi(this, 12), 0, 0);
        box.addView(volumeLabel);
        SeekBar volume = new SeekBar(this);
        volume.setMax(100);
        volume.setProgress(volumePercent());
        volume.setContentDescription("Volumen del reproductor");
        volume.setProgressTintList(ColorStateList.valueOf(Ui.RED));
        volume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int value, boolean fromUser) {
                if (fromUser) setVolumePercent(value / 100f);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        box.addView(volume);

        TextView brightnessLabel = Ui.text(this, "Brillo de la app", 11f, Ui.TEXT_DIM, true);
        brightnessLabel.setPadding(0, Ui.dpi(this, 12), 0, 0);
        box.addView(brightnessLabel);
        SeekBar brightness = new SeekBar(this);
        brightness.setMax(100);
        brightness.setProgress(Math.round(brightnessValue() * 100f));
        brightness.setContentDescription("Brillo de la aplicación");
        brightness.setProgressTintList(ColorStateList.valueOf(Ui.BLUE));
        brightness.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int value, boolean fromUser) {
                if (fromUser) setBrightness(Math.max(0.05f, value / 100f));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        box.addView(brightness);

        TextView keep = Ui.text(this, "Mantener la pantalla encendida al reproducir", 11f, Ui.TEXT_DIM, true);
        keep.setPadding(0, Ui.dpi(this, 12), 0, 0);
        box.addView(keep);
        android.widget.Switch screen = new android.widget.Switch(this);
        screen.setChecked(AppPrefs.keepScreenOn(this));
        screen.setContentDescription("Mantener la pantalla encendida");
        screen.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                AppPrefs.setKeepScreenOn(PlayerActivity.this, isChecked);
                applyKeepScreenOn();
            }
        });
        box.addView(screen);

        TextView note = Ui.text(this, "El brillo se aplica solo dentro de la app, no al sistema. "
                + "Desliza arriba o abajo en la mitad izquierda para el volumen y en la derecha para el brillo. "
                + "Las cabeceras de cada canal se leen solas de su bloque de la lista (#EXTINF, #EXTVLCOPT, "
                + "#KODIPROP, #EXTHTTP y lo que va detrás de \"|\") y se aplican a ese canal antes del primer "
                + "intento, tanto al manifiesto DASH como a cada trozo; lo de aquí solo se usa cuando el canal "
                + "no trae las suyas. Si la lista declara DRM, el motor solo lo identifica: no descifra "
                + "contenido protegido.", 11f, Ui.TEXT_MUTED, false);
        note.setLineSpacing(Ui.dp(this, 2f), 1f);
        note.setPadding(0, Ui.dpi(this, 12), 0, 0);
        box.addView(note);

        scroll.addView(box);
        panel.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.END);
        TextView cancel = Ui.actionButton(this, "Cerrar");
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeSettings();
            }
        });
        buttons.addView(cancel);
        TextView apply = Ui.primaryButton(this, "Aplicar y reintentar");
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        ap.leftMargin = Ui.dpi(this, 8);
        apply.setLayoutParams(ap);
        apply.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                AppPrefs.setUserAgent(PlayerActivity.this, uaField.getText().toString());
                AppPrefs.setReferer(PlayerActivity.this, refField.getText().toString());
                AppPrefs.setExtraHeaders(PlayerActivity.this, extraField.getText().toString());
                String selection = typeAdapter.labelAt(typeSpinner.getSelectedItemPosition());
                manualType = FormatDetector.isAuto(selection) ? "" : selection;
                closeSettings();
                startPlayback();
            }
        });
        buttons.addView(apply);
        LinearLayout.LayoutParams buttonsParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        buttonsParams.topMargin = Ui.dpi(this, 10);
        panel.addView(buttons, buttonsParams);

        // El panel de ajustes se ajusta al ancho real: 470 dp fijos se salían de la
        // pantalla en un móvil en vertical y los campos quedaban cortados.
        int settingsWidth = Math.min(Ui.dpi(this, 470),
                getResources().getDisplayMetrics().widthPixels - Ui.dpi(this, 24));
        FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(
                Math.max(Ui.dpi(this, 220), settingsWidth), FrameLayout.LayoutParams.MATCH_PARENT);
        panelParams.gravity = Gravity.CENTER;
        panelParams.topMargin = Ui.dpi(this, 10);
        panelParams.bottomMargin = Ui.dpi(this, 10);
        holder.addView(panel, panelParams);

        settingsHolder = holder;
        root.addView(settingsHolder, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        ui.removeCallbacks(hideControls);
    }

    private void closeSettings() {
        if (settingsHolder != null) {
            root.removeView(settingsHolder);
            settingsHolder = null;
            scheduleHide();
        }
    }

    private void showChoicePanel(String title, String[] options, int selected, final ChoiceListener listener) {
        closeChoice();
        final FrameLayout holder = new FrameLayout(this);
        holder.setBackgroundColor(0xB3000000);
        holder.setClickable(true);
        holder.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeChoice();
            }
        });

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(Ui.roundedStroke(this, Ui.PANEL, Ui.RED_DEEP, 1.5f, 12f));
        panel.setPadding(Ui.dpi(this, 14), Ui.dpi(this, 12), Ui.dpi(this, 14), Ui.dpi(this, 12));
        panel.setClickable(true);

        TextView heading = Ui.text(this, title, 14f, Ui.RED, true);
        heading.setLetterSpacing(0.1f);
        panel.addView(heading);

        ScrollView scroll = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < options.length; i++) {
            final int position = i;
            boolean isSelected = i == selected;
            TextView row = Ui.text(this, options[i], 13f, isSelected ? Ui.TEXT : Ui.TEXT_DIM, isSelected);
            row.setPadding(Ui.dpi(this, 10), Ui.dpi(this, 12), Ui.dpi(this, 10), Ui.dpi(this, 12));
            row.setBackground(Ui.buttonBg(this, isSelected ? Ui.CARD_SEL : Ui.PANEL,
                    Ui.CARD_SEL, Ui.BLUE_DEEP, 6f));
            row.setClickable(true);
            row.setFocusable(true);
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    listener.onChoice(position);
                }
            });
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rowParams.topMargin = Ui.dpi(this, 6);
            list.addView(row, rowParams);
        }
        scroll.addView(list);
        panel.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView cancel = Ui.actionButton(this, "Cerrar");
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeChoice();
            }
        });
        LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cancelParams.topMargin = Ui.dpi(this, 10);
        cancelParams.gravity = Gravity.END;
        panel.addView(cancel, cancelParams);

        // El panel se ajusta al tamaño real de la pantalla: con medidas fijas, en
        // un móvil en vertical (más estrecho que 440 dp) se salía por los lados.
        int choiceWidth = Math.min(Ui.dpi(this, 440),
                getResources().getDisplayMetrics().widthPixels - Ui.dpi(this, 32));
        int choiceHeight = Math.min(Ui.dpi(this, 280),
                getResources().getDisplayMetrics().heightPixels - Ui.dpi(this, 48));
        FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(
                Math.max(Ui.dpi(this, 220), choiceWidth), Math.max(Ui.dpi(this, 160), choiceHeight));
        panelParams.gravity = Gravity.CENTER;
        holder.addView(panel, panelParams);

        choiceHolder = holder;
        root.addView(choiceHolder, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        ui.removeCallbacks(hideControls);
    }

    private void closeChoice() {
        if (choiceHolder != null) {
            root.removeView(choiceHolder);
            choiceHolder = null;
            scheduleHide();
        }
    }

    // ------------------------------------------------------------------ panel de canales

    /** Solo selección + scroll; sin reintentos en bucle (evita cierre de la app). */
    private void focusDrawerRow(final int position) {
        if (drawerList == null || position < 0) return;
        try {
            if (position >= drawerPositions.size()) return;
            drawerList.setSelection(position);
            drawerList.smoothScrollToPosition(position);
        } catch (Throwable ignored) {
        }
    }

    private void openDrawer() {
        if (drawerHolder == null) return;
        rebuildDrawerSections();
        drawerHolder.setVisibility(View.VISIBLE);
        setControlsVisible(true);
        ui.removeCallbacks(hideControls);
    }

    /**
     * Vuelve a dibujar la fila de apartados del panel con los apartados que trae la
     * lista y marca el elegido. Si el elegido ya no existe se muestran todos los
     * canales de la lista.
     */
    private void rebuildDrawerSections() {
        if (drawerSectionRow == null) return;
        drawerSectionRow.removeAllViews();
        ArrayList<String> names = new ArrayList<String>();
        for (int i = 0; i < channels.size(); i++) {
            String category = categoryOf(channels.get(i));
            if (category.length() > 0 && !names.contains(category)) names.add(category);
        }
        java.util.Collections.sort(names);
        boolean exists = false;
        for (int i = 0; i < names.size(); i++) {
            if (names.get(i).equals(drawerSection)) exists = true;
        }
        if (!exists) drawerSection = "";

        for (int i = 0; i < names.size(); i++) {
            final String name = names.get(i);
            boolean chosen = name.equals(drawerSection);
            TextView chip = Ui.text(this, "", 10f, chosen ? 0xFFFFFFFF : Ui.TEXT_DIM, true);
            // REVISIÓN 52: los apartados se muestran en MAYÚSCULAS, como pidió el
            // usuario (ENTEL GOL, LIGA XBER, ESPN). El nombre guardado no cambia.
            chip.setText(Names.forSection(chip.getPaint(), name));
            chip.setSingleLine(true);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(Ui.dpi(this, 12), Ui.dpi(this, 8), Ui.dpi(this, 12), Ui.dpi(this, 8));
            chip.setMinHeight(Ui.dpi(this, 40));
            chip.setBackground(Ui.buttonBg(this, chosen ? 0xB3E13A2B : 0x50161620,
                    chosen ? Ui.RED : 0x33FFFFFF, chosen ? Ui.RED : 0x33FFFFFF, 14f));
            chip.setContentDescription("Apartado " + name);
            chip.setClickable(true);
            chip.setFocusable(true);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    drawerSection = name;
                    rebuildDrawerSections();
                }
            });
            LinearLayout.LayoutParams chipParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            if (i > 0) chipParams.leftMargin = Ui.dpi(this, 6);
            drawerSectionRow.addView(chip, chipParams);
        }
        refreshDrawerFilter();
    }

    /** Recalcula los canales visibles en el panel y avisa a la lista. */
    private void refreshDrawerFilter() {
        drawerPositions.clear();
        for (int i = 0; i < channels.size(); i++) {
            String category = categoryOf(channels.get(i));
            if (drawerSection.length() == 0 || drawerSection.equals(category)) {
                drawerPositions.add(Integer.valueOf(i));
            }
        }
        if (drawerCount != null) drawerCount.setText(drawerPositions.size() + " entradas");
        if (drawerAdapter != null) drawerAdapter.notifyDataSetChanged();
    }

    private String categoryOf(Channel channel) {
        if (channel == null || channel.category == null) return "";
        return channel.category.trim();
    }

    /** Índice real (en la lista completa) del canal visible que ocupa esa fila. */
    private int channelIndexAt(int visiblePosition) {
        if (visiblePosition < 0 || visiblePosition >= drawerPositions.size()) return -1;
        return drawerPositions.get(visiblePosition).intValue();
    }

    private void closeDrawer() {
        if (drawerHolder != null) drawerHolder.setVisibility(View.GONE);
        scheduleHide();
    }

    private final class DrawerAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return drawerPositions.size();
        }

        @Override
        public Object getItem(int position) {
            int channelIndex = channelIndexAt(position);
            return channelIndex < 0 ? null : channels.get(channelIndex);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout row;
            ImageView logo;
            TextView name;
            TextView category;
            if (convertView instanceof LinearLayout && convertView.getTag() instanceof Object[]) {
                row = (LinearLayout) convertView;
                Object[] holder = (Object[]) convertView.getTag();
                logo = (ImageView) holder[0];
                name = (TextView) holder[1];
                category = (TextView) holder[2];
            } else {
                row = new LinearLayout(PlayerActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(Ui.dpi(PlayerActivity.this, 8), Ui.dpi(PlayerActivity.this, 8),
                        Ui.dpi(PlayerActivity.this, 8), Ui.dpi(PlayerActivity.this, 8));

                logo = new ImageView(PlayerActivity.this);
                logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
                logo.setLayoutParams(new LinearLayout.LayoutParams(
                        Ui.dpi(PlayerActivity.this, 32), Ui.dpi(PlayerActivity.this, 32)));
                row.addView(logo);

                LinearLayout texts = new LinearLayout(PlayerActivity.this);
                texts.setOrientation(LinearLayout.VERTICAL);
                name = Ui.text(PlayerActivity.this, "", 13f, Ui.TEXT, false);
                name.setMaxLines(1);
                name.setEllipsize(TextUtils.TruncateAt.END);
                texts.addView(name);
                category = Ui.text(PlayerActivity.this, "", 10f, Ui.TEXT_MUTED, false);
                category.setMaxLines(1);
                texts.addView(category);
                LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                textParams.leftMargin = Ui.dpi(PlayerActivity.this, 10);
                row.addView(texts, textParams);
                row.setTag(new Object[]{logo, name, category});
                row.setFocusable(true);
                row.setClickable(true);
                row.setFocusableInTouchMode(false);
            }
            int channelIndex = channelIndexAt(position);
            if (channelIndex < 0) return row;
            Channel channel = channels.get(channelIndex);
            String visibleName = Names.forDisplay(name.getPaint(), channel.name);
            name.setText(visibleName);
            category.setText(Names.forSection(category.getPaint(), channel.category));
            final boolean isCurrent = channelIndex == index;
            final int channelIndexFinal = channelIndex;
            name.setTextColor(isCurrent ? 0xFFFFFFFF : Ui.TEXT);
            // Filas del panel también translúcidas, para que el vídeo se vea detrás.
            row.setBackground(Ui.roundedStroke(PlayerActivity.this,
                    isCurrent ? 0x99E13A2B : 0x5A14141C, isCurrent ? Ui.RED : 0x55242433, 1f, 8f));
            LogoLoader.load(PlayerActivity.this, channel.logo, logo, visibleName);
            row.setFocusable(true);
            row.setClickable(true);
            row.setFocusableInTouchMode(false);
            // Brillo fuerte solo con foco del mando (el táctil sigue igual vía OnItemClick)
            final int listPosFocus = position;
            row.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override
                public void onFocusChange(View v, boolean hasFocus) {
                    if (hasFocus) {
                        v.setBackground(Ui.roundedStroke(PlayerActivity.this,
                                0xCC00A8E8, 0xFF00F0FF, 2.5f, 8f));
                        name.setTextColor(0xFFFFFFFF);
                        // Al bajar con el mando, la lista debe desplazarse
                        if (drawerList != null) {
                            try {
                                drawerList.setSelection(listPosFocus);
                                drawerList.smoothScrollToPosition(listPosFocus);
                            } catch (Throwable ignored) {
                            }
                        }
                    } else {
                        v.setBackground(Ui.roundedStroke(PlayerActivity.this,
                                isCurrent ? 0x99E13A2B : 0x5A14141C,
                                isCurrent ? Ui.RED : 0x55242433, 1f, 8f));
                        name.setTextColor(isCurrent ? 0xFFFFFFFF : Ui.TEXT);
                    }
                }
            });
            final int listPos = position;
            row.setOnKeyListener(new View.OnKeyListener() {
                @Override
                public boolean onKey(View v, int keyCode, KeyEvent event) {
                    if (event.getAction() == KeyEvent.ACTION_DOWN) {
                        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                                && listPos < drawerPositions.size() - 1) {
                            int next = listPos + 1;
                            drawerList.setSelection(next);
                            drawerList.smoothScrollToPosition(next);
                            focusDrawerRow(next);
                            return true;
                        }
                        if (keyCode == KeyEvent.KEYCODE_DPAD_UP && listPos > 0) {
                            int prev = listPos - 1;
                            drawerList.setSelection(prev);
                            drawerList.smoothScrollToPosition(prev);
                            focusDrawerRow(prev);
                            return true;
                        }
                    }
                    if (event.getAction() != KeyEvent.ACTION_UP) return false;
                    if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                            || keyCode == KeyEvent.KEYCODE_ENTER
                            || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                        if (drawerList != null) {
                            drawerList.performItemClick(v, listPos, listPos);
                        } else {
                            playIndex(channelIndexFinal);
                            closeDrawer();
                        }
                        return true;
                    }
                    return false;
                }
            });
            if (row.isFocused()) {
                row.setBackground(Ui.roundedStroke(PlayerActivity.this,
                        0xCC00A8E8, 0xFF00F0FF, 2.5f, 8f));
                name.setTextColor(0xFFFFFFFF);
            }
            // Táctil: la fila es clickable y debe abrir el canal (OnItemClick del
            // ListView no llega si el hijo consume el toque).
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    int target = channelIndexFinal;
                    if (target < 0) return;
                    closeDrawer();
                    playIndex(target);
                }
            });
            return row;
        }
    }

    // ------------------------------------------------------------------ ciclo de vida

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        surface = holder.getSurface();
        Diag.add("Reproductor · Android entregó la superficie de vídeo"
                + (media3 != null ? " y se enlazó al motor Media3" : " (todavía sin motor)"));
        if (media3 != null) media3.setSurface(surface);
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        surface = holder.getSurface();
        if (media3 != null) media3.setSurface(surface);
        applyAspect();
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        surface = null;
        if (media3 != null) media3.setSurface(null);
    }

    @Override
    protected void onPause() {
        super.onPause();
        Immersion.stopKeeper();
        wasPlayingBeforePause = playing;
        ui.removeCallbacks(hideControls);
        ui.removeCallbacks(timeTick);
        if (media3 != null && playing) {
            media3.setPaused(true);
            playing = false;
            updatePlayButton();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyKeepScreenOn();
        if (immersive) Immersion.keepHidden(this);
        if (wasPlayingBeforePause && media3 != null) {
            media3.setPaused(false);
            playing = true;
            updatePlayButton();
            ui.removeCallbacks(timeTick);
            ui.postDelayed(timeTick, 1000);
        }
        wasPlayingBeforePause = false;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacksAndMessages(null);
        releasePlayer();
        background.shutdownNow();
    }

    /** Suelta la memoria de los logos cuando Android avisa de que va escasa. */
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            LogoLoader.trimMemory();
        }
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
