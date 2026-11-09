package com.pocketbuilder.app_wslenytvplayer1792356127271;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
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
import android.view.Window;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.pocketbuilder.app_wslenytvplayer1792356127271.movies.MoviesGuard;
import com.pocketbuilder.app_wslenytvplayer1792356127271.movies.MoviesPlayerActivity;
import com.pocketbuilder.app_wslenytvplayer1792356127271.movies.MoviesPrefs;
import com.pocketbuilder.app_wslenytvplayer1792356127271.movies.MoviesSettingsActivity;

import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * INTERFAZ DE PELÍCULAS Y SERIES de LENY TV Player (revisión 61).
 *
 * Se abre con el botón PELÍCULAS de la barra superior de la pantalla de TV, y
 * tiene su propio botón TV para volver. La pantalla de canales no cambia.
 *
 * Qué hace:
 *   - Cuadrícula de carteles con el título, su tipo (PELÍCULA o SERIE) y su nota.
 *   - Buscador por título, año o género.
 *   - Filtros: Todo, Películas, Series y las categorías del catálogo, cada uno con
 *     su número de títulos.
 *   - Ficha de cada título con sinopsis, año, nota, origen y enlaces.
 *   - En las series, temporadas y episodios.
 *   - Reproduce con EL MISMO REPRODUCTOR QUE LA TELEVISIÓN (PlayerActivity), en
 *     modo de enlace único: mismos botones, mismo panel de ajustes, misma
 *     estética y mismo motor. El reproductor propio del módulo
 *     (MoviesPlayerActivity) queda solo como reserva por si el de la TV no se
 *     pudiera abrir.
 *   - ENLACES: direcciones de catálogo JSON o de proveedor, que se pegan en su
 *     pantalla (antes esa entrada se llamaba REPOSITORIOS).
 *   - XTREAM: listas Xtream Codes (servidor, usuario y contraseña).
 *   - AJUSTES del módulo: sus propias cabeceras, su modo de imagen, su registro y
 *     la tabla de adaptadores de proveedor.
 *   - Cuando no hay ningún título, la portada de bienvenida invita a añadir una
 *     lista, con sus dos botones reales (XTREAM y ENLACES).
 *
 * SEPARACIÓN DE LA TELEVISIÓN
 * Esta pantalla vive en su PROPIO PROCESO (":movies", ver el manifiesto), usa sus
 * propias preferencias ("leny_movies_prefs") y escribe en su propio registro. Un
 * fallo aquí queda anotado y, como mucho, cierra el proceso del módulo: la
 * televisión sigue funcionando.
 *
 * El contenido no viene dentro de la app: se lee de las fuentes que el usuario
 * añade. El catálogo de demostración sigue en el proyecto pero está OCULTO por
 * defecto (se activa en AJUSTES).
 */
public final class MoviesActivity extends Activity {

    /** Repositorio virtual del catálogo incluido en la app. */
    private static final CatalogStore.Repo DEMO_REPO = new CatalogStore.Repo();

    /** Colores propios de la pantalla de películas. */
    private static final int CYAN = 0xFF22D3EE;
    private static final int PANEL_DARK = 0xB3150E12;
    private static final int PANEL_EDGE = 0x33FFFFFF;
    private static final int BG_CARD = 0x66150E12;
    private static final int GOLD = 0xFFE8A33D;

    private final ArrayList<MovieItem> allItems = new ArrayList<MovieItem>();
    private final ArrayList<MovieItem> shownItems = new ArrayList<MovieItem>();

    private LinearLayout chipsRow;
    private GridView grid; // legado; la UI nueva usa filas
    private PosterAdapter adapter;
    private LinearLayout emptyBox;
    private LinearLayout loadingBox;
    private TextView loadingText;
    private TextView countView;
    private EditText searchField;
    private EditText quickField;
    private TextView summaryView;
    /** Contenedor de filas horizontales (Destacadas, géneros…). */
    private LinearLayout rowsContainer;
    private ScrollView catalogScroll;
    private FrameLayout heroBox;
    private ImageView heroPoster;
    private TextView heroTitle;
    private TextView heroPlot;
    private TextView heroBadge;
    private LinearLayout sidebar;
    private LinearLayout sidebarCats;
    private String selectedSidebarGenre = "";
    private String sidebarSection = "movies";
    private MovieItem featured;
    /** Último informe de fuentes (se enseña en el panel de ajustes). */
    private String summaryText = "";
    /** Panel de ajustes de la pantalla (listas, enlaces y ajustes del módulo). */
    private Dialog toolsDialog;

    private String selectedKind = ""; // películas + series (solo VOD)
    private String selectedRepo = "";
    private LinearLayout listChips;

    private String selectedGenre = "";
    private String searchText = "";
    private boolean loading = false;
    /** La actualización automática se intenta una sola vez por visita. */
    private boolean autoRefreshDone = false;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newSingleThreadExecutor();

    // ------------------------------------------------------------------- ciclo

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        DEMO_REPO.id = CatalogStore.DEMO_ID;
        DEMO_REPO.name = CatalogStore.DEMO_NAME;
        DEMO_REPO.enabled = true;
        DEMO_REPO.demo = true;
        // Red de seguridad del módulo: deja el error escrito en SU registro antes
        // de que el proceso del módulo se cierre (la televisión no se toca).
        MoviesGuard.install(this);
        try {
            CatalogStore.seedPresets(this);
            CatalogStore.seedDefaultVod(this);
            buildUi();
            // Solo Estrenos al entrar; el resto al tocar su pestaña
            main.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        CatalogStore.seedDefaultVod(MoviesActivity.this);
                        selectedRepo = "Estrenos";
                        rebuildListChips();
                        loadSelectedRepoOnly(false); // usa caché; descarga solo si está vacío
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (Throwable t) {
            Diag.add("Películas · no se pudo dibujar la pantalla: " + Net.describe(t));
            showEmergencyScreen();
            return;
        }
        // PANTALLA COMPLETA, igual que en la televisión: sin barra de estado (las
        // notificaciones) ni botones de navegación. Antes esta pantalla no lo pedía
        // y por eso en Películas se seguían viendo las barras del teléfono.
        try {
            Immersion.keepHidden(this);
        } catch (Throwable ignored) {
        }
        reloadLocal();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Al volver de ENLACES o de LISTAS XTREAM hay que releer lo que se guardó
        // mientras tanto.
        if (grid != null) {
            // No refrescar todas las listas al volver (evita lag y descargas en cadena)
            selectedRepo = selectedRepo.length() == 0 ? "Estrenos" : selectedRepo;
            loadSelectedRepoOnly(false);
        }
        try {
            Immersion.keepHidden(this);
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            Immersion.stopKeeper();
        } catch (Throwable ignored) {
        }
    }

