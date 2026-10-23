package com.pocketbuilder.app_wslenytvplayer1792356127271;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Pantalla principal de LENY TV: barra superior, categorías, canales y ajustes. */
public final class MainActivity extends android.app.Activity {

    private static final int REQ_PICK_FILE = 4711;
    private static final String CAT_ALL = "Todas";
    private static final String ADD_URL_KEY = "@url";
    /** Tiempo máximo que se espera a que termine la lectura de una lista. */
    private static final long PARSE_WATCHDOG_MS = 25000L;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService background = Executors.newFixedThreadPool(2);
    private final ArrayList<Channel> all = new ArrayList<Channel>();
    private final ArrayList<Channel> shown = new ArrayList<Channel>();
    private final ArrayList<String> categoryNames = new ArrayList<String>();
    private final HashMap<String, Integer> categoryCounts = new HashMap<String, Integer>();

    private PlaylistStore.Playlist activePlaylist;
    private String selectedCategory = CAT_ALL;
    private String searchQuery = "";
    private int loadToken = 0;
    private boolean busy = false;
    /** Aviso o motivo del último resultado de carga (se muestra en la barra inferior). */
    private String loadNote = "";
    private String lastLoadProblem = "";
    /** Marca de la lectura que ya terminó (así no se avisa de una lectura que sí acabó). */
    private volatile int finishedParseToken = -1;
    /** Lista que está cargada ahora mismo (para detectar que el usuario cambia de lista). */
    private long loadedPlaylistId = -1L;
    /** Último motivo anotado de que no se vea ningún canal (evita repetirlo sin parar). */
    private String lastFilterNote = "";

    private TextView clockView;
    private TextView dateView;
    private TextView titleView;
    private TextView subtitleView;
    private LinearLayout urlRow;
    /** Campo del enlace visible solo cuando se pulsa el teclado de la barra. */
    private boolean lenyUrlShown;
    private LinearLayout statusBar;
    /** La pantalla deja poco hueco: cabecera y barras más apretadas para que se vea la cuadrícula. */
    private boolean compactMode = false;
    /** Ya se midió la cuadrícula tal como queda colocada en pantalla. */
    private boolean layoutSeen = false;
    private int gridAuditCount = 0;
    /** Número de correcciones seguidas intentadas sobre la cuadrícula. */
    private int gridRepairTries = 0;
    /** Se avisó en pantalla de que este teléfono no consigue dibujar los canales. */
    private boolean gridProblemShown = false;
    private int cardLogoDp = 52;
    private int cardNameDp = 34;
    private int cardPadDp = 8;
    private TextView statusView;
    private TextView playlistView;
    private TextView countView;
    private TextView emptyTitle;
    private TextView emptyBody;
    private LinearLayout emptyActionsBar;
    private TextView emptyDemoButton;
    private TextView emptySettingsButton;
    private TextView buttonPlay;
    private TextView buttonAddList;
    private TextView buttonSearch;
    private ProgressBar statusSpinner;
    private FrameLayout root;
    private FrameLayout settingsOverlay;
    private View emptyView;
    private GridView grid;
    private LinearLayout headerBar;
    /** Contenedor completo de la cabecera (barra + portada decorativa). */
    private FrameLayout headerBox;
    /** Imagen decorativa de la cabecera: es la que antes medía toda la pantalla. */
    private ImageView headerCover;
    /** Columna vertical que reparte la pantalla: cabecera, campo, canales y barra. */
    private LinearLayout columnBox;
    /** Intentos de limitar la cabecera cuando se estaba quedando con toda la pantalla. */
    private int headerLimitTries = 0;
    /** Se retiraron el campo de enlace y la barra inferior para dar sitio a los canales. */
    private boolean barsHiddenForRoom = false;
    /** Veces que se ha recurrido a liberar sitio retirando esas barras. */
    private int barsRoomTries = 0;
    private LinearLayout railBox;
    private FrameLayout contentArea;
    private TextView emptyRetry;
    private TextView emptyCopy;
    private TextView emptyDiag;
    private TextView emptyPlay;
    private FrameLayout diagOverlay;
    private TextView diagBodyView;
    private EditText diagUrlField;
    /** Enlace de la lista activa cuando en realidad resulta ser un vídeo HLS. */
    private String activeLinkUrl = "";
    private String lastStatusLogged = "";
    private int insetLeft = -1;
    private int insetTop = -1;
    private int insetRight = -1;
    private int insetBottom = -1;
    private ListView categoryList;
    private EditText urlField;
    private EditText searchField;
    private LinearLayout searchRow;
    private Spinner typeSpinner;
    private Ui.SpinnerAdapter typeAdapter;
    private RogChannelAdapter channelAdapter;
    private RogCategoryAdapter categoryAdapter;

    // Ajustes (se crean al abrir el panel)
    private LinearLayout savedListsBox;
    private EditText settingsUrlField;
    private EditText userAgentField;
    private EditText refererField;
    private Spinner settingsTypeSpinner;
    private Ui.SpinnerAdapter settingsTypeAdapter;
    private Spinner aspectSpinner;
    private Spinner delaySpinner;
    private Switch screenSwitch;
    private TextView tabListaView;
    private TextView tabAppView;
    private FrameLayout settingsContent;
    private long pendingActiveId = -1;
    /** Listas marcadas en el panel AJUSTES (pueden ser varias). */
    private final java.util.LinkedHashSet<Long> pendingActiveIds = new java.util.LinkedHashSet<Long>();
    private int currentTab = 0;

    // Panel AÑADIR LISTA (estilo PLAYLIST de la app de referencia)
    private FrameLayout addOverlay;
    private LinearLayout addEntriesBox;
    private EditText addUrlField;
    private final ArrayList<LocalLists.Entry> addEntries = new ArrayList<LocalLists.Entry>();
    private final ArrayList<PlaylistStore.Playlist> savedAddPlaylists = new ArrayList<PlaylistStore.Playlist>();
    private final ArrayList<AddRowView> addRowViews = new ArrayList<AddRowView>();
    private final HashMap<String, Boolean> addSelected = new HashMap<String, Boolean>();
    private int scanToken = 0;

    private Runnable clockTick;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Immersion.apply(this);


        // ENTRADA ANTIGUA DEL MENÚ: si esta pantalla se abre con el intento de
        // inicio de Android (icono creado por una revisión anterior, anterior a la
        // portada), se muestra primero la portada y esta pantalla se cierra. Los
        // cambios de pantalla internos de la app no llevan acción, así que nunca
        // entran aquí y no hay riesgo de bucle.
        if (false && openedAsLauncherEntry()) { // REVISIÓN 48: se desactivó la
            // redirección a la portada que se añadió en la 47. Esta pantalla se
            // muestra siempre tal cual, sin saltos ni bucles entre portada y menú,
            // que es lo que dejó de funcionar («App no instalada» al pulsar el icono
            // y tampoco abría desde Ajustes → Abrir).
            try {
                startActivity(new Intent(this, CoverActivity.class));
            } catch (Throwable t) {
                Diag.add("Entrada · no se pudo abrir la portada: " + Net.describe(t));
            }
            finish();
            return;
        }
        Diag.init(this);
        Diag.add("LENY TV · " + Diag.REVISION + " · abre la pantalla principal");
        Diag.add("Móvil: " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL
                + " · Android " + android.os.Build.VERSION.RELEASE
                + " (API " + android.os.Build.VERSION.SDK_INT + ")");
        PlaylistStore.seedDemoIfEmpty(this);

