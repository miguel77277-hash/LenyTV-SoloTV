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
    private GridView grid;
    private PosterAdapter adapter;
    private LinearLayout emptyBox;
    private LinearLayout loadingBox;
    private TextView loadingText;
    private TextView countView;
    private EditText searchField;
    private EditText quickField;
    private TextView summaryView;
    /** Último informe de fuentes (se enseña en el panel de ajustes). */
    private String summaryText = "";
    /** Panel de ajustes de la pantalla (listas, enlaces y ajustes del módulo). */
    private Dialog toolsDialog;

    private String selectedKind = "";
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
            buildUi();
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
            reloadLocal();
            refreshStaleRepos();
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
        FrameLayout root = new FrameLayout(this);
        RogStyle.applyBackdrop(this, root);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dpi(10), dpi(8), dpi(10), dpi(6));
        root.addView(column, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        column.addView(buildHeader(), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        // El buscador y los filtros se quedan a la vista (es lo que más se usa).
        // Todo lo demás (añadir listas, Xtream, Enlaces, actualizar y ajustes del
        // módulo) vive en el panel del botón de ajustes de la esquina superior.
        column.addView(buildSearchRow(), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        column.addView(buildChips(), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        FrameLayout content = new FrameLayout(this);
        LinearLayout.LayoutParams contentParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        contentParams.topMargin = dpi(6);
        column.addView(content, contentParams);

        grid = new GridView(this);
        grid.setNumColumns(GridView.AUTO_FIT);
        grid.setColumnWidth(dpi(112));
        grid.setHorizontalSpacing(dpi(8));
        grid.setVerticalSpacing(dpi(10));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setPadding(dpi(2), dpi(2), dpi(2), dpi(8));
        grid.setClipToPadding(false);
        grid.setVerticalScrollBarEnabled(false);
        grid.setSelector(Ui.buttonBg(this, 0x00000000, 0x33FFFFFF, 0x00000000, 8f));
        grid.setFocusable(true);
        adapter = new PosterAdapter(this, shownItems);
        grid.setAdapter(adapter);
        grid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (position < 0 || position >= shownItems.size()) return;
                showDetail(shownItems.get(position));
            }
        });
        content.addView(grid, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        emptyBox = buildWelcomeCard();
        emptyBox.setVisibility(View.GONE);
        content.addView(emptyBox, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));

        loadingBox = new LinearLayout(this);
        loadingBox.setOrientation(LinearLayout.VERTICAL);
        loadingBox.setGravity(Gravity.CENTER);
        loadingBox.setPadding(dpi(22), dpi(16), dpi(22), dpi(16));
        loadingBox.setBackground(Ui.buttonBg(this, 0xF2120E14, 0xF2120E14, 0x44FFFFFF, 10f));
        ProgressBar spinner = new ProgressBar(this);
        loadingBox.addView(spinner, new LinearLayout.LayoutParams(dpi(34), dpi(34)));
        loadingText = Ui.text(this, "Actualizando…", 11.5f, RogStyle.TEXT_DIM, false);
        loadingText.setGravity(Gravity.CENTER);
        loadingText.setPadding(0, dpi(8), 0, 0);
        loadingBox.addView(loadingText);
        loadingBox.setVisibility(View.GONE);
        content.addView(loadingBox, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));

        countView = Ui.text(this, "", 10.5f, RogStyle.TEXT_DIM, false);
        countView.setPadding(dpi(4), dpi(2), dpi(4), 0);
        column.addView(countView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        setContentView(root);
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
        TextView moreLabel = Ui.text(this, "MÁS OPCIONES", 10f, RogStyle.TEXT_DIM, true);
        moreLabel.setLetterSpacing(0.1f);
        moreLabel.setPadding(dpi(2), dpi(16), dpi(2), dpi(6));
        panel.addView(moreLabel);

        HorizontalScrollView scrollRow = new HorizontalScrollView(this);
        scrollRow.setHorizontalScrollBarEnabled(false);
        scrollRow.setVerticalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        row.addView(button("LISTAS XTREAM", 0xFF1F5AA6, 0xFFFFFFFF,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        dialog.dismiss();
                        openXtream();
                    }
                }), leftGap(0));

        row.addView(button("ENLACES", 0xFF2B2B40, RogStyle.TEXT, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
                openRepos();
            }
        }), leftGap(8));

        row.addView(button("ACTUALIZAR TODO", RogStyle.BLUE_BOX, 0xFFFFFFFF,
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
                + "También puedes añadir un catálogo JSON o un proveedor desde ENLACES.",
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

        actions.addView(button("LISTAS XTREAM", 0xFF2B2B40, RogStyle.TEXT,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        openXtream();
                    }
                }), leftGap(8));

        actions.addView(button("ENLACES", 0xFF2B2B40, RogStyle.TEXT,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        openRepos();
                    }
                }), leftGap(8));

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

        allItems.clear();
        allItems.addAll(items);
        StringBuilder summary = new StringBuilder();
        summary.append(activeRepos).append(activeRepos == 1 ? " fuente activa" : " fuentes activas");
        if (xtreamLists > 0) {
            summary.append(" · ").append(xtreamLists)
                    .append(xtreamLists == 1 ? " lista Xtream" : " listas Xtream");
        }
        if (demoShown) summary.append(" · demostración visible");
        if (waiting > 0) {
            summary.append(" · ").append(waiting)
                    .append(waiting == 1 ? " proveedor sin dirección" : " proveedores sin dirección");
        }
        summaryText = summary.toString();
        if (summaryView != null) summaryView.setText(summaryText);
        rebuildChips();
        applyFilter();
    }

    private void applyFilter() {
        if (adapter == null) return;
        shownItems.clear();
        for (int i = 0; i < allItems.size(); i++) {
            MovieItem item = allItems.get(i);
            if (item == null) continue;
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
        adapter.notifyDataSetChanged();
        updateStatus();
    }

    private void updateStatus() {
        if (grid == null) return;
        if (loadingBox != null) loadingBox.setVisibility(loading ? View.VISIBLE : View.GONE);
        if (loadingText != null && loading) loadingText.setText("Actualizando las listas…");

        boolean empty = shownItems.isEmpty();
        grid.setVisibility(empty ? View.GONE : View.VISIBLE);
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
        if (autoRefreshDone) return;
        autoRefreshDone = true;
        ArrayList<CatalogStore.Repo> repos = CatalogStore.list(this);
        for (int i = 0; i < repos.size(); i++) {
            CatalogStore.Repo repo = repos.get(i);
            if (repo == null || !repo.enabled || repo.needsUrl()) continue;
            if (repo.stale() || !repo.hasItems()) {
                refreshRepo(repo, false);
                return;
            }
        }
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
            toast("Todavía no has añadido ninguna lista. Usa el botón de ajustes ⚙ "
                    + "(LISTAS XTREAM o ENLACES).");
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
                            play(source.url, item.title, source.type);
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
        if (url == null || url.trim().length() == 0) {
            toast("Ese enlace está vacío.");
            return;
        }
        final String cleanUrl = url.trim();
        final String cleanType = type == null ? "" : type.trim();
        // 1) EL MISMO REPRODUCTOR QUE LA TELEVISIÓN.
        //    Se abre PlayerActivity (la pantalla de reproducción de la TV) en modo
        //    de enlace único: así la película o el episodio se ven con los MISMOS
        //    controles, el mismo panel de ajustes, la misma estética y el mismo
        //    motor que un canal. No se duplica nada: es literalmente su pantalla.
        //    Lo único que se le pasa es el enlace, el título y las cabeceras del
        //    módulo (User-Agent, Referer...) como cabeceras propias de este
        //    enlace, para que el servidor de la película las siga recibiendo.
        try {
            Intent intent = new Intent(this, PlayerActivity.class);
            intent.putExtra(PlayerActivity.EXTRA_MODE, PlayerActivity.MODE_SINGLE);
            intent.putExtra(PlayerActivity.EXTRA_URL, cleanUrl);
            intent.putExtra(PlayerActivity.EXTRA_NAME, title == null ? "" : title);
            if (cleanType.length() > 0) {
                intent.putExtra(PlayerActivity.EXTRA_TYPE, cleanType);
            }
            String headersSpec = Headers.toSpec(MoviesPrefs.headers(this));
            if (headersSpec.length() > 0) {
                intent.putExtra(PlayerActivity.EXTRA_HEADERS, headersSpec);
            }
            MoviesPrefs.setLastUrl(this, cleanUrl);
            startActivity(intent);
            Diag.add("Películas · reproduciendo con el reproductor de la TV: "
                    + Diag.channelUrl(cleanUrl));
            return;
        } catch (Throwable t) {
            // La app no se cierra: queda anotado y se prueba el reproductor de
            // reserva del módulo.
            Diag.add("Películas · no se pudo abrir el reproductor de la TV: " + Net.describe(t));
        }
        // 2) RESERVA: reproductor propio del módulo. Solo se llega aquí si el de
        //    la TV no se pudo abrir; se avisa para que no parezca lo mismo.
        try {
            Intent fallback = new Intent(this, MoviesPlayerActivity.class);
            fallback.putExtra(MoviesPlayerActivity.EXTRA_URL, cleanUrl);
            fallback.putExtra(MoviesPlayerActivity.EXTRA_NAME, title);
            if (cleanType.length() > 0) {
                fallback.putExtra(MoviesPlayerActivity.EXTRA_TYPE, cleanType);
            }
            MoviesPrefs.setLastUrl(this, cleanUrl);
            startActivity(fallback);
            toast("Se abre el reproductor de reserva de Películas.");
        } catch (Throwable t) {
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