    /**
     * Algunos teléfonos vuelven a mostrar las barras al cerrar el teclado del
     * buscador o al cambiar de ventana: se vuelven a ocultar al recuperar el foco.
     */
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            try {
                Immersion.apply(this);
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            pool.shutdownNow();
        } catch (Throwable ignored) {
        }
    }

    /**
     * Memoria: la caché de carteles (16 MB) es de este módulo. Cuando el teléfono
     * pide memoria se vacía solo el caché de carteles; los logos de la televisión
     * no se tocan porque viven en otro proceso.
     */
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_RUNNING_LOW) {
            try {
                PosterLoader.trimMemory();
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        try {
            PosterLoader.trimMemory();
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ dibujo

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0B0D14);

        // Sin barra lateral: más espacio para el catálogo (como pidió el usuario)
        LinearLayout column = root;
        sidebar = null;
        sidebarCats = null;

        // Cabecera Leny TV | Películas + lupa + ajustes (mismo tamaño)
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dpi(16), dpi(10), dpi(12), dpi(8));
        TextView brand = Ui.text(this, "Leny TV", 16f, 0xFF00D4FF, true);
        top.addView(brand);
        TextView sep = Ui.text(this, "  |  ", 14f, 0xFF556070, false);
        top.addView(sep);
        TextView mod = Ui.text(this, "Películas", 15f, 0xFFFFFFFF, true);
        top.addView(mod);
        View spacer = new View(this);
        top.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));
        TextView tvBtn = Ui.text(this, "TV", 12f, 0xFFAABBCC, true);
        tvBtn.setPadding(dpi(12), dpi(8), dpi(12), dpi(8));
        tvBtn.setBackground(rounded(0x22FFFFFF, 10f));
        tvBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        top.addView(tvBtn);
        // Lupa: abre buscador aparte
        top.addView(headerIconButton(Icons.search(this, 0xFFE8EEF8), "Buscar",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showSearchPanel();
                    }
                }));
        // Ajustes: mismo cuadro que la lupa
        top.addView(headerIconButton(Icons.gear(this, 0xFFE8EEF8), "Ajustes",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showToolsPanel();
                    }
                }));
        column.addView(top, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // Pestañas por lista / servidor (elegir fuente VOD)
        HorizontalScrollView listScroll = new HorizontalScrollView(this);
        listScroll.setHorizontalScrollBarEnabled(false);
        listScroll.setPadding(dpi(12), dpi(4), dpi(12), dpi(6));
        listChips = new LinearLayout(this);
        listChips.setOrientation(LinearLayout.HORIZONTAL);
        listScroll.addView(listChips);
        column.addView(listScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // Campo de búsqueda oculto (lo usa el panel de búsqueda)
        searchField = new EditText(this);
        searchField.setVisibility(View.GONE);

        FrameLayout content = new FrameLayout(this);

        catalogScroll = new ScrollView(this);
        catalogScroll.setFillViewport(true);
        LinearLayout scrollInner = new LinearLayout(this);
        scrollInner.setOrientation(LinearLayout.VERTICAL);
        scrollInner.setPadding(dpi(12), dpi(4), dpi(12), dpi(20));

        // Hero destacado
        heroBox = new FrameLayout(this);
        heroBox.setMinimumHeight(dpi(200));
        heroBox.setBackground(rounded(0xFF121820, 14f));
        heroPoster = new ImageView(this);
        heroPoster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        heroBox.addView(heroPoster, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dpi(210)));
        View scrim = new View(this);
        scrim.setBackgroundColor(0x99000000);
        heroBox.addView(scrim, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dpi(210)));
        LinearLayout heroText = new LinearLayout(this);
        heroText.setOrientation(LinearLayout.VERTICAL);
        heroText.setPadding(dpi(18), dpi(24), dpi(18), dpi(16));
        heroBadge = Ui.text(this, "PELÍCULA", 10f, 0xFFFF6677, true);
        heroText.addView(heroBadge);
        heroTitle = Ui.text(this, "Catálogo Leny", 22f, 0xFFFFFFFF, true);
        heroTitle.setPadding(0, dpi(4), 0, dpi(6));
        heroText.addView(heroTitle);
        heroPlot = Ui.text(this, "Añade listas M3U / Xtream en Ajustes para llenar el catálogo.", 12f, 0xFFCCD6E0, false);
        heroPlot.setMaxLines(3);
        heroText.addView(heroPlot);
        LinearLayout heroBtns = new LinearLayout(this);
        heroBtns.setOrientation(LinearLayout.HORIZONTAL);
        heroBtns.setPadding(0, dpi(12), 0, 0);
        TextView playHero = Ui.text(this, "  ▶  Reproducir  ", 13f, 0xFF001018, true);
        playHero.setBackground(rounded(0xFF00D4FF, 10f));
        playHero.setPadding(dpi(14), dpi(10), dpi(14), dpi(10));
        playHero.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (featured != null) showDetail(featured);
            }
        });
        heroBtns.addView(playHero);
        TextView infoHero = Ui.text(this, "  Más info  ", 13f, 0xFFE8EEF8, true);
        infoHero.setBackground(rounded(0x44FFFFFF, 10f));
        infoHero.setPadding(dpi(14), dpi(10), dpi(14), dpi(10));
        LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        infoLp.leftMargin = dpi(10);
        infoHero.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (featured != null) showDetail(featured);
            }
        });
        heroBtns.addView(infoHero, infoLp);
        heroText.addView(heroBtns);
        heroBox.addView(heroText, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        scrollInner.addView(heroBox, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dpi(210)));

        countView = Ui.text(this, "", 11f, 0xFF8899AA, false);
        countView.setPadding(dpi(4), dpi(10), dpi(4), dpi(4));
        scrollInner.addView(countView);

        rowsContainer = new LinearLayout(this);
        rowsContainer.setOrientation(LinearLayout.VERTICAL);
        scrollInner.addView(rowsContainer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        catalogScroll.addView(scrollInner);
        content.addView(catalogScroll, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // Grid oculto solo para no romper referencias antiguas
        grid = new GridView(this);
        grid.setVisibility(View.GONE);
        adapter = new PosterAdapter(this, shownItems);
        grid.setAdapter(adapter);
        content.addView(grid, new FrameLayout.LayoutParams(1, 1));

        emptyBox = new LinearLayout(this);
        emptyBox.setOrientation(LinearLayout.VERTICAL);
        emptyBox.setGravity(Gravity.CENTER);
        emptyBox.setPadding(dpi(24), dpi(24), dpi(24), dpi(24));
        emptyBox.addView(Ui.text(this, "Sin títulos todavía", 15f, 0xFFE8EEF8, true));
        emptyBox.addView(Ui.text(this,
                "En Ajustes añade un enlace M3U o get.php de películas/VOD.",
                12f, 0xFF99AABC, false));
        TextView emptyAdd = Ui.text(this, "Abrir ajustes", 13f, 0xFF001018, true);
        emptyAdd.setBackground(rounded(0xFF00D4FF, 10f));
        emptyAdd.setPadding(dpi(16), dpi(10), dpi(16), dpi(10));
        emptyAdd.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showToolsPanel();
            }
        });
        LinearLayout.LayoutParams emptyAddLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        emptyAddLp.topMargin = dpi(14);
        emptyBox.addView(emptyAdd, emptyAddLp);
        emptyBox.setVisibility(View.GONE);
        content.addView(emptyBox, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        loadingBox = new LinearLayout(this);
        loadingBox.setOrientation(LinearLayout.VERTICAL);
        loadingBox.setGravity(Gravity.CENTER);
        ProgressBar spin = new ProgressBar(this);
        loadingBox.addView(spin);
        loadingText = Ui.text(this, "Cargando catálogo…", 12f, 0xFFCCD6E0, false);
        loadingBox.addView(loadingText);
        loadingBox.setVisibility(View.GONE);
        content.addView(loadingBox, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));

        summaryView = Ui.text(this, "", 10f, 0xFF667788, false);
        summaryView.setPadding(dpi(16), dpi(4), dpi(16), dpi(8));
        column.addView(content, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        column.addView(summaryView);

        // chips legacy hidden container
        chipsRow = new LinearLayout(this);
        chipsRow.setVisibility(View.GONE);
        column.addView(chipsRow);

        setContentView(root);
    }

    private View sideItem(final String label, final String id, boolean selected) {
        TextView t = Ui.text(this, label, 12f, selected ? 0xFFFFFFFF : 0xFF8A93A3, true);
        t.setPadding(dpi(12), dpi(12), dpi(12), dpi(12));
        if (selected) {
            t.setBackground(roundedStroke(0x2210A0C0, 0xFF00D4FF, 10f));
        }
        t.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onSidebar(id);
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dpi(6);
        t.setLayoutParams(lp);
        return t;
    }

    private void onSidebar(String id) {
        sidebarSection = id == null ? "movies" : id;
        if ("settings".equals(sidebarSection)) {
            showToolsPanel();
            return;
        }
        if ("fav".equals(sidebarSection) || "hist".equals(sidebarSection)) {
            Toast.makeText(this, "Pronto disponible", Toast.LENGTH_SHORT).show();
            return;
        }
        if ("home".equals(sidebarSection) || "movies".equals(sidebarSection)) {
            selectedKind = "";
        } else if ("series".equals(sidebarSection)) {
            selectedKind = MovieItem.KIND_SERIES;
        }
        if ("movies".equals(sidebarSection)) {
            selectedKind = MovieItem.KIND_MOVIE;
        }
        if ("home".equals(sidebarSection)) {
            selectedKind = "";
        }
        selectedGenre = "";
        rebuildSidebar();
        applyFilter();
    }

    private void rebuildSidebar() {
        if (sidebar == null) return;
        sidebar.removeAllViews();
        sidebar.addView(sideItem("Inicio", "home", "home".equals(sidebarSection)));
        sidebar.addView(sideItem("Películas", "movies", "movies".equals(sidebarSection)));
        sidebar.addView(sideItem("Series", "series", "series".equals(sidebarSection)));
        sidebar.addView(sideItem("Favoritos", "fav", "fav".equals(sidebarSection)));
        sidebar.addView(sideItem("Historial", "hist", "hist".equals(sidebarSection)));
        sidebar.addView(sideItem("Ajustes", "settings", false));
        TextView catLabel = Ui.text(this, "CATEGORÍAS", 9f, 0xFF667788, true);
        catLabel.setPadding(dpi(8), dpi(14), dpi(4), dpi(6));
        sidebar.addView(catLabel);
        ScrollView catScroll = new ScrollView(this);
        sidebarCats = new LinearLayout(this);
        sidebarCats.setOrientation(LinearLayout.VERTICAL);
        catScroll.addView(sidebarCats);
        sidebar.addView(catScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        rebuildSidebarCategories();
    }

    private void rebuildSidebarCategories() {
        if (sidebarCats == null) return; // barra lateral eliminada
        sidebarCats.removeAllViews();
        java.util.LinkedHashMap<String, Integer> counts =
                new java.util.LinkedHashMap<String, Integer>();
        for (int i = 0; i < allItems.size(); i++) {
            MovieItem item = allItems.get(i);
            if (item == null) continue;
            if (MovieItem.KIND_MOVIE.equals(selectedKind) && item.isSeries()) continue;
            if (MovieItem.KIND_SERIES.equals(selectedKind) && !item.isSeries()) continue;
            if (item.genres == null || item.genres.isEmpty()) {
                Integer c = counts.get("Otros");
                counts.put("Otros", c == null ? 1 : c.intValue() + 1);
                continue;
            }
            for (int g = 0; g < item.genres.size(); g++) {
                String genre = item.genres.get(g);
                if (genre == null || genre.trim().length() == 0) continue;
                Integer c = counts.get(genre);
                counts.put(genre, c == null ? 1 : c.intValue() + 1);
            }
        }
        if (counts.isEmpty()) {
            TextView empty = Ui.text(this, "Sin categorías", 10f, 0xFF556070, false);
            empty.setPadding(dpi(8), dpi(4), dpi(4), dpi(4));
            sidebarCats.addView(empty);
            return;
        }
        for (java.util.Map.Entry<String, Integer> e : counts.entrySet()) {
            final String genre = e.getKey();
            boolean on = genre.equals(selectedSidebarGenre);
            TextView row = Ui.text(this, genre + " · " + e.getValue(), 10f,
                    on ? 0xFFFFFFFF : 0xFF8A93A3, on);
            row.setPadding(dpi(8), dpi(8), dpi(6), dpi(8));
            if (on) row.setBackground(roundedStroke(0x2210A0C0, 0xFF00D4FF, 8f));
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (genre.equals(selectedSidebarGenre)) {
                        selectedSidebarGenre = "";
                        selectedGenre = "";
                    } else {
                        selectedSidebarGenre = genre;
                        selectedGenre = genre;
                    }
                    rebuildSidebarCategories();
                    applyFilter();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dpi(3);
            row.setLayoutParams(lp);
            sidebarCats.addView(row);
        }
    }

    private View headerIconButton(android.graphics.drawable.Drawable icon, String desc,
                                  View.OnClickListener click) {
        FrameLayout box = new FrameLayout(this);
        int size = dpi(40);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        lp.leftMargin = dpi(8);
        box.setLayoutParams(lp);
        box.setBackground(roundedStroke(0x22182028, 0x44FFFFFF, 10f));
        box.setClickable(true);
        box.setFocusable(true);
        box.setContentDescription(desc);
        ImageView iv = new ImageView(this);
        iv.setImageDrawable(icon);
        iv.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        int pad = dpi(9);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        iv.setPadding(pad, pad, pad, pad);
        box.addView(iv, ilp);
        box.setOnClickListener(click);
        return box;
    }

    private void showSearchPanel() {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dpi(16), dpi(14), dpi(16), dpi(14));
        panel.setBackground(rounded(0xF2140E14, 14f));
        panel.addView(Ui.text(this, "BUSCAR", 13f, CYAN, true));
        final EditText field = new EditText(this);
        field.setHint("Nombre de película o serie");
        field.setHintTextColor(0xFF667788);
        field.setTextColor(0xFFE8EEF8);
        field.setSingleLine(true);
        field.setText(searchText);
        field.setBackground(rounded(0x22182028, 10f));
        field.setPadding(dpi(12), dpi(10), dpi(12), dpi(10));
        LinearLayout.LayoutParams fieldLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        fieldLp.topMargin = dpi(12);
        panel.addView(field, fieldLp);
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dpi(14), 0, 0);
        TextView ok = Ui.text(this, "  Buscar  ", 13f, 0xFF001018, true);
        ok.setBackground(rounded(0xFF00D4FF, 10f));
        ok.setPadding(dpi(14), dpi(10), dpi(14), dpi(10));
        ok.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                searchText = field.getText() == null ? ""
                        : field.getText().toString().trim().toLowerCase(Locale.US);
                applyFilter();
                dialog.dismiss();
            }
        });
        actions.addView(ok);
        TextView clear = Ui.text(this, "  Limpiar  ", 13f, 0xFFE8EEF8, true);
        clear.setBackground(rounded(0x44FFFFFF, 10f));
        clear.setPadding(dpi(14), dpi(10), dpi(14), dpi(10));
        LinearLayout.LayoutParams clearLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        clearLp.leftMargin = dpi(10);
        clear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                searchText = "";
                field.setText("");
                applyFilter();
                dialog.dismiss();
            }
        });
        actions.addView(clear, clearLp);
        panel.addView(actions);
        dialog.setContentView(panel);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.86f),
                    WindowManager.LayoutParams.WRAP_CONTENT);
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
        }
        dialog.show();
        field.requestFocus();
    }


    private GradientDrawable rounded(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(Ui.dpi(this, radiusDp));
        return g;
    }

    private GradientDrawable roundedStroke(int fill, int stroke, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setStroke(Math.max(1, dpi(1)), stroke);
        g.setCornerRadius(Ui.dpi(this, radiusDp));
        return g;
    }

    private View buildHeader() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackground(RogStyle.bar(this));
        bar.setPadding(dpi(12), dpi(6), dpi(8), dpi(6));

        TextView brand = Ui.text(this, "LENY TV", 16f, RogStyle.TEXT, true);
        brand.setTypeface(Typeface.DEFAULT_BOLD);
        brand.setSingleLine(true);
        bar.addView(brand);

        TextView tag = Ui.text(this, "PELÍCULAS Y SERIES", 10f, CYAN, true);
        tag.setLetterSpacing(0.12f);
        tag.setSingleLine(true);
        LinearLayout.LayoutParams tagParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tagParams.leftMargin = dpi(10);
        bar.addView(tag, tagParams);

        View spacer = new View(this);
        bar.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        bar.addView(button("TV", 0xFF2B2B40, RogStyle.TEXT, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        }), leftGap(0));

        // Botón pequeño de ajustes (engranaje) en la esquina superior derecha:
        // desde aquí se añaden listas, se abren Xtream y Enlaces, se actualiza el
        // catálogo y se entra en los ajustes del módulo.
        TextView gear = Ui.iconButton(this, Icons.gear(this, RogStyle.TEXT),
                "Ajustes de Películas", 44f);
        gear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showToolsPanel();
            }
        });
        bar.addView(gear, leftGap(8));

        return bar;
    }

    /**
     * PANEL DE AJUSTES DE PELÍCULAS (el botón de engranaje de la esquina).
     *
     * Reúne en un solo sitio todo lo que antes ocupaba la parte de arriba de la
     * pantalla: pegar y añadir una lista, listas Xtream, Enlaces, actualizar el
     * catálogo y los ajustes propios del módulo. Al cerrarlo, la cuadrícula de
     * carteles ocupa casi toda la pantalla.
     */
    private void showToolsPanel() {
        if (toolsDialog != null && toolsDialog.isShowing()) return;

        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        toolsDialog = dialog;
        dialog.setOnDismissListener(new Dialog.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface d) {
                // Las vistas del panel dejan de existir: se sueltan para no
                // guardar una pantalla que ya no se ve.
                toolsDialog = null;
                summaryView = null;
                quickField = null;
            }
        });

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dpi(16), dpi(14), dpi(16), dpi(12));
        GradientDrawable background = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xF2140E14, 0xF20A0609});
        background.setCornerRadius(dpi(14));
        background.setStroke(Math.max(1, dpi(1)), 0x55FFFFFF);
        panel.setBackground(background);

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        panel.addView(titleRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView title = Ui.text(this, "AJUSTES DE PELÍCULAS", 13f, CYAN, true);
        title.setLetterSpacing(0.12f);
        title.setSingleLine(true);
        titleRow.addView(title);

        View titleSpacer = new View(this);
        titleRow.addView(titleSpacer, new LinearLayout.LayoutParams(0, 1, 1f));

        TextView close = Ui.iconButton(this, Icons.close(this, RogStyle.TEXT),
                "Cerrar los ajustes", 44f);
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });
        titleRow.addView(close);

        summaryView = Ui.text(this, summaryText.length() == 0 ? " " : summaryText,
                10.5f, RogStyle.TEXT_DIM, false);
        summaryView.setPadding(dpi(2), dpi(6), dpi(2), 0);
        panel.addView(summaryView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // 1) Añadir una lista pegando su dirección.
        TextView addLabel = Ui.text(this, "AÑADIR UNA LISTA", 10f, RogStyle.TEXT_DIM, true);
        addLabel.setLetterSpacing(0.1f);
        addLabel.setPadding(dpi(2), dpi(14), dpi(2), dpi(2));
        panel.addView(addLabel);

        LinearLayout quick = (LinearLayout) buildQuickAdd();
        panel.addView(quick, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // 2) Listas Xtream, Enlaces, actualizar y ajustes del módulo.
        // --- Editar fuentes (ver / quitar) ---
        TextView fuentesLabel = Ui.text(this, "FUENTES GUARDADAS", 10f, RogStyle.TEXT_DIM, true);
        fuentesLabel.setLetterSpacing(0.1f);
        fuentesLabel.setPadding(dpi(2), dpi(14), dpi(2), dpi(6));
        panel.addView(fuentesLabel);
        try {
            ArrayList<CatalogStore.Repo> saved = CatalogStore.list(this);
            for (int i = 0; i < saved.size(); i++) {
                final CatalogStore.Repo repo = saved.get(i);
                if (repo == null || repo.needsUrl()) continue;
                LinearLayout line = new LinearLayout(this);
                line.setOrientation(LinearLayout.HORIZONTAL);
                line.setGravity(Gravity.CENTER_VERTICAL);
                line.setPadding(dpi(4), dpi(6), dpi(4), dpi(6));
                String repoTitle = repo.title();
                if (repoTitle.length() > 28) repoTitle = repoTitle.substring(0, 26) + "…";
                TextView name = Ui.text(this, repoTitle + " · " + repo.itemCount, 11f, 0xFFE8EEF8, false);
                line.addView(name, new LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
                final boolean en = repo.enabled;
                TextView toggle = Ui.text(this, en ? "On" : "Off", 11f,
                        en ? 0xFF00D4FF : 0xFF8899AA, true);
                toggle.setPadding(dpi(8), dpi(6), dpi(8), dpi(6));
                toggle.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        CatalogStore.setEnabled(MoviesActivity.this, repo.id, !en);
                        dialog.dismiss();
                        rebuildListChips();
                        showToolsPanel();
                    }
                });
                line.addView(toggle);
                TextView del = Ui.text(this, "Quitar", 11f, 0xFFFF8899, true);
                del.setPadding(dpi(10), dpi(6), dpi(10), dpi(6));
                del.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        CatalogStore.remove(MoviesActivity.this, repo.id);
                        dialog.dismiss();
                        if (selectedRepo.equals(repo.title())) {
                            selectedRepo = "Estrenos";
                        }
                        loadSelectedRepoOnly(false);
                        rebuildListChips();
                        toast("Fuente eliminada");
                        showToolsPanel();
                    }
                });
                line.addView(del);
                panel.addView(line);
            }
        } catch (Throwable ignored) {
        }

        TextView moreLabel = Ui.text(this, "CATÁLOGO", 10f, RogStyle.TEXT_DIM, true);
        moreLabel.setLetterSpacing(0.1f);
        moreLabel.setPadding(dpi(2), dpi(16), dpi(2), dpi(6));
        panel.addView(moreLabel);

        HorizontalScrollView scrollRow = new HorizontalScrollView(this);
        scrollRow.setHorizontalScrollBarEnabled(false);
        scrollRow.setVerticalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        row.addView(button("ACTUALIZAR CATÁLOGO", RogStyle.BLUE_BOX, 0xFFFFFFFF,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        dialog.dismiss();
                        refreshAllRepos();
                    }
                }), leftGap(8));

        row.addView(button("AJUSTES DEL MÓDULO", 0xFF2B2B40, RogStyle.TEXT,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        dialog.dismiss();
                        openSettings();
                    }
                }), leftGap(8));

        scrollRow.addView(row, new HorizontalScrollView.LayoutParams(
                HorizontalScrollView.LayoutParams.WRAP_CONTENT,
                HorizontalScrollView.LayoutParams.WRAP_CONTENT));
        panel.addView(scrollRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(panel, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        dialog.setContentView(scroll);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.94f),
                    WindowManager.LayoutParams.WRAP_CONTENT);
        }
        try {
            dialog.show();
        } catch (Throwable t) {
            Diag.add("Películas · no se pudo abrir el panel de ajustes: " + Net.describe(t));
            toast("No se pudieron abrir los ajustes de Películas.");
        }
    }

    /**
     * Barra para añadir una lista completa de IPTV sin salir de PELÍCULAS.
     *
     * Se pega la dirección tal cual (por ejemplo la de get.php con usuario y
     * contraseña, o cualquier .m3u/.m3u8) y al pulsar AGREGAR se guarda y se
     * descarga sola. De la lista se traen únicamente sus películas y sus series:
     * los canales en directo se descartan, porque esos van en la pantalla de TV.
     */
    private View buildQuickAdd() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams boxParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        boxParams.topMargin = dpi(8);
        box.setLayoutParams(boxParams);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        box.addView(row, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        quickField = new EditText(this);
        quickField.setSingleLine(true);
        quickField.setHint("Pega aquí tu lista completa (…/get.php?username=…&password=…)");
        quickField.setHintTextColor(0xFF8E8A90);
        quickField.setTextColor(RogStyle.TEXT);
        quickField.setTextSize(11.5f);
        quickField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        quickField.setBackground(Ui.buttonBg(this, 0xCC120E12, 0xFF1B1B28, 0x33FFFFFF, 6f));
        quickField.setPadding(dpi(12), dpi(8), dpi(12), dpi(8));
        quickField.setMinHeight(dpi(44));
        quickField.setContentDescription("Dirección de la lista de películas y series");
        row.addView(quickField, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        row.addView(button("AGREGAR", 0xFF1F5AA6, 0xFFFFFFFF, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                addQuick();
            }
        }), leftGap(8));

        TextView hint = Ui.text(this, "Acepta la dirección completa de tu lista. Si es de Xtream, "
                + "se leen solo sus películas y series por la API (sin bajar la lista entera); si "
                + "es un .m3u/.m3u8, se leen sus películas y series y se descartan los canales.",
                9.5f, RogStyle.TEXT_DIM, false);
        hint.setMaxLines(2);
        hint.setEllipsize(TextUtils.TruncateAt.END);
        hint.setPadding(dpi(4), dpi(4), dpi(4), 0);
        box.addView(hint);

        return box;
    }

    /** Añade y descarga la lista pegada arriba, en un solo toque. */
    private void addQuick() {
        if (loading) {
            toast("Espera a que termine la descarga anterior.");
            return;
        }
        String raw = quickField == null ? "" : quickField.getText().toString();
        final String url;
        try {
            url = CatalogLoader.normalizeUrl(raw);
        } catch (CatalogLoader.CatalogError error) {
            toast(error.getMessage());
            return;
        }
        if (RepoProbe.isPluginUrl(url)) {
            toast("Esa dirección es un complemento .cs3, no una lista de películas.");
            return;
        }
        if (!Net.online(this)) {
            toast("Sin conexión a Internet. Conéctate y vuelve a pulsar AGREGAR.");
            return;
        }
        // addAny reconoce las listas Xtream: se guardan con sus tres datos y se
        // leen por la API (solo películas y series), sin bajar la lista completa.
        CatalogStore.Repo repo = CatalogStore.addAny(this, url);
        if (quickField != null) quickField.setText("");
        // En el registro del módulo solo se anota el hecho, nunca la dirección
        // con usuario y contraseña.
        Diag.add(repo != null && repo.isXtream()
                ? "Películas · lista Xtream añadida (lectura por API)"
                : "Películas · lista añadida desde la pantalla de películas");
        toast(repo != null && repo.isXtream()
                ? "Lista Xtream añadida. Leyendo sus películas y series por la API…"
                : "Añadido. Descargando la lista…");
        // El panel se cierra para ver la cuadrícula con el aviso de descarga.
        if (toolsDialog != null && toolsDialog.isShowing()) {
            try {
                toolsDialog.dismiss();
            } catch (Throwable ignored) {
            }
        }
        refreshRepo(repo, true);
    }

    private LinearLayout.LayoutParams leftGap(int dp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.leftMargin = dpi(dp);
        return params;
    }

    private TextView button(String label, int fill, int textColor,
                           View.OnClickListener click) {
        TextView button = Ui.text(this, label, 11f, textColor, true);
        button.setGravity(Gravity.CENTER);
        button.setSingleLine(true);
        button.setLetterSpacing(0.05f);
        button.setPadding(dpi(13), dpi(9), dpi(13), dpi(9));
        button.setMinHeight(dpi(42));
        button.setMinWidth(dpi(56));
        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.RECTANGLE);
        background.setColor(fill);
        background.setCornerRadius(dpi(6));
        background.setStroke(Math.max(1, dpi(1)), 0x33FFFFFF);
        button.setBackground(background);
        button.setClickable(true);
        button.setFocusable(true);
        button.setOnClickListener(click);
        button.setContentDescription(label);
        return button;
    }

    private View buildSearchRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowParams.topMargin = dpi(8);
        row.setLayoutParams(rowParams);

        searchField = new EditText(this);
        searchField.setSingleLine(true);
        searchField.setHint("Buscar película o serie…");
        searchField.setHintTextColor(0xFF8E8A90);
        searchField.setTextColor(RogStyle.TEXT);
        searchField.setTextSize(12f);
        searchField.setInputType(InputType.TYPE_CLASS_TEXT);
        searchField.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        searchField.setBackground(Ui.buttonBg(this, 0xCC120E12, 0xFF1B1B28, 0x33FFFFFF, 20f));
        searchField.setPadding(dpi(14), dpi(8), dpi(14), dpi(8));
        searchField.setContentDescription("Buscar película o serie");
        searchField.setMinHeight(dpi(44));
        searchField.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                searchText = s == null ? "" : s.toString().trim().toLowerCase(Locale.US);
                applyFilter();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        row.addView(searchField, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView clear = button("LIMPIAR", 0xFF2B2B40, RogStyle.TEXT,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (searchField != null) searchField.setText("");
                    }
                });
        row.addView(clear, leftGap(8));

        return row;
    }

    private View buildChips() {
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        scrollParams.topMargin = dpi(7);
        scroll.setLayoutParams(scrollParams);

        chipsRow = new LinearLayout(this);
        chipsRow.setOrientation(LinearLayout.HORIZONTAL);
        chipsRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams chipsParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        chipsParams.topMargin = dpi(8);
        chipsRow.setLayoutParams(chipsParams);
        scroll.addView(chipsRow, new HorizontalScrollView.LayoutParams(
                HorizontalScrollView.LayoutParams.WRAP_CONTENT,
                HorizontalScrollView.LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    private TextView chip(String label, boolean selected, View.OnClickListener click) {
        TextView view = Ui.text(this, label, 10.5f, selected ? 0xFFFFFFFF : RogStyle.TEXT, selected);
        view.setGravity(Gravity.CENTER);
        view.setSingleLine(true);
        view.setPadding(dpi(13), dpi(8), dpi(13), dpi(8));
        view.setMinHeight(dpi(38));
        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.RECTANGLE);
        background.setColor(selected ? RogStyle.BLUE_BOX : 0x8C150E12);
        background.setCornerRadius(dpi(18));
        if (selected) background.setStroke(Math.max(1, dpi(1)), RogStyle.BLUE_BOX_EDGE);
        view.setBackground(background);
        view.setClickable(true);
        view.setFocusable(true);
        view.setContentDescription(label);
        view.setOnClickListener(click);
        return view;
    }

    private int countOf(String kind, String genre) {
        int total = 0;
        for (int i = 0; i < allItems.size(); i++) {
            MovieItem item = allItems.get(i);
            if (item == null) continue;
            if (MovieItem.KIND_MOVIE.equals(kind) && item.isSeries()) continue;
            if (MovieItem.KIND_SERIES.equals(kind) && !item.isSeries()) continue;
            if (genre != null && genre.length() > 0) {
                boolean found = false;
                for (int g = 0; g < item.genres.size(); g++) {
                    if (item.genres.get(g).equalsIgnoreCase(genre)) {
                        found = true;
                        break;
                    }
                }
                if (!found) continue;
            }
            total++;
        }
        return total;
    }

    private void rebuildChips() {
        if (chipsRow == null) return;
        chipsRow.removeAllViews();
        boolean allSelected = selectedKind.length() == 0 && selectedGenre.length() == 0;
        chipsRow.addView(chip("Todo · " + allItems.size(), allSelected,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        selectedKind = "";
                        selectedGenre = "";
                        rebuildChips();
                        applyFilter();
                    }
                }), leftGap(0));

        chipsRow.addView(chip("Películas · " + countOf(MovieItem.KIND_MOVIE, ""),
                MovieItem.KIND_MOVIE.equals(selectedKind), new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        selectedKind = MovieItem.KIND_MOVIE;
                        selectedGenre = "";
                        rebuildChips();
                        applyFilter();
                    }
                }), leftGap(6));

        int seriesCount = countOf(MovieItem.KIND_SERIES, "");
        if (seriesCount > 0) {
            chipsRow.addView(chip("Series · " + seriesCount,
                    MovieItem.KIND_SERIES.equals(selectedKind), new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            selectedKind = MovieItem.KIND_SERIES;
                            selectedGenre = "";
                            rebuildChips();
                            applyFilter();
                        }
                    }), leftGap(6));
        }

        ArrayList<String> genres = new ArrayList<String>();
        for (int i = 0; i < allItems.size(); i++) {
            MovieItem item = allItems.get(i);
            for (int g = 0; g < item.genres.size(); g++) {
                String genre = item.genres.get(g);
                if (genre.length() > 0 && genre.length() <= 26 && !genres.contains(genre)) {
                    genres.add(genre);
                }
                if (genres.size() >= 14) break;
            }
            if (genres.size() >= 14) break;
        }
        for (int i = 0; i < genres.size(); i++) {
            final String genre = genres.get(i);
            chipsRow.addView(chip(genre, genre.equalsIgnoreCase(selectedGenre),
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            selectedKind = "";
                            selectedGenre = genre;
                            rebuildChips();
                            applyFilter();
                        }
                    }), leftGap(6));
        }
    }

    /** Portada de bienvenida: se ve cuando no hay ningún título que enseñar. */
    private LinearLayout buildWelcomeCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dpi(24), dpi(18), dpi(24), dpi(18));
        GradientDrawable background = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xF2140E14, 0xF20A0609});
        background.setCornerRadius(dpi(14));
        background.setStroke(Math.max(1, dpi(1)), 0x44FFFFFF);
        card.setBackground(background);

        TextView badge = Ui.text(this, "LENY TV · CINE Y SERIES", 10f, CYAN, true);
        badge.setLetterSpacing(0.16f);
        card.addView(badge);

        TextView title = Ui.text(this, "Tu catálogo empieza aquí", 21f, RogStyle.TEXT, true);
        title.setPadding(0, dpi(6), 0, 0);
        title.setGravity(Gravity.CENTER);
        card.addView(title);

        TextView body = Ui.text(this, "Toca el botón de ajustes ⚙ de la esquina superior derecha "
                + "y pega ahí la dirección completa de tu lista (por ejemplo "
                + "…/get.php?username=…&password=…&type=m3u_plus). "
                + "Si es de Xtream, la app pregunta solo por sus películas y series a la API, sin "
                + "descargar la lista entera; y si es un .m3u, se queda con sus películas y "
                + "series. Los episodios de cada serie se cargan al abrirla.\n"
                + "Los episodios de cada serie se cargan al abrirla.",
                11.5f, 0xFFE4DCDC, false);
        body.setGravity(Gravity.CENTER);
        body.setLineSpacing(0f, 1.2f);
        body.setPadding(0, dpi(8), 0, 0);
        body.setMaxWidth(dpi(520));
        card.addView(body);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER);

        actions.addView(button("AÑADIR LISTA", RogStyle.BLUE_BOX, 0xFFFFFFFF,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showToolsPanel();
                    }
                }), leftGap(0));

        // En pantallas estrechas los botones no se cortan: se deslizan.
        HorizontalScrollView actionsScroll = new HorizontalScrollView(this);
        actionsScroll.setHorizontalScrollBarEnabled(false);
        actionsScroll.setVerticalScrollBarEnabled(false);
        actionsScroll.addView(actions, new HorizontalScrollView.LayoutParams(
                HorizontalScrollView.LayoutParams.WRAP_CONTENT,
                HorizontalScrollView.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        actionsParams.topMargin = dpi(14);
        card.addView(actionsScroll, actionsParams);

        return card;
    }

    // -------------------------------------------------------------------- datos

    /** Lee el catálogo de demostración y las fuentes guardadas (sin Internet). */
    private void reloadLocal() {
        ArrayList<MovieItem> items = new ArrayList<MovieItem>();
        // El catálogo de demostración está OCULTO por defecto: sus datos siguen en
        // el proyecto (assets/movies/demo-catalog.json), pero de entrada no se
        // enseña nada que no venga de una fuente real del usuario.
        boolean demoShown = MoviesPrefs.showDemo(this);
        if (demoShown) items.addAll(CatalogLoader.demo(this));

        ArrayList<CatalogStore.Repo> repos = CatalogStore.list(this);
        int activeRepos = 0;
        int waiting = 0;
        int xtreamLists = 0;
        for (int i = 0; i < repos.size(); i++) {
            CatalogStore.Repo repo = repos.get(i);
            if (repo == null) continue;
            if (repo.isXtream()) xtreamLists++;
            if (!repo.isXtream() && repo.preset && repo.needsUrl()) waiting++;
            if (!repo.enabled || repo.needsUrl()) continue;
            activeRepos++;
            items.addAll(CatalogLoader.fromRepo(repo));
        }

        // No mezclar listas en memoria: solo la pestaña activa
        summaryText = activeRepos + (activeRepos == 1 ? " fuente" : " fuentes")
                + (xtreamLists > 0 ? " · " + xtreamLists + " Xtream" : "");
        if (summaryView != null) summaryView.setText(summaryText);
        rebuildChips();
        loadSelectedRepoOnly(false);
    }

    private void applyFilter() {
        rebuildListChips();
        // Si no hay lista elegida, la primera disponible
        if (selectedRepo.length() == 0) {
            for (int i = 0; i < allItems.size(); i++) {
                if (allItems.get(i) != null && allItems.get(i).repoName != null
                        && allItems.get(i).repoName.length() > 0) {
                    selectedRepo = allItems.get(i).repoName;
                    break;
                }
            }
        }
        shownItems.clear();
        for (int i = 0; i < allItems.size(); i++) {
            MovieItem item = allItems.get(i);
            if (item == null) continue;
            // Solo VOD ya viene filtrado al cargar; aquí: lista elegida + búsqueda
            if (selectedRepo.length() > 0) {
                String rn = item.repoName == null ? "" : item.repoName;
                if (!selectedRepo.equals(rn)) continue;
            }
            // selectedKind vacío = películas y series; si se fuerza, respeta
            if (MovieItem.KIND_MOVIE.equals(selectedKind) && item.isSeries()) continue;
            if (MovieItem.KIND_SERIES.equals(selectedKind) && !item.isSeries()) continue;
            if (selectedGenre.length() > 0) {
                boolean found = false;
                for (int g = 0; g < item.genres.size(); g++) {
                    if (item.genres.get(g).equalsIgnoreCase(selectedGenre)) {
                        found = true;
                        break;
                    }
                }
                if (!found) continue;
            }
            if (searchText.length() > 0 && item.searchKey().indexOf(searchText) < 0) continue;
            shownItems.add(item);
        }
        if (adapter != null) adapter.notifyDataSetChanged();
        rebuildCatalogRows();
        rebuildSidebarCategories();
        updateStatus();
    }

    private void rebuildListChips() {
        if (listChips == null) return;
        listChips.removeAllViews();
        // Orden fijo: Estrenos → Series → 1Playcool → CanalesVen → resto
        final String[] preferred = new String[]{
                "Estrenos", "Series", "Servidor 1", "Servidor 2"
        };
        ArrayList<CatalogStore.Repo> ordered = new ArrayList<CatalogStore.Repo>();
        try {
            ArrayList<CatalogStore.Repo> saved = CatalogStore.list(this);
            boolean[] used = new boolean[saved.size()];
            for (int p = 0; p < preferred.length; p++) {
                for (int i = 0; i < saved.size(); i++) {
                    CatalogStore.Repo r = saved.get(i);
                    if (r == null || !r.enabled || r.needsUrl()) continue;
                    String title = r.title();
                    if (preferred[p].equalsIgnoreCase(title)
                            || ("Estrenos".equals(preferred[p]) && title != null
                                && title.toLowerCase(java.util.Locale.US).indexOf("estreno") >= 0)
                            || ("Series".equals(preferred[p]) && title != null
                                && title.toLowerCase(java.util.Locale.US).startsWith("series"))
                            || ("Servidor 1".equals(preferred[p]) && title != null
                                && (title.indexOf("CanalesVen") >= 0 || title.indexOf("cdn-abc") >= 0))
                            || ("Servidor 2".equals(preferred[p]) && title != null
                                && (title.indexOf("1play") >= 0 || title.indexOf("1Play") >= 0
                                    || title.indexOf("Playcool") >= 0))) {
                        ordered.add(r);
                        used[i] = true;
                        break;
                    }
                }
            }
            for (int i = 0; i < saved.size(); i++) {
                if (used[i]) continue;
                CatalogStore.Repo r = saved.get(i);
                if (r == null || !r.enabled || r.needsUrl()) continue;
                // Ocultar presets vacíos sin URL real de VOD
                if (r.preset && r.itemCount == 0 && (r.url == null || r.url.length() < 8)) {
                    continue;
                }
                ordered.add(r);
            }
        } catch (Throwable ignored) {
        }
        if (ordered.isEmpty()) return;
        boolean valid = false;
        for (int i = 0; i < ordered.size(); i++) {
            if (ordered.get(i).title().equals(selectedRepo)) {
                valid = true;
                break;
            }
        }
        if (!valid) selectedRepo = ordered.get(0).title();
        for (int i = 0; i < ordered.size(); i++) {
            final CatalogStore.Repo repo = ordered.get(i);
            final String key = repo.title();
            boolean on = key.equals(selectedRepo);
            String label = key;
            if (label.length() > 22) label = label.substring(0, 20) + "…";
            TextView chip = Ui.text(this, label, 11f, on ? 0xFF001018 : 0xFFE8EEF8, true);
            chip.setPadding(dpi(12), dpi(8), dpi(12), dpi(8));
            chip.setBackground(rounded(on ? CYAN : 0xFF1A2230, 16f));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dpi(8);
            chip.setLayoutParams(lp);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    selectedRepo = key;
                    loadSelectedRepoOnly(false);
                }
            });
            listChips.addView(chip);
        }
    }

    /** Carga en memoria solo la lista de la pestaña activa. */
    private void loadSelectedRepoOnly(boolean forceDownload) {
        CatalogStore.Repo target = null;
        try {
            ArrayList<CatalogStore.Repo> saved = CatalogStore.list(this);
            for (int i = 0; i < saved.size(); i++) {
                CatalogStore.Repo r = saved.get(i);
                if (r != null && r.enabled && selectedRepo.equals(r.title())) {
                    target = r;
                    break;
                }
            }
            if (target == null && !saved.isEmpty()) {
                for (int i = 0; i < saved.size(); i++) {
                    if (saved.get(i) != null && saved.get(i).enabled) {
                        target = saved.get(i);
                        selectedRepo = target.title();
                        break;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        if (target == null) {
            allItems.clear();
            applyFilter();
            return;
        }
        // Mostrar caché local de ESA lista solamente
        allItems.clear();
        try {
            allItems.addAll(CatalogLoader.fromRepo(target));
            // Asegurar repoName correcto
            for (int i = 0; i < allItems.size(); i++) {
                if (allItems.get(i) != null) {
                    allItems.get(i).repoName = target.title();
                }
            }
        } catch (Throwable ignored) {
        }
        applyFilter();
        // Solo descargar si no hay caché (o el usuario fuerza). No bajar otras listas.
        if (forceDownload || !target.hasItems() || target.itemCount <= 0) {
            refreshRepo(target, false);
        }
    }



    private void rebuildCatalogRows() {
        if (rowsContainer == null) return;
        rowsContainer.removeAllViews();
        // Destacadas: priorizar estrenos / años recientes
        ArrayList<MovieItem> ordered = new ArrayList<MovieItem>(shownItems);
        java.util.Collections.sort(ordered, new java.util.Comparator<MovieItem>() {
            @Override
            public int compare(MovieItem a, MovieItem b) {
                int pa = premiereScore(a);
                int pb = premiereScore(b);
                if (pa != pb) return pb - pa;
                int ya = yearNum(a);
                int yb = yearNum(b);
                if (ya != yb) return yb - ya;
                return 0;
            }
        });
        featured = ordered.isEmpty() ? null : ordered.get(0);
        updateHero();
        if (ordered.isEmpty()) return;

        int destCount = Math.min(24, ordered.size());
        ArrayList<MovieItem> dest = new ArrayList<MovieItem>();
        for (int i = 0; i < destCount; i++) dest.add(ordered.get(i));
        rowsContainer.addView(buildRow("Destacadas", dest));

        java.util.LinkedHashMap<String, ArrayList<MovieItem>> byGenre =
                new java.util.LinkedHashMap<String, ArrayList<MovieItem>>();
        for (int i = 0; i < ordered.size(); i++) {
            MovieItem item = ordered.get(i);
            if (item.genres == null || item.genres.isEmpty()) {
                ArrayList<MovieItem> other = byGenre.get("Otros");
                if (other == null) {
                    other = new ArrayList<MovieItem>();
                    byGenre.put("Otros", other);
                }
                other.add(item);
                continue;
            }
            for (int g = 0; g < item.genres.size(); g++) {
                String genre = item.genres.get(g);
                if (genre == null || genre.trim().length() == 0) continue;
                ArrayList<MovieItem> list = byGenre.get(genre);
                if (list == null) {
                    list = new ArrayList<MovieItem>();
                    byGenre.put(genre, list);
                }
                if (!list.contains(item)) list.add(item);
            }
        }
        // Estrenos primero en filas de categorías
        ArrayList<String> genreKeys = new ArrayList<String>(byGenre.keySet());
        java.util.Collections.sort(genreKeys, new java.util.Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                int pa = genrePremiereScore(a);
                int pb = genrePremiereScore(b);
                if (pa != pb) return pb - pa;
                return a.compareToIgnoreCase(b);
            }
        });
        int rows = 0;
        for (int k = 0; k < genreKeys.size(); k++) {
            if (rows >= 20) break;
            String key = genreKeys.get(k);
            ArrayList<MovieItem> list = byGenre.get(key);
            if (list == null || list.isEmpty()) continue;
            // Estrenos: mostrar la fila completa (sin cortar tan agresivo)
            boolean isPremiere = genrePremiereScore(key) > 0;
            int maxInRow = isPremiere ? Math.min(list.size(), 80) : Math.min(list.size(), 40);
            if (list.size() > maxInRow) {
                ArrayList<MovieItem> cut = new ArrayList<MovieItem>();
                for (int i = 0; i < maxInRow; i++) cut.add(list.get(i));
                list = cut;
            }
            if (list.size() < 1) continue;
            rowsContainer.addView(buildRow(key, list));
            rows++;
        }
        if (rows == 0 && ordered.size() > destCount) {
            ArrayList<MovieItem> rest = new ArrayList<MovieItem>();
            for (int i = destCount; i < Math.min(ordered.size(), destCount + 40); i++) {
                rest.add(ordered.get(i));
            }
            if (!rest.isEmpty()) rowsContainer.addView(buildRow("Más títulos", rest));
        }
    }

    private static int yearNum(MovieItem item) {
        if (item == null || item.year == null) return 0;
        try {
            String y = item.year.trim();
            if (y.length() >= 4) return Integer.parseInt(y.substring(0, 4));
        } catch (Throwable ignored) {
        }
        return 0;
    }

    /** Mayor = más prioritario (estrenos / año actual). */
    private static int premiereScore(MovieItem item) {
        if (item == null) return 0;
        int score = 0;
        int y = yearNum(item);
        if (y >= 2027) score += 300;
        else if (y >= 2026) score += 250;
        else if (y >= 2025) score += 150;
        else if (y >= 2024) score += 80;
        if (item.genres != null) {
            for (int i = 0; i < item.genres.size(); i++) {
                score += genrePremiereScore(item.genres.get(i));
            }
        }
        String t = item.title == null ? "" : item.title.toLowerCase(java.util.Locale.US);
        if (t.indexOf("estreno") >= 0) score += 100;
        return score;
    }

    private static int genrePremiereScore(String genre) {
        if (genre == null) return 0;
        String g = genre.toLowerCase(java.util.Locale.US);
        int score = 0;
        if (g.indexOf("estreno") >= 0) score += 500;
        if (g.indexOf("2027") >= 0) score += 400;
        if (g.indexOf("2026") >= 0) score += 350;
        if (g.indexOf("2025") >= 0) score += 200;
        if (g.indexOf("nuevo") >= 0 || g.indexOf("recent") >= 0) score += 80;
        return score;
    }


    private void updateHero() {
        if (heroTitle == null) return;
        if (featured == null) {
            heroTitle.setText("Catálogo Leny");
            heroPlot.setText("Añade listas M3U / Xtream en Ajustes para llenar el catálogo.");
            heroBadge.setText("PELÍCULAS");
            if (heroPoster != null) heroPoster.setImageDrawable(null);
            return;
        }
        heroTitle.setText(featured.title);
        String plot = featured.synopsis;
        if (plot == null || plot.length() == 0) plot = featured.metaLine();
        if (plot == null || plot.length() == 0) {
            plot = featured.isSeries() ? "Serie" : "Película";
            if (featured.year != null && featured.year.length() > 0) plot += " · " + featured.year;
        }
        heroPlot.setText(plot);
        heroBadge.setText(featured.isSeries() ? "SERIE" : "PELÍCULA");
        if (heroPoster != null) {
            PosterLoader.load(this, featured.poster, heroPoster, featured.title);
        }
    }

    private View buildRow(String title, final ArrayList<MovieItem> items) {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams blockLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        blockLp.topMargin = dpi(14);
        block.setLayoutParams(blockLp);

        TextView head = Ui.text(this, title, 14f, 0xFFE8EEF8, true);
        head.setPadding(dpi(4), 0, dpi(4), dpi(8));
        block.addView(head);

        HorizontalScrollView hsv = new HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < items.size(); i++) {
            final MovieItem item = items.get(i);
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dpi(4), 0, dpi(4), 0);
            FrameLayout posterBox = new FrameLayout(this);
            ImageView poster = new ImageView(this);
            poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
            poster.setBackgroundColor(0xFF1A2030);
            posterBox.addView(poster, new FrameLayout.LayoutParams(dpi(110), dpi(160)));
            TextView badge = Ui.text(this, item.isSeries() ? "SERIE" : "PELÍ", 8f, 0xFFFFFFFF, true);
            badge.setBackgroundColor(item.isSeries() ? 0xD9A855F7 : 0xD91F5AA6);
            badge.setPadding(dpi(4), dpi(2), dpi(4), dpi(2));
            FrameLayout.LayoutParams badgeLp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.START | Gravity.TOP);
            badgeLp.leftMargin = dpi(4);
            badgeLp.topMargin = dpi(4);
            posterBox.addView(badge, badgeLp);
            card.addView(posterBox);
            TextView name = Ui.text(this, item.title, 10f, 0xFFE8EEF8, true);
            name.setMaxLines(2);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setPadding(0, dpi(4), 0, 0);
            card.addView(name, new LinearLayout.LayoutParams(dpi(110),
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            PosterLoader.load(this, item.poster, poster, item.title);
            card.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showDetail(item);
                }
            });
            row.addView(card);
        }
        hsv.addView(row);
        block.addView(hsv);
        return block;
    }


    private void updateStatus() {
        if (loadingBox != null) loadingBox.setVisibility(loading ? View.VISIBLE : View.GONE);
        if (loadingText != null && loading) loadingText.setText("Actualizando las listas…");

        boolean empty = shownItems.isEmpty();
        if (catalogScroll != null) {
            catalogScroll.setVisibility(empty && !loading ? View.GONE : View.VISIBLE);
        }
        if (grid != null) grid.setVisibility(View.GONE);
        if (emptyBox != null) {
            emptyBox.setVisibility(empty && !loading ? View.VISIBLE : View.GONE);
        }
        if (countView != null) {
            if (empty) {
                countView.setText("");
            } else {
                countView.setText(shownItems.size()
                        + (shownItems.size() == 1 ? " título en pantalla" : " títulos en pantalla")
                        + (loading ? " · actualizando…" : ""));
            }
        }
    }

    /** Vuelve a descargar las fuentes guardadas que están viejas. */
    private void refreshStaleRepos() {
        // Desactivado a propósito: solo se descarga la pestaña que el usuario elige.
        autoRefreshDone = true;
    }

    private void refreshAllRepos() {
        ArrayList<CatalogStore.Repo> repos = CatalogStore.list(this);
        ArrayList<CatalogStore.Repo> enabled = new ArrayList<CatalogStore.Repo>();
        for (int i = 0; i < repos.size(); i++) {
            if (repos.get(i) != null && repos.get(i).enabled && !repos.get(i).needsUrl()) {
                enabled.add(repos.get(i));
            }
        }
        if (enabled.isEmpty()) {
            toast("Todavía no has añadido ninguna lista. Usa Ajustes y pega el enlace M3U.");
            return;
        }
        for (int i = 0; i < enabled.size(); i++) refreshRepo(enabled.get(i), true);
    }

    /** Descarga una fuente concreta (JSON o Xtream). El error se guarda y se enseña. */
    private void refreshRepo(final CatalogStore.Repo repo, final boolean showResult) {
        if (repo == null) return;
        loading = true;
        updateStatus();
        try {
            pool.execute(new Runnable() {
                @Override
                public void run() {
                    String problem = null;
                    CatalogLoader.Loaded loaded = null;
                    try {
                        loaded = CatalogLoader.fetchRepo(MoviesActivity.this, repo);
                    } catch (Throwable t) {
                        problem = t.getMessage() == null ? Net.describe(t) : t.getMessage();
                    }
                    final CatalogLoader.Loaded result = loaded;
                    final String failure = problem;
                    // Protegido: si algo falla al pintar el resultado, queda en el
                    // registro del módulo y la pantalla sigue viva.
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                loading = false;
                                if (result != null) {
                                    CatalogStore.setLoaded(MoviesActivity.this, repo.id, result.name,
                                            result.itemsJson, result.count);
                                    if (result.note != null && result.note.length() > 0) {
                                        // El informe (películas, series, canales ignorados) se
                                        // guarda con la fuente: se ve en ENLACES y aquí.
                                        CatalogStore.setStatus(MoviesActivity.this, repo.id,
                                                result.note);
                                    }
                                    if (showResult) {
                                        String detail = result.note == null || result.note.length() == 0
                                                ? "" : " · " + result.note;
                                        if (detail.length() > 170) {
                                            detail = detail.substring(0, 170) + "…";
                                        }
                                        toast(repo.title() + ": " + result.count
                                                + (result.count == 1 ? " título" : " títulos")
                                                + detail);
                                    }
                                } else {
                                    CatalogStore.setStatus(MoviesActivity.this, repo.id,
                                            "Error: " + failure);
                                    if (showResult) {
                                        toast(repo.title() + ": no se pudo actualizar");
                                    }
                                }
                                reloadLocal();
                            } catch (Throwable t) {
                                loading = false;
                                Diag.add("Películas · fallo al mostrar la actualización: "
                                        + Net.describe(t));
                            }
                        }
                    });
                }
            });
        } catch (Throwable t) {
            loading = false;
            updateStatus();
            toast("No se pudo actualizar la lista.");
        }
    }

    private void openRepos() {
        try {
            startActivity(new Intent(this, RepoActivity.class));
        } catch (Throwable t) {
            toast("No se pudo abrir ENLACES.");
        }
    }

    /** Abre las listas Xtream (servidor, usuario y contraseña). */
    private void openXtream() {
        try {
            startActivity(new Intent(this, XtreamActivity.class));
        } catch (Throwable t) {
            toast("No se pudo abrir la pantalla de listas Xtream.");
        }
    }

    /** Abre los ajustes PROPIOS del módulo (cabeceras, imagen, registro, adaptadores). */
    private void openSettings() {
        try {
            startActivity(new Intent(this, MoviesSettingsActivity.class));
        } catch (Throwable t) {
            toast("No se pudieron abrir los ajustes del módulo.");
        }
    }

    // -------------------------------------------------------------------- ficha

    private void showDetail(final MovieItem item) {
        if (item == null) return;
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dpi(16), dpi(14), dpi(16), dpi(14));
        GradientDrawable background = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xF2140E14, 0xF20A0609});
        background.setCornerRadius(dpi(14));
        background.setStroke(Math.max(1, dpi(1)), 0x55FFFFFF);
        panel.setBackground(background);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);

        FrameLayout posterBox = new FrameLayout(this);
        ImageView poster = new ImageView(this);
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable posterBg = new GradientDrawable();
        posterBg.setShape(GradientDrawable.RECTANGLE);
        posterBg.setColor(0xFF1B1B28);
        posterBg.setCornerRadius(dpi(10));
        poster.setBackground(posterBg);
        PosterLoader.load(this, item.poster, poster, item.title);
        poster.setContentDescription("Cartel de " + item.title);
        posterBox.addView(poster, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        if (item.rating > 0d) {
            TextView rating = Ui.text(this, String.format(Locale.US, "★ %.1f", item.rating),
                    10.5f, 0xFFFFFFFF, true);
            rating.setPadding(dpi(7), dpi(3), dpi(7), dpi(3));
            GradientDrawable ratingBg = new GradientDrawable();
            ratingBg.setShape(GradientDrawable.RECTANGLE);
            ratingBg.setColor(0xE61F5AA6);
            ratingBg.setCornerRadius(dpi(6));
            rating.setBackground(ratingBg);
            FrameLayout.LayoutParams ratingParams = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.END | Gravity.TOP);
            ratingParams.topMargin = dpi(6);
            ratingParams.rightMargin = dpi(6);
            posterBox.addView(rating, ratingParams);
        }
        top.addView(posterBox, new LinearLayout.LayoutParams(dpi(126), dpi(188)));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams infoParams = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        infoParams.leftMargin = dpi(14);
        top.addView(info, infoParams);

        TextView title = Ui.text(this, item.title, 20f, RogStyle.TEXT, true);
        title.setMaxLines(3);
        info.addView(title);

        TextView kindBadge = Ui.text(this, item.kindLabel().toUpperCase(Locale.US), 9.5f,
                item.isSeries() ? 0xFFA855F7 : CYAN, true);
        kindBadge.setLetterSpacing(0.14f);
        kindBadge.setPadding(0, dpi(5), 0, 0);
        info.addView(kindBadge);

        TextView meta = Ui.text(this, item.metaLine(), 11.5f, 0xFFE4DCDC, false);
        meta.setPadding(0, dpi(3), 0, 0);
        info.addView(meta);

        if (item.repoName.length() > 0) {
            TextView origin = Ui.text(this, "Origen: " + item.repoName, 10f, RogStyle.TEXT_DIM,
                    false);
            origin.setPadding(0, dpi(4), 0, 0);
            info.addView(origin);
        }

        if (item.synopsis.length() > 0) {
            TextView synopsis = Ui.text(this, item.synopsis, 12f, 0xFFE4DCDC, false);
            synopsis.setPadding(0, dpi(9), 0, 0);
            synopsis.setLineSpacing(0f, 1.14f);
            synopsis.setMaxLines(7);
            synopsis.setEllipsize(TextUtils.TruncateAt.END);
            info.addView(synopsis);
        }

        // Serie de una lista Xtream: sus temporadas y episodios se piden al servidor
        // al abrir esta ficha (nunca al cargar la lista entera).
        final boolean lazySeries = item.isSeries() && item.seasons.isEmpty()
                && item.resolveUrl != null && item.resolveUrl.length() > 0;

        if (item.isSeries()) {
            String seasonsText = lazySeries
                    ? "Temporadas y episodios: se piden al servidor al abrir esta ficha."
                    : item.seasons.size() + (item.seasons.size() == 1
                    ? " temporada · " : " temporadas · ") + item.episodeCount() + " episodios";
            TextView seasonsInfo = Ui.text(this, seasonsText, 10.5f, RogStyle.TEXT_DIM, false);
            seasonsInfo.setPadding(0, dpi(7), 0, 0);
            info.addView(seasonsInfo);
        }

        panel.addView(top);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        panel.addView(body);

        if (item.isSeries()) {
            if (lazySeries) {
                loadEpisodes(item, body, dialog);
            } else {
                buildSeriesBody(body, dialog, item);
            }
        } else {
            buildMovieBody(body, dialog, item);
        }

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.END);
        LinearLayout.LayoutParams bottomParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        bottomParams.topMargin = dpi(14);
        panel.addView(bottom, bottomParams);

        TextView close = button("CERRAR", 0xFF2B2B40, RogStyle.TEXT, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });
        bottom.addView(close);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(panel, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        dialog.setContentView(scroll);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.88f),
                    WindowManager.LayoutParams.WRAP_CONTENT);
        }
        try {
            dialog.show();
        } catch (Throwable t) {
            toast("No se pudo abrir la ficha.");
        }
    }

    private void buildMovieBody(LinearLayout panel, final Dialog dialog, final MovieItem item) {
        ArrayList<MovieItem.Source> sources = new ArrayList<MovieItem.Source>();
        for (int i = 0; i < item.sources.size(); i++) {
            if (item.sources.get(i).usable()) sources.add(item.sources.get(i));
        }
        if (sources.isEmpty()) {
            TextView warning = Ui.text(this, "Este título no tiene ningún enlace reproducible "
                    + "en la lista.", 11.5f, Ui.WARN, false);
            warning.setPadding(0, dpi(12), 0, 0);
            panel.addView(warning);
            return;
        }

        TextView label = Ui.text(this, sources.size() == 1 ? "ENLACE" : "ELIGE UN ENLACE", 10f,
                RogStyle.TEXT_DIM, true);
        label.setLetterSpacing(0.1f);
        label.setPadding(0, dpi(14), 0, dpi(6));
        panel.addView(label);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < sources.size() && i < 4; i++) {
            final MovieItem.Source source = sources.get(i);
            TextView play = button("REPRODUCIR " + source.label.toUpperCase(Locale.US),
                    0xFF1F5AA6, 0xFFFFFFFF, new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            play(source.url, item.title, source.type, item);
                            dialog.dismiss();
                        }
                    });
            row.addView(play, leftGap(i == 0 ? 0 : 8));
        }
        if (sources.size() > 4) {
            TextView more = Ui.text(this, "+" + (sources.size() - 4) + " enlaces más en la lista",
                    10f, RogStyle.TEXT_DIM, false);
            LinearLayout.LayoutParams moreParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            moreParams.leftMargin = dpi(10);
            row.addView(more, moreParams);
        }
        panel.addView(row);
    }

    private void buildSeriesBody(LinearLayout panel, final Dialog dialog, final MovieItem item) {
        if (item.seasons.isEmpty()) {
            TextView warning = Ui.text(this, "Esta serie no trae temporadas ni episodios en la "
                    + "lista.", 11.5f, Ui.WARN, false);
            warning.setPadding(0, dpi(12), 0, 0);
            panel.addView(warning);
            return;
        }

        TextView label = Ui.text(this, "TEMPORADAS", 10f, RogStyle.TEXT_DIM, true);
        label.setLetterSpacing(0.1f);
        label.setPadding(0, dpi(14), 0, dpi(6));
        panel.addView(label);

        final LinearLayout episodesBox = new LinearLayout(this);
        episodesBox.setOrientation(LinearLayout.VERTICAL);

        LinearLayout seasonsRow = new LinearLayout(this);
        seasonsRow.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < item.seasons.size(); i++) {
            final int index = i;
            final MovieItem.Season season = item.seasons.get(i);
            TextView button = button(season.label().toUpperCase(Locale.US),
                    i == 0 ? RogStyle.BLUE_BOX : 0xFF2B2B40, RogStyle.TEXT,
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            fillEpisodes(episodesBox, dialog, item, index);
                        }
                    });
            seasonsRow.addView(button, leftGap(i == 0 ? 0 : 8));
        }
        panel.addView(seasonsRow);

        TextView episodeLabel = Ui.text(this, "EPISODIOS", 10f, RogStyle.TEXT_DIM, true);
        episodeLabel.setLetterSpacing(0.1f);
        episodeLabel.setPadding(0, dpi(12), 0, dpi(4));
        panel.addView(episodeLabel);
        panel.addView(episodesBox);

        fillEpisodes(episodesBox, dialog, item, 0);
    }

    /**
     * Pide al servidor las temporadas y los episodios de UNA serie (solo las de
     * Xtream). Mientras llegan se ve un aviso de carga; si el servidor falla, se
     * explica en la ficha sin cerrar nada. Los episodios quedan en memoria para
     * poder reproducirlos en el mismo sitio.
     */
    private void loadEpisodes(final MovieItem item, final LinearLayout box, final Dialog dialog) {
        final TextView loading = Ui.text(this, "Cargando temporadas y episodios…", 11.5f,
                RogStyle.TEXT_DIM, false);
        loading.setPadding(0, dpi(10), 0, dpi(4));
        box.addView(loading);
        final Runnable work = new Runnable() {
            @Override
            public void run() {
                String problem = null;
                ArrayList<MovieItem.Season> seasons = null;
                try {
                    seasons = XtreamClient.fetchEpisodes(MoviesActivity.this, item.resolveUrl);
                } catch (Throwable t) {
                    problem = t.getMessage() == null ? Net.describe(t) : t.getMessage();
                }
                final ArrayList<MovieItem.Season> result = seasons;
                final String failure = problem;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            box.removeAllViews();
                            if (result != null && !result.isEmpty()) {
                                item.seasons.clear();
                                item.seasons.addAll(result);
                                buildSeriesBody(box, dialog, item);
                                if (adapter != null) adapter.notifyDataSetChanged();
                                Diag.add("Películas · episodios cargados de una serie: "
                                        + item.seasons.size() + " temporadas");
                            } else {
                                TextView warning = Ui.text(MoviesActivity.this,
                                        "No se pudieron cargar los episodios de esta serie. "
                                                + (failure == null ? "" : failure), 11.5f,
                                        Ui.WARN, false);
                                warning.setPadding(0, dpi(10), 0, 0);
                                box.addView(warning);
                                Diag.add("Películas · sin episodios de una serie: "
                                        + (failure == null ? "respuesta vacía" : failure));
                            }
                        } catch (Throwable t) {
                            box.removeAllViews();
                            box.addView(Ui.text(MoviesActivity.this,
                                    "No se pudieron mostrar los episodios de esta serie.", 11.5f,
                                    Ui.WARN, false));
                            Diag.add("Películas · fallo al mostrar episodios: " + Net.describe(t));
                        }
                    }
                });
            }
        };
        try {
            pool.execute(work);
        } catch (Throwable t) {
            box.removeAllViews();
            TextView warning = Ui.text(this, "No se pudieron cargar los episodios de esta serie.",
                    11.5f, Ui.WARN, false);
            warning.setPadding(0, dpi(10), 0, 0);
            box.addView(warning);
        }
    }

    private void fillEpisodes(final LinearLayout box, final Dialog dialog, final MovieItem item,
                              int seasonIndex) {
        box.removeAllViews();
        if (seasonIndex < 0 || seasonIndex >= item.seasons.size()) return;
        MovieItem.Season season = item.seasons.get(seasonIndex);
        if (season.episodes.isEmpty()) {
            box.addView(Ui.text(this, "Esta temporada no tiene episodios.", 11.5f, Ui.WARN, false));
            return;
        }
        for (int i = 0; i < season.episodes.size() && i < 120; i++) {
            final MovieItem.Episode episode = season.episodes.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackground(Ui.buttonBg(this, 0x991A1420, 0xCC2B2B40, 0x22FFFFFF, 6f));
            row.setPadding(dpi(10), dpi(8), dpi(10), dpi(8));
            row.setClickable(true);
            row.setFocusable(true);

            TextView number = Ui.text(this, "E" + episode.number, 11f, CYAN, true);
            number.setMinWidth(dpi(40));
            row.addView(number);

            TextView name = Ui.text(this, episode.label(), 11.5f, RogStyle.TEXT, false);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(name, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            TextView play = Ui.text(this, "REPRODUCIR", 10f, 0xFFFFFFFF, true);
            play.setPadding(dpi(10), dpi(6), dpi(10), dpi(6));
            GradientDrawable playBg = new GradientDrawable();
            playBg.setShape(GradientDrawable.RECTANGLE);
            playBg.setColor(0xFF1F5AA6);
            playBg.setCornerRadius(dpi(5));
            play.setBackground(playBg);
            row.addView(play, leftGap(8));

            final ArrayList<MovieItem.Source> sources = episode.sources;
            View.OnClickListener click = new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    for (int s = 0; s < sources.size(); s++) {
                        if (sources.get(s).usable()) {
                            play(sources.get(s).url, item.title + " · " + episode.label(),
                                    sources.get(s).type);
                            dialog.dismiss();
                            return;
                        }
                    }
                    toast("Ese episodio no tiene enlace reproducible.");
                }
            };
            row.setOnClickListener(click);
            play.setOnClickListener(click);

            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rowParams.topMargin = dpi(6);
            box.addView(row, rowParams);
        }
        if (season.episodes.size() > 120) {
            TextView more = Ui.text(this, "(se muestran los primeros 120 episodios)", 10f,
                    RogStyle.TEXT_DIM, false);
            more.setPadding(0, dpi(6), 0, 0);
            box.addView(more);
        }
    }

    // -------------------------------------------------------------- reproducir

    private void play(String url, String title, String type) {
        play(url, title, type, null);
    }

    private void play(String url, String title, String type, MovieItem item) {
        if (url == null || url.trim().length() == 0) {
            toast("Ese enlace está vacío.");
            return;
        }
        final String cleanUrl = url.trim();
        final String cleanType = type == null ? "" : type.trim();
        // Reproductor de TV + opción Audio solo en películas
        try {
            Intent intent = new Intent(this, PlayerActivity.class);
            intent.putExtra(PlayerActivity.EXTRA_MODE, PlayerActivity.MODE_SINGLE);
            intent.putExtra(PlayerActivity.EXTRA_URL, cleanUrl);
            intent.putExtra(PlayerActivity.EXTRA_NAME, title == null ? "" : title);
            intent.putExtra(PlayerActivity.EXTRA_MOVIES_AUDIO, true);
            if (cleanType.length() > 0) {
                intent.putExtra(PlayerActivity.EXTRA_TYPE, cleanType);
            }
            String headersSpec = Headers.toSpec(MoviesPrefs.headers(this));
            if (headersSpec.length() > 0) {
                intent.putExtra(PlayerActivity.EXTRA_HEADERS, headersSpec);
            }
            MoviesPrefs.setLastUrl(this, cleanUrl);
            startActivity(intent);
            return;
        } catch (Throwable t) {
            Diag.add("Películas · no se pudo abrir el reproductor de TV: " + Net.describe(t));
            toast("No se pudo abrir el reproductor: " + Net.describe(t));
        }
    }

    private void toast(String message) {
        try {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) {
        }
    }

    private int dpi(float value) {
        return Ui.dpi(this, value);
    }

    /** Último recurso si el dibujo falla: la app no se cierra, avisa y deja volver. */
    private void showEmergencyScreen() {
        try {
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setGravity(Gravity.CENTER);
            box.setBackgroundColor(0xFF08080F);
            box.setPadding(dpi(24), dpi(24), dpi(24), dpi(24));
            TextView message = Ui.text(this, "No se pudo dibujar la pantalla de películas.\n"
                    + "Vuelve a la televisión y prueba otra vez.", 14f, RogStyle.TEXT, false);
            message.setGravity(Gravity.CENTER);
            box.addView(message);
            TextView back = button("VOLVER A TV", RogStyle.BLUE_BOX, 0xFFFFFFFF,
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            finish();
                        }
                    });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            params.topMargin = dpi(14);
            box.addView(back, params);
            setContentView(box);
        } catch (Throwable ignored) {
        }
    }

    // ---------------------------------------------------------------- cuadrícula

    /**
     * Tarjeta de la cuadrícula: cartel con su distintivo (PELÍCULA o SERIE), la
     * nota si la tiene, el título y una línea de datos.
     */
    private final class PosterAdapter extends BaseAdapter {

        private final Context ctx;
        private final ArrayList<MovieItem> data;

        PosterAdapter(Context ctx, ArrayList<MovieItem> data) {
            this.ctx = ctx;
            this.data = data;
        }

        @Override
        public int getCount() {
            return data == null ? 0 : data.size();
        }

        @Override
        public Object getItem(int position) {
            return data == null || position < 0 || position >= data.size() ? null : data.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout card;
            ImageView poster;
            TextView badge;
            TextView rating;
            TextView name;
            TextView meta;
            if (convertView instanceof LinearLayout && convertView.getTag() instanceof Object[]) {
                card = (LinearLayout) convertView;
                Object[] holder = (Object[]) card.getTag();
                poster = (ImageView) holder[0];
                badge = (TextView) holder[1];
                rating = (TextView) holder[2];
                name = (TextView) holder[3];
                meta = (TextView) holder[4];
            } else {
                card = new LinearLayout(ctx);
                card.setOrientation(LinearLayout.VERTICAL);
                card.setPadding(dpi(4), dpi(4), dpi(4), dpi(6));
                GradientDrawable cardBg = new GradientDrawable();
                cardBg.setShape(GradientDrawable.RECTANGLE);
                cardBg.setColor(BG_CARD);
                cardBg.setCornerRadius(dpi(10));
                cardBg.setStroke(Math.max(1, dpi(1)), 0x22FFFFFF);
                card.setBackground(cardBg);

                FrameLayout posterBox = new FrameLayout(ctx);
                poster = new ImageView(ctx);
                poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
                GradientDrawable posterBg = new GradientDrawable();
                posterBg.setShape(GradientDrawable.RECTANGLE);
                posterBg.setColor(0xFF1B1B28);
                posterBg.setCornerRadius(dpi(8));
                poster.setBackground(posterBg);
                posterBox.addView(poster, new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT));
                card.addView(posterBox, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dpi(146)));

                badge = Ui.text(ctx, "", 8.5f, 0xFFFFFFFF, true);
                badge.setLetterSpacing(0.1f);
                badge.setPadding(dpi(6), dpi(2), dpi(6), dpi(2));
                GradientDrawable badgeBg = new GradientDrawable();
                badgeBg.setShape(GradientDrawable.RECTANGLE);
                badgeBg.setColor(0xD91F5AA6);
                badgeBg.setCornerRadius(dpi(5));
                badge.setBackground(badgeBg);
                FrameLayout.LayoutParams badgeParams = new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.START | Gravity.TOP);
                badgeParams.topMargin = dpi(5);
                badgeParams.leftMargin = dpi(5);
                posterBox.addView(badge, badgeParams);

                rating = Ui.text(ctx, "", 8.5f, 0xFFF5D06F, true);
                rating.setPadding(dpi(5), dpi(2), dpi(5), dpi(2));
                GradientDrawable ratingBg = new GradientDrawable();
                ratingBg.setShape(GradientDrawable.RECTANGLE);
                ratingBg.setColor(0xD90A0609);
                ratingBg.setCornerRadius(dpi(5));
                rating.setBackground(ratingBg);
                FrameLayout.LayoutParams ratingParams = new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.END | Gravity.TOP);
                ratingParams.topMargin = dpi(5);
                ratingParams.rightMargin = dpi(5);
                posterBox.addView(rating, ratingParams);

                name = Ui.text(ctx, "", 10.5f, RogStyle.TEXT, true);
                name.setMaxLines(2);
                name.setEllipsize(TextUtils.TruncateAt.END);
                LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                nameParams.topMargin = dpi(5);
                nameParams.leftMargin = dpi(2);
                nameParams.rightMargin = dpi(2);
                card.addView(name, nameParams);

                meta = Ui.text(ctx, "", 9f, RogStyle.TEXT_DIM, false);
                meta.setMaxLines(1);
                meta.setEllipsize(TextUtils.TruncateAt.END);
                meta.setPadding(dpi(2), 0, dpi(2), 0);
                card.addView(meta);

                card.setTag(new Object[]{poster, badge, rating, name, meta});
            }

            MovieItem item = data.get(position);
            name.setText(item.title);
            badge.setText(item.isSeries() ? "SERIE" : "PELÍCULA");
            badge.setBackgroundColor(item.isSeries() ? 0xD9A855F7 : 0xD91F5AA6);
            if (item.rating > 0d) {
                rating.setText(String.format(Locale.US, "★ %.1f", item.rating));
                rating.setVisibility(View.VISIBLE);
            } else {
                rating.setVisibility(View.GONE);
            }
            String metaText;
            if (item.isSeries() && item.seasons.isEmpty()) {
                // Serie de Xtream: los episodios se piden al abrir su ficha.
                metaText = "Toca para ver episodios";
            } else if (item.isSeries()) {
                metaText = item.seasons.size() + " temp. · " + item.episodeCount() + " ep.";
            } else {
                metaText = item.metaLine();
            }
            meta.setText(metaText);
            poster.setImageDrawable(null);
            PosterLoader.load(ctx, item.poster, poster, item.title);
            card.setContentDescription(item.title + " · " + item.kindLabel()
                    + (item.year.length() > 0 ? " · " + item.year : ""));
            return card;
        }
    }
}