        root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        columnBox = column;
        column.addView(RogBar.create(this, "LENY TV", "v1.56", new RogBar.Host() {
            public void onKeyboard() {
                lenyUrlShown = !lenyUrlShown;
                if (urlRow != null) {
                    urlRow.setVisibility(lenyUrlShown ? View.VISIBLE : View.GONE);
                }
            }

            public void onSearch() {
                toggleSearch();
            }

            public void onRefresh() {
                startLoad(true);
            }

            public void onSettings() {
                showSettings();
            }

            public void onPower() {
                finish();
            }
        }));
        // La barra anterior sigue construida (sus relojes y avisos funcionan) pero
        // no se ve: la barra visible es la de la referencia.
        View lenyOldHeader = buildHeader();
        column.addView(lenyOldHeader);
        lenyOldHeader.setVisibility(View.GONE);
        column.addView(buildUrlRow());
        // El campo de enlace no forma parte de la pantalla general de la
        // referencia: queda oculto y se abre con el botón de teclado.
        if (urlRow != null) urlRow.setVisibility(View.GONE);
        column.addView(buildSearchRow());
        column.addView(buildBody(), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        column.addView(buildStatusBar());
        // REVISIÓN 50: la referencia no tiene la línea de texto inferior, así que la
        // barra de estado no se muestra. Sus avisos importantes (los de tipo WARN)
        // siguen apareciendo como aviso flotante desde setStatus().
        if (statusBar != null) statusBar.setVisibility(View.GONE);
        root.addView(column, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);
        // Portada de bienvenida encima del menú cuando no se viene de la portada
        // de CoverActivity (así siempre hay portada al entrar y nunca sale dos veces).
        WelcomeCover.showIfNeeded(this);
        Immersion.keepHidden(this);
        // El fondo llega hasta el borde de la pantalla: los márgenes del recorte de
        // la cámara los rellena cada contenedor por dentro (cabecera, columna de
        // categorías y cuadrícula) en lugar del contenedor raíz, que era lo que
        // dejaba una franja vacía en el borde superior.
        Immersion.listenInsets(root, new Immersion.InsetCallback() {
            @Override
            public void onInsets(int left, int top, int right, int bottom) {
                applyScreenInsets(left, top, right, bottom);
            }
        });

        urlField.setText(AppPrefs.lastUrl(this));
        startClock();
        startLoad(false);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) Immersion.apply(this);
    }

    @Override
    public void onBackPressed() {
        if (diagOverlay != null) {
            closeDiagnostics();
            return;
        }
        if (addOverlay != null) {
            closeAddDialog();
            return;
        }
        if (settingsOverlay != null) {
            closeSettings();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Immersion.keepHidden(this);
        startClock();
    }

    @Override
    protected void onPause() {
        super.onPause();
        Immersion.stopKeeper();
        stopClock();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopClock();
        background.shutdownNow();
    }

    /**
     * La app está fijada en horizontal y Android no la vuelve a crear al girar.
     * Aquí se vuelve a medir la cuadrícula tras el cambio: si el hueco se queda
     * pequeño, se aprieta la cabecera y se comprueba que las tarjetas se sigan
     * dibujando (era otro de los puntos en los que la pantalla podía quedar
     * vacía sin que el registro dijera nada).
     */
    @Override
    public void onConfigurationChanged(android.content.res.Configuration config) {
        super.onConfigurationChanged(config);
        Diag.add("La pantalla ha cambiado de forma: ahora "
                + (config.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
                ? "horizontal" : "vertical") + " · " + config.screenWidthDp + "x" + config.screenHeightDp + " dp.");
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                headerLimitTries = 0;
                checkContentRoom();
                recalcGridColumns();
                auditGrid("después del cambio de pantalla");
            }
        }, 600L);
    }

    /**
     * Suelta la memoria de los logos cuando Android avisa de que va escasa. Se
     * vuelven a descargar cuando hagan falta, así la cuadrícula no se queda sin
     * imágenes ni la app pierde el estado.
     */
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            LogoLoader.trimMemory();
            Diag.add("Android pidió liberar memoria: se vació la caché de logos.");
        }
    }

    // ---------------------------------------------------------------- interfaz

    private View buildHeader() {
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(Ui.HEADER);
        headerBox = frame;
        Bitmap cover = Art.generated(this, "portada.png");
        if (cover != null) {
            ImageView coverView = new ImageView(this);
            coverView.setImageBitmap(cover);
            coverView.setScaleType(ImageView.ScaleType.CENTER_CROP);
            coverView.setAlpha(0.22f);
            coverView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            headerCover = coverView;
            // CAUSA DE LOS 0 dp DE LA CUADRÍCULA: la portada (1376x768, más ancha
            // que la pantalla) se colocaba con MATCH_PARENT dentro de una cabecera
            // de alto automático (WRAP_CONTENT). En ese caso Android mide la
            // imagen contra TODO el alto que le ofrecen, así que medía los 360 dp
            // de la pantalla entera: la cabecera se comía el hueco, el campo de
            // enlace y la barra inferior se salían por abajo y a la cuadrícula le
            // quedaban 0 px de alto. Ahora el alto de la portada está MEDIDO en dp:
            // la cabecera vuelve a ser una barra y la cuadrícula recibe el resto.
            frame.addView(coverView, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, Ui.dpi(this, headerArtDp())));
        }

        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dpi(this, 14), Ui.dpi(this, 8), Ui.dpi(this, 14), Ui.dpi(this, 8));
        headerBar = bar;

        LinearLayout titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        TextView title = Ui.text(this, "LENY TV", 26f, Ui.TEXT, true);
        title.setLetterSpacing(0.14f);
        titleView = title;
        titleBox.addView(title);
        subtitleView = Ui.text(this, "REPRODUCTOR IPTV · LISTAS M3U / HLS", 9f, Ui.RED, true);
        titleBox.addView(subtitleView);
        bar.addView(titleBox);

        LinearLayout clockBox = new LinearLayout(this);
        clockBox.setOrientation(LinearLayout.VERTICAL);
        clockBox.setGravity(Gravity.CENTER);
        clockView = Ui.text(this, "--:--:--", 19f, Ui.TEXT, true);
        clockView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        clockView.setGravity(Gravity.CENTER);
        dateView = Ui.text(this, "", 11f, Ui.TEXT_DIM, false);
        dateView.setGravity(Gravity.CENTER);
        clockBox.addView(clockView);
        clockBox.addView(dateView);
        bar.addView(clockBox, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        buttonSearch = headerButton("BUSCAR", Icons.search(this, Ui.TEXT));
        buttonSearch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleSearch();
            }
        });
        bar.addView(buttonSearch);

        TextView refresh = headerButton("ACTUALIZAR", Icons.refresh(this, Ui.TEXT));
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                refreshActiveList();
            }
        });
        bar.addView(refresh);

        TextView settings = headerButton("AJUSTES", Icons.gear(this, Ui.TEXT));
        settings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSettings();
            }
        });
        bar.addView(settings);

        TextView exit = headerButton("SALIR", Icons.power(this, Ui.TEXT));
        exit.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finishAffinity();
            }
        });
        bar.addView(exit);

        wrap.addView(bar);
        wrap.addView(Ui.hLine(this, Ui.RED, 3f));
        frame.addView(wrap, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        return frame;
    }

    private TextView headerButton(String label, Drawable icon) {
        TextView b = Ui.text(this, label, 10f, Ui.TEXT, true);
        b.setGravity(Gravity.CENTER);
        b.setAllCaps(true);
        b.setLetterSpacing(0.05f);
        icon.setBounds(0, 0, Ui.dpi(this, 14), Ui.dpi(this, 14));
        b.setCompoundDrawables(icon, null, null, null);
        b.setCompoundDrawablePadding(Ui.dpi(this, 5));
        b.setPadding(Ui.dpi(this, 8), Ui.dpi(this, 9), Ui.dpi(this, 8), Ui.dpi(this, 9));
        b.setBackground(Ui.buttonBg(this, 0x33FFFFFF, 0x99E13A2B, 0x55F4F4F8, 6f));
        b.setClickable(true);
        b.setFocusable(true);
        b.setMinHeight(Ui.dpi(this, 46));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = Ui.dpi(this, 4);
        b.setLayoutParams(lp);
        return b;
    }

    private View buildUrlRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundColor(Ui.PANEL);
        urlRow = row;
        row.setPadding(Ui.dpi(this, 12), Ui.dpi(this, 8), Ui.dpi(this, 12), Ui.dpi(this, 8));

        TextView label = Ui.text(this, "ENLACE", 10f, Ui.RED, true);
        label.setLetterSpacing(0.1f);
        label.setPadding(0, 0, Ui.dpi(this, 10), 0);
        row.addView(label);

        urlField = new EditText(this);
        urlField.setHint("Pega una lista M3U/M3U8 o un enlace de vídeo (.m3u8, .mp4, .ts, .webm)");
        urlField.setHintTextColor(Ui.TEXT_MUTED);
        urlField.setTextColor(Ui.TEXT);
        urlField.setTextSize(13f);
        urlField.setSingleLine(true);
        urlField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        urlField.setBackground(Ui.roundedStroke(this, Ui.BG, Ui.BLUE_DEEP, 1f, 6f));
        urlField.setPadding(Ui.dpi(this, 10), Ui.dpi(this, 9), Ui.dpi(this, 10), Ui.dpi(this, 9));
        LinearLayout.LayoutParams fieldParams = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(urlField, fieldParams);

        typeAdapter = new Ui.SpinnerAdapter(this, FormatDetector.options());
        typeSpinner = new Spinner(this);
        typeSpinner.setAdapter(typeAdapter);
        typeSpinner.setSelection(typeAdapter.indexOf(AppPrefs.contentType(this)));
        typeSpinner.setContentDescription("Tipo de contenido");
        LinearLayout.LayoutParams spinParams = new LinearLayout.LayoutParams(Ui.dpi(this, 170),
                LinearLayout.LayoutParams.WRAP_CONTENT);
        spinParams.leftMargin = Ui.dpi(this, 8);
        // El selector de tipo se ha quitado de este menú para despejarlo: el tipo de
        // contenido se elige en AJUSTES (y en los ajustes del reproductor, canal a
        // canal). Aquí se usa el que esté guardado en AJUSTES al pulsar REPRODUCIR.

        buttonPlay = Ui.primaryButton(this, "Reproducir");
        buttonPlay.setContentDescription("Reproducir el enlace escrito");
        LinearLayout.LayoutParams p1 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p1.leftMargin = Ui.dpi(this, 8);
        buttonPlay.setLayoutParams(p1);
        buttonPlay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onPlayUrl();
            }
        });
        row.addView(buttonPlay);

        buttonAddList = Ui.actionButton(this, "Añadir lista");
        buttonAddList.setContentDescription("Guardar el enlace como lista de canales");
        LinearLayout.LayoutParams p2 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p2.leftMargin = Ui.dpi(this, 8);
        buttonAddList.setLayoutParams(p2);
        buttonAddList.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onAddList();
            }
        });
        // «Añadir lista» NO se muestra en el menú de canales: vive solo dentro de
        // AJUSTES («Añadir enlace» + lista de listas guardadas). El botón se sigue
        // creando para no romper el modo compacto, pero no se añade a esta fila.
        return row;
    }

    private View buildSearchRow() {
        searchRow = new LinearLayout(this);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        searchRow.setBackgroundColor(Ui.HEADER);
        searchRow.setPadding(Ui.dpi(this, 12), Ui.dpi(this, 6), Ui.dpi(this, 12), Ui.dpi(this, 6));
        searchRow.setVisibility(View.GONE);

        searchField = new EditText(this);
        searchField.setHint("Buscar canal por nombre o enlace");
        searchField.setHintTextColor(Ui.TEXT_MUTED);
        searchField.setTextColor(Ui.TEXT);
        searchField.setTextSize(13f);
        searchField.setSingleLine(true);
        searchField.setBackground(Ui.roundedStroke(this, Ui.BG, Ui.RED_DEEP, 1f, 6f));
        searchField.setPadding(Ui.dpi(this, 10), Ui.dpi(this, 8), Ui.dpi(this, 10), Ui.dpi(this, 8));
        searchField.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                searchQuery = s == null ? "" : s.toString().trim();
                refreshFilter();
            }
        });
        searchRow.addView(searchField, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView close = Ui.actionButton(this, "Cerrar");
        close.setContentDescription("Cerrar la búsqueda");
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = Ui.dpi(this, 8);
        close.setLayoutParams(lp);
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hideSearch();
            }
        });
        searchRow.addView(close);
        return searchRow;
    }

    private View buildBody() {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);

        LinearLayout rail = new LinearLayout(this);
        rail.setOrientation(LinearLayout.VERTICAL);
        rail.setBackgroundColor(RogStyle.RAIL_PANEL);
        rail.setPadding(Ui.dpi(this, 8), Ui.dpi(this, 10), Ui.dpi(this, 8), Ui.dpi(this, 8));
        railBox = rail;

        TextView railTitle = Ui.text(this, "CATEGORÍAS", 11f, Ui.RED, true);
        railTitle.setLetterSpacing(0.12f);
        rail.addView(railTitle);
        // La referencia no lleva título encima de las categorías.
        railTitle.setVisibility(View.GONE);

        categoryAdapter = new RogCategoryAdapter(this, categoryNames, categoryCounts,
                new RogCategoryAdapter.Selected() {
                    public String id() {
                        return selectedCategory;
                    }
                });
        categoryList = new ListView(this);
        categoryList.setAdapter(categoryAdapter);
        categoryList.setDivider(null);
        categoryList.setDividerHeight(Ui.dpi(this, 4));
        categoryList.setPadding(0, Ui.dpi(this, 6), 0, Ui.dpi(this, 6));
        categoryList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (position < 0 || position >= categoryNames.size()) return;
                selectedCategory = categoryNames.get(position);
                categoryAdapter.notifyDataSetChanged();
                refreshFilter();
                if (settingsOverlay != null) closeSettings();
            }
        });
        rail.addView(categoryList, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        playlistView = Ui.text(this, "Sin lista activa", 10f, Ui.TEXT_MUTED, false);
        playlistView.setPadding(0, Ui.dpi(this, 6), 0, 0);
        rail.addView(playlistView);
        // La referencia no lleva el selector de lista en esta columna: las listas
        // se gestionan solo en AJUSTES.
        playlistView.setVisibility(View.GONE);

        body.addView(rail, new LinearLayout.LayoutParams(Ui.dpi(this, 190),
                LinearLayout.LayoutParams.MATCH_PARENT));

        FrameLayout content = new FrameLayout(this);
        // Fondo Lenny en sillón en la zona de canales (no en categorías).
        RogStyle.applyBackdrop(this, content);
        contentArea = content;

        channelAdapter = new RogChannelAdapter(this, shown);
        grid = new GridView(this);
        grid.setAdapter(channelAdapter);
        grid.setNumColumns(6);
        // Más aire entre tarjetas: la cuadrícula se veía apretada.
        grid.setHorizontalSpacing(Ui.dpi(this, 14));
        grid.setVerticalSpacing(Ui.dpi(this, 14));
        grid.setPadding(Ui.dpi(this, 12), Ui.dpi(this, 10), Ui.dpi(this, 12), Ui.dpi(this, 10));
        grid.setClipToPadding(false);
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < shown.size()) openPlayer(shown.get(position));
            }
        });
        content.addView(grid, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        View empty = buildEmptyView();
        emptyView = empty;
        content.addView(empty, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        // A propósito NO se usa grid.setEmptyView(empty): ese método deja que el
        // propio componente decida cuándo ocultar la cuadrícula, y era una de las
        // formas en que la pantalla se quedaba vacía con los canales cargados.
        // Ahora la visibilidad la controla únicamente updateEmptyState().
        watchGridLayout();
        recalcGridColumns();

        body.addView(content, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        return body;
    }

    /** Ajusta el número de columnas de la cuadrícula al ancho real disponible. */
    private void recalcGridColumns() {
        if (grid == null) return;
        grid.post(new Runnable() {
            @Override
            public void run() {
                int width = grid.getWidth();
                if (width <= 0) return;
                // La referencia muestra SIEMPRE seis columnas de canales.
                int cols = 6;
                if (cols != grid.getNumColumns()) grid.setNumColumns(cols);
            }
        });
    }

    // ------------------------------- comprobación REAL de la cuadrícula

    /**
     * Mide la cuadrícula TAL COMO ESTÁ EN PANTALLA. No se fía del número de
     * canales del adaptador: mira el hueco que ocupa, cuántas tarjetas ha
     * dibujado Android de verdad, el estado de cada vista y si algún cartel o
     * panel la está tapando. Así el registro nunca vuelve a decir "1.147 en
     * pantalla" mientras la pantalla está vacía.
     */
    private void auditGrid(String where) {
        if (grid == null) return;
        gridAuditCount++;
        int width = grid.getWidth();
        int height = grid.getHeight();
        int drawn = grid.getChildCount();
        StringBuilder sb = new StringBuilder();
        sb.append("CUADRÍCULA (").append(where).append("): ").append(stateName(grid.getVisibility()));
        sb.append(" · tamaño ").append(width).append('x').append(height).append(" px (")
                .append(pxToDp(width)).append('x').append(pxToDp(height)).append(" dp)");
        sb.append(" · canales que pasan el filtro ").append(shown.size());
        sb.append(" · tarjetas dibujadas ").append(drawn);
        if (drawn > 0) {
            sb.append(" (de la ").append(grid.getFirstVisiblePosition() + 1)
                    .append(" a la ").append(grid.getLastVisiblePosition() + 1).append(')');
        }
        sb.append(" · cartel de sin canales ").append(emptyView == null
                ? "sin crear" : stateName(emptyView.getVisibility()));
        sb.append(" · tapando: ").append(coveringReport());
        sb.append(" · cabecera ").append(heightDp(headerBox)).append(" dp de una pantalla de ")
                .append(pxToDp(root == null ? 0 : root.getHeight())).append(" dp");
        sb.append(" · hueco de los canales ").append(contentArea == null
                ? "(sin medir)" : pxToDp(contentArea.getHeight()) + " dp");
        boolean offScreen = cardsOffScreen(drawn);
        if (drawn > 0) {
            View first = grid.getChildAt(0);
            sb.append(" · primera tarjeta desde y=").append(first == null ? "?" : String.valueOf(first.getTop()));
            if (offScreen) sb.append(" FUERA DEL TROZO VISIBLE");
        }
        Diag.add(sb.toString());
        int cards = Math.min(3, drawn);
        for (int i = 0; i < cards; i++) {
            View card = grid.getChildAt(i);
            Diag.add("   tarjeta " + (i + 1) + ": " + card.getWidth() + 'x' + card.getHeight()
                    + " px · " + stateName(card.getVisibility()) + describeCard(card));
        }
        repairGridIfNeeded(where, drawn, height);
    }

    /** Estado del nombre y del logo de una tarjeta, para saber por qué no se ve. */
    private String describeCard(View card) {
        StringBuilder sb = new StringBuilder();
        Object tag = card.getTag();
        if (!(tag instanceof Object[])) {
            sb.append(" · (tarjeta sin datos)");
        } else {
            Object[] holder = (Object[]) tag;
            if (holder.length > 1 && holder[1] instanceof TextView) {
                TextView nameView = (TextView) holder[1];
                String value = nameView.getText() == null ? "" : nameView.getText().toString();
                if (value.length() > 14) value = value.substring(0, 14) + "…";
                sb.append(" · nombre \"").append(value).append("\" · color del texto #")
                        .append(Integer.toHexString(nameView.getCurrentTextColor()));
            }
            if (holder.length > 0 && holder[0] instanceof ImageView) {
                ImageView logoView = (ImageView) holder[0];
                sb.append(" · logo ").append(logoView.getWidth()).append('x').append(logoView.getHeight())
                        .append(logoView.getDrawable() == null ? " sin imagen" : " con imagen");
            }
        }
        sb.append(" · alfa ").append(Float.toString(card.getAlpha()));
        sb.append(" · fondo ").append(card.getBackground() == null ? "ninguno" : "puesto");
        return sb.toString();
    }

    /** Qué paneles o carteles están tapando la cuadrícula en este momento. */
    private String coveringReport() {
        StringBuilder sb = new StringBuilder();
        if (settingsOverlay != null && settingsOverlay.getVisibility() == View.VISIBLE) sb.append("panel AJUSTES, ");
        if (addOverlay != null && addOverlay.getVisibility() == View.VISIBLE) sb.append("panel PLAYLIST, ");
        if (diagOverlay != null && diagOverlay.getVisibility() == View.VISIBLE) sb.append("panel DIAGNÓSTICO, ");
        if (emptyView != null && emptyView.getVisibility() == View.VISIBLE) sb.append("cartel de sin canales, ");
        if (sb.length() == 0) return "nada";
        return sb.substring(0, sb.length() - 2);
    }

    private static String stateName(int visibility) {
        if (visibility == View.VISIBLE) return "visible";
        if (visibility == View.INVISIBLE) return "invisible (INVISIBLE)";
        if (visibility == View.GONE) return "oculta (GONE)";
        return "estado " + visibility;
    }

    private int pxToDp(int px) {
        float density = getResources().getDisplayMetrics().density;
        if (density <= 0f) return px;
        return Math.round(px / density);
    }

    /**
     * true si las tarjetas existen pero están colocadas fuera del trozo visible
     * de la cuadrícula (pantalla aparentemente vacía con tarjetas creadas).
     */
    private boolean cardsOffScreen(int drawn) {
        if (grid == null || drawn <= 0) return false;
        View first = grid.getChildAt(0);
        View last = grid.getChildAt(drawn - 1);
        if (first == null || last == null) return false;
        if (grid.getHeight() <= 0) return true;
        return last.getBottom() <= 0 || first.getTop() >= grid.getHeight();
    }

    /**
     * Si el adaptador tiene canales pero la cuadrícula no dibuja ninguna tarjeta,
     * o está oculta, o el hueco es demasiado bajo, se corrige y se vuelve a
     * medir. Antes esto no se comprobaba: el registro decía que todo estaba bien
     * mientras la pantalla seguía vacía.
     */
    private void repairGridIfNeeded(String where, int drawn, int height) {
        if (grid == null) return;
        if (shown.isEmpty()) {
            gridRepairTries = 0;
            return;
        }
        boolean hidden = grid.getVisibility() != View.VISIBLE;
        boolean noCards = drawn == 0;
        boolean tooShort = height < Ui.dpi(this, 40);
        boolean offScreen = cardsOffScreen(drawn);
        if (tooShort || offScreen) {
            // ANTES de tocar la cuadrícula se corrige lo que se está quedando con
            // el sitio (la cabecera con la portada): una cuadrícula de 0 px de alto
            // no se arregla cambiando la visibilidad ni pidiendo otra medición.
            if (limitTallHeader("la cuadrícula mide " + pxToDp(height) + " dp de alto")) return;
            // Último recurso: si aun así el hueco sigue siendo inutilizable, se
            // retiran los dos bloques que ya quedaban fuera de la pantalla para
            // que la cuadrícula tenga alto de verdad (se avisa en el registro).
            if (tooShort && giveGridRoom("la cuadrícula mide " + pxToDp(height) + " dp de alto")) return;
        }
        if (!hidden && !noCards && !tooShort && !offScreen) {
            gridRepairTries = 0;
            if (gridProblemShown) {
                gridProblemShown = false;
                Diag.add("CUADRÍCULA: ya se ven las tarjetas (" + drawn + " dibujadas).");
                updateEmptyState();
            }
            return;
        }
        if (gridRepairTries >= 3) {
            Diag.add("CUADRÍCULA: después de 3 correcciones sigue sin verse ninguna tarjeta.");
            showGridProblem();
            return;
        }
        gridRepairTries++;
        Diag.add("CUADRÍCULA · corrección " + gridRepairTries + " (" + where + "): "
                + (hidden ? "la cuadrícula estaba oculta; " : "")
                + (noCards ? "no se dibujaba ninguna tarjeta; " : "")
                + (offScreen ? "las tarjetas estaban fuera del trozo visible; " : "")
                + (tooShort ? "el hueco era de solo " + pxToDp(height) + " dp; " : "")
                + "se pone visible, se retira el cartel y se vuelve a ordenar.");
        if (emptyView != null) emptyView.setVisibility(View.GONE);
        grid.setVisibility(View.VISIBLE);
        if (tooShort) setCompactMode(true);
        if (channelAdapter != null) channelAdapter.notifyDataSetChanged();
        grid.setSelection(0);
        grid.requestLayout();
        final int attempt = gridRepairTries;
        final String fromWhere = where;
        grid.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (attempt != gridRepairTries) return;
                auditGrid("después de la corrección " + attempt + " · " + fromWhere);
            }
        }, 500L);
    }

    /** Aviso honesto: hay canales, pero este teléfono no los está dibujando. */
    private void showGridProblem() {
        gridProblemShown = true;
        int free = contentArea == null ? 0 : pxToDp(contentArea.getHeight());
        lastLoadProblem = "La lista tiene " + all.size() + " canales, pero este teléfono no consigue dibujar las"
                + " tarjetas en el hueco disponible (" + free + " dp). Pulsa COPIAR MOTIVO y envíame ese texto:"
                + " incluye el tamaño de la cuadrícula y de cada zona para poder ajustarlo a tu teléfono.";
        Diag.add("CUADRÍCULA: " + lastLoadProblem);
        Diag.save(this);
        updateEmptyState();
    }

    /** Comprueba si la cuadrícula tiene hueco suficiente para los canales. */
    private void checkContentRoom() {
        if (grid == null || contentArea == null) return;
        int free = contentArea.getHeight();
        reportLayoutChain(compactMode ? "cabecera apretada" : "tamaño normal");
        if (free <= 0) {
            // La cuadrícula se ha quedado SIN ALTO. En este teléfono la causa
            // medida es la cabecera, que ocupaba la pantalla entera por la portada
            // decorativa: si vuelve a pasar, se corrige aquí y se vuelve a medir.
            Diag.add("Hueco para los canales: 0 dp · la cuadrícula se ha quedado sin alto"
                    + " (cabecera " + pxToDp(headerBox == null ? 0 : headerBox.getHeight())
                    + " dp de una pantalla de " + pxToDp(root == null ? 0 : root.getHeight()) + " dp).");
            limitTallHeader("la cuadrícula se quedó sin alto");
            return;
        }
        Diag.add("Hueco para los canales: " + pxToDp(free) + " dp · cabecera "
                + pxToDp(headerBar == null ? 0 : headerBar.getHeight()) + " dp · campo de enlace "
                + pxToDp(urlRow == null ? 0 : urlRow.getHeight()) + " dp · barra inferior "
                + pxToDp(statusBar == null ? 0 : statusBar.getHeight()) + " dp");
        setCompactMode(free < Ui.dpi(this, 110));
        restoreBarsIfRoom();
    }

    /**
     * Alto de la portada decorativa de la cabecera, medido en dp y de forma
     * LIMITADA (54-74 dp). Antes se colocaba con MATCH_PARENT y, al ser una
     * imagen más ancha que la pantalla, Android la medía con todo el alto
     * disponible: la cabecera ocupaba 360 dp (la pantalla completa), empujaba el
     * campo de enlace y la barra inferior fuera de la pantalla y dejaba la
     * cuadrícula con 0 px de alto.
     */
    private int headerArtDp() {
        return compactMode ? 54 : 74;
    }

    private void applyHeaderCoverHeight() {
        if (headerCover == null) return;
        ViewGroup.LayoutParams params = headerCover.getLayoutParams();
        if (params == null) return;
        int wanted = Ui.dpi(this, headerArtDp());
        if (params.height != wanted) {
            params.height = wanted;
            headerCover.setLayoutParams(params);
        }
    }

    /**
     * Comprueba que la cabecera no se esté quedando con la pantalla. Si ocupa más
     * de lo razonable (más del 30 % del alto total), se limita la portada, se
     * aprieta la cabecera y se vuelve a medir para comprobar el tamaño FINAL en
     * el siguiente ciclo de diseño de Android: no se da nada por corregido solo
     * por pedir otra medición.
     */
    private boolean limitTallHeader(final String where) {
        if (headerBox == null || root == null) return false;
        int screen = root.getHeight();
        int header = headerBox.getHeight();
        if (screen <= 0 || header <= 0) return false;
        // Portada de cabecera más baja: deja más alto a la cuadrícula de canales y
        // despeja la pantalla (antes llegaba al 30 % del alto).
        int max = Math.max(Ui.dpi(this, 56), Math.round(screen * 0.20f));
        if (header <= max) {
            headerLimitTries = 0;
            return false;
        }
        if (headerLimitTries >= 3) {
            Diag.add("CABECERA: sigue ocupando " + pxToDp(header) + " dp de " + pxToDp(screen)
                    + " dp después de 3 intentos de reducirla. Aviso en pantalla.");
            return false;
        }
        headerLimitTries++;
        Diag.add("CABECERA · corrección " + headerLimitTries + " (" + where + "): ocupaba "
                + header + " px (" + pxToDp(header) + " dp) de una pantalla de " + pxToDp(screen)
                + " dp y como máximo debe ocupar " + pxToDp(max) + " dp. Se limita la portada a "
                + headerArtDp() + " dp y se aprieta la cabecera.");
        applyHeaderCoverHeight();
        setCompactMode(true);
        applyHeaderCoverHeight();
        headerBox.requestLayout();
        root.requestLayout();
        if (grid != null) grid.requestLayout();
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                checkContentRoom();
                if (grid != null) auditGrid("después de limitar la cabecera · " + where);
            }
        }, 400L);
        return true;
    }

    /**
     * Último recurso, solo cuando la cuadrícula no tiene NADA de alto utilizable:
     * se retiran de la pantalla el campo de enlace y la barra inferior (que en esa
     * situación ya quedaban fuera) para devolver el hueco a los canales. Se anota
     * en el registro y se vuelven a mostrar solos en cuanto hay sitio de sobra.
     */
    private boolean giveGridRoom(String where) {
        if (contentArea == null) return false;
        if (contentArea.getHeight() > 0) return false;
        if (barsHiddenForRoom) return false;
        if (urlRow == null && statusBar == null) return false;
        // Como mucho se hace dos veces: así nunca puede quedarse dando vueltas
        // mostrando y ocultando las barras.
        if (barsRoomTries >= 2) return false;
        barsRoomTries++;
        barsHiddenForRoom = true;
        if (urlRow != null) urlRow.setVisibility(View.GONE);
        if (statusBar != null) statusBar.setVisibility(View.GONE);
        Diag.add("Hueco de los canales 0 dp (" + where + "): se retiran de la pantalla el campo de"
                + " enlace y la barra inferior (ya no se veían) para que la cuadrícula tenga alto."
                + " Se vuelven a mostrar solos en cuanto haya sitio de sobra.");
        if (columnBox != null) columnBox.requestLayout();
        if (root != null) root.requestLayout();
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                checkContentRoom();
                if (grid != null) auditGrid("después de liberar sitio para los canales");
            }
        }, 400L);
        return true;
    }

    /** Devuelve el campo de enlace y la barra inferior cuando ya sobra sitio. */
    private void restoreBarsIfRoom() {
        if (!barsHiddenForRoom) return;
        if (contentArea == null) return;
        int free = contentArea.getHeight();
        // Se exige sitio de sobra para que, al volver las barras, la cuadrícula
        // siga teniendo alto: así no se queda mostrando y ocultando sin parar.
        int needed = Ui.dpi(this, 220);
        if (free < needed) return;
        barsHiddenForRoom = false;
        if (urlRow != null && lenyUrlShown) urlRow.setVisibility(View.VISIBLE);
        // REVISIÓN 50: la línea de texto inferior ya no se vuelve a mostrar nunca.
        if (statusBar != null) statusBar.setVisibility(View.GONE);
        Diag.add("Ya hay sitio para los canales (" + pxToDp(free)
                + " dp): vuelven a mostrarse el campo de enlace y la barra inferior.");
        if (columnBox != null) columnBox.requestLayout();
    }

    /** Deja en el registro el reparto REAL de alturas de la pantalla. */
    private void reportLayoutChain(String where) {
        if (root == null) return;
        Diag.add("REPARTO DE LA PANTALLA (" + where + ") · alto total " + pxToDp(root.getHeight())
                + " dp · columna " + heightDp(columnBox) + " dp · cabecera " + heightDp(headerBox)
                + " dp (portada " + heightDp(headerCover) + " dp) · campo de enlace " + heightDp(urlRow)
                + " dp · búsqueda " + heightDp(searchRow) + " dp · canales " + heightDp(contentArea)
                + " dp · barra inferior " + heightDp(statusBar) + " dp");
    }

    private int heightDp(View view) {
        return view == null ? -1 : pxToDp(view.getHeight());
    }

    /**
     * Vuelve a ordenar la pantalla después de cambiar de lista: retira el cartel
     * de "sin canales" cuando ya hay elementos y pide una medición nueva, porque
     * el hueco de los canales depende de lo que midan los demás bloques.
     */
    private void requestScreenRelayout(String where) {
        gridProblemShown = false;
        headerLimitTries = 0;
        if (emptyView != null) emptyView.setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);
        updateEmptyState();
        if (grid != null) grid.requestLayout();
        if (contentArea != null) contentArea.requestLayout();
        if (columnBox != null) columnBox.requestLayout();
        if (root != null) root.requestLayout();
        Diag.add("Se vuelve a ordenar la pantalla (" + where + "): "
                + (shown.isEmpty() ? "no hay canales que mostrar"
                : "hay " + shown.size() + " canales y se retira el cartel de sin canales") + ".");
    }

    /**
     * Aprieta la cabecera, el campo de enlace y la barra inferior cuando la
     * pantalla deja poco hueco (letra o tamaño de pantalla muy grandes). El
     * diseño, los menús y la navegación no cambian: solo se reduce lo justo para
     * que la cuadrícula tenga sitio y los canales se vean.
     */
    private void setCompactMode(boolean compact) {
        boolean changed = compact != compactMode;
        compactMode = compact;
        applyCompactValues();
        if (changed) {
            Diag.add(compact
                    ? "Se aprieta la cabecera (modo compacto) porque el hueco para los canales es pequeño."
                    : "Vuelve el tamaño normal de la cabecera: ya hay hueco suficiente para los canales.");
        }
    }

    private void applyCompactValues() {
        boolean compact = compactMode;
        if (titleView != null) titleView.setTextSize(compact ? 18f : 26f);
        if (subtitleView != null) subtitleView.setVisibility(compact ? View.GONE : View.VISIBLE);
        if (headerBar != null) {
            headerBar.setPadding(Ui.dpi(this, compact ? 10 : 14) + Math.max(0, insetLeft),
                    Ui.dpi(this, compact ? 4 : 8) + Math.max(0, insetTop),
                    Ui.dpi(this, compact ? 10 : 14) + Math.max(0, insetRight),
                    Ui.dpi(this, compact ? 4 : 8));
        }
        if (urlRow != null) {
            urlRow.setPadding(Ui.dpi(this, compact ? 8 : 12), Ui.dpi(this, compact ? 4 : 8),
                    Ui.dpi(this, compact ? 8 : 12), Ui.dpi(this, compact ? 4 : 8));
        }
        if (urlField != null) urlField.setTextSize(compact ? 12f : 13f);
        if (buttonPlay != null) buttonPlay.setMinHeight(Ui.dpi(this, compact ? 34 : 44));
        if (buttonAddList != null) buttonAddList.setMinHeight(Ui.dpi(this, compact ? 34 : 44));
        if (statusBar != null) {
            statusBar.setPadding(Ui.dpi(this, 12), Ui.dpi(this, compact ? 3 : 6),
                    Ui.dpi(this, 12), Ui.dpi(this, compact ? 3 : 6));
        }
        if (statusView != null) {
            statusView.setTextSize(compact ? 11f : 13f);
            statusView.setMaxLines(compact ? 1 : 3);
        }
        cardLogoDp = compact ? 38 : 52;
        cardNameDp = compact ? 26 : 34;
        cardPadDp = compact ? 6 : 8;
        if (grid != null) {
            grid.setHorizontalSpacing(Ui.dpi(this, compact ? 6 : 10));
            grid.setVerticalSpacing(Ui.dpi(this, compact ? 6 : 10));
            grid.setPadding(Ui.dpi(this, compact ? 8 : 12), Ui.dpi(this, compact ? 6 : 10),
                    Ui.dpi(this, compact ? 8 : 12), Ui.dpi(this, compact ? 6 : 10));
        }
        if (railBox != null) {
            ViewGroup.LayoutParams params = railBox.getLayoutParams();
            if (params != null) {
                params.width = Ui.dpi(this, compact ? 148 : 190);
                railBox.setLayoutParams(params);
            }
        }
        applyHeaderCoverHeight();
        applyCardSize();
        if (grid != null) grid.requestLayout();
    }

    /** Ajusta las tarjetas ya dibujadas al tamaño que toca ahora. */
    private void applyCardSize() {
        if (grid == null) return;
        int logoSize = Ui.dpi(this, cardLogoDp);
        int nameSize = Ui.dpi(this, cardNameDp);
        int pad = Ui.dpi(this, cardPadDp);
        for (int i = 0; i < grid.getChildCount(); i++) {
            View card = grid.getChildAt(i);
            Object tag = card.getTag();
            if (tag instanceof Object[]) {
                Object[] holder = (Object[]) tag;
                if (holder.length > 0 && holder[0] instanceof View) {
                    View logoView = (View) holder[0];
                    ViewGroup.LayoutParams logoParams = logoView.getLayoutParams();
                    if (logoParams != null) {
                        logoParams.width = logoSize;
                        logoParams.height = logoSize;
                        logoView.setLayoutParams(logoParams);
                    }
                }
                if (holder.length > 1 && holder[1] instanceof View) {
                    View nameView = (View) holder[1];
                    ViewGroup.LayoutParams nameParams = nameView.getLayoutParams();
                    if (nameParams != null) {
                        nameParams.height = nameSize;
                        nameView.setLayoutParams(nameParams);
                    }
                }
            }
            if (card instanceof LinearLayout) ((LinearLayout) card).setPadding(pad, pad, pad, pad);
        }
    }

    /** Mide la cuadrícula en cuanto Android la coloca de verdad en pantalla. */
    private void watchGridLayout() {
        if (grid == null) return;
        grid.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                if (grid == null || layoutSeen) return;
                layoutSeen = true;
                ViewTreeObserver observer = grid.getViewTreeObserver();
                if (observer.isAlive()) observer.removeOnGlobalLayoutListener(this);
                Diag.add("CUADRÍCULA: Android ya la ha colocado en pantalla (medida "
                        + grid.getWidth() + 'x' + grid.getHeight() + " px).");
                limitTallHeader("primera medida real");
                checkContentRoom();
                scheduleGridAudit("primera medida real");
            }
        });
    }

    /** Vuelve a medir la cuadrícula un instante después de cargar canales. */
    private void scheduleGridAudit(final String where) {
        if (grid == null) return;
        grid.postDelayed(new Runnable() {
            @Override
            public void run() {
                gridRepairTries = 0;
                auditGrid(where);
            }
        }, 700L);
    }

    private View buildEmptyView() {
        // Este cartel se ve cuando no hay ningún canal. Antes era una columna más
        // alta que la pantalla y, en horizontal, el motivo del fallo y los botones
        // quedaban recortados fuera: el usuario veía la cuadrícula vacía "sin que
        // dijera nada". Ahora se puede desplazar y el motivo va en un recuadro.
        ScrollView scroller = new ScrollView(this);
        scroller.setFillViewport(true);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(Ui.dpi(this, 18), Ui.dpi(this, 8), Ui.dpi(this, 18), Ui.dpi(this, 8));

        Bitmap cover = Art.generated(this, "portada.png");
        if (cover != null) {
            ImageView art = new ImageView(this);
            art.setImageBitmap(cover);
            art.setScaleType(ImageView.ScaleType.CENTER_CROP);
            int w = Ui.dpi(this, 160);
            int h = Math.max(Ui.dpi(this, 36), Math.round(w * ((float) cover.getHeight() / cover.getWidth())));
            art.setLayoutParams(new LinearLayout.LayoutParams(w, Math.min(h, Ui.dpi(this, 64))));
            box.addView(art);
        }

        emptyTitle = Ui.text(this, "Todavía no hay canales", 16f, Ui.TEXT, true);
        emptyTitle.setGravity(Gravity.CENTER);
        emptyTitle.setPadding(0, Ui.dpi(this, 6), 0, 0);
        box.addView(emptyTitle);

        emptyBody = Ui.text(this, "", 13f, Ui.TEXT, false);
        emptyBody.setGravity(Gravity.CENTER);
        emptyBody.setBackground(Ui.roundedStroke(this, 0xFF12121C, Ui.WARN, 1.5f, 8f));
        emptyBody.setPadding(Ui.dpi(this, 10), Ui.dpi(this, 8), Ui.dpi(this, 10), Ui.dpi(this, 8));
        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        bodyParams.topMargin = Ui.dpi(this, 6);
        box.addView(emptyBody, bodyParams);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.VERTICAL);
        actions.setGravity(Gravity.CENTER_HORIZONTAL);

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setGravity(Gravity.CENTER);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setGravity(Gravity.CENTER);

        emptyDemoButton = Ui.primaryButton(this, "Cargar lista de demostración");
        emptyDemoButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showDemoList();
            }
        });
        row1.addView(emptyDemoButton);

        emptyPlay = Ui.primaryButton(this, "Reproducir el enlace");
        emptyPlay.setContentDescription("Abrir el enlace de la lista activa en el reproductor");
        LinearLayout.LayoutParams playParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        playParams.leftMargin = Ui.dpi(this, 8);
        emptyPlay.setLayoutParams(playParams);
        emptyPlay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                playActiveLink();
            }
        });
        row1.addView(emptyPlay);

        emptySettingsButton = Ui.actionButton(this, "Abrir ajustes");
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = Ui.dpi(this, 8);
        emptySettingsButton.setLayoutParams(lp);
        emptySettingsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSettings();
            }
        });
        row2.addView(emptySettingsButton);

        emptyDiag = Ui.actionButton(this, "Diagnóstico");
        emptyDiag.setContentDescription("Ver el registro de lo que ha hecho la app al cargar la lista");
        LinearLayout.LayoutParams diagParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        diagParams.leftMargin = Ui.dpi(this, 8);
        emptyDiag.setLayoutParams(diagParams);
        emptyDiag.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showDiagnostics();
            }
        });
        row2.addView(emptyDiag);

        emptyRetry = Ui.primaryButton(this, "Reintentar");
        emptyRetry.setContentDescription("Volver a cargar la lista activa");
        LinearLayout.LayoutParams retryParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        retryParams.leftMargin = Ui.dpi(this, 8);
        emptyRetry.setLayoutParams(retryParams);
        emptyRetry.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                refreshActiveList();
            }
        });
        row2.addView(emptyRetry);

        emptyCopy = Ui.actionButton(this, "Copiar motivo");
        emptyCopy.setContentDescription("Copiar el motivo del fallo para poder enviarlo");
        LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        copyParams.leftMargin = Ui.dpi(this, 8);
        emptyCopy.setLayoutParams(copyParams);
        emptyCopy.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyProblem();
            }
        });
        row2.addView(emptyCopy);

        actions.addView(row1);
        LinearLayout.LayoutParams row2Params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        row2Params.topMargin = Ui.dpi(this, 6);
        actions.addView(row2, row2Params);
        box.addView(actions);
        emptyActionsBar = actions;
        emptyActionsBar.setVisibility(View.GONE);
        scroller.addView(box);
        return scroller;
    }

    private View buildStatusBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Ui.HEADER);
        bar.setPadding(Ui.dpi(this, 12), Ui.dpi(this, 6), Ui.dpi(this, 12), Ui.dpi(this, 6));
        statusBar = bar;

        statusView = Ui.text(this, "Preparando…", 13f, Ui.TEXT_DIM, false);
        statusView.setMaxLines(3);
        bar.addView(statusView, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        statusSpinner = new ProgressBar(this);
        statusSpinner.setIndeterminate(true);
        statusSpinner.setIndeterminateTintList(ColorStateList.valueOf(Ui.RED));
        statusSpinner.setVisibility(View.GONE);
        bar.addView(statusSpinner, new LinearLayout.LayoutParams(Ui.dpi(this, 18), Ui.dpi(this, 18)));

        countView = Ui.text(this, "0 entradas", 10f, Ui.TEXT_MUTED, false);
        countView.setPadding(Ui.dpi(this, 10), 0, 0, 0);
        bar.addView(countView);
        return bar;
    }

    // ----------------------------------------------------------------- reloj

    private void startClock() {
        stopClock();
        updateClock();
        clockTick = new Runnable() {
            @Override
            public void run() {
                updateClock();
                ui.postDelayed(this, 1000);
            }
        };
        ui.postDelayed(clockTick, 1000);
    }

    private void stopClock() {
        if (clockTick != null) ui.removeCallbacks(clockTick);
    }

    private void updateClock() {
        long now = System.currentTimeMillis();
        if (clockView != null) {
            clockView.setText(new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date(now)));
        }
        if (dateView != null) {
            dateView.setText(new SimpleDateFormat("EEE dd MMM yyyy", new Locale("es", "ES")).format(new Date(now)));
        }
    }

    // ------------------------------------------------------------ listas

    private void startLoad(final boolean forceNetwork) {
        final java.util.ArrayList<PlaylistStore.Playlist> actives = PlaylistStore.activeLists(this);
        activePlaylist = actives.isEmpty() ? null : actives.get(0);
        long signature = 0L;
        for (int i = 0; i < actives.size(); i++) signature = signature * 31L + actives.get(i).id;
        boolean changedList = signature != loadedPlaylistId;
        if (changedList && loadedPlaylistId != -1L) {
            clearSearch();
            selectedCategory = CAT_ALL;
            Diag.add("Se cambia de lista(s): se limpian la búsqueda y la categoría anteriores.");
        }
        loadedPlaylistId = signature;
        if (actives.isEmpty()) {
            Diag.add("Paso 1 · lista activa: ninguna");
            setStatus("No hay ninguna lista. Abre AJUSTES para añadir una o carga la de demostración.", Ui.WARN);
            all.clear();
            shown.clear();
            rebuildCategories();
            refreshFilter();
            playlistView.setText("Sin lista activa");
            return;
        }
        if (actives.size() == 1) {
            Diag.add("Paso 1 · lista activa: " + actives.get(0).name + " (" + actives.get(0).kindLabel() + ")");
            playlistView.setText(actives.get(0).name);
        } else {
            Diag.add("Paso 1 · " + actives.size() + " listas activas a la vez");
            playlistView.setText(actives.size() + " listas");
        }
        pendingActiveId = activePlaylist.id;
        // Una sola lista: comportamiento anterior
        if (actives.size() == 1) {
            final PlaylistStore.Playlist pl = actives.get(0);
            String cached = PlaylistStore.readCache(this, pl);
            if (cached != null && cached.length() > 0) {
                Diag.add("Paso 2 · copia guardada en el teléfono: " + cached.length() + " caracteres");
                applyText(cached, ++loadToken, pl);
                if (forceNetwork && !pl.isDemo()) fetchPlaylist(pl, loadToken);
                return;
            }
            Diag.add("Paso 2 · todavía no hay copia guardada: se descarga ahora");
            applyText("", ++loadToken, pl);
            fetchPlaylist(pl, loadToken);
            return;
        }
        // Varias listas: se unen los canales de todas las activas
        loadMergedLists(actives, forceNetwork);
    }

    /**
     * Carga y fusiona varias listas activas en un solo catálogo de canales.
     * Usa la misma lectura que una lista sola (caché, archivo, Net.loadList)
     * para no perder cabeceras, ClearKey ni URLs relativas.
     */
    /** Nombres que en realidad son archivos de manifiesto/segmento, no títulos de canal. */
    private static boolean isWeakChannelName(String name) {
        if (name == null) return true;
        String n = name.trim().toLowerCase(java.util.Locale.US);
        if (n.length() == 0) return true;
        if (n.endsWith(".mpd") || n.endsWith(".m3u8") || n.endsWith(".m3u")
                || n.endsWith(".ts") || n.endsWith(".mp4") || n.endsWith(".m4s")) {
            return true;
        }
        if (n.equals("index") || n.startsWith("index.")
                || n.equals("cenc") || n.startsWith("cenc.")
                || n.equals("manifest") || n.startsWith("manifest.")
                || n.equals("playlist") || n.startsWith("playlist.")) {
            return true;
        }
        return false;
    }

    private void loadMergedLists(final java.util.ArrayList<PlaylistStore.Playlist> actives,
                                 final boolean forceNetwork) {
        final int token = ++loadToken;
        setBusy(true);
        setStatus("Cargando " + actives.size() + " listas…", Ui.TEXT_DIM);
        background.execute(new Runnable() {
            @Override
            public void run() {
                final ArrayList<Channel> merged = new ArrayList<Channel>();
                final StringBuilder names = new StringBuilder();
                final StringBuilder problems = new StringBuilder();
                for (int i = 0; i < actives.size(); i++) {
                    PlaylistStore.Playlist pl = actives.get(i);
                    if (names.length() > 0) names.append(" + ");
                    names.append(pl.name);
                    String text = null;
                    try {
                        if (pl.isDemo()) {
                            text = DemoData.text();
                        } else if (PlaylistStore.KIND_FILE.equals(pl.kind)) {
                            text = PlaylistStore.readCache(MainActivity.this, pl);
                            if (text == null || text.length() == 0 || forceNetwork) {
                                text = readUri(pl.ref);
                                if (text != null && text.length() > 0) {
                                    PlaylistStore.writeCache(MainActivity.this, pl, text);
                                }
                            }
                        } else {
                            text = PlaylistStore.readCache(MainActivity.this, pl);
                            if (text == null || text.length() == 0 || forceNetwork) {
                                if (!Net.online(MainActivity.this)) {
                                    if (text == null || text.length() == 0) {
                                        if (problems.length() > 0) problems.append(" · ");
                                        problems.append(pl.name).append(": sin conexión");
                                    }
                                } else {
                                    Net.LoadResult result = Net.loadList(pl.ref,
                                            AppPrefs.headers(MainActivity.this));
                                    if (result.ok()) {
                                        text = result.text;
                                        PlaylistStore.writeCache(MainActivity.this, pl, text);
                                    } else {
                                        if (problems.length() > 0) problems.append(" · ");
                                        problems.append(pl.name).append(": ")
                                                .append(result.error == null ? "error" : result.error);
                                    }
                                }
                            }
                        }
                    } catch (Throwable ex) {
                        if (problems.length() > 0) problems.append(" · ");
                        problems.append(pl.name).append(": ").append(Net.describe(ex));
                        Diag.add("Fusión · fallo en " + pl.name + ": " + Net.describe(ex));
                    }
                    if (text == null || text.length() == 0) {
                        Diag.add("Fusión · " + pl.name + " sin contenido, se omite");
                        continue;
                    }
                    M3uParser.Result parsed = M3uParser.parse(text, baseOf(pl));
                    // Un .m3u8 de un solo vídeo no es lista de canales: no se mezcla
                    if (parsed.mediaPlaylist) {
                        Diag.add("Fusión · " + pl.name + " es un vídeo HLS, no lista de canales; se omite");
                        if (problems.length() > 0) problems.append(" · ");
                        problems.append(pl.name).append(": es vídeo, no lista");
                        continue;
                    }
                    int before = merged.size();
                    int skippedWeak = 0;
                    for (int c = 0; c < parsed.channels.size(); c++) {
                        Channel ch = parsed.channels.get(c);
                        // Sin título real (cenc.mpd / index.m3u8): suele ser basura de un
                        // manifiesto, no un canal de la lista IPTV.
                        if (isWeakChannelName(ch.name)) {
                            skippedWeak++;
                            continue;
                        }
                        merged.add(ch);
                    }
                    pl.channels = parsed.channels.size();
                    if (skippedWeak > 0) {
                        Diag.add("Fusión · " + pl.name + ": omitidos " + skippedWeak
                                + " sin nombre de canal (mpd/m3u8)");
                    }
                    pl.updated = System.currentTimeMillis();
                    try { PlaylistStore.update(MainActivity.this, pl); } catch (Throwable ignored) {}
                    Diag.add("Fusión · " + pl.name + ": +" + (merged.size() - before)
                            + " canales (total " + merged.size() + ")");
                }
                final String title = names.toString();
                final String warn = problems.toString();
                final ArrayList<Channel> finalMerged = merged;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (token != loadToken) return;
                        setBusy(false);
                        setChannels(finalMerged, activePlaylist, false, "");
                        if (finalMerged.isEmpty()) {
                            setStatus("No se pudo unir las listas"
                                    + (warn.length() > 0 ? ": " + warn : ""), Ui.WARN);
                        } else {
                            setStatus("Listas unidas: " + title + " · " + finalMerged.size() + " entradas"
                                    + (warn.length() > 0 ? " (" + warn + ")" : ""), Ui.OK);
                        }
                    }
                });
            }
        });
    }

    private void refreshActiveList() {
        if (activePlaylist == null) {
            startLoad(false);
            return;
        }
        if (activePlaylist.isDemo()) {
            applyText(DemoData.text(), ++loadToken, activePlaylist);
            setStatus("Lista de demostración recargada.", Ui.OK);
            return;
        }
        setStatus("Actualizando " + activePlaylist.name + "…", Ui.TEXT_DIM);
        fetchPlaylist(activePlaylist, loadToken);
    }

    private void fetchPlaylist(final PlaylistStore.Playlist pl, final int token) {
        setBusy(true);
        setStatus("Cargando " + pl.name + "…", Ui.TEXT_DIM);
        background.execute(new Runnable() {
            @Override
            public void run() {
                String text = null;
                String error = null;
                String snippet = null;
                String usedUserAgent = null;
                String usedReferer = null;
                String note = null;
                try {
                    if (pl.isDemo()) {
                        text = DemoData.text();
                    } else if (PlaylistStore.KIND_FILE.equals(pl.kind)) {
                        text = readUri(pl.ref);
                    } else {
                        if (!Net.online(MainActivity.this)) {
                            Diag.add("Paso 3 · la app cree que NO hay conexión a internet");
                            throw new IOException("no hay conexión a internet");
                        }
                        Diag.add("Paso 3 · internet disponible · descargando "
                                + Diag.shortUrl(pl.ref) + " con " + Net.describeHeaders(AppPrefs.headers(MainActivity.this)));
                        Net.LoadResult result = Net.loadList(pl.ref, AppPrefs.headers(MainActivity.this));
                        logAttempts(result.attempts);
                        if (result.ok()) {
                            Diag.add("Paso 4 · descarga correcta: " + result.text.length() + " caracteres"
                                    + (result.note == null ? "" : " · " + result.note));
                            text = result.text;
                            usedUserAgent = result.usedUserAgent;
                            usedReferer = result.usedReferer;
                            note = result.note;
                        } else {
                            Diag.add("Paso 4 · fallo de descarga: " + result.error);
                            error = result.error + (result.hint == null ? "" : " " + result.hint);
                            snippet = result.snippet;
                        }
                    }
                } catch (Throwable t) {
                    error = Net.describe(t);
                }
                final String loaded = text;
                final String failure = error;
                final String received = snippet;
                final String autoUserAgent = usedUserAgent;
                final String autoReferer = usedReferer;
                final String loadedNote = note;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (token != loadToken) return;
                        setBusy(false);
                        if (loaded != null && loaded.length() > 0) {
                            loadNote = loadedNote == null ? "" : loadedNote;
                            lastLoadProblem = "";
                            if (loadedNote != null) {
                                AppPrefs.setAutoHeaders(MainActivity.this, autoUserAgent, autoReferer);
                            }
                            PlaylistStore.writeCache(MainActivity.this, pl, loaded);
                            applyText(loaded, token, pl);
                        } else {
                            lastLoadProblem = "No se pudo cargar " + pl.name + ": " + failure;
                            String message = lastLoadProblem;
                            if (received != null && received.length() > 0) message = message + " · Recibido: " + received;
                            setStatus(message, Ui.WARN);
                            updateEmptyState();
                        }
                    }
                });
            }
        });
    }

    private void applyText(final String text, final int token, final PlaylistStore.Playlist pl) {
        if (text == null || text.length() == 0) {
            setChannels(new ArrayList<Channel>(), pl, false, "");
            return;
        }
        // La dirección de la lista permite completar los enlaces relativos.
        final String base = baseOf(pl);
        // Red de seguridad: si la lectura de una lista se quedara atascada, la app
        // lo dice con un motivo en vez de quedarse en silencio como antes.
        finishedParseToken = -1;
        armParseWatchdog(token);
        background.execute(new Runnable() {
            @Override
            public void run() {
                M3uParser.Result parsed = null;
                String failure = null;
                long started = android.os.SystemClock.elapsedRealtime();
                try {
                    parsed = M3uParser.parse(text, base);
                    long elapsed = android.os.SystemClock.elapsedRealtime() - started;
                    Diag.add("Paso 4 · lectura terminada en " + elapsed + " ms: "
                            + (parsed == null ? "sin datos" : parsed.summary()));
                    if (parsed != null) {
                        for (int i = 0; i < parsed.notes.size(); i++) {
                            Diag.add("   aviso del lector: " + parsed.notes.get(i));
                        }
                        if (parsed.channels.isEmpty() && parsed.firstLine.length() > 0) {
                            Diag.add("Paso 4 · la lista empieza así: " + parsed.firstLine);
                        }
                    }
                } catch (OutOfMemoryError oom) {
                    failure = "la lista es demasiado grande para la memoria de este teléfono";
                    Diag.add("Paso 4 · lectura fallida: " + failure);
                } catch (Throwable t) {
                    failure = "no se pudo leer la lista (" + Net.describe(t) + ")";
                    Diag.add("Paso 4 · lectura fallida: " + failure);
                }
                finishedParseToken = token;
                final M3uParser.Result result = parsed;
                final String problem = failure;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (token != loadToken) return;
                        if (result == null) {
                            lastLoadProblem = "La lista se descargó, pero " + problem + ".";
                            setChannels(new ArrayList<Channel>(), pl, false, "");
                            return;
                        }
                        setChannels(result.channels, pl, result.mediaPlaylist,
                                result.mediaPlaylist ? "" : result.reason());
                    }
                });
            }
        });
    }

    /** Dirección de la lista, para poder resolver los enlaces relativos que traiga. */
    private String baseOf(PlaylistStore.Playlist pl) {
        if (pl == null || pl.isDemo()) return null;
        String reference = pl.ref == null ? "" : pl.ref.trim();
        if (reference.indexOf("://") < 0) return null;
        return reference;
    }

    /**
     * Aviso de seguridad: si la lectura de una lista no hubiera terminado en
     * unos segundos, se muestra un motivo claro en pantalla (antes, un fallo del
     * lector dejaba la app esperando para siempre y sin decir nada).
     */
    private void armParseWatchdog(final int token) {
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (token != loadToken || token == finishedParseToken) return;
                if (isFinishing()) return;
                Diag.add("Aviso · la lectura de la lista superó "
                        + (PARSE_WATCHDOG_MS / 1000) + " segundos y no había terminado.");
                Diag.save(MainActivity.this);
                lastLoadProblem = "La lectura de esta lista está tardando demasiado (más de "
                        + (PARSE_WATCHDOG_MS / 1000) + " segundos). Puede ser un archivo enorme o dañado:"
                        + " pulsa REINTENTAR o prueba con otra lista.";
                setBusy(false);
                setStatus(lastLoadProblem, Ui.WARN);
                updateEmptyState();
            }
        }, PARSE_WATCHDOG_MS);
    }

    private void setChannels(ArrayList<Channel> list, PlaylistStore.Playlist pl, boolean mediaPlaylist,
                             String emptyReason) {
        all.clear();
        if (list != null) {
            if (mediaPlaylist) {
                all.addAll(list);
            } else {
                for (int i = 0; i < list.size(); i++) {
                    Channel ch = list.get(i);
                    if (ch == null) continue;
                    if (isWeakChannelName(ch.name)) continue;
                    all.add(ch);
                }
            }
        }
        boolean empty = all.isEmpty();

        // El motivo se decide ANTES de refrescar la pantalla: así el cartel de
        // "sin canales" explica ya el resultado real del último intento.
        if (mediaPlaylist) {
            String reference = pl != null && pl.ref != null ? pl.ref.trim() : "";
            activeLinkUrl = (reference.indexOf("://") > 0 && (pl == null || !pl.isDemo())) ? reference : "";
            lastLoadProblem = "Ese enlace es un vídeo HLS (.m3u8), no una lista de canales: sus direcciones"
                    + " son los trozos del mismo vídeo. Pulsa REPRODUCIR EL ENLACE para verlo.";
        } else if (empty) {
            activeLinkUrl = "";
            if (lastLoadProblem.length() == 0) {
                if (emptyReason != null && emptyReason.length() > 0) {
                    lastLoadProblem = "La lista se leyó, pero " + emptyReason + ".";
                } else if (pl != null && pl.isDemo()) {
                    lastLoadProblem = "La lista de demostración no se pudo leer.";
                } else {
                    lastLoadProblem = "La lista no contiene ninguna entrada de canal M3U.";
                }
            }
        } else {
            activeLinkUrl = "";
            lastLoadProblem = "";
            if (pl != null) {
                pl.channels = all.size();
                pl.updated = System.currentTimeMillis();
                PlaylistStore.update(this, pl);
                playlistView.setText(pl.name + " · " + all.size());
            }
        }

        gridProblemShown = false;
        gridRepairTries = 0;
        rebuildCategories();
        refreshFilter();
        if (grid != null) grid.setSelection(0);
        // Al cambiar de lista el diseño se vuelve a ordenar de verdad: se quita el
        // cartel de "sin canales" si ya hay elementos y se mide otra vez.
        requestScreenRelayout("cambio de lista");

        if (!empty && !mediaPlaylist) {
            int categories = categoryNames.size();
            setStatus("Listo: " + all.size() + " entradas y " + categories + " categorías cargadas."
                    + (loadNote.length() == 0 ? "" : " " + loadNote), Ui.OK);
        } else {
            setStatus(lastLoadProblem, Ui.WARN);
        }
        Diag.add("Paso 5 · lista: " + all.size() + " canales leídos, " + shown.size()
                + " pasan los filtros y se entregan al adaptador · " + categoryNames.size() + " categorías · categoría \""
                + selectedCategory + "\" · " + (searchQuery.length() == 0
                ? "sin búsqueda activa" : "búsqueda de " + searchQuery.length() + " caracteres")
                + " · filas del adaptador: " + (channelAdapter == null ? "sin adaptador" : String.valueOf(channelAdapter.getCount()))
                + (mediaPlaylist ? " · el enlace es un vídeo HLS" : "")
                + " (las tarjetas dibujadas se miden aparte, en la línea CUADRÍCULA)");
        // Aquí NO se da por hecho que los canales se vean: un instante después se
        // mide de verdad la cuadrícula y se anota lo que Android ha dibujado.
        scheduleGridAudit("después de cargar " + all.size() + " canales");
        if (lastLoadProblem.length() > 0) {
            updateEmptyState();
            Diag.save(this);
        }
    }

    private void rebuildCategories() {
        categoryNames.clear();
        categoryCounts.clear();
        // REVISIÓN 51: la columna ya NO empieza con «Todas». El usuario quiere ir
        // directamente a los apartados, así que la lista de categorías solo lleva
        // apartados reales de la lista M3U. «Todas» queda solo como estado interno
        // de reserva para cuando la lista no trae ninguna categoría.
        for (int i = 0; i < all.size(); i++) {
            String category = all.get(i).category;
            if (!categoryNames.contains(category)) categoryNames.add(category);
            Integer current = categoryCounts.get(category);
            categoryCounts.put(category, Integer.valueOf(current == null ? 1 : current.intValue() + 1));
        }
        if (!categoryNames.contains(selectedCategory)) {
            // Sin «Todas» en la columna: si la categoría guardada ya no existe se
            // abre el primer apartado de la lista (y solo si no hay ninguno se
            // vuelve al estado interno de «todos los canales»).
            selectedCategory = categoryNames.isEmpty() ? CAT_ALL : categoryNames.get(0);
        }
        if (categoryAdapter != null) categoryAdapter.notifyDataSetChanged();
    }

    private void refreshFilter() {
        shown.clear();
        for (int i = 0; i < all.size(); i++) {
            Channel channel = all.get(i);
            boolean categoryOk = CAT_ALL.equals(selectedCategory) || channel.category.equals(selectedCategory);
            boolean queryOk = searchQuery.length() == 0
                    || channel.name.toLowerCase(Locale.getDefault()).contains(searchQuery.toLowerCase(Locale.getDefault()))
                    || channel.url.toLowerCase(Locale.US).contains(searchQuery.toLowerCase(Locale.US));
            if (categoryOk && queryOk) shown.add(channel);
        }
        if (channelAdapter != null) channelAdapter.notifyDataSetChanged();
        if (countView != null) countView.setText(shown.size() + " en la lista · " + all.size() + " en total");
        // Queda anotado si son la búsqueda o la categoría las que dejan la
        // cuadrícula sin canales: así se distingue de una lista vacía de verdad.
        if (shown.isEmpty() && !all.isEmpty()) {
            String note = searchQuery.length() > 0
                    ? "la búsqueda escrita (" + searchQuery.length() + " caracteres) no coincide con ningún"
                    + " canal de los " + all.size() + " de la lista"
                    : "la categoría \"" + selectedCategory + "\" no tiene canales en esta lista de " + all.size();
            if (!note.equals(lastFilterNote)) {
                lastFilterNote = note;
                Diag.add("Ningún canal en pantalla porque " + note + ".");
            }
        } else {
            lastFilterNote = "";
        }
        updateEmptyState();
    }

    /** Limpia el texto de BUSCAR y vuelve a mostrar todos los canales de la lista. */
    private void clearSearch() {
        searchQuery = "";
        if (searchField != null && searchField.getText().length() > 0) searchField.setText("");
    }

    private void updateEmptyState() {
        if (emptyTitle == null) return;
        boolean nothingAtAll = all.isEmpty();
        if (gridProblemShown && !nothingAtAll) {
            // Hay canales, pero este teléfono no los está dibujando: se explica
            // con el motivo real en vez de dejar la pantalla en negro.
            emptyTitle.setText("Los canales no se pueden dibujar");
            emptyBody.setText("MOTIVO: " + lastLoadProblem);
        } else if (nothingAtAll) {
            if (busy) {
                emptyTitle.setText("Cargando la lista…");
            } else {
                emptyTitle.setText("Todavía no hay canales");
            }
            // El motivo real del fallo se muestra aquí, en grande: es la pista
            // necesaria para saber qué responde el proveedor.
            if (lastLoadProblem.length() > 0) {
                emptyBody.setText("MOTIVO: " + lastLoadProblem
                        + "\n\nPulsa COPIAR MOTIVO y envíame ese texto, o DIAGNÓSTICO para ver el detalle paso a paso.");
            } else if (busy) {
                emptyBody.setText("Descargando y leyendo la lista. En cuanto termine verás los canales aquí.");
            } else {
                emptyBody.setText("Añade tu propia lista M3U/M3U8 desde AJUSTES > Añadir enlace, o carga la lista de demostración incluida.");
            }
        } else {
            emptyTitle.setText("Ningún canal coincide");
            emptyBody.setText("Cambia de categoría o borra el texto de la búsqueda.");
        }
        if (emptyActionsBar != null) emptyActionsBar.setVisibility(View.GONE);
        if (emptyRetry != null) {
            emptyRetry.setVisibility(all.isEmpty() && activePlaylist != null ? View.VISIBLE : View.GONE);
        }
        if (emptyCopy != null) {
            emptyCopy.setVisibility(lastLoadProblem.length() > 0 ? View.VISIBLE : View.GONE);
        }
        if (emptyPlay != null) {
            emptyPlay.setVisibility(all.isEmpty() && activeLinkUrl.length() > 0 ? View.VISIBLE : View.GONE);
        }
        if (emptyDiag != null) emptyDiag.setVisibility(View.VISIBLE);
        // La visibilidad se controla aquí mismo para que la cuadrícula nunca
        // quede tapada por el cartel de "sin canales" cuando sí hay entradas.
        boolean showEmpty = shown.isEmpty() || gridProblemShown;
        if (emptyView != null) emptyView.setVisibility(showEmpty ? View.VISIBLE : View.GONE);
        if (grid != null) grid.setVisibility(showEmpty ? View.GONE : View.VISIBLE);
    }

    /**
     * Reparte los márgenes de la zona de la cámara frontal entre los contenedores
     * que tienen su propio color. Así el fondo llega hasta el borde (sin franja
     * vacía) y ningún texto queda debajo del orificio de la cámara.
     */
    private void applyScreenInsets(int left, int top, int right, int bottom) {
        if (left == insetLeft && top == insetTop && right == insetRight && bottom == insetBottom) return;
        insetLeft = left;
        insetTop = top;
        insetRight = right;
        insetBottom = bottom;
        if (headerBar != null) {
            headerBar.setPadding(Ui.dpi(this, 14) + left, Ui.dpi(this, 8) + top,
                    Ui.dpi(this, 14) + right, Ui.dpi(this, 8));
        }
        // Si la cabecera está en modo compacto, se vuelve a aplicar para que el
        // margen de la cámara no devuelva los tamaños grandes.
        if (compactMode) applyCompactValues();
        if (railBox != null) {
            railBox.setPadding(Ui.dpi(this, 8) + left, Ui.dpi(this, 10),
                    Ui.dpi(this, 8), Ui.dpi(this, 8));
        }
        if (contentArea != null) contentArea.setPadding(0, 0, right, 0);
        recalcGridColumns();
    }

    /** Copia el motivo del fallo para que el usuario pueda enviarlo. */
    private void copyProblem() {
        String text = lastLoadProblem.length() > 0 ? lastLoadProblem
                : (statusView == null ? "" : String.valueOf(statusView.getText()));
        if (text.length() == 0) {
            toast("Todavía no hay ningún motivo que copiar");
            return;
        }
        try {
            android.content.ClipboardManager clipboard =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (clipboard == null) {
                toast("No se pudo copiar el motivo");
                return;
            }
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("LENY TV", "LENY TV · " + text));
            toast("Motivo copiado: ya puedes pegarlo");
        } catch (Throwable t) {
            toast("No se pudo copiar el motivo");
        }
    }

    private void showDemoList() {
        ArrayList<PlaylistStore.Playlist> lists = PlaylistStore.lists(this);
        PlaylistStore.Playlist demo = null;
        for (int i = 0; i < lists.size(); i++) {
            if (lists.get(i).isDemo()) demo = lists.get(i);
        }
        if (demo == null) demo = PlaylistStore.createDemoPlaylist(this);
        PlaylistStore.setActiveId(this, demo.id);
        selectedCategory = CAT_ALL;
        searchQuery = "";
        if (searchField != null) searchField.setText("");
        setStatus("Lista de demostración activada.", Ui.OK);
        Diag.add("Se activa la lista de demostración incluida en la app.");
        startLoad(false);
    }

    // ------------------------------------------------------------ abrir vídeo

    private void openPlayer(Channel channel) {
        int position = shown.indexOf(channel);
        Diag.add("Paso 6 · el usuario toca el canal " + (position + 1) + " de " + shown.size()
                + " en pantalla · " + channel.name + " · " + Diag.channelUrl(channel.url));
        Intent intent = new Intent(this, PlayerActivity.class);
        intent.putExtra(PlayerActivity.EXTRA_MODE, PlayerActivity.MODE_LIST);
        intent.putExtra(PlayerActivity.EXTRA_PLAYLIST_ID, activePlaylist == null ? -1L : activePlaylist.id);
        intent.putExtra(PlayerActivity.EXTRA_URL, channel.url);
        // Las cabeceras del bloque del canal viajan ya leídas: el primer intento de
        // reproducción las usa y no empieza con las generales.
        intent.putExtra(PlayerActivity.EXTRA_HEADERS, channel.headersSpec());
        intent.putExtra(PlayerActivity.EXTRA_NAME, channel.name);
        intent.putExtra(PlayerActivity.EXTRA_CATEGORY, CAT_ALL.equals(selectedCategory) ? "" : selectedCategory);
        intent.putExtra(PlayerActivity.EXTRA_LICENSE_TYPE, channel.licenseType);
        intent.putExtra(PlayerActivity.EXTRA_LICENSE_KEY, channel.licenseKey);
        intent.putExtra(PlayerActivity.EXTRA_LICENSE_STREAM_HEADERS, channel.licenseStreamHeaders);
        startActivity(intent);
    }

    private void playSingle(String url, String type, String name) {
        Intent intent = new Intent(this, PlayerActivity.class);
        intent.putExtra(PlayerActivity.EXTRA_MODE, PlayerActivity.MODE_SINGLE);
        intent.putExtra(PlayerActivity.EXTRA_URL, url);
        intent.putExtra(PlayerActivity.EXTRA_NAME, name);
        if (type != null && type.length() > 0) intent.putExtra(PlayerActivity.EXTRA_TYPE, type);
        startActivity(intent);
    }

    private void onPlayUrl() {
        final String url = urlField.getText().toString().trim();
        if (url.length() == 0) {
            toast("Escribe un enlace para reproducir");
            return;
        }
        AppPrefs.setLastUrl(this, url);
        String override = AppPrefs.contentType(this);
        if (!FormatDetector.isAuto(override)) {
            playSingle(url, override, M3uParser.guessName(url));
            return;
        }
        if (FormatDetector.looksLikePlaylistFile(url)) {
            probeUrl(url, false);
            return;
        }
        final String byExtension = FormatDetector.fromUrl(url);
        if (!FormatDetector.OTHER.equals(byExtension)) {
            playSingle(url, byExtension, M3uParser.guessName(url));
            return;
        }
        // Extensión desconocida: se pregunta al servidor por el tipo de contenido.
        setBusy(true);
        setStatus("Comprobando el tipo de contenido del enlace…", Ui.TEXT_DIM);
        background.execute(new Runnable() {
            @Override
            public void run() {
                final String contentType = Net.contentType(url, AppPrefs.headers(MainActivity.this));
                final String refined = FormatDetector.refine(url, contentType);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        setBusy(false);
                        if (contentType == null) {
                            setStatus("No se pudo consultar el tipo de contenido; se envía el enlace tal cual.", Ui.TEXT_DIM);
                            playSingle(url, refined, M3uParser.guessName(url));
                            return;
                        }
                        // El servidor dice que es una lista: hay que averiguar si es una
                        // lista de canales (se carga) o un vídeo HLS (se reproduce).
                        if (FormatDetector.HLS.equals(refined)) {
                            probeUrl(url, true);
                            return;
                        }
                        // DASH (.mpd) se reproduce con el motor DASH interno de LENY TV:
                        // solo se avisa cuando el enlace no es http/https y el manifiesto
                        // no se puede leer.
                        if (FormatDetector.isDash(refined) && !LocalProxy.isHttp(url)) {
                            setStatus("Ese manifiesto DASH necesita un enlace http o https para poder leerse. "
                                    + "Puedes intentarlo como MP4 o HLS si tu proveedor lo ofrece.", Ui.WARN);
                        }
                        playSingle(url, refined, M3uParser.guessName(url));
                    }
                });
            }
        });
    }

    /** El botón AÑADIR LISTA abre el panel PLAYLIST: enlace web o lista del teléfono. */
    private void onAddList() {
        showAddListDialog();
    }

    /** Descarga el enlace y decide si es una lista de canales o un vídeo. */
    private void probeUrl(final String url, final boolean addToList) {
        setBusy(true);
        setStatus("Descargando el enlace…", Ui.TEXT_DIM);
        background.execute(new Runnable() {
            @Override
            public void run() {
                Net.LoadResult result = new Net.LoadResult();
                M3uParser.Result parsed = null;
                String failure = null;
                try {
                    if (!Net.online(MainActivity.this)) {
                        result.error = "no hay conexión a internet";
                    } else {
                        result = Net.loadList(url, AppPrefs.headers(MainActivity.this));
                    }
                    if (result.ok()) {
                        parsed = M3uParser.parse(result.text,
                                result.finalUrl != null && result.finalUrl.length() > 0 ? result.finalUrl : url);
                    }
                } catch (OutOfMemoryError oom) {
                    failure = "la lista es demasiado grande para la memoria de este teléfono";
                } catch (Throwable t) {
                    failure = Net.describe(t);
                }
                final Net.LoadResult loaded = result;
                final M3uParser.Result parsedResult = parsed;
                final String problem = failure;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        setBusy(false);
                        if (!loaded.ok()) {
                            logAttempts(loaded.attempts);
                            String failureText = loaded.error + (loaded.hint == null ? "" : " " + loaded.hint);
                            Diag.add("No se pudo cargar el enlace como lista: " + failureText);
                            Diag.save(MainActivity.this);
                            // Un .m3u8/.m3u que no se deja descargar como texto puede ser un
                            // enlace de vídeo del proveedor: se intenta reproducir directamente
                            // antes de darlo por fallido.
                            if (FormatDetector.looksLikePlaylistFile(url) && loaded.lastCode != 200) {
                                lastLoadProblem = "No se pudo descargar como lista (" + failureText
                                        + "). Se intenta reproducir el enlace como vídeo.";
                                setStatus(lastLoadProblem, Ui.WARN);
                                playSingle(url, FormatDetector.HLS, M3uParser.guessName(url));
                                return;
                            }
                            lastLoadProblem = "No se pudo cargar el enlace: " + failureText;
                            String message = lastLoadProblem;
                            if (loaded.snippet != null && loaded.snippet.length() > 0) {
                                message = message + " · Recibido: " + loaded.snippet;
                            }
                            setStatus(message, Ui.WARN);
                            toast("No se pudo cargar el enlace");
                            updateEmptyState();
                            return;
                        }
                        if (parsedResult == null) {
                            lastLoadProblem = "El enlace se descargó, pero " + problem + ".";
                            setStatus(lastLoadProblem, Ui.WARN);
                            updateEmptyState();
                            return;
                        }
                        logAttempts(loaded.attempts);
                        Diag.add("Paso 4 · lectura del enlace: " + parsedResult.summary());
                        for (int n = 0; n < parsedResult.notes.size(); n++) {
                            Diag.add("   aviso del lector: " + parsedResult.notes.get(n));
                        }
                        if (loaded.note != null) {
                            AppPrefs.setAutoHeaders(MainActivity.this, loaded.usedUserAgent, loaded.usedReferer);
                        }
                        loadNote = loaded.note == null ? "" : loaded.note;
                        if (parsedResult.mediaPlaylist) {
                            setStatus("El enlace es un vídeo HLS; se abre en el reproductor.", Ui.OK);
                            playSingle(url, FormatDetector.HLS, M3uParser.guessName(url));
                            return;
                        }
                        if (parsedResult.channels.isEmpty()) {
                            String reason = parsedResult.reason();
                            lastLoadProblem = "El enlace se descargó, pero "
                                    + (reason.length() > 0 ? reason
                                    : "no contiene entradas de canal válidas")
                                    + ". · Recibido: " + Net.snippetOf(loaded.text);
                            setStatus(lastLoadProblem, Ui.WARN);
                            updateEmptyState();
                            Diag.add("Paso 4 · " + lastLoadProblem);
                            Diag.save(MainActivity.this);
                            return;
                        }
                        String name = parsedResult.title.length() > 0 ? parsedResult.title : PlaylistStore.nameFromUrl(url);
                        // Si el servidor redirigió a otra dirección, se guarda la final para
                        // que al actualizar la lista no haya que pasar por el salto.
                        String reference = loaded.finalUrl != null && loaded.finalUrl.length() > 0 ? loaded.finalUrl : url;
                        boolean alreadySaved = PlaylistStore.findByRef(MainActivity.this, reference) != null;
                        PlaylistStore.Playlist created = PlaylistStore.createUrlPlaylist(MainActivity.this, name, reference);
                        Diag.add(alreadySaved
                                ? "Esa misma lista ya estaba guardada: se actualiza sin crear una copia."
                                : "Lista guardada: " + created.name + " · " + Diag.channelUrl(reference));
                        PlaylistStore.writeCache(MainActivity.this, created, loaded.text);
                        PlaylistStore.setActiveId(MainActivity.this, created.id);
                        activePlaylist = created;
                        pendingActiveId = created.id;
                        loadedPlaylistId = created.id;
                        selectedCategory = CAT_ALL;
                        clearSearch();
                        loadNote = loaded.note == null ? "" : loaded.note;
                        // Los canales ya leídos se entregan directamente a la cuadrícula: no
                        // hace falta volver a leer y analizar la lista entera (eso provocaba
                        // varias lecturas seguidas de la misma lista en pocos segundos).
                        Diag.add("Paso 4 · se entregan a la cuadrícula los canales ya leídos: "
                                + parsedResult.summary());
                        setChannels(parsedResult.channels, created, false, parsedResult.reason());
                    }
                });
            }
        });
    }

    private void pickM3uFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "audio/x-mpegurl", "application/x-mpegurl", "application/vnd.apple.mpegurl",
                "text/plain", "application/octet-stream", "video/*", "audio/*"});
        try {
            startActivityForResult(intent, REQ_PICK_FILE);
        } catch (Exception e) {
            toast("No se pudo abrir el selector de archivos");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == LocalLists.REQ_STORAGE) {
            if (addOverlay != null) {
                setStatus(LocalLists.hasAccess(this)
                        ? "Buscando listas en el teléfono…"
                        : "Sin acceso a los archivos del teléfono: puedes elegir el archivo a mano.", Ui.TEXT_DIM);
                scanLocalLists();
            }
            return;
        }
        if (requestCode != REQ_PICK_FILE) return;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            setStatus("No se eligió ningún archivo.", Ui.TEXT_DIM);
            return;
        }
        Uri uri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {
        }
        String name = queryName(uri);
        PlaylistStore.Playlist created = PlaylistStore.createFilePlaylist(this, name, uri.toString());
        PlaylistStore.setActiveId(this, created.id);
        pendingActiveId = created.id;
        selectedCategory = CAT_ALL;
        closeAddDialog();
        loadLocalPlaylist(created, true);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == LocalLists.REQ_STORAGE && addOverlay != null) scanLocalLists();
    }

    /** Lee el archivo del teléfono, guarda su copia y actualiza la pantalla. */
    private void loadLocalPlaylist(final PlaylistStore.Playlist pl, final boolean justAdded) {
        if (pl == null) return;
        setBusy(true);
        setStatus((justAdded ? "Añadiendo " : "Leyendo ") + pl.name + "…", Ui.TEXT_DIM);
        background.execute(new Runnable() {
            @Override
            public void run() {
                String text = null;
                String error = null;
                try {
                    text = readUri(pl.ref);
                } catch (Exception e) {
                    error = Net.describe(e);
                }
                final String resultText = text;
                final String resultError = error;
                // La lectura y el análisis se hacen aquí, en segundo plano: una lista
                // grande ya no puede congelar la pantalla principal.
                M3uParser.Result parsedResult = null;
                String parseError = null;
                if (resultText != null && resultText.length() > 0) {
                    try {
                        long started = android.os.SystemClock.elapsedRealtime();
                        parsedResult = M3uParser.parse(resultText, baseOf(pl));
                        Diag.add("Archivo del teléfono " + pl.name + ": lectura terminada en "
                                + (android.os.SystemClock.elapsedRealtime() - started) + " ms: "
                                + parsedResult.summary());
                    } catch (OutOfMemoryError oom) {
                        parseError = "la lista es demasiado grande para la memoria de este teléfono";
                    } catch (Throwable t) {
                        parseError = Net.describe(t);
                    }
                }
                final M3uParser.Result parsed = parsedResult;
                final String failure = parseError;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        setBusy(false);
                        if (resultText == null || resultText.length() == 0) {
                            lastLoadProblem = "No se pudo leer " + pl.name + ": " + resultError;
                            setStatus(lastLoadProblem, Ui.WARN);
                            toast("No se pudo leer el archivo");
                            updateEmptyState();
                            return;
                        }
                        if (parsed == null) {
                            lastLoadProblem = "El archivo " + pl.name + " se abrió, pero "
                                    + (failure == null ? "no se pudo leer" : failure) + ".";
                            setStatus(lastLoadProblem, Ui.WARN);
                            toast("No se pudo leer el archivo");
                            updateEmptyState();
                            return;
                        }
                        if (parsed.channels.isEmpty()) {
                            String reason = parsed.reason();
                            lastLoadProblem = "El archivo " + pl.name + " se abrió, pero "
                                    + (reason.length() > 0 ? reason
                                    : "no contiene entradas de lista M3U")
                                    + " (¿es un archivo comprimido .zip/.rar, o una página web?)."
                                    + " · Recibido: " + Net.snippetOf(resultText);
                            setStatus(lastLoadProblem, Ui.WARN);
                            toast("El archivo no parece una lista M3U");
                            updateEmptyState();
                            return;
                        }
                        PlaylistStore.writeCache(MainActivity.this, pl, resultText);
                        pl.channels = parsed.channels.size();
                        pl.updated = System.currentTimeMillis();
                        PlaylistStore.update(MainActivity.this, pl);
                        activePlaylist = pl;
                        pendingActiveId = pl.id;
                        loadedPlaylistId = pl.id;
                        clearSearch();
                        selectedCategory = CAT_ALL;
                        // Los canales ya leídos van directamente a la cuadrícula.
                        setChannels(parsed.channels, pl, parsed.mediaPlaylist, parsed.reason());
                        if (settingsOverlay != null) rebuildSavedLists();
                    }
                });
            }
        });
    }

    private String queryName(Uri uri) {
        String name = null;
        try {
            android.database.Cursor cursor = getContentResolver().query(uri, null, null, null, null);
            if (cursor != null) {
                int index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (cursor.moveToFirst() && index >= 0) name = cursor.getString(index);
                cursor.close();
            }
        } catch (Exception ignored) {
        }
        if (name == null || name.length() == 0) {
            name = uri.getLastPathSegment();
            if (name == null) name = "Archivo M3U";
        }
        return name;
    }

    private String readUri(String uri) throws IOException {
        InputStream in = getContentResolver().openInputStream(Uri.parse(uri));
        if (in == null) throw new IOException("no se pudo abrir el archivo");
        try {
            return Net.decode(readAll(in), null);
        } finally {
            try {
                in.close();
            } catch (Exception ignored) {
            }
        }
    }

    /** Lee un archivo del teléfono directamente (necesita acceso a los archivos). */
    private String readFile(File file) throws IOException {
        if (file == null || !file.exists()) throw new IOException("el archivo ya no está en esa carpeta");
        FileInputStream in = new FileInputStream(file);
        try {
            return Net.decode(readAll(in), null);
        } finally {
            try {
                in.close();
            } catch (Exception ignored) {
            }
        }
    }

    private byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        int total = 0;
        while ((read = in.read(buffer)) > 0) {
            total += read;
            if (total > 12 * 1024 * 1024) {
                throw new IOException("el archivo es demasiado grande (más de 12 MB)");
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    // ------------------------------------------------------------- búsqueda

    private void toggleSearch() {
        if (searchRow.getVisibility() == View.VISIBLE) {
            hideSearch();
            return;
        }
        searchRow.setVisibility(View.VISIBLE);
        searchField.requestFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(searchField, InputMethodManager.SHOW_IMPLICIT);
    }

    private void hideSearch() {
        searchRow.setVisibility(View.GONE);
        searchField.setText("");
        searchQuery = "";
        refreshFilter();
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(root.getWindowToken(), 0);
    }

    // ------------------------------------------- panel PLAYLIST (añadir lista)

    /** Abre el panel PLAYLIST: enlace web, listas guardadas o listas del teléfono. */
    private void showAddListDialog() {
        closeAddDialog();
        addEntries.clear();
        savedAddPlaylists.clear();
        addSelected.clear();
        String current = urlField == null ? "" : urlField.getText().toString().trim();
        if (current.length() == 0) current = AppPrefs.lastUrl(this);
        if (current.length() > 0) addSelected.put(ADD_URL_KEY, Boolean.TRUE);

        addOverlay = new FrameLayout(this);
        addOverlay.setBackgroundColor(0xB3000000);
        addOverlay.setClickable(true);
        addOverlay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeAddDialog();
            }
        });

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(Ui.roundedStroke(this, Ui.PANEL, Ui.RED_DEEP, 1.5f, 12f));
        panel.setPadding(Ui.dpi(this, 16), Ui.dpi(this, 12), Ui.dpi(this, 16), Ui.dpi(this, 12));
        panel.setClickable(true);

        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView title = Ui.text(this, "PLAYLIST", 19f, Ui.TEXT, true);
        title.setLetterSpacing(0.14f);
        titles.addView(title);
        titles.addView(Ui.text(this, "AÑADIR UNA LISTA AL REPRODUCTOR", 10f, Ui.RED, true));
        headerRow.addView(titles, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView close = Ui.iconButton(this, Icons.close(this, Ui.TEXT), "Cerrar el panel PLAYLIST", 40f);
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeAddDialog();
            }
        });
        headerRow.addView(close);
        panel.addView(headerRow);

        addUrlField = new EditText(this);
        addUrlField.setHint("https://mi-proveedor.com/lista.m3u8");
        addUrlField.setHintTextColor(Ui.TEXT_MUTED);
        addUrlField.setTextColor(Ui.TEXT);
        addUrlField.setTextSize(13f);
        addUrlField.setSingleLine(true);
        addUrlField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        addUrlField.setBackground(Ui.roundedStroke(this, Ui.BG, Ui.BLUE_DEEP, 1f, 6f));
        addUrlField.setPadding(Ui.dpi(this, 10), Ui.dpi(this, 9), Ui.dpi(this, 10), Ui.dpi(this, 9));
        addUrlField.setContentDescription("Enlace de la lista M3U o M3U8");
        addUrlField.setText(current);
        // Al escribir o pegar un enlace se marca solo su fila: así nunca se queda
        // un enlace escrito sin añadir por no haber tocado la casilla.
        addUrlField.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                String value = s == null ? "" : s.toString().trim();
                if (value.length() > 0 && !isAddSelected(ADD_URL_KEY)) {
                    addSelected.put(ADD_URL_KEY, Boolean.TRUE);
                    refreshAddRows();
                }
            }
        });

        ScrollView scroll = new ScrollView(this);
        addEntriesBox = new LinearLayout(this);
        addEntriesBox.setOrientation(LinearLayout.VERTICAL);
        addEntriesBox.setPadding(0, Ui.dpi(this, 4), Ui.dpi(this, 8), Ui.dpi(this, 8));
        scroll.addView(addEntriesBox);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        scrollParams.topMargin = Ui.dpi(this, 6);
        panel.addView(scroll, scrollParams);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.END);
        TextView cancel = Ui.actionButton(this, "Cancelar");
        cancel.setContentDescription("Cancelar y cerrar el panel PLAYLIST");
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeAddDialog();
            }
        });
        buttons.addView(cancel);
        TextView accept = Ui.primaryButton(this, "Aceptar");
        accept.setContentDescription("Añadir lo que esté marcado en el panel PLAYLIST");
        LinearLayout.LayoutParams acceptParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        acceptParams.leftMargin = Ui.dpi(this, 10);
        accept.setLayoutParams(acceptParams);
        accept.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmAddList();
            }
        });
        buttons.addView(accept);
        LinearLayout.LayoutParams buttonsParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        buttonsParams.topMargin = Ui.dpi(this, 10);
        panel.addView(buttons, buttonsParams);

        FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        panelParams.setMargins(Ui.dpi(this, 26), Ui.dpi(this, 10), Ui.dpi(this, 26), Ui.dpi(this, 10));
        addOverlay.addView(panel, panelParams);
        root.addView(addOverlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        rebuildAddEntries();
        scanLocalLists();
    }

    private void closeAddDialog() {
        if (addOverlay == null) return;
        root.removeView(addOverlay);
        addOverlay = null;
        addEntriesBox = null;
        addUrlField = null;
        addRowViews.clear();
        addSelected.clear();
        scanToken++;
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null && root != null) imm.hideSoftInputFromWindow(root.getWindowToken(), 0);
        afterPanelClosed("PLAYLIST");
    }

    private void rebuildAddEntries() {
        if (addEntriesBox == null) return;
        addRowViews.clear();
        addEntriesBox.removeAllViews();

        addEntriesBox.addView(sectionTitle("ENLACE WEB (M3U / M3U8)"));
        addEntriesBox.addView(buildAddRow(ADD_URL_KEY, "Enlace (URL)",
                "Escribe o pega la dirección de tu lista", false));
        if (addUrlField != null) addEntriesBox.addView(addUrlField);

        if (!savedAddPlaylists.isEmpty()) {
            addEntriesBox.addView(sectionTitle("LISTAS YA GUARDADAS"));
            for (int i = 0; i < savedAddPlaylists.size(); i++) {
                PlaylistStore.Playlist p = savedAddPlaylists.get(i);
                addEntriesBox.addView(buildAddRow(savedKey(p.id), p.name, p.summary(), false));
            }
        }

        addEntriesBox.addView(sectionTitle("LISTAS Y CARPETAS DEL TELÉFONO"));
        if (!LocalLists.hasAccess(this)) {
            addEntriesBox.addView(hint("LENY TV todavía no puede ver las carpetas del teléfono "
                    + "(Descargas, Documents, Movies). Concede el acceso y aparecerán aquí tus listas."));
            TextView grant = Ui.primaryButton(this, "Dar acceso a los archivos");
            grant.setContentDescription("Permitir a LENY TV leer los archivos del teléfono");
            grant.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    LocalLists.requestAccess(MainActivity.this);
                }
            });
            addEntriesBox.addView(grant);
        } else if (addEntries.isEmpty()) {
            addEntriesBox.addView(hint("No se encontraron listas .m3u/.m3u8 en las carpetas habituales. "
                    + "Usa \"Elegir un archivo M3U\" o escribe el enlace del proveedor."));
        } else {
            for (int i = 0; i < addEntries.size(); i++) {
                LocalLists.Entry entry = addEntries.get(i);
                addEntriesBox.addView(buildAddRow(entry.path, entry.label,
                        (entry.folder ? "Carpeta · " : "") + entry.detail, entry.folder));
            }
        }

        addEntriesBox.addView(sectionTitle("BUSCAR A MANO"));
        TextView pick = Ui.actionButton(this, "Elegir un archivo M3U del teléfono");
        pick.setContentDescription("Abrir el selector para elegir un archivo M3U del teléfono");
        pick.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickM3uFile();
            }
        });
        addEntriesBox.addView(pick);
        addEntriesBox.addView(hint("Marca lo que quieras añadir y pulsa Aceptar. Se puede marcar el enlace "
                + "y una lista del teléfono a la vez."));
    }

    /** Busca en segundo plano las listas M3U guardadas en el teléfono. */
    private void scanLocalLists() {
        final int token = ++scanToken;
        background.execute(new Runnable() {
            @Override
            public void run() {
                final ArrayList<LocalLists.Entry> found = LocalLists.hasAccess(MainActivity.this)
                        ? LocalLists.scan(MainActivity.this) : new ArrayList<LocalLists.Entry>();
                final ArrayList<PlaylistStore.Playlist> saved = new ArrayList<PlaylistStore.Playlist>();
                ArrayList<PlaylistStore.Playlist> allLists = PlaylistStore.lists(MainActivity.this);
                for (int i = 0; i < allLists.size(); i++) {
                    PlaylistStore.Playlist p = allLists.get(i);
                    if (!p.isDemo()) saved.add(p);
                }
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (token != scanToken || addEntriesBox == null) return;
                        addEntries.clear();
                        addEntries.addAll(found);
                        savedAddPlaylists.clear();
                        savedAddPlaylists.addAll(saved);
                        rebuildAddEntries();
                    }
                });
            }
        });
    }

    private String savedKey(long id) {
        return "@saved:" + id;
    }

    private boolean isAddSelected(String key) {
        Boolean value = addSelected.get(key);
        return value != null && value.booleanValue();
    }

    private void toggleAddSelection(String key) {
        if (key == null) return;
        if (isAddSelected(key)) addSelected.remove(key);
        else addSelected.put(key, Boolean.TRUE);
        refreshAddRows();
    }

    private void refreshAddRows() {
        for (int i = 0; i < addRowViews.size(); i++) {
            addRowViews.get(i).refresh();
        }
    }

    private View buildAddRow(final String key, String label, String detail, boolean folder) {
        AddRowView view = new AddRowView(key, label, detail, folder);
        addRowViews.add(view);
        return view.row;
    }

    /** Fila seleccionable del panel PLAYLIST (enlace, lista guardada o archivo). */
    private final class AddRowView {
        final String key;
        final String label;
        final LinearLayout row;
        final TextView check;
        final TextView action;

        AddRowView(final String key, String label, String detail, boolean folder) {
            this.key = key;
            this.label = label == null ? "" : label;
            row = new LinearLayout(MainActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(Ui.dpi(MainActivity.this, 10), Ui.dpi(MainActivity.this, 8),
                    Ui.dpi(MainActivity.this, 8), Ui.dpi(MainActivity.this, 8));
            row.setClickable(true);
            row.setFocusable(true);

            check = Ui.text(MainActivity.this, "○", 16f, Ui.TEXT_MUTED, true);
            check.setPadding(0, 0, Ui.dpi(MainActivity.this, 10), 0);
            row.addView(check);

            LinearLayout texts = new LinearLayout(MainActivity.this);
            texts.setOrientation(LinearLayout.VERTICAL);
            TextView name = Ui.text(MainActivity.this, this.label, 13f, Ui.TEXT, true);
            name.setMaxLines(1);
            name.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(name);
            TextView sub = Ui.text(MainActivity.this, detail == null ? "" : detail, 10f, Ui.TEXT_DIM, false);
            sub.setMaxLines(1);
            sub.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            texts.addView(sub);
            row.addView(texts, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            action = Ui.text(MainActivity.this, "ELEGIR", 10f, Ui.TEXT_MUTED, true);
            action.setAllCaps(true);
            action.setPadding(Ui.dpi(MainActivity.this, 8), 0, 0, 0);
            row.addView(action);

            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    toggleAddSelection(AddRowView.this.key);
                }
            });

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = Ui.dpi(MainActivity.this, 4);
            row.setLayoutParams(lp);
            refresh();
        }

        void refresh() {
            boolean selected = isAddSelected(key);
            check.setText(selected ? "◉" : "○");
            check.setTextColor(selected ? Ui.RED : Ui.TEXT_MUTED);
            action.setText(selected ? "ELEGIDA" : "ELEGIR");
            action.setTextColor(selected ? Ui.OK : Ui.TEXT_MUTED);
            row.setBackground(Ui.roundedStroke(MainActivity.this, selected ? Ui.CARD_SEL : Ui.CARD,
                    selected ? Ui.RED : Ui.BLUE_DEEP, selected ? 2f : 1f, 8f));
            row.setContentDescription((selected ? "Elegida: " : "No elegida: ") + label);
        }
    }

    private void confirmAddList() {
        final String url = addUrlField == null ? "" : addUrlField.getText().toString().trim();
        final ArrayList<LocalLists.Entry> entries = new ArrayList<LocalLists.Entry>();
        final ArrayList<PlaylistStore.Playlist> saved = new ArrayList<PlaylistStore.Playlist>();
        for (int i = 0; i < addEntries.size(); i++) {
            LocalLists.Entry entry = addEntries.get(i);
            if (isAddSelected(entry.path)) entries.add(entry);
        }
        for (int i = 0; i < savedAddPlaylists.size(); i++) {
            PlaylistStore.Playlist p = savedAddPlaylists.get(i);
            if (isAddSelected(savedKey(p.id))) saved.add(p);
        }
        boolean otherMarked = !entries.isEmpty() || !saved.isEmpty();
        // Un enlace escrito se añade siempre, salvo que el usuario lo haya
        // desmarcado a propósito y haya elegido otra lista en su lugar.
        final boolean urlWanted = url.length() > 0 && (isAddSelected(ADD_URL_KEY) || !otherMarked);
        if (!urlWanted && entries.isEmpty() && saved.isEmpty()) {
            toast("Escribe el enlace o marca una lista y pulsa Aceptar");
            return;
        }
        if (urlWanted) AppPrefs.setLastUrl(this, url);
        closeAddDialog();
        addSelectedLists(entries, urlWanted ? url : null, saved);
    }

    /** Añade las listas elegidas (archivos del teléfono) y luego el enlace, si lo hay. */
    private void addSelectedLists(final ArrayList<LocalLists.Entry> entries, final String url,
                                  final ArrayList<PlaylistStore.Playlist> saved) {
        if (url == null && entries.isEmpty()) {
            if (!saved.isEmpty()) openSavedPlaylist(saved.get(0));
            return;
        }
        setBusy(true);
        setStatus("Añadiendo listas…", Ui.TEXT_DIM);
        background.execute(new Runnable() {
            @Override
            public void run() {
                final ArrayList<PlaylistStore.Playlist> created = new ArrayList<PlaylistStore.Playlist>();
                final ArrayList<String> problems = new ArrayList<String>();
                M3uParser.Result firstParsed = null;
                for (int i = 0; i < entries.size(); i++) {
                    LocalLists.Entry entry = entries.get(i);
                    File file = new File(entry.path);
                    ArrayList<File> targets = new ArrayList<File>();
                    if (entry.folder) {
                        targets.addAll(LocalLists.playlistsIn(file));
                    } else {
                        targets.add(file);
                    }
                    if (targets.isEmpty()) {
                        problems.add("la carpeta " + entry.label + " no tiene listas M3U");
                        continue;
                    }
                    for (int t = 0; t < targets.size(); t++) {
                        File target = targets.get(t);
                        String text;
                        try {
                            text = readFile(target);
                        } catch (Exception e) {
                            problems.add(target.getName() + ": " + Net.describe(e));
                            continue;
                        }
                        M3uParser.Result parsed = M3uParser.parse(text, Uri.fromFile(target).toString());
                        boolean known = PlaylistStore.findByRef(MainActivity.this,
                                Uri.fromFile(target).toString()) != null;
                        PlaylistStore.Playlist p = PlaylistStore.createFilePlaylist(MainActivity.this,
                                target.getName(), Uri.fromFile(target).toString());
                        PlaylistStore.writeCache(MainActivity.this, p, text);
                        p.channels = parsed.channels.size();
                        p.updated = System.currentTimeMillis();
                        PlaylistStore.update(MainActivity.this, p);
                        created.add(p);
                        if (firstParsed == null) firstParsed = parsed;
                        Diag.add((known ? "Esa lista del teléfono ya estaba guardada: se actualiza"
                                : "Lista del teléfono guardada: " + p.name)
                                + " · " + parsed.summary());
                        if (parsed.channels.isEmpty()) {
                            problems.add(target.getName() + ": no contiene entradas M3U válidas");
                        }
                    }
                }
                final M3uParser.Result firstResult = firstParsed;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (created.isEmpty()) {
                            if (!problems.isEmpty()) {
                                setStatus("No se pudo añadir la lista: " + problems.get(0), Ui.WARN);
                                toast("No se pudo añadir la lista");
                            }
                            if (url != null) {
                                probeUrl(url, true);
                                return;
                            }
                            setBusy(false);
                            if (!saved.isEmpty()) openSavedPlaylist(saved.get(0));
                            return;
                        }
                        PlaylistStore.Playlist first = created.get(0);
                        PlaylistStore.setActiveId(MainActivity.this, first.id);
                        activePlaylist = first;
                        pendingActiveId = first.id;
                        loadedPlaylistId = first.id;
                        clearSearch();
                        selectedCategory = CAT_ALL;
                        for (int i = 0; i < problems.size(); i++) {
                            Diag.add("Aviso al añadir listas del teléfono: " + problems.get(i));
                        }
                        if (url != null) {
                            // El enlace escrito en el mismo panel también se añade.
                            probeUrl(url, true);
                            return;
                        }
                        setBusy(false);
                        if (firstResult != null) {
                            Diag.add("Los canales ya leídos de " + first.name
                                    + " se entregan a la cuadrícula: " + firstResult.summary());
                            setChannels(firstResult.channels, first, firstResult.mediaPlaylist,
                                    firstResult.reason());
                        } else {
                            startLoad(false);
                        }
                    }
                });
            }
        });
    }

    /** Activa una lista ya guardada y la recarga. */
    private void openSavedPlaylist(PlaylistStore.Playlist pl) {
        if (pl == null) return;
        PlaylistStore.setActiveId(this, pl.id);
        pendingActiveId = pl.id;
        selectedCategory = CAT_ALL;
        searchQuery = "";
        if (searchField != null) searchField.setText("");
        String cached = PlaylistStore.readCache(this, pl);
        if (cached != null && cached.length() > 0) {
            setStatus("Lista activa: " + pl.name, Ui.OK);
            startLoad(false);
        } else {
            setStatus("Cargando " + pl.name + "…", Ui.TEXT_DIM);
            startLoad(true);
        }
    }

    // ------------------------------------------------------------ diagnóstico

    /** Anota en el registro los pasos de cada intento de descarga. */
    private void logAttempts(ArrayList<String> attempts) {
        if (attempts == null) return;
        for (int i = 0; i < attempts.size(); i++) Diag.add("   " + attempts.get(i));
    }

    /** Reproduce el enlace de la lista activa cuando en realidad es un vídeo HLS. */
    private void playActiveLink() {
        String url = activeLinkUrl.length() > 0 ? activeLinkUrl
                : (urlField == null ? "" : urlField.getText().toString().trim());
        if (url.length() == 0) {
            toast("No hay ningún enlace que reproducir");
            return;
        }
        Diag.add("Se abre en el reproductor el enlace: " + Diag.shortUrl(url));
        playSingle(url, FormatDetector.HLS, M3uParser.guessName(url));
    }

    /** Panel con el registro paso a paso, la prueba del lector y la prueba de un enlace. */
    /**
     * ¿Esta pantalla la ha abierto el ICONO del teléfono (o un icono antiguo
     * guardado por el lanzador) en lugar de la propia app? Se reconoce porque el
     * intento trae la acción MAIN y la categoría LAUNCHER, exactamente lo que
     * guarda el lanzador en su icono. Cualquier navegación interna de la app usa
     * un intento sin acción y no entra aquí.
     */
    private boolean openedAsLauncherEntry() {
        try {
            Intent intent = getIntent();
            if (intent == null) return false;
            if (!Intent.ACTION_MAIN.equals(intent.getAction())) return false;
            java.util.Set<String> categories = intent.getCategories();
            if (categories == null) return false;
            return categories.contains(Intent.CATEGORY_LAUNCHER);
        } catch (Throwable t) {
            return false;
        }
    }

    private void showDiagnostics() {
        if (diagOverlay != null) closeDiagnostics();
        Diag.save(this);

        diagOverlay = new FrameLayout(this);
        diagOverlay.setBackgroundColor(0xB3000000);
        diagOverlay.setClickable(true);
        diagOverlay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeDiagnostics();
            }
        });

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(Ui.roundedStroke(this, Ui.PANEL, Ui.RED_DEEP, 1.5f, 12f));
        panel.setPadding(Ui.dpi(this, 16), Ui.dpi(this, 12), Ui.dpi(this, 16), Ui.dpi(this, 12));
        panel.setClickable(true);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView title = Ui.text(this, "DIAGNÓSTICO", 19f, Ui.TEXT, true);
        title.setLetterSpacing(0.12f);
        titles.addView(title);
        titles.addView(Ui.text(this, "QUÉ HA HECHO LENY TV PASO A PASO · " + Diag.REVISION, 10f, Ui.RED, true));
        header.addView(titles, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView close = Ui.iconButton(this, Icons.close(this, Ui.TEXT), "Cerrar el diagnóstico", 40f);
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeDiagnostics();
            }
        });
        header.addView(close);
        panel.addView(header);

        LinearLayout tools = new LinearLayout(this);
        tools.setOrientation(LinearLayout.HORIZONTAL);
        tools.setGravity(Gravity.CENTER_VERTICAL);
        TextView selfTest = Ui.primaryButton(this, "Probar el lector");
        selfTest.setContentDescription("Comprobar que el lector de listas M3U funciona en este teléfono");
        selfTest.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runDiagSelfTest();
            }
        });
        tools.addView(selfTest);
        TextView copy = Ui.actionButton(this, "Copiar");
        copy.setContentDescription("Copiar el registro para poder enviarlo");
        LinearLayout.LayoutParams toolParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        toolParams.leftMargin = Ui.dpi(this, 8);
        copy.setLayoutParams(toolParams);
        copy.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyDiagnostics();
            }
        });
        tools.addView(copy);
        TextView clear = Ui.actionButton(this, "Vaciar");
        clear.setContentDescription("Borrar el registro para empezar de cero");
        LinearLayout.LayoutParams clearParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        clearParams.leftMargin = Ui.dpi(this, 8);
        clear.setLayoutParams(clearParams);
        clear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Diag.clear();
                Diag.add("Registro vaciado a petición del usuario.");
                rebuildDiagText();
            }
        });
        tools.addView(clear);
        TextView gridCheck = Ui.actionButton(this, "Comprobar la cuadrícula");
        gridCheck.setContentDescription("Medir el hueco de la cuadrícula y contar las tarjetas dibujadas");
        gridCheck.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                checkContentRoom();
                auditGrid("a petición desde DIAGNÓSTICO");
                rebuildDiagText();
            }
        });
        LinearLayout.LayoutParams gridCheckParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        gridCheckParams.leftMargin = Ui.dpi(this, 8);
        gridCheck.setLayoutParams(gridCheckParams);
        tools.addView(gridCheck);
        LinearLayout.LayoutParams toolsParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        toolsParams.topMargin = Ui.dpi(this, 8);
        panel.addView(tools, toolsParams);

        TextView urlLabel = Ui.text(this, "ENLACE A PROBAR (pega aquí la dirección de tu lista)", 10f, Ui.RED, true);
        urlLabel.setLetterSpacing(0.08f);
        urlLabel.setPadding(0, Ui.dpi(this, 10), 0, Ui.dpi(this, 4));
        panel.addView(urlLabel);

        String current = urlField == null ? "" : urlField.getText().toString().trim();
        if (current.length() == 0 && activePlaylist != null) current = activePlaylist.ref;
        diagUrlField = new EditText(this);
        diagUrlField.setHint("https://mi-proveedor.com/lista.m3u8");
        diagUrlField.setHintTextColor(Ui.TEXT_MUTED);
        diagUrlField.setTextColor(Ui.TEXT);
        diagUrlField.setTextSize(12f);
        diagUrlField.setSingleLine(true);
        diagUrlField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        diagUrlField.setBackground(Ui.roundedStroke(this, Ui.BG, Ui.BLUE_DEEP, 1f, 6f));
        diagUrlField.setPadding(Ui.dpi(this, 10), Ui.dpi(this, 8), Ui.dpi(this, 10), Ui.dpi(this, 8));
        diagUrlField.setText(current == null ? "" : current);
        panel.addView(diagUrlField);

        TextView probe = Ui.primaryButton(this, "Probar este enlace");
        probe.setContentDescription("Descargar el enlace y explicar qué responde el servidor");
        LinearLayout.LayoutParams probeParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        probeParams.topMargin = Ui.dpi(this, 8);
        probe.setLayoutParams(probeParams);
        probe.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runDiagProbe();
            }
        });
        panel.addView(probe);

        diagBodyView = Ui.text(this, Diag.text(), 11f, Ui.TEXT_DIM, false);
        diagBodyView.setTypeface(Typeface.MONOSPACE);
        diagBodyView.setTextIsSelectable(true);
        diagBodyView.setPadding(Ui.dpi(this, 8), Ui.dpi(this, 8), Ui.dpi(this, 8), Ui.dpi(this, 8));
        diagBodyView.setBackgroundColor(0xFF0C0C14);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(diagBodyView);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        scrollParams.topMargin = Ui.dpi(this, 8);
        panel.addView(scroll, scrollParams);

        FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        panelParams.setMargins(Ui.dpi(this, 20), Ui.dpi(this, 10), Ui.dpi(this, 20), Ui.dpi(this, 10));
        diagOverlay.addView(panel, panelParams);
        root.addView(diagOverlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
    }

    private void runDiagSelfTest() {
        addDiagLines("PRUEBA DEL LECTOR DE LISTAS", SelfTest.run());
        toast("Prueba del lector terminada");
    }

    private void runDiagProbe() {
        final String url = diagUrlField == null ? "" : diagUrlField.getText().toString().trim();
        if (url.length() == 0) {
            toast("Escribe o pega un enlace para probarlo");
            return;
        }
        Diag.add("== PROBAR UN ENLACE: " + Diag.shortUrl(url) + " ==");
        rebuildDiagText();
        final Map<String, String> headers = AppPrefs.headers(this);
        background.execute(new Runnable() {
            @Override
            public void run() {
                final String report = Net.probeReport(url, headers);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        addDiagLines("RESULTADO DE PROBAR EL ENLACE", report);
                    }
                });
            }
        });
    }

    private void addDiagLines(String title, String report) {
        Diag.add("== " + title + " ==");
        if (report != null) {
            String[] lines = report.split("\n");
            for (int i = 0; i < lines.length; i++) {
                if (lines[i].trim().length() > 0) Diag.add("   " + lines[i]);
            }
        }
        Diag.save(this);
        rebuildDiagText();
    }

    private void copyDiagnostics() {
        try {
            android.content.ClipboardManager clipboard =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (clipboard == null) {
                toast("No se pudo copiar el registro");
                return;
            }
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("LENY TV", Diag.text()));
            toast("Registro copiado: ya puedes pegarlo en un mensaje");
        } catch (Throwable t) {
            toast("No se pudo copiar el registro");
        }
    }

    private void rebuildDiagText() {
        if (diagBodyView != null) diagBodyView.setText(Diag.text());
    }

    private void closeDiagnostics() {
        if (diagOverlay == null) return;
        root.removeView(diagOverlay);
        diagOverlay = null;
        diagBodyView = null;
        diagUrlField = null;
        afterPanelClosed("DIAGNÓSTICO");
    }

    /**
     * Un panel (AJUSTES, DIAGNÓSTICO o PLAYLIST) se ha cerrado de verdad: se
     * quita del contenedor principal, se vuelve a ordenar la pantalla y se mide
     * la cuadrícula, para que ninguna capa se quede ocupando el sitio de los
     * canales.
     */
    private void afterPanelClosed(final String name) {
        if (root != null) root.requestLayout();
        if (columnBox != null) columnBox.requestLayout();
        if (contentArea != null) contentArea.requestLayout();
        if (grid != null) grid.requestLayout();
        Diag.add("Panel " + name + " cerrado y retirado de la pantalla: se vuelve a ordenar todo"
                + " y se mide la cuadrícula.");
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                checkContentRoom();
                if (grid != null) auditGrid("después de cerrar el panel " + name);
            }
        }, 350L);
    }

    // -------------------------------------------------------------- ajustes

    private void showSettings() {
        if (settingsOverlay != null) closeSettings();
        pendingActiveIds.clear();
        java.util.ArrayList<Long> already = PlaylistStore.activeIds(this);
        for (int i = 0; i < already.size(); i++) pendingActiveIds.add(already.get(i));
        if (pendingActiveIds.isEmpty() && activePlaylist != null) pendingActiveIds.add(activePlaylist.id);
        pendingActiveId = pendingActiveIds.isEmpty() ? -1L : pendingActiveIds.iterator().next();
        currentTab = 0;

        settingsOverlay = new FrameLayout(this);
        settingsOverlay.setBackgroundColor(0xB3000000);
        settingsOverlay.setClickable(true);
        settingsOverlay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeSettings();
            }
        });

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(Ui.roundedStroke(this, Ui.PANEL, Ui.RED_DEEP, 1.5f, 12f));
        panel.setPadding(Ui.dpi(this, 16), Ui.dpi(this, 12), Ui.dpi(this, 16), Ui.dpi(this, 12));
        panel.setClickable(true);

        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = Ui.text(this, "AJUSTES", 20f, Ui.TEXT, true);
        title.setLetterSpacing(0.1f);
        headerRow.addView(title, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView close = Ui.iconButton(this, Icons.close(this, Ui.TEXT), "Cerrar ajustes", 40f);
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeSettings();
            }
        });
        headerRow.addView(close);
        panel.addView(headerRow);

        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabListaView = tabLabel("PLAYLIST", 0);
        tabAppView = tabLabel("Aplicación", 1);
        tabs.addView(tabListaView);
        LinearLayout.LayoutParams tabParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tabParams.leftMargin = Ui.dpi(this, 8);
        tabAppView.setLayoutParams(tabParams);
        tabs.addView(tabAppView);
        LinearLayout.LayoutParams tabsParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tabsParams.topMargin = Ui.dpi(this, 8);
        panel.addView(tabs, tabsParams);

        settingsContent = new FrameLayout(this);
        LinearLayout.LayoutParams contentParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        contentParams.topMargin = Ui.dpi(this, 8);
        panel.addView(settingsContent, contentParams);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.END);
        TextView cancel = Ui.actionButton(this, "Cancelar");
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeSettings();
            }
        });
        buttons.addView(cancel);
        TextView accept = Ui.primaryButton(this, "Aceptar");
        LinearLayout.LayoutParams acceptParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        acceptParams.leftMargin = Ui.dpi(this, 10);
        accept.setLayoutParams(acceptParams);
        accept.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                applySettings();
            }
        });
        buttons.addView(accept);
        LinearLayout.LayoutParams buttonsParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        buttonsParams.topMargin = Ui.dpi(this, 10);
        panel.addView(buttons, buttonsParams);

        FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        panelParams.setMargins(Ui.dpi(this, 26), Ui.dpi(this, 10), Ui.dpi(this, 26), Ui.dpi(this, 10));
        settingsOverlay.addView(panel, panelParams);
        root.addView(settingsOverlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        selectTab(0);
    }

    private TextView tabLabel(String label, final int index) {
        TextView tab = Ui.text(this, label, 13f, Ui.TEXT_DIM, true);
        tab.setAllCaps(true);
        tab.setLetterSpacing(0.06f);
        tab.setGravity(Gravity.CENTER);
        tab.setPadding(Ui.dpi(this, 16), Ui.dpi(this, 10), Ui.dpi(this, 16), Ui.dpi(this, 10));
        tab.setClickable(true);
        tab.setFocusable(true);
        tab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectTab(index);
            }
        });
        return tab;
    }

    private void selectTab(int index) {
        currentTab = index;
        styleTab(tabListaView, index == 0);
        styleTab(tabAppView, index == 1);
        settingsContent.removeAllViews();
        View content = index == 0 ? buildListTab() : buildAppTab();
        settingsContent.addView(content, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
    }

    private void styleTab(TextView tab, boolean selected) {
        if (tab == null) return;
        tab.setTextColor(selected ? 0xFFFFFFFF : Ui.TEXT_DIM);
        tab.setBackground(Ui.buttonBg(this, selected ? Ui.RED : Ui.CARD, Ui.CARD_SEL, Ui.RED_DEEP, 6f));
    }

    private View buildListTab() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, Ui.dpi(this, 4), Ui.dpi(this, 6), Ui.dpi(this, 10));

        box.addView(sectionTitle("PLAYLIST"));
        box.addView(hint("Marca las listas activas (puedes varias). Toca una fila para activar o desactivar."));
        savedListsBox = new LinearLayout(this);
        savedListsBox.setOrientation(LinearLayout.VERTICAL);
        box.addView(savedListsBox);
        rebuildSavedLists();

        box.addView(sectionTitle("LINK URL"));
        settingsUrlField = new EditText(this);
        settingsUrlField.setHint("http://… o https://… lista.m3u / m3u8");
        settingsUrlField.setHintTextColor(Ui.TEXT_MUTED);
        settingsUrlField.setTextColor(Ui.TEXT);
        settingsUrlField.setTextSize(13f);
        settingsUrlField.setSingleLine(true);
        settingsUrlField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        settingsUrlField.setBackground(Ui.roundedStroke(this, Ui.BG, Ui.BLUE_DEEP, 1f, 6f));
        settingsUrlField.setPadding(Ui.dpi(this, 10), Ui.dpi(this, 9), Ui.dpi(this, 10), Ui.dpi(this, 9));
        box.addView(settingsUrlField);

        LinearLayout addRow = new LinearLayout(this);
        addRow.setOrientation(LinearLayout.HORIZONTAL);
        TextView addByUrl = Ui.primaryButton(this, "Añadir enlace");
        addByUrl.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                addPlaylistFromSettings();
            }
        });
        addRow.addView(addByUrl);
        TextView pick = Ui.actionButton(this, "Abrir archivo M3U del teléfono");
        LinearLayout.LayoutParams pickParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pickParams.leftMargin = Ui.dpi(this, 8);
        pick.setLayoutParams(pickParams);
        pick.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickM3uFile();
            }
        });
        addRow.addView(pick);
        LinearLayout.LayoutParams addRowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        addRowParams.topMargin = Ui.dpi(this, 6);
        box.addView(addRow, addRowParams);

        box.addView(sectionTitle("TIPO DE CONTENIDO"));
        settingsTypeAdapter = new Ui.SpinnerAdapter(this, FormatDetector.options());
        settingsTypeSpinner = new Spinner(this);
        settingsTypeSpinner.setAdapter(settingsTypeAdapter);
        settingsTypeSpinner.setSelection(settingsTypeAdapter.indexOf(AppPrefs.contentType(this)));
        settingsTypeSpinner.setContentDescription("Tipo de contenido por defecto");
        box.addView(settingsTypeSpinner);
        box.addView(hint("Automático detecta por la extensión del enlace y por el tipo de contenido del servidor. "
                + "Usa una opción manual si la detección falla."));

        box.addView(sectionTitle("CABECERAS HTTP (OPCIONAL)"));
        userAgentField = new EditText(this);
        userAgentField.setHint("User-Agent, por ejemplo: VLC/3.0.18 LibVLC/3.0.18");
        userAgentField.setHintTextColor(Ui.TEXT_MUTED);
        userAgentField.setTextColor(Ui.TEXT);
        userAgentField.setTextSize(12f);
        userAgentField.setSingleLine(true);
        userAgentField.setText(AppPrefs.userAgent(this));
        userAgentField.setBackground(Ui.roundedStroke(this, Ui.BG, Ui.BLUE_DEEP, 1f, 6f));
        userAgentField.setPadding(Ui.dpi(this, 10), Ui.dpi(this, 8), Ui.dpi(this, 10), Ui.dpi(this, 8));
        box.addView(userAgentField);

        refererField = new EditText(this);
        refererField.setHint("Referer, por ejemplo: https://mi-proveedor.com/");
        refererField.setHintTextColor(Ui.TEXT_MUTED);
        refererField.setTextColor(Ui.TEXT);
        refererField.setTextSize(12f);
        refererField.setSingleLine(true);
        refererField.setText(AppPrefs.referer(this));
        refererField.setBackground(Ui.roundedStroke(this, Ui.BG, Ui.BLUE_DEEP, 1f, 6f));
        refererField.setPadding(Ui.dpi(this, 10), Ui.dpi(this, 8), Ui.dpi(this, 10), Ui.dpi(this, 8));
        LinearLayout.LayoutParams refParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        refParams.topMargin = Ui.dpi(this, 6);
        box.addView(refererField, refParams);

        box.addView(hint("Se envían al descargar la lista y al reproducir. Si los dejas vacíos se usa un User-Agent de reproductor (VLC) "
                + "y, si tu lista solo se pudo cargar con otras cabeceras, la app reutiliza automáticamente las que funcionaron."));

        scroll.addView(box);
        return scroll;
    }

    private View buildAppTab() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, Ui.dpi(this, 4), Ui.dpi(this, 6), Ui.dpi(this, 10));

        box.addView(sectionTitle("IMAGEN Y CONTROLES"));
        box.addView(rowLabel("Modo de imagen predeterminado"));
        final Ui.SpinnerAdapter aspectAdapter = new Ui.SpinnerAdapter(this, AppPrefs.aspectLabels());
        aspectSpinner = new Spinner(this);
        aspectSpinner.setAdapter(aspectAdapter);
        aspectSpinner.setSelection(AppPrefs.aspectIndex(AppPrefs.aspect(this)));
        aspectSpinner.setContentDescription("Modo de imagen predeterminado");
        box.addView(aspectSpinner);

        box.addView(rowLabel("Ocultar los controles del reproductor"));
        delaySpinner = new Spinner(this);
        final Ui.SpinnerAdapter delayAdapter = new Ui.SpinnerAdapter(this,
                new String[]{"3 segundos", "5 segundos", "8 segundos", "Nunca"});
        delaySpinner.setAdapter(delayAdapter);
        int[] delays = new int[]{3, 5, 8, 0};
        int current = AppPrefs.hideDelaySeconds(this);
        int selected = 1;
        for (int i = 0; i < delays.length; i++) {
            if (delays[i] == current) selected = i;
        }
        delaySpinner.setSelection(selected);
        delaySpinner.setContentDescription("Tiempo antes de ocultar los controles");
        box.addView(delaySpinner);

        LinearLayout switchRow = new LinearLayout(this);
        switchRow.setOrientation(LinearLayout.HORIZONTAL);
        switchRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = rowLabel("Mantener la pantalla encendida al reproducir");
        label.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        switchRow.addView(label);
        screenSwitch = new Switch(this);
        screenSwitch.setChecked(AppPrefs.keepScreenOn(this));
        screenSwitch.setContentDescription("Mantener la pantalla encendida al reproducir");
        switchRow.addView(screenSwitch);
        box.addView(switchRow);

        box.addView(sectionTitle("FORMATOS Y LÍMITES"));
        box.addView(hint("Compatibles con el reproductor interno de Android: HLS (.m3u8), MPEG-TS (.ts), MP4, WebM y enlaces HTTP/HTTPS directos. "
                + "La compatibilidad real depende del códec, del servidor y del teléfono."));
        box.addView(hint("DASH (.mpd): LENY TV lee el manifiesto y lo reproduce con su motor DASH interno, eligiendo la calidad y uniendo la pista de audio con la de vídeo cuando van separadas. "
                + "Los contenidos con DRM tampoco se pueden reproducir."));
        box.addView(hint("LENY TV no incluye ninguna lista ni canal: usa listas y contenidos a los que tengas derecho. "
                + "Las listas M3U se leen como listas de entradas y un enlace .m3u8 de segmentos se reproduce como vídeo."));

        box.addView(sectionTitle("DIAGNÓSTICO"));
        box.addView(hint("Si los canales no aparecen, aquí se ve el motivo exacto: la conexión, el código de"
                + " respuesta del servidor, qué texto se recibió y cuántos canales reconoció el lector."));
        TextView diag = Ui.primaryButton(this, "Ver el diagnóstico");
        diag.setContentDescription("Ver el registro de diagnóstico y probar listas de muestra");
        diag.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showDiagnostics();
            }
        });
        LinearLayout.LayoutParams diagParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        diagParams.topMargin = Ui.dpi(this, 6);
        box.addView(diag, diagParams);
        TextView selfTest = Ui.actionButton(this, "Probar el lector de listas");
        selfTest.setContentDescription("Comprobar el lector M3U con listas de muestra");
        LinearLayout.LayoutParams selfParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        selfParams.topMargin = Ui.dpi(this, 6);
        selfTest.setLayoutParams(selfParams);
        selfTest.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showDiagnostics();
                runDiagSelfTest();
            }
        });
        box.addView(selfTest);

        box.addView(sectionTitle("ACERCA DE"));
        box.addView(hint("LENY TV · versión 1.0 · reproductor IPTV en horizontal."));
        scroll.addView(box);
        return scroll;
    }

    private TextView sectionTitle(String text) {
        TextView t = Ui.text(this, text, 11f, Ui.RED, true);
        t.setLetterSpacing(0.1f);
        t.setPadding(0, Ui.dpi(this, 12), 0, Ui.dpi(this, 6));
        return t;
    }

    private TextView rowLabel(String text) {
        TextView t = Ui.text(this, text, 12f, Ui.TEXT, false);
        t.setPadding(0, Ui.dpi(this, 8), 0, Ui.dpi(this, 4));
        return t;
    }

    private TextView hint(String text) {
        TextView t = Ui.text(this, text, 11f, Ui.TEXT_MUTED, false);
        t.setLineSpacing(Ui.dp(this, 2f), 1f);
        t.setPadding(0, Ui.dpi(this, 6), 0, 0);
        return t;
    }

    private void rebuildSavedLists() {
        if (savedListsBox == null) return;
        savedListsBox.removeAllViews();
        final ArrayList<PlaylistStore.Playlist> lists = PlaylistStore.lists(this);
        if (lists.isEmpty()) {
            savedListsBox.addView(hint("Todavía no has guardado ninguna lista."));
            return;
        }
        for (int i = 0; i < lists.size(); i++) {
            final PlaylistStore.Playlist playlist = lists.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            boolean active = pendingActiveIds.contains(playlist.id);
            row.setBackground(Ui.roundedStroke(this, active ? Ui.CARD_SEL : Ui.CARD,
                    active ? Ui.RED : Ui.BLUE_DEEP, active ? 2f : 1f, 8f));
            row.setPadding(Ui.dpi(this, 10), Ui.dpi(this, 8), Ui.dpi(this, 8), Ui.dpi(this, 8));
            row.setClickable(true);
            row.setFocusable(true);

            TextView check = Ui.text(this, active ? "◉" : "○", 16f, active ? Ui.RED : Ui.TEXT_MUTED, true);
            check.setPadding(0, 0, Ui.dpi(this, 10), 0);
            row.addView(check);

            LinearLayout texts = new LinearLayout(this);
            texts.setOrientation(LinearLayout.VERTICAL);
            TextView name = Ui.text(this, playlist.name, 13f, Ui.TEXT, true);
            name.setMaxLines(1);
            name.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(name);
            texts.addView(Ui.text(this, playlist.summary(), 10f, Ui.TEXT_DIM, false));
            row.addView(texts, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            if (!playlist.isDemo()) {
                TextView remove = Ui.text(this, "Eliminar", 11f, Ui.WARN, true);
                remove.setPadding(Ui.dpi(this, 10), Ui.dpi(this, 4), Ui.dpi(this, 4), Ui.dpi(this, 4));
                remove.setClickable(true);
                remove.setFocusable(true);
                remove.setContentDescription("Eliminar la lista " + playlist.name);
                remove.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        PlaylistStore.remove(MainActivity.this, playlist.id);
                        pendingActiveIds.remove(playlist.id);
                        if (pendingActiveIds.isEmpty()) {
                            PlaylistStore.Playlist now = PlaylistStore.active(MainActivity.this);
                            if (now != null) pendingActiveIds.add(now.id);
                        }
                        pendingActiveId = pendingActiveIds.isEmpty() ? -1L
                                : pendingActiveIds.iterator().next();
                        setStatus("Lista eliminada: " + playlist.name, Ui.TEXT_DIM);
                        rebuildSavedLists();
                    }
                });
                row.addView(remove);
            } else {
                TextView fixed = Ui.text(this, "Incluida", 10f, Ui.TEXT_MUTED, false);
                fixed.setPadding(Ui.dpi(this, 10), 0, 0, 0);
                row.addView(fixed);
            }

            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    // Multi-selección: tocar alterna la lista sin desmarcar las demás.
                    if (pendingActiveIds.contains(playlist.id)) {
                        pendingActiveIds.remove(playlist.id);
                    } else {
                        pendingActiveIds.add(playlist.id);
                    }
                    pendingActiveId = pendingActiveIds.isEmpty() ? -1L
                            : pendingActiveIds.iterator().next();
                    rebuildSavedLists();
                }
            });

            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rowParams.topMargin = Ui.dpi(this, 4);
            savedListsBox.addView(row, rowParams);
        }
    }

    private void addPlaylistFromSettings() {
        if (settingsUrlField == null) return;
        final String url = settingsUrlField.getText().toString().trim();
        if (url.length() == 0) {
            toast("Escribe el enlace de la lista");
            return;
        }
        setBusy(true);
        setStatus("Descargando la lista…", Ui.TEXT_DIM);
        background.execute(new Runnable() {
            @Override
            public void run() {
                Net.LoadResult result = new Net.LoadResult();
                M3uParser.Result parsed = null;
                String failure = null;
                try {
                    if (!Net.online(MainActivity.this)) {
                        result.error = "no hay conexión a internet";
                    } else {
                        result = Net.loadList(url, AppPrefs.headers(MainActivity.this));
                    }
                    if (result.ok()) {
                        parsed = M3uParser.parse(result.text,
                                result.finalUrl != null && result.finalUrl.length() > 0 ? result.finalUrl : url);
                    }
                } catch (OutOfMemoryError oom) {
                    failure = "la lista es demasiado grande para la memoria de este teléfono";
                } catch (Throwable t) {
                    failure = Net.describe(t);
                }
                final Net.LoadResult loaded = result;
                final M3uParser.Result parsedResult = parsed;
                final String problem = failure;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        setBusy(false);
                        if (!loaded.ok()) {
                            lastLoadProblem = "No se pudo cargar la lista: " + loaded.error
                                    + (loaded.hint == null ? "" : " " + loaded.hint);
                            String message = lastLoadProblem;
                            if (loaded.snippet != null && loaded.snippet.length() > 0) {
                                message = message + " · Recibido: " + loaded.snippet;
                            }
                            setStatus(message, Ui.WARN);
                            toast("No se pudo cargar la lista");
                            return;
                        }
                        if (parsedResult == null) {
                            lastLoadProblem = "La lista se descargó, pero " + problem + ".";
                            setStatus(lastLoadProblem, Ui.WARN);
                            return;
                        }
                        if (loaded.note != null) {
                            AppPrefs.setAutoHeaders(MainActivity.this, loaded.usedUserAgent, loaded.usedReferer);
                        }
                        loadNote = loaded.note == null ? "" : loaded.note;
                        if (parsedResult.mediaPlaylist) {
                            setStatus("Ese enlace es un vídeo HLS, no una lista de canales. Usa REPRODUCIR en la pantalla principal.", Ui.WARN);
                            toast("Es un vídeo, no una lista");
                            return;
                        }
                        if (parsedResult.channels.isEmpty()) {
                            String reason = parsedResult.reason();
                            lastLoadProblem = "La lista se descargó, pero "
                                    + (reason.length() > 0 ? reason : "no contiene entradas de canal válidas")
                                    + ". · Recibido: " + Net.snippetOf(loaded.text);
                            setStatus(lastLoadProblem, Ui.WARN);
                            updateEmptyState();
                            Diag.add("Lectura de la lista: " + parsedResult.summary()
                                    + " · motivo: " + parsedResult.reason());
                            Diag.save(MainActivity.this);
                            return;
                        }
                        String name = parsedResult.title.length() > 0 ? parsedResult.title : PlaylistStore.nameFromUrl(url);
                        String reference = loaded.finalUrl != null && loaded.finalUrl.length() > 0 ? loaded.finalUrl : url;
                        boolean alreadySaved = PlaylistStore.findByRef(MainActivity.this, reference) != null;
                        PlaylistStore.Playlist created = PlaylistStore.createUrlPlaylist(MainActivity.this, name, reference);
                        Diag.add(alreadySaved
                                ? "Esa misma lista ya estaba guardada: se actualiza sin crear una copia."
                                : "Lista añadida desde AJUSTES: " + created.name + " · " + Diag.channelUrl(reference));
                        PlaylistStore.writeCache(MainActivity.this, created, loaded.text);
                        PlaylistStore.setActiveId(MainActivity.this, created.id);
                        activePlaylist = created;
                        pendingActiveId = created.id;
                        loadedPlaylistId = created.id;
                        selectedCategory = CAT_ALL;
                        clearSearch();
                        loadNote = loaded.note == null ? "" : loaded.note;
                        rebuildSavedLists();
                        Diag.add("Los canales ya leídos se entregan a la cuadrícula: " + parsedResult.summary());
                        setChannels(parsedResult.channels, created, false, parsedResult.reason());
                    }
                });
            }
        });
    }

    private void applySettings() {
        AppPrefs.setUserAgent(this, userAgentField == null ? "" : userAgentField.getText().toString());
        AppPrefs.setReferer(this, refererField == null ? "" : refererField.getText().toString());
        if (settingsTypeAdapter != null && settingsTypeSpinner != null) {
            AppPrefs.setContentType(this, settingsTypeAdapter.labelAt(settingsTypeSpinner.getSelectedItemPosition()));
        }
        if (aspectSpinner != null) {
            int index = aspectSpinner.getSelectedItemPosition();
            String[] keys = AppPrefs.aspectKeys();
            if (index >= 0 && index < keys.length) AppPrefs.setAspect(this, keys[index]);
        }
        if (delaySpinner != null) {
            int[] delays = new int[]{3, 5, 8, 0};
            int index = delaySpinner.getSelectedItemPosition();
            if (index >= 0 && index < delays.length) AppPrefs.setHideDelaySeconds(this, delays[index]);
        }
        if (screenSwitch != null) AppPrefs.setKeepScreenOn(this, screenSwitch.isChecked());

        java.util.ArrayList<Long> chosen = new java.util.ArrayList<Long>(pendingActiveIds);
        java.util.ArrayList<Long> before = PlaylistStore.activeIds(this);
        boolean activeChanged = !chosen.equals(before);
        if (activeChanged || chosen.size() > 0) {
            PlaylistStore.setActiveIds(this, chosen);
        }
        closeSettings();
        setStatus("Ajustes guardados.", Ui.OK);
        if (activeChanged) {
            selectedCategory = CAT_ALL;
            startLoad(false);
        }
    }

    private void closeSettings() {
        if (settingsOverlay == null) return;
        root.removeView(settingsOverlay);
        settingsOverlay = null;
        savedListsBox = null;
        settingsUrlField = null;
        userAgentField = null;
        refererField = null;
        settingsTypeSpinner = null;
        settingsTypeAdapter = null;
        aspectSpinner = null;
        delaySpinner = null;
        screenSwitch = null;
        tabListaView = null;
        tabAppView = null;
        settingsContent = null;
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(root.getWindowToken(), 0);
        afterPanelClosed("AJUSTES");
    }

    // ---------------------------------------------------------------- ayudas

    private void setStatus(String message, int color) {
        // REVISIÓN 50: la pantalla de canales ya no tiene la línea de texto inferior
        // (la referencia no la incluye). Para no perder ninguna explicación, los
        // avisos importantes se muestran como aviso flotante.
        if (color == Ui.WARN) toast(message);

        if (statusView == null) return;
        statusView.setText(message);
        statusView.setTextColor(color);
        // Queda anotado en el registro de diagnóstico, pero sin repetir el mismo
        // mensaje una y otra vez.
        if (message != null && !message.equals(lastStatusLogged)) {
            lastStatusLogged = message;
            Diag.add("Aviso en pantalla: " + message);
        }
    }

    private void setBusy(boolean value) {
        busy = value;
        if (statusSpinner != null) statusSpinner.setVisibility(value ? View.VISIBLE : View.GONE);
        updateEmptyState();
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private String describe(Exception e) {
        return Net.describe(e);
    }

    // ------------------------------------------------------------ adaptadores

    private final class CategoryAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return categoryNames.size();
        }

        @Override
        public Object getItem(int position) {
            return categoryNames.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            TextView row = convertView instanceof TextView
                    ? (TextView) convertView
                    : Ui.text(MainActivity.this, "", 15f, Ui.TEXT_DIM, true);
            String category = categoryNames.get(position);
            int total;
            if (position == 0) {
                total = all.size();
            } else {
                Integer count = categoryCounts.get(category);
                total = count == null ? 0 : count.intValue();
            }
            row.setText(category + "  (" + total + ")");
            row.setPadding(Ui.dpi(MainActivity.this, 10), Ui.dpi(MainActivity.this, 10),
                    Ui.dpi(MainActivity.this, 8), Ui.dpi(MainActivity.this, 10));
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMaxLines(2);
            row.setEllipsize(TextUtils.TruncateAt.END);
            boolean selected = category.equals(selectedCategory);
            row.setTextColor(selected ? Ui.TEXT : Ui.TEXT_DIM);
            row.setBackground(selected
                    ? Ui.roundedStroke(MainActivity.this, Ui.RED_DEEP, Ui.RED, 1.5f, 6f)
                    : null);
            return row;
        }
    }

    private final class ChannelAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return shown.size();
        }

        @Override
        public Object getItem(int position) {
            return shown.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout card;
            ImageView logo;
            TextView name;
            if (convertView instanceof LinearLayout && convertView.getTag() instanceof Object[]) {
                card = (LinearLayout) convertView;
                Object[] holder = (Object[]) convertView.getTag();
                logo = (ImageView) holder[0];
                name = (TextView) holder[1];
            } else {
                card = new LinearLayout(MainActivity.this);
                card.setOrientation(LinearLayout.VERTICAL);
                card.setGravity(Gravity.CENTER_HORIZONTAL);
                card.setPadding(Ui.dpi(MainActivity.this, cardPadDp), Ui.dpi(MainActivity.this, cardPadDp),
                        Ui.dpi(MainActivity.this, cardPadDp), Ui.dpi(MainActivity.this, cardPadDp));
                card.setBackground(Ui.roundedStroke(MainActivity.this, Ui.CARD, Ui.BLUE_DEEP, 1f, 10f));

                logo = new ImageView(MainActivity.this);
                logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
                logo.setLayoutParams(new LinearLayout.LayoutParams(
                        Ui.dpi(MainActivity.this, cardLogoDp), Ui.dpi(MainActivity.this, cardLogoDp)));
                card.addView(logo);

                name = Ui.text(MainActivity.this, "", 12f, Ui.TEXT, false);
                name.setGravity(Gravity.CENTER);
                name.setMaxLines(2);
                name.setEllipsize(TextUtils.TruncateAt.END);
                name.setLayoutParams(new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, Ui.dpi(MainActivity.this, cardNameDp)));
                card.addView(name);
                card.setTag(new Object[]{logo, name});
            }
            Channel channel = shown.get(position);
            name.setText(Names.forDisplay(name.getPaint(), channel.name));
            LogoLoader.load(MainActivity.this, channel.logo, logo, channel.name);
            return card;
        }
    }
}
